package dev.picasso.ops.service
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.interfaces.InterfaceMapping
import dev.picasso.ops.service.interfaces.MissionInterfaces
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.requests.*
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.web.WorkRequestController
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
class WorkRequestsTest {
 private val ds=DriverManagerDataSource(PostgresSupport.jdbcUrl,PostgresSupport.username,PostgresSupport.password)
 private val jdbc=JdbcClient.create(ds);private val log=OperationLog(jdbc);private val json=ObjectMapper()
 private val bench=JobOrderBench()
 private val interfaces=MissionInterfaces(jdbc,TransactionTemplate(DataSourceTransactionManager(ds)),log)
 private val operations=JobOrderOperations(bench.eligibility,bench.host,bench.host,log,bench.clock,requeryDelay=Duration.ZERO)
 private fun service()=WorkRequests(jdbc,interfaces,operations,bench.host,TransactionTemplate(DataSourceTransactionManager(ds)))
 private val service=service()
 private val body=json.readTree("""{"externalOrderId":"ORDER-1","interfaceId":"asset-check","interfaceVersion":1,"input":{"assets":[{"id":"T1","location":"bay-7"}]}}""")
 @BeforeTest fun fresh() {
  OpsSchema.flyway(ds,cleanable=true).apply{clean();migrate()}
  val spec=InterfaceMapping.spec(json.readTree("""{"interfaceId":"asset-check","version":1,"name":"점검","workMasterId":"InspectAsset","mapping":{"targets":"/assets"},"sample":{"assets":[{"id":"T1","location":"bay-7"}]}}"""))
  interfaces.create(spec,Actor(Mode.ENGINEER,"lee"));interfaces.state("asset-check",1,"DRAFT","ENABLED","검증",Actor(Mode.ENGINEER,"lee"))
 }
 @Test fun `같은 주문은 동일 접수와 내부 ID를 반환하고 저장소를 다시 만들어도 재전송하지 않는다`() {
  val first=service.receive("mes",body);assertFalse(first.duplicate);assertEquals("ACCEPTED",first.receipt.state)
  val replay=service().receive("mes",body);assertTrue(replay.duplicate);assertEquals(first.receipt,replay.receipt)
  assertEquals(first.receipt.jobOrderId,bench.submitted.single().first["jobOrderId"].asText())
  assertTrue(log.list().any { it.requestId == first.receipt.receiptId })
 }
 @Test fun `JSON 키 순서는 무관하고 다른 입력은 409이며 출처별 키는 독립적이다`() {
  service.receive("mes",body)
  val reordered=json.readTree("""{"input":{"assets":[{"location":"bay-7","id":"T1"}]},"interfaceVersion":1,"interfaceId":"asset-check","externalOrderId":"ORDER-1"}""")
  assertTrue(service.receive("mes",reordered).duplicate)
  val changed=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>();changed.put("interfaceVersion",2)
  assertEquals(409,assertFailsWith<RequestRejected>{service.receive("mes",changed)}.status)
  assertFalse(service.receive("wms",body).duplicate)
  assertEquals(2,bench.submitted.size)
 }
 @Test fun `동시 중복 접수는 호스트를 한 번만 부른다`() {
  val gate=CountDownLatch(1)
  Executors.newFixedThreadPool(6).use { pool ->
   val jobs=(1..6).map{pool.submit<ReceiptAccepted>{gate.await();service.receive("mes",body)}}
   gate.countDown();val answers=jobs.map{it.get(20,TimeUnit.SECONDS)}
   assertEquals(1,answers.count{!it.duplicate});assertEquals(1,answers.map{it.receipt.receiptId}.distinct().size)
  }
  assertEquals(1,bench.submitted.size)
 }
 @Test fun `업무 검증 거절과 미배정도 영속 접수로 남고 같은 주문은 재평가하지 않는다`() {
  val invalid=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>();invalid.set<com.fasterxml.jackson.databind.JsonNode>("input",json.readTree("{}"))
  assertEquals("REJECTED",service.receive("mes",invalid).receipt.state);assertTrue(bench.submitted.isEmpty())
  bench.judge={HostCall.Ok(it.map{id->JobOrderBench.running(id,"busy")})}
  val row=service.receive("wms",body).receipt;assertEquals("UNASSIGNED",row.state)
  assertTrue(service().receive("wms",body).duplicate);assertTrue(bench.submitted.isEmpty())
  assertEquals(2L,service.list("",null,0,20)["total"])
 }
 @Test fun `응답 불명과 제출 중단 상태는 재접수로 다시 실행하지 않는다`() {
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")
  val row=service.receive("mes",body).receipt;assertEquals("IN_DOUBT",row.state)
  assertTrue(service.receive("mes",body).duplicate);assertEquals(1,bench.submitted.size)
  jdbc.sql("UPDATE ops.work_request SET state='DISPATCHING' WHERE receipt_id=:id").param("id",row.receiptId).update()
  assertEquals("DISPATCHING",service().receive("mes",body).receipt.state);assertEquals(1,bench.submitted.size)
 }
 @Test fun `전송 불명은 실행 관측으로 확정하고 호스트 단절 후에도 직전 관측을 보존한다`() {
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")
  val row=service.receive("mes",body).receipt
  assertEquals("IN_DOUBT",row.state)
  bench.executions=bench.executionsWith(row.jobOrderId)
  val seen=service().detail(row.receiptId)
  val confirmed=seen["receipt"] as Receipt
  assertEquals("ACCEPTED",confirmed.state);assertNotNull(confirmed.lastExecution)
  bench.executions=HostCall.Silent("offline")
  val stale=service().detail(row.receiptId)
  assertEquals("UNAVAILABLE",stale["hostRead"]);assertNull(stale["execution"])
  assertEquals(confirmed.lastExecution,(stale["receipt"] as Receipt).lastExecution)
  assertEquals(1,bench.submitted.size)
 }
 @Test fun `접수 저장 실패 시 호스트는 호출하지 않는다`() {
  PostgresSupport.execute("ALTER TABLE ops.work_request ADD CONSTRAINT deny_receipt CHECK (false) NOT VALID")
  assertFails {service.receive("mes",body)};assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `인증은 서버 출처에 묶이고 다른 출처 접수는 404이며 빈 토큰은 접근을 막는다`() {
  val api=WorkRequestController(service,"secret-test-token","mock-mes")
  assertEquals(401,api.receive(null,body.toString().toByteArray()).statusCode.value())
  assertEquals(401,WorkRequestController(service,"","mock-mes").receive("Bearer x",body.toString().toByteArray()).statusCode.value())
  assertEquals(202,api.receive("Bearer secret-test-token",body.toString().toByteArray()).statusCode.value())
  assertEquals(200,api.receive("Bearer secret-test-token",body.toString().toByteArray()).statusCode.value())
  val other=service.receive("other-mes",body).receipt
  assertEquals(404,api.external("Bearer secret-test-token",other.receiptId).statusCode.value())
  assertEquals(400,api.receive("Bearer secret-test-token",ByteArray(65537)).statusCode.value())
 }
 @Test fun `호스트 조회 실패를 실행 없음과 구분하며 검색과 페이지 범위를 제한한다`() {
  val row=service.receive("mes",body).receipt
  bench.executions=HostCall.Silent("offline")
  assertEquals("UNAVAILABLE",service.detail(row.receiptId)["hostRead"])
  bench.executions=HostCall.Ok(json.readTree("{}"))
  assertEquals("UNAVAILABLE",service.detail(row.receiptId)["hostRead"])
  assertEquals(1L,service.list("ORDER-1","ACCEPTED",0,20)["total"])
  assertEquals(0L,service.list("missing",null,0,20)["total"])
  assertFailsWith<RequestRejected>{service.list("",null,0,101)}
  assertEquals(404,assertFailsWith<RequestRejected>{service.get(row.receiptId,"other")}.status)
 }

 private fun observeResult(job:String,version:Int=1,state:String="PHYSICALLY_DONE",blocked:Boolean=false) {
  bench.executions=HostCall.Ok(json.readTree("""{"instanceId":"mw-1","journalBlockedExecutionIds":${if(blocked) "[\"exec-1\"]" else "[]"},"executions":[{
   "executionId":"exec-1","jobOrderId":"$job","workMasterId":"InspectAsset","missionVersion":null,"robotId":"humanoid-01","physicalState":"$state",
   "jobResponse":{"jobResponseId":"response-$version","version":$version,"physicalState":"$state","requiredEvidence":"E2","reachedEvidence":"E2",
   "completedUnits":["T1"],"unverifiedUnits":[],"incompleteUnits":{},"inDoubtUnits":[],"operatorRequired":false,"residualHold":"EMPTY","blockedBy":[],"connection":"ONLINE"}}]}"""))
 }
 @Test fun `결과 재조회는 같은 불변 버전이며 호스트 단절과 저장소 재생성 후에도 조회한다`() {
  val row=service.receive("mes",body).receipt
  assertEquals(404,assertFailsWith<RequestRejected>{service.result(row.receiptId,"mes")}.status)
  observeResult(row.jobOrderId)
  val result=service.result(row.receiptId,"mes");assertEquals(1,result["version"])
  assertNull(result["acknowledgedAt"])
  assertEquals(result,service.result(row.receiptId,"mes"))
  bench.executions=HostCall.Silent("offline")
  assertEquals(result,service().result(row.receiptId,"mes",1))
  assertEquals(result,service().result(row.receiptId,"mes"))
  assertEquals(1,bench.submitted.size)
 }
 @Test fun `ACK는 버전과 해시에 묶이고 재작업의 최신 결과는 별도로 확인한다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId,1,"PARTIAL")
  val first=service.result(row.receiptId,"mes")
  assertEquals(409,assertFailsWith<RequestRejected>{service.acknowledge(row.receiptId,"mes",1,"0".repeat(64))}.status)
  val ack=service.acknowledge(row.receiptId,"mes",1,first["resultHash"] as String)
  assertNotNull(ack["acknowledgedAt"])
  assertEquals(ack,service().acknowledge(row.receiptId,"mes",1,first["resultHash"] as String))
  observeResult(row.jobOrderId,2)
  val second=service.result(row.receiptId,"mes");assertEquals(2,second["version"]);assertNull(second["acknowledgedAt"])
  service.acknowledge(row.receiptId,"mes",1,first["resultHash"] as String)
  assertNull(service.result(row.receiptId,"mes",2)["acknowledgedAt"])
  assertEquals(first["snapshot"],service.result(row.receiptId,"mes",1)["snapshot"])
  assertEquals(404,assertFailsWith<RequestRejected>{service.acknowledge(row.receiptId,"other",2,second["resultHash"] as String)}.status)
  assertEquals(404,assertFailsWith<RequestRejected>{service.acknowledge(row.receiptId,"mes",3,second["resultHash"] as String)}.status)
  assertEquals(1,bench.submitted.size)
 }
 @Test fun `같은 작업 버전의 새 결과 ID도 별도 결과로 보존한다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId,1,"PARTIAL")
  val first=service.result(row.receiptId,"mes");assertEquals(1,first["version"])
  observeResult(row.jobOrderId,1)
  val host=(bench.executions as HostCall.Ok).value.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
  (host["executions"][0]["jobResponse"] as com.fasterxml.jackson.databind.node.ObjectNode).put("jobResponseId","response-next")
  bench.executions=HostCall.Ok(host)
  val second=service.result(row.receiptId,"mes");assertEquals(2,second["version"])
  assertEquals("PHYSICALLY_DONE",(second["snapshot"] as com.fasterxml.jackson.databind.JsonNode)["jobResponse"]["physicalState"].asText())
  assertEquals(first["snapshot"],service.result(row.receiptId,"mes",1)["snapshot"])
 }
 @Test fun `동시 결과 관측은 한 버전을 만들며 늦은 이전 응답은 최신 결과를 되돌리지 않는다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId,2)
  Executors.newFixedThreadPool(6).use { pool ->
   val answers=(1..6).map{pool.submit<Map<String,Any?>>{service().result(row.receiptId,"mes")}}.map{it.get(20,TimeUnit.SECONDS)}
   assertEquals(setOf(1),answers.map{it["version"]}.toSet())
  }
  observeResult(row.jobOrderId,1,"PARTIAL")
  assertEquals(1,service.result(row.receiptId,"mes")["version"])
  val response=(service.result(row.receiptId,"mes")["snapshot"] as com.fasterxml.jackson.databind.JsonNode)["jobResponse"]
  assertEquals(2,response["version"].asInt())
 }
 @Test fun `일지 차단과 재작업 중 이전 응답은 결과를 발행하지 않는다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId,blocked=true)
  assertEquals(404,assertFailsWith<RequestRejected>{service.result(row.receiptId,"mes")}.status)
  observeResult(row.jobOrderId)
  val host=(bench.executions as HostCall.Ok).value.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
  (host["executions"][0] as com.fasterxml.jackson.databind.node.ObjectNode).put("physicalState","RUNNING")
  bench.executions=HostCall.Ok(host)
  assertEquals(404,assertFailsWith<RequestRejected>{service.result(row.receiptId,"mes")}.status)
 }
 @Test fun `외부 결과 API는 인증과 정확한 ACK 본문을 요구한다`() {
  val api=WorkRequestController(service,"secret","mes")
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId)
  assertEquals(401,api.latestResult(null,row.receiptId).statusCode.value())
  assertEquals(200,api.latestResult("Bearer secret",row.receiptId).statusCode.value())
  assertEquals(400,api.acknowledge("Bearer secret",row.receiptId,1,"{}".toByteArray()).statusCode.value())
  val result=service.result(row.receiptId,"mes")
  val ack=json.writeValueAsBytes(mapOf("resultHash" to result["resultHash"]))
  assertEquals(200,api.acknowledge("Bearer secret",row.receiptId,1,ack).statusCode.value())
  assertEquals(200,api.result("Bearer secret",row.receiptId,1).statusCode.value())
 }

 private val manualActor=Actor(Mode.OPERATOR,"kim")
 private fun manualBody(id:java.util.UUID=java.util.UUID.randomUUID(),form:String=JobOrderBench.INSPECT_FORM)=json.readTree("""{"requestId":"$id","form":$form}""")
 @Test fun `수동 폼은 영속 접수와 요청자를 기록하고 같은 요청 ID는 재기동 후에도 재실행하지 않는다`() {
  val input=manualBody();val first=service.receiveManual(manualActor,input)
  assertEquals("ops:manual",first.receipt.sourceSystemId);assertEquals("operator/kim",first.receipt.payload["requestedBy"].asText())
  assertEquals("ACCEPTED",first.receipt.state);assertFalse(first.duplicate)
  val replay=service().receiveManual(manualActor,input)
  assertTrue(replay.duplicate);assertEquals(first.receipt,replay.receipt);assertEquals(1,bench.submitted.size)
  assertEquals("kim",log.list().single{it.requestId==first.receipt.receiptId}.user)
  assertEquals(1L,service.list("ops:manual",null,0,20)["total"])
  observeResult(first.receipt.jobOrderId);assertNotNull(service.detail(first.receipt.receiptId)["latestResult"])
  assertEquals(404,assertFailsWith<RequestRejected>{service.result(first.receipt.receiptId,"mock-mes")}.status)
 }
 @Test fun `수동 요청은 입력과 요청자 충돌을 거절하고 동시 중복을 한 번만 제출한다`() {
  val input=manualBody()
  Executors.newFixedThreadPool(6).use { pool ->
   val gate=CountDownLatch(1)
   val jobs=(1..6).map{pool.submit<ReceiptAccepted>{gate.await();service().receiveManual(manualActor,input)}}
   gate.countDown();assertEquals(1,jobs.map{it.get(20,TimeUnit.SECONDS)}.count{!it.duplicate})
  }
  assertEquals(1,bench.submitted.size)
  val changed=input.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
  (changed["form"]["targets"][0] as com.fasterxml.jackson.databind.node.ObjectNode).put("location","bay-9")
  assertEquals(409,assertFailsWith<RequestRejected>{service.receiveManual(manualActor,changed)}.status)
  assertEquals(409,assertFailsWith<RequestRejected>{service.receiveManual(Actor(Mode.OPERATOR,"lee"),input)}.status)
  assertEquals(1,bench.submitted.size)
 }
 @Test fun `수동 업무 검증 거절과 미배정도 접수 목록에 남고 자동 재배정하지 않는다`() {
  val bad=service.receiveManual(manualActor,manualBody(form="{}"))
  assertEquals("REJECTED",bad.receipt.state);assertNotNull(bad.receipt.detail)
  bench.judge={HostCall.Ok(it.map{id->JobOrderBench.running(id,"busy")})}
  val input=manualBody();val unassigned=service.receiveManual(manualActor,input)
  assertEquals("UNASSIGNED",unassigned.receipt.state)
  bench.judge={HostCall.Ok(it.map(JobOrderBench::pass))}
  assertTrue(service.receiveManual(manualActor,input).duplicate);assertTrue(bench.submitted.isEmpty())
  assertEquals(2L,service.list("ops:manual",null,0,20)["total"])
 }
 @Test fun `수동 전송 불명과 중단 상태는 동일 요청으로 재제출하지 않는다`() {
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")
  val input=manualBody();val row=service.receiveManual(manualActor,input).receipt
  assertEquals("IN_DOUBT",row.state)
  assertTrue(service().receiveManual(manualActor,input).duplicate)
  jdbc.sql("UPDATE ops.work_request SET state='DISPATCHING' WHERE receipt_id=:id").param("id",row.receiptId).update()
  assertEquals("DISPATCHING",service().receiveManual(manualActor,input).receipt.state);assertEquals(1,bench.submitted.size)
 }
 @Test fun `수동 접수 API는 운영자 관문과 본문 크기 및 UUID 형식을 확인한다`() {
  val api=WorkRequestController(service,"secret","mock-mes");val input=manualBody().toString().toByteArray()
  assertEquals(403,api.manual("engineer","kim",null).statusCode.value())
  assertEquals(400,api.manual(null,"kim",input).statusCode.value())
  assertEquals(400,api.manual("operator","kim",ByteArray(65537)).statusCode.value())
  assertEquals(400,api.manual("operator","kim","{\"requestId\":\"1-1-1-1-1\",\"form\":{}}".toByteArray()).statusCode.value())
  assertEquals(0L,service.list("",null,0,20)["total"])
  val first=api.manual("operator","kim",input);assertEquals(202,first.statusCode.value())
  assertNotNull(first.headers.location)
  assertEquals(200,api.manual("operator","kim",input).statusCode.value())
  assertEquals(1,bench.submitted.size)
 }
 @Test fun `수동 접수 DB 실패 시 호스트에 제출하지 않는다`() {
  PostgresSupport.execute("ALTER TABLE ops.work_request ADD CONSTRAINT deny_manual CHECK (false) NOT VALID")
  assertFails{service.receiveManual(manualActor,manualBody())};assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `결과 수집은 조회 없이 저장하며 호스트 장애에도 저장 결과를 보존한다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId)
  assertEquals(1,service.collectResults())
  assertEquals(1,service().result(row.receiptId,"mes",1)["version"])
  assertEquals(1,service.collectResults());assertEquals(1,service.result(row.receiptId,"mes",1)["version"])
  bench.executions=HostCall.Silent("offline")
  assertFailsWith<IllegalStateException>{service.collectResults()}
  assertEquals(1,service.result(row.receiptId,"mes",1)["version"])
 }
 @Test fun `개요 집계와 수신 미확인 필터는 로컬 결과를 제외하고 최신 ACK를 따른다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId);service.collectResults()
  assertEquals(1L,service.summary()["unacknowledgedResults"])
  assertEquals(1L,service.list("",null,0,20,"unacknowledged")["total"])
  val result=service.result(row.receiptId,"mes",1)
  service.acknowledge(row.receiptId,"mes",1,result["resultHash"] as String)
  assertEquals(0L,service.summary()["unacknowledgedResults"])
  assertEquals(0L,service.list("",null,0,20,"unacknowledged")["total"])
  jdbc.sql("UPDATE ops.work_request SET state='DISPATCHING' WHERE receipt_id=:id").param("id",row.receiptId).update()
  assertEquals(1L,service.list("","NEEDS_CONFIRMATION",0,20)["total"])
 }
 @Test fun `전송 전 미배정 재평가는 같은 작업 ID로 한 번 진행하고 원래 접수 재요청은 재실행하지 않는다`() {
  bench.judge={HostCall.Ok(it.map{id->JobOrderBench.running(id,"busy")})}
  val row=service.receive("mes",body).receipt;assertEquals("UNASSIGNED",row.state);assertTrue(bench.submitted.isEmpty())
  val request=json.createObjectNode().put("requestId",java.util.UUID.randomUUID().toString()).put("reason","기체 준비 완료").put("expectedUpdatedAt",row.updatedAt)
  bench.judge={ids->HostCall.Ok(ids.map(JobOrderBench::pass))}
  val recovered=service.recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),request)
  assertEquals("ACCEPTED",recovered.receipt.state);assertFalse(recovered.duplicate)
  assertTrue(service().recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),request).duplicate)
  assertTrue(service.receive("mes",body).duplicate)
  assertEquals(row.jobOrderId,bench.submitted.single().first["jobOrderId"].asText())
  assertEquals(1,(service.detail(row.receiptId)["recoveries"] as List<*>).size)
  assertEquals(409,assertFailsWith<RequestRejected>{service.recover(row.receiptId,Actor(Mode.OPERATOR,"lee"),request)}.status)
 }
 @Test fun `불명 접수와 오래된 상태는 재평가할 수 없다`() {
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")
  val row=service.receive("mes",body).receipt
  val request=json.createObjectNode().put("requestId",java.util.UUID.randomUUID().toString()).put("reason","재개").put("expectedUpdatedAt",row.updatedAt)
  assertEquals(409,assertFailsWith<RequestRejected>{service.recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),request)}.status)
  assertEquals(403,assertFailsWith<RequestRejected>{service.recover(row.receiptId,Actor(Mode.ENGINEER,"kim"),request)}.status)
  assertEquals(1,bench.submitted.size)
  jdbc.sql("UPDATE ops.work_request SET state='UNASSIGNED',submission=NULL,updated_at=clock_timestamp() WHERE receipt_id=:id").param("id",row.receiptId).update()
  assertEquals(409,assertFailsWith<RequestRejected>{service.recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),request)}.status)
 }

 @Test fun `로컬 callback은 실패를 재전송하고 같은 버전 키와 내용을 유지하며 ACK와 분리한다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId);service.collectResults()
  val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
  val seen=java.util.Collections.synchronizedList(mutableListOf<Pair<String,String>>())
  server.createContext("/results") { exchange ->
   seen+=exchange.requestHeaders.getFirst("Idempotency-Key") to exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
   exchange.sendResponseHeaders(if(seen.size==1) 503 else 204,-1);exchange.close()
  };server.start()
  try {
   val url="http://127.0.0.1:${server.address.port}/results"
   WorkResultDelivery(jdbc,service,url,"","mes").deliver()
   assertEquals("RETRY",jdbc.sql("SELECT state FROM ops.work_result_delivery").query(String::class.java).single())
   assertEquals(1,seen.size)
   jdbc.sql("UPDATE ops.work_result_delivery SET next_attempt_at=clock_timestamp()-interval '1 second'").update()
   WorkResultDelivery(jdbc,service(),url,"","mes").deliver()
   assertEquals("DELIVERED",jdbc.sql("SELECT state FROM ops.work_result_delivery").query(String::class.java).single())
   assertEquals(2,seen.size);assertEquals(seen[0],seen[1]);assertEquals("${row.receiptId}/v1",seen[0].first)
   assertNull(service.result(row.receiptId,"mes",1)["acknowledgedAt"])
   WorkResultDelivery(jdbc,service,url,"","mes").deliver();assertEquals(2,seen.size)
  } finally {server.stop(0)}
 }
 @Test fun `로컬 callback은 외부 주소를 거부하며 비활성 시 큐를 만들지 않는다`() {
  assertFailsWith<IllegalArgumentException>{WorkResultDelivery(jdbc,service,"https://example.com/results","","mes")}
  assertFailsWith<IllegalArgumentException>{WorkResultDelivery(jdbc,service,"http://127.0.0.1.evil.test/results","","mes")}
  WorkResultDelivery(jdbc,service,"","","mes").deliver()
  assertEquals(0L,jdbc.sql("SELECT count(*) FROM ops.work_result_delivery").query(Long::class.java).single())
 }

 @Test fun `늦게 끝난 이전 호스트 조회는 최신 관측과 같은 작업 버전의 새 결과를 되돌리지 않는다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId,1,"PARTIAL")
  val old=(bench.executions as HostCall.Ok).value
  val started=CountDownLatch(1);val finish=CountDownLatch(1)
  val slow=object:dev.picasso.ops.service.host.HostReads {
   override fun eligibility(jobOrder:com.fasterxml.jackson.databind.node.ObjectNode,robotIds:List<String>)=bench.host.eligibility(jobOrder,robotIds)
   override fun cell()=bench.host.cell()
   override fun executions():HostCall<com.fasterxml.jackson.databind.JsonNode> {started.countDown();check(finish.await(20,TimeUnit.SECONDS));return HostCall.Ok(old)}
  }
  val delayed=WorkRequests(jdbc,interfaces,operations,slow,TransactionTemplate(DataSourceTransactionManager(ds)))
  Executors.newSingleThreadExecutor().use {pool ->
   val pending=pool.submit<Map<String,Any?>>{delayed.detail(row.receiptId)}
   assertTrue(started.await(20,TimeUnit.SECONDS))
   observeResult(row.jobOrderId,1)
   val current=(bench.executions as HostCall.Ok).value
   (current["executions"][0]["jobResponse"] as com.fasterxml.jackson.databind.node.ObjectNode).put("jobResponseId","new-response-same-job-version")
   try {service.collectResults()}finally{finish.countDown()}
   pending.get(20,TimeUnit.SECONDS)
  }
  assertEquals("PHYSICALLY_DONE",service.get(row.receiptId).lastExecution!!["physicalState"].asText())
  assertEquals("PHYSICALLY_DONE",(service.result(row.receiptId,"mes",1)["snapshot"] as com.fasterxml.jackson.databind.JsonNode)["jobResponse"]["physicalState"].asText())
 }
 @Test fun `동시 재평가는 한 번만 제출한다`() {
  bench.judge={ids->HostCall.Ok(ids.map{id->JobOrderBench.running(id,"busy")})}
  val row=service.receive("mes",body).receipt
  val request=json.createObjectNode().put("requestId",java.util.UUID.randomUUID().toString()).put("reason","동시 재평가").put("expectedUpdatedAt",row.updatedAt)
  bench.judge={ids->HostCall.Ok(ids.map(JobOrderBench::pass))}
  val gate=CountDownLatch(1)
  Executors.newFixedThreadPool(6).use{pool ->
   val jobs=(1..6).map{pool.submit<ReceiptAccepted>{gate.await();service().recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),request)}}
   gate.countDown();val replies=jobs.map{it.get(20,TimeUnit.SECONDS)}
   assertEquals(1,replies.count{!it.duplicate})
  }
  assertEquals(1,bench.submitted.size)
 }
 private fun waiting(locktype:String)=jdbc.sql("SELECT count(*) FROM pg_locks WHERE locktype=:type AND NOT granted").param("type",locktype).query(Long::class.java).single()>0
 private fun await(what:String,done:()->Boolean) {val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!done()) {check(System.nanoTime()<until){"$what 대기 시간 초과"};Thread.sleep(20)}}
 @Test fun `재평가 승인과 커밋 사이의 자원 요구 등록은 재평가 커밋을 기다리고 접수를 제출 중에 남기지 않는다`() {
  jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES('inspection-1','공유 검사','ready','complete',30,'engineer/lee')").update()
  bench.judge={ids->HostCall.Ok(ids.map{id->JobOrderBench.running(id,"busy")})}
  val row=service.receive("mes",body).receipt;assertEquals("UNASSIGNED",row.state)
  val attempt=UUID.randomUUID()
  val request=json.createObjectNode().put("requestId",attempt.toString()).put("reason","경합 재평가").put("expectedUpdatedAt",row.updatedAt)
  bench.judge={ids->HostCall.Ok(ids.map(JobOrderBench::pass))}
  // Holds recover after its authorize, before its commit: an uncommitted row with the same attempt key makes its INSERT wait.
  // The row points at another receipt, so recover's own row lock and the mission's in-flight count are untouched.
  val blocker=PostgresSupport.connection().apply{autoCommit=false}
  try {
   blocker.prepareStatement("INSERT INTO ops.work_request_recovery(attempt_id,receipt_id,actor,reason,expected_updated_at) VALUES(?,?,'test','hold','hold')").use{
    it.setObject(1,attempt);it.setObject(2,raw("REJECTED","OtherMission"));it.executeUpdate()}
   Executors.newFixedThreadPool(2).use{pool ->
    val recovered=pool.submit<ReceiptAccepted>{service().recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),request)}
    await("재평가 정지"){waiting("transactionid")}
    val created=pool.submit<Map<String,Any?>>{service().resources.create(Actor(Mode.ENGINEER,"lee"),"InspectAsset","inspection-1","재평가 중 등록")}
    await("등록 대기"){created.isDone || waiting("advisory")}
    val waited=!created.isDone
    blocker.rollback()
    val reply=recovered.get(20,TimeUnit.SECONDS)
    val refused=assertFailsWith<java.util.concurrent.ExecutionException>{created.get(20,TimeUnit.SECONDS)}.cause as RequestRejected
    assertTrue(waited,"자원 요구 등록이 재평가 커밋을 기다리지 않았습니다")
    assertEquals("RESOURCE_REQUIREMENT",refused.code);assertEquals(409,refused.status)
    assertFalse(reply.duplicate);assertEquals("ACCEPTED",reply.receipt.state)
   }
  } finally {blocker.close()}
  assertNull(service.resources.required("InspectAsset"));assertEquals(1,bench.submitted.size)
  assertEquals("ACCEPTED",service.get(row.receiptId).state)
 }
 /** Commits a requirement together with the DISPATCHING claim, past recover's own check: only dispatch()'s authorize sees it. */
 private fun requirementOnClaim(sweep:Boolean) {
  PostgresSupport.execute("""CREATE FUNCTION ops.test_requirement() RETURNS trigger AS $$ BEGIN
   IF NEW.state='DISPATCHING' AND OLD.state<>'DISPATCHING' THEN
    INSERT INTO ops.mission_resource_requirement(work_master_id,station_id,created_by,reason) VALUES(NEW.normalized_order->>'workMasterId','inspection-1','engineer/lee','제출 직전 등록');
    ${if(sweep) "NEW.state:='IN_DOUBT';NEW.detail:='swept';" else ""}
   END IF; RETURN NEW; END $$ LANGUAGE plpgsql""")
  PostgresSupport.execute("CREATE TRIGGER test_requirement BEFORE UPDATE ON ops.work_request FOR EACH ROW EXECUTE FUNCTION ops.test_requirement()")
 }
 private fun unassignedBeforeRequirement(sweep:Boolean):Receipt {
  jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES('inspection-1','공유 검사','ready','complete',30,'engineer/lee')").update()
  bench.judge={ids->HostCall.Ok(ids.map{id->JobOrderBench.running(id,"busy")})}
  val row=service.receive("mes",body).receipt;assertEquals("UNASSIGNED",row.state)
  requirementOnClaim(sweep);bench.judge={ids->HostCall.Ok(ids.map(JobOrderBench::pass))}
  val ex=assertFailsWith<RequestRejected>{service.recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),json.readTree("""{"requestId":"${UUID.randomUUID()}","reason":"제출 직전 변경","expectedUpdatedAt":"${row.updatedAt}"}"""))}
  assertEquals("RESOURCE_REQUIREMENT",ex.code);assertEquals("필수 자원 대기열을 거치지 않은 실행입니다.",ex.message);assertTrue(bench.submitted.isEmpty())
  return service.get(row.receiptId)
 }
 @Test fun `제출 직전 자원 요구가 바뀌면 호스트에 보내지 않고 거절로 닫으며 오류를 알린다`() {
  val after=unassignedBeforeRequirement(sweep=false)
  assertEquals("REJECTED",after.state);assertEquals("자원 요구가 바뀌어 호스트에 보내지 않았습니다. 새 요청 ID로 다시 보내십시오: 필수 자원 대기열을 거치지 않은 실행입니다.",after.detail)
  assertNull(after.submission)
 }
 @Test fun `제출 직전 자원 거절은 이미 다른 판단으로 닫힌 접수를 덮지 않는다`() {
  val after=unassignedBeforeRequirement(sweep=true)
  assertEquals("IN_DOUBT",after.state);assertEquals("swept",after.detail)
 }

 private fun durable(feed:()->HostCall<com.fasterxml.jackson.databind.JsonNode>)=WorkRequests(jdbc,interfaces,operations,
  object:dev.picasso.ops.service.host.HostReads by bench.host {override fun resultEvents(after:Long,limit:Int)=feed()},TransactionTemplate(DataSourceTransactionManager(ds)),durableResults=true)
 private fun feed(job:String,producer:String=PRODUCER,count:Int=2):com.fasterxml.jackson.databind.JsonNode {
  observeResult(job)
  val snapshot=(bench.executions as HostCall.Ok).value["executions"][0].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("instanceId","mw-1")
  val events=(1..count).map{n->val copy=snapshot.deepCopy();(copy["jobResponse"] as com.fasterxml.jackson.databind.node.ObjectNode).put("jobResponseId","r$n");mapOf("sequence" to n,"snapshot" to copy)}
  return json.valueToTree(mapOf("producerId" to producer,"highWatermark" to count,"events" to events))
 }
 @Test fun `durable cursor resumes after restart and never lets detail reorder backlog`() {
  val row=service.receive("mes",body).receipt
  val page=feed(row.jobOrderId)
  var call:HostCall<com.fasterxml.jackson.databind.JsonNode> = HostCall.Ok(page)
  val worker=durable{call}
  worker.detail(row.receiptId)
  assertEquals(0L,jdbc.sql("SELECT count(*) FROM ops.work_result").query(Long::class.java).single())
  assertEquals(2,worker.collectResults());assertEquals(2,worker.result(row.receiptId,"mes")["version"])
  call=HostCall.Silent("offline")
  assertFailsWith<IllegalStateException>{worker.collectResults()}
  assertEquals(2L,worker.cursorStatus()["sequence"])
  val empty=page.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>();empty.putArray("events");call=HostCall.Ok(empty)
  val restarted=durable{call};assertEquals(0,restarted.collectResults())
  assertEquals(2L,jdbc.sql("SELECT count(*) FROM ops.work_result").query(Long::class.java).single())
  empty.put("producerId",java.util.UUID.randomUUID().toString())
  assertFailsWith<IllegalStateException>{restarted.collectResults()}
  assertEquals(PRODUCER,restarted.cursorStatus()["producerId"])
 }
 @Test fun `invalid event and database failure roll back the whole page and cursor`() {
  val row=service.receive("mes",body).receipt;val page=feed(row.jobOrderId)
  val worker=durable{HostCall.Ok(page)}
  (page["events"][1]["snapshot"]["jobResponse"] as com.fasterxml.jackson.databind.node.ObjectNode).put("requiredEvidence","bad")
  assertFailsWith<IllegalStateException>{worker.collectResults()};assertEquals(0L,worker.cursorStatus()["sequence"])
  (page["events"][1]["snapshot"]["jobResponse"] as com.fasterxml.jackson.databind.node.ObjectNode).put("requiredEvidence","E2")
  jdbc.sql("ALTER TABLE ops.work_result ADD CONSTRAINT refuse_second CHECK(response_id<>'r2')").update()
  assertFailsWith<org.springframework.dao.DataAccessException>{worker.collectResults()}
  assertEquals(0L,worker.cursorStatus()["sequence"])
  assertEquals(0L,jdbc.sql("SELECT count(*) FROM ops.work_result").query(Long::class.java).single())
  jdbc.sql("ALTER TABLE ops.work_result DROP CONSTRAINT refuse_second").update()
  assertEquals(2,worker.collectResults())
 }
 @Test fun `concurrent durable collectors commit one set of versions`() {
  val row=service.receive("mes",body).receipt;val page=feed(row.jobOrderId)
  val gate=CountDownLatch(2)
  val worker=durable{gate.countDown();gate.await(10,TimeUnit.SECONDS);HostCall.Ok(page)}
  Executors.newFixedThreadPool(2).use{pool->(1..2).map{pool.submit<Int>{worker.collectResults()}}.forEach{it.get(20,TimeUnit.SECONDS)}}
  assertEquals(2L,worker.cursorStatus()["sequence"])
  assertEquals(2L,jdbc.sql("SELECT count(*) FROM ops.work_result").query(Long::class.java).single())
 }

 @Test fun `operator review is immutable idempotent fenced and never submits or completes work`() {
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")
  val row=service.receive("mes",body).receipt;val actor=Actor(Mode.OPERATOR,"kim")
  val review=json.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(mapOf("requestId" to UUID.randomUUID().toString(),"observation" to "NOT_STARTED","reason" to "현장 담당자 김 확인","expectedUpdatedAt" to row.updatedAt))
  assertEquals(false,service.review(row.receiptId,actor,review)["duplicate"])
  assertEquals(true,service().review(row.receiptId,actor,review)["duplicate"])
  assertEquals("IN_DOUBT",service.get(row.receiptId).state);assertEquals(1,bench.submitted.size)
  assertEquals(1,(service.detail(row.receiptId)["reviews"] as List<*>).size)
  review.put("reason","different")
  assertEquals("REVIEW_CONFLICT",assertFailsWith<RequestRejected>{service.review(row.receiptId,actor,review)}.code)
  review.put("requestId",UUID.randomUUID().toString()).put("expectedUpdatedAt","old")
  assertEquals("STALE_REVIEW",assertFailsWith<RequestRejected>{service.review(row.receiptId,actor,review)}.code)
  assertEquals(403,assertFailsWith<RequestRejected>{service.review(row.receiptId,Actor(Mode.ENGINEER,"kim"),review)}.status)
 }
 private companion object {const val PRODUCER="43b09c24-1e61-4975-a56f-e789271f2080"}
 private fun resource() {
  jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES('inspection-1','공유 검사','ready','complete',30,'engineer/lee')").update()
  service.resources.create(Actor(Mode.ENGINEER,"lee"),"InspectAsset","inspection-1","공통 검사 자원")
 }
 private fun ready(value:Boolean,complete:Boolean=false) {bench.cell=HostCall.Ok(json.readTree("""{"cell":{"signals":[{"name":"ready","kind":"BOOLEAN","safety":false,"value":"$value","observedAt":"${java.time.Instant.now().plusSeconds(1)}"},{"name":"complete","kind":"BOOLEAN","safety":false,"value":"$complete","observedAt":"${java.time.Instant.now().plusSeconds(1)}"}]}}"""))}
 private fun coordinator()=dev.picasso.ops.service.stations.StationCoordinator(jdbc,TransactionTemplate(DataSourceTransactionManager(ds)),dev.picasso.ops.service.stations.OpsStationPort(service,interfaces,bench.host))
 private fun manual(id:UUID=UUID.randomUUID())=service.receiveManual(Actor(Mode.OPERATOR,"kim"),json.readTree("""{"requestId":"$id","form":${JobOrderBench.INSPECT_FORM}}"""))
 @Test fun `상위 주문 계약 호출 수동 직접 제출은 하나의 필수 자원 대기열을 사용한다`() {
  resource();ready(false)
  val mes=service.receive("mes",body)
  val invoke=service.receiveInterface(Actor(Mode.OPERATOR,"kim"),"asset-check",1,json.readTree("""{"requestId":"${UUID.randomUUID()}","input":${body["input"]}}"""))
  val local=manual()
  val controller=dev.picasso.ops.service.web.JobOrderController(bench.eligibility,operations,bench.host,service)
  val legacy=controller.submit("operator","kim",JobOrderBench.INSPECT_FORM.toByteArray())
  assertEquals(202,legacy.statusCode.value())
  for(r in listOf(mes,invoke,local)) {assertEquals("WAITING_RESOURCE",r.receipt.state);assertEquals("inspection-1",r.receipt.requiredStationId)}
  assertEquals(4,coordinator().runs().size);assertTrue(bench.submitted.isEmpty())
  coordinator().tick();assertEquals(1,coordinator().runs().count{it.state=="WAITING_READY"})
  assertTrue(service.receive("mes",body).duplicate);assertEquals(4,coordinator().runs().size)
 }
 @Test fun `자원 준비 후 원래 접수와 고정 변환으로 한 번만 실행한다`() {
  resource();ready(false);val receipt=service.receive("mes",body).receipt;val second=manual().receipt
  val c=coordinator();c.tick();assertTrue(bench.submitted.isEmpty())
  interfaces.state("asset-check",1,"ENABLED","DISABLED","대기 중 공개 중단",Actor(Mode.ENGINEER,"lee"))
  ready(true);c.tick();coordinator().tick()
  assertEquals(1,bench.submitted.size);assertEquals(receipt.jobOrderId,bench.submitted.single().first["jobOrderId"].asText())
  assertEquals("ACCEPTED",service.get(receipt.receiptId).state);assertEquals("WAITING_RESOURCE",service.get(second.receiptId).state)
  assertEquals(receipt.receiptId,c.stations().single().owner)
  val saved=service.get(receipt.receiptId).normalizedOrder
  assertEquals(saved,bench.submitted.single().first)
 }
 @Test fun `공통 자원 접수는 재시작 후에도 같은 대기열과 순서를 유지한다`() {
  resource();ready(false);val a=manual().receipt;val b=service.receive("mes",body).receipt
  coordinator().tick();coordinator().tick()
  assertEquals(a.receiptId,coordinator().stations().single().owner)
  assertEquals("QUEUED",coordinator().get(b.receiptId).state)
  assertEquals(0L,service.resources.context(a.receiptId)!!["queuedAhead"])
  assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `필수 자원 미배정은 일반 재평가 API로 우회하지 못한다`() {
  resource();ready(true);bench.judge={ids->HostCall.Ok(ids.map{JobOrderBench.missing(it,"inspect")})}
  val a=manual().receipt;coordinator().tick();val current=service.get(a.receiptId)
  assertEquals("UNASSIGNED",current.state)
  val ex=assertFailsWith<RequestRejected>{service.recover(a.receiptId,Actor(Mode.OPERATOR,"kim"),json.readTree("""{"requestId":"${UUID.randomUUID()}","reason":"우회 시도","expectedUpdatedAt":"${current.updatedAt}"}"""))}
  assertEquals("RESOURCE_MANAGED",ex.code);assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `준비 시간 초과와 대기 취소는 같은 영속 접수에 거절 사유를 남긴다`() {
  resource();ready(false);val a=manual().receipt;val b=manual().receipt;val c=coordinator();c.tick()
  c.decide(b.receiptId,Actor(Mode.OPERATOR,"kim"),json.readTree("""{"expectedRevision":1,"decision":"CANCEL_QUEUED","reason":"요청 취소"}"""))
  assertEquals("REJECTED",service.get(b.receiptId).state)
  jdbc.sql("UPDATE ops.station_run SET phase_at=clock_timestamp()-interval '60 seconds' WHERE run_id=:id").param("id",a.receiptId).update()
  c.tick();assertEquals("TIMED_OUT",c.get(a.receiptId).state);assertEquals("REJECTED",service.get(a.receiptId).state);assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `자원 요구는 운영자가 바꾸지 못하고 기존 불명 작업 중에는 등록하지 못한다`() {
  jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES('inspection-1','검사','ready','complete',30,'engineer/lee')").update()
  assertEquals(403,assertFailsWith<RequestRejected>{service.resources.create(Actor(Mode.OPERATOR,"kim"),"InspectAsset","inspection-1","등록")}.status)
  manual()
  assertEquals(409,assertFailsWith<RequestRejected>{service.resources.create(Actor(Mode.ENGINEER,"lee"),"InspectAsset","inspection-1","등록")}.status)
  assertNull(service.resources.required("InspectAsset"))
 }
 @Test fun `서로 다른 계약과 예전 배포도 같은 미션 자원 요구를 생략하지 못한다`() {
  resource()
  val view=interfaces.get("asset-check",1);assertEquals("inspection-1",view.requiredStationId)
  val payload=json.readTree("""{"requestId":"${UUID.randomUUID()}","input":${body["input"]},"expectedDeploymentVersion":1}""")
  assertEquals("WAITING_RESOURCE",service.receiveInterface(Actor(Mode.OPERATOR,"kim"),"asset-check",1,payload).receipt.state)
  assertFailsWith<IllegalArgumentException>{dev.picasso.ops.service.stations.OpsStationPort(service,interfaces,bench.host).validate(json.readTree("""{"interfaceId":"asset-check","interfaceVersion":1,"stationId":"other","expectedDeploymentVersion":1,"input":${body["input"]}}"""))}
  assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `미션 자원 요구는 변경 삭제가 금지되고 조회된다`() {
  resource();assertEquals(1,service.resources.list().size)
  assertFailsWith<org.springframework.dao.DataAccessException>{jdbc.sql("UPDATE ops.mission_resource_requirement SET station_id=station_id").update()}
  assertFailsWith<org.springframework.dao.DataAccessException>{jdbc.sql("DELETE FROM ops.mission_resource_requirement").update()}
  assertEquals(409,assertFailsWith<RequestRejected>{service.resources.create(Actor(Mode.ENGINEER,"lee"),"InspectAsset","inspection-1","중복")}.status)
 }

 @Test fun `공통 접수도 로봇 완료와 새 설비 완료를 모두 확인한 뒤 다음 요청을 허용한다`() {
  resource();ready(true);val a=manual().receipt;val b=manual().receipt;val c=coordinator();c.tick()
  jdbc.sql("""INSERT INTO ops.work_result(receipt_id,version,instance_id,execution_id,response_id,response_version,snapshot,result_hash)
   VALUES(:id,1,'mw-1','exec-1','response-1',1,'{"jobResponse":{"physicalState":"PHYSICALLY_DONE"}}','hash')""").param("id",a.receiptId).update()
  c.tick();assertEquals("RUNNING",c.get(a.receiptId).state);assertEquals(1,bench.submitted.size)
  ready(true,complete=true);c.tick();assertEquals("COMPLETED",c.get(a.receiptId).state)
  c.tick();assertEquals("WAITING_READY",c.get(b.receiptId).state);assertEquals(1,bench.submitted.size)
  ready(true);c.tick();assertEquals("RUNNING",c.get(b.receiptId).state);assertEquals(2,bench.submitted.size)
 }
 @Test fun `조정기 둘이 동시에 공통 접수를 처리해도 호스트 전송은 한 번이다`() {
  resource();ready(true);val a=manual().receipt;val b=manual().receipt
  val pool=Executors.newFixedThreadPool(2);val start=CountDownLatch(1)
  try {
   val work=(1..2).map{pool.submit {start.await();coordinator().tick()}}
   start.countDown();work.forEach{it.get(20,TimeUnit.SECONDS)}
   assertEquals(1,bench.submitted.size);assertEquals(a.receiptId,coordinator().stations().single().owner)
   assertEquals("QUEUED",coordinator().get(b.receiptId).state)
   assertTrue(coordinator().get(a.receiptId).state in setOf("RUNNING","REVIEW_REQUIRED"))
  } finally {pool.shutdownNow()}
 }

 @Test fun `접수 저장 뒤 예기치 않은 오류는 미전송 거절로 닫고 오류를 그대로 알린다`() {
  PostgresSupport.execute("ALTER TABLE ops.work_request ADD CONSTRAINT deny_normalized CHECK (normalized_order IS NULL) NOT VALID")
  assertFailsWith<org.springframework.dao.DataAccessException>{service.receive("mes",body)}
  val row=service.list("",null,0,20)["requests"].let{it as List<*>}.single() as Receipt
  assertEquals("REJECTED",row.state);assertEquals("작업 요청 처리 중 내부 오류; 호스트에 보내지 않았습니다. 새 요청 ID로 다시 보내십시오",row.detail)
  val replay=service().receive("mes",body);assertTrue(replay.duplicate);assertEquals("REJECTED",replay.receipt.state);assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `접수 저장 뒤 업무 거절은 자기 사유로 닫고 그대로 알린다`() {
  // A station run that neither owns its station nor is DISPATCHING: admission refuses with 409.
  jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES('inspection-1','공유 검사','ready','complete',30,'engineer/lee')").update()
  val run=UUID.randomUUID()
  jdbc.sql("INSERT INTO ops.station_run(run_id,station_id,payload,actor,state,reason,created_at,phase_at) VALUES(:id,'inspection-1','{}','operator/kim','QUEUED','대기',clock_timestamp(),clock_timestamp())").param("id",run).update()
  val ex=assertFailsWith<RequestRejected>{service.receiveInterface(Actor(Mode.OPERATOR,"kim"),"asset-check",1,json.readTree("""{"requestId":"${UUID.randomUUID()}","input":${body["input"]}}"""),run)}
  assertEquals(409,ex.status)
  val row=service.list("",null,0,20)["requests"].let{it as List<*>}.single() as Receipt
  assertEquals("REJECTED",row.state);assertEquals("스테이션 점유와 제출 상태를 확인할 수 없습니다",row.detail);assertEquals(ex.message,row.detail);assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `정리가 먼저 닫은 접수는 이어서 제출하거나 대기열에 넣지 않는다`() {
  // Simulates a sweep committing right after the insert ('early') or between normalization and the DISPATCHING claim.
  PostgresSupport.execute("""CREATE FUNCTION ops.test_swept() RETURNS trigger AS $$ BEGIN
   IF TG_OP='INSERT' AND NEW.source_system_id='early' OR TG_OP='UPDATE' AND NEW.source_system_id<>'early' AND OLD.normalized_order IS NULL AND NEW.normalized_order IS NOT NULL THEN NEW.state:='REJECTED';NEW.detail:='swept'; END IF;
   RETURN NEW; END $$ LANGUAGE plpgsql""")
  PostgresSupport.execute("CREATE TRIGGER test_swept BEFORE INSERT OR UPDATE ON ops.work_request FOR EACH ROW EXECUTE FUNCTION ops.test_swept()")
  val early=service.receive("early",body);assertEquals("REJECTED",early.receipt.state);assertEquals("swept",early.receipt.detail)
  val direct=service.receive("mes",body);assertEquals("REJECTED",direct.receipt.state);assertTrue(bench.submitted.isEmpty())
  resource()
  assertEquals(409,assertFailsWith<RequestRejected>{manual()}.status)
  assertEquals(0,coordinator().runs().size);assertTrue(bench.submitted.isEmpty())
  assertEquals(setOf("REJECTED"),(service.list("",null,0,20)["requests"] as List<*>).map{(it as Receipt).state}.toSet())
 }
 private fun raw(state:String,wm:String?="InspectAsset"):UUID {
  val id=UUID.randomUUID()
  jdbc.sql("INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state,normalized_order) VALUES(:id,'mes',:key,'hash','{}',:key,:state,CAST(:order AS JSONB))")
   .param("id",id).param("key",id.toString()).param("state",state).param("order",wm?.let{"""{"workMasterId":"$it"}"""},java.sql.Types.VARCHAR).update()
  return id
 }
 private fun age(id:UUID,seconds:Int) {jdbc.sql("UPDATE ops.work_request SET updated_at=clock_timestamp()-make_interval(secs=>:s) WHERE receipt_id=:id").param("s",seconds).param("id",id).update()}
 @Test fun `오래 멈춘 접수 전 단계와 제출 중 단계만 정리하고 자원 대기는 두지 않는다`() {
  val received=raw("RECEIVED",null);val dispatching=raw("DISPATCHING");val waiting=raw("WAITING_RESOURCE");val fresh=raw("RECEIVED");val freshDispatch=raw("DISPATCHING")
  for(id in listOf(received,dispatching,waiting))age(id,600)
  assertEquals(2,service.sweepStale(Duration.ofMinutes(5)))
  assertEquals("REJECTED",service.get(received).state);assertEquals("작업 요청 처리 중단; 호스트에 보내지 않았습니다. 새 요청 ID로 다시 보내십시오",service.get(received).detail)
  assertEquals("IN_DOUBT",service.get(dispatching).state);assertEquals("제출 도중 중단; 실행 관측과 현장 확인이 필요합니다",service.get(dispatching).detail)
  assertEquals("WAITING_RESOURCE",service.get(waiting).state);assertEquals("RECEIVED",service.get(fresh).state);assertEquals("DISPATCHING",service.get(freshDispatch).state)
  assertEquals(0,service.sweepStale(Duration.ofMinutes(5)));assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `결과 수집 주기는 정리 실패와 무관하게 결과를 모은다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId)
  val stale=raw("RECEIVED");age(stale,600)
  PostgresSupport.execute("ALTER TABLE ops.work_request ADD CONSTRAINT deny_sweep CHECK (detail IS NULL OR detail NOT LIKE '작업 요청 처리 중단%') NOT VALID")
  val collector=WorkResultCollector(service,300);collector.collect()
  assertEquals("OK",collector.status()["state"]);assertEquals(1,service.result(row.receiptId,"mes",1)["version"]);assertEquals("RECEIVED",service.get(stale).state)
  PostgresSupport.execute("ALTER TABLE ops.work_request DROP CONSTRAINT deny_sweep")
  collector.collect();assertEquals("REJECTED",service.get(stale).state)
 }
 private fun register(wm:String)=runCatching{service.resources.create(Actor(Mode.ENGINEER,"lee"),wm,"inspection-1","등록")}.exceptionOrNull()?.let{assertEquals(409,(it as RequestRejected).status);true} ?: false
 private fun reviewed(id:UUID,observation:String) {service.review(id,Actor(Mode.OPERATOR,"kim"),json.valueToTree(mapOf("requestId" to UUID.randomUUID().toString(),"observation" to observation,"reason" to "현장 확인","expectedUpdatedAt" to service.get(id).updatedAt)))}
 private fun settled(id:UUID,state:String,version:Int=1) {jdbc.sql("""INSERT INTO ops.work_result(receipt_id,version,instance_id,execution_id,response_id,response_version,snapshot,result_hash)
   VALUES(:id,:v,'mw-1','exec-1',:r,1,CAST(:snapshot AS JSONB),'hash')""").param("id",id).param("v",version).param("r","response-$version").param("snapshot","""{"jobResponse":{"physicalState":"$state"}}""").update()}
 /** Each case uses its own mission so a successful registration never leaks into the next one. */
 private fun blocks(state:String,setup:(UUID)->Unit={}):Boolean {val wm="M-${UUID.randomUUID()}";val id=raw(state,wm);setup(id);val blocked=register(wm);jdbc.sql("UPDATE ops.work_request SET state='REJECTED' WHERE receipt_id=:id").param("id",id).update();return blocked}
 @Test fun `자원 요구 등록은 이 미션에서 아직 진행 중이거나 완료 근거가 없는 접수만 막는다`() {
  jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES('inspection-1','검사','ready','complete',30,'engineer/lee')").update()
  val other=raw("RECEIVED","OtherMission");assertFalse(register("InspectAsset"));jdbc.sql("UPDATE ops.work_request SET state='REJECTED' WHERE receipt_id=:id").param("id",other).update()
  for(state in listOf("RECEIVED","WAITING_RESOURCE","DISPATCHING","IN_DOUBT","ACCEPTED"))assertTrue(blocks(state),state)
  val unknown=raw("RECEIVED",null);assertTrue(register("M-${UUID.randomUUID()}"));jdbc.sql("UPDATE ops.work_request SET state='REJECTED' WHERE receipt_id=:id").param("id",unknown).update()
  for(state in listOf("UNASSIGNED","REJECTED"))assertFalse(blocks(state),state)
  for(result in listOf("PHYSICALLY_DONE","UNVERIFIED","FAILED","ABORTED","PARTIAL"))assertFalse(blocks("ACCEPTED"){settled(it,result)},result)
  assertTrue(blocks("ACCEPTED"){settled(it,"FAILED");settled(it,"RUNNING",2)})
  assertTrue(blocks("ACCEPTED"){settled(it,"OPERATOR_HOLD")})
  assertFalse(blocks("IN_DOUBT"){reviewed(it,"NOT_STARTED")});assertFalse(blocks("IN_DOUBT"){reviewed(it,"COMPLETED")});assertFalse(blocks("ACCEPTED"){reviewed(it,"COMPLETED")})
  assertTrue(blocks("IN_DOUBT"){reviewed(it,"NOT_CONFIRMED")});assertTrue(blocks("IN_DOUBT"){reviewed(it,"IN_PROGRESS")})
  assertTrue(blocks("IN_DOUBT"){reviewed(it,"NOT_STARTED");reviewed(it,"NOT_CONFIRMED")})
  assertTrue(blocks("IN_DOUBT"){reviewed(it,"NOT_STARTED");jdbc.sql("UPDATE ops.work_request SET updated_at=clock_timestamp() WHERE receipt_id=:id").param("id",it).update()})
 }
 @Test fun `등록 전 미배정 접수는 등록 뒤 일반 재평가로 자원 요구를 우회하지 못한다`() {
  bench.judge={ids->HostCall.Ok(ids.map{id->JobOrderBench.running(id,"busy")})}
  val row=manual().receipt;assertEquals("UNASSIGNED",row.state)
  resource();bench.judge={ids->HostCall.Ok(ids.map(JobOrderBench::pass))}
  val ex=assertFailsWith<RequestRejected>{service.recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),json.readTree("""{"requestId":"${UUID.randomUUID()}","reason":"등록 뒤 재평가","expectedUpdatedAt":"${row.updatedAt}"}"""))}
  assertEquals("RESOURCE_REQUIREMENT",ex.code);assertEquals("UNASSIGNED",service.get(row.receiptId).state);assertEquals(row.updatedAt,service.get(row.receiptId).updatedAt);assertTrue(bench.submitted.isEmpty())
 }

}
