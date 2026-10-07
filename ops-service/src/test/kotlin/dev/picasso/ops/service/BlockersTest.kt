package dev.picasso.ops.service

import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.robots.Blockers
import dev.picasso.ops.service.robots.Connection
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 상태 막힘 4종과 연결 칸(스펙 §7.3·§7.4). 종류마다 경계를 넣는다. */
class BlockersTest {

    private val at = Instant.parse("2026-10-07T00:10:00Z")
    private val threshold = Duration.ofSeconds(90)

    private fun robot(
        status: String,
        lastReportedAt: Instant? = null,
        retiredAt: Instant? = null,
        reportingAfterRetirement: Boolean = false,
    ) = RegistryRobot(
        robotId = "r1",
        siteId = "site-01",
        status = status,
        lastReportedAt = lastReportedAt?.toString(),
        retiredAt = retiredAt?.toString(),
        reportingAfterRetirement = reportingAfterRetirement,
    )

    private fun kinds(robot: RegistryRobot) = Blockers.of(robot, at, threshold).map { it.kind }

    @Test
    fun `연결 칸은 보고 없음, 기준 시간 안, 기준 시간 넘김으로 갈린다`() {
        assertEquals(Connection.NO_REPORT, Blockers.connection(robot("CLAIMED"), at, threshold))
        assertEquals(Connection.FRESH, Blockers.connection(robot("CONFIRMED", at.minusSeconds(90)), at, threshold))
        assertEquals(Connection.STALE, Blockers.connection(robot("CONFIRMED", at.minusSeconds(91)), at, threshold))
    }

    @Test
    fun `선언했는데 보고가 0회면 첫 보고 대기이고 현장이 푼다`() {
        val finding = Blockers.of(robot("CLAIMED"), at, threshold).single()
        assertEquals(Blockers.AWAITING_FIRST_REPORT, finding.kind)
        assertEquals(Owner.SITE, finding.owner)
        assertEquals(false, finding.inScreen)
        assertEquals(at, finding.checkedAt)
        assertEquals("r1", finding.target)
    }

    @Test
    fun `확인된 기체의 보고가 기준 시간을 넘기면 오래됨이고 직전은 아니다`() {
        assertEquals(listOf(), kinds(robot("CONFIRMED", at.minusSeconds(90))))
        assertEquals(listOf(Blockers.REPORT_STALE), kinds(robot("CONFIRMED", at.minusSeconds(91))))
    }

    @Test
    fun `퇴역 뒤 보고는 운영자가 화면 안에서 푼다`() {
        val finding = Blockers.of(
            robot("RETIRED", at.minusSeconds(5), retiredAt = at.minusSeconds(60), reportingAfterRetirement = true),
            at,
            threshold,
        ).single()
        assertEquals(Blockers.REPORTING_AFTER_RETIREMENT, finding.kind)
        assertEquals(Owner.OPERATOR, finding.owner)
        assertEquals(true, finding.inScreen)
    }

    @Test
    fun `퇴역 뒤 보고가 없는 퇴역 기체는 막힘이 없다`() {
        assertEquals(listOf(), kinds(robot("RETIRED", at.minusSeconds(300), retiredAt = at.minusSeconds(60))))
    }

    @Test
    fun `어느 문으로도 안 들어온 행은 엔지니어가 조사한다`() {
        val finding = Blockers.of(robot("UNREGISTERED"), at, threshold).single()
        assertEquals(Blockers.UNREGISTERED_ROW, finding.kind)
        assertEquals(Owner.ENGINEER, finding.owner)
    }

    @Test
    fun `발견된 기체와 신선한 확인 기체는 막힘이 없다`() {
        assertEquals(listOf(), kinds(robot("DISCOVERED")))
        assertEquals(listOf(), kinds(robot("CONFIRMED", at)))
    }
}
