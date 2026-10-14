package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.interfaces.InterfaceMapping
import dev.picasso.ops.service.interfaces.MissionInterfaces
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.requests.Receipt
import dev.picasso.ops.service.requests.WorkRequests
import dev.picasso.ops.service.stations.OpsStationPort
import dev.picasso.ops.service.stations.StationCoordinator
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * 스테이션 tick 의 비용(HA 설계 기록 §10). 활성 스테이션마다 실행 목록 전체를 읽던 호스트 조회와 스테이션 목록 전체를 다시 읽던 DB 조회가
 * 없어졌는지, 그러면서도 tick 이 실행 관측을 작업 요청에 그대로 남기는지 본다. 운영 서비스와 같은 저장 결과 수집(durable) 모드다.
 * DB 문장은 연결이 준비한 SQL 을 센다([CountingDataSource]). 호스트 조회는 운영 코드가 읽는 겉감에서 센다([CountingHost]).
 */
class StationTickCostTest {
 private val plain=DriverManagerDataSource(PostgresSupport.jdbcUrl,PostgresSupport.username,PostgresSupport.password)
 private val counted=CountingDataSource(plain)
 private val jdbc=JdbcClient.create(counted)
 private val tx=TransactionTemplate(DataSourceTransactionManager(counted))
 private val json=ObjectMapper()
 private val bench=JobOrderBench()
 private val host=CountingHost(bench.host)
 private val interfaces=MissionInterfaces(JdbcClient.create(plain),TransactionTemplate(DataSourceTransactionManager(plain)),OperationLog(JdbcClient.create(plain)))
 private val operations=JobOrderOperations(bench.eligibility,bench.host,bench.host,OperationLog(JdbcClient.create(plain)),bench.clock,requeryDelay=Duration.ZERO)
 private val requests=WorkRequests(jdbc,interfaces,operations,host,tx,durableResults=true)
 private fun coordinator()=StationCoordinator(jdbc,tx,OpsStationPort(requests,interfaces,host))

 /** 연결이 준비한 SQL. 트랜잭션 관리와 쿼리가 같은 데이터 소스를 쓴다. */
 private class CountingDataSource(target:javax.sql.DataSource):DelegatingDataSource(target) {
  val sql=CopyOnWriteArrayList<String>()
  override fun getConnection():Connection = counted(super.getConnection())
  private fun counted(c:Connection):Connection = Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) {_,m,args->
   if(m.name=="prepareStatement" || m.name=="prepareCall") sql+=args[0] as String
   try {m.invoke(c,*(args ?: emptyArray()))} catch(e:InvocationTargetException) {throw e.targetException}
  } as Connection
  fun count(prefix:String)=sql.count{it.trimStart().startsWith(prefix)}
 }
 /** 운영 서비스의 호스트 읽기 겉감. `GET /host/executions` 를 센다. 본문은 [body] 가 그때마다 정한다. */
 private class CountingHost(private val delegate:HostReads):HostReads by delegate {
  val executions=AtomicInteger();var body:()->HostCall<JsonNode> = {delegate.executions()}
  override fun executions():HostCall<JsonNode> {executions.incrementAndGet();return body()}
 }

 @BeforeTest fun fresh() {
  OpsSchema.flyway(plain,cleanable=true).apply{clean();migrate()}
  val spec=InterfaceMapping.spec(json.readTree("""{"interfaceId":"asset-check","version":1,"name":"점검","workMasterId":"InspectAsset","mapping":{"targets":"/assets"},"sample":{"assets":[{"id":"T1","location":"bay-7"}]}}"""))
  interfaces.create(spec,Actor(Mode.ENGINEER,"lee"));interfaces.state("asset-check",1,"DRAFT","ENABLED","검증",Actor(Mode.ENGINEER,"lee"))
 }
 private fun reset() {counted.sql.clear();host.executions.set(0)}
 /** 스테이션마다 준비·완료 신호 한 쌍. [complete] 에 든 스테이션만 완료 신호가 켜져 있다. */
 private fun cell(stations:List<String>,complete:Set<String> = emptySet()) {
  val at=java.time.Instant.now().plusSeconds(1)
  val signals=stations.flatMap{s->listOf("""{"name":"$s-ready","kind":"BOOLEAN","safety":false,"value":"true","observedAt":"$at"}""","""{"name":"$s-done","kind":"BOOLEAN","safety":false,"value":"${s in complete}","observedAt":"$at"}""")}
  bench.cell=HostCall.Ok(json.readTree("""{"cell":{"signals":[${signals.joinToString(",")}]}}"""))
 }
 private fun station(id:String,ready:String="$id-ready",done:String="$id-done") =
  JdbcClient.create(plain).sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES(:id,:id,:ready,:done,3600,'engineer/lee')")
   .param("id",id).param("ready",ready).param("done",done).update()
 /** [stationId] 를 점유한 [state] 작업과 그 작업 요청(상태 [receiptState]). 전송한 지 1분 지났고 기준 결과 버전은 0 이다. */
 private fun occupied(stationId:String,state:String="RUNNING",receiptState:String="ACCEPTED"):UUID {
  val receipt=UUID.randomUUID();val p=JdbcClient.create(plain)
  p.sql("""INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state,required_station_id)
   VALUES(:id,'mes',:ext,'h','{}'::jsonb,:job,:state,:station)""").param("id",receipt).param("ext","EXT-$stationId").param("job","JO-$stationId").param("state",receiptState).param("station",stationId).update()
  p.sql("""INSERT INTO ops.station_run(run_id,station_id,payload,actor,state,reason,receipt_id,created_at,phase_at,dispatched_at,result_baseline)
   VALUES(:id,:station,'{}'::jsonb,'OPERATOR/kim',:state,'시드',:id,now()-interval '1 minute',now()-interval '1 minute',now()-interval '1 minute',0)""")
   .param("id",receipt).param("station",stationId).param("state",state).update()
  p.sql("UPDATE ops.rf_station SET owner=:id WHERE station_id=:station").param("id",receipt).param("station",stationId).update()
  return receipt
 }
 private fun occupiedRun(stationId:String):UUID = JdbcClient.create(plain).sql("SELECT run_id FROM ops.station_run WHERE station_id=:s").param("s",stationId).query(UUID::class.java).single()
 private fun stored(receipt:UUID,physical:String) = JdbcClient.create(plain).sql("""INSERT INTO ops.work_result(receipt_id,version,instance_id,execution_id,response_id,response_version,snapshot,result_hash)
   VALUES(:id,1,'mw-1','exec-1','response-1',1,CAST(:snapshot AS JSONB),'hash')""").param("id",receipt).param("snapshot","""{"jobResponse":{"physicalState":"$physical"}}""").update()
 private fun executionsOf(vararg jobs:String):HostCall<JsonNode> = bench.executionsWith(*jobs)
 private fun receipt(id:UUID):Receipt = WorkRequests(JdbcClient.create(plain),interfaces,operations,bench.host,TransactionTemplate(DataSourceTransactionManager(plain))).get(id)

 @Test fun `스테이션 tick 은 활성 스테이션 수와 무관하게 실행 목록을 한 번 읽고 저장 결과로 판단하며 스테이션마다 문장 수가 정해져 있다`() {
  val ids=(1..4).map{"st-$it"};ids.forEach{station(it)};cell(ids,complete=setOf("st-4"))
  val runs=ids.associateWith{occupied(it)}
  ids.take(3).forEach{stored(runs.getValue(it),"RUNNING")};stored(runs.getValue("st-4"),"PHYSICALLY_DONE")
  val c=coordinator()
  reset();c.tick()
  assertEquals(1,host.executions.get(),"활성 스테이션 넷에 실행 목록 조회는 tick 당 한 번")
  // 저장된 결과(물리 완료)와 새 설비 완료 신호로 끝난다. 실행 목록에는 그 실행이 없다.
  assertEquals("COMPLETED",c.get(runs.getValue("st-4")).state)
  assertEquals(listOf("RUNNING","RUNNING","RUNNING"),ids.take(3).map{c.get(runs.getValue(it)).state})
  // 변하지 않는 tick: 목록 1 + 스테이션마다 8(잠금 행·작업·점유 행·작업·작업 요청·저장 결과·잠금 행·작업).
  cell(ids)
  reset();c.tick()
  assertEquals(1,host.executions.get())
  assertTrue(counted.sql.size<=1+8*3+3,"tick 문장 ${counted.sql.size}: ${counted.sql}")
  assertEquals(0,counted.count("SELECT * FROM ops.work_request_review"),"상세 칸을 읽지 않는다")
 }
 @Test fun `스테이션 tick 은 실행 목록을 한 번 읽어 활성 스테이션마다 보인 실행을 작업 요청에 기록하고 불명 제출을 수락으로 바꾼다`() {
  val ids=listOf("st-1","st-2","st-3");ids.forEach{station(it)};cell(ids)
  val runs=ids.associateWith{occupied(it,receiptState="IN_DOUBT")}
  host.body={executionsOf("JO-st-1","JO-st-3")}
  reset();coordinator().tick()
  assertEquals(1,host.executions.get())
  for(id in listOf("st-1","st-3")) {
   val seen=receipt(runs.getValue(id))
   assertEquals("ACCEPTED",seen.state,id);assertEquals("JO-$id",seen.lastExecution!!["jobOrderId"].asText());assertEquals("mw-1",seen.executionInstanceId)
  }
  val unseen=receipt(runs.getValue("st-2"));assertEquals("IN_DOUBT",unseen.state);assertNull(unseen.lastExecution)
  // 실행이 일지 저장에 막힌 동안은 기록하지 않는다(상세와 같다).
  val blocked=executionsOf("JO-st-2").let{(it as HostCall.Ok).value.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply{putArray("journalBlockedExecutionIds").add("exec-1")}}
  host.body={HostCall.Ok(blocked)}
  coordinator().tick()
  assertNull(receipt(runs.getValue("st-2")).lastExecution)
 }
 @Test fun `같은 tick 에서 제출한 스테이션은 제출 뒤 실행 목록을 다시 읽어 그 실행을 기록한다`() {
  station("aa-1");station("inspection-1",ready="ready",done="complete")
  requests.resources.create(Actor(Mode.ENGINEER,"lee"),"InspectAsset","inspection-1","공통 검사 자원")
  val at=java.time.Instant.now().plusSeconds(1)
  bench.cell=HostCall.Ok(json.readTree("""{"cell":{"signals":[{"name":"aa-1-ready","kind":"BOOLEAN","safety":false,"value":"true","observedAt":"$at"},{"name":"aa-1-done","kind":"BOOLEAN","safety":false,"value":"false","observedAt":"$at"},
   {"name":"ready","kind":"BOOLEAN","safety":false,"value":"true","observedAt":"$at"},{"name":"complete","kind":"BOOLEAN","safety":false,"value":"false","observedAt":"$at"}]}}"""))
  occupied("aa-1")
  val queued=requests.receiveManual(Actor(Mode.OPERATOR,"kim"),json.readTree("""{"requestId":"${UUID.randomUUID()}","form":${JobOrderBench.INSPECT_FORM}}""")).receipt
  // 제출하기 전의 실행 목록에는 새 작업 지시가 없다.
  host.body={if(bench.submitted.isEmpty()) executionsOf("JO-aa-1") else executionsOf("JO-aa-1",queued.jobOrderId)}
  val c=coordinator()
  reset();c.tick()
  assertEquals("RUNNING",c.get(queued.receiptId).state);assertEquals(1,bench.submitted.size)
  // 앞 스테이션의 관측이 읽은 목록 1, 제출 뒤 다시 읽은 목록 1.
  assertEquals(2,host.executions.get())
  assertEquals(queued.jobOrderId,receipt(queued.receiptId).lastExecution!!["jobOrderId"].asText())
 }
 @Test fun `스테이션 tick 과 판단은 스테이션 목록 전체를 다시 읽지 않는다`() {
  val ids=(1..5).map{"st-$it"};ids.forEach{station(it)};cell(ids)
  occupied("st-1");occupied("st-2",state="WAITING_ROBOT");val review=occupied("st-3",state="REVIEW_REQUIRED")
  val c=coordinator()
  reset();c.tick()
  assertEquals(1,counted.count("SELECT * FROM ops.rf_station ORDER BY station_id"),"tick 당 목록 한 번: ${counted.sql}")
  reset()
  c.decide(review,Actor(Mode.OPERATOR,"kim"),json.readTree("""{"expectedRevision":${c.get(review).revision},"decision":"CONFIRM_CLEAR","reason":"현장 확인"}"""))
  assertEquals(0,counted.count("SELECT * FROM ops.rf_station ORDER BY station_id"),"판단은 목록을 읽지 않는다: ${counted.sql}")
  assertEquals("MANUALLY_CLEARED",c.get(review).state);assertEquals("RUNNING",c.get(occupiedRun("st-2")).state)
 }
}
