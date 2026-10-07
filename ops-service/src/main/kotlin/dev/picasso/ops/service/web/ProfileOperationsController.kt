package dev.picasso.ops.service.web

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.operations.ProfileOperations
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * [adapterVersionId]·[profileRevisionId] 를 널로 받는 이유: 널이 안 되는 `Long` 으로 두면 본문에 칸이 없을 때 Jackson 이
 * 0 으로 읽어 그대로 registry 에 간다. 빈 칸은 운영 서비스가 먼저 막는다(`BINDING_TARGET_REQUIRED`).
 */
data class BindBody(val adapterVersionId: Long? = null, val profileRevisionId: Long? = null)

/**
 * 개정판·바인딩 조작 API(P2·S1d 스펙 §8.2). 다섯 다 엔지니어 모드에서 한다. 관문, 사전 거절을 조작 기록에 남기지 않는 것,
 * registry 의 답을 200 과 결과 본문으로 돌려주는 것은 [RobotOperationsController] 와 같다.
 *
 * 제출 본문은 바이트 그대로 받는다. 운영 서비스가 다시 직렬화하면 registry 가 매기는 문서 해시가 달라져, 같은 문서의
 * 재제출이 다른 문서로 보이고 재조회의 반영 판정도 틀린다. 본문이 없는 조작(시험 요청, 활성화, 명칭 기록)은 퇴역 복귀의
 * DELETE 처럼 커스텀 헤더만으로 다른 출처를 막는다.
 */
@RestController
class ProfileOperationsController(private val operations: ProfileOperations) {

    @PostMapping("/api/profile-revisions", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) document: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        if (document == null || document.isEmpty()) {
            return@guarded reject(HttpStatus.BAD_REQUEST, "PROFILE_REQUIRED", "제출할 프로파일 문서가 없다")
        }
        ResponseEntity.ok(operations.submit(actor, document))
    }

    @PostMapping("/api/profile-revisions/{profileRevisionId}/test-requests")
    fun requestTest(
        @PathVariable profileRevisionId: Long,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        ResponseEntity.ok(operations.requestTest(actor, profileRevisionId))
    }

    @PostMapping("/api/profile-revisions/{profileRevisionId}/activation")
    fun activate(
        @PathVariable profileRevisionId: Long,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        ResponseEntity.ok(operations.activate(actor, profileRevisionId))
    }

    @PostMapping("/api/robots/{robotId}/binding", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun bind(
        @PathVariable robotId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody body: BindBody,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        val build = body.adapterVersionId
        val revision = body.profileRevisionId
        if (build == null || revision == null) {
            return@guarded reject(HttpStatus.BAD_REQUEST, "BINDING_TARGET_REQUIRED", "adapterVersionId 와 profileRevisionId 가 필요하다")
        }
        ResponseEntity.ok(operations.bind(actor, robotId, build, revision))
    }

    @PostMapping("/api/robots/{robotId}/site-names")
    fun recordSiteNames(
        @PathVariable robotId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        ResponseEntity.ok(operations.recordSiteNames(actor, robotId))
    }
}
