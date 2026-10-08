package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
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

/** 허용 범위(초). 화면은 이 값으로 입력을 막고, 서버도 같은 값으로 막는다. */
data class SiteSettingsRangeView(val minConnectionThresholdSeconds: Int, val maxConnectionThresholdSeconds: Int)

/** `GET /api/site-settings` 의 응답(S2 스펙 §6.1). [history] 는 최신부터이며 첫 행이 [current] 다. */
data class SiteSettingsView(
    val current: SiteSettingsRecord,
    val range: SiteSettingsRangeView,
    val history: List<SiteSettingsRecord>,
)

/**
 * 현장 설정 API(S2 스펙 §6). 읽기는 모드와 관계없고, 변경은 엔지니어 모드만 한다.
 *
 * 변경 본문은 바이트로 받아 관문을 지난 뒤 직접 읽는다. 스프링에 맡기면 못 읽는 본문의 400 이 [PreRejection] 모양이 아니고,
 * 관문보다 먼저 읽혀 운영자 모드의 깨진 본문이 403 이 아니라 400 이 된다. 칸은 널 가능으로 읽는다. 빈 칸을 0 으로 읽으면
 * 누락이 버전 충돌이나 범위 밖으로 갈린다. 범위 밖, 사유 빈칸, 못 읽는 본문은 사전 거부라 조작 기록에 남지 않는다.
 * 기존 `PROFILE_REQUIRED` 와 같은 길이다. 쓰기 본문은 `application/json` 만 받는다(다른 출처 방어는 [RobotOperationsController]).
 *
 * 같은 값으로 바꿔도 새 버전이 생긴다. 버전은 값이 아니라 변경의 기록이다.
 */
@RestController
class SiteSettingsController(
    private val store: SiteSettingsStore,
    private val operations: SiteSettingsOperations,
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
            ),
            history = history,
        )
    }

    @PutMapping("/api/site-settings", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun change(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        val node = body?.let { runCatching { json.readTree(it) }.getOrNull() }
        val base = node.long("baseVersion")
        val seconds = node.long("connectionThresholdSeconds")
        if (base == null || seconds == null) {
            return@guarded reject(
                HttpStatus.BAD_REQUEST, "SETTINGS_BAD_REQUEST", "baseVersion 과 connectionThresholdSeconds 가 정수로 있어야 한다",
            )
        }
        if (!SiteSettingsRange.allows(seconds)) {
            return@guarded reject(
                HttpStatus.BAD_REQUEST, "SETTING_OUT_OF_RANGE",
                "연결 기준 시간 ${seconds}초는 범위 밖이다. " +
                    "${SiteSettingsRange.MIN_CONNECTION_THRESHOLD_SECONDS}~${SiteSettingsRange.MAX_CONNECTION_THRESHOLD_SECONDS}초여야 한다",
            )
        }
        val reason = node?.get("reason")?.takeIf { it.isTextual }?.asText()?.trim()
        if (reason.isNullOrEmpty()) {
            return@guarded reject(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "변경 사유가 없다")
        }
        ResponseEntity.ok(operations.change(actor, base, seconds.toInt(), reason))
    }

    /** 정수 칸만 받는다. 글자나 소수로 온 값은 없는 것으로 본다. */
    private fun JsonNode?.long(field: String): Long? =
        this?.get(field)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
}
