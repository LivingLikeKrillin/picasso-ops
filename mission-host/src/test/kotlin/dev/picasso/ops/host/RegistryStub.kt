package dev.picasso.ops.host

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * registry 의 진단 읽기 `GET /diag/robots` 대역. 실제 본문과 같은 칸 이름(`robotId`·`retiredAt`·`retiredReason`)으로 [retired] 를 낸다.
 * 실제 registry 본문과 맞는지는 통합 시험이 본다. [status] 를 200 밖으로 두면 그 상태 코드를 내고, [body] 를 주면 그것을 그대로 낸다.
 */
class RegistryStub : AutoCloseable {

    /** 퇴역 기체 id 에서 사유로. 사유가 `null` 이면 칸을 `null` 로 낸다. 바꾸면 다음 읽기부터 미친다. */
    @Volatile
    var retired: Map<String, String?> = emptyMap()

    /** 현역으로 함께 싣는 기체. 진단 읽기는 퇴역 기체와 현역 기체를 함께 낸다. */
    @Volatile
    var active: List<String> = listOf(HostBench.QUADRUPED)

    @Volatile
    var status: Int = 200

    @Volatile
    var body: String? = null

    /** 받은 읽기 수. */
    val reads = AtomicInteger()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/diag/robots") { exchange ->
            reads.incrementAndGet()
            val bytes = (body ?: render()).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    private fun render(): String {
        val rows = retired.map { (id, reason) ->
            val why = reason?.let { HostBench.JSON.writeValueAsString(it) } ?: "null"
            """{"robotId":"$id","siteId":"site-01","status":"RETIRED","retiredAt":"2026-11-02T00:00:00Z","retiredBy":"kim","retiredReason":$why}"""
        } + active.map { id ->
            """{"robotId":"$id","siteId":"site-01","status":"CONFIRMED","retiredAt":null,"retiredBy":null,"retiredReason":null}"""
        }
        return rows.joinToString(",", "[", "]")
    }

    override fun close() = server.stop(0)
}
