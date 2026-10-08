package dev.picasso.ops.service

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
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

/** 현장 설정 버전 표(S2 스펙 §5). */
class SiteSettingsStoreTest {

    private val dataSource = DriverManagerDataSource(
        PostgresSupport.jdbcUrl,
        PostgresSupport.username,
        PostgresSupport.password,
    )
    private val store = SiteSettingsStore(JdbcClient.create(dataSource))
    private val lee = Actor(Mode.ENGINEER, "lee")

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
        store.insert(2, 60, lee, "시험")
        store.insert(3, 120, lee, "되돌림")
        assertEquals(listOf(3L, 2L, 1L), store.history().map { it.version })
        assertEquals(3L, store.current().version)
        assertEquals(Duration.ofSeconds(120), store.current().connectionThreshold)
    }

    @Test
    fun `같은 버전 번호는 두 번 들어가지 않는다`() {
        store.insert(2, 60, lee, "시험")
        assertFailsWith<DuplicateKeyException> { store.insert(2, 120, lee, "다른 변경") }
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
    fun `허용 범위는 60초 이상 3600초 이하다`() {
        assertFalse(SiteSettingsRange.allows(59))
        assertTrue(SiteSettingsRange.allows(60))
        assertTrue(SiteSettingsRange.allows(3600))
        assertFalse(SiteSettingsRange.allows(3601))
        // 첫 버전의 값도 범위 안이다.
        assertTrue(SiteSettingsRange.allows(store.latest().connectionThresholdSeconds.toLong()))
    }
}
