package dev.picasso.ops.service.web

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.operations.OperationOutcome
import dev.picasso.ops.service.operations.RobotOperations
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** `POST /api/robots` 의 본문. [site] 는 화면이 싣지 않는다. 실려 오면 운영 서비스의 사이트와 대조한다(스펙 §9). */
data class DeclareBody(
    val robotId: String = "",
    val serialNumber: String = "",
    val displayName: String? = null,
    val site: String? = null,
)

data class RetireBody(val reason: String = "")

/** registry 에 보내기 전에 막은 요청의 답. 조작 기록에 남기지 않는다. registry 에 닿지 않은 요청은 조작이 아니다. */
data class PreRejection(val error: String, val detail: String)

/**
 * 기체 조작 API(스펙 §7.2). 등록은 엔지니어 모드, 퇴역·복귀는 운영자 모드에서 한다(스펙 §8).
 *
 * registry 를 부르기 전에 3가지를 막는다. 행위자 헤더가 없거나 틀리면 400, 모드가 맞지 않으면 403, 사이트가
 * 운영 서비스의 `SITE_ID` 와 다르면 400 이다. 쓰기 본문은 `application/json` 만 받는다. 이 저장소에 인증은 없으며,
 * 커스텀 헤더와 JSON 본문 요구가 다른 출처의 페이지가 이 API 를 부르지 못하게 하는 유일한 방어다(브라우저가
 * 사전 요청을 보내고, 스프링은 CORS 설정이 없으면 그것을 거절한다). 폼이나 `text/plain` 을 받는 쓰기를 더하면 이
 * 방어가 사라진다. DELETE 는 본문이 없어 커스텀 헤더만으로 막히고, GET 은 계속 부작용이 없어야 한다.
 * `server.address=127.0.0.1` 은 다른 기계를 막지만 DNS 재바인딩은 막지 못한다(Host 검사가 없다). 인증을 생략한
 * PoC 의 한계다.
 *
 * 사이트 대조는 선언에만 있다. 퇴역·복귀는 기체 id 로만 부르며, registry 는 사이트를 대조하지 않는다(스펙 §6 ④).
 * 본문이 없거나 JSON 으로 읽히지 않는 요청은 스프링이 400 으로 막는다. 이것도 registry 에 닿지 않아 기록되지 않는다.
 *
 * registry 가 답한 결과(거절 포함)는 200 과 [OperationOutcome] 으로 돌려준다. 이 API 의 호출은 성공했고,
 * registry 의 판단은 본문에 있다.
 */
@RestController
@RequestMapping("/api/robots")
class RobotOperationsController(
    private val operations: RobotOperations,
    private val siteId: SiteId,
) {

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun declare(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody body: DeclareBody,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        if (body.site != null && body.site != siteId.value) {
            return@guarded reject(HttpStatus.BAD_REQUEST, "SITE_MISMATCH", "사이트가 ${siteId.value} 가 아니다: ${body.site}")
        }
        ResponseEntity.ok(operations.declare(actor, body.robotId, body.serialNumber, body.displayName))
    }

    @PostMapping("/{robotId}/retirement", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun retire(
        @PathVariable robotId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody body: RetireBody,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        ResponseEntity.ok(operations.retire(actor, robotId, body.reason))
    }

    @DeleteMapping("/{robotId}/retirement")
    fun reinstate(
        @PathVariable robotId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        ResponseEntity.ok(operations.reinstate(actor, robotId))
    }

    private inline fun guarded(
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

    private fun reject(status: HttpStatus, error: String, detail: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(PreRejection(error, detail))
}

/** 운영 서비스의 사이트 id(`.env` 의 `SITE_ID`). 기체 선언과 목록이 이 값 하나를 쓴다(스펙 §6 ④). */
data class SiteId(val value: String)
