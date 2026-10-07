package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.registry.PostgresSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * S1b 완료 판정(스펙 §3). 운영 서비스 API 로 선언(`CLAIMED`) → 보고(`CONFIRMED`) → 퇴역 → 퇴역 뒤 보고 감지 →
 * 복귀 → 조작 기록 확인. 순서가 있다. 앞 시험의 기체 상태를 뒤 시험이 이어받는다.
 *
 * 기체는 `site/robots.json` 의 `humanoid-01` 이다. mimic 이 떠 있으므로 시간을 밀면 보고가 나간다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LifecycleTest {

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

    private fun robot(): JsonNode = stack.get("/api/robots")["robots"].single { it["robot"]["robotId"].asText() == ROBOT }

    /** S1d 부터 바인딩 안 된 기체에는 `UNBOUND` 가 붙는다(P2·S1d 스펙 §8.5). 이 시험은 바인딩을 하지 않으므로 늘 그것이 있다. */
    private fun blockers(robot: JsonNode) = robot["blockers"].map { it["kind"].asText() }

    /** 프로파일의 상태 발행 주기 상한 30초를 넘겨 민다. 상태 발행이 곧 생존 보고다. */
    private fun report() = stack.site.advance(Duration.ofSeconds(31))

    @Test
    @Order(1)
    fun `엔지니어가 선언하면 CLAIMED 이고 첫 보고를 기다린다`() {
        val reply = stack.send("POST", "/api/robots", "engineer", body = """{"robotId":"$ROBOT","serialNumber":"HA-0001"}""")
        assertEquals(200, reply.status)
        assertEquals("SUCCEEDED", reply.body!!["result"].asText())
        val robot = robot()
        assertEquals("CLAIMED", robot["robot"]["status"].asText())
        assertEquals("NO_REPORT", robot["connection"].asText())
        assertEquals(listOf("AWAITING_FIRST_REPORT", "UNBOUND"), blockers(robot))
    }

    @Test
    @Order(2)
    fun `보고가 오면 CONFIRMED 이고 바인딩 말고는 막힘이 없다`() {
        report()
        val robot = robot()
        assertEquals("CONFIRMED", robot["robot"]["status"].asText())
        assertEquals("FRESH", robot["connection"].asText())
        assertEquals(listOf("UNBOUND"), blockers(robot))
    }

    @Test
    @Order(3)
    fun `운영자가 사유와 함께 퇴역시키면 RETIRED 이다`() {
        val reply = stack.send("POST", "/api/robots/$ROBOT/retirement", "operator", body = """{"reason":"정비"}""")
        assertEquals("SUCCEEDED", reply.body!!["result"].asText())
        assertEquals("RETIRED", robot()["robot"]["status"].asText())
    }

    @Test
    @Order(4)
    fun `퇴역 뒤에도 보고가 오면 운영자가 풀 막힘으로 보인다`() {
        report()
        val robot = robot()
        assertEquals(true, robot["robot"]["reportingAfterRetirement"].asBoolean())
        val blocker = robot["blockers"].single()
        assertEquals("REPORTING_AFTER_RETIREMENT", blocker["kind"].asText())
        assertEquals("OPERATOR", blocker["owner"].asText())
        assertEquals(true, blocker["inScreen"].asBoolean())
    }

    @Test
    @Order(5)
    fun `퇴역한 기체를 다시 선언하면 복귀하라는 거절이다`() {
        val reply = stack.send("POST", "/api/robots", "engineer", body = """{"robotId":"$ROBOT","serialNumber":"HA-0001"}""")
        assertEquals("REJECTED", reply.body!!["result"].asText())
        assertEquals("RETIRED_ALREADY", reply.body["rejection"]["kind"].asText())
        assertEquals("OPERATOR", reply.body["rejection"]["owner"].asText())
    }

    @Test
    @Order(6)
    fun `복귀하면 퇴역이 풀리고 바인딩 말고는 막힘이 사라진다`() {
        val reply = stack.send("DELETE", "/api/robots/$ROBOT/retirement", "operator")
        assertEquals("SUCCEEDED", reply.body!!["result"].asText())
        val robot = robot()
        assertEquals("CONFIRMED", robot["robot"]["status"].asText())
        assertEquals(listOf("UNBOUND"), blockers(robot))
    }

    @Test
    @Order(7)
    fun `조작 기록에 4건이 대상과 행위자와 사유와 함께 남고 registry 도 같은 행위자를 적었다`() {
        val records = stack.get("/api/operations").map {
            listOf(it["target"].asText(), it["mode"].asText(), it["user"].asText(), it["result"].asText(), it["reason"].asText(""))
        }
        assertEquals(
            listOf(
                listOf("robot $ROBOT", "OPERATOR", "kim", "SUCCEEDED", ""),
                listOf("robot $ROBOT", "ENGINEER", "kim", "REJECTED", ""),
                listOf("robot $ROBOT", "OPERATOR", "kim", "SUCCEEDED", "정비"),
                listOf("robot $ROBOT", "ENGINEER", "kim", "SUCCEEDED", ""),
            ),
            records,
        )
        // registry 감사 기록의 행위자가 운영 서비스의 X-Actor 와 같다(스펙 §7.1). 거절된 재선언은 감사 기록에 없다.
        assertEquals("engineer/kim", robot()["robot"]["registeredBy"].asText())
        val audit = PostgresSupport.queryAll(
            "SELECT operation, actor FROM audit_log WHERE subject = '$ROBOT' ORDER BY audit_id",
        ) { it.getString(1) to it.getString(2) }
        assertEquals(
            listOf("ROBOT_DECLARED" to "engineer/kim", "ROBOT_RETIRED" to "operator/kim", "ROBOT_REINSTATED" to "operator/kim"),
            audit,
        )
    }

    @Test
    @Order(8)
    fun `registry 에 보내기 전에 막는 요청은 조작 기록에 남지 않는다`() {
        val before = stack.get("/api/operations").size()
        val body = """{"robotId":"pre-01","serialNumber":"PRE-0001"}"""

        assertEquals(400, stack.send("POST", "/api/robots", mode = null, body = body).status)
        assertEquals(403, stack.send("POST", "/api/robots", "operator", body = body).status)
        val mismatch = stack.send("POST", "/api/robots", "engineer", body = """{"robotId":"pre-01","serialNumber":"PRE-0001","site":"other-site"}""")
        assertEquals(400 to "SITE_MISMATCH", mismatch.status to mismatch.body!!["error"].asText())
        assertEquals(415, stack.send("POST", "/api/robots", "engineer", body = body, contentType = "text/plain").status)

        // 퇴역·복귀도 같은 관문을 지난다. 복귀는 본문이 없어 헤더와 모드만 막는다.
        val retire = """{"reason":"정비"}"""
        assertEquals(400, stack.send("DELETE", "/api/robots/$ROBOT/retirement", mode = null).status)
        assertEquals(403, stack.send("DELETE", "/api/robots/$ROBOT/retirement", "engineer").status)
        assertEquals(415, stack.send("POST", "/api/robots/$ROBOT/retirement", "operator", body = retire, contentType = "text/plain").status)
        assertEquals(403, stack.send("POST", "/api/robots/$ROBOT/retirement", "engineer", body = retire).status)

        assertEquals(before, stack.get("/api/operations").size())
        assertFalse(stack.get("/api/robots")["robots"].any { it["robot"]["robotId"].asText() == "pre-01" })
    }

    @Test
    @Order(9)
    fun `모르는 기체의 퇴역은 registry 의 404 를 대응표로 옮긴다`() {
        val reply = stack.send("POST", "/api/robots/no-such-robot/retirement", "operator", body = """{"reason":"정비"}""")
        assertEquals("REJECTED", reply.body!!["result"].asText())
        assertEquals("UNKNOWN_ROBOT", reply.body["rejection"]["kind"].asText())
    }

    @Test
    @Order(10)
    fun `운영자 토큰이 틀린 운영 서비스는 목록을 읽어도 화면 전체 상태가 토큰 불일치이고 조작도 401 이다`() {
        val (context, url) = stack.opsWithToken("wrong-token")
        context.use {
            val view = E2eStack.read("$url/api/robots")
            assertEquals("REGISTRY_UNAUTHORIZED", view["registry"].asText())
            // 목록 자체는 관문 밖에서 새로 읽었다. 직전 값이 아니다.
            assertEquals(view["checkedAt"].asText(), view["robotsAsOf"].asText())

            val reply = E2eStack.send(url, "POST", "/api/robots", "engineer", body = """{"robotId":"tok-01","serialNumber":"TOK-0001"}""")
            assertEquals("REJECTED", reply.body!!["result"].asText())
            assertEquals(true, reply.body["unauthorized"].asBoolean())
            assertFalse(stack.get("/api/robots")["robots"].any { it["robot"]["robotId"].asText() == "tok-01" })
        }
    }
}
