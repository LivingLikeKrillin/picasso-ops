package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S2 완료 판정(S2 스펙 §3). 연결 기준 시간을 화면 API 로 바꾸면 코드 수정과 재기동 없이 다음 기체 목록 읽기부터 그 값으로
 * 판정하고, 막힘이 근거 버전을 싣는다. 순서가 있다. 앞 시험의 버전과 기체 상태를 뒤 시험이 이어받는다.
 *
 * 오래됨은 실제 시간이 흘러야 생긴다. registry 는 보고 시각을 실제 시계로 찍고, 이 하네스에는 시간을 미는 반복 작업이
 * 없어 [report] 를 부르지 않으면 보고가 멈춘다. 그래서 (4) 는 60초 남짓 실제로 기다린다. 시험 전용 시계 이음새는 두지 않는다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SiteSettingsTest {

    companion object {
        private const val ROBOT = "humanoid-01"
        private lateinit var stack: E2eStack

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start()
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }
    }

    private fun settings(): JsonNode = stack.get("/api/site-settings")

    private fun list(): JsonNode = stack.get("/api/robots")

    private fun robot(view: JsonNode = list()): JsonNode = view["robots"].single { it["robot"]["robotId"].asText() == ROBOT }

    private fun change(base: Long, seconds: Long, reason: String = "시험", mode: String = "engineer") =
        stack.send(
            "PUT", "/api/site-settings", mode,
            body = """{"baseVersion":$base,"connectionThresholdSeconds":$seconds,"reason":"$reason"}""",
        )

    private fun settingsRows() = stack.get("/api/operations").filter { it["target"].asText() == "site-settings" }

    /** 프로파일의 상태 발행 주기 상한 30초를 넘겨 민다. 상태 발행이 곧 생존 보고다. */
    private fun report() = stack.site.advance(Duration.ofSeconds(31))

    @Test
    @Order(1)
    fun `기동 직후 현장 설정은 버전 1 의 90초다`() {
        val view = settings()
        assertEquals(1, view["current"]["version"].asLong())
        assertEquals(90, view["current"]["connectionThresholdSeconds"].asInt())
        assertEquals(60, view["range"]["minConnectionThresholdSeconds"].asInt())
        assertEquals(3600, view["range"]["maxConnectionThresholdSeconds"].asInt())
        assertEquals(listOf(1L), view["history"].map { it["version"].asLong() })
    }

    @Test
    @Order(2)
    fun `선언하고 첫 보고로 CONFIRMED 가 된 뒤 보고를 멈춘다`() {
        val reply = stack.send("POST", "/api/robots", "engineer", body = """{"robotId":"$ROBOT","serialNumber":"HA-0001"}""")
        assertEquals("SUCCEEDED", reply.body!!["result"].asText())
        report()
        val view = list()
        val robot = robot(view)
        assertEquals("CONFIRMED", robot["robot"]["status"].asText())
        assertEquals("FRESH", robot["connection"].asText())
        assertEquals(1, view["settingsVersion"].asLong())
        assertEquals(90, view["connectionThresholdSeconds"].asLong())
    }

    @Test
    @Order(3)
    fun `엔지니어가 버전 1 위에서 60초로 바꾸면 버전 2 이고 조작 기록에 성공 행이 남는다`() {
        val reply = change(1, 60, "연결 기준 줄임")
        assertEquals(200, reply.status)
        assertEquals("SUCCEEDED", reply.body!!["result"].asText())
        val view = settings()
        assertEquals(2, view["current"]["version"].asLong())
        assertEquals(60, view["current"]["connectionThresholdSeconds"].asInt())
        assertEquals("kim", view["current"]["user"].asText())
        assertEquals("연결 기준 줄임", view["current"]["reason"].asText())
        val row = settingsRows().single()
        assertEquals("SUCCEEDED", row["result"].asText())
        assertEquals("ENGINEER", row["mode"].asText())
    }

    @Test
    @Order(4)
    fun `마지막 보고가 60초보다 오래되면 근거 버전 2 의 오래됨으로 막힌다`() {
        val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        var view = list()
        while (robot(view)["connection"].asText() != "STALE") {
            check(System.nanoTime() < deadline) { "90초 안에 오래됨이 되지 않았다: ${robot(view)}" }
            Thread.sleep(2_000)
            view = list()
        }
        assertEquals(2, view["settingsVersion"].asLong())
        val stale = robot(view)["blockers"].single { it["kind"].asText() == "REPORT_STALE" }
        assertEquals("60초 안의 보고", stale["expected"].asText())
        assertEquals(2, stale["basisVersion"].asLong())
        // registry 상태에서 나온 막힘은 근거 버전이 없다.
        val unbound = robot(view)["blockers"].single { it["kind"].asText() == "UNBOUND" }
        assertTrue(unbound["basisVersion"].isNull)
    }

    @Test
    @Order(5)
    fun `버전 2 위에서 3600초로 바꾸면 버전 3 이고 다음 읽기에서 오래됨이 풀린다`() {
        assertEquals("SUCCEEDED", change(2, 3600, "되돌림").body!!["result"].asText())
        val view = list()
        assertEquals(3, view["settingsVersion"].asLong())
        assertEquals(3600, view["connectionThresholdSeconds"].asLong())
        val robot = robot(view)
        assertEquals("FRESH", robot["connection"].asText())
        assertEquals(listOf("UNBOUND"), robot["blockers"].map { it["kind"].asText() })
    }

    @Test
    @Order(6)
    fun `지난 기준 버전으로 바꾸려 하면 버전 충돌로 거부되고 조작 기록에 거부 행이 남는다`() {
        val reply = change(1, 120, "늦은 변경")
        assertEquals(200, reply.status)
        assertEquals("REJECTED", reply.body!!["result"].asText())
        val rejection = reply.body!!["rejection"]
        assertEquals("SETTINGS_VERSION_CONFLICT", rejection["kind"].asText())
        assertEquals("현재 버전 3", rejection["observed"].asText())
        assertEquals(3, settings()["current"]["version"].asLong())
        assertEquals(listOf("REJECTED", "SUCCEEDED", "SUCCEEDED"), settingsRows().map { it["result"].asText() })
    }

    @Test
    @Order(7)
    fun `범위 밖 값과 운영자 모드는 보내기 전에 막히고 조작 기록에 남지 않는다`() {
        val outOfRange = change(3, 30)
        assertEquals(400, outOfRange.status)
        assertEquals("SETTING_OUT_OF_RANGE", outOfRange.body!!["error"].asText())
        val noReason = change(3, 120, reason = " ")
        assertEquals(400, noReason.status)
        assertEquals("REASON_REQUIRED", noReason.body!!["error"].asText())
        val unreadable = stack.send("PUT", "/api/site-settings", "engineer", body = "60초로")
        assertEquals(400, unreadable.status)
        assertEquals("SETTINGS_BAD_REQUEST", unreadable.body!!["error"].asText())
        val operator = change(3, 120, mode = "operator")
        assertEquals(403, operator.status)
        assertEquals("MODE_NOT_ALLOWED", operator.body!!["error"].asText())
        assertEquals(3, settings()["current"]["version"].asLong())
        assertEquals(3, settingsRows().size)
    }
}
