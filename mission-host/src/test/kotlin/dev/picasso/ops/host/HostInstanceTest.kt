package dev.picasso.ops.host

import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.registry.PostgresSupport
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실행 호스트 인스턴스의 심장박동과 기동 복원의 범위(HA 설계 기록 §7·§8 단계 0). 복원은 이 인스턴스의 행과 심장박동이 끊긴 인스턴스의
 * 행만 다시 짓고, 살아 있는 다른 인스턴스가 든 행은 미룬다. 다른 인스턴스는 `mission.host_instance` 에 행을 직접 넣어 흉내 낸다.
 */
class HostInstanceTest {

    @Test
    fun `심장박동은 기동 때 행을 넣고 주기마다 DB 시각으로 고치며 닫으면 멈춤을 적어 곧바로 살아 있지 않다`() {
        HostBench(heartbeat = Duration.ofMillis(200) to Duration.ofSeconds(2)).use { bench ->
            val first = bench.host.instanceId
            val started = assertNotNull(instance(first))
            assertTrue(started.live && started.stoppedAt == null, started.toString())
            bench.eventually("심장박동 갱신") { instance(first)!!.lastSeenAt.isAfter(started.lastSeenAt) }
            val beaten = instance(first)!!
            // 만료는 마지막 심장박동에서 만료 폭(2초)만큼 뒤다. 둘 다 DB 시각이다.
            assertEquals(Duration.ofSeconds(2), Duration.between(beaten.lastSeenAt, beaten.expiresAt))

            bench.restartHost()
            val stopped = instance(first)!!
            assertNotNull(stopped.stoppedAt, stopped.toString())
            assertTrue(!stopped.live, stopped.toString())
            val second = instance(bench.host.instanceId)!!
            assertTrue(second.live, second.toString())
            // 멈춘 인스턴스의 행은 되살리지 않는다.
            Thread.sleep(500)
            assertEquals(stopped, instance(first))
        }
    }

    @Test
    fun `심장박동 만료가 주기의 세 배보다 짧으면 기동하지 않는다`() {
        assertFailsWith<IllegalArgumentException> { HostHeartbeat(Duration.ofSeconds(5), Duration.ofSeconds(14)) }
        assertFailsWith<IllegalArgumentException> { HostHeartbeat(Duration.ZERO, Duration.ofSeconds(30)) }
        assertEquals(HostHeartbeat(Duration.ofSeconds(5), Duration.ofSeconds(30)), HostHeartbeat.DEFAULT)
    }

    @Test
    fun `살아 있는 다른 인스턴스가 든 일지 행은 다시 짓지 않고 미루며 그 기체를 판정에서 빼고 그 심장박동이 끊기면 다시 짓는다`() {
        HostBench().use { bench ->
            insertJournal("JO-1", HUMANOID, "mw-other")
            liveInstance("mw-other")
            bench.restartHost()
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals("JO-1" to "DEFERRED", row["jobOrderId"].asText() to row["result"].asText(), row.toString())
            assertEquals("${MissionHost.HELD_BY_LIVE_INSTANCE}: instance=mw-other", row["reason"].asText())
            assertEquals("mw-other" to "exec-7", row["previousInstanceId"].asText() to row["previousExecutionId"].asText())
            assertTrue(bench.get("/host/executions")["executions"].isEmpty)
            assertEquals(listOf("${MissionHost.UNRESTORED_REASON}: JO-1"), reasons(bench, HUMANOID))

            bench.idlePumps()
            assertEquals("DEFERRED", bench.get("/host/executions")["restore"]["rows"].single()["result"].asText())
            assertEquals(listOf("DEFERRED"), events("JO-1"))

            PostgresSupport.execute("UPDATE mission.host_instance SET expires_at = clock_timestamp() - interval '1 second' WHERE instance_id = 'mw-other'")
            bench.eventually("만료 뒤 다시 짓기") { bench.get("/host/executions")["restore"]["rows"].single()["result"].asText() == "RESTORED" }
            val restored = bench.get("/host/executions")["restore"]["rows"].single()
            assertTrue(restored["reason"].isNull, restored.toString())
            assertEquals(listOf("DEFERRED", "RESTORED"), events("JO-1"))
            assertEquals(listOf("도는 실행이 있다: ${restored["executionId"].asText()}"), reasons(bench, HUMANOID))
        }
    }

    @Test
    fun `심장박동이 끊긴 인스턴스와 멈춤을 적은 인스턴스와 행이 없는 인스턴스가 든 일지 행은 곧바로 다시 짓는다`() {
        HostBench().use { bench ->
            insertJournal("JO-1", HUMANOID, "mw-expired")
            PostgresSupport.execute(
                "INSERT INTO mission.host_instance (instance_id, last_seen_at, expires_at) " +
                    "VALUES ('mw-expired', clock_timestamp() - interval '1 minute', clock_timestamp() - interval '1 second')",
            )
            insertJournal("JO-2", QUADRUPED, "mw-stopped")
            liveInstance("mw-stopped")
            PostgresSupport.execute("UPDATE mission.host_instance SET stopped_at = clock_timestamp() WHERE instance_id = 'mw-stopped'")
            insertJournal("JO-3", QUADRUPED, "mw-unknown")

            bench.restartHost()
            val rows = bench.get("/host/executions")["restore"]["rows"]
            assertEquals(
                listOf("JO-1" to "RESTORED", "JO-2" to "RESTORED", "JO-3" to "RESTORED"),
                rows.map { it["jobOrderId"].asText() to it["result"].asText() },
                rows.toString(),
            )
        }
    }

    @Test
    fun `행을 든 인스턴스는 받은 인스턴스가 아니라 마지막으로 다시 지은 인스턴스이고 그 인스턴스가 살아 있으면 가져가지 않는다`() {
        HostBench().use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val receiver = bench.host.instanceId
            // 받은 인스턴스는 닫혀 멈춤을 적었고, 그 뒤 살아 있는 다른 인스턴스가 이 행을 다시 지었다.
            bench.host.close()
            PostgresSupport.execute(
                "INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, execution_id) VALUES ('JO-1', 'RESTORED', 'mw-live', 'exec-3')",
            )
            liveInstance("mw-live")
            bench.restartHost()
            assertTrue(!instance(receiver)!!.live)
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals("DEFERRED", row["result"].asText(), row.toString())
            assertEquals("${MissionHost.HELD_BY_LIVE_INSTANCE}: instance=mw-live", row["reason"].asText())
            assertEquals("mw-live" to "exec-3", row["previousInstanceId"].asText() to row["previousExecutionId"].asText())
            assertTrue(bench.get("/host/executions")["executions"].isEmpty)
        }
    }

    private data class InstanceRow(val lastSeenAt: Instant, val expiresAt: Instant, val stoppedAt: Instant?, val live: Boolean)

    private companion object {
        fun instance(id: String): InstanceRow? = PostgresSupport.queryAll(
            """
            SELECT last_seen_at, expires_at, stopped_at, (stopped_at IS NULL AND expires_at > clock_timestamp())
            FROM mission.host_instance WHERE instance_id = '$id'
            """.trimIndent(),
        ) {
            InstanceRow(
                it.getTimestamp(1).toInstant(), it.getTimestamp(2).toInstant(), it.getTimestamp(3)?.toInstant(), it.getBoolean(4),
            )
        }.singleOrNull()

        fun liveInstance(id: String) = PostgresSupport.execute(
            "INSERT INTO mission.host_instance (instance_id, expires_at) VALUES ('$id', clock_timestamp() + interval '1 hour')",
        )

        fun reasons(bench: HostBench, robotId: String): List<String> =
            bench.post("/host/eligibility", request(inspect("JO-9", "T2" to "dock-3"), "robotIds", robotId)).body!!["robots"].single()["reasons"].map { it.asText() }

        fun events(jobOrderId: String): List<String> = PostgresSupport.queryAll(
            "SELECT kind FROM mission.execution_journal_event WHERE job_order_id = '$jobOrderId' ORDER BY event_id",
        ) { it.getString(1) }

        fun insertJournal(jobOrderId: String, robotId: String, instanceId: String) {
            val order = JSON.writeValueAsString(JSON.readTree(inspect(jobOrderId, "T1" to "bay-7")))
            PostgresSupport.execute(
                """
                INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id)
                VALUES ('$jobOrderId', '$robotId', '$order'::jsonb, 'InspectAsset', NULL, '$instanceId', 'exec-7')
                """.trimIndent(),
            )
        }
    }
}
