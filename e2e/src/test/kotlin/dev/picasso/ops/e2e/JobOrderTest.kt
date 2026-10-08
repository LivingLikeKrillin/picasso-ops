package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import dev.picasso.ops.e2e.Commissioned.QUADRUPED
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * S3a 완료 판정의 통합 쪽(S3a 스펙 §3·§11). 두 기체를 시운전 완료로 만든 뒤 운영 서비스 REST 로 작업 지시를 낸다. 실행 호스트가
 * 미들웨어로 mimic 기체에서 실행하고, PrepareSequencedRack 은 현장 셀 대역이 채운 슬롯으로 E2 에 이른다.
 *
 * ## 시계
 *
 * 호스트 시계는 현장 시계다(`E2eStack`). 가상 시계를 미는 규칙은 [ExecutionDriver] 에 있다.
 *
 * ## 실패 모드
 *
 * `pick_place` 의 실패 모드(GRASP_FAILED·PAYLOAD_LOST)는 기체별 시드로 추첨된다. 현장은 시드 0 이고, 이 순서(humanoid-01 이
 * InspectAsset 의 `navigate_to`·`inspect` 다음 PrepareSequencedRack 의 `pick_place` 둘)에서는 실패 모드가 나지 않는다. 순서를
 * 바꾸면 추첨이 달라질 수 있다.
 *
 * 순서가 있다. 앞 시험의 실행이 끝나야 뒤 시험의 기체가 도는 실행 없이 배정 가능하다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class JobOrderTest {

    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver

        private const val MATERIAL = "ENGINE-COVER-A"
        private const val PRESENTATION = "SEQ-IN-02.BIN-A"
        private val SLOTS = listOf("RACK-204.S01", "RACK-204.S02")

        private val INSPECT = """{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"}]}"""
        private val RACK = """{"workMasterId":"PrepareSequencedRack","slots":[${SLOTS.joinToString(",") { "\"$it\"" }}],""" +
            """"material":"$MATERIAL","presentation":"$PRESENTATION"}"""

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
    @Order(1)
    fun `PrepareSequencedRack 의 배정 가능 표에서 pick_place 가 없는 기체는 스킬 적합 하나로 불가다`() {
        val reply = stack.send("POST", "/api/job-orders/eligibility", mode = null, body = RACK)
        assertEquals(200, reply.status, "${reply.body}")
        val view = reply.body!!
        assertEquals("OK" to "OK", view["registry"].asText() to view["host"].asText(), "$view")
        val rows = view["robots"].associateBy { it["robotId"].asText() }
        assertEquals(setOf(HUMANOID, QUADRUPED), rows.keys)

        val humanoid = rows.getValue(HUMANOID)
        assertEquals(true, humanoid["eligible"].asBoolean(), "$humanoid")
        assertEquals("FIT", humanoid["host"]["skillFit"].asText())

        // 시운전·연결·도는 실행은 통과하고 스킬 적합 하나로만 빠진다.
        val quadruped = rows.getValue(QUADRUPED)
        assertEquals("COMPLETE" to "FRESH", quadruped["commissioning"].asText() to quadruped["connection"].asText(), "$quadruped")
        assertEquals("MISSING", quadruped["host"]["skillFit"].asText(), "$quadruped")
        assertEquals(listOf("pick_place"), quadruped["host"]["missingSkills"].map { it.asText() })
        assertTrue(quadruped["host"]["runningExecutionId"].isNull, "$quadruped")
        assertEquals(false, quadruped["eligible"].asBoolean())
        assertEquals(listOf("모자란 스킬: pick_place"), quadruped["reasons"].map { it.asText() })
    }

    @Test
    @Order(2)
    fun `InspectAsset 작업 지시가 배정되어 끝나고 작업 응답이 붙으며 임무 버전은 코드 정의다`() {
        val submitted = submit(INSPECT)
        val outcome = submitted["outcome"]
        assertEquals("SUCCEEDED" to "ACCEPTED", submitted["result"].asText() to outcome["result"].asText(), "$submitted")
        // 둘 다 배정 가능이고 도는 실행이 없어 비용이 같다. 같으면 기체 id 순이다(`AssignmentCost.rank`). 실패 모드 추첨이
        // 기체별이므로 어느 기체가 무엇을 도는지가 정해져 있어야 한다.
        assertEquals(HUMANOID, outcome["robotId"].asText(), "$outcome")

        val execution = driver.drive(outcome["executionId"].asText())
        assertEquals(submitted["jobOrderId"].asText(), execution["jobOrderId"].asText())
        assertEquals("InspectAsset", execution["workMasterId"].asText())
        assertTrue(execution.has("missionVersion") && execution["missionVersion"].isNull, "$execution")
        assertEquals("PHYSICALLY_DONE", execution["physicalState"].asText(), "$execution")
        assertEquals(
            listOf(Triple("T1.travel", "navigate_to", "DONE"), Triple("T1", "inspect", "DONE")),
            execution["units"].map { Triple(it["unitId"].asText(), it["skillType"].asText(), it["state"].asText()) },
        )
        val response = assertNotNull(execution["jobResponse"].takeUnless { it.isNull }, "$execution")
        assertEquals("PHYSICALLY_DONE" to "E0", response["physicalState"].asText() to response["reachedEvidence"].asText())
        assertEquals(listOf("T1.travel", "T1"), response["completedUnits"].map { it.asText() })
    }

    @Test
    @Order(3)
    fun `PrepareSequencedRack 작업 지시가 pick_place 가 있는 기체에 배정되어 셀 대역 신호로 E2 에 이른다`() {
        val submitted = submit(RACK)
        val outcome = submitted["outcome"]
        assertEquals("SUCCEEDED" to "ACCEPTED", submitted["result"].asText() to outcome["result"].asText(), "$submitted")
        // 운영 서비스가 배정 불가 기체를 후보에서 이미 뺐으므로 호스트가 다시 뺀 기체도 없다.
        assertEquals(HUMANOID, outcome["robotId"].asText(), "$outcome")
        assertEquals(0, outcome["excluded"].size(), "$outcome")
        val logged = stack.get("/api/operations").first { it["target"].asText() == submitted["jobOrderId"].asText() }
        assertEquals(listOf(HUMANOID), ObjectMapper().readTree(logged["request"].asText())["candidates"].map { it.asText() }, "$logged")

        val execution = driver.drive(outcome["executionId"].asText())
        assertEquals("PHYSICALLY_DONE", execution["physicalState"].asText(), "$execution")
        assertEquals(
            SLOTS.map { Triple(it, "DONE", "E2") },
            execution["units"].map { Triple(it["unitId"].asText(), it["state"].asText(), it["reached"].asText()) },
            "$execution",
        )
        assertEquals("E2", execution["jobResponse"]["reachedEvidence"].asText(), "$execution")

        val slots = stack.get("/api/cell")["cell"]["slots"].associateBy { it["id"].asText() }
        SLOTS.forEach { slot ->
            val place = slots.getValue(slot)
            assertEquals(true to MATERIAL, place["occupied"].asBoolean() to place["material"].asText(), "$place")
        }
    }

    /** 운영자 모드로 작업 지시를 낸다. 운영 서비스가 호스트에 제출했으면 200 이다. */
    private fun submit(form: String): JsonNode {
        val reply = stack.send("POST", "/api/job-orders", "operator", body = form)
        assertEquals(200, reply.status, "${reply.body}")
        return reply.body!!
    }
}
