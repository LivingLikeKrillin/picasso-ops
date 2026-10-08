package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.host.HostClock
import dev.picasso.ops.host.MissionHostApplication
import dev.picasso.ops.service.OpsApplication
import dev.picasso.ops.site.DbConfig
import dev.picasso.ops.site.RobotRoster
import dev.picasso.ops.site.Site
import dev.picasso.ops.site.SiteConfig
import dev.picasso.registry.PostgresSupport
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * 통합 시험 한 세트. 한 JVM 에 Postgres(registry testFixtures)·registry·mimic·실행 호스트·운영 서비스를 띄운다(스펙 §10,
 * S3a 스펙 §11).
 *
 * 시험 클래스마다 새로 띄우고 닫는다. 클래스 사이에 registry 를 멈추는 시험이 있어 공유하지 않는다.
 * DB 는 띄울 때마다 비운다. `PostgresSupport.reset()` 은 public 만 지우므로 ops 스키마는 따로 지운다.
 *
 * 실행 호스트의 시계는 현장 시계(`Site.now()`)다. 시험은 `Site.advance` 로 가상 시각을 실제 시각보다 앞으로 밀고, 미들웨어는
 * E2 시간 윈도우의 기준 시각을 mimic 응답 헤더에서 가져오면서 마감은 호스트 시계로 보므로 둘이 같아야 한다(S3a 스펙 §7.2).
 */
class E2eStack private constructor(
    val site: Site,
    private val host: ConfigurableApplicationContext,
    private val ops: ConfigurableApplicationContext,
    val hostUrl: String,
    val opsUrl: String,
    val siteId: String,
) : AutoCloseable {

    /** 운영 서비스 응답 한 건. 본문이 비면 널이다. */
    data class Reply(val status: Int, val body: JsonNode?)

    fun get(path: String): JsonNode = read(opsUrl + path)

    /** 화면처럼 모드·사용자 헤더를 싣고 이 스택의 운영 서비스를 부른다. [mode] 가 널이면 헤더를 싣지 않는다. */
    fun send(
        method: String,
        path: String,
        mode: String?,
        user: String = "kim",
        body: String? = null,
        contentType: String = "application/json",
    ): Reply = send(opsUrl, method, path, mode, user, body, contentType)

    /** 같은 registry 에 운영자 토큰만 다른 운영 서비스를 하나 더 띄운다. 닫는 것은 부르는 쪽이다. */
    fun opsWithToken(token: String): Pair<ConfigurableApplicationContext, String> {
        val context = startOps(site.registryUrl, token, siteId, hostUrl)
        return context to "http://127.0.0.1:${(context as WebServerApplicationContext).webServer.port}"
    }

    override fun close() {
        try {
            ops.close()
        } finally {
            try {
                host.close()
            } finally {
                site.close()
            }
        }
    }

    companion object {
        const val OPERATOR_TOKEN = "e2e-operator"
        val root: Path = Path.of("..").toAbsolutePath().normalize()
        private val http = HttpClient.newHttpClient()
        private val json = ObjectMapper()

        fun start(): E2eStack {
            val siteId = checkNotNull(System.getenv("SITE_ID")) { "SITE_ID 가 없다(루트 .env)" }
            PostgresSupport.reset()
            PostgresSupport.execute("DROP SCHEMA IF EXISTS ops CASCADE")
            val site = Site.start(
                SiteConfig(
                    root = root,
                    siteId = siteId,
                    db = db(),
                    registryPort = 0,
                    operatorToken = OPERATOR_TOKEN,
                    ingestToken = "e2e-ingest",
                    roster = RobotRoster.read(root.resolve(SiteConfig.ROSTER)),
                ),
            )
            // 실행 호스트는 Site 다음, 운영 서비스 앞이다. 운영 서비스가 기동에서 호스트 주소를 받는다.
            val host = try {
                startHost(site)
            } catch (e: Exception) {
                site.close()
                throw e
            }
            val hostUrl = "http://127.0.0.1:${(host as WebServerApplicationContext).webServer.port}"
            val ops = try {
                startOps(site.registryUrl, OPERATOR_TOKEN, siteId, hostUrl)
            } catch (e: Exception) {
                host.close()
                site.close()
                throw e
            }
            return E2eStack(site, host, ops, hostUrl, "http://127.0.0.1:${(ops as WebServerApplicationContext).webServer.port}", siteId)
        }

        /** [baseUrl] 의 운영 서비스를 화면처럼 부른다. */
        fun send(
            baseUrl: String,
            method: String,
            path: String,
            mode: String?,
            user: String = "kim",
            body: String? = null,
            contentType: String = "application/json",
        ): Reply {
            val headers = buildMap {
                if (mode != null) {
                    put("X-Ops-Mode", mode)
                    put("X-Ops-User", user)
                }
                if (body != null) put("Content-Type", contentType)
            }
            return call(method, baseUrl + path, headers, body)
        }

        /** 운영 서비스 하나를 GET 으로 읽는다. 200 이 아니면 시험을 멈춘다. */
        fun read(url: String): JsonNode {
            val reply = call("GET", url, emptyMap(), null)
            check(reply.status == 200) { "GET $url → ${reply.status} ${reply.body}" }
            return reply.body!!
        }

        private fun db() = DbConfig(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)

        private fun startOps(registryUrl: String, token: String, siteId: String, hostUrl: String): ConfigurableApplicationContext {
            val db = db()
            return OpsApplication.builder().run(
                "--server.port=0",
                "--spring.datasource.url=${db.url}",
                "--spring.datasource.username=${db.user}",
                "--spring.datasource.password=${db.password}",
                "--ops.registry.url=$registryUrl",
                "--ops.registry.operator-token=$token",
                "--ops.site-id=$siteId",
                "--ops.host.url=$hostUrl",
            )
        }

        /** 실행 호스트를 이 현장의 mimic gRPC 포트·셀 대역에 붙이고 현장 시계로 띄운다(S3a 계약 공통 규칙). */
        private fun startHost(site: Site): ConfigurableApplicationContext =
            MissionHostApplication.builder(HostClock { site.now() }).run(
                "--server.port=0",
                "--host.mimic.port=${site.mimicPort}",
                "--host.cell.url=http://127.0.0.1:${site.cellPort}",
            )

        private fun call(method: String, url: String, headers: Map<String, String>, body: String?): Reply {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            headers.forEach { (name, value) -> builder.header(name, value) }
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            val parsed = response.body().takeIf { it.isNotBlank() }?.let { runCatching { json.readTree(it) }.getOrNull() }
            return Reply(response.statusCode(), parsed)
        }
    }
}
