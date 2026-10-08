package dev.picasso.ops.service

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.settings.SiteSettingsFields
import dev.picasso.ops.service.settings.SiteSettingsRange
import dev.picasso.ops.service.settings.SiteSettingsStore
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.SQLException
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 현장 설정 버전 표(S2 스펙 §5)와 시간값 칸·현재 버전 뷰(S3c 스펙 §6.1·§6.3). */
class SiteSettingsStoreTest {

    private val dataSource = DriverManagerDataSource(
        PostgresSupport.jdbcUrl,
        PostgresSupport.username,
        PostgresSupport.password,
    )
    private val store = SiteSettingsStore(JdbcClient.create(dataSource))
    private val lee = Actor(Mode.ENGINEER, "lee")

    private fun threshold(seconds: Int) = SiteSettingsFields(seconds, 30, 15, 60, 300)

    private fun view(): List<List<Long>> = PostgresSupport.queryAll(
        "SELECT version, evidence_before_seconds, evidence_after_seconds, in_doubt_grace_seconds, stall_window_seconds " +
            "FROM ops.site_timings_current",
    ) { rs -> (1..5).map { rs.getLong(it) } }

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    @Test
    fun `마이그레이션이 S1 설정값 90초를 버전 1 로 넣는다`() {
        val first = store.latest()
        assertEquals(1L, first.version)
        assertEquals(90, first.connectionThresholdSeconds)
        assertEquals(Mode.ENGINEER, first.mode)
        assertEquals("system", first.user)
        assertEquals("S1 설정값 이전", first.reason)
        assertEquals(Duration.ofSeconds(90), store.current().connectionThreshold)
        assertEquals(1L, store.current().version)
    }

    @Test
    fun `현재 버전은 가장 큰 버전이고 이력은 최신부터다`() {
        store.insert(2, threshold(60), lee, "시험")
        store.insert(3, threshold(120), lee, "되돌림")
        assertEquals(listOf(3L, 2L, 1L), store.history().map { it.version })
        assertEquals(3L, store.current().version)
        assertEquals(Duration.ofSeconds(120), store.current().connectionThreshold)
    }

    @Test
    fun `같은 버전 번호는 두 번 들어가지 않는다`() {
        store.insert(2, threshold(60), lee, "시험")
        assertFailsWith<DuplicateKeyException> { store.insert(2, threshold(120), lee, "다른 변경") }
        assertEquals(60, store.latest().connectionThresholdSeconds)
    }

    @Test
    fun `현장 설정은 고칠 수 없다`() {
        val e = assertFailsWith<SQLException> {
            PostgresSupport.execute("UPDATE ops.site_settings SET connection_threshold_seconds = 1")
        }
        assertTrue("덧붙이기만" in e.message!!, e.message)
    }

    @Test
    fun `현장 설정은 지울 수 없다`() {
        val e = assertFailsWith<SQLException> { PostgresSupport.execute("DELETE FROM ops.site_settings") }
        assertTrue("덧붙이기만" in e.message!!, e.message)
    }

    @Test
    fun `현장 설정은 통째로 비울 수 없다`() {
        val e = assertFailsWith<SQLException> { PostgresSupport.execute("TRUNCATE ops.site_settings") }
        assertTrue("덧붙이기만" in e.message!!, e.message)
    }

    @Test
    fun `V4 는 기존 행을 picasso 기본값으로 채우고 기본값을 지워 새 칸을 빠뜨린 INSERT 를 막는다`() {
        assertEquals(SiteSettingsFields(90, 30, 15, 60, 300), store.latest().fields())
        val e = assertFailsWith<SQLException> {
            PostgresSupport.execute(
                "INSERT INTO ops.site_settings (version, connection_threshold_seconds, mode, actor_user, reason) " +
                    "VALUES (2, 60, 'ENGINEER', 'lee', '빠뜨림')",
            )
        }
        assertTrue("null value" in e.message!!, e.message)
        // DB 제약은 0 보다 큼만 둔다. 범위는 운영 서비스 상수가 쥔다.
        assertFailsWith<SQLException> {
            PostgresSupport.execute(
                "INSERT INTO ops.site_settings (version, connection_threshold_seconds, evidence_before_seconds, evidence_after_seconds, " +
                    "in_doubt_grace_seconds, stall_window_seconds, mode, actor_user, reason) VALUES (2, 60, 0, 15, 60, 300, 'ENGINEER', 'lee', '영')",
            )
        }
        store.insert(2, SiteSettingsFields(60, 121, 15, 60, 300), lee, "범위는 DB 가 보지 않는다")
        assertEquals(121, store.latest().evidenceBeforeSeconds)
    }

    @Test
    fun `현재 버전 뷰는 가장 큰 버전 한 행의 버전과 시간값 넷이다`() {
        assertEquals(listOf(listOf(1L, 30L, 15L, 60L, 300L)), view())
        store.insert(2, SiteSettingsFields(60, 40, 20, 90, 600), lee, "시간값")
        store.insert(3, SiteSettingsFields(60, 50, 25, 120, 900), lee, "다시")
        assertEquals(listOf(listOf(3L, 50L, 25L, 120L, 900L)), view())
        // 칸 이름과 형이 실행 호스트와의 계약이다(S3c 스펙 T7).
        val columns = PostgresSupport.queryAll(
            "SELECT column_name, data_type FROM information_schema.columns " +
                "WHERE table_schema = 'ops' AND table_name = 'site_timings_current' ORDER BY ordinal_position",
        ) { rs -> rs.getString(1) to rs.getString(2) }
        assertEquals(
            listOf(
                "version" to "bigint",
                "evidence_before_seconds" to "integer",
                "evidence_after_seconds" to "integer",
                "in_doubt_grace_seconds" to "integer",
                "stall_window_seconds" to "integer",
            ),
            columns,
        )
    }

    @Test
    fun `시간값 넷의 허용 범위는 picasso 범위의 사본이고 기본값이 범위 안이다`() {
        val ranges = SiteSettingsRange.FIELDS.toMap()
        assertEquals(5..120, ranges.getValue("evidenceBeforeSeconds"))
        assertEquals(5..120, ranges.getValue("evidenceAfterSeconds"))
        assertEquals(10..600, ranges.getValue("inDoubtGraceSeconds"))
        assertEquals(30..3600, ranges.getValue("stallWindowSeconds"))
        assertEquals(60..3600, ranges.getValue("connectionThresholdSeconds"))
        assertEquals(null, SiteSettingsRange.problem("stallWindowSeconds", 3600))
        assertEquals("stallWindowSeconds 3601초는 범위 밖이다. 30~3600초여야 한다", SiteSettingsRange.problem("stallWindowSeconds", 3601))
        assertEquals("evidenceBeforeSeconds 4초는 범위 밖이다. 5~120초여야 한다", SiteSettingsRange.problem("evidenceBeforeSeconds", 4))
        val first = store.latest()
        SiteSettingsRange.FIELDS.map { it.first }
            .zip(first.fields().let { listOf(it.connectionThresholdSeconds, it.evidenceBeforeSeconds, it.evidenceAfterSeconds, it.inDoubtGraceSeconds, it.stallWindowSeconds) })
            .forEach { (field, value) -> assertEquals(null, SiteSettingsRange.problem(field, value.toLong()), field) }
    }

    @Test
    fun `허용 범위는 60초 이상 3600초 이하다`() {
        assertFalse(SiteSettingsRange.allows(59))
        assertTrue(SiteSettingsRange.allows(60))
        assertTrue(SiteSettingsRange.allows(3600))
        assertFalse(SiteSettingsRange.allows(3601))
        // 첫 버전의 값도 범위 안이다.
        assertTrue(SiteSettingsRange.allows(store.latest().connectionThresholdSeconds.toLong()))
    }
}
