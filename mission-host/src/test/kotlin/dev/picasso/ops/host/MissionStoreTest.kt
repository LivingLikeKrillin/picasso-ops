package dev.picasso.ops.host

import dev.picasso.ops.host.store.HostSchema
import dev.picasso.ops.host.store.MissionStore
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.SQLException
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `mission` 스키마의 표 셋(S3b 스펙 §6.1, T3). 호스트를 띄우지 않고 마이그레이션과 저장만 본다. */
class MissionStoreTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val store = MissionStore(JdbcClient.create(dataSource))

    @BeforeTest
    fun freshSchema() {
        PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
        assertEquals(2, HostSchema.flyway(dataSource).migrate().migrationsExecuted)
    }

    private fun draft(workMaster: String = PSR) = store.saveDraft(workMaster, "{}", "lee", UUID.randomUUID())

    @Test
    fun `mission 마이그레이션은 mission 스키마에만 들어간다`() {
        val placed = PostgresSupport.queryAll(
            """
            SELECT table_name FROM information_schema.tables WHERE table_schema = 'mission' ORDER BY table_name
            """.trimIndent(),
        ) { it.getString(1) }
        assertEquals(
            listOf(
                "draft", "execution_journal", "execution_journal_event", "flyway_schema_history", "incident_copy", "incident_copy_resolution",
                "job_response_log", "mission_version", "mock_run",
            ),
            placed,
        )
        assertNull(PostgresSupport.queryOne("SELECT to_regclass('public.mission_version')::text") { it.getString(1) })
    }

    @Test
    fun `표 셋은 고칠 수도 지울 수도 통째로 비울 수도 없다`() {
        val draft = draft()
        store.saveMockRun(draft.draftId, true, """{"passed":true}""", UUID.randomUUID(), store.now())
        store.insertVersion(PSR, draft.draftId, "{}", "lee", "사유", UUID.randomUUID())
        listOf("draft" to "saved_by = 'x'", "mock_run" to "passed = false", "mission_version" to "reason = 'x'").forEach { (table, set) ->
            listOf("UPDATE mission.$table SET $set", "DELETE FROM mission.$table", "TRUNCATE mission.$table CASCADE").forEach { sql ->
                val e = assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) }
                assertTrue("덧붙이기만" in e.message!!, "$sql: ${e.message}")
            }
        }
        assertEquals(1, store.versions(PSR).size)
        assertEquals(1, store.drafts(PSR, 10).size)
    }

    @Test
    fun `S4b 표 다섯도 고칠 수도 지울 수도 통째로 비울 수도 없다`() {
        PostgresSupport.execute(
            "INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, instance_id, execution_id) " +
                "VALUES ('JO-1', 'r', '{}', 'InspectAsset', 'mw-1', 'exec-1')",
        )
        PostgresSupport.execute("INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, execution_id, detail) VALUES ('JO-1', 'SETTLED', 'mw-1', 'exec-1', 'FAILED')")
        PostgresSupport.execute(
            "INSERT INTO mission.job_response_log (instance_id, job_response_id, job_order_id, execution_id, version, physical_state, required_evidence, " +
                "reached_evidence, completed_units, unverified_units, incomplete_units, in_doubt_units, operator_required, residual_hold, blocked_by, disposition) " +
                "VALUES ('mw-1', 'resp-1', 'JO-1', 'exec-1', 1, 'FAILED', 'E0', 'E0', '[]', '[]', '[]', '[]', false, 'HOLD_KIND_EMPTY', '[]', 'SENT')",
        )
        PostgresSupport.execute(
            "INSERT INTO mission.incident_copy (instance_id, incident_id, execution_id, job_order_id, unit_id, detail) VALUES ('mw-1', 'incident-1', 'exec-1', 'JO-1', 'u', '{}')",
        )
        PostgresSupport.execute(
            "INSERT INTO mission.incident_copy_resolution (instance_id, incident_id, decision, decided_at, wall_clock_at, decided_by_id, decided_by_kind) " +
                "VALUES ('mw-1', 'incident-1', 'REWORK', now(), now(), 'kim', 'PERSON')",
        )
        listOf(
            "execution_journal" to "robot_id = 'x'", "execution_journal_event" to "detail = 'x'", "job_response_log" to "disposition = 'SENT'",
            "incident_copy" to "unit_id = 'x'", "incident_copy_resolution" to "decision = 'x'",
        ).forEach { (table, set) ->
            listOf("UPDATE mission.$table SET $set", "DELETE FROM mission.$table", "TRUNCATE mission.$table CASCADE").forEach { sql ->
                val e = assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) }
                assertTrue("실행 일지·송신 기록·인시던트 사본은 덧붙이기만 한다" in e.message!!, "$sql: ${e.message}")
            }
        }
        // 같은 인스턴스·응답 id 는 두 번 적지 못한다. 일지 이벤트 종류는 넷뿐이다.
        assertFailsWith<SQLException> {
            PostgresSupport.execute(
                "INSERT INTO mission.job_response_log (instance_id, job_response_id, job_order_id, execution_id, version, physical_state, required_evidence, " +
                    "reached_evidence, completed_units, unverified_units, incomplete_units, in_doubt_units, operator_required, residual_hold, blocked_by, disposition) " +
                    "VALUES ('mw-1', 'resp-1', 'JO-1', 'exec-1', 1, 'FAILED', 'E0', 'E0', '[]', '[]', '[]', '[]', false, 'HOLD_KIND_EMPTY', '[]', 'SENT')",
            )
        }
        assertFailsWith<SQLException> {
            PostgresSupport.execute("INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, detail) VALUES ('JO-1', 'FORGOTTEN', 'mw-1', 'x')")
        }
    }

    @Test
    fun `임무 버전 하나를 WorkMaster 와 번호로 읽고 없으면 null 이다`() {
        val draft = draft()
        store.insertVersion(PSR, draft.draftId, "v1", "lee", "r", UUID.randomUUID())
        store.insertVersion(PSR, draft.draftId, "v2", "lee", "r", UUID.randomUUID())
        assertEquals("v1", store.version(PSR, 1)!!.definition)
        assertEquals("v2", store.version(PSR, 2)!!.definition)
        assertNull(store.version(PSR, 3))
        assertNull(store.version("OtherWorkMaster", 1))
    }

    @Test
    fun `버전 번호는 WorkMaster 마다 가장 큰 번호 더하기 1 이고 활성 버전은 WorkMaster 마다 가장 큰 번호다`() {
        val a = draft()
        val b = draft("OtherWorkMaster")
        assertEquals(1, store.insertVersion(PSR, a.draftId, "v1", "lee", "r", UUID.randomUUID()).version)
        assertEquals(2, store.insertVersion(PSR, a.draftId, "v2", "lee", "r", UUID.randomUUID()).version)
        assertEquals(1, store.insertVersion("OtherWorkMaster", b.draftId, "o1", "lee", "r", UUID.randomUUID()).version)
        assertEquals(3, store.insertVersion(PSR, a.draftId, "v3", "lee", "r", UUID.randomUUID()).version)

        assertEquals(listOf(3, 2, 1), store.versions(PSR).map { it.version })
        assertEquals(mapOf(PSR to "v3", "OtherWorkMaster" to "o1"), store.activeVersions().associate { it.workMasterId to it.definition })
    }

    @Test
    fun `마지막 모의 실행은 그 초안의 가장 늦은 행이고 요청 id 로 행을 다시 찾는다`() {
        val draft = draft()
        val other = draft()
        assertNull(store.lastMockRun(draft.draftId))
        val first = store.saveMockRun(draft.draftId, true, """{"n":1}""", UUID.randomUUID(), store.now())
        val requestId = UUID.randomUUID()
        val second = store.saveMockRun(draft.draftId, false, """{"n":2}""", requestId, store.now())
        store.saveMockRun(other.draftId, true, """{"n":3}""", UUID.randomUUID(), store.now())
        assertEquals(second.mockRunId, store.lastMockRun(draft.draftId)!!.mockRunId)
        assertTrue(second.mockRunId > first.mockRunId)
        assertEquals(false, store.lastMockRun(draft.draftId)!!.passed)

        val found = store.byRequest(requestId)
        assertEquals(second.mockRunId, found.mockRun!!.mockRunId)
        assertNull(found.draft)
        assertEquals(draft.draftId, store.byRequest(draft.requestId).draft!!.draftId)
        assertEquals(false, store.byRequest(UUID.randomUUID()).found)
    }

    private companion object {
        const val PSR = "PrepareSequencedRack"
    }
}
