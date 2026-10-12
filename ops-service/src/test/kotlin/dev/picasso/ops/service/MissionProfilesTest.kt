package dev.picasso.ops.service
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.missions.MissionProfiles
import dev.picasso.ops.service.interfaces.*
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.*
class MissionProfilesTest {
 private val ds=DriverManagerDataSource(PostgresSupport.jdbcUrl,PostgresSupport.username,PostgresSupport.password)
 private val jdbc=JdbcClient.create(ds);private val json=ObjectMapper();private val profiles=MissionProfiles(jdbc)
 private val actor=Actor(Mode.ENGINEER,"lee")
 private fun profile()=json.readTree(javaClass.getResourceAsStream("/mission-profile-inspection.json"))
 @BeforeTest fun fresh(){OpsSchema.flyway(ds,cleanable=true).apply{clean();migrate()}}
 @Test fun `같은 입력 계약 재확인은 같은 미션이며 변경과 덮어쓰기는 차단한다`() {
  val p=profile();assertEquals(p,profiles.create(p,actor));assertEquals(p,MissionProfiles(jdbc).create(p,actor));assertEquals(1,profiles.list().size)
  (p as com.fasterxml.jackson.databind.node.ObjectNode).put("name","changed")
  assertEquals(409,assertFailsWith<InterfaceRejected>{profiles.create(p,actor)}.status)
  assertFails{jdbc.sql("UPDATE ops.mission_profile SET created_by='other'").update()}
 }
 @Test fun `새 업무의 샘플은 코드 업무 분기 없이 설비 입력으로 변환된다`() {
  val p=profile();profiles.create(p,actor);val draft=profiles.read(p,p["sample"])
  val order=JobOrderForm.jobOrder(draft,"NEW-JOB",json)
  assertEquals("ManufacturingInspection",order["workMasterId"].asText());assertEquals("E0",order["requiredEvidence"].asText())
  assertEquals("mission_target",order["equipmentRequirements"][0]["equipmentUse"].asText())
  assertEquals("bay-7",order["equipmentRequirements"][0]["properties"]["location"].asText())
 }
 @Test fun `필수 입력 자료형 추가 필드 중복 설비 ID를 제출 전에 막는다`() {
  val p=profile()
  for(input in listOf("{}","{\"targets\":[]}","{\"targets\":[{\"id\":1,\"location\":\"bay-7\"}]}","{\"targets\":[{\"id\":\"T1\",\"location\":\"bay-7\",\"extra\":true}]}","{\"targets\":[{\"id\":\"T1\",\"location\":\"a\"},{\"id\":\"T1\",\"location\":\"b\"}]}")) assertFailsWith<InterfaceRejected>{profiles.read(p,json.readTree(input))}
 }
 @Test fun `새 미션 호출 계약은 숫자 배포 전 공개되지 않으며 입력은 불변 스키마로 검증한다`() {
  val p=profile();profiles.create(p,actor)
  val interfaces=MissionInterfaces(jdbc,TransactionTemplate(DataSourceTransactionManager(ds)),OperationLog(jdbc))
  val spec=interfaces.spec(json.readTree("""{"interfaceId":"manufacturing-inspect","version":1,"name":"공정 점검","workMasterId":"ManufacturingInspection","mapping":{"targets":"/assets"},"sample":{"assets":[{"id":"T1","location":"bay-7"}]}}"""))
  val row=interfaces.create(spec,actor);assertNull(row.deployment)
  assertEquals("DEPLOYMENT_REQUIRED",assertFailsWith<InterfaceRejected>{interfaces.state(row.interfaceId,1,"DRAFT","ENABLED","공개",actor)}.code)
  interfaces.deploy(row.interfaceId,1,"1",0,"검증 버전",actor);interfaces.state(row.interfaceId,1,"DRAFT","ENABLED","공개",actor)
  val draft=interfaces.draft(row.interfaceId,1,spec.sample,true)
  assertEquals("1",JobOrderForm.jobOrder(draft,"JOB",json)["missionVersion"].asText())
  assertFailsWith<InterfaceRejected>{interfaces.draft(row.interfaceId,1,json.readTree("""{"assets":[{"id":"T1"}]}"""),true)}
 }
 @Test fun `미지원 스키마나 실행 ID 불일치는 등록하지 않는다`() {
  for(p in listOf(profile().apply{(get("inputSchema") as com.fasterxml.jackson.databind.node.ObjectNode).put("pattern",".*")},profile().apply{(get("definition") as com.fasterxml.jackson.databind.node.ObjectNode).put("workMasterId","Other")}))assertFailsWith<InterfaceRejected>{profiles.create(p,actor)}
  assertTrue(profiles.list().isEmpty())
 }
}
