package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.host.ExecutionPages
import dev.picasso.ops.host.ExecutionSummaryView
import dev.picasso.ops.host.HostEligibility
import dev.picasso.ops.host.JournalWriteFailed
import dev.picasso.ops.host.MissionHost
import dev.picasso.ops.host.cell.CellSnapshot
import dev.picasso.ops.host.permit.WorkPermitsView
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
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
    fun eligibility(@RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = accepting(body, "robotIds") { order, robots, mission ->
        EligibilityView(host.eligibility(order, robots, mission))
    }

    /** 선택 칸 `sourceConfirmations` 는 사람이 기록한 출발 자리 확인이다([HostRequests.sourceConfirmations]). 이 제출 한 번에만 쓴다. */
    @PostMapping("/host/job-orders", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(@RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = accepting(body, "candidates") { order, candidates, mission ->
        host.submit(order, candidates, mission, HostRequests.sourceConfirmations(body?.let { json.readTree(it) }?.get("sourceConfirmations")))
    }

    /**
     * 실행 목록. 인자 없이 부르면 정착한 실행까지 모든 실행이다. `jobOrderId`·`executionId` 를 (되풀이해) 주면 그 작업 지시나 실행의
     * 실행만 담은 같은 모양이다(HA 설계 기록 §10). 값은 쉼표로 가르지 않는다. 빈 값이 있으면 400 `BAD_REQUEST` 다.
     *
     * 화면의 페이지(HA 설계 기록 §10, [ExecutionPages]): `recent=N` 은 지금 인스턴스의 남은 실행 모두와 최근 정착 이력 N 개
     * ([dev.picasso.ops.host.ExecutionPageView]), `before=<settleSeq>[&limit=M]` 은 그 정착 차례보다 앞의 정착 이력 M 개(기본
     * [ExecutionPages.DEFAULT_LIMIT], [dev.picasso.ops.host.SettledPageView])다. 정착 이력은 실행 호스트의 정착 기록(DB)에서 읽고 인스턴스를
     * 가리지 않는다. 값이 틀리거나(`recent`·`limit` 이 정수 1~[ExecutionPages.MAX] 아님, `before` 가 양의 정수 아님, 빈 값, 되풀이) 좁히는
     * 인자와 섞거나 `recent` 와 `before`·`limit` 을 섞거나 `limit` 을 `before` 없이 주면 400 `BAD_REQUEST` 다.
     */
    @GetMapping("/host/executions")
    fun executions(@RequestParam params: MultiValueMap<String, String>): ResponseEntity<Any> {
        val jobOrderIds = params[JOB_ORDER_ID].orEmpty()
        val executionIds = params[EXECUTION_ID].orEmpty()
        if (PAGE_PARAMS.any { params.containsKey(it) }) {
            if (jobOrderIds.isNotEmpty() || executionIds.isNotEmpty()) return badRequest("좁히는 인자와 recent·before 를 함께 쓸 수 없다")
            return paged(params)
        }
        if (jobOrderIds.isEmpty() && executionIds.isEmpty()) return ResponseEntity.ok(host.executions())
        if ((jobOrderIds + executionIds).any { it.isBlank() }) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(HostRequests.BAD_REQUEST, "jobOrderId·executionId 가 비어 있다"))
        }
        return ResponseEntity.ok(host.executions(jobOrderIds.toSet(), executionIds.toSet()))
    }

    /** 실행 수만(HA 설계 기록 §10). 운영 개요가 읽는다. */
    @GetMapping("/host/executions/summary")
    fun executionSummary(): ExecutionSummaryView = host.executionSummary()

    private fun paged(params: MultiValueMap<String, String>): ResponseEntity<Any> {
        val single = PAGE_PARAMS.associateWith { name ->
            val values = params[name] ?: return@associateWith null
            if (values.size != 1 || values[0].isBlank()) return badRequest("$name 은 한 번만, 비어 있지 않은 값으로 준다")
            values[0]
        }
        val recent = single[RECENT]
        val before = single[BEFORE]
        if (recent != null) {
            if (before != null || single[LIMIT] != null) return badRequest("recent 는 before·limit 과 함께 쓸 수 없다")
            val count = count(recent) ?: return badRequest("recent 는 1~${ExecutionPages.MAX} 의 정수다: $recent")
            return ResponseEntity.ok(host.executionsRecent(count))
        }
        if (before == null) return badRequest("limit 은 before 와 함께 준다")
        val cursor = before.toLongOrNull()?.takeIf { it > 0 } ?: return badRequest("before 는 양의 정수다(정착 이력의 settleSeq·nextBefore): $before")
        val limit = single[LIMIT]?.let { count(it) ?: return badRequest("limit 은 1~${ExecutionPages.MAX} 의 정수다: $it") }
            ?: ExecutionPages.DEFAULT_LIMIT
        return ResponseEntity.ok(host.executionsBefore(cursor, limit))
    }

    private fun count(raw: String): Int? = raw.toIntOrNull()?.takeIf { it in 1..ExecutionPages.MAX }

    private fun badRequest(detail: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(HostRequests.BAD_REQUEST, detail))

    @GetMapping("/host/cell")
    fun cell(): CellView = CellView(host.cell())

    /** 작업 허가의 지금(ADR 52). 현장이 대응을 선언하지 않았으면 `declared` 가 거짓이다. 읽기만 한다. */
    @GetMapping("/host/work-permits")
    fun workPermits(): WorkPermitsView = host.workPermits()

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
        /** `GET /host/executions` 를 좁히는 인자 이름. */
        const val JOB_ORDER_ID = "jobOrderId"
        const val EXECUTION_ID = "executionId"

        /** 화면 페이지 인자 이름(HA 설계 기록 §10). */
        const val RECENT = "recent"
        const val BEFORE = "before"
        const val LIMIT = "limit"
        val PAGE_PARAMS: List<String> = listOf(RECENT, BEFORE, LIMIT)

        /** 일지 쓰기 실패의 오류 이름. */
        const val JOURNAL_WRITE_FAILED = "JOURNAL_WRITE_FAILED"
    }

    private inline fun accepting(
        body: ByteArray?,
        listField: String,
        action: (dev.picasso.middleware.JobOrder, List<String>,String?) -> Any,
    ): ResponseEntity<Any> {
        val node: JsonNode? = body?.let { runCatching { json.readTree(it) }.getOrNull() }
        val (order, robots) = try {
            HostRequests.read(node, listField)
        } catch (e: BadRequest) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
        }
        return try {
            val pin=node?.get("jobOrder")?.get("missionVersion")?.let {
                if(!it.isTextual || !(it.asText()=="CODE" || it.asText().toIntOrNull()?.let{number->number>0 && number.toString()==it.asText()}==true))
                    throw BadRequest(HostRequests.BAD_REQUEST,"missionVersion은 CODE 또는 양의 정수 문자열이어야 합니다")
                it.asText()
            }
            ResponseEntity.ok(action(order, robots,pin))
        }catch(e:BadRequest){ResponseEntity.badRequest().body(HostRejection(e.error,e.message?:""))}
    }
}
