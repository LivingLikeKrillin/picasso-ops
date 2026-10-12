package dev.picasso.ops.service.stations

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.interfaces.MissionInterfaces
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.requests.WorkRequests
import java.time.Instant
import java.util.UUID

data class StationSignal(val value: Boolean, val observedAt: Instant?)
data class StationReceipt(val id: UUID, val state: String, val safeToRetry: Boolean, val updatedAt: String, val physicalState: String?, val snapshot: JsonNode?, val resultVersion: Int? = null)
interface StationPort {
 fun validate(payload: JsonNode)
 fun signals(): Map<String, StationSignal>?
 fun dispatch(id: UUID, payload: JsonNode, actor: Actor): StationReceipt
 fun receipt(id: UUID): StationReceipt
 fun retryForRun(receipt:StationReceipt,attempt:UUID,actor:Actor,runId:UUID):StationReceipt=retry(receipt,attempt,actor)
 fun retry(receipt: StationReceipt, attempt: UUID, actor: Actor): StationReceipt
}
/** Uses durable receipts; an uncertain submission is never sent again. */
class OpsStationPort(private val requests: WorkRequests, private val interfaces: MissionInterfaces, private val host: HostReads): StationPort {
 private val json=ObjectMapper()
 override fun validate(payload:JsonNode) {
  val wm=interfaces.get(payload["interfaceId"].asText(),payload["interfaceVersion"].asInt()).workMasterId
  val required=requests.resources.required(wm)
  require(required==null || payload["stationId"]?.asText()==required) { "미션이 요구하는 스테이션을 선택하세요" }
  val draft=interfaces.draft(payload["interfaceId"].asText(),payload["interfaceVersion"].asInt(),payload["input"],true)
  val deployment=(draft as dev.picasso.ops.service.joborders.DeployedJobOrderDraft).deployment
  require(deployment["deploymentVersion"].asInt()==payload["expectedDeploymentVersion"].asInt()) { "조회한 계약 배포가 변경되었습니다" }
 }
 override fun signals():Map<String,StationSignal>? {
  val list=(host.cell() as? HostCall.Ok)?.value?.path("cell")?.path("signals")?.takeIf{it.isArray} ?: return null
  return list.filter { it.path("kind").asText()=="BOOLEAN" && it.path("safety").isBoolean && !it.path("safety").asBoolean() && it.path("value").asText() in setOf("true","false") }
   .associate { it.path("name").asText() to StationSignal(it.path("value").asText()=="true",it.path("observedAt").takeIf{it.isTextual}?.asText()?.let(Instant::parse)) }
 }
 override fun dispatch(id:UUID,payload:JsonNode,actor:Actor):StationReceipt {
  if(payload["receiptId"]!=null)return mapped(requests.dispatchQueued(UUID.fromString(payload["receiptId"].asText()),actor).receipt.receiptId)
  val body=json.createObjectNode().put("requestId",id.toString()).put("expectedDeploymentVersion",payload["expectedDeploymentVersion"].asInt())
  body.set<JsonNode>("input",payload["input"])
  return mapped(requests.receiveInterface(actor,payload["interfaceId"].asText(),payload["interfaceVersion"].asInt(),body,id).receipt.receiptId)
 }
 override fun receipt(id:UUID)=mapped(id)
 private fun mapped(id:UUID):StationReceipt {
  val receipt=requests.get(id)
  // Use stored result evidence. A missing current host execution never means the work did not start.
  val stored=runCatching{requests.result(id,receipt.sourceSystemId)}.getOrNull();val saved=stored?.get("snapshot") as? JsonNode
  return StationReceipt(id,receipt.state,receipt.state=="UNASSIGNED" && receipt.submission==null && receipt.lastExecution==null,receipt.updatedAt,saved?.path("jobResponse")?.path("physicalState")?.takeIf{it.isTextual}?.asText(),saved,stored?.get("version") as? Int)
 }
 override fun retry(receipt:StationReceipt,attempt:UUID,actor:Actor):StationReceipt=error("자원 작업 ID가 필요합니다")
 override fun retryForRun(receipt:StationReceipt,attempt:UUID,actor:Actor,runId:UUID):StationReceipt {
  val body=json.createObjectNode().put("requestId",attempt.toString()).put("expectedUpdatedAt",receipt.updatedAt).put("reason","공유 스테이션의 전송 전 미배정 재평가")
  requests.recover(receipt.id,actor,body,runId)
  return mapped(receipt.id)
 }
}
/** REWORK of a resource-managed receipt goes through station coordination. Executions without one are forwarded unchanged. */
class StationReworkGuard(private val requests:WorkRequests,private val stations:StationCoordinator):dev.picasso.ops.service.incidents.ReworkGuard {
 private val json=ObjectMapper()
 override fun guard(actor:Actor,executionId:String,instanceId:String,forward:()->dev.picasso.ops.service.incidents.HoldResolveOutcome):dev.picasso.ops.service.incidents.HoldResolveOutcome {
  val receipt=requests.executionReceipt(instanceId,executionId)
  if(receipt?.requiredStationId==null)return forward()
  val context=json.createObjectNode().put("executionId",executionId).put("instanceId",instanceId).put("receiptId",receipt.receiptId.toString())
  return stations.rework(receipt.receiptId,actor,context,forward) {when {
   it.result==OperationResult.SUCCEEDED || it.confirmation==OperationResult.CONFIRMED_APPLIED -> true
   it.result==OperationResult.REJECTED || it.confirmation==OperationResult.CONFIRMED_NOT_APPLIED -> false
   else -> null
  }}
 }
}
