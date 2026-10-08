package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.cell.CellSignalOperations
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostRejection
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 셀 대역 신호 조작과 조작 기록(S3b 스펙 §7, 결정 3). 현장의 거부를 그대로 넘기고, 응답이 없으면 셀 대역 값을 다시 읽어
 * 대조한다. 호스트는 대역이다.
 */
class CellSignalOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = MissionBench()
    private val operations = CellSignalOperations(bench.host, bench.base.host, log, requeryDelay = Duration.ZERO)
    private val kim = Actor(Mode.OPERATOR, "kim")
    private val json = ObjectMapper()

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    @Test
    fun `바뀐 신호는 성공이고 신호 이름을 대상으로 이름과 값을 기록한다`() {
        val outcome = operations.write(kim, "rack_present", "true")
        assertEquals(MissionBench.Call("writeSignal", name = "rack_present", value = "true"), bench.writes().single())
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertNull(outcome.confirmation)
        assertEquals("true", outcome.signal!!["value"].asText())
        assertNull(outcome.rejection)
        val row = log.list().single()
        assertEquals(outcome.requestId, row.requestId)
        assertEquals("rack_present", row.target)
        assertEquals(Mode.OPERATOR, row.mode)
        assertEquals(
            json.readTree("""{"op":"${CellSignalOperations.OP}","name":"rack_present","value":"true"}"""),
            json.readTree(row.request),
        )
        assertEquals(OperationResult.SUCCEEDED, row.result)
    }

    @Test
    fun `현장의 거부는 거부로 남기고 오류 이름과 함께 그대로 넘긴다`() {
        val refusals = listOf(
            HostRejection(403, "SAFETY_SIGNAL_READ_ONLY", "guard_closed 는 안전 신호라 쓸 수 없다"),
            HostRejection(404, "UNKNOWN_SIGNAL", "셀 대역에 없는 신호다: rack_ready"),
            HostRejection(400, "SIGNAL_VALUE_INVALID", "BOOLEAN 신호는 true 나 false 다: TRUE"),
        )
        refusals.forEach { refusal ->
            bench.signalAnswer = HostWrite.Answered(refusal.status, """{"error":"${refusal.error}","detail":"${refusal.detail}"}""")
            val outcome = operations.write(kim, "guard_closed", "false")
            assertEquals(OperationResult.REJECTED, outcome.result)
            assertNull(outcome.confirmation)
            assertNull(outcome.signal)
            assertEquals(refusal, outcome.rejection)
        }
        assertEquals(List(3) { OperationResult.REJECTED }, log.list().map { it.result })
    }

    @Test
    fun `호스트가 안 닿으면 응답 없음 뒤 셀 대역 값을 다시 읽어 보낸 값과 같으면 반영됨이다`() {
        bench.signalAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        bench.base.cell = bench.cellWith("guard_closed" to "true", "rack_present" to "true")
        val outcome = operations.write(kim, "rack_present", "true")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
        assertEquals(1, bench.writes().size)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertEquals(setOf(outcome.requestId), rows.map { it.requestId }.toSet())
        assertEquals("true", json.readTree(rows.first().targetResponse)["observed"]["value"].asText())
    }

    @Test
    fun `현장이 안 닿은 503 도 응답 없음이고 다시 읽은 값이 다르면 반영 안 됨이다`() {
        bench.signalAnswer = HostWrite.Answered(503, """{"error":"CELL_SILENT","detail":"현장 셀 대역이 답하지 않는다"}""")
        bench.base.cell = bench.cellWith("rack_present" to "false")
        val outcome = operations.write(kim, "rack_present", "true")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        assertNull(outcome.rejection)
        assertEquals(503, json.readTree(log.list().last().targetResponse)["status"].asInt())
    }

    @Test
    fun `다시 읽은 스냅숏이나 신호 목록이 없으면 확인 행이 없고 그 이름이 없으면 반영 안 됨이다`() {
        bench.signalAnswer = HostWrite.NoResponse("응답 없음: ConnectException")
        val unknown = listOf(
            HostCall.Silent("응답 없음: ConnectException"),
            HostCall.Ok(json.readTree("""{"cell":null}""")),
            HostCall.Ok(json.readTree("""{"cell":{"presentations":[],"slots":[],"signals":null}}""")),
        )
        unknown.forEach { cell ->
            bench.base.cell = cell
            assertNull(operations.write(kim, "rack_present", "true").confirmation, "$cell")
        }
        assertEquals(List(3) { OperationResult.NO_RESPONSE }, log.list().map { it.result })

        bench.base.cell = bench.cellWith("lot_code" to "LOT-0001")
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.write(kim, "rack_present", "true").confirmation)
        assertTrue(json.readTree(log.list().first().targetResponse)["observed"].isNull)
    }
}
