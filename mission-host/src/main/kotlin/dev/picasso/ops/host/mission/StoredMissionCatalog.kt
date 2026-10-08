package dev.picasso.ops.host.mission

import dev.picasso.middleware.ActiveMission
import dev.picasso.middleware.LogicalCapability
import dev.picasso.middleware.MissionCatalog
import dev.picasso.middleware.mission.DefinedCapability
import dev.picasso.middleware.mission.MissionDefinitionParser
import dev.picasso.middleware.mission.MissionParse
import dev.picasso.ops.host.store.VersionRow

/**
 * 호스트의 임무 카탈로그(S3b 스펙 §6.2, T1). picasso `MissionCatalog` 를 직접 구현해 DB 의 활성 버전 번호를 그대로 싣는다.
 *
 * picasso `InMemoryMissionCatalog.activate` 는 번호를 지정할 수 없고 재기동하면 1부터 센다. 그래서 그것을 쓰지 않고
 * 번호는 저장([dev.picasso.ops.host.store.MissionStore.insertVersion])이 정하고 이 카탈로그는 그 번호를 받아 든다.
 *
 * 활성화한 적이 없는 WorkMaster 는 코드 케이퍼빌리티로 답하고 버전이 없다(`null`).
 *
 * ## 스레드
 *
 * 미들웨어가 호스트 잠금 아래에서 [active] 를 읽고 활성화도 호스트 잠금 아래에서 [install] 을 부른다. 기동 때의 [restore]
 * 는 pump 가 돌기 전이다. 그래도 화면 조회([active])가 잠금 밖에서 올 수 있어 맵을 통째로 갈아 끼운다.
 */
class StoredMissionCatalog(code: List<LogicalCapability> = MissionCatalog.codeCapabilities()) : MissionCatalog {

    private val coded: Map<String, LogicalCapability> = code.associateBy { it.workMasterId }

    @Volatile
    private var activated: Map<String, ActiveMission> = emptyMap()

    override fun active(workMasterId: String): ActiveMission? =
        activated[workMasterId] ?: coded[workMasterId]?.let { ActiveMission(it, missionVersion = null) }

    /**
     * 기동 때 DB 의 활성 버전(WorkMaster 마다 가장 높은 번호)으로 세운다. **다시 검증하지 않는다.** 활성화 때 검증을 지난
     * 정의이고, 지금의 신호 사양·현장 기체로 다시 보면 현장이 바뀐 것만으로 활성 버전이 사라진다.
     *
     * 저장된 정의를 파싱하지 못하면 예외를 던져 호스트 기동을 멈춘다(S3b 스펙 §9). 코드 정의로 물러서면 운영자는 버전
     * N 이 돈다고 알고 있는데 다른 정의가 돈다.
     */
    fun restore(rows: List<VersionRow>) {
        activated = rows.associate { row -> row.workMasterId to ActiveMission(capability(row), row.version) }
    }

    /** 활성화가 버전 행을 넣은 뒤 부른다. 다음 작업 지시부터 이 버전으로 계획한다. 도는 실행은 쥔 버전으로 끝난다. */
    fun install(workMasterId: String, capability: DefinedCapability, version: Int) {
        require(capability.workMasterId == workMasterId) { "정의의 WorkMaster(${capability.workMasterId})가 $workMasterId 가 아니다" }
        activated = activated + (workMasterId to ActiveMission(capability, version))
    }

    private fun capability(row: VersionRow): DefinedCapability {
        val where = "저장된 임무 버전 ${row.workMasterId} 버전 ${row.version}"
        val definition = when (val parsed = MissionDefinitionParser.parse(row.definition)) {
            is MissionParse.Parsed -> parsed.definition
            is MissionParse.Unreadable -> throw IllegalStateException("$where 을 읽지 못했다: ${parsed.problems.joinToString("; ")}")
        }
        check(definition.workMasterId == row.workMasterId) { "$where 의 정의가 다른 WorkMaster(${definition.workMasterId})다" }
        return try {
            DefinedCapability(definition)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("$where 로 케이퍼빌리티를 세우지 못했다: ${e.message}", e)
        }
    }
}
