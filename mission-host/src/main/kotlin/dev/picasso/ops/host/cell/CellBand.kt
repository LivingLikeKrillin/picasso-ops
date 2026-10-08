package dev.picasso.ops.host.cell

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.CellSignals
import dev.picasso.middleware.SlotSignal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * 셀 대역의 자리 하나(현장 `GET /cell` 의 원소). [observedAt] 이 `null` 이면 시각을 주지 않는 신호이고 읽은 순간이 그
 * 시각이다(제시 자리). 슬롯은 채운 가상 시각을 든다.
 */
data class CellPlace(val id: String, val occupied: Boolean, val material: String?, val observedAt: Instant?)

/** 셀 대역의 한 순간(현장 `GET /cell` 의 본문). 제시 자리와 슬롯을 가른다. */
data class CellSnapshot(val presentations: List<CellPlace>, val slots: List<CellPlace>)

/**
 * 현장의 셀 대역을 루프백 HTTP 로 읽는다(S3a 스펙 §7.3). 실패하면 `null`(스냅숏 없음)이다. 못 읽은 것을 빈 셀로 접으면
 * 미들웨어가 «말이 없다» 를 «비었다» 로 읽는다.
 *
 * @param baseUrl 현장 셀 대역의 주소. 경로 `/cell` 을 붙여 부른다.
 */
class CellBandClient(baseUrl: String, private val timeout: Duration = Duration.ofSeconds(1)) {

    private val uri = URI.create(baseUrl.trimEnd('/') + "/cell")
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

    companion object {
        /** 본문 모양이 어긋나면 예외다. [fetch] 가 그것을 «스냅숏 없음» 으로 접는다. */
        fun parse(node: JsonNode): CellSnapshot = CellSnapshot(places(node, "presentations"), places(node, "slots"))

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
 * - [signal]: 이름 있는 신호는 S3a 에서 다루지 않아 `null`(못 읽었다)이다. S3b 의 몫이다.
 *
 * 스냅숏이 없으면 [observe]·[holding] 모두 `null`(못 물어봄)이다.
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
}
