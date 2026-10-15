package dev.picasso.ops.service.executions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostExecutionControls
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostOperationRunner
import dev.picasso.ops.service.operations.HostRecheck
import dev.picasso.ops.service.operations.HostRejection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 운영자 실행 취소의 200 응답(P0-b2).
 *
 * @param result 조작 기록의 결과. `CANCELED` 는 성공, `NOT_CANCELABLE`·`NOT_FOUND`·`REFUSED` 는 거부, 호스트가 안 닿으면 응답 없음이다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했거나 호스트가 그 사이 재기동했으면 널이다
 * @param outcome 호스트 취소 결과 이름 그대로. 호스트가 200 으로 답했을 때만 있다
 * @param answer 호스트의 200 본문 그대로
 * @param rejection 호스트가 4xx 로 막았을 때만 있다(409 `INSTANCE_MISMATCH` 등)
 */
data class ExecutionCancelOutcome(
    val requestId: UUID,
    val executionId: String,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val outcome: String?,
    val answer: JsonNode?,
    val rejection: HostRejection?,
)

/**
 * 잔여 파지 표시 해소(기체 빈손 확인)의 200 응답(P0-b2).
 *
 * @param result 호스트가 해소하면 성공, 404 `RESIDUAL_HOLD_NOT_FOUND`·409 `RESIDUAL_HOLD_ALREADY_CLEARED` 는 거부다
 * @param answer 호스트의 200 본문(해소한 표시와 해소 행) 그대로
 */
data class RobotEmptyOutcome(
    val requestId: UUID,
    val robotId: String,
    val markerId: Long,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val answer: JsonNode?,
    val rejection: HostRejection?,
)

/**
 * 운영자 실행 취소와 기체 빈손 확인(P0-b2). 둘 다 운영자 모드의 조작이며(모드 검사는 컨트롤러의 몫) 승인자는 행위자(`X-Ops-User`)다.
 * 호스트는 그 id 로 `Approver(id, PERSON)` 을 만든다(ADR 43). 사유는 조작 기록과 호스트의 표 둘에 남는다.
 *
 * 취소: 부분 완료(`PARTIAL`) 실행은 picasso 가 계속 돌리므로 그 기체가 배정에서 빠져 있다. 운영 서비스는 작업 지시 id 를 다시 쓰지
 * 않아 리비전으로 닫을 길이 없고, 사람이 취소해야 그 실행이 `ABORTED` 로 정착한다. 응답 없음 뒤 재조회는 실행 목록으로 한다. 목록의
 * 인스턴스가 요청한 인스턴스와 같고 그 실행의 `cancel` 이 행위자의 것이며 적은 시각이 보내기 직전 이후이면 반영됨이다. 인스턴스가
 * 다르면 호스트가 그 사이 재기동한 것이라 확인하지 못한다(널).
 *
 * 빈손 확인: 정착할 때 로봇 단위의 마지막 파지 관측이 HOLDING 이던 기체에 호스트가 표시를 세우고, 표시가 있는 동안 그 기체를
 * 배정에서 뺀다. 사람이 현장에서 빈손을 확인해야 푼다. 응답 없음 뒤 재조회는 미해소 표시 목록으로 한다. 그 표시가 목록에 없으면
 * 반영됨이다(누가 풀었는지는 호스트의 해소 행이 말한다).
 */
class ExecutionControls(
    private val host: HostExecutionControls,
    log: OperationLog,
    requeryDelay: Duration = Duration.ofSeconds(1),
    private val clock: Clock = Clock.systemUTC(),
    private val json: ObjectMapper = jacksonObjectMapper(),
) {
    private val runner = HostOperationRunner(log, requeryDelay, json)

    /** 미해소 잔여 파지 표시. 호스트 본문 그대로다. */
    fun residualHolds(): HostCall<JsonNode> = host.residualHolds()

    /** [instanceId]·[reason] 은 컨트롤러가 비어 있지 않음을 본 값이다. */
    fun cancel(actor: Actor, executionId: String, instanceId: String, reason: String): ExecutionCancelOutcome {
        val request = json.createObjectNode()
            .put("op", CANCEL_OP)
            .put("executionId", executionId)
            .put("approverId", actor.user)
            .put("instanceId", instanceId)
        var sentAt: Instant? = null
        val ran = runner.run(
            actor, executionId, request, reason,
            accepted = { body -> cancelResultOf(body.path("result").takeIf { it.isTextual }?.asText()) },
            recheck = { sentAt?.let { at -> recheckCancel(executionId, instanceId, actor.user, at) } },
        ) { requestId ->
            sentAt = clock.instant()
            host.cancelExecution(executionId, actor.user, reason, instanceId, requestId)
        }
        val outcome = ran.body?.path("result")?.takeIf { it.isTextual }?.asText()
        return ExecutionCancelOutcome(ran.requestId, executionId, ran.result, ran.confirmation, outcome, ran.body, ran.rejection)
    }

    /** [reason] 은 컨트롤러가 비어 있지 않음을 본 값이다. */
    fun confirmEmpty(actor: Actor, robotId: String, markerId: Long, reason: String): RobotEmptyOutcome {
        val request = json.createObjectNode()
            .put("op", CONFIRM_EMPTY_OP)
            .put("robotId", robotId)
            .put("markerId", markerId)
            .put("approverId", actor.user)
        val ran = runner.run(
            actor, "$robotId/$markerId", request, reason,
            accepted = { body -> if (body.path("clearing").isObject) OperationResult.SUCCEEDED else null },
            recheck = { recheckClear(markerId) },
        ) { requestId -> host.clearResidualHold(robotId, markerId, actor.user, reason, requestId) }
        return RobotEmptyOutcome(ran.requestId, robotId, markerId, ran.result, ran.confirmation, ran.body, ran.rejection)
    }

    private fun recheckCancel(executionId: String, instanceId: String, approver: String, sentAt: Instant): HostRecheck? {
        val body = (host.executionsOf(executionId) as? HostCall.Ok)?.value ?: return null
        if (body.path("instanceId").asText() != instanceId) return null
        val execution = body.path("executions").takeIf { it.isArray }?.firstOrNull { it.path("executionId").asText() == executionId }
            ?: return HostRecheck(false, null)
        val cancel = execution.path("cancel").takeIf { it.isObject }
        val applied = cancel != null && cancel.path("approverId").asText() == approver &&
            instant(cancel.path("recordedAt"))?.let { it >= sentAt } == true
        return HostRecheck(applied, execution)
    }

    private fun recheckClear(markerId: Long): HostRecheck? {
        val holds = (host.residualHolds() as? HostCall.Ok)?.value?.path("holds")?.takeIf { it.isArray } ?: return null
        val open = holds.firstOrNull { it.path("markerId").asLong() == markerId }
        return HostRecheck(open == null, open)
    }

    private fun instant(node: JsonNode): Instant? =
        node.takeIf { it.isTextual }?.let { runCatching { Instant.parse(it.asText()) }.getOrNull() }

    companion object {
        const val CANCEL_OP = "CANCEL_EXECUTION"
        const val CONFIRM_EMPTY_OP = "CONFIRM_ROBOT_EMPTY"

        /** 호스트 취소 결과 이름(P0-b2). */
        const val CANCELED = "CANCELED"
        val CANCEL_REFUSALS: Set<String> = setOf("NOT_CANCELABLE", "NOT_FOUND", "REFUSED")

        fun cancelResultOf(name: String?): OperationResult? = when (name) {
            CANCELED -> OperationResult.SUCCEEDED
            in CANCEL_REFUSALS -> OperationResult.REJECTED
            else -> null
        }
    }
}
