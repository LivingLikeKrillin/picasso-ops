package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.OperationOutcome
import dev.picasso.ops.service.settings.SiteSettingsFields
import dev.picasso.ops.service.settings.SiteSettingsOperations
import dev.picasso.ops.service.settings.SiteSettingsStore
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.web.PreRejection
import dev.picasso.ops.service.web.SiteSettingsController
import dev.picasso.registry.PostgresSupport
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 현장 설정 API 의 본문 읽기와 사전 거부, 읽기의 범위와 호스트 반영(S3c 스펙 §6.2, §8, §9). 컨트롤러를 스프링 없이 바로 부른다.
 * 관문과 본문 읽기, 상태 코드의 고름이 컨트롤러 안에 있기 때문이다.
 */
class SiteSettingsControllerTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val jdbc = JdbcClient.create(dataSource)
    private val store = SiteSettingsStore(jdbc)
    private val log = OperationLog(jdbc)
    private val json = ObjectMapper()

    /** 호스트 `GET /host/site-timings` 의 대역. 시험이 바꾼다. */
    private var hostReply: HostCall<JsonNode> = HostCall.Silent("응답 없음: ConnectException")

    private val controller = SiteSettingsController(
        store,
        SiteSettingsOperations(store, log, TransactionTemplate(DataSourceTransactionManager(dataSource)), Clock.systemUTC()),
    ) { hostReply }

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    private fun put(body: String, mode: String = "engineer"): ResponseEntity<Any> = controller.change(mode, "lee", body.toByteArray())

    private fun ResponseEntity<Any>.rejection(): PreRejection = assertIs<PreRejection>(body)

    @Test
    fun `PUT 에서 빠진 칸과 null 칸은 기준 버전의 값이다`() {
        assertEquals(
            OperationResult.SUCCEEDED,
            assertIs<OperationOutcome>(put("""{"baseVersion":1,"stallWindowSeconds":600,"reason":"정체 늦춤"}""").body).result,
        )
        assertEquals(SiteSettingsFields(90, 30, 15, 60, 600), store.latest().fields())
        put("""{"baseVersion":2,"connectionThresholdSeconds":null,"evidenceAfterSeconds":20,"inDoubtGraceSeconds":90,"reason":"뒤 폭"}""")
        assertEquals(SiteSettingsFields(90, 30, 20, 90, 600), store.latest().fields())
        // 화면처럼 다섯을 다 보내면 그 값 그대로다.
        put(
            """{"baseVersion":3,"connectionThresholdSeconds":120,"evidenceBeforeSeconds":40,"evidenceAfterSeconds":25,""" +
                """"inDoubtGraceSeconds":120,"stallWindowSeconds":900,"reason":"전부"}""",
        )
        assertEquals(SiteSettingsFields(120, 40, 25, 120, 900), store.latest().fields())
        assertEquals(listOf(4L, 3L, 2L, 1L), store.history().map { it.version })
    }

    @Test
    fun `범위 밖 칸은 400 SETTING_OUT_OF_RANGE 이고 메시지가 칸 이름과 범위를 싣는다`() {
        val one = put("""{"baseVersion":1,"inDoubtGraceSeconds":9,"reason":"짧게"}""")
        assertEquals(400, one.statusCode.value())
        assertEquals("SETTING_OUT_OF_RANGE", one.rejection().error)
        assertEquals("inDoubtGraceSeconds 9초는 범위 밖이다. 10~600초여야 한다", one.rejection().detail)

        // 여럿이면 칸 순서대로 다 싣는다. 범위 안 칸이 섞여도 하나도 넣지 않는다.
        val two = put("""{"baseVersion":1,"connectionThresholdSeconds":120,"evidenceBeforeSeconds":121,"stallWindowSeconds":29,"reason":"둘"}""")
        assertEquals(
            "evidenceBeforeSeconds 121초는 범위 밖이다. 5~120초여야 한다; stallWindowSeconds 29초는 범위 밖이다. 30~3600초여야 한다",
            two.rejection().detail,
        )
        // Int 를 넘는 정수도 정수이므로 범위 밖이다.
        assertEquals("SETTING_OUT_OF_RANGE", put("""{"baseVersion":1,"evidenceAfterSeconds":9999999999,"reason":"큼"}""").rejection().error)
        assertTrue("connectionThresholdSeconds 59초" in put("""{"baseVersion":1,"connectionThresholdSeconds":59,"reason":"S2"}""").rejection().detail)
        assertEquals(listOf(1L), store.history().map { it.version })
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `값 칸이 다 빠지거나 정수가 아니거나 기준 버전이 없으면 400 SETTINGS_BAD_REQUEST 다`() {
        listOf(
            """{"baseVersion":1,"reason":"값 없음"}""",
            """{"baseVersion":1,"connectionThresholdSeconds":null,"stallWindowSeconds":null,"reason":"다 null"}""",
            """{"baseVersion":1,"stallWindowSeconds":"600","reason":"글자"}""",
            """{"baseVersion":1,"evidenceBeforeSeconds":30.5,"reason":"소수"}""",
            """{"stallWindowSeconds":600,"reason":"기준 없음"}""",
            """[1]""",
            "60초로",
        ).forEach { body ->
            val reply = put(body)
            assertEquals(400, reply.statusCode.value(), body)
            assertEquals("SETTINGS_BAD_REQUEST", reply.rejection().error, body)
        }
        assertEquals("stallWindowSeconds 가 정수가 아니다", put("""{"baseVersion":1,"stallWindowSeconds":"600","reason":"글자"}""").rejection().detail)
        // 못 읽는 본문이 사유 빈칸보다 먼저다. 범위 밖이 사유 빈칸보다 먼저다(S2 순서).
        assertEquals("SETTINGS_BAD_REQUEST", put("""{"baseVersion":1,"reason":" "}""").rejection().error)
        assertEquals("SETTING_OUT_OF_RANGE", put("""{"baseVersion":1,"stallWindowSeconds":1,"reason":" "}""").rejection().error)
        assertEquals("REASON_REQUIRED", put("""{"baseVersion":1,"stallWindowSeconds":600,"reason":" "}""").rejection().error)
        assertEquals(403, put("""{"baseVersion":1,"stallWindowSeconds":600,"reason":"운영자"}""", mode = "operator").statusCode.value())
        assertEquals(listOf(1L), store.history().map { it.version })
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `읽기는 시간값 넷의 범위와 호스트 적용 상태를 싣고 호스트가 닿지 않으면 그 칸만 null 이다`() {
        val unknown = controller.read()
        assertNull(unknown.hostTimings)
        assertEquals(1L, unknown.current.version)
        assertEquals(SiteSettingsFields(90, 30, 15, 60, 300), unknown.current.fields())
        val range = json.valueToTree<JsonNode>(unknown.range)
        assertEquals(
            json.readTree(
                """{"minConnectionThresholdSeconds":60,"maxConnectionThresholdSeconds":3600,""" +
                    """"minEvidenceBeforeSeconds":5,"maxEvidenceBeforeSeconds":120,"minEvidenceAfterSeconds":5,"maxEvidenceAfterSeconds":120,""" +
                    """"minInDoubtGraceSeconds":10,"maxInDoubtGraceSeconds":600,"minStallWindowSeconds":30,"maxStallWindowSeconds":3600}""",
            ),
            range,
        )

        val applied = json.readTree("""{"applied":{"version":1,"evidenceBeforeSeconds":30},"readError":null}""")
        hostReply = HostCall.Ok(applied)
        assertEquals(applied, controller.read().hostTimings)
    }
}
