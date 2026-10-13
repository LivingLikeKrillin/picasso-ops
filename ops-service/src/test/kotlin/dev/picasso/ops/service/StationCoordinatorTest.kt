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
  var texts:Map<String,StationText> = mapOf("job" to StationText("",now),"echo" to StationText("",now),"lot" to StationText("LOT-1",now))
  var writeFails=false;val writes=mutableListOf<Pair<String,String>>()
  /** Runs once after a read has been taken, before its caller judges it. */
  var afterRead:(()->Unit)?=null
  override fun validate(payload:JsonNode){require(valid)}
  override fun signals()=signals
  override fun read():StationSignals? {val taken=signals?.let{StationSignals(it,texts)};afterRead?.let{afterRead=null;it()};return taken}
  override fun write(name:String,value:String,actor:Actor):Boolean {writes+=name to value;if(writeFails)return false;texts=texts+(name to StationText(value,now));return true}
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
 /** The previous run was released on site confirmation and quarantined the station: confirm it idle on this run and dispatch. */
 private fun idle(run:StationRun){if(service.stations().single().quarantinedBy!=null){assertEquals("WAITING_READY",service.get(run.runId).state);decide(run.runId,"CONFIRM_STATION_IDLE");service.tick()}}
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
  signal();service.tick();assertEquals("RUNNING",service.get(run.runId).state);now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("RUNNING",service.get(run.runId).state)
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
   port.physical=null;val run=enqueue();service.tick();idle(run);assertEquals("RUNNING",service.get(run.runId).state,state)
   port.physical=state;service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state,state);assertEquals(run.runId,service.stations().single().owner,state)
   decide(run.runId,"CONFIRM_CLEAR")
  }
  port.physical=null;val run=enqueue();service.tick();idle(run);port.physical="CANCELING";service.tick();service.tick()
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
   val run=enqueue();service.tick();idle(run);assertEquals("REVIEW_REQUIRED",service.get(run.runId).state);assertEquals(run.runId,service.get(run.runId).receiptId)
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
  val r2=enqueue();c.tick();idle(r2);assertEquals("RUNNING",c.get(r2.runId).state);assertEquals(r2.runId,c.stations().single().owner)
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
  // The rework entered RUNNING with the completion signal off, so a later true is new whatever the field clock stamps on it.
  port.physical="PHYSICALLY_DONE";c.tick();assertEquals("RUNNING",c.get(r.runId).state)
  now=now.plusSeconds(1);signal(complete=true,at=now.minusSeconds(60));c.tick();assertEquals("COMPLETED",c.get(r.runId).state)
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
  port.physical="PHYSICALLY_DONE";service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  now=now.plusSeconds(1);signal(complete=true,at=now.minusSeconds(60));service.tick();assertEquals("COMPLETED",service.get(run.runId).state)
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
  now=now.plusSeconds(1);signal(complete=true,at=now.minusSeconds(60));service.tick();assertEquals("COMPLETED",service.get(run.runId).state)
 }
 @Test fun `재작업은 재작업 전에 저장된 물리 완료를 완료 근거로 쓰지 않는다`() {
  val c=coordinator();val r=held(c);result(r.runId,"PHYSICALLY_DONE");now=now.plusSeconds(3);signal();rework(c);assertEquals("RUNNING",c.get(r.runId).state)
  now=now.plusSeconds(1);signal(complete=true);c.tick();assertEquals("RUNNING",c.get(r.runId).state)
  result(r.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);signal(complete=true);c.tick();assertEquals("COMPLETED",c.get(r.runId).state)
 }

 // 이전 작업의 늦은 설비 완료 신호(외부 검토 실험 A1). 현장 확인 해제는 실행을 멈추지 않아 X 쪽 설비가 Y 점유 중에 완료를 쓸 수 있다.
 // 작업 번호 송신·반환 신호가 있는 스테이션은 반환 번호로 가르고, 없는 스테이션은 보낸 작업을 해제하면 격리한다.
 private val completedReason="로봇 물리 완료와 이번 작업의 설비 완료 신호 확인; 자원 해제"
 private val clearedReason="현장 확인에 의한 자원 해제: 현장 상태 확인"
 private fun station()=service.stations().single()
 private fun echoing(){jdbc.sql("DELETE FROM ops.rf_station").update();service.createStation(engineer,json.readTree("""{"stationId":"inspection-1","name":"번호 검사","readySignal":"ready","completeSignal":"complete","timeoutSeconds":10,"jobSignal":"job","jobEchoSignal":"echo"}"""))}
 private fun echo(value:String){port.texts=port.texts+("echo" to StationText(value,now))}
 private fun context(id:UUID,state:String,reason:String?=null)=service.events(id).last{it["state"]==state && (reason==null || it["reason"]==reason)}["context"] as JsonNode
 private fun decideIdle(id:UUID,revision:Int=service.get(id).revision)=decide(id,"CONFIRM_STATION_IDLE",revision)
 /** X 를 보낸 뒤 실행 시간 초과 → 확인 필요 → 현장 확인 해제까지. */
 private fun clearedAfterTimeout():StationRun {
  val x=enqueue();service.tick();assertEquals("RUNNING",service.get(x.runId).state)
  now=now.plusSeconds(11);service.tick();assertEquals("REVIEW_REQUIRED",service.get(x.runId).state)
  decide(x.runId,"CONFIRM_CLEAR");assertEquals("MANUALLY_CLEARED",service.get(x.runId).state);assertNull(station().owner)
  return service.get(x.runId)
 }
 @Test fun `작업 번호 송신·반환 신호는 둘 다 연결하거나 둘 다 비우고 현장에 선언된 TEXT 신호여야 한다`() {
  jdbc.sql("DELETE FROM ops.rf_station").update()
  fun create(extra:String)=service.createStation(engineer,json.readTree("""{"stationId":"inspection-1","name":"번호 검사","readySignal":"ready","completeSignal":"complete","timeoutSeconds":10$extra}"""))
  fun refused(extra:String)=assertFailsWith<RequestRejected>{create(extra)}
  val plain=create(""","jobSignal":null,"jobEchoSignal":null""").single();assertNull(plain.jobSignal);assertNull(plain.jobEchoSignal);jdbc.sql("DELETE FROM ops.rf_station").update()
  for(extra in listOf(""","jobSignal":"job"""",""","jobEchoSignal":"echo"""",""","jobSignal":"job","jobEchoSignal":null""")) {val e=refused(extra);assertEquals(400,e.status,extra);assertEquals(StationCoordinator.JOB_PAIR,e.message,extra)}
  for(extra in listOf(""","jobSignal":"ready","jobEchoSignal":"echo"""",""","jobSignal":"job","jobEchoSignal":"missing"""")) {val e=refused(extra);assertEquals(400,e.status,extra);assertEquals(StationCoordinator.TEXT_REQUIRED,e.message,extra)}
  assertEquals(400,refused(""","jobSignal":"job","jobEchoSignal":"job"""").status);assertEquals(400,refused(""","jobSignal":"job","jobEchoSignal":"echo","extra":1""").status)
  assertEquals(emptyList(),service.stations())
  val st=create(""","jobSignal":"job","jobEchoSignal":"echo"""").single();assertEquals("job" to "echo",st.jobSignal to st.jobEchoSignal);assertNull(st.quarantinedBy)
 }
 @Test fun `작업 번호 반환 스테이션은 해제한 이전 작업의 늦은 완료 신호를 다음 작업의 완료로 채택하지 않고 확인 필요로 넘긴다`() {
  echoing();val x=clearedAfterTimeout();assertNull(station().quarantinedBy);assertEquals(false,context(x.runId,"MANUALLY_CLEARED")["quarantined"].asBoolean())
  now=now.plusSeconds(1);signal();val y=enqueue();service.tick()
  assertEquals("RUNNING",service.get(y.runId).state);assertEquals(2,port.sends);assertEquals(listOf("job" to x.runId.toString(),"job" to y.runId.toString()),port.writes)
  val dispatching=context(y.runId,"DISPATCHING");assertEquals(y.runId.toString(),dispatching["jobToken"].asText());assertEquals("job",dispatching["jobSignal"].asText())
  // X 쪽 설비가 늦게 X 의 번호와 완료를 쓴다. Y 의 로봇은 아직이다.
  now=now.plusSeconds(2);echo(x.runId.toString());signal(complete=true);service.tick()
  val review=service.get(y.runId);assertEquals("REVIEW_REQUIRED",review.state);assertEquals(StationCoordinator.ECHO_MISMATCH,review.reason);assertEquals(y.runId,station().owner)
  val seen=context(y.runId,"REVIEW_REQUIRED");assertEquals(y.runId.toString(),seen["expected"].asText());assertEquals(x.runId.toString(),seen["echoed"].asText())
  assertEquals(true,seen["signals"]["complete"]["value"].asBoolean());assertEquals(x.runId.toString(),seen["signals"]["echo"]["value"].asText())
  now=now.plusSeconds(1);result(y.runId,"PHYSICALLY_DONE");service.tick();assertEquals("REVIEW_REQUIRED",service.get(y.runId).state);assertEquals(y.runId,station().owner)
 }
 @Test fun `작업 번호 반환 스테이션은 반환 번호가 이전 값이면 로봇 완료 뒤에 온 완료 신호도 채택하지 않는다`() {
  echoing()
  val x=enqueue();service.tick();result(x.runId,"OPERATOR_HOLD");service.tick();assertEquals("REVIEW_REQUIRED",service.get(x.runId).state)
  decide(x.runId,"CONFIRM_CLEAR");assertNull(station().quarantinedBy)
  now=now.plusSeconds(1);val y=enqueue();service.tick();assertEquals("RUNNING",service.get(y.runId).state)
  now=now.plusSeconds(2);result(y.runId,"PHYSICALLY_DONE");service.tick();assertEquals("RUNNING",service.get(y.runId).state)
  now=now.plusSeconds(2);signal(complete=true);service.tick()
  assertEquals("REVIEW_REQUIRED",service.get(y.runId).state);assertEquals("",context(y.runId,"REVIEW_REQUIRED")["echoed"].asText())
 }
 @Test fun `작업 번호 반환 스테이션은 이번 작업 번호와 완료 신호와 로봇 완료가 모두 있어야 완료한다`() {
  echoing();val run=enqueue();service.tick();assertEquals("RUNNING",service.get(run.runId).state);assertEquals(run.runId.toString(),port.texts.getValue("job").value)
  // 번호만 돌아오고 완료 신호가 없거나, 번호와 완료 신호가 맞아도 로봇 완료 전이면 기다린다.
  now=now.plusSeconds(1);echo(run.runId.toString());service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  result(run.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);service.tick()
  val done=service.get(run.runId);assertEquals("COMPLETED",done.state);assertEquals(completedReason,done.reason);assertNull(station().owner)
  assertEquals(run.runId.toString(),context(run.runId,"COMPLETED")["signals"]["echo"]["value"].asText())
 }
 @Test fun `작업 번호 불일치로 확인 필요가 된 작업은 신호 초기화 뒤 관측을 재개하고 이번 번호로 완료한다`() {
  echoing();val x=clearedAfterTimeout();now=now.plusSeconds(1);signal();val y=enqueue();service.tick()
  now=now.plusSeconds(1);echo(x.runId.toString());signal(complete=true);service.tick();assertEquals("REVIEW_REQUIRED",service.get(y.runId).state)
  now=now.plusSeconds(1);signal();decide(y.runId,"RESUME_OBSERVATION");assertEquals("RUNNING",service.get(y.runId).state)
  result(y.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);echo(y.runId.toString());signal(complete=true);service.tick()
  assertEquals("COMPLETED",service.get(y.runId).state);assertEquals(2,port.sends)
 }
 @Test fun `정상 완료한 이전 작업의 완료 신호를 설비가 다시 써도 반환 번호가 다르면 다음 작업의 완료로 채택하지 않는다`() {
  echoing();val x=enqueue();service.tick();result(x.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);echo(x.runId.toString());signal(complete=true);service.tick()
  assertEquals("COMPLETED",service.get(x.runId).state);assertNull(station().quarantinedBy)
  now=now.plusSeconds(1);val y=enqueue();service.tick();service.tick();assertEquals("WAITING_READY",service.get(y.runId).state);assertEquals(1,port.sends)
  now=now.plusSeconds(1);signal();service.tick();assertEquals("RUNNING",service.get(y.runId).state)
  now=now.plusSeconds(1);signal(complete=true);result(y.runId,"PHYSICALLY_DONE");service.tick()
  assertEquals("REVIEW_REQUIRED",service.get(y.runId).state);assertEquals(x.runId.toString(),context(y.runId,"REVIEW_REQUIRED")["echoed"].asText())
 }
 @Test fun `작업 번호 쓰기에 실패하면 로봇을 보내지 않고 설비 준비 대기에 남아 다음 처리에서 다시 쓴다`() {
  echoing();port.writeFails=true;val run=enqueue();service.tick();service.tick()
  val waiting=service.get(run.runId);assertEquals("WAITING_READY",waiting.state);assertEquals(StationCoordinator.WRITE_FAILED,waiting.reason);assertEquals(0,port.sends);assertEquals(2,port.writes.size)
  assertEquals(1,service.events(run.runId).count{it["reason"]==StationCoordinator.WRITE_FAILED});assertEquals(run.runId.toString(),context(run.runId,"WAITING_READY",StationCoordinator.WRITE_FAILED)["jobToken"].asText())
  now=now.plusSeconds(3);port.writeFails=false;service.tick()
  assertEquals("RUNNING",service.get(run.runId).state);assertEquals(1,port.sends);assertEquals(run.runId.toString(),port.texts.getValue("job").value)
 }
 @Test fun `작업 번호 쓰기 실패가 이어져도 설비 준비 기한은 그대로라 전송 전에 시간 초과로 해제한다`() {
  echoing();port.writeFails=true;val run=enqueue();service.tick();now=now.plusSeconds(6);service.tick();now=now.plusSeconds(5);service.tick()
  assertEquals("TIMED_OUT",service.get(run.runId).state);assertEquals(0,port.sends);assertNull(station().owner);assertNull(station().quarantinedBy)
 }
 @Test fun `작업 번호 반환이 없는 스테이션은 보낸 작업을 해제하면 격리하고 현장 확인 후 격리 해제 전에는 다음 작업을 보내지 않는다`() {
  val x=clearedAfterTimeout();assertEquals(x.runId,station().quarantinedBy);assertNotNull(station().quarantinedAt)
  val events=service.events(x.runId).takeLast(2);assertEquals(listOf(clearedReason,StationCoordinator.QUARANTINE),events.map{it["reason"]});assertEquals(listOf("operator/kim","operator/kim"),events.map{it["actor"]})
  val cleared=events[0]["context"] as JsonNode
  assertEquals(true,cleared["quarantined"].asBoolean());assertEquals(false,cleared["signals"]["complete"]["value"].asBoolean());assertTrue(cleared.has("physicalState"));assertTrue(cleared["physicalState"].isNull)
  assertEquals("inspection-1",(events[1]["context"] as JsonNode)["stationId"].asText())
  now=now.plusSeconds(1);val y=enqueue();service.tick();service.tick()
  val waiting=service.get(y.runId);assertEquals("WAITING_READY",waiting.state);assertEquals(StationCoordinator.QUARANTINED,waiting.reason);assertEquals(y.runId,station().owner);assertEquals(1,port.sends)
  assertEquals(1,service.events(y.runId).count{it["reason"]==StationCoordinator.QUARANTINED})
  // X 쪽 설비가 늦게 완료를 쓴다. Y 는 보내지 않았으므로 채택될 자리가 없다.
  now=now.plusSeconds(2);signal(complete=true);service.tick();assertEquals("WAITING_READY",service.get(y.runId).state);assertEquals(1,port.sends)
  assertEquals(409,assertFailsWith<RequestRejected>{decideIdle(y.runId)}.status)
  port.signals=null;assertEquals(503,assertFailsWith<RequestRejected>{decideIdle(y.runId)}.status);assertEquals(x.runId,station().quarantinedBy)
  now=now.plusSeconds(1);signal();decideIdle(y.runId)
  val idle=service.events(y.runId).last();assertEquals("현장 확인 후 격리 해제: 현장 상태 확인",idle["reason"]);assertEquals("operator/kim",idle["actor"])
  assertEquals(x.runId.toString(),(idle["context"] as JsonNode)["releasedRunId"].asText());assertEquals(false,(idle["context"] as JsonNode)["signals"]["complete"]["value"].asBoolean())
  assertNull(station().quarantinedBy);assertNull(station().quarantinedAt)
  service.tick();assertEquals("RUNNING",service.get(y.runId).state);assertEquals(2,port.sends)
  now=now.plusSeconds(1);result(y.runId,"PHYSICALLY_DONE");signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(y.runId).state);assertEquals(completedReason,service.get(y.runId).reason)
 }
 @Test fun `격리 중 설비 준비 기한은 멈추고 격리 해제 판단부터 다시 센다`() {
  val x=enqueue();service.tick();result(x.runId,"OPERATOR_HOLD");service.tick();decide(x.runId,"CONFIRM_CLEAR")
  assertEquals("OPERATOR_HOLD",context(x.runId,"MANUALLY_CLEARED",clearedReason)["physicalState"].asText());assertEquals(x.runId,station().quarantinedBy)
  now=now.plusSeconds(1);val y=enqueue();service.tick();now=now.plusSeconds(60);service.tick()
  assertEquals("WAITING_READY",service.get(y.runId).state);assertEquals(y.runId,station().owner);assertEquals(1,port.sends)
  signal(ready=false);decideIdle(y.runId);now=now.plusSeconds(9);service.tick();assertEquals("WAITING_READY",service.get(y.runId).state)
  now=now.plusSeconds(1);service.tick();assertEquals("TIMED_OUT",service.get(y.runId).state);assertEquals(1,port.sends);assertNull(station().owner);assertNull(station().quarantinedBy)
 }
 @Test fun `응답 불명으로 해제한 작업도 호스트에 닿았을 수 있어 스테이션을 격리한다`() {
  port.unknown=true;val x=enqueue();service.tick();assertEquals("REVIEW_REQUIRED",service.get(x.runId).state);assertNotNull(service.get(x.runId).dispatchedAt)
  decide(x.runId,"CONFIRM_CLEAR");port.unknown=false;assertEquals(x.runId,station().quarantinedBy)
  now=now.plusSeconds(1);val y=enqueue();service.tick();assertEquals("WAITING_READY",service.get(y.runId).state);assertEquals(StationCoordinator.QUARANTINED,service.get(y.runId).reason);assertEquals(1,port.sends)
 }
 @Test fun `보내지 않은 작업의 종료와 계약 거부는 스테이션을 격리하지 않는다`() {
  val cancelled=enqueue();decide(cancelled.runId,"CANCEL_QUEUED");assertEquals("CANCELLED",service.get(cancelled.runId).state)
  signal(ready=false);val timed=enqueue();service.tick();now=now.plusSeconds(11);service.tick();assertEquals("TIMED_OUT",service.get(timed.runId).state)
  signal();port.receiptState="REJECTED";val rejected=enqueue();service.tick();assertEquals("REJECTED",service.get(rejected.runId).state)
  assertNull(station().quarantinedBy);assertNull(station().owner)
  port.receiptState="ACCEPTED";val next=enqueue();service.tick();assertEquals("RUNNING",service.get(next.runId).state)
 }
 @Test fun `격리 해제 판단은 격리된 스테이션을 점유한 설비 준비 대기 작업에서 운영자가 조회 버전으로 한다`() {
  signal(ready=false);val run=enqueue();assertEquals(409,assertFailsWith<RequestRejected>{decideIdle(run.runId)}.status)
  service.tick();assertEquals("WAITING_READY",service.get(run.runId).state);assertEquals(409,assertFailsWith<RequestRejected>{decideIdle(run.runId)}.status)
  signal();service.tick();now=now.plusSeconds(11);service.tick();decide(run.runId,"CONFIRM_CLEAR");assertEquals(run.runId,station().quarantinedBy)
  val y=enqueue();service.tick();val revision=service.get(y.runId).revision
  assertEquals(409,assertFailsWith<RequestRejected>{decideIdle(y.runId,revision-1)}.status)
  assertEquals(403,assertFailsWith<RequestRejected>{service.decide(y.runId,engineer,json.readTree("""{"expectedRevision":$revision,"decision":"CONFIRM_STATION_IDLE","reason":"확인"}"""))}.status)
  assertEquals(run.runId,station().quarantinedBy);assertEquals("WAITING_READY",service.get(y.runId).state)
 }
 @Test fun `정상 완료는 격리하지 않고 이전 신호가 초기화되기 전에는 다음 작업을 보내지 않는다`() {
  val x=enqueue();service.tick();result(x.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(x.runId).state);assertNull(station().quarantinedBy)
  now=now.plusSeconds(1);val y=enqueue();service.tick();service.tick();assertEquals("WAITING_READY",service.get(y.runId).state);assertEquals(1,port.sends)
  now=now.plusSeconds(1);signal();service.tick();assertEquals("RUNNING",service.get(y.runId).state);assertEquals(2,port.sends)
  // 한계: 반환 번호가 없으면 정상 완료한 X 의 설비가 완료를 다시 쓰는 것과 Y 의 완료를 가르지 못한다(반환 스테이션은 위 시험이 가른다).
  now=now.plusSeconds(1);signal(complete=true);now=now.plusSeconds(1);result(y.runId,"PHYSICALLY_DONE");service.tick()
  assertEquals("COMPLETED",service.get(y.runId).state)
 }
 @Test fun `현장 시계가 운영 시계보다 늦어도 전송 뒤 새로 켜진 완료 신호로 완료한다`() {
  val run=enqueue();service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  // 현장 시계가 50초 늦어 신호의 observedAt 이 전송 시각보다 앞선다. 전송은 완료 false 에서만 했으므로 이 true 는 새 신호다.
  now=now.plusSeconds(2);result(run.runId,"PHYSICALLY_DONE");signal(complete=true,at=now.minusSeconds(50));service.tick()
  assertEquals("COMPLETED",service.get(run.runId).state)
  assertTrue(Instant.parse(context(run.runId,"COMPLETED")["signals"]["complete"]["observedAt"].asText()).isBefore(service.get(run.runId).dispatchedAt))
 }
 @Test fun `실행 진입 전에 읽은 신호는 그 실행의 완료 근거로 쓰지 않는다`() {
  val run=enqueue();service.tick();port.physical="OPERATOR_HOLD";service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state);result(run.runId,"PHYSICALLY_DONE")
  // 처리 주기가 완료 true 를 읽은 직후 운영자가 신호를 내리고 관측을 재개한다. 그 주기의 읽기는 재개보다 앞선다(운영 시계).
  now=now.plusSeconds(1);signal(complete=true)
  port.afterRead={now=now.plusSeconds(1);signal();decide(run.runId,"RESUME_OBSERVATION")}
  service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(run.runId).state)
 }
 @Test fun `전송 전 미배정 재평가도 설비 완료 신호가 꺼져 있어야 실행으로 들어간다`() {
  port.receiptState="UNASSIGNED";val run=enqueue();service.tick();assertEquals("WAITING_ROBOT",service.get(run.runId).state)
  now=now.plusSeconds(5);signal(complete=true);service.tick();service.tick();assertEquals("WAITING_ROBOT",service.get(run.runId).state);assertEquals(0,port.retries)
  signal();service.tick();assertEquals("RUNNING",service.get(run.runId).state);assertEquals(1,port.retries)
 }

}
