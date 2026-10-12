package dev.picasso.ops.service.interfaces

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.core.JsonPointer
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.joborders.FormRejection
import dev.picasso.ops.service.joborders.JobOrderDraft
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

class InterfaceRejected(val code: String, override val message: String, val status: Int = 400) : RuntimeException(message)
data class InterfaceSpec(val interfaceId: String, val version: Int, val name: String, val workMasterId: String,
    val mapping: JsonNode, val sample: JsonNode)
data class InterfaceView(val interfaceId: String, val version: Int, val name: String, val workMasterId: String,
    val mapping: JsonNode, val sample: JsonNode, val status: String, val createdBy: String, val createdAt: String,
    val missionVersionPolicy: String = "PINNED_DEPLOYMENT", val deployment:JsonNode?=null, val requiredStationId:String?=null)

/** 제한된 JSON Pointer 매핑. 스크립트·동적 workMaster·임의 내부 필드 쓰기를 허용하지 않는다. */
object InterfaceMapping {
    private val json = ObjectMapper()
    private val fields = mapOf("InspectAsset" to setOf("targets"), "PrepareSequencedRack" to setOf("slots", "material", "presentation"))

    fun spec(node: JsonNode?, profiles:dev.picasso.ops.service.missions.MissionProfiles?=null): InterfaceSpec {
        if (node == null || !node.isObject) fail("계약 본문은 객체여야 합니다")
        val id = text(node, "interfaceId")
        if (!Regex("[a-z][a-z0-9-]{0,63}").matches(id)) fail("호출 식별자는 소문자로 시작하는 영문·숫자·하이픈 1~64자입니다")
        val versionNode = node["version"]
        if (versionNode == null || !versionNode.isIntegralNumber || !versionNode.canConvertToInt() || versionNode.asInt() < 1) fail("버전은 양의 정수여야 합니다")
        val name = text(node, "name")
        if (name.length > 120) fail("업무 이름은 120자 이하여야 합니다")
        val wm = text(node, "workMasterId")
        val mapping = node["mapping"] ?: fail("입력 매핑이 없습니다")
        validateMapping(wm, mapping,profiles)
        val sample = node["sample"] ?: fail("검증할 샘플 입력이 없습니다")
        transform(wm, mapping, sample,profiles)
        return InterfaceSpec(id, versionNode.asInt(), name, wm, mapping, sample)
    }

    fun transform(workMasterId: String, mapping: JsonNode, input: JsonNode, profiles:dev.picasso.ops.service.missions.MissionProfiles?=null): JobOrderDraft {
        validateMapping(workMasterId, mapping,profiles)
        if (!input.isObject) fail("호출 입력은 JSON 객체여야 합니다")
        val form = json.createObjectNode().put("workMasterId", workMasterId)
        mapping.fields().forEach { (target, source) ->
            val value = input.at(source.asText())
            if (value.isMissingNode || value.isNull) fail("$target 입력을 찾을 수 없습니다: ${source.asText()}")
            form.set<JsonNode>(target, value)
        }
        return profiles?.find(workMasterId)?.let{profiles.read(it,form.deepCopy().apply{remove("workMasterId")})} ?: JobOrderForm.read(form)
    }

    private fun validateMapping(wm: String, mapping: JsonNode,profiles:dev.picasso.ops.service.missions.MissionProfiles?) {
        val required = fields[wm] ?: profiles?.find(wm)?.path("inputSchema")?.path("properties")?.fieldNames()?.asSequence()?.toSet() ?: fail("지원하지 않는 업무입니다: $wm")
        if (!mapping.isObject || mapping.fieldNames().asSequence().toSet() != required) fail("$wm 매핑 대상은 ${required.sorted().joinToString()}입니다")
        mapping.fields().forEach { (_, value) ->
            if (!value.isTextual || value.asText().length > 256 || !value.asText().startsWith("/")) fail("매핑 경로는 /로 시작하는 256자 이하 JSON Pointer입니다")
            val pointer = value.asText()
            if (Regex("~(?![01])").containsMatchIn(pointer)) fail("매핑 경로의 ~는 ~0 또는 ~1로 이스케이프해야 합니다")
            runCatching { JsonPointer.compile(pointer) }.getOrElse { fail("잘못된 매핑 경로입니다: $pointer") }
        }
    }
    private fun text(node: JsonNode, name: String): String = node[name]?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() } ?: fail("$name 문자열이 필요합니다")
    private fun fail(message: String): Nothing = throw InterfaceRejected("INTERFACE_BAD_REQUEST", message)
}

/** 계약 정의는 버전별 불변이며 상태 변경과 감사 기록은 한 트랜잭션이다. */
class MissionInterfaces(private val jdbc: JdbcClient, private val tx: TransactionTemplate, private val log: OperationLog) {
    private val json = ObjectMapper()
    val profiles=dev.picasso.ops.service.missions.MissionProfiles(jdbc)
    fun spec(node:JsonNode?)=InterfaceMapping.spec(node,profiles)
    fun transform(wm:String,mapping:JsonNode,input:JsonNode)=InterfaceMapping.transform(wm,mapping,input,profiles)
    fun list(): List<InterfaceView> = jdbc.sql("SELECT * FROM ops.mission_interface ORDER BY created_at DESC, interface_id, version DESC").query { rs, _ ->
        InterfaceView(rs.getString("interface_id"), rs.getInt("version"), rs.getString("name"), rs.getString("work_master_id"),
            json.readTree(rs.getString("mapping")), json.readTree(rs.getString("sample")), rs.getString("status"),
            rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime::class.java).toString(), deployment=deployment(rs.getString("interface_id"),rs.getInt("version")),requiredStationId=resource(rs.getString("work_master_id")))
    }.list()

    fun get(id: String, version: Int): InterfaceView = jdbc.sql("SELECT * FROM ops.mission_interface WHERE interface_id=:id AND version=:version")
        .param("id", id).param("version", version).query { rs, _ ->
            InterfaceView(rs.getString("interface_id"), rs.getInt("version"), rs.getString("name"), rs.getString("work_master_id"),
                json.readTree(rs.getString("mapping")), json.readTree(rs.getString("sample")), rs.getString("status"),
                rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime::class.java).toString(), deployment=deployment(rs.getString("interface_id"),rs.getInt("version")),requiredStationId=resource(rs.getString("work_master_id")))
        }.optional().orElseThrow { InterfaceRejected("INTERFACE_NOT_FOUND", "호출 계약을 찾을 수 없습니다", 404) }

    fun create(spec: InterfaceSpec, actor: Actor): InterfaceView {
        try {
            return tx.execute {
                jdbc.sql("""INSERT INTO ops.mission_interface(interface_id, version, name, work_master_id, mapping, sample, status, created_by)
                    VALUES (:id, :version, :name, :wm, CAST(:mapping AS JSONB), CAST(:sample AS JSONB), 'DRAFT', :actor)""")
                    .param("id", spec.interfaceId).param("version", spec.version).param("name", spec.name).param("wm", spec.workMasterId)
                    .param("mapping", spec.mapping.toString()).param("sample", spec.sample.toString()).param("actor", actor.user).update()
                if(profiles.find(spec.workMasterId)==null)jdbc.sql("INSERT INTO ops.interface_deployment(interface_id,interface_version,deployment_version,mission_version,created_by,reason) VALUES(:id,:version,1,'CODE',:actor,'초기 코드 정의 고정')")
                    .param("id",spec.interfaceId).param("version",spec.version).param("actor",actor.header()).update()
                audit(actor, spec.interfaceId, spec.version, "CREATE_INTERFACE", null)
                get(spec.interfaceId, spec.version)
            }!!
        } catch (e: DuplicateKeyException) {
            throw InterfaceRejected("INTERFACE_VERSION_EXISTS", "이미 존재하는 계약 버전입니다. 새 버전 번호를 사용하세요", 409)
        }
    }

    fun state(id: String, version: Int, expected: String, next: String, reason: String, actor: Actor): InterfaceView {
        if (expected !in setOf("DRAFT", "ENABLED", "DISABLED") || next !in setOf("ENABLED", "DISABLED") || expected == next || (expected == "DRAFT" && next == "DISABLED"))
            throw InterfaceRejected("INTERFACE_BAD_TRANSITION", "지원하지 않는 공개 상태 변경입니다")
        if (reason.isBlank() || reason.length > 1000) throw InterfaceRejected("REASON_REQUIRED", "공개 상태 변경 사유를 1~1000자로 입력하세요")
        val original = get(id, version)
        transform(original.workMasterId, original.mapping, original.sample)
        if(next=="ENABLED" && deployment(id,version)==null)throw InterfaceRejected("DEPLOYMENT_REQUIRED","검증·활성화한 미션 버전을 먼저 배포하세요",409)
        return tx.execute {
            val count = jdbc.sql("UPDATE ops.mission_interface SET status=:next WHERE interface_id=:id AND version=:version AND status=:expected")
                .param("next", next).param("id", id).param("version", version).param("expected", expected).update()
            if (count != 1) throw InterfaceRejected("INTERFACE_STATE_CONFLICT", "공개 상태가 변경되었습니다. 다시 조회하세요", 409)
            audit(actor, id, version, "INTERFACE_$next", reason)
            get(id, version)
        }!!
    }

    fun draft(id: String, version: Int, input: JsonNode, requireEnabled: Boolean): JobOrderDraft {
        val view = get(id, version)
        if (requireEnabled && view.status != "ENABLED") throw InterfaceRejected("INTERFACE_NOT_ENABLED", "공개된 계약만 호출할 수 있습니다", 409)
        val draft=transform(view.workMasterId, view.mapping, input)
        return if(requireEnabled) dev.picasso.ops.service.joborders.DeployedJobOrderDraft(draft,view.deployment
            ?: throw InterfaceRejected("DEPLOYMENT_REQUIRED","실행 배포가 필요합니다",409)) else draft
    }
    private fun resource(wm:String):String? = jdbc.sql("SELECT station_id FROM ops.mission_resource_requirement WHERE work_master_id=:wm").param("wm",wm).query(String::class.java).optional().orElse(null)
    fun deployment(id:String,version:Int):JsonNode? = deployments(id,version).firstOrNull()
    fun deployments(id:String,version:Int):List<JsonNode> = jdbc.sql("SELECT * FROM ops.interface_deployment WHERE interface_id=:id AND interface_version=:version ORDER BY deployment_version DESC")
        .param("id",id).param("version",version).query {rs,_ -> json.createObjectNode().put("interfaceId",id).put("interfaceVersion",version)
            .put("mappingVersion",version).put("deploymentVersion",rs.getInt("deployment_version")).put("missionVersion",rs.getString("mission_version"))
            .put("createdBy",rs.getString("created_by")).put("reason",rs.getString("reason")).put("createdAt",rs.getString("created_at")) as JsonNode }.list()
    fun deploy(id:String,version:Int,mission:String,expected:Int,reason:String,actor:Actor):InterfaceView = tx.execute {
        if(reason.isBlank() || reason.length>1000 || !(mission=="CODE" || mission.toIntOrNull()?.let{it>0 && it.toString()==mission}==true))
            throw InterfaceRejected("BAD_DEPLOYMENT","미션 버전 CODE 또는 양의 정수와 사유가 필요합니다")
        val view=get(id,version)
        if(mission=="CODE" && profiles.find(view.workMasterId)!=null)throw InterfaceRejected("BAD_DEPLOYMENT","새 미션은 숫자 버전이 필요합니다",409)
        jdbc.sql("SELECT version FROM ops.mission_interface WHERE interface_id=:id AND version=:version FOR UPDATE").param("id",id).param("version",version).query(Int::class.java).single()
        val old=deployment(id,version)?.get("deploymentVersion")?.asInt()?:0
        if(old!=expected) throw InterfaceRejected("STALE_DEPLOYMENT","배포 버전이 바뀌었습니다. 다시 조회하세요",409)
        jdbc.sql("INSERT INTO ops.interface_deployment(interface_id,interface_version,deployment_version,mission_version,created_by,reason) VALUES(:id,:version,:deployment,:mission,:actor,:reason)")
            .param("id",id).param("version",version).param("deployment",old+1).param("mission",mission).param("actor",actor.header()).param("reason",reason.trim()).update()
        audit(actor,id,version,"INTERFACE_DEPLOY",reason.trim());get(id,version)
    }!!
    private fun audit(actor: Actor, id: String, version: Int, op: String, reason: String?) {
        log.append(UUID.randomUUID(), actor, "interface:$id/v$version", json.createObjectNode().put("op", op).put("interfaceId", id).put("version", version).toString(), reason, OperationResult.SUCCEEDED, null)
    }
}
