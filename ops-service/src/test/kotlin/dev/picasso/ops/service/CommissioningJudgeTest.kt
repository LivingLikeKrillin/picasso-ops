package dev.picasso.ops.service

import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.registry.RegistryBinding
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.robots.CommissioningJudge
import dev.picasso.ops.service.robots.CommissioningState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 시운전 판정 세 조건(결정 6)과 새 막힘 4종(P2·S1d 스펙 §8.5)을 표로 본다. */
class CommissioningJudgeTest {

    private val at = Instant.parse("2026-10-08T00:00:00Z")

    private fun robot(status: String) = RegistryRobot(robotId = "r1", siteId = "site-01", status = status)

    private fun binding(siteNames: String, unsupported: Boolean? = null, count: Int? = null) = RegistryBinding(
        robotId = "r1", vendor = "v", model = "m", profileRevisionId = 2, revision = 1, adapterName = "acme/fleet",
        adapterVersion = "1.0.0", conformanceStatus = "UNTESTED", active = true, siteNames = siteNames,
        siteNameKeys = listOf("location"), adapterVersionId = 7, boundBy = "engineer/kim", boundAt = "t",
        siteNamesRegisteredAt = "t1", siteNamesUnsupported = unsupported, siteNamesCount = count,
    )

    private data class Row(val status: String, val binding: RegistryBinding?, val state: CommissioningState, val kinds: List<String>)

    private val table = listOf(
        Row("CONFIRMED", binding("CONFIRMED"), CommissioningState.COMPLETE, emptyList()),
        Row("CONFIRMED", binding("NOT_REQUIRED"), CommissioningState.COMPLETE, emptyList()),
        Row("CONFIRMED", null, CommissioningState.INCOMPLETE, listOf(CommissioningJudge.UNBOUND)),
        Row("CLAIMED", binding("CONFIRMED"), CommissioningState.INCOMPLETE, emptyList()),
        Row("CONFIRMED", binding("UNREGISTERED"), CommissioningState.INCOMPLETE, listOf(CommissioningJudge.SITE_NAMES_UNREGISTERED)),
        Row("CONFIRMED", binding("CLAIMED"), CommissioningState.INCOMPLETE, listOf(CommissioningJudge.SITE_NAMES_UNANSWERED)),
        Row("CONFIRMED", binding("CONTRADICTED", count = 0), CommissioningState.INCOMPLETE, listOf(CommissioningJudge.SITE_NAMES_CONTRADICTED)),
        Row("RETIRED", null, CommissioningState.RETIRED, emptyList()),
        Row("RETIRED", binding("CONFIRMED"), CommissioningState.RETIRED, emptyList()),
    )

    @Test
    fun `세 조건이 모두 맞아야 완료이고 빠진 것이 막힘으로 보인다`() {
        table.forEach { row ->
            val judged = CommissioningJudge.of(robot(row.status), row.binding)
            assertEquals(row.state, judged.state, "$row")
            assertEquals(row.kinds, CommissioningJudge.blockers(robot(row.status), row.binding, at).map { it.kind }, "$row")
        }
    }

    @Test
    fun `체크 목록은 조건마다 따로 참거짓이다`() {
        val judged = CommissioningJudge.of(robot("CLAIMED"), binding("UNREGISTERED"))
        assertEquals(Triple(false, true, false), Triple(judged.ledgerConfirmed, judged.bound, judged.siteNamesReady))
    }

    @Test
    fun `해결 담당은 바인딩·기록 빠짐이 엔지니어이고 기체의 답 문제는 현장이다`() {
        fun owner(binding: RegistryBinding?) = CommissioningJudge.blockers(robot("CONFIRMED"), binding, at).single().let { it.owner to it.inScreen }
        assertEquals(Owner.ENGINEER to true, owner(null))
        assertEquals(Owner.ENGINEER to true, owner(binding("UNREGISTERED")))
        assertEquals(Owner.SITE to false, owner(binding("CLAIMED")))
        assertEquals(Owner.SITE to false, owner(binding("CONTRADICTED", unsupported = false, count = 0)))
    }

    @Test
    fun `기체가 명칭을 지원하지 않는다고 답했으면 다시 티칭이 아니라 엔지니어가 프로파일을 본다`() {
        val finding = CommissioningJudge.blockers(robot("CONFIRMED"), binding("CONTRADICTED", unsupported = true), at).single()
        assertEquals(CommissioningJudge.SITE_NAMES_CONTRADICTED, finding.kind)
        assertEquals(Owner.ENGINEER to true, finding.owner to finding.inScreen)
        assertEquals("그 기종의 프로파일과 명칭 기록 확인", finding.action)
        assertEquals("r1" to at, finding.target to finding.checkedAt)
    }
}
