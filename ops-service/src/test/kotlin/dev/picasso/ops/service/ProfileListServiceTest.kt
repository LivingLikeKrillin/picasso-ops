package dev.picasso.ops.service

import dev.picasso.ops.service.profiles.ProfileListService
import dev.picasso.ops.service.profiles.TestRequestState
import dev.picasso.ops.service.registry.ProfileSource
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryCatalog
import dev.picasso.ops.service.registry.RegistryRevision
import dev.picasso.ops.service.registry.RegistryTestRequest
import dev.picasso.ops.service.robots.RegistryState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 프로파일 목록(P2·S1d 스펙 §8.1)과 시험 요청 상태 4값(§9). registry 는 대역이다. */
class ProfileListServiceTest {

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val t1 = Instant.parse("2026-10-08T00:00:00Z")
    private val t2 = Instant.parse("2026-10-08T00:00:05Z")
    private val clock = MovableClock(t1)

    private var catalog: RegistryCall<RegistryCatalog> = RegistryCall.Ok(RegistryCatalog("0.9.0"))
    private var revisions: RegistryCall<List<RegistryRevision>> = RegistryCall.Ok(emptyList())

    private val service = ProfileListService(
        object : ProfileSource {
            override fun catalog() = catalog
            override fun revisions() = revisions
        },
        clock,
    )

    private fun revision(request: RegistryTestRequest?) =
        RegistryRevision(1, "v", "m", 1, "VALIDATED", documentHash = "h", latestTestRequest = request)

    private fun request(claimedAt: String? = null, expires: String? = null, completed: String? = null) =
        RegistryTestRequest(9, "engineer/kim", "2026-10-07T23:00:00Z", claimedBy = claimedAt?.let { "site-runner" }, claimedAt = claimedAt, claimExpiresAt = expires, completedAt = completed)

    @Test
    fun `시험 요청 상태는 요청 없음·대기·실행 중·만료·끝남이다`() {
        val cases = listOf(
            null to TestRequestState.NONE,
            request() to TestRequestState.WAITING,
            request("2026-10-07T23:59:00Z", "2026-10-08T00:14:00Z") to TestRequestState.RUNNING,
            request("2026-10-07T23:40:00Z", "2026-10-07T23:55:00Z") to TestRequestState.EXPIRED,
            request("2026-10-07T23:40:00Z", "2026-10-07T23:55:00Z", "2026-10-07T23:41:00Z") to TestRequestState.DONE,
        )
        cases.forEach { (req, state) -> assertEquals(state, ProfileListService.testRequestState(revision(req), t1), "$req") }
    }

    @Test
    fun `만료 시각과 같은 시각은 이미 만료다 - registry 가 그 순간부터 다시 집어 준다`() {
        val at = Instant.parse("2026-10-08T00:14:00Z")
        assertEquals(TestRequestState.EXPIRED, ProfileListService.testRequestState(revision(request("2026-10-07T23:59:00Z", "2026-10-08T00:14:00Z")), at))
    }

    @Test
    fun `둘 다 읽히면 새 값이고 상태는 읽은 시각으로 판정한다`() {
        revisions = RegistryCall.Ok(listOf(revision(request("2026-10-07T23:59:00Z", "2026-10-08T00:00:03Z"))))
        val first = service.read()
        assertEquals(RegistryState.OK to t1, first.registry to first.asOf)
        assertEquals(TestRequestState.RUNNING, first.revisions!!.single().testRequest)

        clock.now = t2
        assertEquals(TestRequestState.EXPIRED, service.read().revisions!!.single().testRequest)
    }

    @Test
    fun `하나라도 못 읽으면 둘 다 직전 값이고 토큰 불일치는 전체 상태로 간다`() {
        assertNull(ProfileListService(object : ProfileSource {
            override fun catalog() = RegistryCall.Silent("x")
            override fun revisions() = revisions
        }, clock).read().catalog)

        service.read()
        clock.now = t2
        revisions = RegistryCall.Silent("응답 없음")
        val silent = service.read()
        assertEquals(RegistryState.REGISTRY_SILENT to t1, silent.registry to silent.asOf)
        assertEquals("0.9.0", silent.catalog!!.contractSemver)

        catalog = RegistryCall.Unauthorized
        assertEquals(RegistryState.REGISTRY_UNAUTHORIZED, service.read().registry)
    }

    @Test
    fun `카탈로그는 읽혀도 개정판 목록이 401 이면 토큰 불일치다`() {
        revisions = RegistryCall.Unauthorized
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_UNAUTHORIZED, view.registry)
        assertNull(view.revisions)
    }
}
