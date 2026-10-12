package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.requests.RequestRejected
import dev.picasso.ops.service.requests.WorkRequests
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest
import java.util.UUID

/** A single server-configured Mock MES identity. Empty token disables external access. */
@RestController
class WorkRequestController(private val requests:WorkRequests,
 @Value("\${ops.integration.token:}") private val token:String,
 @Value("\${ops.integration.source:mock-mes}") private val source:String) {
 private val json=ObjectMapper()
 init { require(source.matches(Regex("[a-z][a-z0-9-]{0,63}"))) }
 @PostMapping("/integration/v1/job-orders",consumes=[MediaType.APPLICATION_JSON_VALUE])
 fun receive(@RequestHeader("Authorization",required=false) auth:String?,@RequestBody(required=false) body:ByteArray?):ResponseEntity<Any> = checked {
  authenticate(auth)
  if(body==null || body.size>65536) throw RequestRejected("BAD_BODY","본문은 64KiB 이하 JSON 객체입니다")
  val payload=runCatching { json.readTree(body) }.getOrNull()?.takeIf { it.isObject } ?: throw RequestRejected("BAD_BODY","JSON 객체가 필요합니다")
  val accepted=requests.receive(source,payload)
  ResponseEntity.status(if(accepted.duplicate) 200 else 202).header("Location","/integration/v1/job-orders/${accepted.receipt.receiptId}").body(accepted)
 }
 @GetMapping("/integration/v1/job-orders/{id}")
 fun external(@RequestHeader("Authorization",required=false) auth:String?,@PathVariable id:UUID):ResponseEntity<Any> = checked {
  authenticate(auth); ResponseEntity.ok(requests.detail(id,source))
 }
 @GetMapping("/integration/v1/job-orders/{id}/results/latest")
 fun latestResult(@RequestHeader("Authorization",required=false) auth:String?,@PathVariable id:UUID):ResponseEntity<Any> = checked {
  authenticate(auth);ResponseEntity.ok(requests.result(id,source))
 }
 @GetMapping("/integration/v1/job-orders/{id}/results/{version}")
 fun result(@RequestHeader("Authorization",required=false) auth:String?,@PathVariable id:UUID,@PathVariable version:Int):ResponseEntity<Any> = checked {
  authenticate(auth);ResponseEntity.ok(requests.result(id,source,version))
 }
 @PostMapping("/integration/v1/job-orders/{id}/results/{version}/ack",consumes=[MediaType.APPLICATION_JSON_VALUE])
 fun acknowledge(@RequestHeader("Authorization",required=false) auth:String?,@PathVariable id:UUID,@PathVariable version:Int,
  @RequestBody(required=false) body:ByteArray?):ResponseEntity<Any> = checked {
  authenticate(auth)
  if(body==null || body.size>4096 || version<1) throw RequestRejected("BAD_ACK","결과 버전과 resultHash가 필요합니다")
  val value=runCatching { json.readTree(body) }.getOrNull()
  val hash=value?.get("resultHash")?.takeIf { it.isTextual }?.asText()
  if(value?.isObject!=true || value.fieldNames().asSequence().toSet()!=setOf("resultHash") || hash==null || !hash.matches(Regex("[0-9a-f]{64}")))
   throw RequestRejected("BAD_ACK","64자리 SHA-256 resultHash가 필요합니다")
  ResponseEntity.ok(requests.acknowledge(id,source,version,hash))
 }
 @PostMapping("/api/work-requests/manual",consumes=[MediaType.APPLICATION_JSON_VALUE])
 fun manual(@RequestHeader(dev.picasso.ops.service.actor.Actor.MODE_HEADER,required=false) mode:String?,
  @RequestHeader(dev.picasso.ops.service.actor.Actor.USER_HEADER,required=false) user:String?,
  @RequestBody(required=false) body:ByteArray?):ResponseEntity<Any> = guarded(mode,user,dev.picasso.ops.service.actor.Mode.OPERATOR) { actor -> checked {
  if(body==null || body.size>65536) throw RequestRejected("BAD_BODY","본문은 64KiB 이하 JSON 객체입니다")
  val value=runCatching { json.readTree(body) }.getOrNull()?.takeIf { it.isObject }
   ?: throw RequestRejected("BAD_BODY","JSON 객체가 필요합니다")
  val accepted=requests.receiveManual(actor,value)
  ResponseEntity.status(if(accepted.duplicate) 200 else 202).header("Location","/api/work-requests/${accepted.receipt.receiptId}").body(accepted)
 } }
 @GetMapping("/api/work-requests")
 fun list(@RequestParam(defaultValue="") search:String,@RequestParam(required=false) state:String?,@RequestParam(defaultValue="0") offset:Int,@RequestParam(defaultValue="20") limit:Int,@RequestParam(required=false) result:String?=null):ResponseEntity<Any> = checked {
  ResponseEntity.ok(requests.list(search,state?.takeIf { it.isNotBlank() },offset,limit,result?.takeIf{it.isNotBlank()}))
 }
 @PostMapping("/api/work-requests/{id}/recover",consumes=[MediaType.APPLICATION_JSON_VALUE])
 fun recover(@PathVariable id:UUID,@RequestHeader(dev.picasso.ops.service.actor.Actor.MODE_HEADER,required=false) mode:String?,
  @RequestHeader(dev.picasso.ops.service.actor.Actor.USER_HEADER,required=false) user:String?,@RequestBody(required=false) body:ByteArray?):ResponseEntity<Any> =
  guarded(mode,user,dev.picasso.ops.service.actor.Mode.OPERATOR) {actor -> checked {
   if(body==null || body.size>4096) throw RequestRejected("BAD_RECOVERY","재평가 본문은 4KiB 이하입니다")
   val value=runCatching{json.readTree(body)}.getOrNull() ?: throw RequestRejected("BAD_RECOVERY","JSON 객체가 필요합니다")
   val accepted=requests.recover(id,actor,value)
   ResponseEntity.status(if(accepted.duplicate) 200 else 202).body(accepted)
  }}
 @PostMapping("/api/work-requests/{id}/reviews",consumes=[MediaType.APPLICATION_JSON_VALUE])
 fun review(@PathVariable id:UUID,@RequestHeader(dev.picasso.ops.service.actor.Actor.MODE_HEADER,required=false) mode:String?,
  @RequestHeader(dev.picasso.ops.service.actor.Actor.USER_HEADER,required=false) user:String?,@RequestBody(required=false) body:ByteArray?):ResponseEntity<Any> =
  guarded(mode,user,dev.picasso.ops.service.actor.Mode.OPERATOR) {actor -> checked {
   if(body==null || body.size>4096)throw RequestRejected("BAD_REVIEW","확인 기록은 4KiB 이하입니다")
   val value=runCatching{json.readTree(body)}.getOrNull() ?: throw RequestRejected("BAD_REVIEW","JSON 객체가 필요합니다")
   ResponseEntity.ok(requests.review(id,actor,value))
  }}
 @GetMapping("/api/work-requests/summary")
 fun summary():ResponseEntity<Any> = checked { ResponseEntity.ok(requests.summary()) }
 @GetMapping("/api/work-requests/{id}")
 fun detail(@PathVariable id:UUID):ResponseEntity<Any> = checked { ResponseEntity.ok(requests.detail(id)) }
 private fun authenticate(auth:String?) {
  val supplied=auth?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: ""
  fun digest(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
  if(token.isBlank() || supplied.isBlank() || !MessageDigest.isEqual(digest(token),digest(supplied))) throw RequestRejected("UNAUTHORIZED","호출 인증이 필요합니다",401)
 }
 private inline fun checked(action:()->ResponseEntity<Any>):ResponseEntity<Any> = try { action() }
  catch(e:RequestRejected) { ResponseEntity.status(e.status).body(PreRejection(e.code,e.message)) }
}
