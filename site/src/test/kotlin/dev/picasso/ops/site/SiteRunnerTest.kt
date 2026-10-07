package dev.picasso.ops.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.registry.PostgresSupport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 런처가 개정판 시험 실행기를 함께 띄우고, 현장의 명칭 티칭이 기체의 보고로 registry 에 닿는다(P2·S1d 스펙 §7).
 *
 * 실행기는 실제 시간 1초마다 폴링하므로 이 시험은 «TESTED 가 될 때까지» 를 실제 시간으로 기다린다(상한 60초).
 */
class SiteRunnerTest {

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

    private fun call(site: Site, method: String, path: String, body: String? = null): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(site.registryUrl + path))
                .header("Authorization", "Bearer op-test")
                .header("X-Actor", "engineer/site-test")
                .header("Content-Type", "application/json")
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun revision(site: Site, id: Long): JsonNode =
        json.readTree(call(site, "GET", "/operations/profile-revisions").body()).single { it["profile_revision_id"].asLong() == id }

    @Test
    fun `제출하고 시험을 요청하면 현장의 실행기가 집어 TESTED 로 올린다`() {
        PostgresSupport.reset()
        Site.start(config()).use { site ->
            val submitted = call(site, "POST", "/operations/profile-revisions", Files.readString(root.resolve("picasso/profile/profiles/humanoid-a.json")))
            assertEquals(201, submitted.statusCode(), submitted.body())
            val id = json.readTree(submitted.body())["profile_revision_id"].asLong()
            assertEquals(201, call(site, "POST", "/operations/profile-revisions/$id/test-requests").statusCode())

            val deadline = Instant.now().plusSeconds(60)
            while (revision(site, id)["status"].asText() != "TESTED" && Instant.now().isBefore(deadline)) Thread.sleep(500)

            val row = revision(site, id)
            assertEquals("TESTED", row["status"].asText(), row.toString())
            listOf("CONTRACT", "NEGATIVE", "DETERMINISM").forEach { suite ->
                assertEquals("PASS", row["suites"][suite]["result"].asText(), suite)
                assertEquals(Site.RUNNER_NAME, row["suites"][suite]["ran_by"].asText(), suite)
            }
        }
    }

    @Test
    fun `명부의 명칭이 기체 보고로 닿고 다시 티칭하면 다음 보고부터 바뀐다`() {
        PostgresSupport.reset()
        Site.start(config()).use { site ->
            val declared = call(
                site, "POST", "/operations/robots",
                """{"robot_id":"humanoid-01","site":"site-test","serial_number":"HA-0001"}""",
            )
            assertEquals(201, declared.statusCode(), declared.body())
            fun count(): Int = PostgresSupport.queryOne(
                "SELECT site_names_count FROM robot_liveness WHERE robot_id = 'humanoid-01'",
            ) { it.getInt(1) }

            site.advance(Duration.ofSeconds(31))
            assertEquals(2, count())

            site.teach("humanoid-01", listOf("dock-3", "bay-7", "rack-1"))
            site.advance(Duration.ofSeconds(31))
            assertEquals(3, count())
        }
    }
}
