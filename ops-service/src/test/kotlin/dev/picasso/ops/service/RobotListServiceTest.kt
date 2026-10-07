package dev.picasso.ops.service

import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.robots.RegistryState
import dev.picasso.ops.service.robots.RobotListService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
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
    private var askedSite: String? = null
    private val service = RobotListService({ site -> askedSite = site; next }, "site-01", clock)

    @Test
    fun `registry 가 답하면 목록과 읽은 시각을 낸다`() {
        next = RegistryCall.Ok(listOf(r1))
        val view = service.read()
        assertEquals(RegistryState.OK, view.registry)
        assertEquals(listOf(r1), view.robots)
        assertEquals(t1, view.robotsAsOf)
        assertEquals("site-01", askedSite)
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
        assertEquals(listOf(r1), view.robots)
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
        assertEquals(listOf(r1), view.robots)
    }
}
