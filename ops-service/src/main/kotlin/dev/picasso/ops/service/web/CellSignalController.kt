package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.cell.CellSignalOperations
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 셀 대역 신호 조작 API(S3b 스펙 §7, 결정 3). 운영 영역의 조작이며 운영자·엔지니어 두 모드가 한다. 행위자 헤더가 없으면
 * 400 `ACTOR_REQUIRED` 다.
 *
 * 본문은 `{"value": "<문자열>"}` 이다. 신호 값은 종류와 상관없이 늘 문자열이다(S3b JSON 계약 공통 규칙). 본문이 객체가
 * 아니거나 `value` 가 문자열이 아니면 400 `SIGNAL_BAD_REQUEST` 로 호스트를 부르기 전에 막는다. 재조회가 대조할 값이 있어야
 * 하기 때문이다. 값이 그 신호의 종류에 맞는지와 안전 신호인지는 보지 않는다. 현장 셀 대역이 보고, 그 거부는 200 본문의
 * `rejection` 으로 그대로 넘어온다(ADR 32). 화면은 안전 신호에 버튼을 두지 않는다.
 */
@RestController
class CellSignalController(private val operations: CellSignalOperations) {
    private val json = ObjectMapper()

    @PostMapping("/api/cell/signals/{name}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun write(
        @PathVariable name: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, BOTH_MODES) { actor ->
        val value = body?.let { runCatching { json.readTree(it) }.getOrNull() }
            ?.takeIf { it.isObject }?.get("value")?.takeIf { it.isTextual }?.asText()
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, BAD_REQUEST, "value 가 문자열이 아니다(신호 값은 늘 문자열이다)")
        ResponseEntity.ok(operations.write(actor, name, value))
    }

    companion object {
        const val BAD_REQUEST = "SIGNAL_BAD_REQUEST"

        /** 사람이 PLC 역할을 하는 정상 조작이라 두 모드가 다 한다(결정 3). */
        val BOTH_MODES: Set<Mode> = setOf(Mode.OPERATOR, Mode.ENGINEER)
    }
}
