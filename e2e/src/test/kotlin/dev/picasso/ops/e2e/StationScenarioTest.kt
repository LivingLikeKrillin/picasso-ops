package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.test.*

class StationScenarioTest {
 companion object {
  private lateinit var stack:E2eStack
  private lateinit var driver:ExecutionDriver
  private val json=ObjectMapper()
  @BeforeAll @JvmStatic fun up(){stack=E2eStack.start();driver=ExecutionDriver(stack);Commissioned.complete(stack)}
  @AfterAll @JvmStatic fun down(){if(::stack.isInitialized)stack.close()}
 }
 @Test fun `shared station serializes two real mission calls and requires fresh equipment completion`() {
  val profile=json.readTree(Files.readString(E2eStack.root.resolve("docs/examples/mission-profile-station-inspection.json")))
  post("/api/mission-profiles",profile.toString())
  val wm="StationInspection"
  val draft=post("/api/missions/$wm/drafts",json.createObjectNode().put("definition",profile["definition"].toString()).toString())["outcome"]["draft"]["draftId"].asLong()
  for(action in listOf("validate","mock-run"))assertEquals("PASSED",post("/api/missions/$wm/drafts/$draft/$action","{}")["outcome"]["result"].asText())
  post("/api/missions/$wm/drafts/$draft/activate","""{"reason":"공유 스테이션 검증"}""")
  val created=post("/api/mission-interfaces","""{"interfaceId":"station-check","version":1,"name":"스테이션 검사","workMasterId":"StationInspection","mapping":{"targets":"/assets"},"sample":{"assets":[{"id":"T1","location":"bay-7"}]}}""")
  val deployed=post("/api/mission-interfaces/station-check/1/deploy","""{"missionVersion":"1","expectedDeploymentVersion":${created["deployment"]?.get("deploymentVersion")?.asInt() ?: 0},"reason":"검사 버전 고정"}""")
  post("/api/mission-interfaces/station-check/1/state","""{"expectedStatus":"DRAFT","status":"ENABLED","reason":"공개"}""")
  signal("inspection_ready","false");signal("inspection_complete","false")
  post("/api/stations","""{"stationId":"inspection-1","name":"공유 검사","readySignal":"inspection_ready","completeSignal":"inspection_complete","timeoutSeconds":120}""")
  val version=deployed["deployment"]["deploymentVersion"].asInt()
  fun enqueue():String {val id=UUID.randomUUID().toString();post("/api/stations/runs","""{"requestId":"$id","stationId":"inspection-1","interfaceId":"station-check","interfaceVersion":1,"expectedDeploymentVersion":$version,"input":{"assets":[{"id":"T1","location":"bay-7"}]}}""","operator");return id}
  val a=enqueue();waitFor(a,"WAITING_READY");val b=enqueue()
  assertEquals("QUEUED",read(b)["state"].asText());assertTrue(read(a)["receiptId"].isNull)
  signal("inspection_ready","true")
  val first=waitFor(a,"RUNNING");assertEquals("QUEUED",read(b)["state"].asText())
  finish(first)
  Thread.sleep(2500);assertEquals("RUNNING",read(a)["state"].asText())
  signal("inspection_complete","true");waitFor(a,"COMPLETED")
  waitFor(b,"WAITING_READY");assertTrue(read(b)["receiptId"].isNull)
  signal("inspection_complete","false");val second=waitFor(b,"RUNNING")
  finish(second);signal("inspection_complete","true");waitFor(b,"COMPLETED")
  val detail=stack.get("/api/stations/runs/$b/evidence")
  assertEquals("ops.station.evidence/1",detail["schema"].asText());assertEquals(false,detail["explanationMayChangeState"].asBoolean());assertTrue(detail["events"].any{it["state"].asText()=="DISPATCHING"})
  assertEquals(2,stack.get("/api/stations")["runs"].size())
  val file=E2eStack.root.resolve("build/reports/station-orchestration.json");Files.createDirectories(file.parent);Files.writeString(file,json.writerWithDefaultPrettyPrinter().writeValueAsString(detail))
 }
 private fun finish(run:JsonNode){val receipt=stack.get("/api/work-requests/${run["receiptId"].asText()}")["receipt"];val execution=receipt["submission"]["outcome"]["executionId"].asText();assertEquals("PHYSICALLY_DONE",driver.drive(execution)["physicalState"].asText());driver.push(Duration.ofSeconds(1))}
 private fun read(id:String)=stack.get("/api/stations/runs/$id")["run"]
 private fun waitFor(id:String,state:String):JsonNode{repeat(100){val run=read(id);if(run["state"].asText()==state)return run;assertNotEquals("REVIEW_REQUIRED",run["state"].asText(),run.toString());Thread.sleep(200)};error("$state 대기 실패: ${read(id)}")}
 private fun signal(name:String,value:String){post("/api/cell/signals/$name","""{"value":"$value"}""","operator");driver.push(Duration.ofSeconds(1))}
 private fun post(path:String,body:String,mode:String="engineer"):JsonNode{val reply=stack.send("POST",path,mode,body=body);assertEquals(200,reply.status,"$path: ${reply.body}");return reply.body!!}
}
