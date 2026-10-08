package dev.picasso.ops.host.mission

import dev.picasso.middleware.FloorOwnership
import dev.picasso.middleware.mission.MissionDefinition
import dev.picasso.middleware.mission.MissionDefinitionParser
import dev.picasso.middleware.mission.MissionParse
import dev.picasso.middleware.mission.MissionRefusal
import dev.picasso.middleware.mission.MissionRefusalKind
import dev.picasso.middleware.mission.MissionValidator
import dev.picasso.middleware.mission.SignalSpec
import dev.picasso.ops.host.cell.CellSnapshot
import java.time.Instant

/** 검증 입력 중 모르는 것(T7). 모름은 거부가 아니다. */
enum class UnknownInput {
    /** 셀 대역 스냅숏이 없거나 그 본문에 신호 목록이 없다. */
    SIGNAL_SPEC,

    /** 넘긴 기체 중 하나라도 케이퍼빌리티를 못 물어봤다. */
    SITE_SKILLS,

    /** 셀 대역 스냅숏에 슬롯이나 자재를 든 제시 자리가 없어 모의 실행의 표본 작업 지시를 만들 수 없다. */
    SAMPLE_ORDER,
}

/**
 * 검증 입력(S3b 스펙 §6.3, T7). 호스트 잠금 아래에서 한 번에 읽는다. 기체 케이퍼빌리티를 묻는 클라이언트가 잠금 밖에서
 * 안전하지 않고, 신호 사양과 현장 스킬이 같은 순간의 것이어야 해서다.
 *
 * @param cell 마지막 pump 가 읽은 셀 대역 스냅숏. 못 읽었으면 `null`.
 * @param skillsByRobot 넘긴 기체마다 선언한 스킬. 못 물어본 기체는 `null`.
 */
data class SiteInputs(val cell: CellSnapshot?, val skillsByRobot: Map<String, Set<String>?>) {

    /** 신호 사양 = 셀 대역 스냅숏의 신호 목록. 모르면 `null`. */
    val signals: List<SignalSpec>? get() = cell?.signalSpecs()

    /** 현장 스킬 = 넘긴 기체들의 스킬 합. 하나라도 모르면 `null` 이다. 모름을 없음으로 접으면 엉뚱한 SKILL_NOT_ON_SITE 가 난다. */
    val siteSkills: Set<String>? get() =
        if (skillsByRobot.values.any { it == null }) null else skillsByRobot.values.flatMap { it.orEmpty() }.toSortedSet()

    /** 케이퍼빌리티를 못 물어본 기체. */
    val unknownRobots: List<String> get() = skillsByRobot.filterValues { it == null }.keys.toList()

    val unknown: List<UnknownInput> get() = buildList {
        if (signals == null) add(UnknownInput.SIGNAL_SPEC)
        if (siteSkills == null) add(UnknownInput.SITE_SKILLS)
    }
}

/** 초안 하나를 지금 입력으로 본 결과. */
sealed interface Judgment {
    data class Passed(val definition: MissionDefinition) : Judgment

    /** 거부. 거부를 전부 든다. */
    data class Refused(val refusals: List<MissionRefusal>) : Judgment

    /** 입력을 몰라 판정하지 않았다(T7). [robots] 는 케이퍼빌리티를 못 물어본 기체다. */
    data class Unknown(val inputs: List<UnknownInput>, val robots: List<String>) : Judgment
}

/**
 * 초안 판정(S3b 스펙 §6.3). 순서는 문서 → WorkMaster 대조 → 입력 → 검증기다.
 *
 * 문서 수준의 문제(읽을 수 없음, WorkMaster 가 다름)는 현장 입력과 상관이 없으므로 입력을 모를 때도 거부로 낸다. 읽을 수
 * 없는 문서를 거부로 바꾸는 부분은 picasso `InMemoryMissionCatalog.activate` 의 그 자리를 그대로 옮긴다(파서는 문제 문자열
 * 목록만 준다).
 *
 * 정의 안의 `workMasterId` 가 초안의 WorkMaster(경로)와 다르면 UNREADABLE(경로 `$.workMasterId`)이다(T6). 검증기는
 * `workMasterId` 를 보지 않으므로 여기서 막지 않으면 다른 WorkMaster 의 카탈로그가 바뀐다.
 *
 * 바닥 소유는 `FloorOwnership.None` 이다. 호스트가 바닥 소유를 쥐지 않아 그 검사는 늘 통과한다(S3b 스펙 §11).
 */
object MissionJudgment {

    fun judge(workMasterId: String, text: String, inputs: SiteInputs, at: Instant): Judgment {
        val definition = when (val parsed = MissionDefinitionParser.parse(text)) {
            is MissionParse.Unreadable -> return Judgment.Refused(
                parsed.problems.map { MissionRefusal(MissionRefusalKind.UNREADABLE, null, it, DOCUMENT_SHAPE, at) },
            )
            is MissionParse.Parsed -> parsed.definition
        }
        if (definition.workMasterId != workMasterId) {
            return Judgment.Refused(
                listOf(
                    MissionRefusal(
                        MissionRefusalKind.UNREADABLE, null,
                        "$.workMasterId: 초안의 WorkMaster 와 다르다(${definition.workMasterId})", workMasterId, at,
                    ),
                ),
            )
        }
        val signals = inputs.signals
        val siteSkills = inputs.siteSkills
        if (signals == null || siteSkills == null) return Judgment.Unknown(inputs.unknown, inputs.unknownRobots)
        val refusals = MissionValidator.validate(definition, signals, FloorOwnership.None, siteSkills, at)
        return if (refusals.isEmpty()) Judgment.Passed(definition) else Judgment.Refused(refusals)
    }

    /** 읽을 수 없는 문서 거부의 기대 값. picasso 카탈로그와 같은 문구다. */
    val DOCUMENT_SHAPE = "임무 정의 문서 버전 ${MissionDefinitionParser.SCHEMA_VERSION} 의 모양"
}
