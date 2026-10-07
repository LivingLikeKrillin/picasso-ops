package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.SQLException
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OperationLogTest {

    private val dataSource = DriverManagerDataSource(
        PostgresSupport.jdbcUrl,
        PostgresSupport.username,
        PostgresSupport.password,
    )
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val json = ObjectMapper()
    private val kim = Actor(Mode.OPERATOR, "kim")

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    @Test
    fun `칸은 9개다`() {
        val columns = PostgresSupport.queryAll(
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'ops' AND table_name = 'operation_log' ORDER BY ordinal_position
            """.trimIndent(),
        ) { it.getString(1) }
        assertEquals(
            listOf(
                "request_id", "mode", "actor_user", "target", "request",
                "reason", "result", "registry_response", "recorded_at",
            ),
            columns,
        )
    }

    @Test
    fun `조작 한 건을 그대로 남긴다`() {
        val id = UUID.randomUUID()
        log.append(id, kim, "robot humanoid-01", """{"reason":"정비"}""", "정비", OperationResult.SUCCEEDED, """{"status":200}""")
        val record = log.list().single()
        assertEquals(id, record.requestId)
        assertEquals(Mode.OPERATOR, record.mode)
        assertEquals("kim", record.user)
        assertEquals("robot humanoid-01", record.target)
        assertEquals(json.readTree("""{"reason":"정비"}"""), json.readTree(record.request))
        assertEquals("정비", record.reason)
        assertEquals(OperationResult.SUCCEEDED, record.result)
        assertEquals(json.readTree("""{"status":200}"""), json.readTree(record.registryResponse))
    }

    @Test
    fun `응답 없음 뒤 재조회는 같은 요청 id 로 새 행을 붙이고 처음 행을 남긴다`() {
        val id = UUID.randomUUID()
        log.append(id, kim, "robot r1", "{}", "정비", OperationResult.NO_RESPONSE, null)
        log.append(id, kim, "robot r1", "{}", "정비", OperationResult.CONFIRMED_APPLIED, null)
        val rows = log.list().filter { it.requestId == id }
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
    }

    @Test
    fun `조작 기록은 고칠 수 없다`() {
        log.append(UUID.randomUUID(), kim, "robot r1", "{}", null, OperationResult.REJECTED, null)
        val e = assertFailsWith<SQLException> { PostgresSupport.execute("UPDATE ops.operation_log SET reason = 'x'") }
        assertTrue("덧붙이기만" in e.message!!, e.message)
    }

    @Test
    fun `조작 기록은 지울 수 없다`() {
        log.append(UUID.randomUUID(), kim, "robot r1", "{}", null, OperationResult.REJECTED, null)
        val e = assertFailsWith<SQLException> { PostgresSupport.execute("DELETE FROM ops.operation_log") }
        assertTrue("덧붙이기만" in e.message!!, e.message)
    }

    @Test
    fun `조작 기록은 통째로 비울 수 없다`() {
        log.append(UUID.randomUUID(), kim, "robot r1", "{}", null, OperationResult.REJECTED, null)
        val e = assertFailsWith<SQLException> { PostgresSupport.execute("TRUNCATE ops.operation_log") }
        assertTrue("덧붙이기만" in e.message!!, e.message)
    }

    @Test
    fun `ops 마이그레이션은 ops 스키마에만 들어간다`() {
        val placed = PostgresSupport.queryOne(
            """
            SELECT to_regclass('ops.operation_log')::text, to_regclass('public.operation_log')::text,
                   to_regclass('ops.flyway_schema_history')::text
            """.trimIndent(),
        ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }
        assertEquals(Triple("ops.operation_log", null, "ops.flyway_schema_history"), placed)
    }
}
