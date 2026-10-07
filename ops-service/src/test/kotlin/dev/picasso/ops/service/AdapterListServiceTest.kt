package dev.picasso.ops.service

import dev.picasso.ops.service.adapters.AdapterListService
import dev.picasso.ops.service.registry.AdapterSource
import dev.picasso.ops.service.registry.RegistryAdapter
import dev.picasso.ops.service.registry.RegistryBuild
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryInstance
import dev.picasso.ops.service.robots.RegistryState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 어댑터 목록은 제품·빌드와 인스턴스를 한 시각으로 읽고, registry 가 답하지 않으면 직전 값을 지킨다(스펙 §9). */
class AdapterListServiceTest {

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val t1 = Instant.parse("2026-10-07T00:00:00Z")
    private val t2 = Instant.parse("2026-10-07T00:00:05Z")
    private val clock = MovableClock(t1)
    private val build = RegistryBuild(10, "1.0.0", "0.9.0", "UNTESTED")
    private val adapter = RegistryAdapter(1, "acme", "fleet", listOf(build))
    private val instance = RegistryInstance(
        instanceId = "i1", siteId = "site-01", adapter = "acme/fleet", version = "1.0.0", contractSemver = "0.9.0",
        conformance = "UNTESTED",
    )
    private var adapters: RegistryCall<List<RegistryAdapter>> = RegistryCall.Ok(listOf(adapter))
    private var instances: RegistryCall<List<RegistryInstance>> = RegistryCall.Ok(listOf(instance))
    private var askedSite: String? = null
    private val calls = mutableListOf<String>()
    private val service = AdapterListService(
        object : AdapterSource {
            override fun adapters() = adapters.also { calls += "adapters" }
            override fun instances(siteId: String) = instances.also {
                askedSite = siteId
                calls += "instances"
            }
        },
        "site-01",
        clock,
    )

    @Test
    fun `둘 다 읽히면 제품·빌드와 이 사이트의 인스턴스를 같은 시각으로 낸다`() {
        val view = service.read()
        assertEquals(RegistryState.OK, view.registry)
        assertEquals(listOf(adapter), view.adapters)
        assertEquals(listOf(instance), view.instances)
        assertEquals(t1 to t1, view.checkedAt to view.asOf)
        assertEquals("site-01", askedSite)
    }

    @Test
    fun `인스턴스를 먼저 읽고, 못 읽으면 제품·빌드는 읽지 않는다`() {
        service.read()
        assertEquals(listOf("instances", "adapters"), calls)
        calls.clear()
        instances = RegistryCall.Silent("응답 없음")
        service.read()
        assertEquals(listOf("instances"), calls)
    }

    @Test
    fun `한 번도 못 읽었으면 없음이 아니라 모름이다`() {
        adapters = RegistryCall.Silent("응답 없음")
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_SILENT, view.registry)
        assertNull(view.adapters)
        assertNull(view.instances)
        assertNull(view.asOf)
    }

    @Test
    fun `인스턴스만 못 읽어도 둘 다 직전 값을 지킨다`() {
        service.read()
        clock.now = t2
        adapters = RegistryCall.Ok(emptyList())
        instances = RegistryCall.Silent("응답 없음")
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_SILENT, view.registry)
        assertEquals(listOf(adapter), view.adapters)
        assertEquals(listOf(instance), view.instances)
        assertEquals(t2 to t1, view.checkedAt to view.asOf)
    }

    @Test
    fun `제품·빌드 읽기가 401 이면 토큰 불일치이고 직전 값을 지킨다`() {
        service.read()
        clock.now = t2
        adapters = RegistryCall.Unauthorized
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_UNAUTHORIZED, view.registry)
        assertEquals(listOf(adapter), view.adapters)
        assertEquals(t1, view.asOf)
    }

    @Test
    fun `늦게 끝난 옛 읽기는 더 새 목록을 덮지 않는다`() {
        clock.now = t2
        service.read()
        clock.now = t1
        adapters = RegistryCall.Ok(emptyList())
        val view = service.read()
        assertEquals(listOf(adapter), view.adapters)
        assertEquals(t2 to t2, view.checkedAt to view.asOf)
    }
}
