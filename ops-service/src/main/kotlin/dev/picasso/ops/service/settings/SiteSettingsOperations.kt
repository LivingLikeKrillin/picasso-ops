package dev.picasso.ops.service.settings

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.OperationOutcome
import org.springframework.dao.DuplicateKeyException
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.util.UUID

/**
 * 현장 설정 변경(S2 스펙 §6.2). registry 를 부르지 않으므로 `OperationRunner` 를 거치지 않는다. 같은 DB 라 응답 없음과
 * 재조회가 없다. 새 버전 행과 조작 기록의 `SUCCEEDED` 행은 한 트랜잭션에 들어간다.
 *
 * 범위·사유·본문 검사는 부르는 쪽(웹 관문)이 먼저 한다. 여기 오는 요청은 그것을 지난 것이다.
 */
class SiteSettingsOperations(
    private val store: SiteSettingsStore,
    private val log: OperationLog,
    private val tx: TransactionOperations,
    private val clock: Clock,
    private val json: ObjectMapper = ObjectMapper(),
) {
    private sealed interface Change {
        data object Applied : Change
        data class Conflict(val current: Long) : Change
    }

    fun change(actor: Actor, baseVersion: Long, connectionThresholdSeconds: Int, reason: String): OperationOutcome {
        require(SiteSettingsRange.allows(connectionThresholdSeconds.toLong())) { "범위 밖 값은 관문이 막는다: $connectionThresholdSeconds" }
        val requestId = UUID.randomUUID()
        val request = json.createObjectNode()
            .put("op", "CHANGE_SITE_SETTINGS")
            .put("baseVersion", baseVersion)
            .put("connectionThresholdSeconds", connectionThresholdSeconds)
            .toString()
        val change = try {
            tx.execute {
                val current = store.latest().version
                // 기준 버전이 지금 버전이 아니면 넣지 않는다. 지난 버전 위의 변경은 기본 키가 막지만, 아직 없는 버전을
                // 기준으로 보내면 번호가 건너뛴 행이 들어간다.
                if (current != baseVersion) return@execute Change.Conflict(current)
                store.insert(baseVersion + 1, connectionThresholdSeconds, actor, reason)
                log.append(requestId, actor, TARGET, request, reason, OperationResult.SUCCEEDED, null)
                Change.Applied
            }!!
        } catch (e: DuplicateKeyException) {
            // 같은 기준 버전 위의 다른 변경이 먼저 들어갔다. 이 트랜잭션은 되돌려졌다.
            Change.Conflict(store.latest().version)
        }
        return when (change) {
            Change.Applied -> OperationOutcome(requestId, OperationResult.SUCCEEDED, null, null, false, null)
            is Change.Conflict -> {
                log.append(requestId, actor, TARGET, request, reason, OperationResult.REJECTED, null)
                val rejection = Finding(
                    VERSION_CONFLICT, "현재 버전 ${change.current}", "기준 버전 $baseVersion",
                    clock.instant(), Owner.ENGINEER, true, "현재 값을 다시 읽고 다시", null,
                )
                OperationOutcome(requestId, OperationResult.REJECTED, null, rejection, false, null)
            }
        }
    }

    companion object {
        const val TARGET = "site-settings"
        const val VERSION_CONFLICT = "SETTINGS_VERSION_CONFLICT"
    }
}
