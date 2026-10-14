package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import dev.picasso.ops.e2e.Commissioned.QUADRUPED
import dev.picasso.ops.site.SignalWrite
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 작업 허가(ADR 52)의 통합 쪽. 현장이 `guard_closed`(안전 신호)가 두 기체를 맡는다고 선언하고, 현장 안전 계통의 대역이 셀 문을 열면
 * (`Site.actuateSafety`) 운영 서비스로 낸 작업 지시의 실행이 새 로봇 명령 없이 기다리며 막음이 운영 서비스의 실행 목록과 작업 요청 상세에
 * 보인다. 문이 닫히면 그 실행이 끝까지 간다.
 *
 * 작업 허가는 안전 기능이 아니다. 이 시험은 picasso 가 새 명령을 내지 않는 것만 본다.
 */
class WorkPermitTest {

    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver
        private val INSPECT = """{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"}]}"""

        @BeforeAll
        @JvmStatic
        fun up() {
            val mapping = Files.createTempFile("work-permits", ".json")
            mapping.toFile().deleteOnExit()
            Files.writeString(mapping, """{"signals":[{"signal":"guard_closed","permittedWhen":"true","robots":["$HUMANOID","$QUADRUPED"]}]}""")
            stack = E2eStack.start(permitMapping = mapping)
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
    fun `셀 문이 열리면 실행이 새 명령 없이 기다리며 막은 기체와 주체와 풀릴 근거가 실행 목록에 보이고 닫히면 끝까지 간다`() {
        assertIs<SignalWrite.Written>(stack.site.actuateSafety("guard_closed", "false"))
        // 소프트웨어 쓰기 길은 여전히 안전 신호를 거부한다.
        val refused = stack.send("POST", "/api/cell/signals/guard_closed", "operator", body = """{"value":"true"}""").body!!
        assertEquals("REJECTED" to "SAFETY_SIGNAL_READ_ONLY", refused["result"].asText() to refused["rejection"]["error"].asText(), "$refused")
        driver.push(Duration.ofSeconds(1))

        val reply = stack.send("POST", "/api/job-orders", "operator", body = INSPECT)
        assertEquals(200, reply.status, "${reply.body}")
        val outcome = reply.body!!["outcome"]
        assertEquals("ACCEPTED", outcome["result"].asText(), "$outcome")
        val executionId = outcome["executionId"].asText()
        driver.push(Duration.ofSeconds(1))

        val waiting = driver.execution(executionId)
        assertTrue(waiting["units"].all { it["state"].asText() == "PENDING" }, "$waiting")
        val block = waiting["permitBlock"]
        assertEquals("START", block["withheld"].asText(), "$waiting")
        val robot = block["robots"].single()
        assertEquals(outcome["robotId"].asText() to "INHIBITED", robot["robotId"].asText() to robot["permit"].asText())
        val reason = robot["reasons"].single()
        assertEquals("guard_closed=false", reason["subject"].asText())
        assertEquals("안전 계통이 guard_closed 을 true 으로 되돌릴 때", reason["resumeBasis"].asText())
        assertTrue(stack.site.taskHistory(robot["robotId"].asText()).isEmpty(), "막힌 동안 기체에 명령이 나갔다")

        // 작업 요청 상세의 실행에도 같은 막음이 실린다.
        val receipt = stack.get("/api/work-requests?search=${reply.body!!["jobOrderId"].asText()}&limit=20")["requests"].single()
        val detail: JsonNode = stack.get("/api/work-requests/${receipt["receiptId"].asText()}")
        assertEquals(block["robots"], detail["execution"]["permitBlock"]["robots"], "$detail")

        assertIs<SignalWrite.Written>(stack.site.actuateSafety("guard_closed", "true"))
        val done = driver.drive(executionId)
        assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), "$done")
        assertTrue(done["permitBlock"].isNull, "$done")
    }
}
