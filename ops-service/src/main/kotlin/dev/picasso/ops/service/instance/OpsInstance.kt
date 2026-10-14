package dev.picasso.ops.service.instance

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 운영 서비스 프로세스 하나의 신원과 심장박동(HA 설계 기록 §7·§8 단계 0). [start] 하면 `ops.service_instance` 에 자기 행을 넣고
 * [period] 마다 DB 시각으로 마지막 관측과 만료(그때 + [expiry])를 고치며, [close] 에서 멈춤을 적는다.
 *
 * 스테이션 조정기가 남은 `RETRYING`·`REWORKING` 행을 «앞 프로세스가 죽었다» 로 읽기 전에 그 행을 쓴 인스턴스가 살아 있는지
 * [isLive] 로 묻는다. 시각 비교는 모두 DB 의 `clock_timestamp()` 다(설계 기록 §4.3). 운영 서비스 시계를 쓰지 않는다.
 *
 * **리스가 아니다.** 세대도 펜싱도 없고, 만료 뒤 깨어난 프로세스를 막지 않는다. [start] 하지 않은 인스턴스(시험이 조정기를 직접
 * 짓는 경우)는 행이 없어 다른 인스턴스가 보기에 살아 있지 않다.
 *
 * 만료 폭은 설계 기록 §4.5 의 하한(주기의 세 배 + 최대 GC 정지 + DB 왕복)을 넘게 잡는다. 기본값은 주기 5초, 만료 30초다(실측 없음,
 * 주기의 세 배에 15초 여유). 심장박동은 자기 스레드에서 돌아 스테이션 tick·결과 수집이 오래 걸려도 늦지 않는다.
 *
 * @param period 심장박동 주기(`ops.instance.heartbeat-period`)
 * @param expiry 마지막 심장박동에서 만료까지(`ops.instance.heartbeat-expiry`). 주기의 세 배 이상이어야 한다
 */
class OpsInstance(
    private val jdbc: JdbcClient,
    val period: Duration = DEFAULT_PERIOD,
    val expiry: Duration = DEFAULT_EXPIRY,
    val instanceId: String = "ops-${UUID.randomUUID()}",
) : AutoCloseable {

    init {
        require(!period.isNegative && !period.isZero) { "심장박동 주기는 양수여야 한다: $period" }
        require(expiry >= period.multipliedBy(3)) { "심장박동 만료($expiry)는 주기($period)의 세 배 이상이어야 한다" }
    }

    @Volatile
    private var beater: ScheduledExecutorService? = null

    /** 자기 행을 넣고 심장박동을 시작한다. 첫 쓰기가 실패하면 예외가 나가 기동이 멈춘다. */
    fun start(): OpsInstance = apply {
        beat()
        beater = Executors.newSingleThreadScheduledExecutor { Thread(it, "ops-instance-heartbeat").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay(::beatOnce, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    /** 자기 행을 넣거나 고친다. 이미 멈춤을 적은 행은 되살리지 않는다. */
    fun beat() {
        jdbc.sql(
            """
            INSERT INTO ops.service_instance(instance_id,started_at,last_seen_at,expires_at)
            SELECT :id,t.now,t.now,t.now+:ms*interval '1 millisecond' FROM (SELECT clock_timestamp() AS now) t
            ON CONFLICT(instance_id) DO UPDATE SET last_seen_at=EXCLUDED.last_seen_at,expires_at=EXCLUDED.expires_at
            WHERE ops.service_instance.stopped_at IS NULL
            """.trimIndent(),
        ).param("id", instanceId).param("ms", expiry.toMillis()).update()
    }

    private fun beatOnce() {
        try {
            beat()
        } catch (e: Exception) {
            log.warn("심장박동 실패: {}", e.toString())
        }
    }

    /** [other] 가 지금(DB 시각) 살아 있는가. `null` 이거나 행이 없으면 거짓이다. 자기 자신도 행으로 판정한다. */
    fun isLive(other: String?): Boolean {
        if (other == null) return false
        return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM ops.service_instance WHERE instance_id=:id AND stopped_at IS NULL AND expires_at>clock_timestamp())",
        ).param("id", other).query(Boolean::class.java).single()
    }

    /** 심장박동을 멈추고 멈춤을 적는다. 다음 프로세스는 이 인스턴스가 남긴 행을 만료를 기다리지 않고 넘긴다. */
    override fun close() {
        val running = beater ?: return
        running.shutdownNow()
        running.awaitTermination(2, TimeUnit.SECONDS)
        try {
            jdbc.sql(
                "UPDATE ops.service_instance SET stopped_at=clock_timestamp(),expires_at=least(expires_at,clock_timestamp()) WHERE instance_id=:id AND stopped_at IS NULL",
            ).param("id", instanceId).update()
        } catch (e: Exception) {
            log.warn("인스턴스 멈춤을 적지 못했다: {}", e.toString())
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(OpsInstance::class.java)
        val DEFAULT_PERIOD: Duration = Duration.ofSeconds(5)
        val DEFAULT_EXPIRY: Duration = Duration.ofSeconds(30)
    }
}
