package dev.picasso.ops.e2e

import java.lang.management.ManagementFactory
import java.lang.management.ThreadInfo
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 진단 전용. 시험 안에서 HTTP 호출 하나가 [SLOW_MS] 를 넘기거나 운영 서비스가 503 `HOST_SILENT` 를 돌려주면 JVM 전체 스레드
 * 덤프를 System.err 에 한 번 남긴다(JUnit XML 의 system-err 에 실린다). 단언을 바꾸지 않고 호출을 다시 시도하지도 않는다.
 *
 * 덤프는 시험 클래스당 [MAX_DUMPS] 번까지다. 클래스마다 [E2eStack.start] 가 [reset] 을 부른다.
 * 느린 호출은 끝나기를 기다리지 않고 [SLOW_MS] 가 지나는 순간 떠야 막고 있는 스레드가 찍히므로 감시기를 호출 전에 건다.
 */
object SlowCallDiagnostic {
    const val SLOW_MS = 2_000L
    const val MAX_DUMPS = 3

    private val dumps = AtomicInteger()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "slow-call-diagnostic").apply { isDaemon = true } }

    fun reset() = dumps.set(0)

    /** 남은 덤프 자리가 있으면 하나 쓰고 참을 돌려준다. */
    fun tryTake(): Boolean {
        while (true) {
            val seen = dumps.get()
            if (seen >= MAX_DUMPS) return false
            if (dumps.compareAndSet(seen, seen + 1)) return true
        }
    }

    /** 호출 전에 건다. 반환된 감시를 호출 뒤 닫으면 취소된다. 덤프 스레드가 아닌 호출 스레드는 막지 않는다. */
    fun watch(label: String): ScheduledFuture<*> =
        scheduler.schedule({ dump("HTTP call exceeded ${SLOW_MS}ms (in flight): $label") }, SLOW_MS, TimeUnit.MILLISECONDS)

    fun dump(reason: String) {
        if (!tryTake()) return
        System.err.println(format(reason, Instant.now(), ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)))
    }

    /** `ThreadInfo.toString()` 은 스택을 8 프레임에서 자르므로 전체 프레임·잠금 소유자·쥔 잠금을 직접 적는다. */
    fun format(reason: String, at: Instant, infos: Array<ThreadInfo>): String {
        val out = StringBuilder()
        out.append("===== SLOW-CALL THREAD DUMP at ").append(at).append(" : ").append(reason).append(" (").append(infos.size).append(" threads) =====\n")
        for (info in infos) {
            out.append('"').append(info.threadName).append("\" id=").append(info.threadId).append(' ').append(info.threadState)
            info.lockName?.let { out.append(" on ").append(it) }
            if (info.lockOwnerName != null) out.append(" owned by \"").append(info.lockOwnerName).append("\" id=").append(info.lockOwnerId)
            out.append('\n')
            val monitors = info.lockedMonitors.groupBy { it.lockedStackDepth }
            info.stackTrace.forEachIndexed { depth, frame ->
                out.append("    at ").append(frame).append('\n')
                monitors[depth]?.forEach { out.append("        - locked ").append(it).append('\n') }
            }
            info.lockedSynchronizers.forEach { out.append("    - holds ").append(it).append('\n') }
        }
        out.append("===== END SLOW-CALL THREAD DUMP =====")
        return out.toString()
    }
}
