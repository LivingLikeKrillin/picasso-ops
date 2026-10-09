package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.IncidentBench.Companion.resolution
import dev.picasso.ops.service.IncidentBench.Companion.row
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.incidents.FaultOperations
import dev.picasso.ops.service.incidents.HoldResolutions
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostRejection
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 장애 주입과 운영자 판단의 조작 기록(S4a 스펙 §7, T7). 결과 매김, 거부의 원래 이름, 응답 없음 뒤 판단 재조회의 대조를 본다.
 * 호스트는 대역이다.
 */
class IncidentOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = IncidentBench()
    private val clock = MovableClock(T0)
    private val faults = FaultOperations(bench.host, log)
    private val resolutions = HoldResolutions(bench.host, log, requeryDelay = Duration.ZERO, clock = clock)
    private val lee = Actor(Mode.ENGINEER, "lee")
    private val kim = Actor(Mode.OPERATOR, "kim")
    private val json = ObjectMapper()

    /** 시험이 옮기는 시계. 호스트가 판단을 받는 순간 앞으로 옮겨 «요청 직전» 과 «요청 뒤» 를 가른다. */
    class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
        // 호스트는 요청을 받은 뒤 판단하므로 판단의 실제 시각은 요청 직전 시각보다 늦다.
        bench.onResolve = { clock.now = T0.plusSeconds(10) }
    }

    @Test
    fun `받아들인 장애 주입은 성공이고 기체를 대상으로 종류와 값과 사유를 기록한다`() {
        val outcome = faults.inject(lee, "humanoid-01", "SKILL_EXECUTION_FAILED", null, "스킬 실패 시연")
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertNull(outcome.confirmation)
        assertNull(outcome.rejection)
        assertEquals("RETRIABLE", outcome.fault!!["taskState"].asText())
        assertEquals(json.readTree("""{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED"}"""), bench.writes().single().body)
        val row = log.list().single()
        assertEquals(outcome.requestId, row.requestId)
        assertEquals("humanoid-01", row.target)
        assertEquals(Mode.ENGINEER, row.mode)
        assertEquals("lee", row.user)
        assertEquals("스킬 실패 시연", row.reason)
        assertEquals(
            json.readTree("""{"op":"${FaultOperations.OP}","robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED","state":null}"""),
            json.readTree(row.request),
        )
        assertEquals("JO-1#RACK-204.S01", json.readTree(row.targetResponse)["body"]["taskId"].asText())

        bench.faultAnswer = HostWrite.Answered(200, """{"robotId":"quadruped-01","kind":"CONNECTION","state":"OFFLINE","changed":true}""")
        assertEquals(OperationResult.SUCCEEDED, faults.inject(lee, "quadruped-01", "CONNECTION", "OFFLINE", "묵은 값 시연").result)
        assertEquals(json.readTree("""{"robotId":"quadruped-01","kind":"CONNECTION","state":"OFFLINE"}"""), bench.writes().last().body)
    }

    @Test
    fun `현장의 거부는 거부로 남기고 원래 오류 이름을 응답 칸과 응답에 그대로 넘긴다`() {
        val refusals = listOf(
            HostRejection(409, "NO_RUNNING_TASK", "humanoid-01 에 진행 중인 pick_place 태스크가 없다"),
            HostRejection(404, "UNKNOWN_ROBOT", "이 현장에 없는 기체다: ghost-01"),
            HostRejection(400, "UNSUPPORTED_FAULT", "받지 않는 장애다: PAYLOAD_LOST"),
        )
        refusals.forEach { refusal ->
            bench.faultAnswer = HostWrite.Answered(refusal.status, """{"error":"${refusal.error}","detail":"${refusal.detail}"}""")
            val outcome = faults.inject(lee, "humanoid-01", "SKILL_EXECUTION_FAILED", null, "거부 시연")
            assertEquals(OperationResult.REJECTED, outcome.result)
            assertNull(outcome.fault)
            assertEquals(refusal, outcome.rejection)
        }
        val rows = log.list()
        assertEquals(List(3) { OperationResult.REJECTED }, rows.map { it.result })
        assertEquals(
            refusals.map { it.error }.reversed(),
            rows.map { json.readTree(it.targetResponse)["body"]["error"].asText() },
        )
    }

    @Test
    fun `현장이나 호스트가 안 닿으면 장애 주입은 응답 없음 하나만 남기고 재조회하지 않는다`() {
        listOf(
            HostWrite.Answered(503, """{"error":"CELL_SILENT","detail":"현장 셀 대역이 답하지 않는다"}"""),
            HostWrite.NoResponse("응답 없음: HttpTimeoutException"),
        ).forEach { answer ->
            bench.faultAnswer = answer
            val outcome = faults.inject(lee, "humanoid-01", "SKILL_EXECUTION_FAILED", null, "불통 시연")
            assertEquals(OperationResult.NO_RESPONSE, outcome.result)
            assertNull(outcome.confirmation)
            assertNull(outcome.rejection)
        }
        assertEquals(List(2) { OperationResult.NO_RESPONSE }, log.list().map { it.result })
        assertEquals(listOf("injectFault", "injectFault"), bench.calls.map { it.op })
        assertEquals("CELL_SILENT", json.readTree(log.list().last().targetResponse)["body"]["error"].asText())
    }

    @Test
    fun `판단 Resolved 는 성공이고 승인자와 결정과 실행과 단위와 사유와 결과 이름을 기록한다`() {
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "랙 재배치 뒤 재작업")
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertEquals("Resolved", outcome.outcome)
        assertNull(outcome.confirmation)
        assertNull(outcome.rejection)
        val call = bench.writes().single()
        assertEquals(
            IncidentBench.Call(
                "resolve", executionId = "exec-1", unitId = "rack-arrival", decision = "REWORK", approverId = "kim",
                requestId = outcome.requestId, instanceId = "i-1",
            ),
            call,
        )
        val row = log.list().single()
        assertEquals("exec-1/rack-arrival", row.target)
        assertEquals(Mode.OPERATOR, row.mode)
        assertEquals("랙 재배치 뒤 재작업", row.reason)
        assertEquals(
            json.readTree(
                """{"op":"${HoldResolutions.OP}","executionId":"exec-1","unitId":"rack-arrival","decision":"REWORK","approverId":"kim","instanceId":"i-1"}""",
            ),
            json.readTree(row.request),
        )
        assertEquals("Resolved", json.readTree(row.targetResponse)["body"]["result"].asText())
        assertEquals("incident-1", json.readTree(row.targetResponse)["body"]["incidentId"].asText())
    }

    @Test
    fun `판단 NotHeld 와 Refused 는 거부로 남기고 응답 칸에 원래 결과 이름이 있다`() {
        listOf("NotHeld" to null, "Refused" to "에이전트는 운영자 판단을 내지 못한다").forEach { (name, detail) ->
            bench.resolveAnswer = HostWrite.Answered(200, IncidentBench.resolved(null, name, detail))
            val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "CONFIRM_DONE", "i-1", "확인")
            assertEquals(OperationResult.REJECTED, outcome.result)
            assertEquals(name, outcome.outcome)
            assertNull(outcome.rejection)
            assertNull(outcome.confirmation)
        }
        val rows = log.list()
        assertEquals(List(2) { OperationResult.REJECTED }, rows.map { it.result })
        assertEquals(listOf("Refused", "NotHeld"), rows.map { json.readTree(it.targetResponse)["body"]["result"].asText() })
    }

    @Test
    fun `판단 응답이 없으면 같은 실행과 단위의 인시던트 하나라도 행위자의 같은 결정이 요청 뒤에 붙었으면 반영됨이다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        // 반영된 재작업 뒤 대기가 기한을 다시 넘겨 새 보류가 가장 최근이다. 앞 인시던트의 판단으로 반영을 읽어야 한다.
        bench.incidents = bench.listed(
            row("incident-2"),
            row("incident-1", resolution = resolution("REWORK", "kim", T0.plusSeconds(1))),
        )
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
        assertNull(outcome.outcome)
        assertEquals(500, bench.calls.single { it.op == "incidents" }.limit)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertEquals(setOf(outcome.requestId), rows.map { it.requestId }.toSet())
        assertEquals("incident-1", json.readTree(rows.first().targetResponse)["observed"]["incidentId"].asText())
    }

    @Test
    fun `같은 사람이 요청 앞에 낸 판단이나 다른 사람 다른 결정 다른 단위 다른 실행의 판단은 반영 안 됨이다`() {
        bench.resolveAnswer = HostWrite.Answered(503, "<html>bad gateway</html>")
        val misses = listOf(
            // 같은 사람, 같은 결정, 같은 단위지만 요청 직전 시각보다 앞선 판단.
            row("incident-1", resolution = resolution("REWORK", "kim", T0.minusMillis(1))),
            row("incident-1", resolution = resolution("REWORK", "park", T0.plusSeconds(1))),
            row("incident-1", resolution = resolution("CONFIRM_DONE", "kim", T0.plusSeconds(1))),
            row("incident-1", unitId = "RACK-204.S01", resolution = resolution("REWORK", "kim", T0.plusSeconds(1))),
            row("incident-1", executionId = "exec-2", resolution = resolution("REWORK", "kim", T0.plusSeconds(1))),
        )
        misses.forEach { miss ->
            clock.now = T0
            bench.incidents = bench.listed(miss)
            val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
            assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation, miss)
        }
        // 요청 직전 시각과 같은 판단 시각은 반영이다(경계).
        clock.now = T0
        bench.incidents = bench.listed(row("incident-1", resolution = resolution("REWORK", "kim", T0)))
        assertEquals(OperationResult.CONFIRMED_APPLIED, resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation)
    }

    @Test
    fun `대조 시각은 요청을 보내기 직전의 시각이다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        // 호스트가 받은 뒤(T0+10초)가 아니라 보내기 직전(T0)과 비교해야 T0+1초의 판단이 반영으로 읽힌다.
        bench.incidents = bench.listed(row("incident-1", resolution = resolution("REWORK", "kim", T0.plusSeconds(1))))
        assertEquals(OperationResult.CONFIRMED_APPLIED, resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation)
    }

    @Test
    fun `판단 재조회가 목록을 못 읽으면 확인 행이 없고 모르는 결과 이름은 응답 없음이다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: ConnectException")
        bench.incidents = HostCall.Silent("응답 없음: ConnectException")
        assertNull(resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation)

        bench.resolveAnswer = HostWrite.Answered(200, IncidentBench.resolved(null, "RESOLVED"))
        bench.incidents = bench.listed()
        clock.now = T0
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        assertEquals(
            listOf(OperationResult.CONFIRMED_NOT_APPLIED, OperationResult.NO_RESPONSE, OperationResult.NO_RESPONSE),
            log.list().map { it.result },
        )
    }

    @Test
    fun `이전 인스턴스를 실은 판단의 호스트 409 INSTANCE_MISMATCH 는 거부이고 원래 이름이 응답 칸과 응답에 있다`() {
        bench.resolveAnswer = HostWrite.Answered(
            409, """{"error":"INSTANCE_MISMATCH","detail":"판단 요청의 인스턴스(i-0)가 지금 인스턴스(i-1)가 아니다"}""",
        )
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-0", "재작업")
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals(HostRejection(409, "INSTANCE_MISMATCH", "판단 요청의 인스턴스(i-0)가 지금 인스턴스(i-1)가 아니다"), outcome.rejection)
        assertNull(outcome.outcome)
        assertNull(outcome.confirmation)
        assertEquals("i-0", bench.writes().single().instanceId)
        val row = log.list().single()
        assertEquals(OperationResult.REJECTED, row.result)
        assertEquals("INSTANCE_MISMATCH", json.readTree(row.targetResponse)["body"]["error"].asText())
        assertEquals(409, json.readTree(row.targetResponse)["status"].asInt())
        assertEquals("i-0", json.readTree(row.request)["instanceId"].asText())
        // 재조회하지 않는다.
        assertEquals(emptyList(), bench.calls.filter { it.op == "incidents" })
    }

    @Test
    fun `판단 재조회는 목록의 인스턴스가 요청한 인스턴스와 다르면 incidents 가 아니라 earlier 의 그 인스턴스 사본만 본다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        val applied = resolution("REWORK", "kim", T0.plusSeconds(1))
        // 재기동 뒤 새 인스턴스의 exec-1 은 다른 실행이다. 같은 실행 id·단위·판단자·결정이어도 반영으로 읽지 않는다.
        bench.incidents = bench.listedAt("i-2", listOf(row("incident-1", resolution = applied)))
        val newInstance = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, newInstance.confirmation)

        // 이전 인스턴스 사본 가운데 요청한 인스턴스(i-1)의 것만 본다. 다른 이전 인스턴스(i-0)의 판단은 반영이 아니다.
        clock.now = T0
        bench.incidents = bench.listedAt("i-2", emptyList(), listOf(IncidentBench.copy("i-0", "incident-1", applied)))
        assertEquals(
            OperationResult.CONFIRMED_NOT_APPLIED,
            resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation,
        )

        clock.now = T0
        bench.incidents = bench.listedAt(
            "i-2",
            listOf(row("incident-1")),
            listOf(IncidentBench.copy("i-1", "incident-2"), IncidentBench.copy("i-1", "incident-1", applied)),
        )
        val earlier = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
        assertEquals(OperationResult.CONFIRMED_APPLIED, earlier.confirmation)
        val observed = json.readTree(log.list().first().targetResponse)["observed"]
        assertEquals("i-1", observed["instanceId"].asText())
        assertEquals("incident-1", observed["incidentId"].asText())

        // 같은 인스턴스면 지금 목록을 본다(앞 시험들과 같다).
        clock.now = T0
        bench.incidents = bench.listedAt("i-1", listOf(row("incident-1", resolution = applied)))
        assertEquals(
            OperationResult.CONFIRMED_APPLIED,
            resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation,
        )
    }

    @Test
    fun `판단 재조회의 반영 안 됨 관측은 요청한 인스턴스의 그 단위 가장 최근 사본이다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        bench.incidents = bench.listedAt(
            "i-2",
            listOf(row("incident-9")),
            listOf(IncidentBench.copy("i-1", "incident-3"), IncidentBench.copy("i-1", "incident-2", executionId = "exec-2")),
        )
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        val observed = json.readTree(log.list().first().targetResponse)["observed"]
        assertEquals("incident-3", observed["incidentId"].asText())
        assertEquals("i-1", observed["instanceId"].asText())
    }

    @Test
    fun `판단 본문이 틀렸다는 호스트 400 은 거부이고 오류 이름을 넘긴다`() {
        bench.resolveAnswer = HostWrite.Answered(400, """{"error":"BAD_REQUEST","detail":"decision 이 두 값이 아니다"}""")
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals("BAD_REQUEST", outcome.rejection!!.error)
        assertNull(outcome.outcome)
    }

    companion object {
        val T0: Instant = Instant.parse("2026-10-09T02:31:00Z")
    }
}
