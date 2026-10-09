package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.host.ExecutionsView
import dev.picasso.ops.host.HostEligibility
import dev.picasso.ops.host.JournalWriteFailed
import dev.picasso.ops.host.MissionHost
import dev.picasso.ops.host.cell.CellSnapshot
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** `POST /host/eligibility` 의 본문. 요청의 기체 순서이며 겹친 id 는 한 번만 나온다. */
data class EligibilityView(val robots: List<HostEligibility>)

/** `GET /host/cell` 의 본문. 마지막 pump 가 셀 대역을 못 읽었으면 [cell] 이 `null` 이다(못 물어봄). */
data class CellView(val cell: CellSnapshot?)

/**
 * 실행 호스트 REST(S3a 스펙 §7.6). 루프백이고 인증이 없다. 호출자는 운영 서비스뿐이다.
 *
 * POST 는 `application/json` 만 받는다. 브라우저의 단순 요청(폼·`text/plain`)은 사전 요청 없이 다른 출처에서 올 수 있어,
 * 운영 서비스와 같이 그것을 415 로 막는다. 같은 기계의 다른 프로세스가 운영자 모드 검사 없이 작업 지시를 낼 수 있는 것은
 * 한계다(스펙 §12).
 */
@RestController
class HostController(private val host: MissionHost, private val json: ObjectMapper) {

    @PostMapping("/host/eligibility", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun eligibility(@RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = accepting(body, "robotIds") { order, robots ->
        EligibilityView(host.eligibility(order, robots))
    }

    @PostMapping("/host/job-orders", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(@RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = accepting(body, "candidates") { order, candidates ->
        host.submit(order, candidates)
    }

    @GetMapping("/host/executions")
    fun executions(): ExecutionsView = host.executions()

    @GetMapping("/host/cell")
    fun cell(): CellView = CellView(host.cell())

    /**
     * 송신 기록(S4b 스펙 T6). 최근부터 많아야 [limit] 개(기본 50, 1~500). [jobOrderId] 를 주면 그 작업 지시만이다. limit 이 틀리거나
     * jobOrderId 가 빈 문자열이면 400 `BAD_REQUEST` 다.
     */
    @GetMapping("/host/job-responses")
    fun jobResponses(@RequestParam(required = false) jobOrderId: String?, @RequestParam(required = false) limit: String?): ResponseEntity<Any> {
        val count = if (limit == null) IncidentController.DEFAULT_LIMIT else limit.toIntOrNull()?.takeIf { it in 1..IncidentController.MAX_LIMIT }
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(HostRejection(HostRequests.BAD_REQUEST, "limit 은 1~${IncidentController.MAX_LIMIT} 의 정수다: $limit"))
        if (jobOrderId != null && jobOrderId.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(HostRequests.BAD_REQUEST, "jobOrderId 가 비어 있다"))
        }
        return ResponseEntity.ok(host.jobResponses(jobOrderId, count))
    }

    /** 일지 쓰기 실패(S4b 스펙 §9). 실행은 미들웨어에 남는다. */
    @ExceptionHandler(JournalWriteFailed::class)
    fun journalFailed(e: JournalWriteFailed): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(HostRejection(JOURNAL_WRITE_FAILED, e.message ?: ""))

    companion object {
        /** 일지 쓰기 실패의 오류 이름. */
        const val JOURNAL_WRITE_FAILED = "JOURNAL_WRITE_FAILED"
    }

    private inline fun accepting(
        body: ByteArray?,
        listField: String,
        action: (dev.picasso.middleware.JobOrder, List<String>) -> Any,
    ): ResponseEntity<Any> {
        val node: JsonNode? = body?.let { runCatching { json.readTree(it) }.getOrNull() }
        val (order, robots) = try {
            HostRequests.read(node, listField)
        } catch (e: BadRequest) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
        }
        return ResponseEntity.ok(action(order, robots))
    }
}
