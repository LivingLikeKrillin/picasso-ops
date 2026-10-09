package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostJobResponses
import dev.picasso.ops.service.web.JobResponseController
import dev.picasso.ops.service.web.PreRejection
import org.springframework.http.ResponseEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * 작업 응답 송신 기록 중계(S4b 스펙 T9). 컨트롤러를 스프링 없이 바로 부른다. 호스트는 대역이고 받은 쿼리 인자를 적는다.
 */
class JobResponseControllerTest {

    private val json = ObjectMapper()
    private val asked = mutableListOf<Pair<String?, Int?>>()
    private var answer: HostCall<JsonNode> = HostCall.Ok(json.readTree(BODY))
    private val controller = JobResponseController(
        HostJobResponses { jobOrderId, limit ->
            asked += jobOrderId to limit
            answer
        },
    )

    private fun ResponseEntity<Any>.rejection(): PreRejection = assertIs<PreRejection>(body)

    @Test
    fun `송신 기록은 호스트 본문 그대로이고 작업 지시 id 와 limit 을 있을 때만 넘긴다`() {
        val all = controller.jobResponses(null, null)
        assertEquals(200, all.statusCode.value())
        assertSame((answer as HostCall.Ok).value, all.body)
        assertEquals(200, controller.jobResponses("JO-1", null).statusCode.value())
        assertEquals(200, controller.jobResponses("JO-1", "500").statusCode.value())
        assertEquals(200, controller.jobResponses(null, "1").statusCode.value())
        assertEquals(listOf(null to null, "JO-1" to null, "JO-1" to 500, null to 1), asked)
    }

    @Test
    fun `빈 작업 지시 id 나 범위 밖 limit 은 호스트를 부르지 않는 400 JOB_RESPONSE_BAD_REQUEST 다`() {
        listOf("" to null, " " to null, null to "0", null to "501", null to "x", null to "").forEach { (jobOrderId, limit) ->
            val reply = controller.jobResponses(jobOrderId, limit)
            assertEquals(400, reply.statusCode.value(), "$jobOrderId $limit")
            assertEquals(JobResponseController.JOB_RESPONSE_BAD_REQUEST, reply.rejection().error)
        }
        assertEquals("jobOrderId 는 비어 있지 않은 문자열이다", controller.jobResponses("", null).rejection().detail)
        assertEquals("limit 은 1~500 의 정수다: 501", controller.jobResponses(null, "501").rejection().detail)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `호스트가 안 닿으면 503 HOST_SILENT 다`() {
        answer = HostCall.Silent("응답 없음: ConnectException")
        val reply = controller.jobResponses("JO-1", null)
        assertEquals(503, reply.statusCode.value())
        assertEquals("HOST_SILENT", reply.rejection().error)
        assertEquals("실행 호스트가 답하지 않는다: 응답 없음: ConnectException", reply.rejection().detail)
    }

    companion object {
        /** 송신 기록 본문(S4b 계약 H6). 재기동 중복 한 줄과 송신 한 줄이다. */
        const val BODY = """{"instanceId":"i-2","total":2,"responses":[
            {"instanceId":"i-2","jobResponseId":"resp-1","jobOrderId":"JO-1","executionId":"exec-1","version":1,
             "physicalState":"PARTIAL","requiredEvidence":"E2","reachedEvidence":"E0","completedUnits":[],"unverifiedUnits":[],
             "inDoubtUnits":[],"incompleteUnits":["rack-arrival"],"operatorRequired":true,"residualHold":"HOLD_KIND_UNSPECIFIED",
             "blockedBy":[],"disposition":"RESTART_DUPLICATE","recordedAt":"2026-10-09T09:00:02Z"},
            {"instanceId":"i-1","jobResponseId":"resp-1","jobOrderId":"JO-1","executionId":"exec-1","version":1,
             "physicalState":"PARTIAL","requiredEvidence":"E2","reachedEvidence":"E0","completedUnits":[],"unverifiedUnits":[],
             "inDoubtUnits":[],"incompleteUnits":["rack-arrival"],"operatorRequired":true,"residualHold":"HOLD_KIND_UNSPECIFIED",
             "blockedBy":[],"disposition":"SENT","recordedAt":"2026-10-09T09:00:01Z"}]}"""
    }
}
