package dev.picasso.ops.service.stations

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.requests.RequestRejected
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Mission-wide immutable requirements are enforced at durable admission, independent of caller input. */
class MissionResources(private val jdbc:JdbcClient,private val tx:TransactionTemplate) {
 private val json=ObjectMapper()
 private fun missionLock(wm:String) {jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:wm))").param("wm",wm).query{_,_->true}.single()}
 fun list():List<Map<String,Any?>> = jdbc.sql("SELECT * FROM ops.mission_resource_requirement ORDER BY work_master_id").query{r,_->mapOf("workMasterId" to r.getString("work_master_id"),"stationId" to r.getString("station_id"),"revision" to r.getInt("revision"),"createdBy" to r.getString("created_by"),"reason" to r.getString("reason"),"createdAt" to r.getString("created_at"))}.list()
 fun required(wm:String):String? = jdbc.sql("SELECT station_id FROM ops.mission_resource_requirement WHERE work_master_id=:wm").param("wm",wm).query(String::class.java).optional().orElse(null)
 fun create(actor:Actor,wm:String,station:String,reason:String):Map<String,Any?> = tx.execute {
  if(actor.mode!=Mode.ENGINEER)fail("엔지니어만 자원 요구를 등록합니다",403)
  if(wm.isBlank() || wm.length>120 || station.isBlank() || reason.isBlank() || reason.length>1000)fail("미션·스테이션·등록 사유가 필요합니다",400)
  missionLock(wm)
  // RECEIVED is conservatively included: its mission may not have been normalized yet.
  val active=jdbc.sql("""SELECT count(*) FROM ops.work_request r WHERE state='RECEIVED' OR
   (normalized_order->>'workMasterId'=:wm AND state<>'REJECTED' AND NOT EXISTS
    (SELECT 1 FROM ops.work_result v WHERE v.receipt_id=r.receipt_id AND v.version=(SELECT max(version) FROM ops.work_result WHERE receipt_id=r.receipt_id)
     AND v.snapshot->'jobResponse'->>'physicalState'='PHYSICALLY_DONE'))""").param("wm",wm).query(Long::class.java).single()
  val stationActive=jdbc.sql("""SELECT count(*) FROM ops.station_run r JOIN ops.mission_interface i
   ON i.interface_id=r.payload->>'interfaceId' AND i.version=(r.payload->>'interfaceVersion')::integer
   WHERE i.work_master_id=:wm AND r.state NOT IN ('COMPLETED','TIMED_OUT','REJECTED','MANUALLY_CLEARED','CANCELLED')""").param("wm",wm).query(Long::class.java).single()
  if(active>0 || stationActive>0)fail("처리 중이거나 완료 근거가 없는 접수가 있습니다. 자원 요구 등록 전 기존 작업을 확인하세요")
  val exists=jdbc.sql("SELECT station_id FROM ops.rf_station WHERE station_id=:station").param("station",station).query(String::class.java).optional().orElse(null)
  if(exists==null)fail("등록된 스테이션을 선택하세요",404)
  val count=jdbc.sql("INSERT INTO ops.mission_resource_requirement(work_master_id,station_id,created_by,reason) VALUES(:wm,:station,:actor,:reason) ON CONFLICT DO NOTHING").param("wm",wm).param("station",station).param("actor",actor.header()).param("reason",reason.trim()).update()
  if(count==0)fail("미션의 자원 요구는 이미 고정되어 있습니다")
  list().single{it["workMasterId"]==wm}
 }!!
 /** The receipt already exists. Queue insertion and the fixed resource are committed together. */
 fun admit(id:UUID,actor:Actor,stationRunId:UUID?=null):Boolean = tx.execute {
  val row=jdbc.sql("SELECT normalized_order,payload FROM ops.work_request WHERE receipt_id=:id").param("id",id).query{r,_->json.readTree(r.getString(1)) to json.readTree(r.getString(2))}.single()
  val wm=row.first["workMasterId"].asText();missionLock(wm)
  val required=required(wm)
  if(stationRunId!=null) {
   val station=jdbc.sql("SELECT station_id FROM ops.station_run WHERE run_id=:run").param("run",stationRunId).query(String::class.java).single()
   stationLock(station)
   if(required!=null && required!=station)fail("미션이 요구하는 스테이션과 다릅니다")
   val allowed=jdbc.sql("SELECT count(*) FROM ops.station_run r JOIN ops.rf_station s ON s.owner=r.run_id WHERE r.run_id=:run AND r.state='DISPATCHING'").param("run",stationRunId).query(Long::class.java).single()
   if(allowed!=1L)fail("스테이션 점유와 제출 상태를 확인할 수 없습니다")
   jdbc.sql("UPDATE ops.work_request SET required_station_id=:station WHERE receipt_id=:id").param("station",station).param("id",id).update()
   jdbc.sql("UPDATE ops.station_run SET receipt_id=:id WHERE run_id=:run").param("id",id).param("run",stationRunId).update()
   return@execute false
  }
  if(required==null)return@execute false
  stationLock(required)
  val payload=json.createObjectNode().put("receiptId",id.toString()).put("workMasterId",wm)
  for(key in listOf("interfaceId","interfaceVersion","input"))row.second[key]?.let{payload.set<JsonNode>(key,it)}
  row.first["deployment"]?.let{payload.put("expectedDeploymentVersion",it["deploymentVersion"].asInt())}
  jdbc.sql("INSERT INTO ops.station_run(run_id,station_id,payload,actor,state,reason,receipt_id,created_at,phase_at) VALUES(:id,:station,CAST(:payload AS JSONB),:actor,'QUEUED','미션 필수 자원 순서를 기다립니다',:id,clock_timestamp(),clock_timestamp())").param("id",id).param("station",required).param("payload",payload.toString()).param("actor",actor.header()).update()
  jdbc.sql("INSERT INTO ops.station_event(run_id,state,reason,actor,context,recorded_at) VALUES(:id,'QUEUED','공통 접수의 필수 자원 대기',:actor,CAST(:context AS JSONB),clock_timestamp())").param("id",id).param("actor",actor.header()).param("context",json.createObjectNode().put("workMasterId",wm).put("requiredStationId",required).put("requirementRevision",1).toString()).update()
  jdbc.sql("UPDATE ops.work_request SET required_station_id=:station,state='WAITING_RESOURCE',detail='필수 공유 자원 대기',updated_at=clock_timestamp() WHERE receipt_id=:id").param("station",required).param("id",id).update()
  true
 }!!
 fun authorize(id:UUID,wm:String) {
  val station=jdbc.sql("SELECT required_station_id FROM ops.work_request WHERE receipt_id=:id").param("id",id).query{r,_->r.getString(1) to true}.single().first
  val required=required(wm)
  if(required!=null && required!=station)fail("필수 자원 접수를 거치지 않은 실행입니다")
  if(station!=null) {
   val owned=jdbc.sql("SELECT count(*) FROM ops.station_run r JOIN ops.rf_station s ON s.owner=r.run_id WHERE r.receipt_id=:id AND s.station_id=:station AND r.state IN ('DISPATCHING','RETRYING')").param("id",id).param("station",station).query(Long::class.java).single()
   if(owned!=1L)fail("필수 자원을 점유한 조정기만 실행할 수 있습니다")
  }
 }
 fun context(id:UUID):Map<String,Any?>? = jdbc.sql("""SELECT r.run_id,r.station_id,r.state,r.reason,s.owner,
  (SELECT count(*) FROM ops.station_run q WHERE q.station_id=r.station_id AND q.state='QUEUED' AND (q.created_at,q.run_id)<(r.created_at,r.run_id)) AS ahead
  FROM ops.station_run r JOIN ops.rf_station s ON s.station_id=r.station_id WHERE r.receipt_id=:id ORDER BY r.created_at LIMIT 1""").param("id",id).query{r,_->mapOf("runId" to r.getObject("run_id",UUID::class.java),"stationId" to r.getString("station_id"),"state" to r.getString("state"),"reason" to r.getString("reason"),"owner" to r.getObject("owner",UUID::class.java),"queuedAhead" to r.getLong("ahead"))}.optional().orElse(null)
 private fun stationLock(station:String) {jdbc.sql("SELECT station_id FROM ops.rf_station WHERE station_id=:station FOR UPDATE").param("station",station).query(String::class.java).single()}
 private fun fail(reason:String,status:Int=409):Nothing=throw RequestRejected("RESOURCE_REQUIREMENT",reason,status)
}
