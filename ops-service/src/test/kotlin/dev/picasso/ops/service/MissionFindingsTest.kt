package dev.picasso.ops.service

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.picasso.ops.service.MissionBench.Companion.refusal
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.missions.HostMissionRefusal
import dev.picasso.ops.service.missions.MissionFindings
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 호스트 거부 → 거부 카드 변환(S3b 스펙 §7). 호스트 본문(S3b JSON 계약 §3.1)을 읽어 옮긴다. */
class MissionFindingsTest {

    private val json = jacksonObjectMapper().registerModule(JavaTimeModule())

    private fun finding(body: String): Finding = MissionFindings.of(json.readValue<HostMissionRefusal>(body))

    @Test
    fun `노드 거부는 관측값 앞에 노드 id 를 싣고 대상은 널이며 엔지니어가 화면 안에서 푼다`() {
        assertEquals(
            Finding(
                kind = "SIGNAL_NOT_IN_SPEC",
                observed = "노드 rack-arrival: rack_ready",
                expected = "신호 사양의 이름 중 하나(guard_closed, lot_code, rack_present)",
                checkedAt = Instant.parse("2026-10-08T00:00:01Z"),
                owner = Owner.ENGINEER,
                inScreen = true,
                action = "신호 이름을 고치거나 신호 사양에 더한다",
                target = null,
                basisVersion = null,
            ),
            finding(refusal()),
        )
    }

    @Test
    fun `OUTSIDE_CONSOLE 는 현장 담당이고 화면 밖이다`() {
        val outside = finding(refusal(kind = "FLOOR_UNOWNED", nodeId = "place", observed = "RACK-204", owner = "OUTSIDE_CONSOLE"))
        assertEquals(Owner.SITE, outside.owner)
        assertEquals(false, outside.inScreen)
        assertEquals("노드 place: RACK-204", outside.observed)
    }

    @Test
    fun `문서 전체의 거부는 노드 접두가 없고 근거 버전은 Long 으로 옮긴다`() {
        val whole = finding(refusal(kind = "UNREADABLE", nodeId = null, observed = "$.workMasterId: 초안의 WorkMaster 와 다르다(InspectAsset)", basisVersion = "7"))
        assertEquals("$.workMasterId: 초안의 WorkMaster 와 다르다(InspectAsset)", whole.observed)
        assertEquals(7L, whole.basisVersion)
        assertEquals(null, whole.target)
    }
}
