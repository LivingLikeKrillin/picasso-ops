package dev.picasso.ops.site

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.client.PicassoClient
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 셀 대역(S3a 스펙 §6.3). registry 없이 mimic 하나와 셀 대역만 띄우고, 기체에 `pick_place` 를 직접 걸어 가상 시계로 끝낸다.
 * 시계를 미는 순서는 [Site.advance] 와 같다(민 직후 훑기).
 */
class SiteCellTest {

    private val root = Path.of("..").toAbsolutePath().normalize()
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    /** mimic 하나와 셀 대역, 그리고 그 mimic 에 붙은 클라이언트. */
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
        val client = PicassoClient(channel, "site-cell-test")

        init {
            // EPOCH 에서 멀리 떨어진 시각에서 시작한다. 관측 시각을 EPOCH 로 적는 결함이 «거의 같은 값» 으로 숨지 않게 한다.
            advance(Duration.between(Instant.EPOCH, START))
        }

        fun now(): Instant = mimic.server.exclusive { mimic.instance(HUMANOID)!!.clock.now() }

        fun advance(by: Duration) = mimic.server.exclusive {
            mimic.server.advance(by)
            cell.scan()
        }

        fun pickPlace(robotId: String, taskId: String, objectId: String, destination: String) {
            val response = client.start(
                robotId, taskId, 1, "pick_place",
                listOf(
                    ParameterValue.newBuilder().setKey("object_id").setStringValue(objectId).build(),
                    ParameterValue.newBuilder().setKey("destination").setStringValue(destination).build(),
                ),
            )
            assertTrue(response.hasHandle(), "태스크 시작이 거부됐다: ${response.rejection}")
        }

        fun state(robotId: String, taskId: String): TaskState? =
            mimic.server.exclusive { mimic.instance(robotId)!!.tasks.find(taskId)?.machine?.state }

        /** 태스크가 종료할 때까지 5초씩 민다. 종료한 그 밀기 직후의 가상 시각을 돌려준다. */
        fun runToEnd(robotId: String, taskId: String): Instant {
            repeat(40) {
                advance(Duration.ofSeconds(5))
                if (state(robotId, taskId)?.isTerminal == true) return now()
            }
            error("태스크가 끝나지 않았다: ${state(robotId, taskId)}")
        }

        fun slot(id: String): CellPlace = cell.snapshot.slots.single { it.id == id }

        override fun close() {
            channel.shutdownNow()
            cell.close()
            mimic.server.shutdown()
        }
    }

    @Test
    fun `pick_place 가 성공하면 그 슬롯을 제시 자리의 자재로 채우고 관측 시각은 채운 가상 시각이다`() {
        Bench().use { bench ->
            assertEquals(false, bench.slot(S01).occupied)
            bench.pickPlace(HUMANOID, "JO-1#$S01", SOURCE, S01)

            val doneAt = bench.runToEnd(HUMANOID, "JO-1#$S01")

            assertEquals(TaskState.SUCCEEDED, bench.state(HUMANOID, "JO-1#$S01"))
            assertEquals(CellPlace(S01, true, MATERIAL, doneAt), bench.slot(S01))
            // 다른 슬롯과 제시 자리는 그대로다.
            assertTrue(bench.cell.snapshot.slots.filter { it.id != S01 }.none { it.occupied })
            assertEquals(listOf(CellPlace(SOURCE, true, MATERIAL, null)), bench.cell.snapshot.presentations)
        }
    }

    @Test
    fun `같은 태스크는 한 번만 채운다`() {
        Bench().use { bench ->
            bench.pickPlace(HUMANOID, "JO-1#$S01", SOURCE, S01)
            val doneAt = bench.runToEnd(HUMANOID, "JO-1#$S01")

            // 끝난 태스크가 엔진에 남아 있는 채로 더 민다. 다시 처리하면 관측 시각이 뒤로 옮겨진다.
            bench.advance(Duration.ofSeconds(30))
            bench.advance(Duration.ofSeconds(30))

            assertEquals(doneAt, bench.slot(S01).observedAt)
        }
    }

    @Test
    fun `셀 밖 목적지와 제시 자리가 아닌 출발지는 각자 규칙대로 다룬다`() {
        Bench().use { bench ->
            // 셀 밖 목적지: 아무 슬롯도 안 바뀐다.
            bench.pickPlace(HUMANOID, "JO-2#dock-3", SOURCE, "dock-3")
            bench.runToEnd(HUMANOID, "JO-2#dock-3")
            assertTrue(bench.cell.snapshot.slots.none { it.occupied })

            // 제시 자리가 아닌 출발지: 놓였다는 보고는 맞으므로 점유로 채우되 자재는 모른다.
            bench.pickPlace(HUMANOID, "JO-3#$S02", "box-7", S02)
            val doneAt = bench.runToEnd(HUMANOID, "JO-3#$S02")
            assertEquals(CellPlace(S02, true, null, doneAt), bench.slot(S02))
        }
    }

    @Test
    fun `GET cell 은 제시 자리와 슬롯을 내고 다른 방법과 경로는 받지 않는다`() {
        Bench().use { bench ->
            bench.pickPlace(HUMANOID, "JO-1#$S01", SOURCE, S01)
            val doneAt = bench.runToEnd(HUMANOID, "JO-1#$S01")

            val base = "http://127.0.0.1:${bench.cell.port}"
            val got = http.send(HttpRequest.newBuilder(URI.create("$base/cell")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, got.statusCode())
            assertEquals("application/json", got.headers().firstValue("Content-Type").orElse(null))
            val body = json.readTree(got.body())
            assertEquals(setOf("presentations", "slots"), body.fieldNames().asSequence().toSet())

            val source = body["presentations"].single()
            assertEquals(SOURCE, source["id"].asText())
            assertEquals(true, source["occupied"].asBoolean())
            assertEquals(MATERIAL, source["material"].asText())
            assertTrue(source["observedAt"].isNull)

            val slots = body["slots"]
            assertEquals(CellFixture.STANDARD.slots, slots.map { it["id"].asText() })
            assertEquals(doneAt.toString(), slots[0]["observedAt"].asText())
            assertEquals(MATERIAL, slots[0]["material"].asText())
            assertEquals(false, slots[1]["occupied"].asBoolean())
            assertTrue(slots[1]["material"].isNull)
            assertTrue(slots[1]["observedAt"].isNull)

            val post = http.send(
                HttpRequest.newBuilder(URI.create("$base/cell")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(405, post.statusCode())
            val other = http.send(HttpRequest.newBuilder(URI.create("$base/cells")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(404, other.statusCode())
        }
    }

    @Test
    fun `처음 스냅숏은 슬롯이 비고 제시 자리가 찬 픽스처 그대로다`() {
        Bench().use { bench ->
            val snapshot = bench.cell.snapshot
            assertEquals(CellFixture.STANDARD.slots.map { CellPlace(it, false, null, null) }, snapshot.slots)
            assertNotNull(snapshot.presentations.singleOrNull { it.id == SOURCE && it.occupied })
            assertNull(snapshot.presentations.single().observedAt)
        }
    }

    private companion object {
        const val HUMANOID = "humanoid-01"
        const val QUADRUPED = "quadruped-01"
        const val SOURCE = "SEQ-IN-02.BIN-A"
        const val MATERIAL = "ENGINE-COVER-A"
        const val S01 = "RACK-204.S01"
        const val S02 = "RACK-204.S02"
        val START: Instant = Instant.parse("2026-10-08T00:00:00Z")
    }
}
