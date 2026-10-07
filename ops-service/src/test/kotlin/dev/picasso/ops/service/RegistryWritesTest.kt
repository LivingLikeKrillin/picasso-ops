package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryClient
import dev.picasso.ops.service.registry.RegistryWrite
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** 조작과 토큰 확인이 registry 에 보내는 모양(스펙 §7.1·§7.2). registry 는 JDK HttpServer 대역이다. */
class RegistryWritesTest {

    private class Seen(val method: String, val path: String, val headers: Map<String, String?>, val body: String)

    private var server: HttpServer? = null
    @Volatile private var seen: Seen? = null
    private val json = ObjectMapper()

    private fun serve(status: Int, body: String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { exchange ->
            val headers = listOf("Authorization", "X-Actor", "Content-Type")
                .associateWith { exchange.requestHeaders.getFirst(it) }
            seen = Seen(
                exchange.requestMethod,
                exchange.requestURI.rawPath,
                headers,
                exchange.requestBody.readBytes().decodeToString(),
            )
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
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
    fun `선언은 운영자 토큰과 X-Actor 를 싣고 registry 의 본문 모양으로 보낸다`() {
        val write = RegistryClient(serve(201, """{"robot":"r1","status":"CLAIMED"}"""), "op-t")
            .declare("site-01", "r1", "SN-1", null, "engineer/lee")
        assertEquals(RegistryWrite.Answered(201, """{"robot":"r1","status":"CLAIMED"}"""), write)
        val request = seen!!
        assertEquals("POST", request.method)
        assertEquals("/operations/robots", request.path)
        assertEquals("Bearer op-t", request.headers["Authorization"])
        assertEquals("engineer/lee", request.headers["X-Actor"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals(
            json.readTree("""{"robot_id":"r1","site":"site-01","serial_number":"SN-1"}"""),
            json.readTree(request.body),
        )
    }

    @Test
    fun `퇴역은 사유를 싣고 복귀는 같은 경로에 DELETE 를 보낸다`() {
        val url = serve(200, "{}")
        val client = RegistryClient(url, "op-t")
        client.retire("r 1", "정비", "operator/kim")
        assertEquals("POST" to "/operations/robots/r%201/retirement", seen!!.method to seen!!.path)
        assertEquals(json.readTree("""{"reason":"정비"}"""), json.readTree(seen!!.body))
        client.reinstate("r 1", "operator/kim")
        assertEquals("DELETE" to "/operations/robots/r%201/retirement", seen!!.method to seen!!.path)
    }

    @Test
    fun `거절도 응답이므로 코드와 본문을 그대로 넘긴다`() {
        val write = RegistryClient(serve(409, """{"status":"RETIRED"}"""), "op-t").retire("r1", "정비", "operator/kim")
        assertEquals(RegistryWrite.Answered(409, """{"status":"RETIRED"}"""), write)
    }

    @Test
    fun `닿지 않는 registry 는 응답 없음이다`() {
        val url = serve(200, "{}")
        server!!.stop(0)
        server = null
        assertIs<RegistryWrite.NoResponse>(RegistryClient(url, "op-t").reinstate("r1", "operator/kim"))
    }

    @Test
    fun `토큰 확인은 관문 안의 읽기를 부르고 401 을 토큰 불일치로 본다`() {
        assertEquals(RegistryCall.Unauthorized, RegistryClient(serve(401, ""), "op-t").operatorToken())
        assertEquals("GET" to "/operations/adapters", seen!!.method to seen!!.path)
        stop()
        assertEquals(RegistryCall.Ok(Unit), RegistryClient(serve(200, "[]"), "op-t").operatorToken())
    }

    @Test
    fun `형식이 틀린 registry 주소는 기동에서 거절한다`() {
        assertFailsWith<IllegalArgumentException> { RegistryClient("127.0.0.1:8781", "op-t") }
        assertFailsWith<IllegalArgumentException> { RegistryClient("", "op-t") }
        assertEquals("http://127.0.0.1:8781", RegistryClient.checkBaseUrl("http://127.0.0.1:8781/"))
    }
}
