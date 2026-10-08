package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostMissions
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.missions.MissionOperations
import dev.picasso.ops.service.missions.MissionSubmission
import dev.picasso.ops.service.missions.MissionValidation
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
 * 임무 버전 API(S3b 스펙 §7). 두 읽기는 모드와 관계없고, 초안 저장·검증·모의 실행·활성화는 엔지니어 모드만 한다(T5).
 * 활성화는 사유가 있어야 한다.
 *
 * 판정 순서는 관문(헤더 400, 모드 403) → WorkMaster(400 `UNKNOWN_WORK_MASTER`) → 초안 id(400 `MISSION_BAD_REQUEST`) →
 * 본문(400 `MISSION_BAD_REQUEST`, 활성화의 사유는 400 `REASON_REQUIRED`) → 시운전 완료 기체(503
 * `COMMISSIONED_ROBOTS_UNKNOWN`)다. 모두 호스트에 닿지 않은 사전 거부라 조작 기록에 남지 않는다. 본문은 바이트로 받아 관문을
 * 지난 뒤 직접 읽는다([SiteSettingsController] 와 같은 이유: 운영자 모드의 깨진 본문은 400 이 아니라 403 이다). 쓰기 본문은
 * `application/json` 만 받는다(다른 출처 방어는 [RobotOperationsController]). 검증·모의 실행은 본문을 읽지 않지만 같은
 * 까닭으로 `application/json` 을 요구한다.
 *
 * 초안 저장·모의 실행·활성화는 호스트가 무엇을 답했든 200 과 [dev.picasso.ops.service.missions.MissionOperationOutcome]
 * 이다. 호스트의 판단(결과 이름, 거부 목록)은 본문에 있다. 검증은 조작이 아니라 호스트 본문과 거부 카드를 200 으로 내고,
 * 호스트의 4xx(없는 초안 404 `DRAFT_NOT_FOUND` 등)는 같은 상태 코드로, 호스트 불통은 503 `HOST_SILENT` 로 낸다.
 */
@RestController
class MissionVersionController(
    private val operations: MissionOperations,
    private val host: HostMissions,
) {
    private val json = ObjectMapper()

    @GetMapping("/api/missions/{workMasterId}")
    fun overview(@PathVariable workMasterId: String): ResponseEntity<Any> =
        editable(workMasterId) { forwarded(host.overview(it)) }

    @GetMapping("/api/missions/templates/{workMasterId}")
    fun templates(@PathVariable workMasterId: String): ResponseEntity<Any> =
        editable(workMasterId) { forwarded(host.templates(it)) }

    @PostMapping("/api/missions/{workMasterId}/drafts", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun saveDraft(
        @PathVariable workMasterId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = engineer(mode, user) { actor ->
        editable(workMasterId) { wm ->
            val definition = read(body)?.get("definition")?.takeIf { it.isTextual }?.asText()
                ?: return@editable reject(HttpStatus.BAD_REQUEST, BAD_REQUEST, "definition 이 문자열이 아니다(정의 JSON 은 글자로 싣는다)")
            ResponseEntity.ok(operations.saveDraft(actor, wm, definition))
        }
    }

    @PostMapping("/api/missions/{workMasterId}/drafts/{draftId}/validate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun validate(
        @PathVariable workMasterId: String,
        @PathVariable draftId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
    ): ResponseEntity<Any> = engineer(mode, user) {
        drafted(workMasterId, draftId) { wm, id ->
            when (val validation = operations.validate(wm, id)) {
                is MissionValidation.Answered -> ResponseEntity.ok(validation.reply)
                is MissionValidation.HostRejected -> ResponseEntity.status(validation.rejection.status).body(
                    PreRejection(validation.rejection.error ?: HOST_REJECTED, validation.rejection.detail ?: ""),
                )
                is MissionValidation.HostSilent -> hostSilent(validation.cause)
                is MissionValidation.RobotsUnknown -> robotsUnknown(validation.detail)
            }
        }
    }

    @PostMapping("/api/missions/{workMasterId}/drafts/{draftId}/mock-run", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun mockRun(
        @PathVariable workMasterId: String,
        @PathVariable draftId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
    ): ResponseEntity<Any> = engineer(mode, user) { actor ->
        drafted(workMasterId, draftId) { wm, id -> submitted(operations.mockRun(actor, wm, id)) }
    }

    @PostMapping("/api/missions/{workMasterId}/drafts/{draftId}/activate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun activate(
        @PathVariable workMasterId: String,
        @PathVariable draftId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = engineer(mode, user) { actor ->
        drafted(workMasterId, draftId) { wm, id ->
            val reason = read(body)?.get("reason")?.takeIf { it.isTextual }?.asText()?.trim()
            if (reason.isNullOrEmpty()) {
                return@drafted reject(HttpStatus.BAD_REQUEST, REASON_REQUIRED, "활성화 사유가 없다")
            }
            submitted(operations.activate(actor, wm, id, reason))
        }
    }

    /** 임무 편집 관문. 엔지니어 모드만 받는다(T5). 네 쓰기가 모두 이것을 지난다. */
    private inline fun engineer(mode: String?, user: String?, action: (Actor) -> ResponseEntity<Any>): ResponseEntity<Any> =
        guarded(mode, user, Mode.ENGINEER, action)

    private inline fun editable(workMasterId: String, action: (String) -> ResponseEntity<Any>): ResponseEntity<Any> {
        if (workMasterId !in MissionOperations.EDITABLE) {
            return reject(
                HttpStatus.BAD_REQUEST, JobOrderForm.UNKNOWN_WORK_MASTER,
                "편집하지 않는 임무다: $workMasterId (편집하는 것: ${MissionOperations.EDITABLE.sorted()})",
            )
        }
        return action(workMasterId)
    }

    private inline fun drafted(
        workMasterId: String,
        draftId: String,
        action: (String, Long) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> = editable(workMasterId) { wm ->
        val id = draftId.toLongOrNull()?.takeIf { it > 0 }
            ?: return@editable reject(HttpStatus.BAD_REQUEST, BAD_REQUEST, "초안 id 가 양의 정수가 아니다: $draftId")
        action(wm, id)
    }

    private fun submitted(submission: MissionSubmission): ResponseEntity<Any> = when (submission) {
        is MissionSubmission.Submitted -> ResponseEntity.ok(submission.outcome)
        is MissionSubmission.RobotsUnknown -> robotsUnknown(submission.detail)
    }

    private fun read(body: ByteArray?): JsonNode? = body?.let { runCatching { json.readTree(it) }.getOrNull() }?.takeIf { it.isObject }

    private fun forwarded(call: HostCall<JsonNode>): ResponseEntity<Any> = when (call) {
        is HostCall.Ok -> ResponseEntity.ok(call.value)
        is HostCall.Silent -> hostSilent(call.cause)
    }

    private fun hostSilent(cause: String): ResponseEntity<Any> =
        reject(HttpStatus.SERVICE_UNAVAILABLE, JobOrderController.HOST_SILENT, "실행 호스트가 답하지 않는다: $cause")

    private fun robotsUnknown(detail: String): ResponseEntity<Any> =
        reject(HttpStatus.SERVICE_UNAVAILABLE, MissionOperations.COMMISSIONED_ROBOTS_UNKNOWN, detail)

    companion object {
        const val BAD_REQUEST = "MISSION_BAD_REQUEST"

        /** 현장 설정 변경과 같은 이름이다([SiteSettingsController]). */
        const val REASON_REQUIRED = "REASON_REQUIRED"

        /** 호스트 4xx 본문에 `error` 가 없을 때의 이름. 정상 흐름에서는 나오지 않는다. */
        const val HOST_REJECTED = "HOST_REJECTED"
    }
}
