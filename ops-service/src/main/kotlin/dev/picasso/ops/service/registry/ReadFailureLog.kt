package dev.picasso.ops.service.registry

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * registry 읽기의 «모름» 을 경로마다 솎아 남긴다. registry 가 오래 내려가 있으면 화면이 읽을 때마다 한 줄씩 쌓인다.
 *
 * 경로(질의 문자열 없이)마다 처음 모름은 바로 WARN 으로 남긴다. 그 뒤로는 [interval] 에 한 줄만 남기고, 그 줄에
 * 가장 최근 원인과 지난 줄 이후 생략한 건수를 적는다. 경로가 다시 응답하면 INFO 한 줄로 회복을 알리고 비운다.
 * 응답 본문에 실리는 원인([RegistryCall.Silent.cause])은 요청마다 그대로이며 이 솎기와 무관하다.
 */
class ReadFailureLog(
    private val interval: Duration = DEFAULT_INTERVAL,
    private val clock: Clock = Clock.systemUTC(),
    private val warn: (String) -> Unit = { log.warn(it) },
    private val info: (String) -> Unit = { log.info(it) },
) {
    /** [loggedAtMillis] 는 이 경로가 마지막으로 WARN 을 남긴 때, [suppressed] 는 그 뒤로 생략한 건수다. */
    private data class Streak(val loggedAtMillis: Long, val suppressed: Int)

    private val streaks = ConcurrentHashMap<String, Streak>()

    /** 한 경로의 읽기가 모름으로 끝났다. [cause] 는 이번 요청의 원인이다. */
    fun failed(path: String, cause: String) {
        val now = clock.millis()
        var line: String? = null
        streaks.compute(path) { _, streak ->
            when {
                streak == null -> {
                    line = "$WARN_PREFIX$cause"
                    Streak(now, 0)
                }
                now - streak.loggedAtMillis >= interval.toMillis() -> {
                    line = "$WARN_PREFIX$cause (생략 ${streak.suppressed}건)"
                    Streak(now, 0)
                }
                else -> streak.copy(suppressed = streak.suppressed + 1)
            }
        }
        line?.let(warn)
    }

    /** 한 경로가 모름이 아닌 응답을 냈다. 앞서 모른 적이 있으면 한 번 알리고 비운다. */
    fun answered(path: String) {
        val streak = streaks.remove(path) ?: return
        info("registry 읽기 회복: $path (생략 ${streak.suppressed}건)")
    }

    companion object {
        private val log = LoggerFactory.getLogger(ReadFailureLog::class.java)
        val DEFAULT_INTERVAL: Duration = Duration.ofSeconds(60)
        private const val WARN_PREFIX = "registry 읽기 모름: "
    }
}
