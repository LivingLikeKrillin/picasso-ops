package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.registry.RegistryAdapter
import dev.picasso.ops.service.registry.RegistryBuild
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryClient
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 어댑터 읽기와 조작이 registry 와 주고받는 모양(스펙 §5·§7.2). registry 는 JDK HttpServer 대역이다. */
class RegistryAdaptersTest {

    private class Seen(val method: String, val uri: String, val actor: String?, val body: String)

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
    fun `제품·빌드 목록은 registry 의 snake_case 를 읽는다`() {
        val body = """[{"adapter_id":1,"vendor":"acme","name":"fleet","versions":[{"adapter_version_id":10,"version":"1.0.0",
            "contract_semver":"0.9.0","conformance":"UNTESTED","registered_at":"t","registered_by":"engineer/lee"}]}]"""
        val call = RegistryClient(serve(200, body), "op-t").adapters()
        val build = RegistryBuild(10, "1.0.0", "0.9.0", "UNTESTED", "t", "engineer/lee")
        assertEquals(RegistryCall.Ok(listOf(RegistryAdapter(1, "acme", "fleet", listOf(build)))), call)
        assertEquals("GET /operations/adapters", "${seen!!.method} ${seen!!.uri}")
    }

    @Test
    fun `인스턴스 목록은 사이트로 묻는다`() {
        val body = """[{"instanceId":"i1","siteId":"site 01","fleetEndpoint":null,"registeredAt":"t","registeredBy":"engineer/lee",
            "adapter":"acme/fleet","version":"1.0.0","contractSemver":"0.9.0","conformance":"UNTESTED","discoveredRobots":0}]"""
        val call = RegistryClient(serve(200, body), "op-t").instances("site 01")
        assertEquals("UNTESTED", (call as RegistryCall.Ok).value.single().conformance)
        assertEquals("GET /diag/adapter-instances?site=site+01", "${seen!!.method} ${seen!!.uri}")
    }

    @Test
    fun `조작 3가지는 registry 의 경로와 본문 모양으로 X-Actor 를 싣고 보낸다`() {
        val client = RegistryClient(serve(201, "{}"), "op-t")
        client.declareAdapter("acme", "fleet", "engineer/lee")
        assertEquals("POST /operations/adapters engineer/lee", "${seen!!.method} ${seen!!.uri} ${seen!!.actor}")
        assertEquals(json.readTree("""{"vendor":"acme","name":"fleet"}"""), json.readTree(seen!!.body))

        client.declareBuild(1, "1.0.0", "0.9.0", "engineer/lee")
        assertEquals("POST /operations/adapters/1/versions", "${seen!!.method} ${seen!!.uri}")
        assertEquals(json.readTree("""{"version":"1.0.0","contract_semver":"0.9.0"}"""), json.readTree(seen!!.body))

        client.registerInstance("site-01", "i1", 10, null, "engineer/lee")
        assertEquals("POST /operations/adapter-instances", "${seen!!.method} ${seen!!.uri}")
        assertEquals(json.readTree("""{"instance_id":"i1","adapter_version_id":10,"site":"site-01"}"""), json.readTree(seen!!.body))

        client.registerInstance("site-01", "i1", 10, "tcp://fleet:1", "engineer/lee")
        assertEquals("tcp://fleet:1", json.readTree(seen!!.body)["fleet_endpoint"].asText())
    }
}
