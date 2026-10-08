package dev.picasso.ops.site

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.picasso.mimic.cli.MimicCli
import dev.picasso.mimic.engine.TaskState
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant

/**
 * 셀 대역의 고정 픽스처(S3a 스펙 §6.3). 제시 자리는 늘 점유이고 자재가 바뀌지 않는다(공급이 끝나지 않는다).
 * 슬롯은 처음에 비어 있다.
 *
 * @param presentations 제시 자리 id 와 그 자리의 자재.
 * @param slots 슬롯 id. 순서가 `GET /cell` 의 순서다.
 */
data class CellFixture(val presentations: Map<String, String>, val slots: List<String>) {
    init {
        require(slots.distinct().size == slots.size) { "슬롯 id 가 겹친다: $slots" }
        require(presentations.keys.none { it in slots }) { "제시 자리와 슬롯이 같은 id 를 쓴다" }
    }

    companion object {
        /** 런처와 시험이 쓰는 세트. 슬롯 넷이 PrepareSequencedRack 작업 지시 하나의 단위 수 상한이다. */
        val STANDARD = CellFixture(
            presentations = linkedMapOf("SEQ-IN-02.BIN-A" to "ENGINE-COVER-A"),
            slots = listOf("RACK-204.S01", "RACK-204.S02", "RACK-204.S03", "RACK-204.S04"),
        )
    }
}

/**
 * 셀 대역의 자리 하나. [observedAt] 이 `null` 이면 시각을 주지 않는 신호이고 읽은 순간이 그 시각이다(제시 자리).
 * 슬롯은 채운 가상 시각을 든다.
 */
data class CellPlace(val id: String, val occupied: Boolean, val material: String?, val observedAt: Instant?)

/** 셀 대역의 한 순간. 불변이며 훑기마다 통째로 갈아 끼운다. `GET /cell` 의 본문 모양이다. */
data class CellSnapshot(val presentations: List<CellPlace>, val slots: List<CellPlace>)

/**
 * 셀 대역(S3a 스펙 §6.3, 결정 6, T4). 기체가 보고한 배치에서 슬롯을 채우는 **대역**이며 독립 설비 확인이 아니다.
 *
 * ## 훑기
 *
 * [scan] 은 시계를 민 직후마다 mimic 엔진 잠금(`MimicServer.exclusive`) 아래에서 기체들의 태스크를 훑는다. 새로
 * `SUCCEEDED` 가 된 `pick_place` 를 찾으면 그 `destination` 슬롯을 «점유, 자재 = `object_id` 제시 자리의 자재, 관측 시각
 * = 지금 가상 시각» 으로 채운다. 같은 태스크는 한 번만 처리한다. 슬롯을 비우지 않는다(스펙 §12).
 *
 * 발행 경로를 감싸지 않는 이유: 감싸면 mimic 의 전송 장애 주입(S4)에 셀 대역이 같이 걸린다. 시계를 미는 쪽이 현장이므로
 * 민 직후에 엔진을 직접 읽는 것이 장애와 무관하다.
 *
 * `object_id` 가 제시 자리가 아니면 자재를 모르는 채 점유로 채운다(기체가 무언가 놓았다는 보고는 맞으므로).
 * `destination` 이 픽스처의 슬롯이 아니면 셀 밖이라 무시한다.
 *
 * ## 내는 곳
 *
 * 루프백 JDK `HttpServer` 의 `GET /cell` 하나다. 처리 스레드는 [snapshot] 만 읽고 엔진에 닿지 않는다.
 *
 * @param port 0 이면 무작위(시험).
 */
class SiteCell(
    private val mimic: MimicCli.Started,
    private val fixture: CellFixture = CellFixture.STANDARD,
    port: Int = 0,
) : AutoCloseable {

    /** 이미 처리한 태스크. 기체마다 태스크 id 공간이 따로이므로 쌍으로 든다. */
    private val seen = mutableSetOf<Pair<String, String>>()

    @Volatile
    var snapshot: CellSnapshot = CellSnapshot(
        presentations = fixture.presentations.map { (id, material) -> CellPlace(id, true, material, null) },
        slots = fixture.slots.map { CellPlace(it, false, null, null) },
    )
        private set

    private val json = ObjectMapper()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0).apply {
        createContext("/cell", ::handle)
        start()
    }

    /** 열린 포트. 시험이 0 을 주면 여기서 읽는다. */
    val port: Int get() = server.address.port

    /** 시계를 민 직후에 부른다. 엔진 잠금은 재진입되므로 이미 잠금 안에서 불러도 된다. */
    fun scan() = mimic.server.exclusive {
        val filled = snapshot.slots.associateBy { it.id }.toMutableMap()
        var changed = false
        for (robotId in mimic.robotIds.sorted()) {
            val instance = mimic.instance(robotId) ?: continue
            val now = instance.clock.now()
            for (task in instance.tasks.all) {
                if (task.skillType != PICK_PLACE || task.machine.state != TaskState.SUCCEEDED) continue
                if (!seen.add(robotId to task.taskId)) continue
                val parameters = task.machine.parameters.associate { it.key to it.stringValue }
                val destination = parameters[P_DESTINATION] ?: continue
                if (destination !in filled) continue
                val material = parameters[P_OBJECT]?.let { fixture.presentations[it] }
                filled[destination] = CellPlace(destination, true, material, now)
                changed = true
            }
        }
        if (changed) {
            snapshot = snapshot.copy(slots = fixture.slots.map { filled.getValue(it) })
        }
    }

    private fun handle(exchange: HttpExchange) = try {
        respond(exchange)
    } finally {
        exchange.close()
    }

    private fun respond(exchange: HttpExchange) {
        val status: Int
        val body: ByteArray
        when {
            exchange.requestURI.path != "/cell" -> {
                status = 404
                body = ByteArray(0)
            }
            exchange.requestMethod != "GET" -> {
                exchange.responseHeaders.add("Allow", "GET")
                status = 405
                body = ByteArray(0)
            }
            else -> {
                exchange.responseHeaders.add("Content-Type", "application/json")
                status = 200
                body = json.writeValueAsBytes(wire(snapshot))
            }
        }
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.write(body)
    }

    override fun close() = server.stop(0)

    /**
     * 본문 모양. 시각은 ISO-8601 문자열이다. Jackson 의 날짜 모듈에 기대지 않고 여기서 적는다. 모듈이 클래스패스에서
     * 빠지면 시각이 숫자로 바뀌어 읽는 쪽(실행 호스트)이 조용히 못 읽는다.
     */
    private fun wire(snapshot: CellSnapshot): Map<String, Any> = linkedMapOf(
        "presentations" to snapshot.presentations.map(::wirePlace),
        "slots" to snapshot.slots.map(::wirePlace),
    )

    private fun wirePlace(place: CellPlace): Map<String, Any?> = linkedMapOf(
        "id" to place.id,
        "occupied" to place.occupied,
        "material" to place.material,
        "observedAt" to place.observedAt?.toString(),
    )

    private companion object {
        /** 계약 카탈로그의 스킬 이름과 파라미터 키. */
        const val PICK_PLACE = "pick_place"
        const val P_OBJECT = "object_id"
        const val P_DESTINATION = "destination"
    }
}
