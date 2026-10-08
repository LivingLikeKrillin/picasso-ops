package dev.picasso.ops.host

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 실행 호스트는 registry 를 부르지 않으므로 적재 토큰도 운영자 토큰도 받지 않는다(S3a 스펙 §7.1). */
class HostEnvBoundaryTest {

    @Test
    fun `실행 호스트는 두 토큰을 받지 않고 포트 값은 받는다`() {
        assertNull(System.getenv("PICASSO_INGEST_TOKEN"))
        assertNull(System.getenv("PICASSO_OPERATOR_TOKEN"))
        // 값을 시험에 다시 적지 않고 루트 .env 에서 읽어 맞댄다.
        val env = Files.readAllLines(HostBench.ROOT.resolve(".env"))
        listOf("HOST_PORT", "MIMIC_GRPC_PORT", "SITE_CELL_PORT").forEach { key ->
            val value = env.single { it.startsWith("$key=") }.substringAfter("=").trim()
            assertEquals(value, System.getenv(key), key)
        }
    }
}
