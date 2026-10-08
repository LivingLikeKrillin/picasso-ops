package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.OperationOutcome
import dev.picasso.ops.service.settings.SiteSettingsOperations
import dev.picasso.ops.service.settings.SiteSettingsStore
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 현장 설정 변경(S2 스펙 §6.2·§6.3). 새 버전 행과 조작 기록 행이 같이 들어가거나 같이 안 들어간다. */
class SiteSettingsOperationsTest {

    private val dataSource = DriverManagerDataSource(
        PostgresSupport.jdbcUrl,
        PostgresSupport.username,
        PostgresSupport.password,
    )
    private val jdbc = JdbcClient.create(dataSource)
    private val store = SiteSettingsStore(jdbc)
    private val log = OperationLog(jdbc)
    private val at = Instant.parse("2026-10-08T00:00:00Z")
    private val operations = SiteSettingsOperations(
        store, log, TransactionTemplate(DataSourceTransactionManager(dataSource)), Clock.fixed(at, ZoneOffset.UTC),
    )
    private val lee = Actor(Mode.ENGINEER, "lee")
    private val json = ObjectMapper()

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    @Test
    fun `기준 버전이 지금 버전이면 다음 버전을 넣고 조작 기록에 성공 행을 남긴다`() {
        val outcome = operations.change(lee, 1, 60, "시험")
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertNull(outcome.rejection)
        assertNull(outcome.registryStatus)
        val latest = store.latest()
        assertEquals(2L, latest.version)
        assertEquals(60, latest.connectionThresholdSeconds)
        assertEquals("lee", latest.user)
        assertEquals("시험", latest.reason)
        val record = log.list().single()
        assertEquals(outcome.requestId, record.requestId)
        assertEquals(SiteSettingsOperations.TARGET, record.target)
        assertEquals(OperationResult.SUCCEEDED, record.result)
        assertEquals("시험", record.reason)
        assertNull(record.registryResponse)
        assertEquals(
            json.readTree("""{"op":"CHANGE_SITE_SETTINGS","baseVersion":1,"connectionThresholdSeconds":60}"""),
            json.readTree(record.request),
        )
    }

    @Test
    fun `지난 기준 버전 위의 변경은 버전 충돌로 거부하고 조작 기록에 거부 행을 남긴다`() {
        operations.change(lee, 1, 60, "먼저")
        val outcome = operations.change(Actor(Mode.ENGINEER, "park"), 1, 120, "늦게")
        assertEquals(OperationResult.REJECTED, outcome.result)
        val rejection = outcome.rejection!!
        assertEquals(SiteSettingsOperations.VERSION_CONFLICT, rejection.kind)
        assertEquals("현재 버전 2", rejection.observed)
        assertEquals("기준 버전 1", rejection.expected)
        assertEquals(Owner.ENGINEER, rejection.owner)
        assertEquals(true, rejection.inScreen)
        assertEquals(at, rejection.checkedAt)
        assertNull(rejection.target)
        assertNull(rejection.basisVersion)
        assertEquals(60, store.latest().connectionThresholdSeconds)
        assertEquals(listOf(OperationResult.REJECTED, OperationResult.SUCCEEDED), log.list().map { it.result })
    }

    @Test
    fun `아직 없는 버전을 기준으로 보내면 번호를 건너뛰지 않고 거부한다`() {
        val outcome = operations.change(lee, 5, 60, "앞선 기준")
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals("현재 버전 1", outcome.rejection!!.observed)
        assertEquals(listOf(1L), store.history().map { it.version })
    }

    @Test
    fun `같은 번호를 다른 변경이 먼저 커밋하면 기본 키가 막고 버전 충돌로 거부 행을 남긴다`() {
        val pool = Executors.newSingleThreadExecutor()
        dataSource.connection.use { other ->
            other.autoCommit = false
            other.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO ops.site_settings (version, connection_threshold_seconds, mode, actor_user, reason) " +
                        "VALUES (2, 120, 'ENGINEER', 'park', '먼저')",
                )
            }
            val pending = pool.submit<OperationOutcome> { operations.change(lee, 1, 60, "늦게") }
            // 변경이 버전 2 의 기본 키 잠금을 기다릴 때까지 본다. 그 전에 커밋하면 비교 경로로 간다.
            val deadline = System.nanoTime() + 10_000_000_000
            while (PostgresSupport.queryOne(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'INSERT INTO ops.site_settings%'",
                ) { it.getInt(1) } == 0
            ) {
                check(System.nanoTime() < deadline) { "변경이 잠금을 기다리지 않았다" }
                Thread.sleep(20)
            }
            other.commit()
            val outcome = pending.get()
            assertEquals(OperationResult.REJECTED, outcome.result)
            assertEquals("현재 버전 2", outcome.rejection!!.observed)
        }
        pool.shutdownNow()
        assertEquals(120, store.latest().connectionThresholdSeconds)
        assertEquals(listOf(OperationResult.REJECTED), log.list().map { it.result })
    }

    @Test
    fun `범위 밖 값은 관문이 막으므로 여기까지 오면 계약 위반이다`() {
        assertFailsWith<IllegalArgumentException> { operations.change(lee, 1, 59, "범위 밖") }
        assertEquals(listOf(1L), store.history().map { it.version })
        assertEquals(emptyList(), log.list())
    }
}
