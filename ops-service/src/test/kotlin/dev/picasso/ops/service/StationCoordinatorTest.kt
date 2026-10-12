package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.requests.RequestRejected
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
  val receipts=mutableMapOf<UUID,StationReceipt>()
  override fun validate(payload:JsonNode){require(valid)}
  override fun signals()=signals
  @Synchronized override fun dispatch(id:UUID,payload:JsonNode,actor:Actor):StationReceipt {
   receipts[id]?.let{return it}
   sends++
   jdbc.sql("INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state) VALUES(:id,'ops:interface',:key,'hash','{}',:key,:state)").param("id",id).param("key",id.toString()).param("state",receiptState).update()
   val r=StationReceipt(id,receiptState,receiptState=="UNASSIGNED","v1",physical,null);receipts[id]=r
   if(unknown)error("response lost")
   return r
  }
  override fun receipt(id:UUID)=receipts.getValue(id).copy(physicalState=physical)
  override fun retry(receipt:StationReceipt,attempt:UUID,actor:Actor):StationReceipt {retries++;return receipt.copy(state="ACCEPTED",safeToRetry=false).also{receipts[receipt.id]=it}}
 }
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
    after=="REJECTED" -> assertEquals("현장 확인으로 자원을 해제했습니다. 이 접수는 실행하지 않습니다: 현장 상태 확인",detail)
    else -> assertEquals("현장 확인으로 자원을 해제했습니다. 제출 결과는 실행 관측으로 확인합니다: 현장 상태 확인",detail)
   }
  }
  assertEquals(expected.size,port.sends)
 }

}
