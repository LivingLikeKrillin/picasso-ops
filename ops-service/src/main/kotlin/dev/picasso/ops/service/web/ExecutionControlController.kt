package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.executions.ExecutionControls
import dev.picasso.ops.service.host.HostCall
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 운영자 실행 취소와 잔여 파지 표시 API(P0-b2), 운영자 결함 감수 API(S3a JSON 계약 §9.8).
 *
 * 읽기(`GET /api/robots/residual-holds`)는 모드와 관계없고 호스트 본문을 그대로 넘긴다. 호스트가 안 닿으면 503 `HOST_SILENT` 다.
 *
 * 세 조작은 운영자 모드만 한다. 판정 순서는 관문(헤더 400 `ACTOR_REQUIRED`, 모드 403) → 본문(400 `CANCEL_BAD_REQUEST`·
 * `CLEAR_BAD_REQUEST`·`RELEASE_BAD_REQUEST`) → 사유(400 `REASON_REQUIRED`)다. 모두 호스트에 닿지 않은 사전 거부라 조작 기록에 남지
 * 않는다. 본문은 바이트로 받아 관문을 지난 뒤 직접 읽고, 쓰기 본문은 `application/json` 만 받는다([IncidentController] 와 같은
 * 규칙). 세 조작은 호스트가 무엇을 답했든 200 과 조작 결과다.
 */
@RestController
class ExecutionControlController(private val controls: ExecutionControls) {
    private val json = ObjectMapper()

    @GetMapping("/api/robots/residual-holds")
    fun residualHolds(): ResponseEntity<Any> = when (val call = controls.residualHolds()) {
        is HostCall.Ok -> ResponseEntity.ok(call.value)
        is HostCall.Silent -> reject(HttpStatus.SERVICE_UNAVAILABLE, JobOrderController.HOST_SILENT, "실행 호스트가 답하지 않는다: ${call.cause}")
    }

    /** 본문 `{instanceId, reason}`. instanceId 는 실행 목록의 `instanceId` 다. */
    @PostMapping("/api/executions/{executionId}/cancel", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun cancel(
        @PathVariable executionId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        val node = read(body)
        val instanceId = text(node, "instanceId")
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, CANCEL_BAD_REQUEST, "instanceId 는 비어 있지 않은 문자열이다(실행 목록의 instanceId)")
        val reason = reason(node) ?: return@guarded reject(HttpStatus.BAD_REQUEST, REASON_REQUIRED, "취소 사유가 없다")
        ResponseEntity.ok(controls.cancel(actor, executionId, instanceId, reason))
    }

    /**
     * 본문 `{instanceId, reason, sawFaults}`. instanceId 는 실행 목록의 `instanceId`, sawFaults 는 그 실행의 `blockedFaults` 에서
     * 운영자가 본 결함 `[{errorType, references:[{key, value}]}]` 이다. 호스트에는 그 칸만 옮겨 보낸다(결함 객체의 다른 칸은 버린다).
     */
    @PostMapping("/api/executions/{executionId}/release", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun release(
        @PathVariable executionId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        val node = read(body)
        val instanceId = text(node, "instanceId")
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, RELEASE_BAD_REQUEST, "instanceId 는 비어 있지 않은 문자열이다(실행 목록의 instanceId)")
        val saw = sawFaults(node?.get("sawFaults"))
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, RELEASE_BAD_REQUEST, SAW_FAULTS_SHAPE)
        val reason = reason(node) ?: return@guarded reject(HttpStatus.BAD_REQUEST, REASON_REQUIRED, "결함 감수 사유가 없다")
        ResponseEntity.ok(controls.release(actor, executionId, instanceId, reason, saw))
    }

    /** 본문 `{reason}`. [markerId] 는 미해소 표시 목록의 `markerId` 다. */
    @PostMapping("/api/robots/{robotId}/residual-holds/{markerId}/clear", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun clear(
        @PathVariable robotId: String,
        @PathVariable markerId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        val id = markerId.toLongOrNull()?.takeIf { it > 0 }
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, CLEAR_BAD_REQUEST, "markerId 는 양의 정수다: $markerId")
        val reason = reason(read(body)) ?: return@guarded reject(HttpStatus.BAD_REQUEST, REASON_REQUIRED, "빈손 확인 사유가 없다")
        ResponseEntity.ok(controls.confirmEmpty(actor, robotId, id, reason))
    }

    private fun read(body: ByteArray?): JsonNode? = body?.let { runCatching { json.readTree(it) }.getOrNull() }?.takeIf { it.isObject }

    private fun text(node: JsonNode?, field: String): String? =
        node?.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

    /** 모양이 맞으면 `errorType`·`references`(원소마다 `key`·`value`)만 옮긴 새 배열, 아니면 널이다. */
    private fun sawFaults(node: JsonNode?): ArrayNode? {
        if (node == null || !node.isArray || node.isEmpty) return null
        val saw = json.createArrayNode()
        for (fault in node) {
            val errorType = text(fault, "errorType") ?: return null
            val references = fault.get("references")?.takeIf { it.isArray } ?: return null
            val copy = saw.addObject().put("errorType", errorType)
            val refs = copy.putArray("references")
            for (reference in references) {
                val key = text(reference, "key") ?: return null
                val value = reference.get("value")?.takeIf { it.isTextual }?.asText() ?: return null
                refs.addObject().put("key", key).put("value", value)
            }
        }
        return saw
    }

    private fun reason(node: JsonNode?): String? = node?.get("reason")?.takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val CANCEL_BAD_REQUEST = "CANCEL_BAD_REQUEST"
        const val CLEAR_BAD_REQUEST = "CLEAR_BAD_REQUEST"
        const val RELEASE_BAD_REQUEST = "RELEASE_BAD_REQUEST"
        const val SAW_FAULTS_SHAPE =
            "sawFaults 는 비어 있지 않은 배열이고 원소마다 errorType 은 비어 있지 않은 문자열, references 는 {key, value} 객체의 배열이다"

        /** 현장 설정 변경·임무 활성화·운영자 판단과 같은 이름이다. */
        const val REASON_REQUIRED = MissionVersionController.REASON_REQUIRED
    }
}
