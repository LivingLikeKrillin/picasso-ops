package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 출발 미관측의 보류와 사람의 출발 자리 확인을 실제 세 프로세스(현장·실행 호스트·운영 서비스)로 한 번 돈다.
 *
 * 출발 관측을 요구한 버전(REQUIRED)을 활성화하고, 셀 대역이 말하지 않는 출발 자리(현장 픽스처에 없는 제시 자리)로 수동 작업 요청을
 * 낸다. 실행 호스트는 보내지 않고 기체별 사유에 출발 미관측을 싣는다. 운영 서비스는 결품과 따로 확인 대기로 보이고 자동으로 다시 내지
 * 않는다. 운영자가 출발 자리 확인을 기록하면 같은 작업 지시가 그 확인을 셀 관측으로 싣고 다시 나가 게이트를 지나 실행이 선다.
 */
class SourceObservationTest {

    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver
        private val json = ObjectMapper()

        private const val WORK_MASTER = "PrepareSequencedRack"
        private const val MISSIONS = "/api/missions/$WORK_MASTER"
        private const val MATERIAL = "ENGINE-COVER-A"

        /** 현장 셀 대역 픽스처에 없는 제시 자리. 셀 대역은 이 자리에 대해 말하지 않는다. */
        private const val SILENT_SOURCE = "SEQ-IN-09.BIN-Z"

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start()
            driver = ExecutionDriver(stack)
            Commissioned.complete(stack)
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }
    }

    @Test
    fun `출발 미관측은 확인 대기로 보류되고 사람의 출발 자리 확인을 기록하면 같은 작업 지시가 게이트를 지나 실행이 선다`() {
        val template = stack.get("/api/missions/templates/$WORK_MASTER")["templates"].single { it["id"].asText() == "DATA_V1" }["definition"].asText()
        val required = template.replace("\"sourceObservation\": \"OPTIONAL\"", "\"sourceObservation\": \"REQUIRED\"")
        check(required != template) { "출발 관측 선언을 바꾸지 못했다" }
        val saved = engineer("$MISSIONS/drafts", json.createObjectNode().put("definition", required).toString())
        val draftId = saved["outcome"]["draft"]["draftId"].asLong()
        val mocked = engineer("$MISSIONS/drafts/$draftId/mock-run", "{}")
        assertEquals("PASSED", mocked["outcome"]["result"].asText(), "$mocked")
        val activated = engineer("$MISSIONS/drafts/$draftId/activate", """{"reason":"출발 자리 관측 요구"}""")
        assertEquals("ACTIVATED", activated["outcome"]["result"].asText(), "$activated")

        val form = """{"workMasterId":"$WORK_MASTER","slots":["RACK-204.S01"],"material":"$MATERIAL","presentation":"$SILENT_SOURCE"}"""
        val received = operator("/api/work-requests/manual", """{"requestId":"${UUID.randomUUID()}","form":$form}""", 202)["receipt"]
        val id = received["receiptId"].asText()
        assertEquals("UNASSIGNED", received["state"].asText(), "$received")

        val held = stack.get("/api/work-requests/$id")
        val refusal = held["unassigned"]["refusals"].single { it["robotId"].asText() == HUMANOID }
        assertEquals(SILENT_SOURCE, refusal["sourceUnobserved"]["source"].asText(), "$refusal")
        assertTrue(refusal["sourceUnobserved"]["occupied"].isNull, "$refusal")
        assertTrue(refusal["sourceMissing"].isNull && refusal["alternativeLocations"].isNull, "$refusal")
        assertEquals(SILENT_SOURCE, held["pendingSources"].single()["source"].asText(), "$held")
        assertEquals(0, driver.executions()["executions"].size())

        // 자동으로 다시 내지 않는다.
        driver.push(ExecutionDriver.STEP)
        assertEquals("UNASSIGNED", stack.get("/api/work-requests/$id")["receipt"]["state"].asText())
        assertEquals(0, driver.executions()["executions"].size())

        val confirmation = json.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("source", SILENT_SOURCE).put("identity", MATERIAL)
            .put("reason", "현장 담당자 김이 출발 자리 바코드 확인").put("expectedUpdatedAt", held["receipt"]["updatedAt"].asText())
        val confirmed = operator("/api/work-requests/$id/source-confirmations", confirmation.toString(), 202)["receipt"]
        assertEquals("ACCEPTED", confirmed["state"].asText(), "$confirmed")
        val executionId = confirmed["submission"]["outcome"]["executionId"].asText()
        // 같은 작업 지시가 게이트를 지나 실행이 섰다. 현장 셀 대역은 모르는 제시 자리의 자재를 몰라 슬롯을 신원 없이 채우므로
        // 정착 판정(E2 신원 대조)은 이 시험이 보지 않는다.
        val execution = driver.execution(executionId)
        assertEquals(received["jobOrderId"].asText() to HUMANOID, execution["jobOrderId"].asText() to execution["robotId"].asText(), "$execution")

        val after = stack.get("/api/work-requests/$id")
        assertEquals(0, after["pendingSources"].size(), "$after")
        val recorded = after["sourceConfirmations"].single()
        assertEquals(listOf("operator/kim", SILENT_SOURCE, MATERIAL), listOf(recorded["actor"].asText(), recorded["source"].asText(), recorded["identity"].asText()), "$recorded")
    }

    private fun engineer(path: String, body: String): JsonNode {
        val reply = stack.send("POST", path, "engineer", body = body)
        assertEquals(200, reply.status, "$path → ${reply.body}")
        assertEquals("SUCCEEDED", reply.body!!["result"].asText(), "$path → ${reply.body}")
        return reply.body
    }

    private fun operator(path: String, body: String, status: Int): JsonNode {
        val reply = stack.send("POST", path, "operator", body = body)
        assertEquals(status, reply.status, "$path → ${reply.body}")
        return reply.body!!
    }
}
