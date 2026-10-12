package dev.picasso.ops.service.stations

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.requests.RequestRejected
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class RfStation(val stationId:String,val name:String,val readySignal:String,val completeSignal:String,val timeoutSeconds:Int,val owner:UUID?,val createdBy:String,val createdAt:String)
data class StationRun(val runId:UUID,val stationId:String,val payload:JsonNode,val actor:String,val state:String,val reason:String,val receiptId:UUID?,val attempt:Int,val revision:Int,val createdAt:Instant,val phaseAt:Instant,val dispatchedAt:Instant?)
/** Durable station arbitration. Database locks cover decisions, never network calls. */
class StationCoordinator(private val jdbc:JdbcClient,private val tx:TransactionTemplate,private val port:StationPort,private val clock:Clock=Clock.systemUTC()) {
 private val json=ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
 fun stations():List<RfStation> = jdbc.sql("SELECT * FROM ops.rf_station ORDER BY station_id").query {r,_->RfStation(r.getString("station_id"),r.getString("name"),r.getString("ready_signal"),r.getString("complete_signal"),r.getInt("timeout_seconds"),r.getObject("owner",UUID::class.java),r.getString("created_by"),r.getString("created_at"))}.list()
 fun runs():List<StationRun> = jdbc.sql("SELECT * FROM ops.station_run ORDER BY created_at DESC,run_id LIMIT 100").query(::row).list()
 fun get(id:UUID):StationRun = jdbc.sql("SELECT * FROM ops.station_run WHERE run_id=:id").param("id",id).query(::row).optional().orElseThrow{RequestRejected("RUN_NOT_FOUND","스테이션 작업이 없습니다",404)}
 fun events(id:UUID):List<Map<String,Any?>> {get(id);return jdbc.sql("SELECT * FROM ops.station_event WHERE run_id=:id ORDER BY event_id").param("id",id).query {r,_->mapOf("state" to r.getString("state"),"reason" to r.getString("reason"),"actor" to r.getString("actor"),"recordedAt" to r.getString("recorded_at"),"context" to json.readTree(r.getString("context")))}.list()}
 /** Keep the state revision and its event history in one station-locked read. */
 fun snapshot(id:UUID):Map<String,Any> = tx.execute {
  lock(get(id).stationId)
  mapOf("run" to get(id),"events" to events(id))
 }!!
 fun createStation(actor:Actor,p:JsonNode):List<RfStation> {
  if(actor.mode!=Mode.ENGINEER) reject("엔지니어만 자원을 등록합니다",403)
  exact(p,setOf("stationId","name","readySignal","completeSignal","timeoutSeconds"))
  val id=text(p,"stationId",64);val name=text(p,"name",120);val ready=text(p,"readySignal",120);val complete=text(p,"completeSignal",120)
  val timeout=p["timeoutSeconds"]
  if(!id.matches(Regex("[a-z][a-z0-9-]{0,63}")) || ready==complete || timeout?.isIntegralNumber!=true || !timeout.canConvertToInt() || timeout.asInt() !in 5..3600) reject("식별자·서로 다른 신호·5~3600초 기한이 필요합니다")
  val signals=port.signals() ?: reject("현장 신호를 확인할 수 없습니다",503)
  if(ready !in signals || complete !in signals) reject("현재 현장에 선언된 일반 BOOLEAN 신호만 연결할 수 있습니다")
  val n=jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES(:id,:name,:ready,:complete,:timeout,:actor) ON CONFLICT DO NOTHING").param("id",id).param("name",name).param("ready",ready).param("complete",complete).param("timeout",timeout.asInt()).param("actor",actor.header()).update()
  if(n==0)reject("이미 등록된 스테이션입니다",409)
  return stations()
 }
 fun enqueue(actor:Actor,p:JsonNode):StationRun {
  if(actor.mode!=Mode.OPERATOR)reject("운영자만 작업을 요청합니다",403)
  exact(p,setOf("requestId","stationId","interfaceId","interfaceVersion","expectedDeploymentVersion","input"))
  val id=uuid(text(p,"requestId",36));val station=text(p,"stationId",64)
  text(p,"interfaceId",64)
  for(key in listOf("interfaceVersion","expectedDeploymentVersion")) if(p[key]?.isIntegralNumber!=true || !p[key].canConvertToInt() || p[key].asInt()<1)reject("양의 정수 계약·배포 버전이 필요합니다")
  if(p["input"]?.isObject!=true)reject("input 객체가 필요합니다")
  // Replays are resolved before live deployment validation, including after publication changes.
  val old=jdbc.sql("SELECT run_id FROM ops.station_run WHERE run_id=:id").param("id",id).query(UUID::class.java).optional().orElse(null)
  if(old!=null)return replay(id,p,actor)
  port.validate(p)
  return tx.execute {
   lock(station)
   val count=jdbc.sql("INSERT INTO ops.station_run(run_id,station_id,payload,actor,state,reason,created_at,phase_at) VALUES(:id,:station,CAST(:payload AS JSONB),:actor,'QUEUED','공유 자원 순서를 기다립니다',:at,:at) ON CONFLICT DO NOTHING").param("id",id).param("station",station).param("payload",p.toString()).param("actor",actor.header()).param("at",java.sql.Timestamp.from(clock.instant())).update()
   if(count==0) replay(id,p,actor) else {val run=get(id);event(run,"작업 접수",actor.header());run}
  }!!
 }
 private fun replay(id:UUID,p:JsonNode,actor:Actor):StationRun {val run=get(id);if(run.payload!=p || run.actor!=actor.header())reject("같은 요청 ID에 다른 입력·요청자가 있습니다",409);return run}
 @Synchronized fun tick() {
  val signals=runCatching{port.signals()}.getOrNull()
  for(station in stations()) {
   val dispatch=tx.execute {
    lock(station.stationId)
    var current=stations().single{it.stationId==station.stationId}
    if(current.owner==null) {
     val queued=jdbc.sql("SELECT run_id FROM ops.station_run WHERE station_id=:station AND state='QUEUED' ORDER BY created_at,run_id LIMIT 1").param("station",station.stationId).query(UUID::class.java).optional().orElse(null)
     if(queued!=null) {jdbc.sql("UPDATE ops.rf_station SET owner=:id WHERE station_id=:station").param("id",queued).param("station",station.stationId).update();transition(get(queued),"WAITING_READY","자원 확보; 설비 준비와 완료 신호 초기화를 기다립니다");current=current.copy(owner=queued)}
    }
    val run=current.owner?.let(::get) ?: return@execute null
    if(run.state=="WAITING_READY") {
     if(expired(run,current)) {transition(run,"TIMED_OUT","설비 준비 시간 초과; 로봇 전송 전 자원 해제",release=true);return@execute null}
     if(signals?.get(current.readySignal)?.value==true && signals[current.completeSignal]?.value==false) {
      transition(run,"DISPATCHING","설비 준비 확인; 고정 계약을 제출합니다",dispatch=true,context=json.valueToTree(signals))
      return@execute get(run.runId)
     }
    }
    if(run.state=="RETRYING") transition(run,"REVIEW_REQUIRED","재평가 중 프로세스 중단; 실행 관측과 현장 확인 필요")
    if(run.state in setOf("RUNNING","WAITING_ROBOT") && expired(run,current)) transition(run,"REVIEW_REQUIRED","작업 시간 초과; 물리 상태 확인 전 점유를 유지합니다")
    null
   }
   if(dispatch!=null) dispatch(dispatch)
   val owner=stations().single{it.stationId==station.stationId}.owner ?: continue
   val run=get(owner)
   // A previous process may have stopped after committing DISPATCHING. Same-key receipt lookup is safe, blind retry is not.
   if(run.state=="DISPATCHING" && dispatch==null) dispatch(run)
   if(run.state in setOf("RUNNING","WAITING_ROBOT")) observe(run,station,signals)
  }
 }
 private fun dispatch(run:StationRun) {
  val receipt=runCatching {port.dispatch(run.runId,run.payload,actor(run))}.getOrNull()
  tx.execute {lock(run.stationId);val current=get(run.runId);if(current.state!="DISPATCHING")return@execute
   if(receipt==null) transition(current,"REVIEW_REQUIRED","호출 결과 불명; 새 요청을 보내지 않고 점유 유지")
   else {jdbc.sql("UPDATE ops.station_run SET receipt_id=:receipt WHERE run_id=:id").param("receipt",receipt.id).param("id",run.runId).update();received(get(run.runId),receipt)}
  }
 }
 private fun received(run:StationRun,receipt:StationReceipt) {when {
  receipt.state=="ACCEPTED" -> transition(run,"RUNNING","로봇 수락; 저장 결과와 새 설비 완료 신호를 기다립니다",context=receipt.snapshot)
  receipt.safeToRetry -> transition(run,"WAITING_ROBOT","전송 전 미배정; 가용 기체를 재평가합니다")
  receipt.state=="REJECTED" -> transition(run,"REJECTED","계약 제출 거절; 자원 해제",release=true)
  else -> transition(run,"REVIEW_REQUIRED","접수 ${receipt.state}; 실행 여부 확인 전 점유 유지")
 }}
 private fun observe(run:StationRun,station:RfStation,signals:Map<String,StationSignal>?) {
  val receipt=run.receiptId?.let{runCatching{port.receipt(it)}.getOrNull()} ?: return
  if(run.state=="WAITING_ROBOT") {
   if(!receipt.safeToRetry) {tx.execute{lock(run.stationId);if(get(run.runId).state==run.state)received(get(run.runId),receipt)};return}
   if(signals?.get(station.readySignal)?.value!=true || signals[station.completeSignal]?.value!=false)return
   if(run.attempt>=3 || clock.instant().isBefore(run.phaseAt.plusSeconds(5)))return
   // Claim a retry before calling out. Crashes at this boundary require review, never another retry.
   val claimed=tx.execute{lock(run.stationId);val r=get(run.runId);if(r.state!="WAITING_ROBOT" || r.revision!=run.revision)false else {jdbc.sql("UPDATE ops.station_run SET attempt=attempt+1 WHERE run_id=:id").param("id",r.runId).update();transition(get(r.runId),"RETRYING","호스트 전송 전 미배정의 제한 재평가");true}}!!
   if(claimed) {
    val next=runCatching{port.retryForRun(receipt,UUID.nameUUIDFromBytes("${run.runId}/${run.attempt+1}".toByteArray()),actor(run),run.runId)}.getOrNull()
    tx.execute{lock(run.stationId);val r=get(run.runId);if(r.state=="RETRYING")if(next==null)transition(r,"REVIEW_REQUIRED","재평가 결과 불명; 점유 유지") else received(r,next)}
   }
   return
  }
  tx.execute {
   lock(run.stationId);val current=get(run.runId);if(current.state!="RUNNING")return@execute
   if(signals?.get(station.readySignal)==null || signals[station.completeSignal]==null) {transition(current,"REVIEW_REQUIRED","현장 신호 관측 불명; 점유를 유지하고 현장 확인을 기다립니다");return@execute}
   val complete=signals[station.completeSignal]
   if(receipt.physicalState=="PHYSICALLY_DONE" && complete?.value==true && complete.observedAt!=null && current.dispatchedAt!=null && complete.observedAt.isAfter(current.dispatchedAt))
    transition(current,"COMPLETED","로봇 물리 완료와 이번 작업의 설비 완료 신호 확인; 자원 해제",release=true,context=json.createObjectNode().apply{set<JsonNode>("execution",receipt.snapshot?:json.nullNode());set<JsonNode>("signals",json.valueToTree(signals))})
   else if(receipt.physicalState in setOf("OPERATOR_HOLD","IN_DOUBT","FAILED","STOPPED","ABORTED")) transition(current,"REVIEW_REQUIRED","로봇 ${receipt.physicalState}; 자동 재실행 없이 점유 유지",context=receipt.snapshot)
  }
 }
 fun decide(id:UUID,actor:Actor,p:JsonNode):StationRun = tx.execute {
  if(actor.mode!=Mode.OPERATOR)reject("운영자만 확인합니다",403)
  exact(p,setOf("expectedRevision","decision","reason"))
  val run=get(id);lock(run.stationId);val current=get(id)
  if(p["expectedRevision"]?.isIntegralNumber!=true || p["expectedRevision"].asLong()!=current.revision.toLong())reject("작업 상태가 바뀌었습니다. 다시 조회하세요",409)
  val decision=text(p,"decision",32);val reason=text(p,"reason",1000)
  when(decision) {
   "CANCEL_QUEUED" -> {if(current.state!="QUEUED")reject("대기열 작업만 취소할 수 있습니다",409);transition(current,"CANCELLED",reason,by=actor.header())}
   "RESUME_OBSERVATION" -> {if(current.state!="REVIEW_REQUIRED" || current.receiptId==null)reject("접수가 있는 확인 필요 작업만 관측을 재개합니다",409);transition(current,"RUNNING",reason,by=actor.header())}
   "CONFIRM_CLEAR" -> {if(current.state!="REVIEW_REQUIRED")reject("확인 필요 작업만 현장 확인 후 해제합니다",409);transition(current,"MANUALLY_CLEARED","현장 확인에 의한 자원 해제: $reason",release=true,by=actor.header());cleared(current,reason)}
   else -> reject("지원하지 않는 판단입니다")
  };get(id)
 }!!
 private fun expired(run:StationRun,station:RfStation)=!clock.instant().isBefore(run.phaseAt.plusSeconds(station.timeoutSeconds.toLong()))
 private fun transition(run:StationRun,state:String,reason:String,release:Boolean=false,dispatch:Boolean=false,by:String="system/station",context:JsonNode?=null) {
  jdbc.sql("UPDATE ops.station_run SET state=:state,reason=:reason,revision=revision+1,phase_at=:at,dispatched_at=CASE WHEN :dispatch THEN :at ELSE dispatched_at END WHERE run_id=:id").param("state",state).param("reason",reason).param("at",java.sql.Timestamp.from(clock.instant())).param("dispatch",dispatch).param("id",run.runId).update()
  if(release)jdbc.sql("UPDATE ops.rf_station SET owner=NULL WHERE station_id=:station AND owner=:id").param("station",run.stationId).param("id",run.runId).update()
  if(state in setOf("TIMED_OUT","CANCELLED","REJECTED") && run.receiptId!=null)
   jdbc.sql("UPDATE ops.work_request SET state='REJECTED',detail=:reason,updated_at=clock_timestamp() WHERE receipt_id=:id AND state='WAITING_RESOURCE'").param("id",run.receiptId).param("reason",reason).update()
  event(get(run.runId),reason,by,context)
 }
 /** WAITING_RESOURCE never reached DISPATCHING and UNASSIGNED failed eligibility before sending; DISPATCHING may have been sent. */
 private fun cleared(run:StationRun,reason:String) {
  val id=run.receiptId ?: return
  fun close(from:String,to:String,detail:String)=jdbc.sql("UPDATE ops.work_request SET state=:to,detail=:detail,updated_at=clock_timestamp() WHERE receipt_id=:id AND state IN (:from)").param("to",to).param("detail",detail.take(2000)).param("id",id).param("from",from.split(',')).update()
  close("WAITING_RESOURCE,UNASSIGNED","REJECTED","현장 확인으로 자원을 해제했습니다. 이 접수는 실행하지 않습니다: $reason")
  close("DISPATCHING","IN_DOUBT","현장 확인으로 자원을 해제했습니다. 제출 결과는 실행 관측으로 확인합니다: $reason")
 }
 private fun event(run:StationRun,reason:String,by:String,context:JsonNode?=null) {jdbc.sql("INSERT INTO ops.station_event(run_id,state,reason,actor,context,recorded_at) VALUES(:id,:state,:reason,:actor,CAST(:context AS JSONB),:at)").param("id",run.runId).param("state",run.state).param("reason",reason).param("actor",by).param("context",(context?:json.nullNode()).toString()).param("at",java.sql.Timestamp.from(clock.instant())).update()}
 private fun lock(station:String) {jdbc.sql("SELECT station_id FROM ops.rf_station WHERE station_id=:id FOR UPDATE").param("id",station).query(String::class.java).optional().orElseThrow{RequestRejected("STATION_NOT_FOUND","스테이션이 없습니다",404)}}
 private fun actor(run:StationRun)=Actor(Mode.OPERATOR,run.actor.substringAfter('/'))
 private fun row(r:java.sql.ResultSet,@Suppress("UNUSED_PARAMETER") i:Int)=StationRun(r.getObject("run_id",UUID::class.java),r.getString("station_id"),json.readTree(r.getString("payload")),r.getString("actor"),r.getString("state"),r.getString("reason"),r.getObject("receipt_id",UUID::class.java),r.getInt("attempt"),r.getInt("revision"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("phase_at").toInstant(),r.getTimestamp("dispatched_at")?.toInstant())
 private fun exact(p:JsonNode,fields:Set<String>) {if(!p.isObject || p.fieldNames().asSequence().toSet()!=fields)reject("요청 필드를 확인하세요")}
 private fun text(p:JsonNode,key:String,max:Int)=p[key]?.takeIf{it.isTextual && it.asText().isNotBlank() && it.asText().length<=max}?.asText() ?: reject("$key 문자열이 필요합니다")
 private fun uuid(s:String)=runCatching{UUID.fromString(s)}.getOrNull()?.takeIf{it.toString()==s} ?: reject("정규 UUID가 필요합니다")
 private fun reject(message:String,status:Int=400):Nothing=throw RequestRejected("STATION_REJECTED",message,status)
}
