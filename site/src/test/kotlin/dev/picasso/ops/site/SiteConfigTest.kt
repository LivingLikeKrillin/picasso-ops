package dev.picasso.ops.site

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SiteConfigTest {

    private val root = Path.of("..").toAbsolutePath().normalize()

    private val env = mapOf(
        "SITE_ID" to "site-x",
        "PICASSO_DB_URL" to "jdbc:postgresql://h/db",
        "PICASSO_DB_USER" to "u",
        "PICASSO_DB_PASSWORD" to "p",
        "REGISTRY_PORT" to "8781",
        "MIMIC_GRPC_PORT" to "8783",
        "SITE_CELL_PORT" to "8784",
        "PICASSO_OPERATOR_TOKEN" to "op",
        "PICASSO_INGEST_TOKEN" to "in",
    )

    @Test
    fun `환경 변수와 명부에서 설정을 만든다`() {
        val config = SiteConfig.fromEnv(env, root)
        assertEquals("site-x", config.siteId)
        assertEquals(8781, config.registryPort)
        assertEquals(8783, config.mimicPort)
        assertEquals(8784, config.cellPort)
        assertEquals(CellFixture.STANDARD, config.cell)
        assertEquals(DbConfig("jdbc:postgresql://h/db", "u", "p"), config.db)
        assertEquals(2, config.roster.size)
        assertEquals(root.resolve(SiteConfig.PROFILE_SCHEMA), config.schema)
    }

    @Test
    fun `SITE_ID 가 없으면 기동하지 않는다`() {
        val e = assertFailsWith<IllegalArgumentException> { SiteConfig.fromEnv(env - "SITE_ID", root) }
        assertTrue("SITE_ID" in e.message!!, e.message)
    }

    @Test
    fun `포트가 정수가 아니면 기동하지 않는다`() {
        assertFailsWith<IllegalArgumentException> { SiteConfig.fromEnv(env + ("REGISTRY_PORT" to "x"), root) }
        assertFailsWith<IllegalArgumentException> { SiteConfig.fromEnv(env + ("MIMIC_GRPC_PORT" to "x"), root) }
        assertFailsWith<IllegalArgumentException> { SiteConfig.fromEnv(env + ("SITE_CELL_PORT" to "x"), root) }
    }

    @Test
    fun `mimic 포트나 셀 대역 포트가 없으면 기동하지 않는다`() {
        // 실행 호스트가 붙을 자리다. 빠진 채 무작위 포트로 뜨면 호스트가 엉뚱한 곳을 두드린다.
        listOf("MIMIC_GRPC_PORT", "SITE_CELL_PORT").forEach { key ->
            val e = assertFailsWith<IllegalArgumentException> { SiteConfig.fromEnv(env - key, root) }
            assertTrue(key in e.message!!, e.message)
        }
    }

    @Test
    fun `루트 env 의 SITE_ID 가 시험 환경 변수로 넘어온다`() {
        // Gradle 이 루트 .env 를 환경 변수로 넘긴다(루트 build.gradle.kts). 어긋나면 한 출처가 끊긴 것이다.
        val fromFile = java.nio.file.Files.readAllLines(root.resolve(".env"))
            .single { it.startsWith("SITE_ID=") }
            .substringAfter("=")
            .trim()
        assertTrue(fromFile.isNotEmpty())
        assertEquals(fromFile, System.getenv("SITE_ID"))
    }

    @Test
    fun `site 만 적재 토큰을 받는다`() {
        assertTrue(System.getenv("PICASSO_INGEST_TOKEN").isNullOrEmpty().not())
    }
}
