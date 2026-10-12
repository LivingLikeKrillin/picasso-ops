package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.joborders.FormRejection
import dev.picasso.ops.service.joborders.JobOrderForm
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class ContractField(val path: String, val constraint: String, val target: String)
data class JobContract(val workMasterId: String, val name: String, val description: String, val editable: Boolean,
    val requiredEvidence: String, val fields: List<ContractField>, val sample: JsonNode,val profile:JsonNode?=null)
data class JobContracts(val contracts: List<JobContract>)

/** Ops 입력 계약 조회와 부작용 없는 변환 미리보기. 실제 제출과 같은 JobOrderForm을 사용한다. */
@RestController
class JobContractController(private val profiles:dev.picasso.ops.service.missions.MissionProfiles?=null) {
    private val json = ObjectMapper()

    @GetMapping("/api/job-contracts")
    fun contracts(): JobContracts = JobContracts(listOf(
        JobContract(JobOrderForm.INSPECT_ASSET, "자산 점검", "대상 위치로 이동하고 자산을 점검합니다.", false, "E0", listOf(
            ContractField("targets[].id", "필수 문자열 · 1~${JobOrderForm.MAX_TARGET_ID_LENGTH}자 · 중복 불가", "equipmentRequirements[].id"),
            ContractField("targets[].location", "필수 비어 있지 않은 문자열", "equipmentRequirements[].properties.location"),
        ), json.readTree("""{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"}]}""")),
        JobContract(JobOrderForm.PREPARE_SEQUENCED_RACK, "서열 랙 준비", "제시된 자재를 지정 슬롯에 준비합니다. 실행 정의를 초안으로 편집하고 검증할 수 있습니다.", true, "E2", listOf(
            ContractField("slots[]", "필수 문자열 목록 · 1개 이상 · 중복 불가", "equipmentRequirements[destination].id"),
            ContractField("material", "필수 비어 있지 않은 문자열", "materialRequirements[].materialDefinitionId / equipmentRequirements[].properties.material"),
            ContractField("presentation", "필수 비어 있지 않은 문자열", "equipmentRequirements[source].id"),
        ), json.readTree("""{"workMasterId":"PrepareSequencedRack","slots":["RACK-204.S01"],"material":"ENGINE-COVER-A","presentation":"SEQ-IN-02.BIN-A"}""")),
    )+profiles?.list().orEmpty().map{profile ->
        val sample=profile["sample"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("workMasterId",profile["workMasterId"].asText())
        JobContract(profile["workMasterId"].asText(),profile["name"].asText(),profile.path("description").asText(),true,profile["requiredEvidence"].asText(),
            profile["inputSchema"]["properties"].fields().asSequence().map{(key,schema)->ContractField(key,schema.toString(),"선언형 orderMapping")}.toList(),sample,profile)
    })

    @PostMapping("/api/job-orders/preview", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun preview(@RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> {
        val node = body?.let { runCatching { json.readTree(it) }.getOrNull() }
        return try {
            ResponseEntity.ok(JobOrderForm.jobOrder(profiles?.find(node?.path("workMasterId")?.asText()?:"")?.let{profiles.read(it,node!!.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply{remove("workMasterId")})} ?: JobOrderForm.read(node), "JO-PREVIEW", json))
        } catch(e:dev.picasso.ops.service.interfaces.InterfaceRejected){ResponseEntity.status(e.status).body(PreRejection(e.code,e.message))
        } catch (e: FormRejection) {
            ResponseEntity.badRequest().body(PreRejection(e.error, e.message ?: ""))
        }
    }
}
