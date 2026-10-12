package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** One upstream contract spans two robot types; station signals and operator decisions remain explicit. */
class ManufacturingScenarioTest {
    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver
        private val json = ObjectMapper()
        @BeforeAll @JvmStatic fun up() {
            stack = E2eStack.start(integrationToken = "manufacturing-test-token")
            driver = ExecutionDriver(stack)
            Commissioned.complete(stack)
        }
        @AfterAll @JvmStatic fun down() { if (::stack.isInitialized) stack.close() }
    }

    @Test fun `shared inspection contract and evidence gated supply preserve upstream identity across rework`() {
        val profile = json.readTree(Files.readString(E2eStack.root.resolve("docs/examples/mission-profile-station-inspection.json")))
        post("/api/mission-profiles", profile.toString())
        activate("StationInspection", profile["definition"].toString())
        contract("station-inspection", "StationInspection", """{"targets":"/assets"}""", """{"assets":[{"id":"T1","location":"bay-7"}]}""")

        val input = """{"assets":[{"id":"T1","location":"bay-7"}]}"""
        val assessment = post("/api/mission-interfaces/station-inspection/1/eligibility", """{"input":$input}""")
        assertEquals("1", assessment["jobOrder"]["missionVersion"].asText())
        val candidates = assessment["eligibility"]["robots"]
        assertEquals(2, candidates.count { it["eligible"].asBoolean() })
        candidates.forEach { robot ->
            assertEquals(listOf("inspect"), robot["host"]["requiredSkills"].map { it.asText() })
            kotlin.test.assertTrue(robot["host"]["availableSkills"].any { it.asText() == "inspect" })
        }
        val requestA = UUID.randomUUID().toString()
        val a = invoke("station-inspection", requestA, input)
        // The first robot is occupied when the second request is evaluated.
        val occupied = post("/api/mission-interfaces/station-inspection/1/eligibility", """{"input":$input}""")["eligibility"]["robots"]
        assertEquals(false, occupied.single { it["robotId"].asText() == Commissioned.HUMANOID }["eligible"].asBoolean())
        assertEquals(true, occupied.single { it["robotId"].asText() == Commissioned.QUADRUPED }["eligible"].asBoolean())
        val b = invoke("station-inspection", UUID.randomUUID().toString(), input)
        assertEquals("ACCEPTED", a["state"].asText(), "$a")
        assertEquals("ACCEPTED", b["state"].asText(), "$b")
        val ea = executionId(a)
        val eb = executionId(b)
        assertEquals(setOf(Commissioned.HUMANOID, Commissioned.QUADRUPED), setOf(driver.execution(ea)["robotId"].asText(), driver.execution(eb)["robotId"].asText()))
        assertEquals(a["normalizedOrder"]["deployment"], b["normalizedOrder"]["deployment"])
        assertEquals(a["payload"]["input"], b["payload"]["input"])
        assertEquals("1", a["normalizedOrder"]["missionVersion"].asText())
        for (id in listOf(ea, eb)) {
            val done = driver.drive(id)
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), "$done")
            assertEquals("E0", done["jobResponse"]["reachedEvidence"].asText(), "$done")
            assertEquals("inspect", done["units"].single()["skillType"].asText())
        }
        val replay = invoke("station-inspection", requestA, input, expectedStatus = 200)
        assertEquals(a["receiptId"], replay["receiptId"])
        assertEquals(a["jobOrderId"], replay["jobOrderId"])
        val resultA = savedResult(a, "E0")
        val resultB = savedResult(b, "E0")

        // Manipulation cannot be assigned to a robot lacking pick_place, even after commissioning.
        val rack = """{"workMasterId":"PrepareSequencedRack","slots":["RACK-204.S03"],"material":"ENGINE-COVER-A","presentation":"SEQ-IN-02.BIN-A"}"""
        val eligible = post("/api/job-orders/eligibility", rack, mode = null)["robots"]
        val quadruped = eligible.single { it["robotId"].asText() == Commissioned.QUADRUPED }
        assertEquals("COMPLETE", quadruped["commissioning"].asText())
        assertEquals(false, quadruped["eligible"].asBoolean())
        assertEquals(listOf("pick_place"), quadruped["host"]["missingSkills"].map { it.asText() })
        val template = stack.get("/api/missions/templates/PrepareSequencedRack")["templates"].single { it["id"].asText() == "ARRIVAL_WAIT_HOLD" }["definition"].asText()
        activate("PrepareSequencedRack", template)
        contract("sequenced-supply", "PrepareSequencedRack", """{"slots":"/slots","material":"/material","presentation":"/presentation"}""", rack.replace("\"workMasterId\":\"PrepareSequencedRack\",", ""))
        signal("false")
        val request = UUID.randomUUID().toString()
        val supplyInput = rack.replace("\"workMasterId\":\"PrepareSequencedRack\",", "")
        val supply = invoke("sequenced-supply", request, supplyInput)
        val executionId = executionId(supply)
        repeat(12) {
            if (driver.execution(executionId)["physicalState"].asText() != "OPERATOR_HOLD") driver.push(Duration.ofSeconds(5))
        }
        assertEquals("OPERATOR_HOLD", driver.execution(executionId)["physicalState"].asText())
        val incident = stack.get("/api/incidents")["incidents"].single { it["executionId"].asText() == executionId }
        assertEquals("SIGNAL_DEADLINE", incident["failureClass"].asText())
        val detail = stack.get("/api/incidents/${incident["incidentId"].asText()}")
        val resolution = post("/api/executions/$executionId/units/rack-arrival/resolve", """{"decision":"REWORK","instanceId":"${detail["instanceId"].asText()}","reason":"스테이션 랙 배치 확인 후 재작업"}""", "operator")
        assertEquals("Resolved", resolution["outcome"].asText(), "$resolution")
        signal("true")
        val done = driver.drive(executionId)
        assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), "$done")
        assertEquals("E2", done["jobResponse"]["reachedEvidence"].asText(), "$done")
        val after = invoke("sequenced-supply", request, supplyInput, expectedStatus = 200)
        assertEquals(supply["receiptId"], after["receiptId"])
        assertEquals(supply["jobOrderId"], after["jobOrderId"])
        assertEquals(supply["normalizedOrder"], after["normalizedOrder"])
        val audit = stack.get("/api/incidents")["incidents"].single { it["incidentId"] == incident["incidentId"] }
        assertEquals("REWORK", audit["resolution"]["decision"].asText())
        assertEquals("kim", audit["resolution"]["decidedBy"]["id"].asText())
        val reworkOperation = stack.get("/api/operations").single {
            it["target"].asText() == "$executionId/rack-arrival" && json.readTree(it["request"].asText())["op"].asText() == "RESOLVE_OPERATOR_HOLD"
        }
        assertEquals("스테이션 랙 배치 확인 후 재작업", reworkOperation["reason"].asText())
        assertEquals("SUCCEEDED", reworkOperation["result"].asText())
        val resultSupply = savedResult(supply, "E2")
        val report = json.createObjectNode().apply {
            put("scenario", "manufacturing-station")
            put("environment", "reference-mimic-and-cell-simulator")
            set<JsonNode>("inspectionA", resultA)
            set<JsonNode>("inspectionB", resultB)
            set<JsonNode>("supply", resultSupply)
            set<JsonNode>("reworkIncident", audit)
            set<JsonNode>("reworkOperation", reworkOperation)
            set<JsonNode>("excludedManipulationCandidate", quadruped)
        }
        val reportFile = E2eStack.root.resolve("build/reports/manufacturing-scenario.json")
        Files.createDirectories(reportFile.parent)
        Files.writeString(reportFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(report))
    }

    private fun activate(wm: String, definition: String) {
        val draft = post("/api/missions/$wm/drafts", json.createObjectNode().put("definition", definition).toString())["outcome"]["draft"]["draftId"].asLong()
        assertEquals("PASSED", post("/api/missions/$wm/drafts/$draft/validate", "{}")["outcome"]["result"].asText())
        assertEquals("PASSED", post("/api/missions/$wm/drafts/$draft/mock-run", "{}")["outcome"]["result"].asText())
        assertEquals("ACTIVATED", post("/api/missions/$wm/drafts/$draft/activate", """{"reason":"스테이션 통합 검증"}""")["outcome"]["result"].asText())
    }
    private fun contract(id: String, wm: String, mapping: String, sample: String) {
        val created = post("/api/mission-interfaces", """{"interfaceId":"$id","version":1,"name":"스테이션 업무","workMasterId":"$wm","mapping":$mapping,"sample":$sample}""")
        val expected = created["deployment"]?.get("deploymentVersion")?.asInt() ?: 0
        post("/api/mission-interfaces/$id/1/deploy", """{"missionVersion":"1","expectedDeploymentVersion":$expected,"reason":"검증 버전 고정"}""")
        post("/api/mission-interfaces/$id/1/state", """{"expectedStatus":"DRAFT","status":"ENABLED","reason":"검증 호출 공개"}""")
    }
    private fun invoke(id: String, request: String, input: String, expectedStatus: Int = 202): JsonNode {
        val payload = """{"externalOrderId":"$request","interfaceId":"$id","interfaceVersion":1,"input":$input}"""
        val call = HttpRequest.newBuilder(URI.create("${stack.opsUrl}/integration/v1/job-orders"))
            .header("Authorization", "Bearer manufacturing-test-token").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build()
        val reply = HttpClient.newHttpClient().send(call, HttpResponse.BodyHandlers.ofString())
        assertEquals(expectedStatus, reply.statusCode(), reply.body())
        val receipt = json.readTree(reply.body())["receipt"]
        assertEquals("mock-mes", receipt["sourceSystemId"].asText())
        return receipt
    }
    private fun executionId(receipt: JsonNode): String = receipt["submission"]["outcome"]["executionId"].asText()
    private fun signal(value: String) { assertEquals("SUCCEEDED", post("/api/cell/signals/rack_present", """{"value":"$value"}""", "operator")["result"].asText()) }
    private fun savedResult(receipt: JsonNode, evidence: String): JsonNode {
        repeat(60) {
            val detail = stack.get("/api/work-requests/${receipt["receiptId"].asText()}")
            val saved = detail["latestResult"]
            if (saved != null && !saved.isNull && saved["snapshot"]["jobResponse"]["physicalState"].asText() == "PHYSICALLY_DONE") {
                assertEquals(evidence, saved["snapshot"]["jobResponse"]["reachedEvidence"].asText())
                assertEquals(evidence, saved["snapshot"]["jobResponse"]["requiredEvidence"].asText())
                return detail
            }
            Thread.sleep(250)
        }
        error("결과가 접수에 수집되지 않았다: $receipt")
    }
    private fun post(path: String, body: String, mode: String? = "engineer"): JsonNode {
        val reply = stack.send("POST", path, mode, body = body)
        assertEquals(200, reply.status, "$path → ${reply.body}")
        return reply.body!!
    }
}
