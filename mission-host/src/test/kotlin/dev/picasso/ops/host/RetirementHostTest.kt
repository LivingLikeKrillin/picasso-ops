package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * registry 원장의 기체 퇴역을 실행 호스트에 붙인 동작(picasso §15.249). [RegistryStub] 이 registry 의 진단 읽기를 대신한다.
 *
 * 퇴역은 제어 명령이 아니다. 이 시험이 보는 것은 퇴역한 기체로 새 로봇 명령을 내지 않고 기다리는지, 그 차단이 실행 목록에 보이는지,
 * 퇴역이 풀리면 내는지다.
 */
class RetirementHostTest {

    private fun HostBench.submit(jobOrderId: String): String {
        val submitted = post("/host/job-orders", request(inspect(jobOrderId, "T1" to "bay-7"), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
        return submitted["executionId"].asText()
    }

    /** pump 두 번을 지나 본다. 지금 대기 중인 pump 가 앞선 스냅숏을 들고 끝날 수 있다. */
    private fun HostBench.settlePumps() {
        repeat(2) {
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
        }
    }

    private fun HostBench.retirements(): JsonNode = get("/host/retirements")

    @Test
    fun `퇴역한 기체에는 새 명령을 내지 않고 퇴역 사유가 차단으로 보이며 퇴역을 취소하면 낸다`() {
        RegistryStub().use { registry ->
            registry.retired = mapOf(HUMANOID to "감속기 교체")
            HostBench(registryUrl = registry.url, retirementInterval = Duration.ofMillis(100)).use { bench ->
                val view = bench.retirements()
                assertTrue(view["declared"].asBoolean(), view.toString())
                assertEquals(listOf(HUMANOID to "감속기 교체"), view["retired"].map { it["robotId"].asText() to it["reason"].asText() })

                val executionId = bench.submit("JO-1")
                bench.settlePumps()
                val execution = bench.execution(executionId)!!
                assertTrue(execution["units"].all { it["state"].asText() == "PENDING" }, execution.toString())
                val block = execution["permitBlock"]
                assertEquals("START", block["withheld"].asText(), block.toString())
                val robot = block["robots"].single()
                assertEquals(HUMANOID, robot["robotId"].asText())
                assertEquals("INHIBITED", robot["permit"].asText())
                val reason = robot["reasons"].single()
                assertEquals("RETIRED", reason["cause"].asText(), reason.toString())
                assertEquals("감속기 교체", reason["subject"].asText())
                assertTrue(reason["source"].isNull)
                assertTrue(bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.isEmpty() }, "퇴역한 기체에 명령이 나갔다")

                registry.retired = emptyMap()
                bench.eventually("퇴역 취소를 읽음") { bench.retirements()["retired"].isEmpty }
                val done = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
                assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
                assertTrue(done["permitBlock"].isNull, done.toString())
            }
        }
    }

    @Test
    fun `registry 가 안 닿으면 퇴역으로 막지 않고 실패를 조회에 남긴다`() {
        val closed = RegistryStub().use { it.url }
        HostBench(registryUrl = closed).use { bench ->
            val view = bench.retirements()
            assertTrue(view["declared"].asBoolean(), view.toString())
            assertTrue(view["retired"].isEmpty, view.toString())
            assertFalse(view["readError"].isNull, view.toString())

            val executionId = bench.submit("JO-1")
            val done = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            assertTrue(done["permitBlock"].isNull, done.toString())
        }
    }

    @Test
    fun `registry 주소가 비면 퇴역 포트를 붙이지 않는다`() {
        HostBench().use { bench ->
            val view = bench.retirements()
            assertFalse(view["declared"].asBoolean(), view.toString())
            assertTrue(view["lastReadAt"].isNull, view.toString())
        }
    }
}
