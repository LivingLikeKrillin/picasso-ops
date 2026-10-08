package dev.picasso.ops.host.cell

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.CellSignals
import dev.picasso.middleware.NamedSignal
import dev.picasso.middleware.SlotSignal
import dev.picasso.middleware.mission.SignalKind
import dev.picasso.middleware.mission.SignalSpec
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * 셀 대역의 자리 하나(현장 `GET /cell` 의 원소). [observedAt] 이 `null` 이면 시각을 주지 않는 신호이고 읽은 순간이 그
 * 시각이다(제시 자리). 슬롯은 채운 가상 시각을 든다.
 */
data class CellPlace(val id: String, val occupied: Boolean, val material: String?, val observedAt: Instant?)

/**
 * 이름 있는 신호 하나(현장 `GET /cell` 의 `signals` 원소, S3b 스펙 §5.2). 사양 칸 넷과 지금 값, 관측 시각이다. 값은 종류와
 * 상관없이 문자열이다. [observedAt] 이 `null` 이면 현장이 시각을 주지 않은 처음 값이고 읽은 순간이 그 시각이다.
 */
data class CellSignal(
    val name: String,
    val location: String?,
    val kind: SignalKind,
    val safety: Boolean,
    val value: String,
    val observedAt: Instant?,
) {
    /** 신호 사양의 한 줄. 검증기가 이것을 받는다(T7). 본문 칸이 아니므로 속성이 아니라 함수다. */
    fun spec(): SignalSpec = SignalSpec(name, location, kind, safety)
}

/**
 * 셀 대역의 한 순간(현장 `GET /cell` 의 본문). 제시 자리와 슬롯을 가른다.
 *
 * [signals] 가 `null` 이면 현장 본문에 `signals` 칸이 없었다(신호를 선언하지 않는 셀 대역). 빈 목록과 다르다. 빈 목록은
 * «신호가 하나도 없다» 는 사양이고 `null` 은 «신호 사양을 모른다» 다. 검증은 `null` 을 «못 읽음» 으로 다룬다(T7).
 */
data class CellSnapshot(val presentations: List<CellPlace>, val slots: List<CellPlace>, val signals: List<CellSignal>? = null) {
    /** 신호 사양. 모르면 `null` 이다. 본문 칸이 아니므로 속성이 아니라 함수다(`GET /host/cell` 이 이 모양을 그대로 낸다). */
    fun signalSpecs(): List<SignalSpec>? = signals?.map { it.spec() }
}

/** 신호 조작을 현장에 넘긴 결과. 현장이 답하면 그 상태 코드와 본문 그대로다. */
data class SignalRelay(val status: Int, val contentType: String?, val body: ByteArray)

/**
 * 현장의 셀 대역을 루프백 HTTP 로 읽는다(S3a 스펙 §7.3). 실패하면 `null`(스냅숏 없음)이다. 못 읽은 것을 빈 셀로 접으면
 * 미들웨어가 «말이 없다» 를 «비었다» 로 읽는다.
 *
 * 신호 조작([writeSignal])도 이것으로 현장에 넘긴다(S3b 스펙 §6.6). 판정은 현장이 하고 호스트는 응답을 그대로 돌려준다.
 *
 * @param baseUrl 현장 셀 대역의 주소. 경로 `/cell` 을 붙여 부른다.
 * @param timeout 연결 제한과 셀 대역 읽기의 요청 제한
 * @param writeTimeout 신호 조작의 요청 제한. [SIGNAL_WRITE_TIMEOUT] 의 까닭을 본다
 */
class CellBandClient(
    baseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(1),
    private val writeTimeout: Duration = SIGNAL_WRITE_TIMEOUT,
) {

    private val base = baseUrl.trimEnd('/')
    private val uri = URI.create("$base/cell")
    private val http = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val json = ObjectMapper()

    fun fetch(): CellSnapshot? = try {
        val response = http.send(
            HttpRequest.newBuilder(uri).timeout(timeout).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (response.statusCode() == 200) parse(json.readTree(response.body())) else null
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: Exception) {
        null
    }

    /**
     * `POST /cell/signals/{name}` 을 현장에 그대로 넘긴다. 현장이 안 닿으면(연결 실패, 시간 초과) `null` 이다. 이름은 경로
     * 조각으로 인코딩한다. 판정(404·400·403)은 현장의 몫이다.
     */
    fun writeSignal(name: String, body: ByteArray): SignalRelay? = try {
        val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")
        val response = http.send(
            HttpRequest.newBuilder(URI.create("$base/cell/signals/$encoded"))
                .timeout(writeTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        SignalRelay(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null), response.body())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: Exception) {
        null
    }

    companion object {
        /**
         * 신호 조작의 요청 제한. 현장의 신호 쓰기는 mimic 엔진 잠금을 기다리고, 엔진은 그 잠금 아래에서 registry 로 태스크
         * 관측을 적재할 수 있다(요청 제한 3초). 1초에서 끊으면 현장이 곧 반영할 쓰기를 «현장이 안 닿음»(503)으로 돌려주게
         * 된다. 운영 서비스의 호스트 요청 제한(5초)보다는 짧아야 호스트의 503 이 운영 서비스에 닿는다.
         */
        val SIGNAL_WRITE_TIMEOUT: Duration = Duration.ofSeconds(4)

        /** 본문 모양이 어긋나면 예외다. [fetch] 가 그것을 «스냅숏 없음» 으로 접는다. `signals` 칸이 없으면 신호 사양을 모른다. */
        fun parse(node: JsonNode): CellSnapshot =
            CellSnapshot(places(node, "presentations"), places(node, "slots"), node.get("signals")?.let(::signals))

        private fun signals(node: JsonNode): List<CellSignal> {
            require(node.isArray) { "signals 가 배열이 아니다" }
            return node.map { signal ->
                CellSignal(
                    name = requireNotNull(signal.get("name")?.takeIf { it.isTextual && it.asText().isNotEmpty() }) { "신호 이름이 없다" }.asText(),
                    location = signal.get("location")?.takeIf { it.isTextual }?.asText(),
                    kind = requireNotNull(signal.get("kind")?.takeIf { it.isTextual }) { "신호 종류가 없다" }.asText()
                        .let { kind -> SignalKind.entries.firstOrNull { it.name == kind } ?: throw IllegalArgumentException("모르는 신호 종류다: $kind") },
                    safety = requireNotNull(signal.get("safety")?.takeIf { it.isBoolean }) { "safety 가 없다" }.asBoolean(),
                    value = requireNotNull(signal.get("value")?.takeIf { it.isTextual }) { "신호 값이 문자열이 아니다" }.asText(),
                    observedAt = signal.get("observedAt")?.takeIf { it.isTextual }?.asText()?.let(Instant::parse),
                )
            }
        }

        private fun places(node: JsonNode, field: String): List<CellPlace> {
            val array = requireNotNull(node.get(field)?.takeIf { it.isArray }) { "$field 가 배열이 아니다" }
            return array.map { place ->
                CellPlace(
                    id = requireNotNull(place.get("id")?.takeIf { it.isTextual }) { "자리 id 가 없다" }.asText(),
                    occupied = requireNotNull(place.get("occupied")?.takeIf { it.isBoolean }) { "occupied 가 없다" }.asBoolean(),
                    material = place.get("material")?.takeIf { it.isTextual }?.asText(),
                    observedAt = place.get("observedAt")?.takeIf { it.isTextual }?.asText()?.let(Instant::parse),
                )
            }
        }
    }
}

/**
 * 셀 대역 스냅숏 위의 [CellSignals](S3a 스펙 §7.4). 호스트가 pump 직전에 [snapshot] 을 갈아 끼우고, 미들웨어는 같은 잠금
 * 아래에서 읽는다.
 *
 * - [observe]: 스냅숏에서 자리 id 로 답한다. 스냅숏에 없는 자리는 `null`(말이 없다)이다.
 * - [holding]: 그 자재를 든 **제시 자리**만 낸다. 채운 슬롯을 대안 자리로 내면 미들웨어가 막 놓은 자재를 다시 집으러 보낸다.
 * - [signal]: 스냅숏의 신호에서 이름으로 값과 관측 시각을 낸다(S3b 스펙 §6.6). 모르는 이름은 `null`(못 읽었다)이고
 *   설비 대기는 그때 기한까지 기다린다.
 *
 * 스냅숏이 없으면 셋 모두 `null`(못 물어봄)이다.
 */
class CellBandSignals : CellSignals {

    var snapshot: CellSnapshot? = null

    override fun observe(location: String): SlotSignal? {
        val current = snapshot ?: return null
        val place = (current.presentations.asSequence() + current.slots.asSequence()).firstOrNull { it.id == location }
            ?: return null
        return SlotSignal(place.occupied, place.material, place.observedAt)
    }

    override fun holding(material: String): List<String>? =
        snapshot?.presentations?.filter { it.occupied && it.material == material }?.map { it.id }

    override fun signal(name: String): NamedSignal? =
        snapshot?.signals?.firstOrNull { it.name == name }?.let { NamedSignal(it.value, it.observedAt) }
}
