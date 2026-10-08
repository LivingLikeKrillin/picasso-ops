package dev.picasso.ops.host

import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 실행 호스트의 현장 시간값 읽기와 적용(S3c 스펙 §7.1, §9, §10 의 호스트 행). 뷰는 [HostBench] 의 대역 뷰다.
 */
class SiteTimingsHostTest {

    @Test
    fun `첫 읽기 전에는 판정이 기체마다 미적용 이유를 더하고 제출은 사유 없는 UNASSIGNED 다`() {
        HostBench(timings = null).use { bench ->
            val state = bench.get("/host/site-timings")
            assertTrue(state["applied"].isNull, state.toString())
            assertTrue(state["appliedAt"].isNull, state.toString())
            assertTrue("site_timings_current" in state["readError"].asText(), state.toString())
            assertTrue(!state["lastReadAt"].isNull, state.toString())

            val judged = bench.post("/host/eligibility", request(inspect("JO-1", "T1" to "bay-7"), "robotIds", HUMANOID, QUADRUPED))
                .body!!["robots"]
            judged.forEach { row ->
                assertEquals("FIT", row["skillFit"].asText(), row.toString())
                assertEquals(false, row["passed"].asBoolean(), row.toString())
                assertEquals(listOf(MissionHost.UNAPPLIED_REASON), row["reasons"].map { it.asText() })
            }

            val submitted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("UNASSIGNED", submitted["result"].asText(), submitted.toString())
            assertEquals(0, submitted["refusals"].size())
            assertEquals(listOf(MissionHost.UNAPPLIED_REASON), submitted["excluded"].single()["reasons"].map { it.asText() })
            // 기체가 하나도 넘어가지 않으므로 요구 근거 등급을 넘는 작업 지시도 REJECTED 가 아니라 UNASSIGNED 다. 새 결과 값은 없다.
            val beyond = bench.post("/host/job-orders", request(inspect("JO-2", "T1" to "bay-7", evidence = "E2"), "candidates", HUMANOID))
                .body!!
            assertEquals("UNASSIGNED", beyond["result"].asText(), beyond.toString())
            assertEquals(0, bench.get("/host/executions")["executions"].size())
            // 시간값을 쓰지 않는 경로는 그대로다.
            assertEquals(200, bench.fetch("/host/missions/PrepareSequencedRack").status)
            assertEquals(200, bench.fetch("/host/cell").status)

            // 뷰가 생기면 1초 주기 읽기가 적용하고 같은 작업 지시가 선다.
            bench.timingsView(HostBench.STANDARD_TIMINGS)
            val applied = bench.awaitApplied(1)
            assertTrue(applied["readError"].isNull, applied.toString())
            val accepted = bench.post("/host/job-orders", request(inspect("JO-3", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
        }
    }

    @Test
    fun `기동 안의 첫 읽기로 버전과 값을 적용한다`() {
        // 주기를 한 시간으로 둔다. 기동이 1초를 넘겨도 주기 읽기가 끼지 않으므로, 적용은 기동 안의 첫 읽기에서만 온다.
        HostBench(readInterval = Duration.ofHours(1)).use { bench ->
            val first = bench.get("/host/site-timings")
            assertEquals(
                HostBench.JSON.readTree(
                    """{"version":1,"evidenceBeforeSeconds":30,"evidenceAfterSeconds":15,"inDoubtGraceSeconds":60,"stallWindowSeconds":300}""",
                ),
                first["applied"],
            )
            assertTrue(!first["appliedAt"].isNull && first["readError"].isNull && first["rejected"].isNull, first.toString())
            assertEquals(first["appliedAt"].asText(), first["lastReadAt"].asText())
        }
    }

    @Test
    fun `새 버전은 다음 주기 읽기에서 적용한다`() {
        HostBench().use { bench ->
            val first = bench.awaitApplied(1)

            // 호스트 시계는 mimic 의 가상 시계라 밀어야 적용 시각이 달라진다.
            bench.mimic.server.advance(Duration.ofSeconds(5))
            bench.timingsView(listOf(2, 40, 20, 90, 600))
            val second = bench.awaitApplied(2)
            assertEquals(listOf(40L, 20L, 90L, 600L), listOf("evidenceBeforeSeconds", "evidenceAfterSeconds", "inDoubtGraceSeconds", "stallWindowSeconds").map { second["applied"][it].asLong() })
            assertNotEquals(first["appliedAt"].asText(), second["appliedAt"].asText())
            assertTrue(!Instant.parse(second["lastReadAt"].asText()).isBefore(Instant.parse(second["appliedAt"].asText())), second.toString())
        }
    }

    @Test
    fun `범위 밖 행은 적용하지 않고 마지막 버전을 유지하며 버전과 이유를 보인다`() {
        HostBench().use { bench ->
            bench.timingsView(listOf(2, 30, 15, 60, 3601))
            val rejected = bench.awaitTimings { !it["rejected"].isNull }
            assertEquals(1, rejected["applied"]["version"].asLong(), rejected.toString())
            assertEquals(2, rejected["rejected"]["version"].asLong())
            assertEquals(listOf("stallWindow: 30~3600 초 밖이다 (3601)"), rejected["rejected"]["reasons"].map { it.asText() })
            assertTrue(rejected["readError"].isNull, rejected.toString())
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())

            // 적용할 수 있는 다음 버전을 읽으면 적용하고 거부 표시를 지운다.
            bench.timingsView(listOf(3, 30, 15, 60, 3600))
            val next = bench.awaitApplied(3)
            assertTrue(next["rejected"].isNull, next.toString())
        }
    }

    @Test
    fun `읽기가 실패하면 마지막 버전을 계속 쓰고 실패를 보이며 다시 읽히면 지운다`() {
        HostBench().use { bench ->
            bench.dropTimingsView()
            val failing = bench.awaitTimings { !it["readError"].isNull }
            assertEquals(1, failing["applied"]["version"].asLong(), failing.toString())
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())

            bench.mimic.server.advance(Duration.ofSeconds(5))
            bench.timingsView(HostBench.STANDARD_TIMINGS)
            val recovered = bench.awaitTimings { it["readError"].isNull }
            assertEquals(1, recovered["applied"]["version"].asLong(), recovered.toString())
            // 같은 버전이라 다시 적용하지 않는다.
            assertEquals(failing["appliedAt"].asText(), recovered["appliedAt"].asText())
        }
    }
}
