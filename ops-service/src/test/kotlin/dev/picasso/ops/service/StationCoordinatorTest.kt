package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.requests.RequestRejected
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.incidents.HoldResolutions
import dev.picasso.ops.service.interfaces.MissionInterfaces
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.requests.WorkRequests
import dev.picasso.ops.service.stations.*
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.*
import java.util.UUID
import kotlin.test.*

class StationCoordinatorTest {
 private val ds=DriverManagerDataSource(PostgresSupport.jdbcUrl,PostgresSupport.username,PostgresSupport.password)
 private val jdbc=JdbcClient.create(ds)
 private val tx=TransactionTemplate(DataSourceTransactionManager(ds))
 private val json=ObjectMapper()
 private val engineer=Actor(Mode.ENGINEER,"lee")
 private val operator=Actor(Mode.OPERATOR,"kim")
 private var now=Instant.parse("2026-10-12T00:00:00Z")
 private val clock=object:Clock(){override fun getZone()=ZoneOffset.UTC;override fun withZone(zone:ZoneId)=this;override fun instant()=now}
 private val port=FakePort()
 private fun coordinator()=StationCoordinator(jdbc,tx,port,clock)
 private val service get()=coordinator()
 inner class FakePort:StationPort {
  var signals:Map<String,StationSignal>?=mapOf("ready" to StationSignal(true,now),"complete" to StationSignal(false,now))
  var receiptState="ACCEPTED";var physical:String?=null;var unknown=false;var valid=true;var sends=0;var retries=0
  /** A result already stored for the receipt when it is sent. */
  var preResult:String?=null
  val receipts=mutableMapOf<UUID,StationReceipt>()
  private val written=mutableMapOf<UUID,String>()
  override fun validate(payload:JsonNode){require(valid)}
  override fun signals()=signals
  @Synchronized override fun dispatch(id:UUID,payload:JsonNode,actor:Actor):StationReceipt {
   receipts[id]?.let{return it}
   sends++
   jdbc.sql("INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state) VALUES(:id,'ops:interface',:key,'hash','{}',:key,:state)").param("id",id).param("key",id.toString()).param("state",receiptState).update()
   preResult?.let{result(id,it)}
   val r=StationReceipt(id,receiptState,receiptState=="UNASSIGNED","v1",physical,null);receipts[id]=r
   if(unknown)error("response lost")
   return r
  }
  /** A changed [physical] is a new stored result version; the latest stored result is what the port reports. */
  override fun receipt(id:UUID):StationReceipt {
   physical?.let{if(written[id]!=it){result(id,it);written[id]=it}}
   val latest=jdbc.sql("SELECT version,snapshot->'jobResponse'->>'physicalState' FROM ops.work_result WHERE receipt_id=:id ORDER BY version DESC LIMIT 1").param("id",id).query{r,_->r.getInt(1) to r.getString(2)}.optional().orElse(null)
   return receipts.getValue(id).copy(physicalState=latest?.second,resultVersion=latest?.first)
  }
  override fun retry(receipt:StationReceipt,attempt:UUID,actor:Actor):StationReceipt {retries++;return receipt.copy(state="ACCEPTED",safeToRetry=false).also{receipts[receipt.id]=it}}
 }
 private fun result(id:UUID,state:String) {jdbc.sql("""INSERT INTO ops.work_result(receipt_id,version,instance_id,execution_id,response_id,response_version,snapshot,result_hash)
  SELECT :id,COALESCE(max(version),0)+1,'mw-1','exec-1','r-'||(COALESCE(max(version),0)+1),COALESCE(max(version),0)+1,CAST(:snapshot AS JSONB),'hash' FROM ops.work_result WHERE receipt_id=:id""")
  .param("id",id).param("snapshot","""{"jobResponse":{"physicalState":"$state"}}""").update()}
 @BeforeTest fun fresh(){OpsSchema.flyway(ds,cleanable=true).apply{clean();migrate()};service.createStation(engineer,json.readTree("""{"stationId":"inspection-1","name":"공유 검사","readySignal":"ready","completeSignal":"complete","timeoutSeconds":10}"""))}
 private fun enqueue(id:UUID=UUID.randomUUID())=service.enqueue(operator,json.readTree("""{"requestId":"$id","stationId":"inspection-1","interfaceId":"inspect","interfaceVersion":1,"expectedDeploymentVersion":1,"input":{"assets":[]}}"""))
 private fun signal(ready:Boolean=true,complete:Boolean=false,at:Instant=now){port.signals=mapOf("ready" to StationSignal(ready,at),"complete" to StationSignal(complete,at))}
 private fun decide(id:UUID,decision:String,revision:Int=service.get(id).revision)=service.decide(id,operator,json.readTree("""{"expectedRevision":$revision,"decision":"$decision","reason":"현장 상태 확인"}"""))
 @Test fun `자원 점유는 직렬화되고 로봇 완료와 새로운 설비 완료 근거가 모두 필요하다`() {
  val a=enqueue();now=now.plusMillis(1);val b=enqueue();service.tick()
  assertEquals("RUNNING",service.get(a.runId).state);assertEquals("QUEUED",service.get(b.runId).state);assertEquals(1,port.sends)
  port.physical="PHYSICALLY_DONE";service.tick();assertEquals(a.runId,service.stations().single().owner)
  now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(a.runId).state);assertNull(service.stations().single().owner)
  service.tick();assertEquals("WAITING_READY",service.get(b.runId).state);assertEquals(1,port.sends)
  signal();service.tick();assertEquals("RUNNING",service.get(b.runId).state);assertEquals(2,port.sends)
 }
 @Test fun `이전 완료 true와 로봇 수락만으로 점유를 풀지 않는다`() {
  signal(complete=true);val run=enqueue();service.tick();assertEquals("WAITING_READY",service.get(run.runId).state);assertEquals(0,port.sends)
  signal();service.tick();signal(complete=true,at=now.minusSeconds(5));port.physical="PHYSICALLY_DONE";service.tick();assertEquals("RUNNING",service.get(run.runId).state)
 }
 @Test fun `설비 지연은 로봇 전송 없이 기다리다가 준비 후 진행한다`() {
  signal(ready=false);val run=enqueue();service.tick();assertEquals(0,port.sends)
  now=now.plusSeconds(3);signal();service.tick();assertEquals("RUNNING",service.get(run.runId).state)
 }
 @Test fun `준비 시간 초과는 전송 전 자원을 해제한다`() {
  port.signals=null;val run=enqueue();service.tick();now=now.plusSeconds(11);service.tick()
  assertEquals("TIMED_OUT",service.get(run.runId).state);assertEquals(0,port.sends);assertNull(service.stations().single().owner)
 }
 @Test fun `실행 시간 초과는 점유를 유지하고 자동 재실행하지 않는다`() {
  val run=enqueue();service.tick();now=now.plusSeconds(11);service.tick();service.tick()
  assertEquals("REVIEW_REQUIRED",service.get(run.runId).state);assertEquals(run.runId,service.stations().single().owner);assertEquals(1,port.sends)
 }
 @Test fun `응답 불명은 운영자 확인 전 해제나 재전송하지 않는다`() {
  port.unknown=true;val run=enqueue();service.tick();coordinator().tick()
  assertEquals("REVIEW_REQUIRED",service.get(run.runId).state);assertEquals(1,port.sends)
  decide(run.runId,"CONFIRM_CLEAR");assertEquals("MANUALLY_CLEARED",service.get(run.runId).state);assertNull(service.stations().single().owner)
  assertEquals("operator/kim",service.events(run.runId).last()["actor"])
 }
 @Test fun `전송 전 미배정만 제한 재평가하고 같은 접수를 유지한다`() {
  port.receiptState="UNASSIGNED";val run=enqueue();service.tick();assertEquals("WAITING_ROBOT",service.get(run.runId).state)
  now=now.plusSeconds(5);service.tick();assertEquals("RUNNING",service.get(run.runId).state);assertEquals(run.runId,service.get(run.runId).receiptId);assertEquals(1,port.sends);assertEquals(1,port.retries)
 }
 @Test fun `운영자 보류는 재할당하지 않고 관측 재개로 완료한다`() {
  val run=enqueue();service.tick();port.physical="OPERATOR_HOLD";service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state)
  decide(run.runId,"RESUME_OBSERVATION");now=now.plusSeconds(1);port.physical="PHYSICALLY_DONE";signal(complete=true);service.tick()
  assertEquals("COMPLETED",service.get(run.runId).state);assertEquals(1,port.sends)
 }
 @Test fun `정착한 비성공 물리 상태는 시간 초과 전에 확인 필요로 넘기고 점유를 유지하며 취소 중은 기다린다`() {
  for(state in listOf("UNVERIFIED","PARTIAL","FAILED","ABORTED","IN_DOUBT","OPERATOR_HOLD")) {
   port.physical=null;val run=enqueue();service.tick();assertEquals("RUNNING",service.get(run.runId).state,state)
   port.physical=state;service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state,state);assertEquals(run.runId,service.stations().single().owner,state)
   decide(run.runId,"CONFIRM_CLEAR")
  }
  port.physical=null;val run=enqueue();service.tick();port.physical="CANCELING";service.tick();service.tick()
  assertEquals("RUNNING",service.get(run.runId).state);assertEquals(run.runId,service.stations().single().owner)
 }
 @Test fun `같은 요청 재접수는 현재 계약이 바뀌어도 원래 작업을 반환한다`() {
  val run=enqueue();port.valid=false;assertEquals(run,enqueue(run.runId));assertEquals(1,service.runs().size)
  assertFailsWith<RequestRejected>{service.enqueue(Actor(Mode.OPERATOR,"other"),run.payload)}
 }
 @Test fun `오래된 판단 버전과 비운영자 조작은 거절한다`() {
  val run=enqueue();service.tick();now=now.plusSeconds(11);service.tick()
  assertFailsWith<RequestRejected>{decide(run.runId,"CONFIRM_CLEAR",1)}
  assertFailsWith<RequestRejected>{service.decide(run.runId,engineer,json.readTree("{}"))}
  assertEquals(run.runId,service.stations().single().owner)
 }
 @Test fun `재기동은 점유와 대기열과 이벤트를 보존한다`() {
  val a=enqueue();now=now.plusMillis(1);val b=enqueue();service.tick();coordinator().tick()
  assertEquals(a.runId,coordinator().stations().single().owner);assertEquals("QUEUED",coordinator().get(b.runId).state);assertEquals(1,port.sends);assertTrue(coordinator().events(a.runId).size>=3)
 }
 @Test fun `전송 기록 직후 재기동은 동일 키를 확인하고 추가 실행하지 않는다`() {
  val run=enqueue();service.tick();jdbc.sql("UPDATE ops.station_run SET state='DISPATCHING' WHERE run_id=:id").param("id",run.runId).update()
  coordinator().tick();assertEquals(1,port.sends);assertEquals("RUNNING",service.get(run.runId).state)
 }
 @Test fun `실행 중 신호 단절은 확인 필요로 전환하고 점유를 유지한다`() {
  val run=enqueue();service.tick();port.signals=null;service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state);assertEquals(run.runId,service.stations().single().owner);assertEquals(1,port.sends)
 }
 @Test fun `재평가 중 재기동은 자동 재전송 없이 현장 확인으로 넘긴다`() {
  val run=enqueue();service.tick();jdbc.sql("UPDATE ops.station_run SET state='RETRYING' WHERE run_id=:id").param("id",run.runId).update();coordinator().tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state);assertEquals(1,port.sends)
 }
 @Test fun `확인 이벤트는 DB에서 수정 삭제 통째 비우기를 금지한다`() {
  val run=enqueue();assertFails{jdbc.sql("UPDATE ops.station_event SET reason='changed' WHERE run_id=:id").param("id",run.runId).update()};assertFails{jdbc.sql("DELETE FROM ops.station_event").update()};assertFails{jdbc.sql("TRUNCATE ops.station_event").update()}
 }
 @Test fun `동시 접수와 워커는 한 자원에서 추가 실행을 만들지 않는다`() {
  val id=UUID.randomUUID();val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
  try {val accepted=(1..2).map{executor.submit<StationRun>{enqueue(id)}}.map{it.get()};assertEquals(1,accepted.map{it.runId}.distinct().size)
   val ticks=(1..2).map{executor.submit{coordinator().tick()}};ticks.forEach{it.get()};assertEquals(1,port.sends);assertEquals(id,service.stations().single().owner)
  }finally{executor.shutdownNow()}
 }
 @Test fun `안전 신호 또는 누락 신호는 자원 준비 조건으로 등록하지 않는다`() {
  assertFailsWith<RequestRejected>{service.createStation(engineer,json.readTree("""{"stationId":"unsafe","name":"거절","readySignal":"safety","completeSignal":"missing","timeoutSeconds":10}"""))};assertEquals(1,service.stations().size)
 }
 private fun receipt(id:UUID)=jdbc.sql("SELECT state,detail FROM ops.work_request WHERE receipt_id=:id").param("id",id).query{r,_->r.getString(1) to r.getString(2)}.single()
 @Test fun `현장 확인 해제는 묶인 접수를 미전송 거절 또는 결과 불명으로 닫는다`() {
  port.receiptState="IN_DOUBT"
  val expected=mapOf("WAITING_RESOURCE" to "REJECTED","UNASSIGNED" to "REJECTED","DISPATCHING" to "IN_DOUBT","ACCEPTED" to "ACCEPTED","IN_DOUBT" to "IN_DOUBT","REJECTED" to "REJECTED")
  for((before,after) in expected) {
   val run=enqueue();service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state);assertEquals(run.runId,service.get(run.runId).receiptId)
   jdbc.sql("UPDATE ops.work_request SET state=:state,detail='before' WHERE receipt_id=:id").param("state",before).param("id",run.runId).update()
   decide(run.runId,"CONFIRM_CLEAR");assertNull(service.stations().single().owner)
   val (state,detail)=receipt(run.runId);assertEquals(after,state,before)
   when {
    before==after -> assertEquals("before",detail,before)
    after=="REJECTED" -> assertEquals("현장 확인으로 자원을 해제했습니다. 이 작업 요청은 실행하지 않습니다: 현장 상태 확인",detail)
    else -> assertEquals("현장 확인으로 자원을 해제했습니다. 제출 결과는 실행 관측으로 확인합니다: 현장 상태 확인",detail)
   }
  }
  assertEquals(expected.size,port.sends)
 }
 // Hold resolution through the station rework guard. The host's execution list maps exec-1 to the run's receipt.
 private val hostBench=JobOrderBench();private val incidents=IncidentBench();private val opsLog=OperationLog(jdbc)
 private val requests=WorkRequests(jdbc,MissionInterfaces(jdbc,tx,opsLog),JobOrderOperations(hostBench.eligibility,hostBench.host,hostBench.host,opsLog,hostBench.clock),hostBench.host,tx)
 private class StopProcess:Error("프로세스 중단 모사")
 private fun listed(job:String,instance:String="i-1"){hostBench.executions=HostCall.Ok(json.readTree("""{"instanceId":"$instance","executions":[{"executionId":"exec-1","jobOrderId":"$job"}]}"""))}
 private fun resolutions(c:StationCoordinator)=HoldResolutions(incidents.host,opsLog,requeryDelay=Duration.ZERO,clock=clock,rework=StationReworkGuard(requests,c))
 private fun rework(c:StationCoordinator)=resolutions(c).resolve(operator,"exec-1","rack-arrival","REWORK","i-1","재작업")
 /** A resource-managed run whose robot holds: REVIEW_REQUIRED and still owning the station. */
 private fun held(c:StationCoordinator):StationRun {
  port.physical=null;val run=enqueue();c.tick();assertEquals("RUNNING",c.get(run.runId).state)
  jdbc.sql("UPDATE ops.work_request SET required_station_id='inspection-1' WHERE receipt_id=:id").param("id",run.runId).update()
  port.physical="OPERATOR_HOLD";c.tick();assertEquals("REVIEW_REQUIRED",c.get(run.runId).state);assertEquals(run.runId,c.stations().single().owner)
  listed(run.runId.toString());return c.get(run.runId)
 }
 @Test fun `해제 뒤 다른 작업이 점유한 스테이션에서는 이전 작업의 재작업을 호스트에 넘기지 않는다`() {
  val c=coordinator();val r=held(c);decide(r.runId,"CONFIRM_CLEAR");port.physical=null
  val r2=enqueue();c.tick();assertEquals("RUNNING",c.get(r2.runId).state);assertEquals(r2.runId,c.stations().single().owner)
  val ex=assertFailsWith<RequestRejected>{rework(c)};assertEquals(409,ex.status);assertEquals(StationCoordinator.REWORK_HELD,ex.message)
  assertEquals(emptyList(),incidents.writes());assertEquals("MANUALLY_CLEARED",c.get(r.runId).state)
  assertEquals("RUNNING",c.get(r2.runId).state);assertEquals(r2.runId,c.stations().single().owner)
  // Confirming a hold moves nothing, so it is not gated.
  assertEquals("Resolved",resolutions(c).resolve(operator,"exec-1","rack-arrival","CONFIRM_DONE","i-1","현장 확인").outcome);assertEquals("CONFIRM_DONE",incidents.writes().single().decision)
 }
 @Test fun `점유 중이어도 확인 필요 상태가 아니면 재작업을 넘기지 않는다`() {
  val c=coordinator();port.physical=null;val run=enqueue();c.tick();assertEquals("RUNNING",c.get(run.runId).state)
  jdbc.sql("UPDATE ops.work_request SET required_station_id='inspection-1' WHERE receipt_id=:id").param("id",run.runId).update();listed(run.runId.toString())
  val ex=assertFailsWith<RequestRejected>{rework(c)};assertEquals(409,ex.status);assertEquals(StationCoordinator.REWORK_HELD,ex.message)
  assertEquals(emptyList(),incidents.writes());assertEquals("RUNNING",c.get(run.runId).state);assertEquals(run.runId,c.stations().single().owner)
 }
 @Test fun `확인 필요 상태여도 스테이션 점유가 없으면 재작업을 넘기지 않는다`() {
  val c=coordinator();val r=held(c)
  // Decisions never reach this (review keeps the owner); it isolates the owner check.
  jdbc.sql("UPDATE ops.rf_station SET owner=NULL WHERE station_id='inspection-1'").update()
  assertEquals(409,assertFailsWith<RequestRejected>{rework(c)}.status);assertEquals(emptyList(),incidents.writes());assertEquals("REVIEW_REQUIRED",c.get(r.runId).state)
 }
 @Test fun `점유를 유지한 확인 필요 작업의 재작업은 한 번 넘기고 재작업 뒤의 완료 근거만 인정한다`() {
  val c=coordinator();val r=held(c);val before=r.dispatchedAt!!
  now=now.plusSeconds(3);signal()
  var during:String?=null;var clear:Int?=null
  incidents.onResolve={during=service.get(r.runId).state;clear=runCatching{decide(r.runId,"CONFIRM_CLEAR")}.exceptionOrNull()?.let{(it as RequestRejected).status}}
  assertEquals("Resolved",rework(c).outcome);assertEquals(1,incidents.writes().size)
  assertEquals("REWORKING",during);assertEquals(409,clear)
  val after=c.get(r.runId);assertEquals("RUNNING",after.state);assertEquals(now,after.dispatchedAt);assertTrue(after.dispatchedAt!!.isAfter(before));assertEquals(r.runId,c.stations().single().owner)
  assertEquals(listOf("REVIEW_REQUIRED","REWORKING","RUNNING"),c.events(r.runId).takeLast(3).map{it["state"]})
  assertEquals(listOf("operator/kim","operator/kim"),c.events(r.runId).takeLast(2).map{it["actor"]})
  // A completion signal from before the rework is not this rework's completion.
  port.physical="PHYSICALLY_DONE";signal(complete=true,at=now.minusSeconds(1));c.tick();assertEquals("RUNNING",c.get(r.runId).state)
  now=now.plusSeconds(1);signal(complete=true);c.tick();assertEquals("COMPLETED",c.get(r.runId).state)
 }
 @Test fun `호스트가 재작업을 거절하거나 결과를 모르면 확인 필요로 되돌리고 점유를 유지한다`() {
  val c=coordinator();val r=held(c)
  incidents.resolveAnswer=HostWrite.Answered(200,IncidentBench.resolved(null,"NotHeld"))
  assertEquals(OperationResult.REJECTED,rework(c).result);assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertEquals("재작업 거부; 점유를 유지하고 현장 확인을 기다립니다",c.get(r.runId).reason)
  incidents.resolveAnswer=HostWrite.NoResponse("timeout");incidents.incidents=HostCall.Silent("down")
  assertNull(rework(c).confirmation);assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertEquals("재작업 반영 불명; 실행 관측과 현장 확인 전 점유 유지",c.get(r.runId).reason)
  incidents.onResolve={error("connection reset")};assertFailsWith<IllegalStateException>{rework(c)};assertEquals("REVIEW_REQUIRED",c.get(r.runId).state)
  assertEquals(3,incidents.writes().size);assertEquals(r.runId,c.stations().single().owner);assertEquals(r.dispatchedAt,c.get(r.runId).dispatchedAt)
 }
 @Test fun `재작업이 반영돼도 설비 완료 신호가 켜져 있거나 신호를 모르면 확인 필요로 남긴다`() {
  val c=coordinator();val r=held(c)
  signal(complete=true);rework(c);assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertTrue(c.get(r.runId).reason.startsWith("재작업 반영; 설비 완료 신호가 켜져"))
  port.signals=null;rework(c);assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertTrue(c.get(r.runId).reason.startsWith("재작업 반영; 현장 신호 관측 불명"))
  assertEquals(r.dispatchedAt,c.get(r.runId).dispatchedAt);assertEquals(2,incidents.writes().size)
 }
 @Test fun `자원 관리가 아닌 실행과 접수 없는 실행과 다른 인스턴스의 재작업은 그대로 넘긴다`() {
  val c=coordinator()
  jdbc.sql("INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state) VALUES(:id,'mes','plain','hash','{}','JO-plain','ACCEPTED')").param("id",UUID.randomUUID()).update()
  listed("JO-plain");assertEquals("Resolved",rework(c).outcome)
  listed("JO-direct");assertEquals("Resolved",rework(c).outcome)
  // The host refuses a request naming another instance itself; instance ids never repeat.
  val r=held(c);listed(r.runId.toString(),instance="i-2");incidents.resolveAnswer=HostWrite.Answered(409,"""{"error":"INSTANCE_MISMATCH","detail":"다른 인스턴스"}""")
  assertEquals(OperationResult.REJECTED,rework(c).result);assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertEquals(r.revision,c.get(r.runId).revision)
  assertEquals(3,incidents.writes().size)
 }
 @Test fun `실행 목록을 못 읽거나 실행이 없으면 재작업을 넘기지 않는다`() {
  val c=coordinator();val r=held(c)
  hostBench.executions=HostCall.Silent("offline");assertEquals(503,assertFailsWith<RequestRejected>{rework(c)}.status)
  hostBench.executions=HostCall.Ok(json.readTree("""{"instanceId":"i-1","executions":[]}"""));assertEquals(409,assertFailsWith<RequestRejected>{rework(c)}.status)
  assertEquals(emptyList(),incidents.writes());assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertEquals(r.revision,c.get(r.runId).revision)
 }
 @Test fun `재작업 전달 중 프로세스가 멈추면 재기동 뒤 확인 필요로 넘긴다`() {
  val c=coordinator();val r=held(c)
  incidents.onResolve={throw StopProcess()}
  assertFailsWith<StopProcess>{rework(c)};assertEquals("REWORKING",c.get(r.runId).state)
  assertEquals(409,assertFailsWith<RequestRejected>{decide(r.runId,"CONFIRM_CLEAR")}.status)
  coordinator().tick();assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertEquals(r.runId,c.stations().single().owner);assertEquals(r.dispatchedAt,c.get(r.runId).dispatchedAt)
  assertEquals(1,incidents.writes().size)
 }
 @Test fun `관측 재개는 재개 뒤의 완료 근거만 인정하고 설비 완료 신호가 켜져 있거나 모르면 거절한다`() {
  val run=enqueue();service.tick();port.physical="OPERATOR_HOLD";service.tick();val before=service.get(run.runId).dispatchedAt!!
  now=now.plusSeconds(3)
  signal(complete=true);assertEquals(409,assertFailsWith<RequestRejected>{decide(run.runId,"RESUME_OBSERVATION")}.status)
  port.signals=null;assertEquals(503,assertFailsWith<RequestRejected>{decide(run.runId,"RESUME_OBSERVATION")}.status)
  assertEquals("REVIEW_REQUIRED",service.get(run.runId).state)
  signal();decide(run.runId,"RESUME_OBSERVATION");assertEquals("RUNNING",service.get(run.runId).state);assertEquals(now,service.get(run.runId).dispatchedAt);assertTrue(now.isAfter(before))
  port.physical="PHYSICALLY_DONE";signal(complete=true,at=now.minusSeconds(1));service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(run.runId).state)
 }

 @Test fun `재작업 뒤 이전 보류 결과로는 확인 필요로 되돌리지 않고 새 결과만 판단에 쓴다`() {
  val c=coordinator();val r=held(c);now=now.plusSeconds(3);signal();rework(c);assertEquals("RUNNING",c.get(r.runId).state)
  c.tick();c.tick();assertEquals("RUNNING",c.get(r.runId).state)
  result(r.runId,"OPERATOR_HOLD");c.tick();assertEquals("REVIEW_REQUIRED",c.get(r.runId).state)
  now=now.plusSeconds(1);rework(c);assertEquals("RUNNING",c.get(r.runId).state);c.tick();assertEquals("RUNNING",c.get(r.runId).state)
  result(r.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);signal(complete=true);c.tick();assertEquals("COMPLETED",c.get(r.runId).state)
 }
 @Test fun `관측 재개 뒤 이전 보류 결과로는 확인 필요로 되돌리지 않고 새 결과만 판단에 쓴다`() {
  val run=enqueue();service.tick();port.physical="OPERATOR_HOLD";service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state)
  now=now.plusSeconds(3);decide(run.runId,"RESUME_OBSERVATION");service.tick();service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  result(run.runId,"OPERATOR_HOLD");service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state)
  now=now.plusSeconds(1);decide(run.runId,"RESUME_OBSERVATION");service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  result(run.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(run.runId).state)
 }
 @Test fun `처음 실행은 전송 전에 저장된 결과를 판단에 쓰지 않는다`() {
  port.preResult="OPERATOR_HOLD";val held=enqueue();service.tick();service.tick();assertEquals("RUNNING",service.get(held.runId).state)
  result(held.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(held.runId).state)
  port.preResult="PHYSICALLY_DONE";signal();val done=enqueue();service.tick();assertEquals("RUNNING",service.get(done.runId).state)
  now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("RUNNING",service.get(done.runId).state)
  result(done.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(done.runId).state)
 }

 @Test fun `관측 재개는 같은 실행을 다시 보므로 확인 대기 중 저장된 물리 완료를 새 완료 신호와 함께 인정한다`() {
  val run=enqueue();service.tick();port.physical="OPERATOR_HOLD";service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state)
  result(run.runId,"PHYSICALLY_DONE");now=now.plusSeconds(3);decide(run.runId,"RESUME_OBSERVATION");service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  signal(complete=true,at=now.minusSeconds(1));service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(run.runId).state)
 }
 @Test fun `재작업은 재작업 전에 저장된 물리 완료를 완료 근거로 쓰지 않는다`() {
  val c=coordinator();val r=held(c);result(r.runId,"PHYSICALLY_DONE");now=now.plusSeconds(3);signal();rework(c);assertEquals("RUNNING",c.get(r.runId).state)
  now=now.plusSeconds(1);signal(complete=true);c.tick();assertEquals("RUNNING",c.get(r.runId).state)
  result(r.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);signal(complete=true);c.tick();assertEquals("COMPLETED",c.get(r.runId).state)
 }

}
