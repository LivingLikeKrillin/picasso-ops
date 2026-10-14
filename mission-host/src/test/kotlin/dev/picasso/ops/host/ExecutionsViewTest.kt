package dev.picasso.ops.host

import dev.picasso.contracts.v1.HoldState
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.JobResponse
import dev.picasso.middleware.PhysicalState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * `GET /host/executions` 의 실행별 응답(HA 설계 기록 §10). 실행마다 아웃박스를 훑던 계산(`lastOrNull`)과 한 번에 모은 계산이
 * 같은 응답을 고르는지 본다. 미들웨어 없이 아웃박스 모양의 목록만 쓴다.
 */
class ExecutionsViewTest {

    private fun response(executionId: String, version: Int, id: String = "$executionId-r$version") = JobResponse(
        jobResponseId = id, jobOrderId = "JO-$executionId", executionId = executionId, version = version,
        physicalState = PhysicalState.RUNNING, requiredEvidence = Evidence.E0, reachedEvidence = Evidence.E0,
        completedUnits = emptyList(), unverifiedUnits = emptyList(), incompleteUnits = emptyMap(), operatorRequired = false,
        residualHold = HoldState.getDefaultInstance(),
    )

    /** 바꾸기 전의 계산 그대로. */
    private fun old(responses: List<JobResponse>, executionId: String): JobResponse? = responses.lastOrNull { it.executionId == executionId }

    @Test
    fun `실행별 마지막 응답은 응답이 섞이고 버전이 뒤바뀌고 응답 없는 실행이 있어도 실행마다 아웃박스를 훑던 값과 같다`() {
        val responses = listOf(
            response("exec-1", 1), response("exec-2", 2), response("exec-1", 2), response("exec-3", 1),
            response("exec-2", 1), response("exec-1", 3), response("exec-1", 3, id = "exec-1-again"),
        )
        val latest = MissionHost.latestResponses(responses)
        for (id in listOf("exec-1", "exec-2", "exec-3", "exec-4")) assertSame(old(responses, id), latest[id], id)
        // 버전이 아니라 적힌 순서다: exec-2 는 버전 1 이 뒤에 적혔다.
        assertEquals(1, latest.getValue("exec-2").version)
        assertEquals("exec-1-again", latest.getValue("exec-1").jobResponseId)
        assertNull(latest["exec-4"])
    }

    @Test
    fun `실행별 마지막 응답은 무작위로 쌓인 아웃박스에서도 실행마다 아웃박스를 훑던 값과 같다`() {
        val random = Random(20261015)
        val executions = (1..40).map { "exec-$it" }
        val responses = (1..2000).map { n -> response(executions[random.nextInt(executions.size)], random.nextInt(1, 6), id = "r-$n") }
        val latest = MissionHost.latestResponses(responses)
        for (id in executions + "exec-none") assertSame(old(responses, id), latest[id], id)
        assertEquals(responses.map { it.executionId }.toSet(), latest.keys)
    }
}
