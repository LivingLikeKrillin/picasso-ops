package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostSiteTimings
import dev.picasso.ops.service.settings.SiteSettingsChange
import dev.picasso.ops.service.settings.SiteSettingsOperations
import dev.picasso.ops.service.settings.SiteSettingsRange
import dev.picasso.ops.service.settings.SiteSettingsRecord
import dev.picasso.ops.service.settings.SiteSettingsStore
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 허용 범위(초). 화면은 이 값으로 입력을 막고, 서버도 같은 값으로 막는다. 시간값 넷의 범위는 S3c 에서 더했다. */
data class SiteSettingsRangeView(
    val minConnectionThresholdSeconds: Int,
    val maxConnectionThresholdSeconds: Int,
    val minEvidenceBeforeSeconds: Int,
    val maxEvidenceBeforeSeconds: Int,
    val minEvidenceAfterSeconds: Int,
    val maxEvidenceAfterSeconds: Int,
    val minInDoubtGraceSeconds: Int,
    val maxInDoubtGraceSeconds: Int,
    val minStallWindowSeconds: Int,
    val maxStallWindowSeconds: Int,
)

/**
 * `GET /api/site-settings` 의 응답(S2 스펙 §6.1, S3c 스펙 §8). [history] 는 최신부터이며 첫 행이 [current] 다.
 *
 * @param hostTimings 실행 호스트 `GET /host/site-timings` 본문 그대로. 호스트가 닿지 않거나 200 이 아니면 `null`(모름)이다.
 *   본문 안의 `applied` 가 `null` 이면 호스트가 아직 적용한 버전이 없다는 응답이고 모름과 다르다.
 */
data class SiteSettingsView(
    val current: SiteSettingsRecord,
    val range: SiteSettingsRangeView,
    val history: List<SiteSettingsRecord>,
    val hostTimings: JsonNode?,
)

/**
 * 현장 설정 API(S2 스펙 §6, S3c 스펙 §6.2). 읽기는 모드와 관계없고, 변경은 엔지니어 모드만 한다.
 *
 * 변경 본문은 바이트로 받아 관문을 지난 뒤 직접 읽는다. 스프링에 맡기면 못 읽는 본문의 400 이 [PreRejection] 모양이 아니고,
 * 관문보다 먼저 읽혀 운영자 모드의 깨진 본문이 403 이 아니라 400 이 된다. 칸은 널 가능으로 읽는다. 빈 칸을 0 으로 읽으면
 * 누락이 버전 충돌이나 범위 밖으로 바뀐다. 범위 밖, 사유 빈칸, 못 읽는 본문은 사전 거부라 조작 기록에 남지 않는다.
 * 기존 `PROFILE_REQUIRED` 와 같은 길이다. 쓰기 본문은 `application/json` 만 받는다(다른 출처 방어는 [RobotOperationsController]).
 *
 * 값 칸 다섯은 모두 선택이다(S3c 스펙 §6.2). 칸이 없거나 `null` 이면 빠진 것이고 기준 버전의 값으로 채운다. 칸이 있는데
 * 정수가 아니면(글자, 소수) 빠진 것으로 보지 않고 400 이다. 빠진 것으로 보면 화면이 보낸 값이 조용히 기준 버전 값으로 바뀐다.
 *
 * 같은 값으로 바꿔도 새 버전이 생긴다. 버전은 값이 아니라 변경의 기록이다.
 */
@RestController
class SiteSettingsController(
    private val store: SiteSettingsStore,
    private val operations: SiteSettingsOperations,
    private val host: HostSiteTimings,
) {
    private val json = ObjectMapper()

    @GetMapping("/api/site-settings")
    fun read(): SiteSettingsView {
        val history = store.history()
        return SiteSettingsView(
            current = history.first(),
            range = SiteSettingsRangeView(
                SiteSettingsRange.MIN_CONNECTION_THRESHOLD_SECONDS,
                SiteSettingsRange.MAX_CONNECTION_THRESHOLD_SECONDS,
                SiteSettingsRange.MIN_EVIDENCE_BEFORE_SECONDS,
                SiteSettingsRange.MAX_EVIDENCE_BEFORE_SECONDS,
                SiteSettingsRange.MIN_EVIDENCE_AFTER_SECONDS,
                SiteSettingsRange.MAX_EVIDENCE_AFTER_SECONDS,
                SiteSettingsRange.MIN_IN_DOUBT_GRACE_SECONDS,
                SiteSettingsRange.MAX_IN_DOUBT_GRACE_SECONDS,
                SiteSettingsRange.MIN_STALL_WINDOW_SECONDS,
                SiteSettingsRange.MAX_STALL_WINDOW_SECONDS,
            ),
            history = history,
            // 호스트를 못 읽어도 현장 설정 읽기는 실패시키지 않는다. 반영 칸만 모름이다.
            hostTimings = (host.siteTimings() as? HostCall.Ok)?.value,
        )
    }

    @PutMapping("/api/site-settings", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun change(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        val node = body?.let { runCatching { json.readTree(it) }.getOrNull() }?.takeIf { it.isObject }
        val base = node?.get("baseVersion")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, BAD_REQUEST, "baseVersion 이 정수로 있어야 한다")
        val values = SiteSettingsRange.FIELDS.map { (field, _) ->
            val value = node.get(field)
            when {
                value == null || value.isNull -> field to null
                value.isIntegralNumber && value.canConvertToLong() -> field to value.asLong()
                else -> return@guarded reject(HttpStatus.BAD_REQUEST, BAD_REQUEST, "$field 가 정수가 아니다")
            }
        }
        if (values.all { it.second == null }) {
            return@guarded reject(
                HttpStatus.BAD_REQUEST, BAD_REQUEST,
                "값 칸(${SiteSettingsRange.FIELDS.joinToString(", ") { it.first }}) 중 하나 이상이 정수로 있어야 한다",
            )
        }
        val problems = values.mapNotNull { (field, value) -> value?.let { SiteSettingsRange.problem(field, it) } }
        if (problems.isNotEmpty()) {
            return@guarded reject(HttpStatus.BAD_REQUEST, "SETTING_OUT_OF_RANGE", problems.joinToString("; "))
        }
        val reason = node.get("reason")?.takeIf { it.isTextual }?.asText()?.trim()
        if (reason.isNullOrEmpty()) {
            return@guarded reject(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "변경 사유가 없다")
        }
        // 범위 검사를 지났으므로 값은 Int 에 든다.
        val seconds = values.toMap().mapValues { it.value?.toInt() }
        val change = SiteSettingsChange(
            connectionThresholdSeconds = seconds[SiteSettingsRange.CONNECTION_THRESHOLD],
            evidenceBeforeSeconds = seconds[SiteSettingsRange.EVIDENCE_BEFORE],
            evidenceAfterSeconds = seconds[SiteSettingsRange.EVIDENCE_AFTER],
            inDoubtGraceSeconds = seconds[SiteSettingsRange.IN_DOUBT_GRACE],
            stallWindowSeconds = seconds[SiteSettingsRange.STALL_WINDOW],
        )
        ResponseEntity.ok(operations.change(actor, base, change, reason))
    }

    private companion object {
        const val BAD_REQUEST = "SETTINGS_BAD_REQUEST"
    }
}
