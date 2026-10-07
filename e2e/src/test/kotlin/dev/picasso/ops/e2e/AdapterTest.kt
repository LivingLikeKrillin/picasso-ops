package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.registry.PostgresSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S1c 완료 판정(스펙 §3). 운영 서비스 API 로 제품 선언 → 빌드 선언 → 인스턴스 등록 → 목록에 `UNTESTED` 표시,
 * 그리고 P1·인스턴스 거절(400/404/409)이 대응표(스펙 §7.4)대로 보이는 것. 순서가 있다. 앞 시험이 만든 제품과
 * 빌드를 뒤 시험이 쓴다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AdapterTest {

    companion object {
        private const val INSTANCE = "fleet-gw-01"
        private lateinit var stack: E2eStack
        private var adapterId = 0L
        private var buildId = 0L

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

    private fun engineer(path: String, body: String) = stack.send("POST", path, "engineer", body = body)

    private fun outcome(path: String, body: String): JsonNode {
        val reply = engineer(path, body)
        assertEquals(200, reply.status, "$path ${reply.body}")
        return reply.body!!
    }

    private fun rejection(path: String, body: String, status: Int): JsonNode {
        val outcome = outcome(path, body)
        assertEquals("REJECTED" to status, outcome["result"].asText() to outcome["registryStatus"].asInt(), "$outcome")
        return outcome["rejection"]
    }

    private fun adapters(): JsonNode = stack.get("/api/adapters")

    @Test
    @Order(1)
    fun `엔지니어가 제품을 선언하면 목록에 보이고 다시 선언해도 같은 제품이다`() {
        val first = outcome("/api/adapters", """{"vendor":"acme","name":"fleet"}""")
        assertEquals("SUCCEEDED" to 201, first["result"].asText() to first["registryStatus"].asInt())
        val again = outcome("/api/adapters", """{"vendor":"acme","name":"fleet"}""")
        assertEquals("SUCCEEDED" to 200, again["result"].asText() to again["registryStatus"].asInt())

        val listed = adapters()["adapters"].single()
        assertEquals("acme/fleet", "${listed["vendor"].asText()}/${listed["name"].asText()}")
        adapterId = listed["adapterId"].asLong()
    }

    @Test
    @Order(2)
    fun `빌드를 선언하면 UNTESTED 로 보인다`() {
        val declared = outcome("/api/adapters/$adapterId/versions", """{"version":"1.0.0","contractSemver":"0.9.0"}""")
        assertEquals("SUCCEEDED" to 201, declared["result"].asText() to declared["registryStatus"].asInt())

        val build = adapters()["adapters"].single()["versions"].single()
        assertEquals(listOf("1.0.0", "0.9.0", "UNTESTED"), listOf("version", "contractSemver", "conformance").map { build[it].asText() })
        assertEquals("engineer/kim", build["registeredBy"].asText())
        buildId = build["adapterVersionId"].asLong()
    }

    @Test
    @Order(3)
    fun `인스턴스를 등록하면 이 사이트의 인스턴스 목록에 UNTESTED 로 보인다`() {
        val registered = outcome("/api/adapter-instances", """{"instanceId":"$INSTANCE","adapterVersionId":$buildId}""")
        assertEquals("SUCCEEDED" to 201, registered["result"].asText() to registered["registryStatus"].asInt())

        val view = adapters()
        assertEquals("OK", view["registry"].asText())
        val instance = view["instances"].single()
        assertEquals(
            listOf(INSTANCE, stack.siteId, "acme/fleet", "1.0.0", "UNTESTED"),
            listOf("instanceId", "siteId", "adapter", "version", "conformance").map { instance[it].asText() },
        )
    }

    @Test
    @Order(4)
    fun `P1 과 인스턴스의 거절은 대응표대로 엔지니어가 화면 안에서 풀 종류로 보인다`() {
        val blank = rejection("/api/adapters", """{"vendor":"","name":"fleet"}""", 400)
        val semver = rejection("/api/adapters/$adapterId/versions", """{"version":"1.1.0","contractSemver":"not-semver"}""", 400)
        val unknown = rejection("/api/adapters/999999/versions", """{"version":"1.0.0","contractSemver":"0.9.0"}""", 404)
        val conflict = rejection("/api/adapters/$adapterId/versions", """{"version":"1.0.0","contractSemver":"1.0.0"}""", 409)
        val instance = rejection("/api/adapter-instances", """{"instanceId":"bad-01","adapterVersionId":999999}""", 400)

        assertEquals(
            listOf("ADAPTER_BAD_REQUEST", "ADAPTER_BAD_REQUEST", "UNKNOWN_ADAPTER", "VERSION_CONFLICT", "INSTANCE_BAD_REQUEST"),
            listOf(blank, semver, unknown, conflict, instance).map { it["kind"].asText() },
        )
        listOf(blank, semver, unknown, conflict, instance).forEach {
            assertEquals("ENGINEER" to true, it["owner"].asText() to it["inScreen"].asBoolean(), "$it")
        }
        // 409 는 이미 있는 계약값을 관측값에 싣는다. 다른 버전 번호로 다시 하라는 근거다.
        assertTrue(conflict["observed"].asText().contains("existing_contract_semver=0.9.0"), "$conflict")
        assertEquals(1, adapters()["instances"].size())
    }

    @Test
    @Order(5)
    fun `registry 에 보내기 전에 막는 요청은 조작 기록에 남지 않는다`() {
        val before = stack.get("/api/operations").size()
        val instance = """{"instanceId":"pre-01","adapterVersionId":$buildId}"""

        assertEquals(403, stack.send("POST", "/api/adapters", "operator", body = """{"vendor":"acme","name":"pre"}""").status)
        assertEquals(403, stack.send("POST", "/api/adapters/$adapterId/versions", "operator", body = """{"version":"9.0.0","contractSemver":"0.9.0"}""").status)
        assertEquals(403, stack.send("POST", "/api/adapter-instances", "operator", body = instance).status)
        assertEquals(400, stack.send("POST", "/api/adapter-instances", mode = null, body = instance).status)
        val mismatch = engineer("/api/adapter-instances", """{"instanceId":"pre-01","adapterVersionId":$buildId,"site":"other-site"}""")
        assertEquals(400 to "SITE_MISMATCH", mismatch.status to mismatch.body!!["error"].asText())
        // 빌드 id 가 없는 본문. Jackson 이 0 으로 읽어 registry 에 보내지 않게 운영 서비스가 막는다.
        val noBuild = engineer("/api/adapter-instances", """{"instanceId":"pre-01"}""")
        assertEquals(400 to "BUILD_REQUIRED", noBuild.status to noBuild.body!!["error"].asText())
        assertEquals(415, stack.send("POST", "/api/adapters", "engineer", body = """{"vendor":"acme","name":"pre"}""", contentType = "text/plain").status)

        assertEquals(before, stack.get("/api/operations").size())
        assertEquals(listOf(INSTANCE), adapters()["instances"].map { it["instanceId"].asText() })
    }

    @Test
    @Order(6)
    fun `조작 기록에 어댑터 조작이 대상과 결과와 함께 남고 registry 도 같은 행위자를 적었다`() {
        val records = stack.get("/api/operations").map { listOf(it["target"].asText(), it["mode"].asText(), it["result"].asText()) }
        assertEquals(
            listOf(
                listOf("adapter acme/fleet", "ENGINEER", "SUCCEEDED"),
                listOf("adapter acme/fleet", "ENGINEER", "SUCCEEDED"),
                listOf("build $adapterId@1.0.0", "ENGINEER", "SUCCEEDED"),
                listOf("instance $INSTANCE", "ENGINEER", "SUCCEEDED"),
                listOf("adapter /fleet", "ENGINEER", "REJECTED"),
                listOf("build $adapterId@1.1.0", "ENGINEER", "REJECTED"),
                listOf("build 999999@1.0.0", "ENGINEER", "REJECTED"),
                listOf("build $adapterId@1.0.0", "ENGINEER", "REJECTED"),
                listOf("instance bad-01", "ENGINEER", "REJECTED"),
            ),
            records.reversed(),
        )
        // 같은 제품의 두 번째 선언은 registry 가 감사 기록을 더하지 않는다. 거절도 감사 기록에 없다.
        val audit = PostgresSupport.queryAll(
            "SELECT operation, subject, actor FROM audit_log WHERE operation LIKE 'ADAPTER%' ORDER BY audit_id",
        ) { listOf(it.getString(1), it.getString(2), it.getString(3)) }
        assertEquals(
            listOf(
                listOf("ADAPTER_REGISTER", "acme/fleet", "engineer/kim"),
                listOf("ADAPTER_VERSION_REGISTER", "$adapterId@1.0.0", "engineer/kim"),
                listOf("ADAPTER_INSTANCE_REGISTERED", INSTANCE, "engineer/kim"),
            ),
            audit,
        )
    }

    @Test
    @Order(7)
    fun `운영자 토큰이 틀린 운영 서비스는 어댑터 목록을 모름과 토큰 불일치로 보인다`() {
        val (context, url) = stack.opsWithToken("wrong-token")
        context.use {
            val view = E2eStack.read("$url/api/adapters")
            assertEquals("REGISTRY_UNAUTHORIZED", view["registry"].asText())
            // 둘 다 읽혀야 새 값으로 바꾸므로 관문 밖의 인스턴스 목록도 모름이다.
            assertTrue(view["adapters"].isNull && view["instances"].isNull, "$view")
        }
    }
}
