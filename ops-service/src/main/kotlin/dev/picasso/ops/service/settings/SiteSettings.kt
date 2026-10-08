package dev.picasso.ops.service.settings

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** 판정에 쓰는 현장 설정 한 세트(S2 스펙 §5). [version] 이 근거 버전이 된다. */
data class SiteSettingsValues(val version: Long, val connectionThreshold: Duration)

/** 지금의 현장 설정. 못 읽으면 예외를 던진다. 기본값으로 대신하지 않는다(S2 스펙 §6.4). */
fun interface SettingsSource {
    fun current(): SiteSettingsValues
}

/** 현장 설정 버전 한 행(S2 스펙 §5). */
data class SiteSettingsRecord(
    val version: Long,
    val connectionThresholdSeconds: Int,
    val mode: Mode,
    val user: String,
    val reason: String,
    val recordedAt: Instant,
) {
    fun values(): SiteSettingsValues = SiteSettingsValues(version, Duration.ofSeconds(connectionThresholdSeconds.toLong()))
}

/**
 * 연결 기준 시간의 허용 범위(S2 스펙 §5). 이 값의 주인은 picasso 가 아니라 운영 서비스라서 범위도 여기 둔다.
 *
 * 하한은 프로파일 보고 간격 상한 30초의 2배다. 보고를 한 번 놓쳐도 오래됨이 되지 않는다. 보고 간격 가까이 두면
 * 정상 기체가 신선과 오래됨을 오간다. 상한 1시간은 그보다 길면 연결 칸이 뜻을 잃는다는 판단이다.
 */
object SiteSettingsRange {
    const val MIN_CONNECTION_THRESHOLD_SECONDS = 60
    const val MAX_CONNECTION_THRESHOLD_SECONDS = 3600

    fun allows(seconds: Long): Boolean = seconds in MIN_CONNECTION_THRESHOLD_SECONDS..MAX_CONNECTION_THRESHOLD_SECONDS
}

/** ops 스키마의 현장 설정 버전 표. 덧붙이기와 읽기만 있다. 고치기·지우기는 스키마의 트리거가 막는다. */
class SiteSettingsStore(private val jdbc: JdbcClient) : SettingsSource {

    override fun current(): SiteSettingsValues = latest().values()

    /** 가장 큰 버전. 마이그레이션이 버전 1 을 넣으므로 늘 있다. */
    fun latest(): SiteSettingsRecord = history(limit = 1).single()

    /** 최신 버전부터. */
    fun history(limit: Int = 200): List<SiteSettingsRecord> =
        jdbc.sql(
            """
            SELECT version, connection_threshold_seconds, mode, actor_user, reason, recorded_at
            FROM ops.site_settings
            ORDER BY version DESC
            LIMIT :limit
            """.trimIndent(),
        )
            .param("limit", limit)
            .query { rs, _ ->
                SiteSettingsRecord(
                    version = rs.getLong("version"),
                    connectionThresholdSeconds = rs.getInt("connection_threshold_seconds"),
                    mode = Mode.valueOf(rs.getString("mode")),
                    user = rs.getString("actor_user"),
                    reason = rs.getString("reason"),
                    recordedAt = rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }
            .list()

    /** 버전 [version] 행을 넣는다. 그 번호가 이미 있으면 기본 키가 막는다(`DuplicateKeyException`). */
    fun insert(version: Long, connectionThresholdSeconds: Int, actor: Actor, reason: String) {
        jdbc.sql(
            """
            INSERT INTO ops.site_settings (version, connection_threshold_seconds, mode, actor_user, reason)
            VALUES (:version, :seconds, :mode, :user, :reason)
            """.trimIndent(),
        )
            .param("version", version)
            .param("seconds", connectionThresholdSeconds)
            .param("mode", actor.mode.name)
            .param("user", actor.user)
            .param("reason", reason)
            .update()
    }
}
