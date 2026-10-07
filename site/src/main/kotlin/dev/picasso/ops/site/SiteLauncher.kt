package dev.picasso.ops.site

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 실제 1초마다 가상 1초를 민다(1:1, 스펙 §6 ③). 비율을 바꾸면 운영 서비스의 연결 기준 90초(스펙 §7.3)도 다시 정한다.
 * 주기와 전진량이 같은 값이어야 1:1 이므로 하나로 둔다.
 */
val TICK: Duration = Duration.ofSeconds(1)

fun main() {
    val root = Path.of("").toAbsolutePath()
    val config = SiteConfig.fromEnv(System.getenv(), root)
    val site = Site.start(config)
    println("registry: ${site.registryUrl} (site=${config.siteId})")
    println("mimic: ${site.robotIds.sorted()}")
    // uplink 가 거절 결과를 버리고 registry 도 남기지 않아서, 선언 전 보고의 거절은 어디에도 안 보인다.
    println("선언 전 mimic 의 생존 보고는 registry 가 거절하며 이 로그에도 남지 않는다. 기체를 선언하면 보고가 붙는다")

    val ticker = Executors.newSingleThreadScheduledExecutor()
    ticker.scheduleAtFixedRate(
        { runCatching { site.advance(TICK) }.onFailure { System.err.println("시간 진행 실패: $it") } },
        TICK.toMillis(),
        TICK.toMillis(),
        TimeUnit.MILLISECONDS,
    )
    Runtime.getRuntime().addShutdownHook(
        Thread {
            ticker.shutdownNow()
            // 진행 중인 advance 가 끝난 뒤 닫는다.
            ticker.awaitTermination(2, TimeUnit.SECONDS)
            site.close()
        },
    )
    Thread.currentThread().join()
}
