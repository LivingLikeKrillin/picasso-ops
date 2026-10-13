package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.executions.ExecutionCancelOutcome
import dev.picasso.ops.service.executions.ExecutionControls
import dev.picasso.ops.service.executions.RobotEmptyOutcome
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostExecutionControls
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.web.ExecutionControlController
import dev.picasso.ops.service.web.PreRejection
import dev.picasso.registry.PostgresSupport
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 운영자 실행 취소와 기체 빈손 확인 API(P0-b2). 관문과 사전 거부, 조작 기록의 결과 매김, 응답 없음 뒤 재조회의 대조를 본다. 컨트롤러를
 * 스프링 없이 바로 부르고 호스트는 대역이다. `application/json` 제한은 스프링의 몫이다.
 */
class ExecutionControlsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val clock = IncidentOperationsTest.MovableClock(T0)
    private val json = ObjectMapper()
    private val host = FakeHost()
    private val controller = ExecutionControlController(ExecutionControls(host, log, requeryDelay = Duration.ZERO, clock = clock))

    /** 호스트 대역. 기본은 취소를 CANCELED 로, 해소를 200 으로 답한다. */
    inner class FakeHost : HostExecutionControls {
        var cancelAnswer: HostWrite = HostWrite.Answered(200, cancelBody("CANCELED"))
        var clearAnswer: HostWrite = HostWrite.Answered(200, CLEARED)
        var executions: HostCall<JsonNode> = HostCall.Silent("응답 없음: ConnectException")
        var holds: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"holds":[$HOLD]}"""))
        val calls = mutableListOf<Map<String, Any?>>()

        override fun executions(): HostCall<JsonNode> = executions.also { calls += mapOf("op" to "executions") }

        override fun cancelExecution(executionId: String, approverId: String, reason: String, instanceId: String, requestId: UUID): HostWrite {
            calls += mapOf("op" to "cancel", "executionId" to executionId, "approverId" to approverId, "reason" to reason, "instanceId" to instanceId)
            return cancelAnswer
        }

        override fun residualHolds(): HostCall<JsonNode> = holds.also { calls += mapOf("op" to "residualHolds") }

        override fun clearResidualHold(robotId: String, markerId: Long, approverId: String, reason: String, requestId: UUID): HostWrite {
            calls += mapOf("op" to "clear", "robotId" to robotId, "markerId" to markerId, "approverId" to approverId, "reason" to reason)
            return clearAnswer
        }

        fun writes() = calls.filter { it["op"] in setOf("cancel", "clear") }
    }

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    private fun ResponseEntity<Any>.rejection(): PreRejection = assertIs<PreRejection>(body)

    private fun cancel(mode: String?, user: String? = "kim", body: String = """{"instanceId":"mw-1","reason":"부분 완료 닫기"}"""): ResponseEntity<Any> =
        controller.cancel("exec-1", mode, user, body.toByteArray())

    private fun clear(mode: String?, user: String? = "kim", marker: String = "7", body: String = """{"reason":"현장에서 빈손 확인"}"""): ResponseEntity<Any> =
        controller.clear("humanoid-01", marker, mode, user, body.toByteArray())

    @Test
    fun `두 조작은 엔지니어 모드면 403 이고 행위자 헤더가 없으면 400 이며 호스트를 부르지 않고 기록하지 않는다`() {
        listOf(cancel("engineer"), clear("engineer")).forEach { reply ->
            assertEquals(403, reply.statusCode.value())
            assertEquals("MODE_NOT_ALLOWED", reply.rejection().error)
            assertEquals("이 조작은 operator 모드에서 한다", reply.rejection().detail)
        }
        listOf(cancel(null), cancel("operator", user = null), clear(null), clear("operator", user = "김 운영")).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals("ACTOR_REQUIRED", reply.rejection().error)
        }
        assertEquals(emptyList(), host.calls)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `본문이 틀리면 사유보다 먼저 400 이고 사유가 없으면 400 REASON_REQUIRED 이며 호스트를 부르지 않는다`() {
        listOf("취소", """{"reason":"r"}""", """{"instanceId":" ","reason":"r"}""", """{"instanceId":3,"reason":"r"}""").forEach { body ->
            assertEquals(ExecutionControlController.CANCEL_BAD_REQUEST, cancel("operator", body = body).rejection().error, body)
        }
        listOf("x", "0", "-3", "1.5").forEach { marker ->
            assertEquals(ExecutionControlController.CLEAR_BAD_REQUEST, clear("operator", marker = marker).rejection().error, marker)
        }
        listOf(
            cancel("operator", body = """{"instanceId":"mw-1"}"""),
            cancel("operator", body = """{"instanceId":"mw-1","reason":"  "}"""),
            clear("operator", body = """{}"""),
            clear("operator", body = """{"reason":7}"""),
        ).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals(ExecutionControlController.REASON_REQUIRED, reply.rejection().error)
        }
        assertEquals(emptyList(), host.calls)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `취소는 승인자가 행위자이고 CANCELED 는 성공 그 밖의 결과와 호스트 4xx 는 거부로 남는다`() {
        val canceled = assertIs<ExecutionCancelOutcome>(cancel("operator", body = """{"instanceId":"mw-1","reason":" 부분 완료 닫기 ","approverId":"mallory"}""").body)
        assertEquals(listOf(OperationResult.SUCCEEDED, "CANCELED"), listOf(canceled.result, canceled.outcome))
        assertEquals(
            mapOf("op" to "cancel", "executionId" to "exec-1", "approverId" to "kim", "reason" to "부분 완료 닫기", "instanceId" to "mw-1"),
            host.writes().single(),
        )
        val row = log.list().single()
        assertEquals(listOf("kim", "exec-1", "부분 완료 닫기"), listOf(row.user, row.target, row.reason))
        assertEquals(
            json.readTree("""{"op":"CANCEL_EXECUTION","executionId":"exec-1","approverId":"kim","instanceId":"mw-1"}"""),
            json.readTree(row.request),
        )

        listOf("NOT_CANCELABLE", "NOT_FOUND", "REFUSED").forEach { result ->
            host.cancelAnswer = HostWrite.Answered(200, cancelBody(result))
            val refused = assertIs<ExecutionCancelOutcome>(cancel("operator").body)
            assertEquals(listOf(OperationResult.REJECTED, result), listOf(refused.result, refused.outcome))
        }
        host.cancelAnswer = HostWrite.Answered(409, """{"error":"INSTANCE_MISMATCH","detail":"x"}""")
        val mismatch = assertIs<ExecutionCancelOutcome>(cancel("operator").body)
        assertEquals(OperationResult.REJECTED, mismatch.result)
        assertEquals("INSTANCE_MISMATCH", mismatch.rejection!!.error)
        assertNull(mismatch.outcome)
        assertEquals(5, log.list().size)
    }

    @Test
    fun `취소 응답이 없으면 실행 목록의 cancel 로 대조하고 인스턴스가 바뀌었으면 확인하지 못한다`() {
        host.cancelAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        host.executions = executions("mw-1", """{"approverId":"kim","approverKind":"PERSON","reason":"r","physicalState":"PARTIAL","recordedAt":"${T0.plusSeconds(1)}"}""")
        val applied = assertIs<ExecutionCancelOutcome>(cancel("operator").body)
        assertEquals(listOf(OperationResult.NO_RESPONSE, OperationResult.CONFIRMED_APPLIED), listOf(applied.result, applied.confirmation))

        // 앞선 사람의 취소(보내기 직전보다 이른 기록)는 이 요청의 반영이 아니다.
        host.executions = executions("mw-1", """{"approverId":"kim","approverKind":"PERSON","reason":"r","physicalState":"PARTIAL","recordedAt":"${T0.minusSeconds(60)}"}""")
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, assertIs<ExecutionCancelOutcome>(cancel("operator").body).confirmation)
        host.executions = executions("mw-1", "null")
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, assertIs<ExecutionCancelOutcome>(cancel("operator").body).confirmation)

        host.executions = executions("mw-2", """{"approverId":"kim","approverKind":"PERSON","reason":"r","physicalState":"PARTIAL","recordedAt":"${T0.plusSeconds(1)}"}""")
        val restarted = assertIs<ExecutionCancelOutcome>(cancel("operator").body)
        assertEquals(OperationResult.NO_RESPONSE, restarted.result)
        assertNull(restarted.confirmation)
    }

    @Test
    fun `빈손 확인은 승인자가 행위자이고 해소는 성공 404 409 는 거부이며 응답이 없으면 미해소 목록으로 대조한다`() {
        val cleared = assertIs<RobotEmptyOutcome>(clear("operator").body)
        assertEquals(OperationResult.SUCCEEDED, cleared.result)
        assertEquals(
            mapOf("op" to "clear", "robotId" to "humanoid-01", "markerId" to 7L, "approverId" to "kim", "reason" to "현장에서 빈손 확인"),
            host.writes().single(),
        )
        val row = log.list().single()
        assertEquals(listOf("kim", "humanoid-01/7", "현장에서 빈손 확인"), listOf(row.user, row.target, row.reason))
        assertEquals(
            json.readTree("""{"op":"CONFIRM_ROBOT_EMPTY","robotId":"humanoid-01","markerId":7,"approverId":"kim"}"""),
            json.readTree(row.request),
        )

        listOf(404 to "RESIDUAL_HOLD_NOT_FOUND", 409 to "RESIDUAL_HOLD_ALREADY_CLEARED").forEach { (status, error) ->
            host.clearAnswer = HostWrite.Answered(status, """{"error":"$error","detail":"x"}""")
            val refused = assertIs<RobotEmptyOutcome>(clear("operator").body)
            assertEquals(OperationResult.REJECTED, refused.result)
            assertEquals(error, refused.rejection!!.error)
        }

        host.clearAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, assertIs<RobotEmptyOutcome>(clear("operator").body).confirmation)
        host.holds = HostCall.Ok(json.readTree("""{"holds":[]}"""))
        assertEquals(OperationResult.CONFIRMED_APPLIED, assertIs<RobotEmptyOutcome>(clear("operator").body).confirmation)
        host.holds = HostCall.Silent("응답 없음")
        assertNull(assertIs<RobotEmptyOutcome>(clear("operator").body).confirmation)
    }

    @Test
    fun `미해소 표시 읽기는 모드와 관계없이 호스트 본문 그대로이고 호스트가 안 닿으면 503 HOST_SILENT 다`() {
        val read = controller.residualHolds()
        assertEquals(200, read.statusCode.value())
        assertEquals(json.readTree("""{"holds":[$HOLD]}"""), read.body)
        host.holds = HostCall.Silent("응답 없음: ConnectException")
        val silent = controller.residualHolds()
        assertEquals(503, silent.statusCode.value())
        assertEquals("HOST_SILENT", silent.rejection().error)
        assertEquals(emptyList(), log.list())
    }

    private fun executions(instanceId: String, cancel: String): HostCall<JsonNode> = HostCall.Ok(
        json.readTree(
            """{"instanceId":"$instanceId","pumpedAt":null,"executions":[{"executionId":"exec-1","jobOrderId":"JO-1","robotId":"humanoid-01",""" +
                """"physicalState":"CANCELING","cancel":$cancel}],"restore":null,"journalBlockedExecutionIds":[]}""",
        ),
    )

    private companion object {
        val T0: Instant = Instant.parse("2026-10-13T09:00:00Z")

        const val HOLD =
            """{"markerId":7,"robotId":"humanoid-01","jobOrderId":"JO-1","executionId":"exec-1","instanceId":"mw-1","unitId":"RACK-204.S02","objectRef":"C-1","recordedAt":"2026-10-13T08:59:00Z"}"""

        val CLEARED =
            """{"hold":$HOLD,"clearing":{"approverId":"kim","approverKind":"PERSON","reason":"r","clearedAt":"2026-10-13T09:00:01Z"},"requestId":null}"""

        fun cancelBody(result: String) =
            """{"result":"$result","executionId":"exec-1","physicalState":"PARTIAL","detail":null,"requestId":null}"""
    }
}
