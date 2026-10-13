package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.ops.host.ClearOutcome
import dev.picasso.ops.host.MissionHost
import dev.picasso.ops.host.ResidualHoldsView
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 운영자 실행 취소와 잔여 파지 표시(P0-b2). 루프백이고 인증이 없다. 호출자는 운영 서비스뿐이다. 모드 검사(운영자)와 조작 기록은 운영
 * 서비스가 한다. 사유는 호스트도 받아 자기 표에 적는다(취소·해소의 근거가 호스트 기록에 남아야 재기동 뒤에도 읽힌다).
 *
 * 승인자는 늘 `Approver(approverId, PERSON)` 이다(운영자 판단과 같이 ADR 43, 기본값 없음). 에이전트 승인자의 거부는 호스트 메서드를
 * 직접 부를 때만 난다. POST 는 `application/json` 만 받는다(S3a 와 같은 까닭).
 *
 * 취소의 결과(CANCELED·NOT_CANCELABLE·NOT_FOUND·REFUSED)는 늘 200 의 본문에 있다(운영자 판단과 같은 모양). 해소는 200(해소함),
 * 404 `RESIDUAL_HOLD_NOT_FOUND`(그 기체에 그 표시가 없음), 409 `RESIDUAL_HOLD_ALREADY_CLEARED`(이미 해소함)다. 기록을 못 적으면
 * 500 `RECORD_WRITE_FAILED` 이고 아무것도 바꾸지 않는다.
 */
@RestController
class ExecutionControlController(private val host: MissionHost, private val json: ObjectMapper) {

    /**
     * 본문의 `instanceId` 가 지금 인스턴스가 아니면 409 `INSTANCE_MISMATCH` 이고 취소하지 않는다. `exec-N` 은 인스턴스마다 다시 세므로
     * 이전 인스턴스의 실행 id 가 지금 인스턴스의 다른 실행을 가리킬 수 있다(운영자 판단과 같은 까닭).
     */
    @PostMapping("/host/executions/{executionId}/cancel", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun cancel(@PathVariable executionId: String, @RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> {
        val request = try {
            HostRequests.cancel(body?.let { runCatching { json.readTree(it) }.getOrNull() })
        } catch (e: BadRequest) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
        }
        if (request.instanceId != host.instanceId) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                HostRejection(
                    IncidentController.INSTANCE_MISMATCH,
                    "취소 요청의 인스턴스(${request.instanceId})가 지금 인스턴스(${host.instanceId})가 아니다",
                ),
            )
        }
        return ResponseEntity.ok(host.cancel(executionId, Approver(request.approverId, ApproverKind.PERSON), request.reason, request.requestId))
    }

    @GetMapping("/host/robots/residual-holds")
    fun residualHolds(): ResidualHoldsView = host.residualHolds()

    @PostMapping("/host/robots/{robotId}/residual-holds/{markerId}/clear", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun clear(@PathVariable robotId: String, @PathVariable markerId: String, @RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> {
        val id = markerId.toLongOrNull()?.takeIf { it > 0 }
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(HostRequests.BAD_REQUEST, "markerId 는 양의 정수다: $markerId"))
        val request = try {
            HostRequests.clearing(body?.let { runCatching { json.readTree(it) }.getOrNull() })
        } catch (e: BadRequest) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
        }
        return when (val outcome = host.clearResidualHold(robotId, id, Approver(request.approverId, ApproverKind.PERSON), request.reason, request.requestId)) {
            is ClearOutcome.Cleared -> ResponseEntity.ok(outcome.view)
            ClearOutcome.NotFound -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(HostRejection(RESIDUAL_HOLD_NOT_FOUND, "기체 $robotId 에 잔여 파지 표시 $id 가 없다"))
            is ClearOutcome.AlreadyCleared -> ResponseEntity.status(HttpStatus.CONFLICT).body(
                HostRejection(
                    RESIDUAL_HOLD_ALREADY_CLEARED,
                    "잔여 파지 표시 $id 는 이미 해소했다(${outcome.clearing.approverId}, ${outcome.clearing.clearedAt})",
                ),
            )
            is ClearOutcome.Refused -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(HostRejection(APPROVER_REFUSED, outcome.reason))
        }
    }

    /** 취소·해소 기록을 못 적었다. 아무것도 바꾸지 않았다(취소는 기록을 먼저 적고 미들웨어를 부른다). */
    @ExceptionHandler(DataAccessException::class)
    fun recordFailed(e: DataAccessException): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(HostRejection(RECORD_WRITE_FAILED, e.mostSpecificCause.message ?: e.toString()))

    companion object {
        const val RESIDUAL_HOLD_NOT_FOUND = "RESIDUAL_HOLD_NOT_FOUND"
        const val RESIDUAL_HOLD_ALREADY_CLEARED = "RESIDUAL_HOLD_ALREADY_CLEARED"
        const val APPROVER_REFUSED = "APPROVER_REFUSED"
        const val RECORD_WRITE_FAILED = "RECORD_WRITE_FAILED"
    }
}
