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
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * S3b 완료 판정의 통합 쪽(S3b 스펙 §3·§10). 두 기체를 시운전 완료로 만든 뒤 운영 서비스 REST 만으로 PrepareSequencedRack 의
 * 임무 버전을 다룬다. 신호 조작도 운영 서비스 REST 다.
 *
 * 순서가 있다. `pick_place` 를 가진 기체가 humanoid-01 하나이고 호스트 판정은 도는 실행이 있는 기체를 통과시키지 않으므로, 버전
 * 1 실행이 끝나야 둘째 작업 지시를 배정할 수 있다. 셀 대역은 슬롯을 비우지 않으므로 두 작업 지시가 슬롯 넷을 나눠 쓴다(S01·S02,
 * S03·S04).
 *
 * ## 실패 모드
 *
 * `pick_place` 의 실패 모드는 기체별 시드로 추첨된다. 현장은 시드 0 이고, 이 순서(humanoid-01 이 `pick_place` 둘을 두 번)에서는
 * 실패 모드가 나지 않는다(스파이크에서 확인). 모의 실행은 실패 모드를 뺀 별도 mimic 으로 돌아 현장 기체의 추첨을 바꾸지 않는다.
 *
 * ## 설비 대기 기한
 *
 * 버전 2 의 대기 단위는 기한 120초, 기한 뒤 ABORTED 다. 대기 단위가 시작된 뒤 신호를 켜기 전에는 가상 시계를 [WAIT_BEFORE_SIGNAL]
 * 만 민다(120초보다 훨씬 짧다). 신호 조작이 현장에 닿지 않으면 진행기가 기한을 넘겨 실행이 ABORTED 로 정착한다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MissionVersionTest {

    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver
        private val json = ObjectMapper()

        private const val WORK_MASTER = "PrepareSequencedRack"
        private const val MISSIONS = "/api/missions/$WORK_MASTER"
        private const val MATERIAL = "ENGINE-COVER-A"
        private const val PRESENTATION = "SEQ-IN-02.BIN-A"
        private val FIRST_SLOTS = listOf("RACK-204.S01", "RACK-204.S02")
        private val SECOND_SLOTS = listOf("RACK-204.S03", "RACK-204.S04")

        /** 신호를 켜기 전에 대기 단위가 기다리는 것을 보려고 미는 가상 시간. 기한 120초보다 훨씬 짧다. */
        private val WAIT_BEFORE_SIGNAL: Duration = Duration.ofSeconds(10)

        /** 템플릿 id → 정의 글자(S3b JSON 계약 §4.2). */
        private lateinit var templates: Map<String, String>

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start()
            driver = ExecutionDriver(stack)
            Commissioned.complete(stack)
            templates = stack.get("/api/missions/templates/$WORK_MASTER")["templates"]
                .associate { it["id"].asText() to it["definition"].asText() }
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }

        private fun rack(slots: List<String>): String =
            """{"workMasterId":"$WORK_MASTER","slots":[${slots.joinToString(",") { "\"$it\"" }}],""" +
                """"material":"$MATERIAL","presentation":"$PRESENTATION"}"""
    }

    @Test
    @Order(1)
    fun `데이터 정의를 초안 저장하고 검증하고 모의 실행을 통과시킨 뒤 사유를 적어 활성화하면 버전 1 이다`() {
        val before = stack.get(MISSIONS)
        assertEquals("CODE", before["active"]["source"].asText(), "$before")
        assertTrue(before["active"]["version"].isNull, "$before")

        val draftId = save(templates.getValue("DATA_V1"))

        val validated = engineer("$MISSIONS/drafts/$draftId/validate", "{}")
        assertEquals("PASSED", validated["outcome"]["result"].asText(), "$validated")
        assertEquals(0, validated["findings"].size(), "$validated")
        // 판정 입력은 현장의 실제 값이다. 신호 사양은 셀 대역 픽스처의 선언, 현장 스킬은 시운전 완료 기체 둘의 스킬 합이다.
        val inputs = validated["outcome"]["inputs"]
        assertEquals(listOf(HUMANOID, QUADRUPED), inputs["robotIds"].map { it.asText() }, "$inputs")
        assertEquals(listOf("rack_present", "guard_closed", "lot_code"), inputs["signals"].map { it["name"].asText() }, "$inputs")
        assertTrue(inputs["siteSkills"].any { it.asText() == "pick_place" }, "$inputs")

        val mocked = engineer("$MISSIONS/drafts/$draftId/mock-run", "{}")
        assertEquals("SUCCEEDED" to "PASSED", mocked["result"].asText() to mocked["outcome"]["result"].asText(), "$mocked")
        val run = mocked["outcome"]["mockRun"]["result"]
        assertEquals("PHYSICALLY_DONE", run["physicalState"].asText(), "$run")
        assertEquals(
            FIRST_SLOTS.map { Triple(it, "DONE", "E2") },
            run["units"].map { Triple(it["unitId"].asText(), it["state"].asText(), it["reached"].asText()) },
            "$run",
        )
        // 모의 실행은 별도 mimic 과 이상적 현장으로 돈다. 현장 셀 대역의 슬롯은 그대로 비어 있다.
        assertTrue(stack.get("/api/cell")["cell"]["slots"].none { it["occupied"].asBoolean() }, "${stack.get("/api/cell")}")

        val activated = engineer("$MISSIONS/drafts/$draftId/activate", """{"reason":"데이터 정의로 옮김"}""")
        assertEquals("SUCCEEDED" to "ACTIVATED", activated["result"].asText() to activated["outcome"]["result"].asText(), "$activated")
        assertEquals(1, activated["outcome"]["version"].asInt(), "$activated")

        val after = stack.get(MISSIONS)
        assertEquals("DATA" to 1, after["active"]["source"].asText() to after["active"]["version"].asInt(), "$after")
        val version = after["versions"].single()
        assertEquals(
            listOf(draftId.toString(), "kim", "데이터 정의로 옮김"),
            listOf(version["draftId"].asText(), version["activatedBy"].asText(), version["reason"].asText()),
            "$version",
        )
        val logged = stack.get("/api/operations").single { json.readTree(it["request"].asText())["op"].asText() == "ACTIVATE_MISSION_VERSION" }
        assertEquals(
            listOf(WORK_MASTER, "SUCCEEDED", "데이터 정의로 옮김"),
            listOf(logged["target"].asText(), logged["result"].asText(), logged["reason"].asText()),
            "$logged",
        )
    }

    @Test
    @Order(2)
    fun `신호 사양에 없는 신호를 기다리는 초안의 활성화는 SIGNAL_NOT_IN_SPEC 거부 카드로 막히고 활성 버전은 그대로다`() {
        val arrival = templates.getValue("ARRIVAL_WAIT")
        val wrong = "\"signal\": \"rack_present\""
        assertEquals(1, arrival.split(wrong).size - 1, arrival)
        val draftId = save(arrival.replace(wrong, "\"signal\": \"rack_ready\""))

        val refused = engineer("$MISSIONS/drafts/$draftId/activate", """{"reason":"잘못된 신호 이름"}""")
        assertEquals("REJECTED" to "REFUSED", refused["result"].asText() to refused["outcome"]["result"].asText(), "$refused")
        val finding = refused["findings"].single()
        assertEquals("SIGNAL_NOT_IN_SPEC", finding["kind"].asText(), "$finding")
        assertEquals("노드 rack-arrival: rack_ready", finding["observed"].asText(), "$finding")
        assertTrue("rack_present" in finding["expected"].asText(), "$finding")
        // 해결 담당은 화면 안의 엔지니어이고 바로 갈 작업이 실린다. 대상은 기체가 아니라 노드라 널이다.
        assertEquals("ENGINEER" to true, finding["owner"].asText() to finding["inScreen"].asBoolean(), "$finding")
        assertEquals("신호 이름을 고치거나 신호 사양에 더한다", finding["action"].asText(), "$finding")
        assertTrue(finding["target"].isNull, "$finding")
        assertEquals(refused["outcome"]["checkedAt"].asText(), finding["checkedAt"].asText(), "$finding")

        val after = stack.get(MISSIONS)
        assertEquals(1, after["active"]["version"].asInt(), "$after")
        assertEquals(listOf(1), after["versions"].map { it["version"].asInt() }, "$after")
    }

    @Test
    @Order(3)
    fun `버전 1 로 도는 실행 중에 버전 2 를 활성화하면 옛 실행은 버전 1 로 끝나고 새 작업 지시는 랙 도착을 기다렸다가 신호를 켜면 끝난다`() {
        // 버전 1 로 작업 지시를 내고 한 번 밀어 실행이 돌게 한다.
        val first = submit(rack(FIRST_SLOTS))
        driver.push(ExecutionDriver.STEP)
        val running = driver.execution(first)
        assertEquals(1, running["missionVersion"].asInt(), "$running")
        assertTrue(running["physicalState"].asText() !in ExecutionDriver.SETTLED, "$running")

        // 도는 중에 버전 2(랙 도착 대기, 120초, ABORTED)를 모의 실행하고 활성화한다.
        val draftId = save(templates.getValue("ARRIVAL_WAIT"))
        val mocked = engineer("$MISSIONS/drafts/$draftId/mock-run", "{}")
        assertEquals("PASSED", mocked["outcome"]["result"].asText(), "$mocked")
        val mockUnits = mocked["outcome"]["mockRun"]["result"]["units"]
        assertEquals(
            listOf("rack-arrival" to "SIGNAL") + FIRST_SLOTS.map { it to "ROBOT" },
            mockUnits.map { it["unitId"].asText() to it["route"].asText() },
            "$mockUnits",
        )
        val activated = engineer("$MISSIONS/drafts/$draftId/activate", """{"reason":"랙 도착 대기 도입"}""")
        assertEquals("ACTIVATED" to 2, activated["outcome"]["result"].asText() to activated["outcome"]["version"].asInt(), "$activated")
        val stillRunning = driver.execution(first)
        assertTrue(stillRunning["physicalState"].asText() !in ExecutionDriver.SETTLED, "활성화 때 옛 실행이 돌고 있어야 한다: $stillRunning")

        // 옛 실행은 대기 단위 없이 버전 1 로 끝난다.
        val old = driver.drive(first)
        assertEquals("PHYSICALLY_DONE" to 1, old["physicalState"].asText() to old["missionVersion"].asInt(), "$old")
        assertEquals(
            FIRST_SLOTS.map { Triple(it, "DONE", "E2") },
            old["units"].map { Triple(it["unitId"].asText(), it["state"].asText(), it["reached"].asText()) },
            "$old",
        )

        // 새 작업 지시는 버전 2 로 서고 랙 도착을 기다린다. 기한 120초보다 짧게만 밀어 기다리는 것을 본다.
        val second = submit(rack(SECOND_SLOTS))
        val waitFrom = stack.site.now()
        var waiting = driver.execution(second)
        while (Duration.between(waitFrom, stack.site.now()) < WAIT_BEFORE_SIGNAL) {
            driver.push(ExecutionDriver.STEP)
            waiting = driver.execution(second)
        }
        assertEquals(2, waiting["missionVersion"].asInt(), "$waiting")
        assertEquals(
            listOf(Triple("rack-arrival", "equipment_wait", "RUNNING")) + SECOND_SLOTS.map { Triple(it, "pick_place", "PENDING") },
            waiting["units"].map { Triple(it["unitId"].asText(), it["skillType"].asText(), it["state"].asText()) },
            "$waiting",
        )
        assertEquals("RUNNING", waiting["physicalState"].asText(), "$waiting")

        // 운영자가 운영 서비스로 rack_present 를 켠다. 현장이 지금 가상 시각으로 관측 시각을 적는다.
        val signalAt = stack.site.now()
        val written = stack.send("POST", "/api/cell/signals/rack_present", "operator", body = """{"value":"true"}""")
        assertEquals(200, written.status, "${written.body}")
        assertEquals("SUCCEEDED", written.body!!["result"].asText(), "${written.body}")
        assertEquals("true" to signalAt, written.body["signal"]["value"].asText() to Instant.parse(written.body["signal"]["observedAt"].asText()))

        // 신호가 현장에 닿지 않으면 여기서 기한을 넘겨 ABORTED 로 정착한다.
        val done = driver.drive(second)
        assertEquals("PHYSICALLY_DONE" to 2, done["physicalState"].asText() to done["missionVersion"].asInt(), "$done")
        assertEquals(
            listOf(Triple("rack-arrival", "DONE", "E2")) + SECOND_SLOTS.map { Triple(it, "DONE", "E2") },
            done["units"].map { Triple(it["unitId"].asText(), it["state"].asText(), it["reached"].asText()) },
            "$done",
        )
        assertEquals("E2", done["jobResponse"]["reachedEvidence"].asText(), "$done")

        val cell = stack.get("/api/cell")["cell"]
        assertEquals("true", cell["signals"].single { it["name"].asText() == "rack_present" }["value"].asText(), "$cell")
        val slots = cell["slots"].associateBy { it["id"].asText() }
        (FIRST_SLOTS + SECOND_SLOTS).forEach { slot ->
            assertEquals(true to MATERIAL, slots.getValue(slot)["occupied"].asBoolean() to slots.getValue(slot)["material"].asText(), "$cell")
        }
    }

    @Test
    @Order(4)
    fun `실행 호스트를 같은 포트로 다시 띄워도 활성 버전은 2 이고 새 작업 지시가 버전 2 로 선다`() {
        val instanceBefore = driver.executions()["instanceId"].asText()
        stack.restartHost()

        val after = stack.get(MISSIONS)
        assertEquals("DATA" to 2, after["active"]["source"].asText() to after["active"]["version"].asInt(), "$after")
        assertEquals(listOf(2, 1), after["versions"].map { it["version"].asInt() }, "$after")
        val executions = driver.executions()
        assertNotEquals(instanceBefore, executions["instanceId"].asText(), "$executions")
        assertEquals(0, executions["executions"].size(), "$executions")

        // 기동 때 DB 에서 세운 카탈로그가 미들웨어에 들어갔다. 새 실행이 버전 2 로 서고 대기 단위가 처음이다.
        val next = submit(rack(FIRST_SLOTS))
        driver.push(ExecutionDriver.STEP)
        val execution = driver.execution(next)
        assertEquals(2, execution["missionVersion"].asInt(), "$execution")
        assertEquals("rack-arrival", execution["units"].first()["unitId"].asText(), "$execution")
    }

    /** 엔지니어 모드로 초안을 저장하고 초안 id 를 낸다. */
    private fun save(definition: String): Long {
        val saved = engineer("$MISSIONS/drafts", json.createObjectNode().put("definition", definition).toString())
        assertEquals("SUCCEEDED", saved["result"].asText(), "$saved")
        return saved["outcome"]["draft"]["draftId"].asLong()
    }

    /** 엔지니어 모드로 부른다. 운영 서비스가 받았으면 200 이다(결과는 본문). */
    private fun engineer(path: String, body: String): JsonNode {
        val reply = stack.send("POST", path, "engineer", body = body)
        assertEquals(200, reply.status, "$path → ${reply.body}")
        return reply.body!!
    }

    /** 운영자 모드로 작업 지시를 내고 배정된 실행 id 를 낸다. 배정 기체는 `pick_place` 를 가진 humanoid-01 하나다. */
    private fun submit(form: String): String {
        val reply = stack.send("POST", "/api/job-orders", "operator", body = form)
        assertEquals(200, reply.status, "${reply.body}")
        val outcome = reply.body!!["outcome"]
        assertEquals("ACCEPTED" to HUMANOID, outcome["result"].asText() to outcome["robotId"].asText(), "${reply.body}")
        return outcome["executionId"].asText()
    }
}
