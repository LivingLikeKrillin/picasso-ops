package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.ops.host.ClearOutcome
import dev.picasso.ops.host.CloseInProgress
import dev.picasso.ops.host.CloseRequestReused
import dev.picasso.ops.host.MissionHost
import dev.picasso.ops.host.ReleaseRequestReused
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 운영자 실행 취소와 잔여 파지 표시(P0-b2), 운영자 결함 감수(결함 감수·조치 승인 노출 설계 제안 §2.1). 루프백이고 인증이 없다. 호출자는 운영 서비스뿐이다. 모드 검사(운영자)와 조작 기록은 운영
 * 서비스가 한다. 사유는 호스트도 받아 자기 표에 적는다(취소·해소의 근거가 호스트 기록에 남아야 재기동 뒤에도 읽힌다).
 *
 * 승인자는 늘 `Approver(approverId, PERSON)` 이다(운영자 판단과 같이 ADR 43, 기본값 없음). 에이전트 승인자의 거부는 호스트 메서드를
 * 직접 부를 때만 난다. POST 는 `application/json` 만 받는다(S3a 와 같은 까닭).
 *
 * 취소의 결과(CANCELED·NOT_CANCELABLE·NOT_FOUND·REFUSED)는 늘 200 의 본문에 있다(운영자 판단과 같은 모양). 해소는 200(해소함),
 * 404 `RESIDUAL_HOLD_NOT_FOUND`(그 기체에 그 표시가 없음), 409 `RESIDUAL_HOLD_ALREADY_CLEARED`(이미 해소함)다. 기록을 못 적으면
 * 500 `RECORD_WRITE_FAILED` 이고 아무것도 바꾸지 않는다.
 *
 * 감수의 결과(RELEASED·NOT_BLOCKED·BLOCK_CHANGED·NOT_FOUND·REFUSED)도 늘 200 의 본문에 있다. 같은 requestId 의 감수 행이 이미 있으면
 * 409 `REQUEST_ID_REUSED` 이고(S3b JSON 계약 §3 과 같은 이름), 감수 행을 못 적으면 500 `RECORD_WRITE_FAILED` 이고 감수하지 않는다.
 *
 * 복원 못 한 실행의 운영자 종결(결함 14)은 작업 지시 id 로 한다(메모리에 실행이 없다). 결과(CLOSED·ALREADY_CLOSED·NOT_CLOSABLE·TASK_LIVE·
 * STOP_UNCONFIRMED·TARGET_CHANGED·NOT_FOUND·REFUSED)는 늘 200 의 본문에 있고, 같은 requestId 의 종결 행이 이미 있으면 409 `REQUEST_ID_REUSED`,
 * 종결 행을 못 적으면 500 `RECORD_WRITE_FAILED` 이고 아무것도 적지 않는다. 종결 조회는 종결 행이 있으면 200, 없으면 404 `NOT_CLOSED`, 조회가 준 요청 id 의 종결을 처리 중이면 409 `REQUEST_IN_PROGRESS` 다.
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

    /**
     * 운영자 결함 감수. 본문의 `instanceId` 가 지금 인스턴스가 아니면 409 `INSTANCE_MISMATCH` 이고 감수하지 않는다(취소와 같은 까닭).
     * 판정 순서와 결과는 [MissionHost.release] 다.
     */
    @PostMapping("/host/executions/{executionId}/release", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun release(@PathVariable executionId: String, @RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> {
        val request = try {
            HostRequests.release(body?.let { runCatching { json.readTree(it) }.getOrNull() })
        } catch (e: BadRequest) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
        }
        if (request.instanceId != host.instanceId) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                HostRejection(
                    IncidentController.INSTANCE_MISMATCH,
                    "수용 요청의 인스턴스(${request.instanceId})가 지금 인스턴스(${host.instanceId})가 아니다",
                ),
            )
        }
        return try {
            ResponseEntity.ok(
                host.release(executionId, Approver(request.approverId, ApproverKind.PERSON), request.reason, request.sawFaults, request.requestId),
            )
        } catch (e: ReleaseRequestReused) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(HostRejection(MissionController.REQUEST_ID_REUSED, e.message ?: ""))
        }
    }

    /**
     * 복원 못 한 실행의 운영자 종결. 본문 `{approverId, reason, requestId, executionInstanceId, executionId, attestedStopped}` 는
     * [HostRequests.close] 가 읽는다. 대상이 작업 지시 id 라 `instanceId` 막이는 없다 — 낡은 값으로 닫는 것은 `TARGET_CHANGED` 가 막는다.
     * 판정 순서와 결과는 [MissionHost.closeUnrestored] 다.
     */
    @PostMapping("/host/job-orders/{jobOrderId}/close", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun close(@PathVariable jobOrderId: String, @RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> {
        val request = try {
            HostRequests.close(body?.let { runCatching { json.readTree(it) }.getOrNull() })
        } catch (e: BadRequest) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
        }
        return try {
            ResponseEntity.ok(
                host.closeUnrestored(
                    jobOrderId, Approver(request.approverId, ApproverKind.PERSON), request.reason, request.requestId,
                    request.executionInstanceId to request.executionId, request.attestedStopped,
                ),
            )
        } catch (e: CloseRequestReused) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(HostRejection(MissionController.REQUEST_ID_REUSED, e.message ?: ""))
        }
    }

    /**
     * 그 작업 지시의 운영자 종결. DB 만 읽고 호스트 잠금을 잡지 않는다. 종결하지 않았으면 404 `NOT_CLOSED` 다. `requestId` 를 주면 그 요청 id 의 종결을
     * 아직 처리 중일 때 409 `REQUEST_IN_PROGRESS` 다(운영 서비스의 응답 없음 뒤 재조회가 «반영 안 됨» 으로 읽지 않도록). UUID 가 아니면 400 이다.
     */
    @GetMapping("/host/job-orders/{jobOrderId}/closure")
    fun closure(@PathVariable jobOrderId: String, @RequestParam(required = false) requestId: String?): ResponseEntity<Any> {
        val key = requestId?.let { text ->
            runCatching { UUID.fromString(text) }.getOrNull()
                ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(HostRequests.BAD_REQUEST, "requestId 가 UUID 문자열이 아니다"))
        }
        val closure = try {
            host.closure(jobOrderId, key)
        } catch (e: CloseInProgress) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(HostRejection(MissionController.REQUEST_IN_PROGRESS, e.message ?: ""))
        }
        return closure?.let { ResponseEntity.ok<Any>(it) }
            ?: ResponseEntity.status(HttpStatus.NOT_FOUND).body(HostRejection(NOT_CLOSED, "해당 작업 지시는 운영자 종결하지 않았다: $jobOrderId"))
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

    /** 취소·해소·결함 수용·종결 기록을 못 적었다. 아무것도 바꾸지 않았다(취소와 수용은 기록을 먼저 적고 미들웨어를 부른다). */
    @ExceptionHandler(DataAccessException::class)
    fun recordFailed(e: DataAccessException): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(HostRejection(RECORD_WRITE_FAILED, e.mostSpecificCause.message ?: e.toString()))

    companion object {
        const val RESIDUAL_HOLD_NOT_FOUND = "RESIDUAL_HOLD_NOT_FOUND"
        const val RESIDUAL_HOLD_ALREADY_CLEARED = "RESIDUAL_HOLD_ALREADY_CLEARED"
        const val APPROVER_REFUSED = "APPROVER_REFUSED"
        const val NOT_CLOSED = "NOT_CLOSED"
        const val RECORD_WRITE_FAILED = "RECORD_WRITE_FAILED"
    }
}
