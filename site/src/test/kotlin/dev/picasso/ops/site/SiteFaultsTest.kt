package dev.picasso.ops.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.client.PicassoClient
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.mimic.cli.MimicCli
import dev.picasso.mimic.engine.TaskState
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 기체 장애 주입 `POST /faults`(S4a 스펙 §5, §10 의 site 행). registry 없이 mimic 하나와 셀 대역만 띄우고 기체에 `pick_place` 를
 * 직접 걸어 가상 시계로 민다. 시계를 미는 순서는 [Site.advance] 와 같다(민 직후 훑기).
 */
class SiteFaultsTest {

    private val root = Path.of("..").toAbsolutePath().normalize()
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    private inner class Bench : AutoCloseable {
        val mimic: MimicCli.Started = checkNotNull(
            MimicCli().start(
                robots = mapOf(
                    HUMANOID to root.resolve("picasso/profile/profiles/humanoid-a.json"),
                    QUADRUPED to root.resolve("picasso/profile/profiles/quadruped-b.json"),
                ),
                schema = root.resolve(SiteConfig.PROFILE_SCHEMA),
                port = 0,
                virtual = true,
                seed = 0L,
                err = System.err,
            ),
        ) { "mimic 기동 거부" }
        val cell = SiteCell(mimic)
        private val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", mimic.server.port).usePlaintext().build()
        val client = PicassoClient(channel, "site-faults-test")

        init {
            advance(Duration.between(Instant.EPOCH, START))
        }

        fun advance(by: Duration) = mimic.server.exclusive {
            mimic.server.advance(by)
            cell.scan()
        }

        fun pickPlace(taskId: String, destination: String = S01) {
            val response = client.start(
                HUMANOID, taskId, 1, "pick_place",
                listOf(
                    ParameterValue.newBuilder().setKey("object_id").setStringValue(SOURCE).build(),
                    ParameterValue.newBuilder().setKey("destination").setStringValue(destination).build(),
                ),
            )
            assertTrue(response.hasHandle(), "태스크 시작이 거부됐다: ${response.rejection}")
        }

        fun state(taskId: String): TaskState? = mimic.server.exclusive { mimic.instance(HUMANOID)!!.tasks.find(taskId)?.machine?.state }

        fun connection(robotId: String): ConnectionState = mimic.server.exclusive { mimic.instance(robotId)!!.events.connectionState }

        fun post(body: String, contentType: String = "application/json", path: String = "/faults"): HttpResponse<String> = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${cell.port}$path"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        fun inject(robotId: String, kind: String, state: String? = null): HttpResponse<String> =
            post(json.writeValueAsString(linkedMapOf("robotId" to robotId, "kind" to kind, "state" to state).filterValues { it != null }))

        override fun close() {
            channel.shutdownNow()
            cell.close()
            mimic.server.shutdown()
        }
    }

    private fun HttpResponse<String>.json(): JsonNode = json.readTree(body())

    private fun HttpResponse<String>.error(): String = json()["error"].asText()

    @Test
    fun `스킬 실패는 현장이 진행 중인 pick_place 를 찾아 강제하고 그 태스크는 RETRIABLE 이며 슬롯을 채우지 않는다`() {
        Bench().use { bench ->
            bench.pickPlace("JO-1#$S01")
            bench.advance(Duration.ofSeconds(1))
            assertEquals(TaskState.RUNNING, bench.state("JO-1#$S01"))

            val reply = bench.inject(HUMANOID, SKILL)
            assertEquals(200, reply.statusCode(), reply.body())
            assertEquals(
                mapOf("robotId" to HUMANOID, "kind" to SKILL, "taskId" to "JO-1#$S01", "taskState" to "RETRIABLE", "raised" to true),
                json.convertValue(reply.json(), Map::class.java),
            )
            assertEquals(listOf("robotId", "kind", "taskId", "taskState", "raised"), reply.json().fieldNames().asSequence().toList())
            assertEquals(TaskState.RETRIABLE, bench.state("JO-1#$S01"))
            val raised = bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.faults.active().map { it.errorType } }
            assertEquals(listOf(SKILL), raised)

            // 실패로 끝난 태스크는 다음 시계 진행에서 슬롯을 채우지 않는다.
            repeat(4) { bench.advance(Duration.ofSeconds(5)) }
            assertEquals(TaskState.RETRIABLE, bench.state("JO-1#$S01"))
            assertFalse(bench.cell.snapshot.slots.single { it.id == S01 }.occupied)
        }
    }

    @Test
    fun `진행 중 태스크가 없으면 409 NO_RUNNING_TASK 이고 끝난 태스크와 pick_place 가 없는 기체도 같다`() {
        Bench().use { bench ->
            val idle = bench.inject(HUMANOID, SKILL)
            assertEquals(409, idle.statusCode(), idle.body())
            assertEquals(SiteFaults.NO_RUNNING_TASK, idle.error())

            bench.pickPlace("JO-1#$S01")
            repeat(40) { if (bench.state("JO-1#$S01")?.isTerminal != true) bench.advance(Duration.ofSeconds(5)) }
            assertEquals(TaskState.SUCCEEDED, bench.state("JO-1#$S01"))
            val done = bench.inject(HUMANOID, SKILL)
            assertEquals(409, done.statusCode(), done.body())
            assertEquals(SiteFaults.NO_RUNNING_TASK, done.error())

            val quadruped = bench.inject(QUADRUPED, SKILL)
            assertEquals(409, quadruped.statusCode(), quadruped.body())
            assertEquals(SiteFaults.NO_RUNNING_TASK, quadruped.error())
            assertTrue(bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.faults.active().isEmpty() })
        }
    }

    @Test
    fun `지울 때까지 유지되는 결함과 전송 장애와 HIBERNATING 은 400 UNSUPPORTED_FAULT 이고 모르는 기체는 404 다`() {
        Bench().use { bench ->
            bench.pickPlace("JO-1#$S01")
            bench.advance(Duration.ofSeconds(1))

            listOf("PAYLOAD_LOST", "LOCALIZATION_LOST", "CONTROL_AUTHORITY_LOST", "DISCONNECT", "DELAY", "EVENT_LOSS").forEach { kind ->
                val reply = bench.inject(HUMANOID, kind)
                assertEquals(400, reply.statusCode(), kind)
                assertEquals(SiteFaults.UNSUPPORTED_FAULT, reply.error(), kind)
            }
            listOf("HIBERNATING", "CONNECTION_STATE_OFFLINE", "offline").forEach { state ->
                val reply = bench.inject(HUMANOID, SiteFaults.CONNECTION, state)
                assertEquals(400, reply.statusCode(), state)
                assertEquals(SiteFaults.UNSUPPORTED_FAULT, reply.error(), state)
            }
            assertEquals(TaskState.RUNNING, bench.state("JO-1#$S01"))
            assertEquals(ConnectionState.CONNECTION_STATE_ONLINE, bench.connection(HUMANOID))

            listOf(bench.inject(GHOST, SKILL), bench.inject(GHOST, SiteFaults.CONNECTION, "OFFLINE")).forEach { reply ->
                assertEquals(404, reply.statusCode(), reply.body())
                assertEquals(SiteFaults.UNKNOWN_ROBOT, reply.error())
            }
        }
    }

    @Test
    fun `연결 상태를 바꾸고 같은 상태를 다시 넣으면 changed 가 거짓이며 ONLINE 으로 복구한다`() {
        Bench().use { bench ->
            val offline = bench.inject(HUMANOID, SiteFaults.CONNECTION, "OFFLINE")
            assertEquals(200, offline.statusCode(), offline.body())
            assertEquals(
                mapOf("robotId" to HUMANOID, "kind" to "CONNECTION", "state" to "OFFLINE", "changed" to true),
                json.convertValue(offline.json(), Map::class.java),
            )
            assertEquals(listOf("robotId", "kind", "state", "changed"), offline.json().fieldNames().asSequence().toList())
            assertEquals(ConnectionState.CONNECTION_STATE_OFFLINE, bench.connection(HUMANOID))
            assertEquals(ConnectionState.CONNECTION_STATE_ONLINE, bench.connection(QUADRUPED))

            assertEquals(false, bench.inject(HUMANOID, SiteFaults.CONNECTION, "OFFLINE").json()["changed"].asBoolean())

            assertEquals(200, bench.inject(QUADRUPED, SiteFaults.CONNECTION, "CONNECTION_BROKEN").statusCode())
            assertEquals(ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN, bench.connection(QUADRUPED))

            assertEquals(true, bench.inject(HUMANOID, SiteFaults.CONNECTION, "ONLINE").json()["changed"].asBoolean())
            assertEquals(ConnectionState.CONNECTION_STATE_ONLINE, bench.connection(HUMANOID))
        }
    }

    @Test
    fun `본문 모양이 틀리면 400 BAD_REQUEST 이고 JSON 이 아니면 415 이며 POST 만 받고 다른 경로는 404 다`() {
        Bench().use { bench ->
            listOf(
                """{"kind":"$SKILL"}""",
                """{"robotId":"$HUMANOID"}""",
                """{"robotId":7,"kind":"$SKILL"}""",
                """{"robotId":"$HUMANOID","kind":"CONNECTION"}""",
                """{"robotId":"$HUMANOID","kind":"CONNECTION","state":true}""",
                """{"robotId":"$HUMANOID","kind":"$SKILL","state":"OFFLINE"}""",
                """["$HUMANOID"]""",
                "{not json",
            ).forEach { body ->
                val reply = bench.post(body)
                assertEquals(400, reply.statusCode(), body)
                assertEquals(SiteFaults.BAD_REQUEST, reply.error(), body)
            }
            // state 가 null 이면 없는 것과 같다.
            assertEquals(409, bench.post("""{"robotId":"$HUMANOID","kind":"$SKILL","state":null}""").statusCode())

            val text = bench.post("""{"robotId":"$HUMANOID","kind":"CONNECTION","state":"OFFLINE"}""", contentType = "text/plain")
            assertEquals(415, text.statusCode())
            assertEquals(SiteFaults.UNSUPPORTED_MEDIA_TYPE, text.error())
            assertEquals(ConnectionState.CONNECTION_STATE_ONLINE, bench.connection(HUMANOID))

            val get = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${bench.cell.port}/faults")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(405, get.statusCode())
            assertEquals("POST", get.headers().firstValue("Allow").orElse(null))
            assertEquals(404, bench.post("""{"robotId":"$HUMANOID","kind":"$SKILL"}""", path = "/faults/x").statusCode())
        }
    }

    @Test
    fun `장애 주입은 mimic 엔진 잠금을 기다린다`() {
        Bench().use { bench ->
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread {
                bench.mimic.server.exclusive {
                    held.countDown()
                    release.await()
                }
            }.apply { start() }
            held.await()
            val injection = CompletableFuture.supplyAsync { bench.inject(HUMANOID, SiteFaults.CONNECTION, "OFFLINE").statusCode() }
            try {
                Thread.sleep(300)
                assertTrue(!injection.isDone, "엔진 잠금을 쥔 동안 장애 주입이 끝났다")
            } finally {
                release.countDown()
                holder.join()
            }
            assertEquals(200, injection.get(5, TimeUnit.SECONDS))
            assertEquals(ConnectionState.CONNECTION_STATE_OFFLINE, bench.connection(HUMANOID))
        }
    }

    private companion object {
        const val HUMANOID = "humanoid-01"
        const val QUADRUPED = "quadruped-01"
        const val GHOST = "ghost-01"
        const val SKILL = "SKILL_EXECUTION_FAILED"
        const val SOURCE = "SEQ-IN-02.BIN-A"
        const val S01 = "RACK-204.S01"
        val START: Instant = Instant.parse("2026-10-08T00:00:00Z")
    }
}
