package dev.picasso.ops.host.web

import dev.picasso.middleware.SiteTimings
import dev.picasso.ops.host.MissionHost
import dev.picasso.ops.host.timings.RejectedTimings
import dev.picasso.ops.host.timings.SiteTimingsReader
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
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
 * 현장 시간값 적용 상태와 인시던트 조회(S3c 스펙 §7.2, T9). 둘 다 읽기만 한다. 운영 서비스가 적용 상태를 대신 읽어 화면에 보이고,
 * 통합 시험이 인시던트에 실린 설정 버전과 시간값을 확인한다.
 */
@RestController
class SiteTimingsController(private val host: MissionHost, private val timings: SiteTimingsReader) {

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

    /** 최신부터 많아야 [limit] 개. 정수가 아니거나 1~[MAX_LIMIT] 밖이면 400 `BAD_REQUEST` 다. */
    @GetMapping("/host/incidents")
    fun incidents(@RequestParam(required = false) limit: String?): ResponseEntity<Any> {
        val count = if (limit == null) DEFAULT_LIMIT else limit.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(HostRejection(HostRequests.BAD_REQUEST, "limit 은 1~$MAX_LIMIT 의 정수다: $limit"))
        return ResponseEntity.ok(host.incidents(count))
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
    }
}
