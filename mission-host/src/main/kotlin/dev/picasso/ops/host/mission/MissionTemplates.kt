package dev.picasso.ops.host.mission

import dev.picasso.middleware.PrepareSequencedRack

/** 시작용 정의 하나. [definition] 은 편집기에 그대로 넣는 글자다. */
data class MissionTemplate(val id: String, val title: String, val definition: String)

/**
 * 시작용 정의(S3b 스펙 §6.6). picasso 시험 픽스처 `MissionFixtures` 의 사본을 호스트 리소스로 둔다. 시험 소스라 이 저장소가
 * 쓸 수 없다. 칸마다 한 줄인 모양도 픽스처 그대로다.
 *
 * - [DATA_V1]: 코드 `PrepareSequencedRack` 을 데이터로 옮긴 것(버전 1 의 모양).
 * - [ARRIVAL_WAIT]: 그 앞에 랙 도착 대기(신호 `rack_present`, 기대 `true`, 기한 120초)를 둔 것(버전 2 의 모양). 사본에서
 *   `onDeadline` 만 ABORTED 로 바꿨다(픽스처 기본값은 OPERATOR_HOLD, 결정 4). 운영자 보류와 그 해소 화면은 S4 다.
 */
object MissionTemplates {

    const val DATA_V1 = "DATA_V1"
    const val ARRIVAL_WAIT = "ARRIVAL_WAIT"

    /** WorkMaster 마다 시작용 정의. 편집 대상이 PrepareSequencedRack 하나다(T6). */
    fun of(workMasterId: String): List<MissionTemplate> = when (workMasterId) {
        PrepareSequencedRack.WORK_MASTER -> listOf(
            MissionTemplate(DATA_V1, "코드 PrepareSequencedRack 을 옮긴 데이터 정의", read("PrepareSequencedRack.data-v1.json")),
            MissionTemplate(ARRIVAL_WAIT, "랙 도착 대기(rack_present = true, 기한 120초, 기한 뒤 ABORTED)", read("PrepareSequencedRack.arrival-wait.json")),
        )
        else -> emptyList()
    }

    private fun read(name: String): String =
        requireNotNull(MissionTemplates::class.java.getResourceAsStream("/mission-templates/$name")) { "템플릿 리소스가 없다: $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .trimEnd('\n')
}
