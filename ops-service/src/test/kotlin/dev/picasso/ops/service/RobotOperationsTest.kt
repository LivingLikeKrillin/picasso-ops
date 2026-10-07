package dev.picasso.ops.service

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.Rejections
import dev.picasso.ops.service.operations.RobotOperations
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RegistryWrite
import dev.picasso.ops.service.registry.RobotWrites
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 조작 한 번이 조작 기록에 남는 모양과 응답 없음 뒤 재조회(스펙 §7.1·§9). registry 는 대역이다. */
class RobotOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val at = Instant.parse("2026-10-07T00:00:00Z")
    private val kim = Actor(Mode.OPERATOR, "kim")

    private var answer: RegistryWrite = RegistryWrite.Answered(200, """{"robot":"r1","status":"RETIRED"}""")
    private var list: RegistryCall<List<RegistryRobot>> = RegistryCall.Ok(emptyList())
    private val sent = mutableListOf<String>()

    private val writes = object : RobotWrites {
        override fun declare(siteId: String, robotId: String, serialNumber: String, displayName: String?, actor: String) =
            answer.also { sent += "declare $siteId $robotId $serialNumber $actor" }

        override fun retire(robotId: String, reason: String, actor: String) =
            answer.also { sent += "retire $robotId $reason $actor" }

        override fun reinstate(robotId: String, actor: String) = answer.also { sent += "reinstate $robotId $actor" }
    }

    private val operations =
        RobotOperations(writes, { list }, log, "site-01", Clock.fixed(at, ZoneOffset.UTC), requeryDelay = Duration.ZERO)

    private fun robot(status: String, origin: String = "DECLARED", serialNumber: String = "SN-1") =
        RegistryRobot(robotId = "r1", siteId = "site-01", serialNumber = serialNumber, status = status, origin = origin)

    private val lee = Actor(Mode.ENGINEER, "lee")

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    @Test
    fun `성공한 조작은 사유와 행위자와 registry 응답과 함께 한 행으로 남는다`() {
        val outcome = operations.retire(kim, "r1", "정비")
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertEquals(listOf("retire r1 정비 operator/kim"), sent)
        val row = log.list().single()
        assertEquals(outcome.requestId, row.requestId)
        assertEquals("robot r1", row.target)
        assertEquals("정비", row.reason)
        assertEquals(OperationResult.SUCCEEDED, row.result)
        assertTrue(row.registryResponse!!.contains("\"status\": 200"), row.registryResponse)
    }

    @Test
    fun `선언은 운영 서비스의 사이트로 보낸다`() {
        answer = RegistryWrite.Answered(201, """{"robot":"r1","status":"CLAIMED"}""")
        operations.declare(Actor(Mode.ENGINEER, "lee"), "r1", "SN-1", null)
        assertEquals(listOf("declare site-01 r1 SN-1 engineer/lee"), sent)
    }

    @Test
    fun `거절은 대응표로 옮기고 거절 행으로 남긴다`() {
        answer = RegistryWrite.Answered(409, """{"error":"퇴역한 기체다","status":"RETIRED"}""")
        val outcome = operations.declare(Actor(Mode.ENGINEER, "lee"), "r1", "SN-1", null)
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals(Rejections.RETIRED_ALREADY, outcome.rejection!!.kind)
        assertEquals(at, outcome.rejection.checkedAt)
        assertEquals(OperationResult.REJECTED, log.list().single().result)
    }

    @Test
    fun `401 은 거절 행으로 남기고 대응표가 아니라 전체 상태로 넘긴다`() {
        answer = RegistryWrite.Answered(401, "")
        val outcome = operations.retire(kim, "r1", "정비")
        assertEquals(true, outcome.unauthorized)
        assertNull(outcome.rejection)
        assertEquals(OperationResult.REJECTED, log.list().single().result)
    }

    @Test
    fun `응답이 없으면 응답 없음을 남기고 다시 읽어 반영됨을 같은 요청 id 로 붙인다`() {
        answer = RegistryWrite.NoResponse("응답 없음: HttpTimeoutException")
        list = RegistryCall.Ok(listOf(robot("RETIRED")))
        val outcome = operations.retire(kim, "r1", "정비")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertEquals(setOf(outcome.requestId), rows.map { it.requestId }.toSet())
        // 확인 행에는 재조회에서 본 원장 상태가 남는다.
        assertTrue(rows.first().registryResponse!!.contains("\"status\": \"RETIRED\""), rows.first().registryResponse)
    }

    @Test
    fun `선언의 반영됨은 선언으로 들어와 퇴역하지 않은 기체다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        list = RegistryCall.Ok(listOf(robot("CLAIMED")))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.declare(lee, "r1", "SN-1", null).confirmation)
        list = RegistryCall.Ok(emptyList())
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.declare(lee, "r1", "SN-1", null).confirmation)
    }

    @Test
    fun `목록에 있어도 퇴역했거나 발견된 기체면 선언이 반영된 것이 아니다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        list = RegistryCall.Ok(listOf(robot("RETIRED")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.declare(lee, "r1", "SN-1", null).confirmation)
        list = RegistryCall.Ok(listOf(robot("DISCOVERED", origin = "DISCOVERED")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.declare(lee, "r1", "SN-1", null).confirmation)
    }

    @Test
    fun `이미 선언된 기체의 일련번호를 고치는 선언은 목록의 일련번호가 그대로면 반영된 것이 아니다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        list = RegistryCall.Ok(listOf(robot("CLAIMED", serialNumber = "SN-OLD")))
        val outcome = operations.declare(lee, "r1", "SN-NEW", null)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        assertEquals(listOf(OperationResult.CONFIRMED_NOT_APPLIED, OperationResult.NO_RESPONSE), log.list().map { it.result })
        list = RegistryCall.Ok(listOf(robot("CLAIMED", serialNumber = "SN-NEW")))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.declare(lee, "r1", "SN-NEW", null).confirmation)
    }

    @Test
    fun `재조회는 기본 값으로 쓰면 쓰기 뒤 1초 가까이 기다린 다음에 읽는다`() {
        var wrote = 0L
        var read = 0L
        val timed = RobotOperations(
            object : RobotWrites {
                override fun declare(siteId: String, robotId: String, serialNumber: String, displayName: String?, actor: String) =
                    RegistryWrite.NoResponse("응답 없음").also { wrote = System.nanoTime() }

                override fun retire(robotId: String, reason: String, actor: String) = RegistryWrite.NoResponse("응답 없음")

                override fun reinstate(robotId: String, actor: String) = RegistryWrite.NoResponse("응답 없음")
            },
            { RegistryCall.Ok(emptyList<RegistryRobot>()).also { read = System.nanoTime() } },
            log, "site-01", Clock.fixed(at, ZoneOffset.UTC),
        )
        timed.declare(lee, "r1", "SN-1", null)
        assertTrue(read > wrote && Duration.ofNanos(read - wrote) >= Duration.ofMillis(900), "쓰기와 재조회 사이 ${(read - wrote) / 1_000_000} ms")
    }

    @Test
    fun `5xx 는 응답 없음과 같이 다루고 반영 안 됨을 붙인다`() {
        answer = RegistryWrite.Answered(503, "")
        list = RegistryCall.Ok(listOf(robot("CONFIRMED")))
        val outcome = operations.retire(kim, "r1", "정비")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        assertNull(outcome.rejection)
    }

    @Test
    fun `복귀의 반영됨은 퇴역이 아닌 상태다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        list = RegistryCall.Ok(listOf(robot("CLAIMED")))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.reinstate(kim, "r1").confirmation)
        list = RegistryCall.Ok(listOf(robot("RETIRED")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.reinstate(kim, "r1").confirmation)
    }

    @Test
    fun `재조회도 실패하면 확인 행을 붙이지 않고 모름으로 둔다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        list = RegistryCall.Silent("응답 없음")
        val outcome = operations.declare(Actor(Mode.ENGINEER, "lee"), "r1", "SN-1", null)
        assertNull(outcome.confirmation)
        assertEquals(listOf(OperationResult.NO_RESPONSE), log.list().map { it.result })
    }
}
