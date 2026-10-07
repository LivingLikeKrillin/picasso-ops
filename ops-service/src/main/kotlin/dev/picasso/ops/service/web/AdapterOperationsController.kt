package dev.picasso.ops.service.web

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.operations.AdapterOperations
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

data class DeclareAdapterBody(val vendor: String = "", val name: String = "")

data class DeclareBuildBody(val version: String = "", val contractSemver: String = "")

/**
 * [site] 는 화면이 싣지 않는다. 실려 오면 운영 서비스의 사이트와 대조한다(스펙 §9).
 *
 * [adapterVersionId] 를 널로 받는 이유: 널이 안 되는 `Long` 으로 두면 본문에 칸이 없을 때 Jackson 이 0 으로 읽어
 * 그대로 registry 에 간다. 빈 칸은 운영 서비스가 먼저 막는다.
 */
data class RegisterInstanceBody(
    val instanceId: String = "",
    val adapterVersionId: Long? = null,
    val fleetEndpoint: String? = null,
    val site: String? = null,
)

/**
 * 어댑터 조작 API(스펙 §3 S1c). 셋 다 엔지니어 모드에서 한다(스펙 §8). 관문과 교차 출처 방어, 사전 거절을 조작 기록에
 * 남기지 않는 것, registry 의 답을 200 과 결과 본문으로 돌려주는 것은 [RobotOperationsController] 와 같다.
 * 사이트 대조는 인스턴스 등록에만 있다. 제품과 빌드는 사이트에 매이지 않는다.
 */
@RestController
class AdapterOperationsController(
    private val operations: AdapterOperations,
    private val siteId: SiteId,
) {

    @PostMapping("/api/adapters", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun declareAdapter(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody body: DeclareAdapterBody,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        ResponseEntity.ok(operations.declareAdapter(actor, body.vendor, body.name))
    }

    @PostMapping("/api/adapters/{adapterId}/versions", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun declareBuild(
        @PathVariable adapterId: Long,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody body: DeclareBuildBody,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        ResponseEntity.ok(operations.declareBuild(actor, adapterId, body.version, body.contractSemver))
    }

    @PostMapping("/api/adapter-instances", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun registerInstance(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody body: RegisterInstanceBody,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        val build = body.adapterVersionId
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, "BUILD_REQUIRED", "adapterVersionId 가 없다")
        siteMismatch(body.site, siteId)
            ?: ResponseEntity.ok(operations.registerInstance(actor, body.instanceId, build, body.fleetEndpoint))
    }
}
