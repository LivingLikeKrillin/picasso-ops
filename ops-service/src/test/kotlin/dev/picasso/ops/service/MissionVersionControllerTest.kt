package dev.picasso.ops.service

import dev.picasso.ops.service.MissionBench.Companion.WM
import dev.picasso.ops.service.cell.CellSignalOperations
import dev.picasso.ops.service.cell.SignalWriteOutcome
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.missions.MissionOperationOutcome
import dev.picasso.ops.service.missions.MissionOperations
import dev.picasso.ops.service.missions.MissionValidationReply
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.web.CellSignalController
import dev.picasso.ops.service.web.MissionVersionController
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
 * 임무 버전·신호 조작 API 의 관문과 사전 거부(S3b 스펙 §7). 컨트롤러를 스프링 없이 바로 부른다. 관문(`guarded`)과 본문 읽기,
 * 상태 코드의 고름이 컨트롤러 안에 있기 때문이다. `application/json` 제한은 스프링의 몫이라 통합 시험이 본다.
 */
class MissionVersionControllerTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = MissionBench()
    private val missions = MissionVersionController(
        MissionOperations(bench.base.robotList, bench.host, log, requeryDelay = Duration.ZERO),
        bench.host,
    )
    private val signals = CellSignalController(CellSignalOperations(bench.host, bench.base.host, log, requeryDelay = Duration.ZERO))

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    private fun ResponseEntity<Any>.rejection(): PreRejection = assertIs<PreRejection>(body)

    /** 엔지니어 조작 넷을 [mode]·[user] 로 부른다. 본문은 각 조작의 정상 본문 또는 [body]. */
    private fun editAll(mode: String?, user: String? = "lee", body: String? = null): List<ResponseEntity<Any>> = listOf(
        missions.saveDraft(WM, mode, user, (body ?: """{"definition":"{}"}""").toByteArray()),
        missions.validate(WM, "3", mode, user),
        missions.mockRun(WM, "3", mode, user),
        missions.activate(WM, "3", mode, user, (body ?: """{"reason":"랙 도착 대기 도입"}""").toByteArray()),
    )

    @Test
    fun `임무 편집 넷은 운영자 모드면 403 이고 호스트를 부르지 않고 기록하지 않는다`() {
        editAll("operator").forEach { reply ->
            assertEquals(403, reply.statusCode.value())
            assertEquals("MODE_NOT_ALLOWED", reply.rejection().error)
            assertEquals("이 조작은 engineer 모드에서 한다", reply.rejection().detail)
        }
        // 깨진 본문도 관문이 먼저다.
        editAll("operator", body = "임무").forEach { assertEquals(403, it.statusCode.value()) }
        assertEquals(emptyList(), bench.writes())
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `행위자 헤더가 없거나 틀리면 넷 모두 400 ACTOR_REQUIRED 다`() {
        (editAll(null) + editAll("engineer", user = null) + editAll("engineer", user = "이 엔지")).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals("ACTOR_REQUIRED", reply.rejection().error)
        }
        assertEquals(emptyList(), bench.writes())
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `엔지니어 모드의 넷은 200 이고 검증을 뺀 셋만 기록한다`() {
        val replies = editAll("engineer")
        replies.forEach { assertEquals(200, it.statusCode.value()) }
        assertIs<MissionOperationOutcome>(replies[0].body)
        assertEquals("PASSED", assertIs<MissionValidationReply>(replies[1].body).outcome["result"].asText())
        assertEquals(OperationResult.SUCCEEDED, assertIs<MissionOperationOutcome>(replies[2].body).result)
        assertEquals(1, assertIs<MissionOperationOutcome>(replies[3].body).outcome!!["version"].asInt())
        assertEquals(listOf("saveDraft", "validate", "mockRun", "activate"), bench.writes().map { it.op })
        assertEquals(List(3) { OperationResult.SUCCEEDED }, log.list().map { it.result })
        assertEquals(setOf(WM), log.list().map { it.target }.toSet())
    }

    @Test
    fun `활성화 사유가 없거나 비었거나 공백뿐이면 400 REASON_REQUIRED 이고 호스트를 부르지 않는다`() {
        listOf("""{}""", """{"reason":""}""", """{"reason":"   "}""", """{"reason":3}""", "사유").forEach { body ->
            val reply = missions.activate(WM, "3", "engineer", "lee", body.toByteArray())
            assertEquals(400, reply.statusCode.value(), body)
            assertEquals(MissionVersionController.REASON_REQUIRED, reply.rejection().error, body)
        }
        assertEquals(400, missions.activate(WM, "3", "engineer", "lee", null).statusCode.value())
        assertEquals(emptyList(), bench.writes())
        assertEquals(emptyList(), log.list())

        missions.activate(WM, "3", "engineer", "lee", """{"reason":"  랙 도착 대기 도입 "}""".toByteArray())
        assertEquals("랙 도착 대기 도입", bench.writes().single().reason)
        assertEquals("랙 도착 대기 도입", log.list().single().reason)
    }

    @Test
    fun `편집하지 않는 WorkMaster 는 400 UNKNOWN_WORK_MASTER 이고 초안 id 와 정의가 틀리면 400 MISSION_BAD_REQUEST 다`() {
        listOf(
            missions.overview("InspectAsset"),
            missions.templates("DeliverContainer"),
            missions.saveDraft("InspectAsset", "engineer", "lee", """{"definition":"{}"}""".toByteArray()),
            missions.mockRun("InspectAsset", "3", "engineer", "lee"),
        ).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals("UNKNOWN_WORK_MASTER", reply.rejection().error)
        }
        listOf(
            missions.validate(WM, "0", "engineer", "lee"),
            missions.mockRun(WM, "x", "engineer", "lee"),
            missions.activate(WM, "-1", "engineer", "lee", """{"reason":"r"}""".toByteArray()),
            missions.saveDraft(WM, "engineer", "lee", """{"definition":{"schemaVersion":1}}""".toByteArray()),
            missions.saveDraft(WM, "engineer", "lee", null),
        ).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals(MissionVersionController.BAD_REQUEST, reply.rejection().error)
        }
        assertEquals(emptyList(), bench.calls)
        assertEquals(emptyList(), log.list())

        // 초안은 자유롭다. 빈 글자도 저장한다.
        assertEquals(200, missions.saveDraft(WM, "engineer", "lee", """{"definition":""}""".toByteArray()).statusCode.value())
        assertEquals("", bench.writes().single().definition)
    }

    @Test
    fun `읽기 둘은 모드 헤더 없이 되고 호스트 본문을 그대로 넘기며 호스트가 안 닿으면 503 이다`() {
        assertSame((bench.overview as HostCall.Ok).value, missions.overview(WM).body)
        assertSame((bench.templates as HostCall.Ok).value, missions.templates(WM).body)
        bench.overview = HostCall.Silent("응답 없음: ConnectException")
        bench.templates = HostCall.Silent("HTTP 500")
        val down = missions.overview(WM)
        assertEquals(503, down.statusCode.value())
        assertEquals(PreRejection("HOST_SILENT", "실행 호스트가 답하지 않는다: 응답 없음: ConnectException"), down.body)
        assertEquals(503, missions.templates(WM).statusCode.value())
    }

    @Test
    fun `시운전 완료 기체를 모르면 검증과 모의 실행과 활성화가 503 COMMISSIONED_ROBOTS_UNKNOWN 이다`() {
        bench.base.robots = RegistryCall.Silent("응답 없음: ConnectException")
        editAll("engineer").drop(1).forEach { reply ->
            assertEquals(503, reply.statusCode.value())
            assertEquals(MissionOperations.COMMISSIONED_ROBOTS_UNKNOWN, reply.rejection().error)
        }
        assertEquals(listOf("saveDraft"), bench.writes().map { it.op })
        assertEquals(1, log.list().size)
    }

    @Test
    fun `검증의 호스트 4xx 는 같은 상태 코드로, 불통은 503 HOST_SILENT 로 낸다`() {
        bench.validateAnswer = HostWrite.Answered(404, """{"error":"DRAFT_NOT_FOUND","detail":"초안 9 가 없다"}""")
        val missing = missions.validate(WM, "9", "engineer", "lee")
        assertEquals(404, missing.statusCode.value())
        assertEquals(PreRejection("DRAFT_NOT_FOUND", "초안 9 가 없다"), missing.body)
        bench.validateAnswer = HostWrite.NoResponse("응답 없음: ConnectException")
        val down = missions.validate(WM, "9", "engineer", "lee")
        assertEquals(503, down.statusCode.value())
        assertEquals("HOST_SILENT", down.rejection().error)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `신호 조작은 운영자와 엔지니어 두 모드 모두 하고 행위자 헤더가 없으면 400 이다`() {
        listOf("operator" to "kim", "engineer" to "lee").forEach { (mode, user) ->
            val reply = signals.write("rack_present", mode, user, """{"value":"true"}""".toByteArray())
            assertEquals(200, reply.statusCode.value(), mode)
            assertEquals(OperationResult.SUCCEEDED, assertIs<SignalWriteOutcome>(reply.body).result)
        }
        assertEquals(listOf("kim", "lee"), log.list().sortedBy { it.recordedAt }.map { it.user })
        listOf(
            signals.write("rack_present", null, "kim", """{"value":"true"}""".toByteArray()),
            signals.write("rack_present", "operator", null, """{"value":"true"}""".toByteArray()),
        ).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals("ACTOR_REQUIRED", reply.rejection().error)
        }
        assertEquals(2, bench.writes().size)
    }

    @Test
    fun `신호 값이 문자열이 아니면 400 SIGNAL_BAD_REQUEST 이고 호스트를 부르지 않는다`() {
        listOf("""{"value":true}""", """{"value":null}""", """{}""", """["true"]""", "true").forEach { body ->
            val reply = signals.write("rack_present", "operator", "kim", body.toByteArray())
            assertEquals(400, reply.statusCode.value(), body)
            assertEquals(CellSignalController.BAD_REQUEST, reply.rejection().error, body)
        }
        assertEquals(emptyList(), bench.writes())
        assertEquals(emptyList(), log.list())
        // 값이 종류에 맞는지는 현장이 본다. 운영 서비스는 넘긴다.
        bench.signalAnswer = HostWrite.Answered(400, """{"error":"SIGNAL_VALUE_INVALID","detail":"x"}""")
        val reply = signals.write("rack_present", "operator", "kim", """{"value":"TRUE"}""".toByteArray())
        assertEquals(200, reply.statusCode.value())
        assertEquals("SIGNAL_VALUE_INVALID", assertIs<SignalWriteOutcome>(reply.body).rejection!!.error)
    }
}
