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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun `기동 직후 가상 시각이 실제 시각까지 와 있고 advanceTo 는 앞으로만 민다`() {
        PostgresSupport.reset()
        val before = Instant.now()
        Site.start(config()).use { site ->
            val after = Instant.now()
            val started = site.now()
            assertTrue(!started.isBefore(before) && !started.isAfter(after), "기동 직후 가상 시각 $started 가 [$before, $after] 밖이다")

            site.advanceTo(started.minusSeconds(60))
            assertEquals(started, site.now(), "앞선 시각으로는 되감지 않는다")
            site.advanceTo(started)
            assertEquals(started, site.now())
            site.advanceTo(started.plusSeconds(5))
            assertEquals(started.plusSeconds(5), site.now())
        }
    }

    @Test
    fun `mimic 과 셀 대역이 포트를 열고 close 가 셀 대역도 닫는다`() {
        PostgresSupport.reset()
        val site = Site.start(config())
        val cell = URI.create("http://127.0.0.1:${site.cellPort}/cell")
        site.use {
            assertTrue(site.mimicPort > 0)
            val got = http.send(HttpRequest.newBuilder(cell).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, got.statusCode(), got.body())
            assertEquals(CellFixture.STANDARD.slots, json.readTree(got.body())["slots"].map { it["id"].asText() })
        }
        assertFailsWith<ConnectException> { http.send(HttpRequest.newBuilder(cell).build(), HttpResponse.BodyHandlers.ofString()) }
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
