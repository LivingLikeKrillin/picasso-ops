package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * 해제한 이전 작업의 늦은 설비 완료 신호(외부 검토 실험 A1)를 실제 스택(mimic·셀 대역·실행 호스트·운영 서비스)에서 막는다.
 *
 * X 를 보낸 뒤 로봇이 끝나지 않아 실행 시간 초과 → 확인 필요 → 현장 확인 해제. 해제는 X 의 실행을 멈추지 않는다.
 * 작업 번호 반환 스테이션은 X 쪽 설비의 늦은 완료를 반환 번호로 가려 Y 를 확인 필요로 넘기고, 반환이 없는 스테이션은
 * 격리해 현장 확인 후 격리 해제 전에는 Y 를 보내지 않는다. 시험이 설비 역할로 반환 번호와 완료 신호를 쓴다.
 */
class StationLateCompletionTest {
 private val json=ObjectMapper()
 private lateinit var stack:E2eStack
 private lateinit var driver:ExecutionDriver
 private var version=0
 private fun up(station:String) {
  stack=E2eStack.start();driver=ExecutionDriver(stack);Commissioned.complete(stack)
  val profile=json.readTree(Files.readString(E2eStack.root.resolve("docs/examples/mission-profile-station-inspection.json")))
  post("/api/mission-profiles",profile.toString())
  val wm="StationInspection"
  val draft=post("/api/missions/$wm/drafts",json.createObjectNode().put("definition",profile["definition"].toString()).toString())["outcome"]["draft"]["draftId"].asLong()
  for(action in listOf("validate","mock-run"))assertEquals("PASSED",post("/api/missions/$wm/drafts/$draft/$action","{}")["outcome"]["result"].asText())
  post("/api/missions/$wm/drafts/$draft/activate","""{"reason":"늦은 완료 신호 회귀"}""")
  val created=post("/api/mission-interfaces","""{"interfaceId":"station-check","version":1,"name":"스테이션 검사","workMasterId":"StationInspection","mapping":{"targets":"/assets"},"sample":{"assets":[{"id":"T1","location":"bay-7"}]}}""")
  val deployed=post("/api/mission-interfaces/station-check/1/deploy","""{"missionVersion":"1","expectedDeploymentVersion":${created["deployment"]?.get("deploymentVersion")?.asInt() ?: 0},"reason":"검사 버전 고정"}""")
  post("/api/mission-interfaces/station-check/1/state","""{"expectedStatus":"DRAFT","status":"ENABLED","reason":"공개"}""")
  version=deployed["deployment"]["deploymentVersion"].asInt()
  signal("inspection_ready","true");signal("inspection_complete","false")
  post("/api/stations",station)
 }
 private fun enqueue():String {val id=UUID.randomUUID().toString();post("/api/stations/runs","""{"requestId":"$id","stationId":"inspection-1","interfaceId":"station-check","interfaceVersion":1,"expectedDeploymentVersion":$version,"input":{"assets":[{"id":"T1","location":"bay-7"}]}}""","operator");return id}
 /** X: 보내고 로봇을 끝내지 않는다(가상 시계를 밀지 않음). 실행 시간 초과로 확인 필요가 되면 현장 확인으로 해제한다. */
 private fun clearedX():Pair<String,String> {
  val x=enqueue();val xExecution=execution(waitFor(x,"RUNNING"))
  waitFor(x,"REVIEW_REQUIRED",Duration.ofSeconds(TIMEOUT_SECONDS+15L))
  decide(x,"CONFIRM_CLEAR","로봇이 멈춘 것으로 보고 현장 확인 해제");assertEquals("MANUALLY_CLEARED",read(x)["state"].asText())
  return x to xExecution
 }
 @Test fun `작업 번호 반환 스테이션은 해제한 이전 작업의 늦은 완료 신호를 실제 스택에서 다음 작업의 완료로 채택하지 않는다`() {
  up("""{"stationId":"inspection-1","name":"번호 검사","readySignal":"inspection_ready","completeSignal":"inspection_complete","timeoutSeconds":$TIMEOUT_SECONDS,"jobSignal":"inspection_job","jobEchoSignal":"inspection_job_done"}""")
  try {
   val (x,xExecution)=clearedX();assertTrue(station()["quarantinedBy"].isNull)
   assertEquals(x,dispatching(x)["jobToken"].asText())
   // Y: 준비 true, 완료 false 라 번호를 쓰고 바로 보낸다. X 의 기체는 아직 실행 중이라 다른 기체로 간다.
   val y=enqueue();val yRun=waitFor(y,"RUNNING");val yExecution=execution(yRun);assertNotEquals(xExecution,yExecution)
   assertEquals(y,cellAfterPump("inspection_job",y));assertEquals(y,dispatching(y)["jobToken"].asText())
   // X 쪽 설비가 늦게 X 의 번호와 완료를 쓴다. Y 의 로봇은 아직 끝나지 않았다.
   signal("inspection_job_done",x);signal("inspection_complete","true")
   val review=waitFor(y,"REVIEW_REQUIRED");assertEquals("설비 완료 신호 수신, 작업 번호 불일치; 현장 확인이 필요합니다",review["reason"].asText())
   assertEquals(y,station()["owner"].asText())
   val seen=stack.get("/api/stations/runs/$y/evidence")["events"].last()["context"]
   assertEquals(y,seen["expected"].asText());assertEquals(x,seen["echoed"].asText());assertEquals(true,seen["signals"]["inspection_complete"]["value"].asBoolean())
   // 현장 확인: 신호를 내리고 관측을 재개한다. Y 의 설비가 Y 의 번호와 완료를 쓰면 완료다.
   signal("inspection_complete","false");decide(y,"RESUME_OBSERVATION","이전 작업 신호 확인 후 관측 재개");waitFor(y,"RUNNING")
   assertEquals("PHYSICALLY_DONE",driver.drive(yExecution)["physicalState"].asText());driver.push(Duration.ofSeconds(1))
   Thread.sleep(2500);assertEquals("RUNNING",read(y)["state"].asText())
   signal("inspection_job_done",y);signal("inspection_complete","true")
   val done=waitFor(y,"COMPLETED");assertEquals("로봇 물리 완료와 이번 작업의 설비 완료 신호 확인; 자원 해제",done["reason"].asText())
   report("station-late-completion-echo.json",x,y)
  } finally {stack.close()}
 }
 @Test fun `작업 번호 반환이 없는 스테이션은 보낸 작업을 해제하면 격리하고 현장 확인 후 격리 해제 전에는 실제 스택에서 다음 작업을 보내지 않는다`() {
  up("""{"stationId":"inspection-1","name":"공유 검사","readySignal":"inspection_ready","completeSignal":"inspection_complete","timeoutSeconds":$TIMEOUT_SECONDS}""")
  try {
   val (x,_)=clearedX();assertEquals(x,station()["quarantinedBy"].asText())
   val events=stack.get("/api/stations/runs/$x/evidence")["events"].toList().takeLast(2)
   assertEquals(listOf("현장 확인에 의한 자원 해제: 로봇이 멈춘 것으로 보고 현장 확인 해제","자원 해제에 따른 스테이션 격리"),events.map{it["reason"].asText()})
   assertEquals(false,events[0]["context"]["signals"]["inspection_complete"]["value"].asBoolean());assertTrue(events[0]["context"].has("physicalState"))
   // Y: 준비 true, 완료 false 여도 격리 중이라 보내지 않는다.
   val y=enqueue();waitFor(y,"WAITING_READY")
   Thread.sleep(2500);val waiting=read(y);assertEquals("WAITING_READY",waiting["state"].asText());assertEquals("스테이션 격리; 현장 확인과 격리 해제 판단을 기다립니다",waiting["reason"].asText());assertTrue(waiting["receiptId"].isNull)
   // X 쪽 설비가 늦게 완료를 쓴다. 완료 신호가 켜져 있으면 격리를 풀지 않는다.
   signal("inspection_complete","true")
   assertEquals(409,stack.send("POST","/api/stations/runs/$y/decision","operator",body=decision(y,"CONFIRM_STATION_IDLE","현장 확인")).status)
   signal("inspection_complete","false");decide(y,"CONFIRM_STATION_IDLE","설비 비어 있음과 이전 로봇 정지 확인")
   assertTrue(station()["quarantinedBy"].isNull)
   val yExecution=execution(waitFor(y,"RUNNING"))
   assertEquals("PHYSICALLY_DONE",driver.drive(yExecution)["physicalState"].asText());driver.push(Duration.ofSeconds(1))
   signal("inspection_complete","true")
   val done=waitFor(y,"COMPLETED");assertEquals("로봇 물리 완료와 이번 작업의 설비 완료 신호 확인; 자원 해제",done["reason"].asText())
   assertTrue(stack.get("/api/stations/runs/$y/evidence")["events"].any{it["reason"].asText()=="현장 확인 후 격리 해제: 설비 비어 있음과 이전 로봇 정지 확인"})
   report("station-late-completion-quarantine.json",x,y)
  } finally {stack.close()}
 }
 private fun report(name:String,x:String,y:String) {
  val report=json.createObjectNode().apply {set<JsonNode>("station",station());put("siteNow",stack.site.now().toString());put("wallNow",Instant.now().toString());set<JsonNode>("x",stack.get("/api/stations/runs/$x/evidence"));set<JsonNode>("y",stack.get("/api/stations/runs/$y/evidence"))}
  val file=E2eStack.root.resolve("build/reports/$name");Files.createDirectories(file.parent);Files.writeString(file,json.writerWithDefaultPrettyPrinter().writeValueAsString(report))
 }
 private fun station()=stack.get("/api/stations")["stations"].single{it["stationId"].asText()=="inspection-1"}
 private fun dispatching(id:String)=stack.get("/api/stations/runs/$id/evidence")["events"].last{it["state"].asText()=="DISPATCHING"}["context"]
 private fun cell(name:String)=stack.get("/api/cell")["cell"]["signals"].single{it["name"].asText()==name}["value"].asText()
 /** The host serves the cell band as of its last pump, so a value written by the station shows after a later pump. */
 private fun cellAfterPump(name:String,expected:String):String {val until=Instant.now().plusSeconds(5);while(Instant.now().isBefore(until)){val value=cell(name);if(value==expected)return value;Thread.sleep(100)};return cell(name)}
 private fun execution(run:JsonNode)=stack.get("/api/work-requests/${run["receiptId"].asText()}")["receipt"]["submission"]["outcome"]["executionId"].asText()
 private fun read(id:String)=stack.get("/api/stations/runs/$id")["run"]
 private fun decision(id:String,decision:String,reason:String)="""{"expectedRevision":${read(id)["revision"].asInt()},"decision":"$decision","reason":"$reason"}"""
 private fun decide(id:String,decision:String,reason:String)=post("/api/stations/runs/$id/decision",decision(id,decision,reason),"operator")
 private fun waitFor(id:String,state:String,limit:Duration=Duration.ofSeconds(30)):JsonNode {
  val until=Instant.now().plus(limit)
  while(Instant.now().isBefore(until)){val run=read(id);if(run["state"].asText()==state)return run;if(state!="REVIEW_REQUIRED")assertNotEquals("REVIEW_REQUIRED",run["state"].asText(),run.toString());Thread.sleep(200)}
  error("$state 대기 실패: ${read(id)}")
 }
 private fun signal(name:String,value:String){post("/api/cell/signals/$name","""{"value":"$value"}""","operator");driver.push(Duration.ofSeconds(1))}
 private fun post(path:String,body:String,mode:String="engineer"):JsonNode{val reply=stack.send("POST",path,mode,body=body);assertEquals(200,reply.status,"$path: ${reply.body}");return reply.body!!}
 private companion object {const val TIMEOUT_SECONDS=20}
}
