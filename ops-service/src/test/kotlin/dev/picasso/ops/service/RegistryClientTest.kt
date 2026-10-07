package dev.picasso.ops.service

import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryClient
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
        assertIs<RegistryCall.Silent>(RegistryClient(serve(503, ""), "op-t").robots("site-01"))
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
        assertIs<RegistryCall.Silent>(RegistryClient(url, "op-t").robots("site-01"))
    }
}
