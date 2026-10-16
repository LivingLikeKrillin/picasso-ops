package dev.picasso.ops.service

import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.registry.ReadFailureLog
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryClient
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RegistryClientTest {

    private var server: HttpServer? = null
    @Volatile private var seenQuery: String? = null
    @Volatile private var seenAuthorization: String? = null

    private fun serve(status: Int, body: String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/diag/robots") { exchange ->
            seenQuery = exchange.requestURI.rawQuery
            seenAuthorization = exchange.requestHeaders.getFirst("Authorization")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `퇴역 기체를 포함해 사이트로 묻고 칸을 읽는다`() {
        val url = serve(
            200,
            """[{"robotId":"r1","siteId":"site-01","status":"RETIRED","lastReportedAt":"2026-10-07T00:00:00Z",
               "reportingAfterRetirement":true,"unknownField":1}]""",
        )
        val call = RegistryClient(url, "op-t").robots("site-01")
        val robot = assertIs<RegistryCall.Ok<List<dev.picasso.ops.service.registry.RegistryRobot>>>(call).value.single()
        assertEquals("r1", robot.robotId)
        assertEquals("RETIRED", robot.status)
        assertEquals(true, robot.reportingAfterRetirement)
        assertEquals("retired=true&site=site-01", seenQuery)
        assertEquals("Bearer op-t", seenAuthorization)
    }

    @Test
    fun `본문 null 은 모름이다`() {
        assertIs<RegistryCall.Silent>(RegistryClient(serve(200, "null"), "op-t").robots("site-01"))
    }

    /** 실제 registry 의 `/diag/robots` 는 관문 밖이라 401 을 안 낸다. 분류만 본다(S1b 의 `/operations` 호출이 쓴다). */
    @Test
    fun `401 은 토큰 불일치로 분류한다`() {
        assertEquals(RegistryCall.Unauthorized, RegistryClient(serve(401, ""), "op-t").robots("site-01"))
    }

    @Test
    fun `5xx 는 모름이다`() {
        val silent = assertIs<RegistryCall.Silent>(RegistryClient(serve(503, ""), "op-t").robots("site-01"))
        // 원인에 경로(질의 문자열 없이)·HTTP 상태·걸린 시간이 남는다.
        assertTrue(silent.cause.startsWith("GET /diag/robots: HTTP 503 (") && silent.cause.endsWith("ms)"), silent.cause)
    }

    @Test
    fun `해석할 수 없는 본문은 모름이다`() {
        assertIs<RegistryCall.Silent>(RegistryClient(serve(200, "not json"), "op-t").robots("site-01"))
    }

    @Test
    fun `닿지 않는 registry 는 모름이다`() {
        val url = serve(200, "[]")
        server!!.stop(0)
        server = null
        val silent = assertIs<RegistryCall.Silent>(RegistryClient(url, "op-t").robots("site-01"))
        // 원인에 예외 클래스가 남는다.
        assertTrue(silent.cause.startsWith("GET /diag/robots: 응답 없음: ConnectException"), silent.cause)
    }

    /** 응답 상태를 시험이 바꿀 수 있는 서버. 경로는 `/diag/robots` 와 `/operations/adapters` 둘이다. */
    @Volatile private var status = 503

    private fun serveSwitchable(): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        for (path in listOf("/diag/robots", "/operations/adapters")) {
            s.createContext(path) { exchange ->
                val bytes = "[]".toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @Test
    fun `같은 경로의 모름은 질의가 달라도 한 줄로 솎고 회복을 한 번 남긴다`() {
        val warns = CopyOnWriteArrayList<String>()
        val infos = CopyOnWriteArrayList<String>()
        // 간격 안에 끝나는 시험이라 시계는 실제 것을 쓴다.
        val log = ReadFailureLog(Duration.ofSeconds(60), Clock.systemUTC(), { warns += it }, { infos += it })
        val client = RegistryClient(serveSwitchable(), "op-t", readFailures = log)

        val causes = listOf("site-01", "site-02", "site-03").map {
            assertIs<RegistryCall.Silent>(client.robots(it)).cause
        }
        // 응답에 실리는 원인은 요청마다 그대로다. 솎는 것은 로그 줄뿐이다.
        assertTrue(causes.all { it.startsWith("GET /diag/robots: HTTP 503 (") }, causes.toString())
        assertEquals(1, warns.size, warns.toString())
        assertTrue(warns.single().startsWith("registry 읽기 모름: GET /diag/robots: HTTP 503 ("), warns.toString())
        assertTrue(infos.isEmpty())

        // 다른 경로는 따로 센다.
        assertIs<RegistryCall.Silent>(client.adapters())
        assertEquals(2, warns.size, warns.toString())

        status = 200
        assertIs<RegistryCall.Ok<*>>(client.robots("site-01"))
        assertEquals(listOf("registry 읽기 회복: /diag/robots (생략 2건)"), infos.toList())
        assertIs<RegistryCall.Ok<*>>(client.robots("site-01"))
        assertEquals(1, infos.size)
    }
}
