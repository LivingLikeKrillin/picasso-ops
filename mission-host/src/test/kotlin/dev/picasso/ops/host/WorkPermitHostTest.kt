package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 셀 대역 안전 신호로 비춘 작업 허가(ADR 52)를 실행 호스트에 붙인 거동. 셀 대역 대역의 `guard_closed`(안전 신호)가 원천이다.
 *
 * 작업 허가는 안전 기능이 아니다. 이 시험이 보는 것은 미들웨어가 새 로봇 명령을 내지 않고 기다리는지와, 그 막음이 실행 목록에 보이는지다.
 */
class WorkPermitHostTest {

    /** `guard_closed` 가 [robots] 를 맡는 대응 파일. */
    private fun mapping(vararg robots: String): Path = Files.createTempFile("work-permits", ".json").also {
        Files.writeString(it, """{"signals":[{"signal":"guard_closed","permittedWhen":"true","robots":[${robots.joinToString(",") { r -> "\"$r\"" }}]}]}""")
        it.toFile().deleteOnExit()
    }

    private fun guard(value: String): String {
        val closed = "\"name\":\"guard_closed\",\"location\":null,\"kind\":\"BOOLEAN\",\"safety\":true,\"value\":\"true\""
        check(closed in HostBench.STANDARD_CELL) { "대역 본문의 guard_closed 를 못 찾았다" }
        return HostBench.STANDARD_CELL.replace(closed, closed.replace("\"value\":\"true\"", "\"value\":\"$value\""))
    }

    private fun HostBench.submit(jobOrderId: String, robot: String = HUMANOID): String {
        val submitted = post("/host/job-orders", request(inspect(jobOrderId, "T1" to "bay-7"), "candidates", robot)).body!!
        assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
        return submitted["executionId"].asText()
    }

    /** pump 두 번을 지나 본다. 지금 대기 중인 pump 가 앞선 스냅숏을 들고 끝날 수 있다. */
    private fun HostBench.settlePumps() {
        repeat(2) {
            mimic.server.advance(java.time.Duration.ofSeconds(1))
            awaitPump()
        }
    }

    private fun HostBench.block(executionId: String): JsonNode = execution(executionId)!!["permitBlock"]

    @Test
    fun `허가 대응을 선언하지 않으면 안전 신호가 막아도 포트를 붙이지 않고 오늘처럼 낸다`() {
        HostBench().use { bench ->
            bench.cellBody = guard("false")
            bench.awaitPump()
            val view = bench.get("/host/work-permits")
            assertFalse(view["declared"].asBoolean(), view.toString())
            assertEquals(0, view["robots"].size())

            val executionId = bench.submit("JO-1")
            val done = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            assertTrue(done["permitBlock"].isNull, done.toString())
        }
    }

    @Test
    fun `INHIBITED 면 새 명령을 내지 않고 기다리며 막은 기체와 주체와 풀릴 근거가 실행 목록에 보이고 PERMITTED 로 돌아오면 낸다`() {
        HostBench(permitMapping = mapping(HUMANOID, QUADRUPED)).use { bench ->
            bench.cellBody = guard("false")
            bench.settlePumps()
            val executionId = bench.submit("JO-1")
            bench.settlePumps()

            val execution = bench.execution(executionId)!!
            assertTrue(execution["units"].all { it["state"].asText() == "PENDING" }, execution.toString())
            assertNotEquals("OPERATOR_HOLD", execution["physicalState"].asText())
            val block = execution["permitBlock"]
            assertEquals("T1.travel", block["unitId"].asText(), block.toString())
            assertEquals("START", block["withheld"].asText())
            val robot = block["robots"].single()
            assertEquals(HUMANOID, robot["robotId"].asText())
            assertEquals("INHIBITED", robot["permit"].asText())
            val reason = robot["reasons"].single()
            assertEquals("cell/guard_closed", reason["source"].asText())
            assertEquals("REPORTED", reason["cause"].asText())
            assertEquals("guard_closed=false", reason["subject"].asText())
            assertEquals("안전 계통이 guard_closed 을 true 으로 되돌릴 때", reason["resumeBasis"].asText())
            assertTrue(bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.isEmpty() }, "막힌 동안 기체에 명령이 나갔다")

            // 작업 허가 조회는 발행 게이트와 같은 판정을 낸다(관측 시각까지 같은 판독).
            val view = bench.get("/host/work-permits")
            assertTrue(view["declared"].asBoolean())
            assertEquals(listOf("guard_closed"), view["mappings"].map { it["signal"].asText() })
            val viewed = view["robots"].single { it["robotId"].asText() == HUMANOID }
            val blocked = bench.block(executionId)["robots"].single()
            assertEquals(blocked["permit"], viewed["permit"])
            assertEquals(blocked["reasons"].map { it["cause"] to it["subject"] }, viewed["reasons"].map { it["cause"] to it["subject"] })

            bench.cellBody = guard("true")
            val done = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            assertTrue(done["permitBlock"].isNull, done.toString())
        }
    }

    @Test
    fun `INHIBITED 여도 내기 전에 막힌 실행의 운영자 취소는 허가를 기다리지 않고 중단으로 끝난다`() {
        HostBench(permitMapping = mapping(HUMANOID, QUADRUPED)).use { bench ->
            bench.cellBody = guard("false")
            bench.settlePumps()
            val executionId = bench.submit("JO-1")
            bench.settlePumps()
            assertFalse(bench.block(executionId).isNull)

            val canceled = bench.post(
                "/host/executions/$executionId/cancel",
                JSON.writeValueAsString(linkedMapOf("approverId" to "kim", "reason" to "셀 문이 열려 이 작업을 닫는다", "instanceId" to bench.host.instanceId)),
            )
            assertEquals(200, canceled.status, canceled.body.toString())
            assertEquals("CANCELED", canceled.body!!["result"].asText(), canceled.body.toString())
            bench.eventually("취소한 실행이 중단으로 정착") { bench.execution(executionId)!!["physicalState"].asText() == "ABORTED" }
            assertEquals("ABORTED", bench.execution(executionId)!!["physicalState"].asText())
        }
    }

    @Test
    fun `대응에 없는 기체는 어느 원천도 덮지 않아 NOT_COVERED 로 막힌다`() {
        HostBench(permitMapping = mapping(QUADRUPED)).use { bench ->
            bench.settlePumps()
            val executionId = bench.submit("JO-1", HUMANOID)
            bench.settlePumps()
            val reason = bench.block(executionId)["robots"].single()["reasons"].single()
            assertEquals("NOT_COVERED", reason["cause"].asText(), reason.toString())
            assertTrue(reason["source"].isNull)
        }
    }

    @Test
    fun `셀 대역을 못 읽으면 원천 세션이 바뀌어 SESSION_CHANGED 로 막고 다시 읽히면 낸다`() {
        HostBench(permitMapping = mapping(HUMANOID, QUADRUPED)).use { bench ->
            bench.cellBody = "not json"
            bench.settlePumps()
            val executionId = bench.submit("JO-1")
            bench.settlePumps()
            val reason = bench.block(executionId)["robots"].single()["reasons"].single()
            assertEquals("SESSION_CHANGED", reason["cause"].asText(), reason.toString())
            assertEquals("cell-band#2", bench.get("/host/work-permits")["session"].asText())

            bench.cellBody = HostBench.STANDARD_CELL
            val done = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
        }
    }
}
