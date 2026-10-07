package dev.picasso.ops.service.profiles

import dev.picasso.ops.service.registry.ProfileSource
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryCatalog
import dev.picasso.ops.service.registry.RegistryRevision
import dev.picasso.ops.service.robots.RegistryState
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * 시험 요청 상태(P2·S1d 스펙 §9). «대기» 는 아직 안 집힌 것, «실행 중» 은 집혔고 만료 전, «만료» 는 집혔으나 만료가
 * 지나 다시 집히기를 기다리는 것, «끝남» 은 끝난 것이다. 요청이 없으면 [NONE] 이다.
 */
enum class TestRequestState { NONE, WAITING, RUNNING, EXPIRED, DONE }

/** 개정판 한 줄과 그 시험 요청 상태. 상태는 목록을 읽은 시각과 만료 시각을 비교해 정한다. */
data class RevisionView(val revision: RegistryRevision, val testRequest: TestRequestState)

/**
 * `GET /api/profiles` 의 답.
 *
 * @param catalog 널이면 «모름»(한 번도 읽지 못했다)
 * @param asOf [catalog]·[revisions] 를 registry 에서 읽은 시각. [checkedAt] 과 다르면 직전 값이다
 */
data class ProfileListView(
    val registry: RegistryState,
    val checkedAt: Instant,
    val catalog: RegistryCatalog?,
    val revisions: List<RevisionView>?,
    val asOf: Instant?,
)

/**
 * 카탈로그(`GET /operations/skill-types`)와 개정판 목록(`GET /operations/profile-revisions`). 둘 다 읽혀야 새 값으로
 * 바꾸고, 하나라도 못 읽으면 둘 다 직전 값이다(어댑터 목록과 같은 규칙). 둘 다 운영자 토큰 관문 안이라 토큰이 틀리면
 * [RegistryState.REGISTRY_UNAUTHORIZED] 다.
 */
class ProfileListService(
    private val source: ProfileSource,
    private val clock: Clock,
) {
    private data class Known(val catalog: RegistryCatalog, val revisions: List<RegistryRevision>, val at: Instant)

    private val last = AtomicReference<Known?>(null)

    fun read(): ProfileListView {
        val now = clock.instant()
        val catalog = source.catalog()
        val revisions = if (catalog is RegistryCall.Ok) source.revisions() else null
        if (catalog is RegistryCall.Ok && revisions is RegistryCall.Ok) {
            // 늦게 끝난 옛 읽기가 더 새 값을 덮지 않게 한다. 폴링과 조작 뒤 다시 읽기가 겹칠 수 있다.
            val known = last.updateAndGet { prev ->
                if (prev != null && prev.at.isAfter(now)) prev else Known(catalog.value, revisions.value, now)
            }!!
            return view(RegistryState.OK, maxOf(now, known.at), known)
        }
        val state = if (catalog == RegistryCall.Unauthorized || revisions == RegistryCall.Unauthorized) {
            RegistryState.REGISTRY_UNAUTHORIZED
        } else {
            RegistryState.REGISTRY_SILENT
        }
        return view(state, now, last.get())
    }

    private fun view(state: RegistryState, now: Instant, known: Known?): ProfileListView =
        ProfileListView(
            registry = state,
            checkedAt = now,
            catalog = known?.catalog,
            revisions = known?.revisions?.map { RevisionView(it, testRequestState(it, known.at)) },
            asOf = known?.at,
        )

    companion object {
        /**
         * 판정 시각 [at] 은 목록을 읽은 시각이다. PoC 에서 registry 와 운영 서비스는 같은 기계에 있다(스펙 §9).
         * 만료 시각과 같은 시각은 이미 만료다. registry 가 그 순간부터 다른 실행기에 다시 집어 준다(`claim_expires_at <= now`).
         */
        fun testRequestState(revision: RegistryRevision, at: Instant): TestRequestState {
            val request = revision.latestTestRequest ?: return TestRequestState.NONE
            if (request.completedAt != null) return TestRequestState.DONE
            if (request.claimedAt == null) return TestRequestState.WAITING
            val expires = request.claimExpiresAt?.let(Instant::parse) ?: return TestRequestState.RUNNING
            return if (!at.isBefore(expires)) TestRequestState.EXPIRED else TestRequestState.RUNNING
        }
    }
}
