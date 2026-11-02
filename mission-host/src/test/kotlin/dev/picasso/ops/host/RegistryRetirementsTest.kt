package dev.picasso.ops.host

import dev.picasso.ops.host.retire.RegistryRetirements
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 기체 퇴역 읽기 주기 자체(picasso §15.249). 호스트를 띄우지 않고 [RegistryStub] 에 읽기 주기만 붙인다. */
class RegistryRetirementsTest {

    private val clock = HostClock { Instant.parse("2026-11-02T00:00:00Z") }

    @Test
    fun `퇴역한 기체만 사유와 함께 읽고 사유가 비면 사유 없이 퇴역으로 둔다`() {
        RegistryStub().use { registry ->
            registry.retired = mapOf(HostBench.HUMANOID to "  감속기 교체  ", "humanoid-02" to null, "humanoid-03" to "")
            RegistryRetirements(registry.url, clock).use { reader ->
                reader.readOnce()
                assertEquals(
                    mapOf(
                        HostBench.HUMANOID to "감속기 교체",
                        "humanoid-02" to RegistryRetirements.NO_REASON,
                        "humanoid-03" to RegistryRetirements.NO_REASON,
                    ),
                    reader.retired(),
                )
                assertNull(reader.state().readError)
                assertEquals(clock.now(), reader.state().readAt)
            }
        }
    }

    @Test
    fun `한 번 읽은 뒤 registry 가 안 닿으면 마지막 퇴역을 그대로 두고 실패를 남긴다`() {
        RegistryStub().use { registry ->
            registry.retired = mapOf(HostBench.HUMANOID to "감속기 교체")
            RegistryRetirements(registry.url, clock).use { reader ->
                reader.readOnce()
                registry.status = 503
                reader.readOnce()
                assertEquals(mapOf(HostBench.HUMANOID to "감속기 교체"), reader.retired())
                assertNotNull(reader.state().readError)

                registry.status = 200
                registry.retired = emptyMap()
                reader.readOnce()
                assertEquals(emptyMap(), reader.retired(), "퇴역을 취소하면 다음 읽기에서 풀린다")
                assertNull(reader.state().readError)
            }
        }
    }

    @Test
    fun `한 번도 못 읽었으면 아무 기체도 퇴역으로 두지 않는다`() {
        val port = RegistryStub().use { it.url }
        RegistryRetirements(port, clock).use { reader ->
            reader.readOnce()
            assertEquals(emptyMap(), reader.retired())
            assertNotNull(reader.state().readError)
            assertNull(reader.state().readAt)
        }
    }

    @Test
    fun `본문 모양이 틀리면 퇴역 없음으로 읽지 않고 실패로 남긴다`() {
        RegistryStub().use { registry ->
            registry.retired = mapOf(HostBench.HUMANOID to "감속기 교체")
            RegistryRetirements(registry.url, clock).use { reader ->
                reader.readOnce()
                registry.body = """{"robots":[]}"""
                reader.readOnce()
                assertEquals(mapOf(HostBench.HUMANOID to "감속기 교체"), reader.retired())
                assertTrue(reader.state().readError!!.contains("배열"), reader.state().readError)
            }
        }
    }

    @Test
    fun `주소가 비면 읽지 않고 붙이지 않는다`() {
        RegistryRetirements("  ", clock).start().use { reader ->
            assertFalse(reader.declared)
            reader.readOnce()
            assertEquals(emptyMap(), reader.retired())
            assertNull(reader.state().lastReadAt)
        }
    }
}
