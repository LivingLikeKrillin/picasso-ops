package dev.picasso.ops.service.web

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * 관문에서 막은 요청의 답. 조작 기록에 남기지 않는다. 상태를 바꾸는 쪽(registry, 또는 S2 의 현장 설정)에 닿지 않은 요청은
 * 조작이 아니다(S2 스펙 §6.2).
 */
data class PreRejection(val error: String, val detail: String)

/**
 * 조작 API 의 관문. 행위자 헤더가 없거나 틀리면 400, 모드가 맞지 않으면 403 으로 registry 를 부르기 전에 막는다(스펙 §9).
 * 기체 조작과 어댑터 조작이 같이 쓴다. 교차 출처 방어에서 이 헤더가 맡는 몫은 [RobotOperationsController] 에 적었다.
 */
internal inline fun guarded(
    mode: String?,
    user: String?,
    required: Mode,
    action: (Actor) -> ResponseEntity<Any>,
): ResponseEntity<Any> {
    val actor = Actor.fromHeaders(mode, user)
        ?: return reject(HttpStatus.BAD_REQUEST, "ACTOR_REQUIRED", "${Actor.MODE_HEADER}·${Actor.USER_HEADER} 헤더가 없거나 틀리다")
    if (actor.mode != required) {
        return reject(HttpStatus.FORBIDDEN, "MODE_NOT_ALLOWED", "이 조작은 ${required.wire} 모드에서 한다")
    }
    return action(actor)
}

internal fun reject(status: HttpStatus, error: String, detail: String): ResponseEntity<Any> =
    ResponseEntity.status(status).body(PreRejection(error, detail))

/** 본문에 실려 온 사이트가 운영 서비스의 사이트와 다르면 막는다. 화면은 사이트를 싣지 않는다(스펙 §9). */
internal fun siteMismatch(site: String?, siteId: SiteId): ResponseEntity<Any>? =
    if (site != null && site != siteId.value) {
        reject(HttpStatus.BAD_REQUEST, "SITE_MISMATCH", "사이트가 ${siteId.value} 가 아니다: $site")
    } else {
        null
    }
