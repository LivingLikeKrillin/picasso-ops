package dev.picasso.ops.host.web

import dev.picasso.middleware.SiteTimings
import dev.picasso.ops.host.timings.RejectedTimings
import dev.picasso.ops.host.timings.SiteTimingsReader
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** 적용한 현장 시간값. 칸 이름은 운영 서비스 현장 설정 본문의 이름과 같고 값은 초 단위 정수다. */
data class AppliedTimingsView(
    val version: Long,
    val evidenceBeforeSeconds: Long,
    val evidenceAfterSeconds: Long,
    val inDoubtGraceSeconds: Long,
    val stallWindowSeconds: Long,
) {
    companion object {
        fun of(timings: SiteTimings) = AppliedTimingsView(
            timings.siteSettingsVersion,
            timings.evidenceWindowBefore.seconds,
            timings.evidenceWindowAfter.seconds,
            timings.inDoubtGrace.seconds,
            timings.stallWindow.seconds,
        )
    }
}

/**
 * `GET /host/site-timings` 의 본문(S3c 스펙 §7.2). 칸의 뜻은 `SiteTimingsState` 에 있다. [applied] 가 `null` 이면 미적용이다.
 */
data class SiteTimingsStateView(
    val applied: AppliedTimingsView?,
    val appliedAt: Instant?,
    val lastReadAt: Instant?,
    val readError: String?,
    val rejected: RejectedTimings?,
)

/**
 * 현장 시간값 적용 상태 조회(S3c 스펙 §7.2, T9). 읽기만 한다. 운영 서비스가 적용 상태를 대신 읽어 화면에 보인다. 인시던트 조회는
 * S4a 에서 [IncidentController] 로 옮겼다.
 */
@RestController
class SiteTimingsController(private val timings: SiteTimingsReader) {

    @GetMapping("/host/site-timings")
    fun siteTimings(): SiteTimingsStateView {
        val state = timings.state()
        return SiteTimingsStateView(
            applied = state.applied?.let(AppliedTimingsView::of),
            appliedAt = state.appliedAt,
            lastReadAt = state.lastReadAt,
            readError = state.readError,
            rejected = state.rejected,
        )
    }
}
