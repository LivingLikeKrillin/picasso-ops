package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostIncident
import dev.picasso.ops.service.incidents.FaultInjectionOutcome
import dev.picasso.ops.service.incidents.FaultOperations
import dev.picasso.ops.service.incidents.HoldResolutions
import dev.picasso.ops.service.incidents.HoldResolveOutcome
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.web.IncidentController
import dev.picasso.ops.service.web.PreRejection
import dev.picasso.registry.PostgresSupport
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * 장애 주입·인시던트·운영자 판단 API 의 관문과 사전 거부와 읽기 중계(S4a 스펙 §7, §9). 컨트롤러를 스프링 없이 바로 부른다.
 * `application/json` 제한은 스프링의 몫이라 통합 시험이 본다.
 */
class IncidentControllerTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = IncidentBench()
    private val controller = IncidentController(
        FaultOperations(bench.host, log),
        HoldResolutions(bench.host, log, requeryDelay = Duration.ZERO),
        bench.host,
    )
    private val json = ObjectMapper()

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    private fun ResponseEntity<Any>.rejection(): PreRejection = assertIs<PreRejection>(body)

    private fun inject(mode: String?, user: String? = "lee", body: String = FAULT): ResponseEntity<Any> =
        controller.inject(mode, user, body.toByteArray())

    private fun resolve(mode: String?, user: String? = "kim", body: String = RESOLVE): ResponseEntity<Any> =
        controller.resolve("exec-1", "rack-arrival", mode, user, body.toByteArray())

    @Test
    fun `장애 주입은 운영자 모드면 403 이고 판단은 엔지니어 모드면 403 이며 호스트를 부르지 않고 기록하지 않는다`() {
        listOf(inject("operator"), inject("operator", body = "장애")).forEach { reply ->
            assertEquals(403, reply.statusCode.value())
            assertEquals("MODE_NOT_ALLOWED", reply.rejection().error)
            assertEquals("이 조작은 engineer 모드에서 한다", reply.rejection().detail)
        }
        listOf(resolve("engineer"), resolve("engineer", body = "판단")).forEach { reply ->
            assertEquals(403, reply.statusCode.value())
            assertEquals("MODE_NOT_ALLOWED", reply.rejection().error)
            assertEquals("이 조작은 operator 모드에서 한다", reply.rejection().detail)
        }
        assertEquals(emptyList(), bench.calls)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `행위자 헤더가 없거나 틀리면 둘 다 400 ACTOR_REQUIRED 다`() {
        listOf(
            inject(null), inject("engineer", user = null), inject("engineer", user = "이 엔지"),
            resolve(null), resolve("operator", user = null), resolve("operator", user = "김 운영"),
        ).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals("ACTOR_REQUIRED", reply.rejection().error)
        }
        assertEquals(emptyList(), bench.calls)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `사유가 없거나 비었거나 문자열이 아니면 400 REASON_REQUIRED 이고 호스트를 부르지 않는다`() {
        val faults = listOf(
            """{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED"}""",
            """{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED","reason":"  "}""",
            """{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED","reason":3}""",
        )
        val decisions = listOf(
            """{"decision":"REWORK","instanceId":"i-1"}""",
            """{"decision":"REWORK","instanceId":"i-1","reason":""}""",
            """{"decision":"REWORK","instanceId":"i-1","reason":null}""",
        )
        (faults.map { inject("engineer", body = it) } + decisions.map { resolve("operator", body = it) }).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals(IncidentController.REASON_REQUIRED, reply.rejection().error)
        }
        assertEquals(emptyList(), bench.calls)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `본문 모양이 틀리면 사유보다 먼저 400 이다`() {
        listOf(
            "장애",
            """{"kind":"SKILL_EXECUTION_FAILED","reason":"r"}""",
            """{"robotId":" ","kind":"SKILL_EXECUTION_FAILED","reason":"r"}""",
            """{"robotId":"humanoid-01","kind":7,"reason":"r"}""",
            """{"robotId":"humanoid-01","kind":"CONNECTION","state":0,"reason":"r"}""",
        ).forEach { body ->
            assertEquals(IncidentController.FAULT_BAD_REQUEST, inject("engineer", body = body).rejection().error, body)
        }
        listOf("판단", """{"reason":"r"}""", """{"decision":"RELEASE","reason":"r"}""", """{"decision":"rework"}""").forEach { body ->
            assertEquals(IncidentController.RESOLVE_BAD_REQUEST, resolve("operator", body = body).rejection().error, body)
        }
        assertEquals(emptyList(), bench.calls)
    }

    @Test
    fun `판단 본문의 instanceId 가 없거나 비었거나 문자열이 아니면 사유보다 먼저 400 RESOLVE_BAD_REQUEST 다`() {
        listOf(
            """{"decision":"REWORK","reason":"r"}""",
            """{"decision":"REWORK","instanceId":" ","reason":"r"}""",
            """{"decision":"REWORK","instanceId":null,"reason":"r"}""",
            """{"decision":"REWORK","instanceId":3,"reason":"r"}""",
            """{"decision":"REWORK"}""",
        ).forEach { body ->
            val reply = resolve("operator", body = body)
            assertEquals(400, reply.statusCode.value(), body)
            assertEquals(IncidentController.RESOLVE_BAD_REQUEST, reply.rejection().error, body)
            assertEquals("instanceId 는 비어 있지 않은 문자열이다(인시던트 상세의 instanceId)", reply.rejection().detail)
        }
        assertEquals(emptyList(), bench.calls)
        assertEquals(emptyList(), log.list())

        assertEquals(200, resolve("operator", body = """{"decision":"REWORK","instanceId":"mw-9b1e","reason":"r"}""").statusCode.value())
        assertEquals("mw-9b1e", bench.writes().single().instanceId)
    }

    @Test
    fun `맞는 모드와 사유면 호스트에 닿고 결과는 200 본문이다`() {
        val injected = inject("engineer", body = """{"robotId":"quadruped-01","kind":"CONNECTION","state":"OFFLINE","reason":" 묵은 값 "}""")
        assertEquals(200, injected.statusCode.value())
        val fault = assertIs<FaultInjectionOutcome>(injected.body)
        assertEquals(OperationResult.SUCCEEDED, fault.result)
        assertEquals("OFFLINE", fault.state)

        bench.resolveAnswer = dev.picasso.ops.service.host.HostWrite.Answered(200, IncidentBench.resolved(null, "NotHeld"))
        val resolved = resolve("operator")
        assertEquals(200, resolved.statusCode.value())
        val outcome = assertIs<HoldResolveOutcome>(resolved.body)
        assertEquals("NotHeld", outcome.outcome)
        assertEquals(OperationResult.REJECTED, outcome.result)

        assertEquals(listOf("묵은 값", "보류 해소"), log.list().reversed().map { it.reason })
        assertEquals("kim", bench.writes().last().approverId)
    }

    @Test
    fun `판단 본문에 승인자 칸을 실어도 승인자는 행위자 헤더다`() {
        val reply = resolve(
            "operator", user = "kim",
            body = """{"decision":"CONFIRM_DONE","instanceId":"i-1","reason":"설비 확인","approverId":"mallory"}""",
        )
        assertEquals(200, reply.statusCode.value())
        assertEquals("kim", bench.writes().last().approverId)
        val row = log.list().single()
        assertEquals("kim", row.user)
        assertEquals("kim", json.readTree(row.request)["approverId"].asText())
    }

    @Test
    fun `인시던트 읽기는 호스트 본문 그대로이고 호스트가 안 닿으면 503 HOST_SILENT 이며 상세의 404 는 그대로 넘긴다`() {
        val listed = bench.listedAt(
            "i-2", listOf(IncidentBench.row("incident-1")), listOf(IncidentBench.copy("i-1", "incident-1")),
        )
        bench.incidents = listed
        val list = controller.incidents(null)
        assertEquals(200, list.statusCode.value())
        assertSame((listed as HostCall.Ok).value, list.body)
        assertEquals(listOf(null), bench.calls.map { it.limit })

        assertEquals(200, controller.incidents("500").statusCode.value())
        assertEquals(500, bench.calls.last().limit)
        listOf("0", "501", "x", "").forEach { raw ->
            val reply = controller.incidents(raw)
            assertEquals(400, reply.statusCode.value(), raw)
            assertEquals(IncidentController.INCIDENT_BAD_REQUEST, reply.rejection().error)
        }

        bench.incidents = HostCall.Silent("응답 없음: ConnectException")
        val silent = controller.incidents(null)
        assertEquals(503, silent.statusCode.value())
        assertEquals("HOST_SILENT", silent.rejection().error)

        val detail = json.readTree("""{"instanceId":"i-1","incidentId":"incident-1"}""")
        bench.incident = HostIncident.Found(detail)
        assertSame(detail, controller.incident("incident-1", null).body)
        assertEquals(IncidentBench.Call("incident", incidentId = "incident-1"), bench.calls.last())

        val missing = json.readTree("""{"error":"INCIDENT_NOT_FOUND","detail":"없는 인시던트다: incident-9"}""")
        bench.incident = HostIncident.NotFound(missing)
        val notFound = controller.incident("incident-9", null)
        assertEquals(404, notFound.statusCode.value())
        assertSame(missing, notFound.body)

        bench.incident = HostIncident.Silent("HTTP 500")
        val down = controller.incident("incident-1", null)
        assertEquals(503, down.statusCode.value())
        assertEquals("HOST_SILENT", down.rejection().error)
    }

    @Test
    fun `상세의 instanceId 쿼리는 그대로 호스트에 넘기고 빈 값이면 호스트를 부르지 않는 400 이다`() {
        val copy = json.readTree("""{"instanceId":"i-1","incidentId":"incident-1","held":false}""")
        bench.incident = HostIncident.Found(copy)
        val reply = controller.incident("incident-1", "i-1")
        assertEquals(200, reply.statusCode.value())
        assertSame(copy, reply.body)
        assertEquals(IncidentBench.Call("incident", incidentId = "incident-1", instanceId = "i-1"), bench.calls.single())

        listOf("", "  ").forEach { raw ->
            val blank = controller.incident("incident-1", raw)
            assertEquals(400, blank.statusCode.value())
            assertEquals(IncidentController.INCIDENT_BAD_REQUEST, blank.rejection().error)
            assertEquals("instanceId 는 비어 있지 않은 문자열이다", blank.rejection().detail)
        }
        assertEquals(1, bench.calls.size)
    }

    companion object {
        const val FAULT = """{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED","reason":"스킬 실패 시연"}"""
        const val RESOLVE = """{"decision":"REWORK","instanceId":"i-1","reason":"보류 해소"}"""
    }
}
