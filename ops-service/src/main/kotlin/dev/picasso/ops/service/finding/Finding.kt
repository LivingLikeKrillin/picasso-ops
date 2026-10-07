package dev.picasso.ops.service.finding

import java.time.Instant

/** 누가 푸는가(스펙 §7.4). */
enum class Owner { SITE, OPERATOR, ENGINEER, NONE }

/**
 * 막힘이나 거절 한 건. 종류를 값으로 둔다. 사유 문장만 주면 읽는 쪽이 문자열을 대조하게 된다(스펙 §7.4).
 *
 * 화면에 내는 칸 5개와 맞춘다. 종류는 [kind], 관측값과 기대값은 [observed]·[expected], 마지막 확인 시각은
 * [checkedAt], 해결 담당은 [owner]·[inScreen], 바로 갈 링크는 [target] 이다. 링크 모양은 화면이 정한다.
 *
 * @param checkedAt 이 판정이 기댄 값을 registry 에서 읽은 시각
 * @param inScreen 화면 안에서 풀 수 있는가. 거짓이면 현장 등 화면 밖에서 풀린다
 * @param target 링크가 가리킬 기체 id. 없으면 널
 */
data class Finding(
    val kind: String,
    val observed: String,
    val expected: String,
    val checkedAt: Instant,
    val owner: Owner,
    val inScreen: Boolean,
    val action: String,
    val target: String?,
)
