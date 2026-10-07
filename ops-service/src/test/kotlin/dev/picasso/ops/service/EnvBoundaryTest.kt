package dev.picasso.ops.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 적재 토큰은 mimic 이 있는 site 만 쥔다(스펙 §4). 운영 서비스에는 넘어오지 않는다. */
class EnvBoundaryTest {

    @Test
    fun `운영 서비스는 적재 토큰을 받지 않고 나머지 env 값은 받는다`() {
        assertNull(System.getenv("PICASSO_INGEST_TOKEN"))
        // 값을 시험에 다시 적지 않고 루트 .env 에서 읽어 맞댄다.
        val siteId = Files.readAllLines(Path.of("..").toAbsolutePath().normalize().resolve(".env"))
            .single { it.startsWith("SITE_ID=") }
            .substringAfter("=")
            .trim()
        assertEquals(siteId, System.getenv("SITE_ID"))
    }
}
