package dev.picasso.ops.host

import dev.picasso.ops.host.HostBench.Companion.GHOST
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실행 호스트의 판정·제출·실행 목록(S3a 스펙 §7.6, §11). mimic 은 실제 Netty 포트에 뜨고 호스트는 그 포트에 gRPC 로 붙는다.
 */
class MissionHostTest {

    @Test
    fun `스킬이 모자란 기체와 케이퍼빌리티를 못 물은 기체를 가르고 이유를 낸다`() {
        HostBench().use { bench ->
            val rack = bench.post("/host/eligibility", request(rack("JO-1", "RACK-204.S01"), "robotIds", HUMANOID, QUADRUPED, GHOST))
            assertEquals(200, rack.status, rack.body.toString())
            val rows = rack.body!!["robots"].associateBy { it["robotId"].asText() }
            assertEquals(listOf(HUMANOID, QUADRUPED, GHOST), rack.body["robots"].map { it["robotId"].asText() })

            val humanoid = rows.getValue(HUMANOID)
            assertEquals("FIT", humanoid["skillFit"].asText())
            assertEquals(0, humanoid["missingSkills"].size())
            assertTrue(humanoid["runningExecutionId"].isNull)
            assertEquals(true, humanoid["passed"].asBoolean())
            assertEquals(0, humanoid["reasons"].size())

            // quadruped-01 은 pick_place 를 선언하지 않는다.
            val quadruped = rows.getValue(QUADRUPED)
            assertEquals("MISSING", quadruped["skillFit"].asText())
            assertEquals(listOf("pick_place"), quadruped["missingSkills"].map { it.asText() })
            assertEquals(false, quadruped["passed"].asBoolean())
            assertEquals(listOf("모자란 스킬: pick_place"), quadruped["reasons"].map { it.asText() })

            // mimic 이 모르는 기체는 케이퍼빌리티를 못 물어본다. 모자람이 아니라 모름이다.
            val ghost = rows.getValue(GHOST)
            assertEquals("UNKNOWN", ghost["skillFit"].asText())
            assertEquals(0, ghost["missingSkills"].size())
            assertEquals(false, ghost["passed"].asBoolean())

            // InspectAsset 은 둘 다 든다.
            val inspection = bench.post("/host/eligibility", request(inspect("JO-2", "T1" to "bay-7"), "robotIds", HUMANOID, QUADRUPED))
            assertEquals(listOf("FIT", "FIT"), inspection.body!!["robots"].map { it["skillFit"].asText() })
        }
    }

    @Test
    fun `제출은 판정을 다시 해 통과한 기체만 assign 에 넘긴다`() {
        HostBench().use { bench ->
            // 통과한 기체가 없으면 assign 의 사유는 비고 뺀 기체가 이유와 함께 나온다.
            val none = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01"), "candidates", QUADRUPED))
            assertEquals(200, none.status, none.body.toString())
            assertEquals("UNASSIGNED", none.body!!["result"].asText())
            assertTrue(none.body["executionId"].isNull)
            assertEquals(0, none.body["refusals"].size())
            assertEquals(listOf(QUADRUPED), none.body["excluded"].map { it["robotId"].asText() })
            assertEquals(listOf("pick_place"), none.body["excluded"][0]["missingSkills"].map { it.asText() })

            // 후보 순서가 quadruped-01 먼저여도 배정은 통과한 humanoid-01 이다.
            val accepted = bench.post(
                "/host/job-orders",
                request(rack("JO-2", "RACK-204.S01", "RACK-204.S02"), "candidates", QUADRUPED, HUMANOID),
            )
            assertEquals("ACCEPTED", accepted.body!!["result"].asText(), accepted.body.toString())
            assertEquals(HUMANOID, accepted.body["robotId"].asText())
            assertEquals("exec-1", accepted.body["executionId"].asText())
            assertTrue(accepted.body["rejectionReason"].isNull)
            assertEquals(listOf(QUADRUPED), accepted.body["excluded"].map { it["robotId"].asText() })

            val execution = bench.execution("exec-1")!!
            assertEquals("JO-2", execution["jobOrderId"].asText())
            assertEquals("PrepareSequencedRack", execution["workMasterId"].asText())
            assertEquals(HUMANOID, execution["robotId"].asText())
            // 코드 정의 임무는 버전이 없다. 칸은 있고 값이 null 이다.
            assertTrue(execution.has("missionVersion") && execution["missionVersion"].isNull, execution.toString())
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02"), execution["units"].map { it["unitId"].asText() })
            assertTrue(execution["units"].all { it["skillType"].asText() == "pick_place" && it.has("state") && it.has("reached") })
        }
    }

    @Test
    fun `도는 실행이 있는 기체는 판정과 제출에서 빠진다`() {
        HostBench().use { bench ->
            val first = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID))
            assertEquals("ACCEPTED", first.body!!["result"].asText(), first.body.toString())
            val executionId = first.body["executionId"].asText()

            val judged = bench.post("/host/eligibility", request(inspect("JO-2", "T2" to "dock-3"), "robotIds", HUMANOID))
                .body!!["robots"].single()
            assertEquals("FIT", judged["skillFit"].asText())
            assertEquals(executionId, judged["runningExecutionId"].asText())
            assertEquals(false, judged["passed"].asBoolean())
            assertEquals(listOf("도는 실행이 있다: $executionId"), judged["reasons"].map { it.asText() })

            val second = bench.post("/host/job-orders", request(inspect("JO-2", "T2" to "dock-3"), "candidates", HUMANOID))
            assertEquals("UNASSIGNED", second.body!!["result"].asText(), second.body.toString())
            assertEquals(executionId, second.body["excluded"].single()["runningExecutionId"].asText())
            assertEquals(1, bench.get("/host/executions")["executions"].size())
        }
    }

    @Test
    fun `끝난 InspectAsset 를 같은 id 로 다시 내면 IDEMPOTENT 이고 실행에 마지막 작업 응답이 붙는다`() {
        HostBench().use { bench ->
            val order = request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)
            val accepted = bench.post("/host/job-orders", order).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val executionId = accepted["executionId"].asText()

            val done = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            assertEquals(listOf("T1.travel" to "DONE", "T1" to "DONE"), done["units"].map { it["unitId"].asText() to it["state"].asText() })
            assertTrue(done["units"].all { it["reached"].asText() == "E0" })

            val response = done["jobResponse"]
            assertEquals("PHYSICALLY_DONE", response["physicalState"].asText(), response.toString())
            assertEquals("E0", response["reachedEvidence"].asText())
            assertEquals(listOf("T1.travel", "T1"), response["completedUnits"].map { it.asText() })

            val again = bench.post("/host/job-orders", order).body!!
            assertEquals("IDEMPOTENT", again["result"].asText(), again.toString())
            assertEquals(executionId, again["executionId"].asText())
            assertEquals(HUMANOID, again["robotId"].asText())
            assertEquals(1, bench.get("/host/executions")["executions"].size())
        }
    }

    @Test
    fun `요구 근거 등급이 임무의 최고 등급을 넘으면 REJECTED 와 사유를 낸다`() {
        HostBench().use { bench ->
            val rejected = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7", evidence = "E2"), "candidates", HUMANOID))
            assertEquals(200, rejected.status)
            assertEquals("REJECTED", rejected.body!!["result"].asText(), rejected.body.toString())
            assertTrue(rejected.body["executionId"].isNull)
            assertTrue(rejected.body["robotId"].isNull)
            assertTrue("E2" in rejected.body["rejectionReason"].asText(), rejected.body.toString())
            assertEquals(0, bench.get("/host/executions")["executions"].size())
        }
    }

    @Test
    fun `받지 않는 WorkMaster 와 못 읽는 본문은 400 이고 JSON 이 아니면 415 다`() {
        HostBench().use { bench ->
            val deliver = inspect("JO-1", "T1" to "bay-7").replace("InspectAsset", "DeliverContainer")
            listOf("/host/eligibility" to "robotIds", "/host/job-orders" to "candidates").forEach { (path, field) ->
                val unknown = bench.post(path, request(deliver, field, HUMANOID))
                assertEquals(400, unknown.status, "$path ${unknown.body}")
                assertEquals("UNKNOWN_WORK_MASTER", unknown.body!!["error"].asText())

                val broken = bench.post(path, "{not json")
                assertEquals(400, broken.status, path)
                assertEquals("BAD_REQUEST", broken.body!!["error"].asText())

                val noId = bench.post(path, request(inspect("JO-1", "T1" to "bay-7").replace("\"jobOrderId\":\"JO-1\",", ""), field, HUMANOID))
                assertEquals(400, noId.status, path)
                assertEquals("BAD_REQUEST", noId.body!!["error"].asText())

                val noRobots = bench.post(path, """{"jobOrder":${inspect("JO-1", "T1" to "bay-7")}}""")
                assertEquals(400, noRobots.status, path)
                assertTrue(field in noRobots.body!!["detail"].asText(), noRobots.body.toString())

                val text = bench.post(path, request(inspect("JO-1", "T1" to "bay-7"), field, HUMANOID), contentType = "text/plain")
                assertEquals(415, text.status, path)
            }
            assertEquals(0, bench.get("/host/executions")["executions"].size())
        }
    }

    @Test
    fun `실행 목록은 미들웨어 인스턴스와 마지막 pump 시각을 낸다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val view = bench.get("/host/executions")
            assertTrue(view["instanceId"].asText().startsWith("mw-"), view.toString())
            assertEquals(bench.now().toString(), view["pumpedAt"].asText())
            assertEquals(0, view["executions"].size())
        }
    }

    @Test
    fun `셀 대역 스냅숏을 pump 마다 읽어 내고 못 읽으면 null 이다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val cell = bench.get("/host/cell")["cell"]
            assertEquals(listOf(HostBench.SOURCE), cell["presentations"].map { it["id"].asText() })
            assertEquals(HostBench.MATERIAL, cell["presentations"][0]["material"].asText())
            assertTrue(cell["presentations"][0]["observedAt"].isNull)
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02", "RACK-204.S03", "RACK-204.S04"), cell["slots"].map { it["id"].asText() })

            bench.stopCell()
            // 지금 대기 중인 pump 가 앞선 스냅숏을 들고 끝날 수 있어 두 번을 지나 본다.
            bench.mimic.server.advance(java.time.Duration.ofSeconds(1))
            bench.awaitPump()
            bench.mimic.server.advance(java.time.Duration.ofSeconds(1))
            bench.awaitPump()
            assertTrue(bench.get("/host/cell")["cell"].isNull)
        }
    }
}
