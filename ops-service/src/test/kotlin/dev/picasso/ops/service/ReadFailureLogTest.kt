package dev.picasso.ops.service

import dev.picasso.ops.service.registry.ReadFailureLog
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadFailureLogTest {

    /** 시험이 손으로 돌리는 시계. */
    private class ManualClock(private var millis: Long = 1_000_000L) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
        override fun millis(): Long = millis
        fun advance(by: Duration) {
            millis += by.toMillis()
        }
    }

    private val clock = ManualClock()
    private val warns = CopyOnWriteArrayList<String>()
    private val infos = CopyOnWriteArrayList<String>()
    private val log = ReadFailureLog(Duration.ofSeconds(60), clock, { warns += it }, { infos += it })

    @Test
    fun `처음 모름은 바로 WARN 한 줄로 남긴다`() {
        log.failed("/diag/robots", "GET /diag/robots: HTTP 503 (3ms)")
        assertEquals(listOf("registry 읽기 모름: GET /diag/robots: HTTP 503 (3ms)"), warns.toList())
        assertTrue(infos.isEmpty())
    }

    @Test
    fun `간격 안의 반복은 남기지 않는다`() {
        log.failed("/diag/robots", "원인 1")
        repeat(5) {
            clock.advance(Duration.ofSeconds(10))
            log.failed("/diag/robots", "원인 ${it + 2}")
        }
        // 50초가 지났고 간격은 60초다.
        assertEquals(1, warns.size, warns.toString())
    }

    @Test
    fun `간격이 지나면 최근 원인과 생략 건수를 담은 한 줄을 남긴다`() {
        log.failed("/diag/robots", "원인 1")
        clock.advance(Duration.ofSeconds(20))
        log.failed("/diag/robots", "원인 2")
        clock.advance(Duration.ofSeconds(20))
        log.failed("/diag/robots", "원인 3")
        clock.advance(Duration.ofSeconds(20))
        log.failed("/diag/robots", "원인 4")
        assertEquals(
            listOf("registry 읽기 모름: 원인 1", "registry 읽기 모름: 원인 4 (생략 2건)"),
            warns.toList(),
        )
    }

    @Test
    fun `줄을 남기면 생략 건수를 비운다`() {
        log.failed("/diag/robots", "원인 1")
        clock.advance(Duration.ofSeconds(20))
        log.failed("/diag/robots", "원인 2")
        clock.advance(Duration.ofSeconds(40))
        log.failed("/diag/robots", "원인 3")
        clock.advance(Duration.ofSeconds(30))
        log.failed("/diag/robots", "원인 4")
        clock.advance(Duration.ofSeconds(30))
        log.failed("/diag/robots", "원인 5")
        // 둘째 줄(원인 3) 뒤의 생략은 원인 4 하나뿐이다.
        assertEquals(
            listOf(
                "registry 읽기 모름: 원인 1",
                "registry 읽기 모름: 원인 3 (생략 1건)",
                "registry 읽기 모름: 원인 5 (생략 1건)",
            ),
            warns.toList(),
        )
    }

    @Test
    fun `회복은 생략 건수와 함께 한 번만 남긴다`() {
        log.failed("/diag/robots", "원인 1")
        log.failed("/diag/robots", "원인 2")
        log.failed("/diag/robots", "원인 3")
        log.answered("/diag/robots")
        assertEquals(listOf("registry 읽기 회복: /diag/robots (생략 2건)"), infos.toList())
        log.answered("/diag/robots")
        assertEquals(1, infos.size)
    }

    @Test
    fun `회복하면 비워서 다음 모름은 간격과 상관없이 바로 남는다`() {
        log.failed("/diag/robots", "원인 1")
        log.answered("/diag/robots")
        log.failed("/diag/robots", "원인 2")
        assertEquals(listOf("registry 읽기 모름: 원인 1", "registry 읽기 모름: 원인 2"), warns.toList())
    }

    @Test
    fun `모른 적 없는 경로의 응답은 아무것도 남기지 않는다`() {
        log.answered("/diag/robots")
        assertTrue(infos.isEmpty())
        assertTrue(warns.isEmpty())
    }

    @Test
    fun `생략 없이 한 번 모르고 회복해도 회복은 남는다`() {
        log.failed("/diag/robots", "원인 1")
        log.answered("/diag/robots")
        assertEquals(listOf("registry 읽기 회복: /diag/robots (생략 0건)"), infos.toList())
    }

    @Test
    fun `경로는 서로 독립이다`() {
        log.failed("/diag/robots", "로봇 1")
        log.failed("/operations/adapters", "어댑터 1")
        log.failed("/diag/robots", "로봇 2")
        assertEquals(
            listOf("registry 읽기 모름: 로봇 1", "registry 읽기 모름: 어댑터 1"),
            warns.toList(),
        )
        // 한 경로의 회복이 다른 경로의 연속을 끊지 않는다.
        log.answered("/diag/robots")
        assertEquals(listOf("registry 읽기 회복: /diag/robots (생략 1건)"), infos.toList())
        log.failed("/operations/adapters", "어댑터 2")
        assertEquals(2, warns.size)
        log.answered("/operations/adapters")
        assertEquals("registry 읽기 회복: /operations/adapters (생략 1건)", infos.last())
    }

    @Test
    fun `동시에 모르는 호출도 줄은 하나이고 생략 건수는 어긋나지 않는다`() {
        val threads = 8
        val perThread = 50
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) { log.failed("/diag/robots", "원인") }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, warns.size, warns.toString())
        log.answered("/diag/robots")
        assertEquals("registry 읽기 회복: /diag/robots (생략 ${threads * perThread - 1}건)", infos.single())
    }
}
