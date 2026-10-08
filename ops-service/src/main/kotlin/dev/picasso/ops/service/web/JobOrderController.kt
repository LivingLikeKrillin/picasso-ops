package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.joborders.FormRejection
import dev.picasso.ops.service.joborders.JobOrderDraft
import dev.picasso.ops.service.joborders.JobOrderEligibility
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.joborders.JobOrderSubmission
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 작업 지시와 실행 API(S3a 스펙 §8). 판정과 두 조회는 모드와 관계없고, 제출은 운영자 모드만 한다.
 *
 * 폼 본문은 바이트로 받아 직접 읽는다([SiteSettingsController] 와 같은 이유). 제출은 관문을 먼저 지나므로 엔지니어 모드의
 * 깨진 본문은 400 이 아니라 403 이다. 폼 오류(`JOB_ORDER_BAD_REQUEST`·`UNKNOWN_WORK_MASTER`·`UNIT_ID_CONFLICT`)와 후보 없음
 * (`NO_ELIGIBLE_ROBOT`)은 호스트에 닿지 않은 사전 거부라 조작 기록에 남지 않는다. 쓰기 본문은 `application/json` 만 받는다
 * (다른 출처 방어는 [RobotOperationsController]). 판정은 POST 지만 부작용이 없다. 폼 초안을 본문으로 실어야 해서 POST 다.
 *
 * 제출 결과는 호스트가 무엇을 답했든 200 과 [dev.picasso.ops.service.joborders.JobOrderOutcome] 이다. 호스트의 판단은 본문에 있다.
 * 실행 목록과 셀은 호스트 본문을 그대로 넘기고, 호스트가 안 닿으면 503 과 이유다.
 */
@RestController
class JobOrderController(
    private val eligibility: JobOrderEligibility,
    private val operations: JobOrderOperations,
    private val host: HostReads,
) {
    private val json = ObjectMapper()

    @PostMapping("/api/job-orders/eligibility", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun eligibility(@RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = drafted(body) { draft ->
        ResponseEntity.ok(eligibility.judge(JobOrderForm.jobOrder(draft, DRAFT_ID, json)))
    }

    @PostMapping("/api/job-orders", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        drafted(body) { draft ->
            when (val submission = operations.submit(actor, draft)) {
                is JobOrderSubmission.Submitted -> ResponseEntity.ok(submission.outcome)
                is JobOrderSubmission.NoEligibleRobot ->
                    reject(HttpStatus.BAD_REQUEST, JobOrderOperations.NO_ELIGIBLE_ROBOT, submission.detail)
            }
        }
    }

    @GetMapping("/api/executions")
    fun executions(): ResponseEntity<Any> = forwarded(host.executions())

    @GetMapping("/api/cell")
    fun cell(): ResponseEntity<Any> = forwarded(host.cell())

    private inline fun drafted(body: ByteArray?, action: (JobOrderDraft) -> ResponseEntity<Any>): ResponseEntity<Any> {
        val node = body?.let { runCatching { json.readTree(it) }.getOrNull() }
        val draft = try {
            JobOrderForm.read(node)
        } catch (e: FormRejection) {
            return reject(HttpStatus.BAD_REQUEST, e.error, e.message ?: "")
        }
        return action(draft)
    }

    private fun forwarded(call: HostCall<JsonNode>): ResponseEntity<Any> = when (call) {
        is HostCall.Ok -> ResponseEntity.ok(call.value)
        is HostCall.Silent -> reject(HttpStatus.SERVICE_UNAVAILABLE, HOST_SILENT, "실행 호스트가 답하지 않는다: ${call.cause}")
    }

    companion object {
        const val HOST_SILENT = "HOST_SILENT"

        /** 판정에 싣는 작업 지시 id. 호스트는 판정에서 id 를 쓰지 않으며, 제출은 새 id 로 다시 판정한다. */
        const val DRAFT_ID = "JO-DRAFT"
    }
}
