package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostClient
import dev.picasso.ops.service.host.HostIncident
import dev.picasso.ops.service.host.HostWrite
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 실행 호스트 클라이언트의 장애 주입 전달, 인시던트 읽기, 운영자 판단(S4a JSON 계약 §2~§5). 호스트는 JDK HttpServer 대역이다. */
class HostIncidentsClientTest {

    private var server: HttpServer? = null
    private val pool = Executors.newCachedThreadPool()
    private val json = ObjectMapper()
    private val requestId = UUID.fromString("3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10")

    /** 받은 요청(방법, 인코딩된 경로, 쿼리, Content-Type, 본문). */
    private val seen = mutableListOf<Seen>()

    private data class Seen(val method: String, val rawPath: String, val rawQuery: String?, val contentType: String?, val body: String)

    /** 경로 앞부분마다 (상태 코드, 본문)으로 답한다. */
    private fun serve(vararg routes: Pair<String, Pair<Int, String>>): HostClient {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        routes.forEach { (path, answer) ->
            s.createContext(path) { exchange ->
                seen += Seen(
                    exchange.requestMethod,
                    exchange.requestURI.rawPath,
                    exchange.requestURI.rawQuery,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestBody.readAllBytes().decodeToString(),
                )
                val bytes = answer.second.toByteArray()
                exchange.sendResponseHeaders(answer.first, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        s.executor = pool
        s.start()
        server = s
        return HostClient("http://127.0.0.1:${s.address.port}")
    }

    private fun stop() {
        server?.stop(0)
        server = null
    }

    @AfterTest
    fun close() {
        stop()
        pool.shutdownNow()
    }

    @Test
    fun `장애 주입은 본문을 그대로 POST host faults 로 보내고 현장의 응답을 그대로 돌려준다`() {
        val refused = """{"error":"NO_RUNNING_TASK","detail":"x"}"""
        val client = serve("/host/faults" to (409 to refused))
        val body = json.createObjectNode().put("robotId", "humanoid-01").put("kind", "SKILL_EXECUTION_FAILED")
        assertEquals(HostWrite.Answered(409, refused), client.injectFault(body))
        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("/host/faults", request.rawPath)
        assertEquals("application/json", request.contentType)
        assertEquals(body, json.readTree(request.body))
    }

    @Test
    fun `인시던트 목록은 limit 을 쿼리로 싣고 객체 본문 그대로이며 200 아님은 모름이다`() {
        val listed = """{"instanceId":"i-1","total":0,"incidents":[]}"""
        val client = serve("/host/incidents" to (200 to listed))
        assertEquals(json.readTree(listed), (client.incidents() as HostCall.Ok).value)
        assertEquals(json.readTree(listed), (client.incidents(500) as HostCall.Ok).value)
        assertEquals(listOf(null, "limit=500"), seen.map { it.rawQuery })
        assertEquals(listOf("/host/incidents", "/host/incidents"), seen.map { it.rawPath })
        stop()
        val refusing = serve("/host/incidents" to (400 to """{"error":"BAD_REQUEST","detail":"x"}"""))
        assertEquals(HostCall.Silent("HTTP 400"), refusing.incidents(9999))
    }

    @Test
    fun `인시던트 상세는 찾음, 404 INCIDENT_NOT_FOUND 면 없음 본문 그대로, 그 밖은 못 읽음이다`() {
        val found = serve("/host/incidents/" to (200 to """{"instanceId":"i-1","incidentId":"incident-1"}"""))
        assertEquals("incident-1", assertIs<HostIncident.Found>(found.incident("incident-1")).body["incidentId"].asText())
        assertEquals("/host/incidents/incident-1", seen.single().rawPath)
        found.incident("a/b")
        assertEquals("/host/incidents/a%2Fb", seen.last().rawPath)
        assertEquals(listOf(null, null), seen.map { it.rawQuery })
        stop()
        val missing = """{"error":"INCIDENT_NOT_FOUND","detail":"없다"}"""
        assertEquals(
            HostIncident.NotFound(json.readTree(missing)),
            serve("/host/incidents/" to (404 to missing)).incident("incident-9"),
        )
        stop()
        // 경로가 없는 서버의 404 는 호스트의 판단이 아니다.
        assertEquals(HostIncident.Silent("HTTP 404"), serve("/other" to (200 to "{}")).incident("incident-1"))
        stop()
        // 스프링 기본 404 본문에도 error 칸이 있다. 이름이 다르면 호스트의 판단이 아니다.
        val springDefault = """{"timestamp":"2026-10-09T00:00:00Z","status":404,"error":"Not Found","path":"/host/incidents/incident-1"}"""
        assertEquals(HostIncident.Silent("HTTP 404"), serve("/host/incidents/" to (404 to springDefault)).incident("incident-1"))
        stop()
        assertEquals(HostIncident.Silent("본문 모양이 다르다"), serve("/host/incidents/" to (200 to "[]")).incident("incident-1"))
        stop()
        assertEquals(HostIncident.Silent("HTTP 500"), serve("/host/incidents/" to (500 to "{}")).incident("incident-1"))
    }

    @Test
    fun `판단은 실행과 단위를 경로 조각으로 인코딩해 결정과 승인자와 요청 id 를 보내고 응답을 그대로 돌려준다`() {
        val answer = """{"result":"NotHeld","detail":null,"incidentId":null,"requestId":"$requestId"}"""
        val client = serve("/host/executions/" to (200 to answer))
        assertEquals(
            HostWrite.Answered(200, answer),
            client.resolve("exec-1", "RACK-204.S01/x", "CONFIRM_DONE", "kim", "mw-9b1e", requestId),
        )
        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("/host/executions/exec-1/units/RACK-204.S01%2Fx/resolve", request.rawPath)
        assertEquals("application/json", request.contentType)
        // 칸 순서도 계약 그대로다(S4b 계약 H5).
        assertEquals(
            """{"decision":"CONFIRM_DONE","approverId":"kim","requestId":"$requestId","instanceId":"mw-9b1e"}""",
            request.body,
        )
    }

    @Test
    fun `인시던트 상세는 instanceId 를 쿼리로 인코딩해 싣고 이전 인스턴스 사본을 찾음으로 돌려준다`() {
        val client = serve("/host/incidents/" to (200 to """{"instanceId":"mw 3f/0c","incidentId":"incident-1","held":false}"""))
        val found = assertIs<HostIncident.Found>(client.incident("incident-1", "mw 3f/0c"))
        assertEquals("mw 3f/0c", found.body["instanceId"].asText())
        assertEquals("/host/incidents/incident-1", seen.single().rawPath)
        assertEquals("instanceId=mw%203f%2F0c", seen.single().rawQuery)
    }

    @Test
    fun `송신 기록은 작업 지시 id 와 limit 을 있을 때만 쿼리로 싣고 객체 본문 그대로이며 200 아님은 모름이다`() {
        val body = """{"instanceId":"i-2","total":0,"responses":[]}"""
        val client = serve("/host/job-responses" to (200 to body))
        assertEquals(json.readTree(body), (client.jobResponses(null, null) as HostCall.Ok).value)
        client.jobResponses("JO 1&x", null)
        client.jobResponses(null, 500)
        client.jobResponses("JO-1", 20)
        assertEquals(listOf("/host/job-responses"), seen.map { it.rawPath }.distinct())
        assertEquals(listOf(null, "jobOrderId=JO%201%26x", "limit=500", "jobOrderId=JO-1&limit=20"), seen.map { it.rawQuery })
        stop()
        val refusing = serve("/host/job-responses" to (400 to """{"error":"BAD_REQUEST","detail":"x"}"""))
        assertEquals(HostCall.Silent("HTTP 400"), refusing.jobResponses(null, 9999))
        stop()
        assertEquals(HostCall.Silent("본문 모양이 다르다"), serve("/host/job-responses" to (200 to "[]")).jobResponses(null, null))
    }

    @Test
    fun `닿지 않는 호스트는 읽기가 모름이고 쓰기가 응답 없음이다`() {
        val client = serve()
        stop()
        assertIs<HostCall.Silent>(client.incidents())
        assertIs<HostIncident.Silent>(client.incident("incident-1"))
        assertIs<HostWrite.NoResponse>(client.resolve("exec-1", "rack-arrival", "REWORK", "kim", "i-1", requestId))
        assertIs<HostCall.Silent>(client.jobResponses(null, null))
        assertIs<HostWrite.NoResponse>(client.injectFault(json.createObjectNode()))
    }
}
