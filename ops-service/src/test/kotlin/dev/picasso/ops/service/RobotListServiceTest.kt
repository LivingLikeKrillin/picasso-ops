package dev.picasso.ops.service

import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.robots.Blockers
import dev.picasso.ops.service.robots.Connection
import dev.picasso.ops.service.robots.RegistryState
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.settings.SiteSettingsValues
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RobotListServiceTest {

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val t1 = Instant.parse("2026-10-07T00:00:00Z")
    private val t2 = Instant.parse("2026-10-07T00:00:05Z")
    private val clock = MovableClock(t1)
    private val r1 = RegistryRobot(robotId = "r1", siteId = "site-01", status = "CLAIMED")
    private var next: RegistryCall<List<RegistryRobot>> = RegistryCall.Ok(emptyList())
    private var token: RegistryCall<Unit> = RegistryCall.Ok(Unit)
    private var askedSite: String? = null
    private var settings = SiteSettingsValues(1, Duration.ofSeconds(90))
    private var settingsDown = false
    private val service = RobotListService(
        { site -> askedSite = site; next },
        { token },
        "site-01",
        clock,
        { if (settingsDown) error("ops DB 응답 없음") else settings },
    )

    @Test
    fun `registry 가 답하면 목록과 읽은 시각을 낸다`() {
        next = RegistryCall.Ok(listOf(r1))
        val view = service.read()
        assertEquals(RegistryState.OK, view.registry)
        assertEquals(listOf(r1), view.robots!!.map { it.robot })
        assertEquals(t1, view.robotsAsOf)
        assertEquals("site-01", askedSite)
    }

    @Test
    fun `기체마다 읽은 시각 기준의 연결 칸과 막힘을 붙인다`() {
        next = RegistryCall.Ok(listOf(r1))
        val robot = service.read().robots!!.single()
        assertEquals(Connection.NO_REPORT, robot.connection)
        assertEquals(listOf(Blockers.AWAITING_FIRST_REPORT), robot.blockers.map { it.kind })
        assertEquals(t1, robot.blockers.single().checkedAt)
    }

    @Test
    fun `한 번도 못 읽었으면 목록은 없음이 아니라 모름이다`() {
        next = RegistryCall.Silent("응답 없음")
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_SILENT, view.registry)
        assertNull(view.robots)
        assertNull(view.robotsAsOf)
    }

    @Test
    fun `registry 가 침묵하면 직전 목록과 그 시각을 유지한다`() {
        next = RegistryCall.Ok(listOf(r1))
        service.read()
        clock.now = t2
        next = RegistryCall.Silent("응답 없음")
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_SILENT, view.registry)
        assertEquals(t2, view.checkedAt)
        assertEquals(listOf(r1), view.robots!!.map { it.robot })
        assertEquals(t1, view.robotsAsOf)
    }

    @Test
    fun `직전 값이 빈 목록이면 침묵 뒤에도 없음을 유지한다`() {
        next = RegistryCall.Ok(emptyList())
        service.read()
        next = RegistryCall.Silent("응답 없음")
        assertEquals(emptyList(), service.read().robots)
    }

    @Test
    fun `토큰 불일치는 전체 상태로 가고 직전 목록을 유지한다`() {
        next = RegistryCall.Ok(listOf(r1))
        service.read()
        next = RegistryCall.Unauthorized
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_UNAUTHORIZED, view.registry)
        assertEquals(listOf(r1), view.robots!!.map { it.robot })
    }

    @Test
    fun `목록은 읽혀도 관문 안 확인이 401 이면 토큰 불일치이고 목록은 새 값이다`() {
        next = RegistryCall.Ok(listOf(r1))
        token = RegistryCall.Unauthorized
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_UNAUTHORIZED, view.registry)
        assertEquals(view.checkedAt, view.robotsAsOf)
        assertEquals(listOf(r1), view.robots!!.map { it.robot })
    }

    @Test
    fun `관문 안 확인이 답하지 않는 것은 토큰 불일치가 아니다`() {
        next = RegistryCall.Ok(listOf(r1))
        token = RegistryCall.Silent("HTTP 503")
        assertEquals(RegistryState.OK, service.read().registry)
    }

    @Test
    fun `연결 칸과 오래됨은 그 읽기의 현장 설정 버전으로 판정하고 근거 버전을 싣는다`() {
        val confirmed = r1.copy(status = "CONFIRMED", lastReportedAt = t1.minusSeconds(70).toString())
        next = RegistryCall.Ok(listOf(confirmed))
        val before = service.read()
        assertEquals(Connection.FRESH, before.robots!!.single().connection)
        assertEquals(1L, before.settingsVersion)
        assertEquals(90L, before.connectionThresholdSeconds)

        settings = SiteSettingsValues(2, Duration.ofSeconds(60))
        val after = service.read()
        val robot = after.robots!!.single()
        assertEquals(Connection.STALE, robot.connection)
        assertEquals(2L, after.settingsVersion)
        assertEquals(60L, after.connectionThresholdSeconds)
        val stale = robot.blockers.single()
        assertEquals(Blockers.REPORT_STALE, stale.kind)
        assertEquals("60초 안의 보고", stale.expected)
        assertEquals(2L, stale.basisVersion)
    }

    @Test
    fun `현장 설정을 못 읽으면 기본값으로 판정하지 않고 실패하며 직전 스냅샷을 바꾸지 않는다`() {
        next = RegistryCall.Ok(listOf(r1))
        service.read()
        clock.now = t2
        settingsDown = true
        next = RegistryCall.Ok(emptyList())
        assertFailsWith<IllegalStateException> { service.read() }
        settingsDown = false
        next = RegistryCall.Silent("응답 없음")
        val view = service.read()
        assertEquals(listOf(r1), view.robots!!.map { it.robot })
        assertEquals(t1, view.robotsAsOf)
    }

    @Test
    fun `registry 가 침묵하면 직전 스냅샷의 현장 설정 버전을 그대로 보인다`() {
        next = RegistryCall.Ok(listOf(r1))
        service.read()
        settings = SiteSettingsValues(2, Duration.ofSeconds(60))
        next = RegistryCall.Silent("응답 없음")
        val view = service.read()
        assertEquals(1L, view.settingsVersion)
        assertEquals(90L, view.connectionThresholdSeconds)
    }

    @Test
    fun `늦게 끝난 옛 읽기는 더 새 목록을 덮지 않는다`() {
        clock.now = t2
        next = RegistryCall.Ok(listOf(r1))
        service.read()
        clock.now = t1
        next = RegistryCall.Ok(emptyList())
        val view = service.read()
        assertEquals(listOf(r1), view.robots!!.map { it.robot })
        assertEquals(t2, view.robotsAsOf)
        // 확인 시각도 남긴 값의 시각으로 맞춘다. 화면은 두 시각이 다르면 직전 값으로 보인다.
        assertEquals(t2, view.checkedAt)
    }
}
