package dev.picasso.ops.service

import dev.picasso.ops.service.registry.CommissioningSource
import dev.picasso.ops.service.registry.RegistryBinding
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RegistrySoftware
import dev.picasso.ops.service.robots.CommissioningState
import dev.picasso.ops.service.robots.RegistryState
import dev.picasso.ops.service.robots.RobotListService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 기체 목록이 기체 → 바인딩 → 소프트웨어 대조를 함께 읽고 셋 다 읽혀야 새 값으로 바꾼다(P2·S1d 스펙 §8.1). */
class RobotCommissioningListTest {

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val t1 = Instant.parse("2026-10-08T00:00:00Z")
    private val t2 = Instant.parse("2026-10-08T00:00:05Z")
    private val clock = MovableClock(t1)
    private val r1 = RegistryRobot(robotId = "r1", siteId = "site-01", status = "CONFIRMED", lastReportedAt = "2026-10-07T23:59:50Z")
    private val r2 = RegistryRobot(robotId = "r2", siteId = "site-01", status = "CONFIRMED", lastReportedAt = "2026-10-07T23:59:50Z")

    private fun binding(robotId: String, siteNames: String) = RegistryBinding(
        robotId = robotId, vendor = "v", model = "m", profileRevisionId = 2, revision = 1, adapterName = "acme/fleet",
        adapterVersion = "1.0.0", conformanceStatus = "UNTESTED", active = true, siteNames = siteNames,
        adapterVersionId = 7, boundBy = "engineer/kim", boundAt = "t",
    )

    private var robots: RegistryCall<List<RegistryRobot>> = RegistryCall.Ok(listOf(r1, r2))
    private var bindings: RegistryCall<List<RegistryBinding>> = RegistryCall.Ok(listOf(binding("r1", "CONFIRMED")))
    private var software: RegistryCall<List<RegistrySoftware>> = RegistryCall.Ok(listOf(RegistrySoftware("r1", "1.0", "1.0", "MATCH")))
    private val asked = mutableListOf<String>()

    private val service = RobotListService(
        { robots },
        { RegistryCall.Ok(Unit) },
        "site-01",
        clock,
        Duration.ofSeconds(90),
        commissioning = object : CommissioningSource {
            override fun bindings(siteId: String) = bindings.also { asked += "bindings $siteId" }
            override fun software(siteId: String) = software.also { asked += "software $siteId" }
        },
    )

    @Test
    fun `셋 다 읽히면 기체마다 바인딩·시운전·소프트웨어 대조를 붙인다`() {
        val view = service.read()
        assertEquals(listOf("bindings site-01", "software site-01"), asked)

        val (one, two) = view.robots!!
        assertEquals(CommissioningState.COMPLETE, one.commissioning!!.state)
        assertEquals(7L, one.binding!!.adapterVersionId)
        assertEquals("MATCH", one.software!!.verdict)
        assertEquals(emptyList(), one.blockers)

        assertEquals(CommissioningState.INCOMPLETE, two.commissioning!!.state)
        assertNull(two.binding)
        assertEquals(listOf("UNBOUND"), two.blockers.map { it.kind })
    }

    @Test
    fun `바인딩이나 소프트웨어 대조를 못 읽으면 셋 다 직전 값이다`() {
        service.read()
        clock.now = t2
        bindings = RegistryCall.Ok(emptyList())
        software = RegistryCall.Silent("응답 없음")

        val silent = service.read()
        assertEquals(RegistryState.REGISTRY_SILENT to t1, silent.registry to silent.robotsAsOf)
        assertEquals(7L, silent.robots!!.first().binding!!.adapterVersionId)

        software = RegistryCall.Ok(emptyList())
        bindings = RegistryCall.Silent("응답 없음")
        asked.clear()
        assertEquals(RegistryState.REGISTRY_SILENT, service.read().registry)
        assertEquals(listOf("bindings site-01"), asked)
    }

    @Test
    fun `기체 목록을 못 읽으면 바인딩을 묻지 않는다`() {
        robots = RegistryCall.Silent("응답 없음")
        assertEquals(RegistryState.REGISTRY_SILENT, service.read().registry)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `이력 행은 활성 바인딩으로 보지 않는다`() {
        bindings = RegistryCall.Ok(listOf(binding("r1", "CONFIRMED").copy(active = false)))
        val one = service.read().robots!!.first()
        assertNull(one.binding)
        assertEquals(listOf("UNBOUND"), one.blockers.map { it.kind })
    }
}
