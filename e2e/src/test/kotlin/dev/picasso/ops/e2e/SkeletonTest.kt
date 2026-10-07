package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.OpsApplication
import dev.picasso.ops.site.DbConfig
import dev.picasso.ops.site.RobotRoster
import dev.picasso.ops.site.Site
import dev.picasso.ops.site.SiteConfig
import dev.picasso.registry.PostgresSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * S1a 완료 판정(스펙 §3). 전체를 한 JVM 에 띄우고 운영 서비스의 기체 목록이 빈 목록을 돌려준다.
 * registry 를 멈추면 화면 전체 상태가 «모름» 이 된다. 순서가 있다: 마지막 시험이 registry 를 멈춘다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkeletonTest {

    companion object {
        private const val OPERATOR_TOKEN = "e2e-operator"
        private val root: Path = Path.of("..").toAbsolutePath().normalize()
        private val siteId: String = checkNotNull(System.getenv("SITE_ID")) { "SITE_ID 가 없다(루트 .env)" }
        private val http = HttpClient.newHttpClient()
        private val json = ObjectMapper()

        private lateinit var site: Site
        private lateinit var ops: ConfigurableApplicationContext
        private lateinit var opsUrl: String

        @BeforeAll
        @JvmStatic
        fun up() {
            PostgresSupport.reset()
            PostgresSupport.execute("DROP SCHEMA IF EXISTS ops CASCADE")
            val db = DbConfig(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
            site = Site.start(
                SiteConfig(
                    root = root,
                    siteId = siteId,
                    db = db,
                    registryPort = 0,
                    operatorToken = OPERATOR_TOKEN,
                    ingestToken = "e2e-ingest",
                    roster = RobotRoster.read(root.resolve(SiteConfig.ROSTER)),
                ),
            )
            ops = OpsApplication.builder().run(
                "--server.port=0",
                "--spring.datasource.url=${db.url}",
                "--spring.datasource.username=${db.user}",
                "--spring.datasource.password=${db.password}",
                "--ops.registry.url=${site.registryUrl}",
                "--ops.registry.operator-token=$OPERATOR_TOKEN",
                "--ops.site-id=$siteId",
            )
            opsUrl = "http://127.0.0.1:${(ops as WebServerApplicationContext).webServer.port}"
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::ops.isInitialized) ops.close()
            if (::site.isInitialized) site.close()
        }
    }

    private fun get(url: String): JsonNode {
        val response = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body())
    }

    /** registry REST 를 직접 부른다. 운영 서비스의 선언 API 는 S1b 의 몫이다. */
    private fun declareDirectly(robotId: String) {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("${site.registryUrl}/operations/robots"))
                .header("Authorization", "Bearer $OPERATOR_TOKEN")
                .header("X-Actor", "engineer/e2e")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"robot_id":"$robotId","site":"$siteId","serial_number":"E2E-0001"}""",
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(201, response.statusCode(), response.body())
    }

    @Test
    @Order(1)
    fun `운영 서비스의 기체 목록 조회가 빈 목록을 돌려준다`() {
        // 선언 전 mimic 의 보고는 registry 가 거절하고 남기지 않는다(스펙 §6). 보고가 흘러도 목록은 비어 있다.
        repeat(2) { site.advance(Duration.ofSeconds(31)) }
        val view = get("$opsUrl/api/robots")
        assertEquals("OK", view["registry"].asText())
        assertEquals(0, view["robots"].size(), view.toString())
        assertFalse(view["robots"].isNull)
        assertFalse(view["robotsAsOf"].isNull)
    }

    @Test
    @Order(2)
    fun `ops 스키마는 운영 서비스가 올리고 조작 기록은 비어 있다`() {
        val versions = PostgresSupport.queryAll(
            "SELECT version FROM ops.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
        ) { it.getString(1) }
        assertEquals(listOf("1"), versions)
        assertEquals(0, get("$opsUrl/api/operations").size())
    }

    @Test
    @Order(3)
    fun `registry 를 멈추면 전체 상태가 모름이고 직전 목록을 지우지 않는다`() {
        declareDirectly("e2e-held-01")
        val before = get("$opsUrl/api/robots")
        assertEquals(listOf("e2e-held-01"), before["robots"].map { it["robotId"].asText() })

        site.stopRegistry()

        val after = get("$opsUrl/api/robots")
        assertEquals("REGISTRY_SILENT", after["registry"].asText())
        assertEquals(listOf("e2e-held-01"), after["robots"].map { it["robotId"].asText() })
        assertEquals(before["robotsAsOf"].asText(), after["robotsAsOf"].asText())
    }
}
