package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.ops.host.MissionHost
import dev.picasso.ops.host.cell.CellBandClient
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 인시던트 조회, 운영자 판단, 장애 주입 전달(S3c 스펙 §7.2, S4a 스펙 §6). 루프백이고 인증이 없다. 호출자는 운영 서비스뿐이다. 모드
 * 검사(주입은 엔지니어, 판단은 운영자)와 사유, 조작 기록은 운영 서비스가 한다.
 *
 * 조회와 판단은 호스트 잠금 아래에서 하고, 장애 주입은 신호 조작처럼 잠금 밖에서 현장에 넘긴다. POST 는 `application/json` 만
 * 받는다(S3a 와 같은 까닭). 판단의 결과(Resolved·NotHeld·Refused)는 늘 200 의 본문에 있다.
 */
@RestController
class IncidentController(
    private val host: MissionHost,
    private val cellBand: CellBandClient,
    private val json: ObjectMapper,
) {

    /** 최신부터 많아야 [limit] 개. 정수가 아니거나 1~[MAX_LIMIT] 밖이면 400 `BAD_REQUEST` 다. */
    @GetMapping("/host/incidents")
    fun incidents(@RequestParam(required = false) limit: String?): ResponseEntity<Any> {
        val count = if (limit == null) DEFAULT_LIMIT else limit.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(HostRejection(HostRequests.BAD_REQUEST, "limit 은 1~$MAX_LIMIT 의 정수다: $limit"))
        return ResponseEntity.ok(host.incidents(count))
    }

    /** 인시던트 하나의 상세. 없으면 404 `INCIDENT_NOT_FOUND` 다. 호스트를 재기동하면 앞 인시던트는 사라진다(S4b 의 자리). */
    @GetMapping("/host/incidents/{incidentId}")
    fun incident(@PathVariable incidentId: String): ResponseEntity<Any> {
        val detail = host.incident(incidentId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(HostRejection(INCIDENT_NOT_FOUND, "그 인시던트가 없다: $incidentId"))
        return ResponseEntity.ok(detail)
    }

    /**
     * 운영자 판단. 승인자는 늘 `Approver(approverId, PERSON)` 이다(ADR 43 에 따라 기본값이 없고 approverId 는 필수다). 실행이 없거나
     * 그 단위가 보류가 아니면 NotHeld 이며 404 를 내지 않는다.
     */
    @PostMapping("/host/executions/{executionId}/units/{unitId}/resolve", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun resolve(
        @PathVariable executionId: String,
        @PathVariable unitId: String,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        val request = try {
            HostRequests.resolution(body?.let { runCatching { json.readTree(it) }.getOrNull() })
        } catch (e: BadRequest) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
        }
        return ResponseEntity.ok(
            host.resolve(executionId, unitId, request.decision, Approver(request.approverId, ApproverKind.PERSON), request.requestId),
        )
    }

    /**
     * 장애 주입을 현장 `POST /faults` 에 그대로 넘긴다(T1). 현장의 응답(200·400·404·409·415)은 상태 코드와 본문 그대로 돌려준다.
     * 호스트는 종류를 해석하지 않고 잠금을 잡지 않는다. 현장이 안 닿으면 신호 조작과 같은 503 `CELL_SILENT` 다.
     */
    @PostMapping("/host/faults", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun injectFault(@RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> {
        val relay = cellBand.injectFault(body ?: ByteArray(0))
            ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(HostRejection(MissionController.CELL_SILENT, "현장 셀 대역이 답하지 않는다"))
        val builder = ResponseEntity.status(relay.status)
        relay.contentType?.let { builder.contentType(MediaType.parseMediaType(it)) }
        return builder.body(relay.body)
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500

        /** 오류 이름(S4a JSON 계약 §3). */
        const val INCIDENT_NOT_FOUND = "INCIDENT_NOT_FOUND"
    }
}
