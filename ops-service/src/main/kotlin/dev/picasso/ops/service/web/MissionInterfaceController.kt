package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.interfaces.InterfaceMapping
import dev.picasso.ops.service.interfaces.InterfaceRejected
import dev.picasso.ops.service.interfaces.MissionInterfaces
import dev.picasso.ops.service.joborders.FormRejection
import dev.picasso.ops.service.joborders.JobOrderForm
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/** 로컬 Ops PoC의 버전별 호출 계약. mode 헤더는 실서비스 인증을 대체하지 않는다. */
@RestController
class MissionInterfaceController(private val interfaces: MissionInterfaces, private val requests: dev.picasso.ops.service.requests.WorkRequests, private val host:dev.picasso.ops.service.host.HostMissions?=null, private val eligibility:dev.picasso.ops.service.joborders.JobOrderEligibility?=null) {
    private val json = ObjectMapper()
    @GetMapping("/api/mission-interfaces")
    fun list(): Any = mapOf("interfaces" to interfaces.list())

    @PostMapping("/api/mission-interfaces", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(@RequestHeader(Actor.MODE_HEADER, required=false) mode: String?, @RequestHeader(Actor.USER_HEADER, required=false) user: String?, @RequestBody(required=false) body: ByteArray?): ResponseEntity<Any> =
        guarded(mode, user, Mode.ENGINEER) { actor -> checked { ResponseEntity.ok(interfaces.create(interfaces.spec(read(body)), actor)) } }

    @PostMapping("/api/mission-interfaces/{id}/{version}/state", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun state(@PathVariable id: String, @PathVariable version: Int, @RequestHeader(Actor.MODE_HEADER, required=false) mode: String?, @RequestHeader(Actor.USER_HEADER, required=false) user: String?, @RequestBody(required=false) body: ByteArray?): ResponseEntity<Any> =
        guarded(mode, user, Mode.ENGINEER) { actor -> checked {
            val node = read(body)
            ResponseEntity.ok(interfaces.state(id, version, text(node, "expectedStatus"), text(node, "status"), text(node, "reason"), actor))
        } }

    @PostMapping("/api/mission-interfaces/preview", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun preview(@RequestBody(required=false) body: ByteArray?): ResponseEntity<Any> = checked {
        val spec = interfaces.spec(read(body))
        ResponseEntity.ok(JobOrderForm.jobOrder(interfaces.transform(spec.workMasterId, spec.mapping, spec.sample), "JO-PREVIEW", json))
    }

    /** Read-only assessment of the same deployed order used for submission; it reserves no robot. */
    @PostMapping("/api/mission-interfaces/{id}/{version}/eligibility", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun eligibility(@PathVariable id: String, @PathVariable version: Int, @RequestBody(required=false) body: ByteArray?): ResponseEntity<Any> = checked {
        val value = read(body)
        if (value.fieldNames().asSequence().toSet() != setOf("input") || value["input"]?.isObject != true)
            throw InterfaceRejected("BAD_ELIGIBILITY_INPUT", "input 객체 하나가 필요합니다")
        val draft = interfaces.draft(id, version, value["input"], true)
        val order = JobOrderForm.jobOrder(draft, "JO-PREVIEW", json)
        val judge = eligibility ?: throw InterfaceRejected("ELIGIBILITY_UNAVAILABLE", "배정 판정 서비스를 확인할 수 없습니다", 503)
        ResponseEntity.ok(mapOf("interfaceId" to id, "interfaceVersion" to version, "jobOrder" to order, "eligibility" to judge.judge(order)))
    }

    @PostMapping("/api/mission-interfaces/{id}/{version}/invoke", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun invoke(@PathVariable id: String, @PathVariable version: Int, @RequestHeader(Actor.MODE_HEADER, required=false) mode: String?, @RequestHeader(Actor.USER_HEADER, required=false) user: String?, @RequestBody(required=false) body: ByteArray?): ResponseEntity<Any> =
        guarded(mode, user, Mode.OPERATOR) { actor -> checked {
            val accepted = requests.receiveInterface(actor,id,version,read(body))
            ResponseEntity.status(if(accepted.duplicate) 200 else 202)
                .header("Location","/api/work-requests/${accepted.receipt.receiptId}").body(accepted)
        } }

    @GetMapping("/api/mission-interfaces/{id}/{version}/deployments")
    fun deployments(@PathVariable id:String,@PathVariable version:Int):ResponseEntity<Any> = checked {
        interfaces.get(id,version);ResponseEntity.ok(mapOf("deployments" to interfaces.deployments(id,version)))
    }
    @PostMapping("/api/mission-interfaces/{id}/{version}/deploy",consumes=[MediaType.APPLICATION_JSON_VALUE])
    fun deploy(@PathVariable id:String,@PathVariable version:Int,@RequestHeader(Actor.MODE_HEADER,required=false) mode:String?,@RequestHeader(Actor.USER_HEADER,required=false) user:String?,@RequestBody(required=false) body:ByteArray?):ResponseEntity<Any> = guarded(mode,user,Mode.ENGINEER) {actor -> checked {
        val value=read(body);val mission=text(value,"missionVersion");val reason=text(value,"reason")
        val expected=value["expectedDeploymentVersion"]?.takeIf{it.isIntegralNumber && it.canConvertToInt()}?.asInt()
            ?: throw InterfaceRejected("BAD_DEPLOYMENT","조회한 배포 버전이 필요합니다")
        val view=interfaces.get(id,version)
        if(mission=="CODE" && interfaces.profiles.find(view.workMasterId)!=null)throw InterfaceRejected("BAD_DEPLOYMENT","신규 미션은 활성화한 숫자 버전을 배포해야 합니다",409)
        if(mission!="CODE") {
            val call=host?.overview(view.workMasterId) as? dev.picasso.ops.service.host.HostCall.Ok
            val versions=call?.value?.get("versions")?.takeIf{it.isArray}
                ?: throw InterfaceRejected("HOST_UNAVAILABLE","배포 대상 미션 버전을 확인할 수 없습니다",503)
            if(versions.none{it["version"]?.asText()==mission}) throw InterfaceRejected("MISSION_VERSION_NOT_FOUND","활성화 이력이 있는 미션 버전만 배포할 수 있습니다",409)
        }
        ResponseEntity.ok(interfaces.deploy(id,version,mission,expected,reason,actor))
    }}
    private fun read(body: ByteArray?): JsonNode {
        if (body == null || body.size > 65536) throw InterfaceRejected("INTERFACE_BAD_REQUEST", "본문은 64KiB 이하 JSON이어야 합니다")
        return runCatching { json.readTree(body) }.getOrNull()?.takeIf { it.isObject }
            ?: throw InterfaceRejected("INTERFACE_BAD_REQUEST", "본문은 JSON 객체여야 합니다")
    }
    private fun text(node: JsonNode, key: String): String = node[key]?.takeIf { it.isTextual }?.asText() ?: throw InterfaceRejected("INTERFACE_BAD_REQUEST", "$key 문자열이 필요합니다")
    private inline fun checked(action: () -> ResponseEntity<Any>): ResponseEntity<Any> = try { action() }
        catch(e: dev.picasso.ops.service.requests.RequestRejected) { ResponseEntity.status(e.status).body(PreRejection(e.code,e.message)) }
        catch(e: InterfaceRejected) { ResponseEntity.status(e.status).body(PreRejection(e.code, e.message)) }
        catch(e: FormRejection) { ResponseEntity.badRequest().body(PreRejection(e.error, e.message ?: "")) }
}
