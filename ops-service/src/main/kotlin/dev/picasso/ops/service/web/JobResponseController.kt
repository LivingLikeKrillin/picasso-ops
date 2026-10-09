package dev.picasso.ops.service.web

import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostJobResponses
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 작업 응답 송신 기록 읽기(S4b 스펙 T9·§8). 호스트 `GET /host/job-responses` 본문을 그대로 넘긴다. 모드와 관계없다.
 *
 * 쿼리 `jobOrderId` 와 `limit` 은 있을 때만 호스트에 싣는다. 빈 `jobOrderId` 와 1~[MAX_LIMIT] 밖의 `limit` 은 호스트에 닿지
 * 않은 400 `JOB_RESPONSE_BAD_REQUEST` 다. 호스트가 안 닿으면 다른 호스트 읽기와 같이 503 `HOST_SILENT` 다. 새 쓰기 조작이
 * 아니므로 조작 기록에 남지 않는다.
 */
@RestController
class JobResponseController(private val host: HostJobResponses) {

    @GetMapping("/api/job-responses")
    fun jobResponses(
        @RequestParam(required = false) jobOrderId: String?,
        @RequestParam(required = false) limit: String?,
    ): ResponseEntity<Any> {
        if (jobOrderId != null && jobOrderId.isBlank()) {
            return reject(HttpStatus.BAD_REQUEST, JOB_RESPONSE_BAD_REQUEST, "jobOrderId 는 비어 있지 않은 문자열이다")
        }
        val parsed = limit?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
                ?: return reject(HttpStatus.BAD_REQUEST, JOB_RESPONSE_BAD_REQUEST, "limit 은 1~$MAX_LIMIT 의 정수다: $raw")
        }
        return when (val call = host.jobResponses(jobOrderId, parsed)) {
            is HostCall.Ok -> ResponseEntity.ok(call.value)
            is HostCall.Silent ->
                reject(HttpStatus.SERVICE_UNAVAILABLE, JobOrderController.HOST_SILENT, "실행 호스트가 답하지 않는다: ${call.cause}")
        }
    }

    companion object {
        const val JOB_RESPONSE_BAD_REQUEST = "JOB_RESPONSE_BAD_REQUEST"

        /** 호스트 송신 기록이 받는 최대(S4b 계약 H6). */
        const val MAX_LIMIT = 500
    }
}
