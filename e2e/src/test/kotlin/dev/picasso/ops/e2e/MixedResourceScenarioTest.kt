package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.nio.file.Files
import kotlin.test.*

class MixedResourceScenarioTest {
 companion object {
  private lateinit var stack:E2eStack
  private lateinit var driver:ExecutionDriver
  private val json=ObjectMapper()
  @BeforeAll @JvmStatic fun up(){stack=E2eStack.start(integrationToken="resource-test-token");driver=ExecutionDriver(stack);Commissioned.complete(stack)}
  @AfterAll @JvmStatic fun down(){if(::stack.isInitialized)stack.close()}
 }
 @Test fun `MES operator contract manual and legacy submissions share the same required resource and stable receipts`() {
  val input="""{"assets":[{"id":"T1","location":"bay-7"}]}"""
  post("/api/mission-interfaces","""{"interfaceId":"resource-check","version":1,"name":"공유 검사 호출","workMasterId":"InspectAsset","mapping":{"targets":"/assets"},"sample":$input}""")
  post("/api/mission-interfaces/resource-check/1/state","""{"expectedStatus":"DRAFT","status":"ENABLED","reason":"통합 검증"}""")
  signal("inspection_ready","false");signal("inspection_complete","false")
  post("/api/stations","""{"stationId":"inspection-1","name":"필수 검사 자원","readySignal":"inspection_ready","completeSignal":"inspection_complete","timeoutSeconds":120}""")
  post("/api/mission-resources","""{"workMasterId":"InspectAsset","stationId":"inspection-1","reason":"모든 검사 경로의 자원 조정"}""")
  val mesPayload="""{"externalOrderId":"MES-1","interfaceId":"resource-check","interfaceVersion":1,"input":$input}"""
  fun mes():JsonNode {
   val req=HttpRequest.newBuilder(URI.create("${stack.opsUrl}/integration/v1/job-orders")).header("Authorization","Bearer resource-test-token").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mesPayload)).build()
   val reply=HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.ofString());assertTrue(reply.statusCode() in setOf(200,202),reply.body());return json.readTree(reply.body())
  }
  val first=mes()["receipt"];assertEquals("WAITING_RESOURCE",first["state"].asText())
  val invoke=post("/api/mission-interfaces/resource-check/1/invoke","""{"requestId":"${UUID.randomUUID()}","expectedDeploymentVersion":1,"input":$input}""","operator",202)["receipt"]
  val manual=post("/api/work-requests/manual","""{"requestId":"${UUID.randomUUID()}","form":{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"}]}}""","operator",202)["receipt"]
  val legacy=post("/api/job-orders","""{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"}]}""","operator",202)["receipt"]
  val receipts=listOf(first,invoke,manual,legacy)
  assertEquals(first["receiptId"],mes()["receipt"]["receiptId"])
  assertEquals(4,stack.get("/api/stations")["runs"].size())
  for(r in receipts){assertEquals("inspection-1",r["requiredStationId"].asText());assertTrue(r["submission"].isNull)}
  waitFor(first["receiptId"].asText(),"WAITING_READY")
  signal("inspection_ready","true")
  for((index,r) in receipts.withIndex()) {
   val id=r["receiptId"].asText();waitFor(id,"RUNNING")
   val current=stack.get("/api/work-requests/$id")
   assertEquals(id,current["resource"]["runId"].asText())
   assertEquals(r["jobOrderId"],current["receipt"]["jobOrderId"])
   val execution=current["receipt"]["submission"]["outcome"]["executionId"].asText()
   assertEquals("PHYSICALLY_DONE",driver.drive(execution)["physicalState"].asText());driver.push(Duration.ofSeconds(1))
   Thread.sleep(2200);assertEquals("RUNNING",read(id)["state"].asText())
   signal("inspection_complete","true");waitFor(id,"COMPLETED")
   if(index<receipts.lastIndex) {
    val next=receipts[index+1]["receiptId"].asText();waitFor(next,"WAITING_READY")
    assertEquals("WAITING_RESOURCE",stack.get("/api/work-requests/$next")["receipt"]["state"].asText())
    signal("inspection_complete","false")
   }
  }
  assertTrue(stack.get("/api/stations")["stations"][0]["owner"].isNull)
  val report=stack.get("/api/stations/runs/${legacy["receiptId"].asText()}/evidence")
  val path=E2eStack.root.resolve("build/reports/mixed-resource-orchestration.json");Files.createDirectories(path.parent);Files.writeString(path,json.writerWithDefaultPrettyPrinter().writeValueAsString(report))
 }
 private fun read(id:String)=stack.get("/api/stations/runs/$id")["run"]
 private fun waitFor(id:String,state:String){repeat(150){val r=read(id);if(r["state"].asText()==state)return;assertNotEquals("REVIEW_REQUIRED",r["state"].asText(),r.toString());Thread.sleep(200)};error("$state 대기 실패: ${read(id)}")}
 private fun signal(name:String,value:String){post("/api/cell/signals/$name","""{"value":"$value"}""","operator");driver.push(Duration.ofSeconds(1))}
 private fun post(path:String,body:String,mode:String="engineer",status:Int=200):JsonNode{val reply=stack.send("POST",path,mode,body=body);assertEquals(status,reply.status,"$path: ${reply.body}");return reply.body!!}
}
