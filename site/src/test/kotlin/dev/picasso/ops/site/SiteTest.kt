package dev.picasso.ops.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.registry.PostgresSupport
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 런처가 registry 와 mimic 을 띄우고, 시간을 밀면 생존 보고가 registry 에 닿는다(스펙 §6). */
class SiteTest {

    private val root = Path.of("..").toAbsolutePath().normalize()
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    private fun config() = SiteConfig(
        root = root,
        siteId = "site-test",
        db = DbConfig(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password),
        registryPort = 0,
        operatorToken = "op-test",
        ingestToken = "in-test",
        roster = RobotRoster.read(root.resolve(SiteConfig.ROSTER)),
    )

    private fun robots(site: Site): JsonNode {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("${site.registryUrl}/diag/robots?retired=true&site=site-test")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body())
    }

    private fun declare(site: Site, robotId: String, serial: String) {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("${site.registryUrl}/operations/robots"))
                .header("Authorization", "Bearer op-test")
                .header("X-Actor", "engineer/site-test")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"robot_id":"$robotId","site":"site-test","serial_number":"$serial"}""",
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(201, response.statusCode(), response.body())
    }

    @Test
    fun `선언한 기체는 시간을 밀면 생존 보고로 CONFIRMED 가 된다`() {
        PostgresSupport.reset()
        Site.start(config()).use { site ->
            assertEquals(setOf("humanoid-01", "quadruped-01"), site.robotIds)
            assertEquals(0, robots(site).size())

            declare(site, "humanoid-01", "HA-0001")
            assertEquals("CLAIMED", robots(site).single()["status"].asText())

            // 프로파일의 publish_interval.max_seconds 30 을 넘겨 민다.
            site.advance(Duration.ofSeconds(31))
            val robot = robots(site).single()
            assertEquals("CONFIRMED", robot["status"].asText())
            assertEquals(false, robot["lastReportedAt"].isNull)
        }
    }

    @Test
    fun `registry 만 멈추면 그 주소가 답하지 않는다`() {
        PostgresSupport.reset()
        Site.start(config()).use { site ->
            site.stopRegistry()
            assertFailsWith<ConnectException> {
                http.send(
                    HttpRequest.newBuilder(URI.create("${site.registryUrl}/diag/robots")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }
        }
    }
}
