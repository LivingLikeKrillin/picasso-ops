package dev.picasso.ops.e2e

import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 스택 없이 도는 진단 도구 시험. 덤프 형식과 클래스당 상한. */
class SlowCallDiagnosticTest {
    @Test
    fun `덤프는 머리말과 잠금 소유자와 쥔 모니터를 적는다`() {
        val lock = Any()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val owner = Thread({ synchronized(lock) { held.countDown(); release.await() } }, "diag-owner").apply { isDaemon = true; start() }
        held.await()
        val waiter = Thread({ blocked.countDown(); synchronized(lock) { } }, "diag-waiter").apply { isDaemon = true; start() }
        blocked.await()
        while (waiter.state != Thread.State.BLOCKED) Thread.sleep(5)
        try {
            val text = SlowCallDiagnostic.format("test", Instant.EPOCH, ManagementFactory.getThreadMXBean().dumpAllThreads(true, true))
            assertTrue(text.startsWith("===== SLOW-CALL THREAD DUMP at 1970-01-01T00:00:00Z : test"), text.take(120))
            assertTrue(text.contains("\"diag-waiter\""), "대기 스레드가 없다")
            assertTrue(text.contains("owned by \"diag-owner\""), "잠금 소유자가 없다")
            assertTrue(text.contains("- locked "), "쥔 모니터가 없다")
            assertTrue(text.trimEnd().endsWith("===== END SLOW-CALL THREAD DUMP ====="))
        } finally {
            release.countDown()
            owner.join(2000)
        }
    }

    @Test
    fun `덤프 자리는 클래스당 상한까지만 나오고 reset 으로 되돌아온다`() {
        SlowCallDiagnostic.reset()
        val taken = (1..SlowCallDiagnostic.MAX_DUMPS + 5).count { SlowCallDiagnostic.tryTake() }
        assertEquals(SlowCallDiagnostic.MAX_DUMPS, taken)
        assertFalse(SlowCallDiagnostic.tryTake())
        SlowCallDiagnostic.reset()
        assertTrue(SlowCallDiagnostic.tryTake())
        SlowCallDiagnostic.reset()
    }
}
