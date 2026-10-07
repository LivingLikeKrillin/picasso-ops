package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryClient
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 개정판·바인딩 읽기와 조작이 registry 와 주고받는 모양(P2·S1d 스펙 §8.1·§8.2). registry 는 JDK HttpServer 대역이다. */
class RegistryProfilesTest {

    private class Seen(val method: String, val uri: String, val actor: String?, val body: ByteArray)

    private var server: HttpServer? = null
    @Volatile private var seen: Seen? = null
    private val json = ObjectMapper()

    private fun serve(status: Int, body: String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { exchange ->
            seen = Seen(
                exchange.requestMethod,
                exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: ""),
                exchange.requestHeaders.getFirst("X-Actor"),
                exchange.requestBody.readBytes(),
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

    private fun request() = "${seen!!.method} ${seen!!.uri} ${seen!!.actor}"

    @Test
    fun `카탈로그와 개정판 목록은 registry 의 snake_case 를 읽는다`() {
        val catalog = RegistryClient(
            serve(200, """{"contract_semver":"0.9.0","skill_types":[{"name":"navigate_to","major":1,"introduced_in_semver":"0.1.0","site_reference_keys":["location"]}]}"""),
            "op-t",
        ).catalog()
        val skill = (catalog as RegistryCall.Ok).value.skillTypes.single()
        assertEquals(listOf("navigate_to", "0.1.0", "location"), listOf(skill.name, skill.introducedInSemver, skill.siteReferenceKeys.single()))
        assertEquals("GET /operations/skill-types null", request())

        val revisions = RegistryClient(
            serve(
                200,
                """[{"profile_revision_id":5,"vendor":"v","model":"m","revision":2,"status":"TESTED","reasons":[],"document_hash":"h",
                "suites":{"CONTRACT":{"result":"PASS","ran_at":"t","ran_by":"site-runner","detail":{"checks":3,"failures":[]}}},
                "latest_test_request":{"request_id":9,"requested_by":"engineer/kim","requested_at":"t","claimed_by":"site-runner",
                "claimed_at":"t","claim_expires_at":"t","completed_at":"t"}}]""",
            ),
            "op-t",
        ).revisions()
        val row = (revisions as RegistryCall.Ok).value.single()
        assertEquals("site-runner" to 3, row.suites["CONTRACT"]!!.ranBy to row.suites["CONTRACT"]!!.detail!!["checks"].asInt())
        assertEquals(9L to "t", row.latestTestRequest!!.requestId to row.latestTestRequest!!.completedAt)
    }

    @Test
    fun `바인딩은 사이트의 활성 행만 묻고 줄을 읽는다`() {
        val call = RegistryClient(
            serve(
                200,
                """{"rows":[{"robotId":"r1","siteId":"site 01","vendor":"v","model":"m","profileRevisionId":5,"revision":2,
                "adapterName":"acme/fleet","adapterVersion":"1.0.0","conformanceStatus":"UNTESTED","active":true,"liveness":"REPORTING",
                "siteNames":"CONFIRMED","siteNameKeys":["location"],"adapterVersionId":7,"boundBy":"engineer/kim","boundAt":"t",
                "siteNamesRegisteredBy":"engineer/kim","siteNamesRegisteredAt":"t","siteNamesReportedAt":"t","siteNamesCount":2,
                "siteNamesUnsupported":false}],"robotsPerRevision":{"5":1}}""",
            ),
            "op-t",
        ).bindings("site 01")
        val row = (call as RegistryCall.Ok).value.single()
        assertEquals(Triple(7L, "CONFIRMED", 2), Triple(row.adapterVersionId, row.siteNames, row.siteNamesCount))
        assertEquals("GET /diag/bindings?site=site+01 null", request())
    }

    @Test
    fun `소프트웨어 대조는 사이트로 묻는다`() {
        val call = RegistryClient(serve(200, """[{"robotId":"r1","declared":null,"reported":"1.0","verdict":"UNREPORTED"}]"""), "op-t").software("site-01")
        val row = (call as RegistryCall.Ok).value.single()
        assertEquals("UNREPORTED" to null, row.verdict to row.declared)
        assertEquals("GET /diag/software?site=site-01 null", request())
    }

    @Test
    fun `제출은 본문 바이트를 다시 직렬화하지 않고 그대로 보낸다`() {
        val document = "{\n  \"vendor\" : \"v\",   \"note\": \"한글\"\n}".toByteArray()
        RegistryClient(serve(201, "{}"), "op-t").submit(document, "engineer/kim")
        assertEquals("POST /operations/profile-revisions engineer/kim", request())
        assertContentEquals(document, seen!!.body)
    }

    @Test
    fun `시험 요청·활성화·명칭 기록은 본문 없이, 바인딩은 두 id 를 싣고 보낸다`() {
        val client = RegistryClient(serve(200, "{}"), "op-t")

        client.requestTest(5, "engineer/kim")
        assertEquals("POST /operations/profile-revisions/5/test-requests engineer/kim", request())
        assertEquals(0, seen!!.body.size)

        client.activate(5, "engineer/kim")
        assertEquals("POST /operations/profile-revisions/5/activation engineer/kim", request())

        client.recordSiteNames("robot 1", "engineer/kim")
        assertEquals("POST /operations/site-names?robot=robot+1 engineer/kim", request())

        client.bind("robot 1", 7, 5, "engineer/kim")
        assertEquals("POST /operations/robots/robot%201/binding engineer/kim", request())
        assertEquals(json.readTree("""{"adapter_version_id":7,"profile_revision_id":5}"""), json.readTree(seen!!.body))
    }

    @Test
    fun `개정판 목록의 401 은 토큰 불일치다`() {
        assertEquals(RegistryCall.Unauthorized, RegistryClient(serve(401, ""), "op-t").revisions())
        assertNull((RegistryClient(serve(200, "null"), "op-t").catalog() as? RegistryCall.Ok))
    }
}
