package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.interfaces.InterfaceMapping
import dev.picasso.ops.service.interfaces.InterfaceRejected
import dev.picasso.ops.service.interfaces.MissionInterfaces
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.web.MissionInterfaceController
import dev.picasso.registry.PostgresSupport
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Duration
import kotlin.test.*

class MissionInterfacesTest {
    private val ds = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val jdbc = JdbcClient.create(ds)
    private val log = OperationLog(jdbc)
    private fun store() = MissionInterfaces(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)), log)
    private val service = store()
    private val actor = Actor(Mode.ENGINEER, "lee")
    private val json = ObjectMapper()
    private val input = json.readTree("""{"assets":[{"id":"T1","location":"bay-7"}]}""")
    private val spec = InterfaceMapping.spec(json.readTree("""{"interfaceId":"asset-check","version":1,"name":"자산 점검","workMasterId":"InspectAsset","mapping":{"targets":"/assets"},"sample":{"assets":[{"id":"T1","location":"bay-7"}]}}"""))
    private fun envelope(id:String=java.util.UUID.randomUUID().toString()) = """{"requestId":"$id","input":$input}"""
    private fun controller(bench:JobOrderBench)=MissionInterfaceController(service,dev.picasso.ops.service.requests.WorkRequests(
        jdbc,service,JobOrderOperations(bench.eligibility,bench.host,bench.host,log,bench.clock,requeryDelay=Duration.ZERO),bench.host,
        TransactionTemplate(DataSourceTransactionManager(ds))), eligibility=bench.eligibility)
    @BeforeTest fun fresh() { OpsSchema.flyway(ds, cleanable=true).apply { clean(); migrate() } }
    @Test fun `새 저장소에서도 계약 정의와 상태가 복원된다`() {
        service.create(spec, actor)
        service.state("asset-check", 1, "DRAFT", "ENABLED", "검증됨", actor)
        assertEquals("ENABLED", store().get("asset-check", 1).status)
        assertEquals(spec.mapping, store().get("asset-check", 1).mapping)
        assertEquals(2, log.list().size)
    }
    @Test fun `같은 계약 버전은 중복 생성할 수 없고 새 버전은 독립적이다`() {
        service.create(spec, actor)
        assertEquals(409, assertFailsWith<InterfaceRejected> { service.create(spec, actor) }.status)
        service.create(spec.copy(version=2), actor)
        assertEquals(2, service.list().size)
        assertEquals(2, log.list().size)
    }
    @Test fun `오래된 상태로 공개를 변경하지 못한다`() {
        service.create(spec, actor)
        service.state("asset-check", 1, "DRAFT", "ENABLED", "공개", actor)
        assertEquals(409, assertFailsWith<InterfaceRejected> { service.state("asset-check", 1, "DRAFT", "ENABLED", "오래된 요청", actor) }.status)
        service.state("asset-check", 1, "ENABLED", "DISABLED", "중지", actor)
        assertEquals("DISABLED", store().get("asset-check", 1).status)
        assertFailsWith<InterfaceRejected> { service.draft("asset-check", 1, input, true) }
    }
    @Test fun `DB가 저장된 매핑 정의 덮어쓰기를 막는다`() {
        service.create(spec, actor)
        assertFailsWith<SQLException> { PostgresSupport.execute("UPDATE ops.mission_interface SET mapping='{}'::jsonb") }
    }
    @Test fun `감사 기록 실패 시 계약 저장도 롤백한다`() {
        PostgresSupport.execute("ALTER TABLE ops.operation_log ADD CONSTRAINT reject_interface CHECK (target NOT LIKE 'interface:%') NOT VALID")
        assertFailsWith<DataIntegrityViolationException> { service.create(spec, actor) }
        assertEquals(emptyList(), service.list())
    }
    @Test fun `감사 기록 실패 시 공개 상태 변경도 롤백한다`() {
        service.create(spec, actor)
        PostgresSupport.execute("ALTER TABLE ops.operation_log ADD CONSTRAINT reject_interface CHECK (target NOT LIKE 'interface:%') NOT VALID")
        assertFailsWith<DataIntegrityViolationException> { service.state("asset-check", 1, "DRAFT", "ENABLED", "공개", actor) }
        assertEquals("DRAFT", store().get("asset-check", 1).status)
    }
    @Test fun `공개 계약 호출은 기존 작업 제출과 연결되고 매핑 버전과 원본을 감사 기록에 남긴다`() {
        service.create(spec, actor); service.state("asset-check", 1, "DRAFT", "ENABLED", "공개", actor)
        val bench = JobOrderBench()
        val controller = controller(bench)
        assertEquals(403, controller.invoke("asset-check", 1, "engineer", "lee", envelope().toByteArray()).statusCode.value())
        assertEquals(0, bench.submitted.size)
        val reply = controller.invoke("asset-check", 1, "operator", "kim", envelope().toByteArray())
        assertEquals(202, reply.statusCode.value())
        assertEquals(1, bench.submitted.size)
        val request = json.readTree(log.list().first { it.target.startsWith("JO-") }.request)
        assertEquals("asset-check/v1", request["interfaceRef"].asText())
        assertEquals(input, request["interfaceInput"])
        service.state("asset-check", 1, "ENABLED", "DISABLED", "중지", actor)
        val refused=controller.invoke("asset-check", 1, "operator", "kim", envelope().toByteArray())
        assertEquals(202,refused.statusCode.value())
        assertEquals("REJECTED",(refused.body as dev.picasso.ops.service.requests.ReceiptAccepted).receipt.state)
        assertEquals(1, bench.submitted.size)
    }
    @Test fun `등록은 엔지니어만 가능하며 미공개 계약은 호출할 수 없다`() {
        val bench = JobOrderBench()
        val controller = controller(bench)
        assertEquals(403, controller.create("operator", "kim", "broken".toByteArray()).statusCode.value())
        assertEquals(400, controller.create(null, null, null).statusCode.value())
        service.create(spec, actor)
        val refused=controller.invoke("asset-check", 1, "operator", "kim", envelope().toByteArray())
        assertEquals(202,refused.statusCode.value())
        assertEquals("REJECTED",(refused.body as dev.picasso.ops.service.requests.ReceiptAccepted).receipt.state)
        assertEquals(0, bench.submitted.size)
        assertEquals(400, controller.preview(ByteArray(65537)).statusCode.value())
    }
    @Test fun `계약 호출은 중지 뒤에도 동일 요청을 조회하고 다른 입력과 요청자는 충돌한다`() {
        service.create(spec,actor);service.state("asset-check",1,"DRAFT","ENABLED","공개",actor)
        val bench=JobOrderBench();val api=controller(bench);val body=envelope()
        val first=api.invoke("asset-check",1,"operator","kim",body.toByteArray()).body as dev.picasso.ops.service.requests.ReceiptAccepted
        assertEquals("ops:interface",first.receipt.sourceSystemId)
        assertEquals("operator/kim",first.receipt.payload["requestedBy"].asText())
        service.state("asset-check",1,"ENABLED","DISABLED","중지",actor)
        val repeat=controller(bench).invoke("asset-check",1,"operator","kim",body.toByteArray())
        assertEquals(200,repeat.statusCode.value())
        assertEquals(first.receipt.receiptId,(repeat.body as dev.picasso.ops.service.requests.ReceiptAccepted).receipt.receiptId)
        assertEquals(409,api.invoke("asset-check",1,"operator","lee",body.toByteArray()).statusCode.value())
        assertEquals(409,api.invoke("asset-check",1,"operator","kim",body.replace("bay-7","bay-8").toByteArray()).statusCode.value())
        assertEquals(1,bench.submitted.size)
        assertEquals(400,api.invoke("asset-check",1,"operator","kim",input.toString().toByteArray()).statusCode.value())
    }

    @Test fun `배포 이력은 불변이고 새 배포는 새 요청에만 적용된다`() {
        service.create(spec,actor);service.state("asset-check",1,"DRAFT","ENABLED","공개",actor)
        val before=service.draft("asset-check",1,input,true)
        val first=dev.picasso.ops.service.joborders.JobOrderForm.jobOrder(before,"JO-1",json)
        assertEquals("CODE",first["missionVersion"].asText());assertEquals(1,first["deployment"]["deploymentVersion"].asInt())
        val deployed=service.deploy("asset-check",1,"7",1,"검증한 미션 v7 배포",actor)
        assertEquals(2,deployed.deployment!!["deploymentVersion"].asInt())
        assertEquals("CODE",dev.picasso.ops.service.joborders.JobOrderForm.jobOrder(before,"JO-1",json)["missionVersion"].asText())
        assertEquals("7",dev.picasso.ops.service.joborders.JobOrderForm.jobOrder(service.draft("asset-check",1,input,true),"JO-2",json)["missionVersion"].asText())
        assertEquals(409,assertFailsWith<InterfaceRejected>{service.deploy("asset-check",1,"CODE",1,"오래된 배포",actor)}.status)
        assertEquals(2,service.deployments("asset-check",1).size)
        assertFailsWith<SQLException>{PostgresSupport.execute("UPDATE ops.interface_deployment SET mission_version='CODE'")}
        service.deploy("asset-check",1,"CODE",2,"이후 요청 롤백",actor)
        assertEquals(3,service.deployment("asset-check",1)!!["deploymentVersion"].asInt())
    }

    @Test fun `배정 미리보기는 실제 입력과 고정 배포를 사용하고 접수와 감사 기록을 생성하지 않는다`() {
        service.create(spec,actor); service.state("asset-check",1,"DRAFT","ENABLED","공개",actor)
        service.deploy("asset-check",1,"7",1,"고정 배포",actor)
        val bench=JobOrderBench();val before=log.list().size
        val reply=controller(bench).eligibility("asset-check",1,"""{"input":$input}""".toByteArray())
        assertEquals(200,reply.statusCode.value())
        val order=bench.judged.single().first
        assertEquals("7",order["missionVersion"].asText());assertEquals(2,order["deployment"]["deploymentVersion"].asInt())
        assertEquals("T1",order["equipmentRequirements"].single()["id"].asText())
        assertEquals(0,bench.submitted.size);assertEquals(before,log.list().size)
        assertEquals(0,jdbc.sql("SELECT count(*) FROM ops.work_request").query(Int::class.java).single())
    }
    @Test fun `배정 미리보기는 미공개 계약 잘못된 입력과 알 수 없는 계약을 거절한다`() {
        service.create(spec,actor);val api=controller(JobOrderBench())
        assertEquals(409,api.eligibility("asset-check",1,"""{"input":$input}""".toByteArray()).statusCode.value())
        service.state("asset-check",1,"DRAFT","ENABLED","공개",actor)
        for(body in listOf("{}","{\"input\":[]}","{\"input\":{},\"extra\":1}","{\"input\":{}}"))
            assertEquals(400,api.eligibility("asset-check",1,body.toByteArray()).statusCode.value())
        assertEquals(404,api.eligibility("unknown",1,"""{"input":$input}""".toByteArray()).statusCode.value())
    }
    @Test fun `호스트 응답이 없으면 미리보기의 기체 적합은 모름이고 배정 불가다`() {
        service.create(spec,actor);service.state("asset-check",1,"DRAFT","ENABLED","공개",actor)
        val bench=JobOrderBench();bench.judge={dev.picasso.ops.service.host.HostCall.Silent("offline")}
        val answer=controller(bench).eligibility("asset-check",1,"""{"input":$input}""".toByteArray()).body as Map<*,*>
        val view=answer["eligibility"] as dev.picasso.ops.service.joborders.EligibilityView
        assertEquals(dev.picasso.ops.service.joborders.HostState.HOST_SILENT,view.host)
        assertTrue(view.robots!!.all{!it.eligible && it.host==null})
    }
    @Test fun `신규 미션도 계약 매핑과 숫자 고정 버전으로 배정 판정을 받는다`() {
        val profile=json.readTree(javaClass.getResourceAsStream("/mission-profile-inspection.json"))
        service.profiles.create(profile,actor)
        val custom=service.spec(json.readTree("""{"interfaceId":"custom-inspect","version":1,"name":"공정 검사","workMasterId":"ManufacturingInspection","mapping":{"targets":"/assets"},"sample":$input}"""))
        service.create(custom,actor);service.deploy("custom-inspect",1,"3",0,"검증 버전",actor);service.state("custom-inspect",1,"DRAFT","ENABLED","공개",actor)
        val bench=JobOrderBench()
        assertEquals(200,controller(bench).eligibility("custom-inspect",1,"""{"input":$input}""".toByteArray()).statusCode.value())
        assertEquals("ManufacturingInspection",bench.judged.single().first["workMasterId"].asText())
        assertEquals("3",bench.judged.single().first["missionVersion"].asText())
    }
    @Test fun `배포가 달라진 새 호출은 제출하지 않고 같은 요청 재확인은 기존 접수를 반환한다`() {
        service.create(spec,actor);service.state("asset-check",1,"DRAFT","ENABLED","공개",actor)
        val bench=JobOrderBench();val api=controller(bench)
        val pinned=envelope().dropLast(1)+",\"expectedDeploymentVersion\":1}"
        val first=api.invoke("asset-check",1,"operator","kim",pinned.toByteArray()).body as dev.picasso.ops.service.requests.ReceiptAccepted
        assertEquals("ACCEPTED",first.receipt.state)
        service.deploy("asset-check",1,"8",1,"변경 배포",actor)
        val repeat=api.invoke("asset-check",1,"operator","kim",pinned.toByteArray())
        assertEquals(200,repeat.statusCode.value())
        assertEquals(first.receipt.receiptId,(repeat.body as dev.picasso.ops.service.requests.ReceiptAccepted).receipt.receiptId)
        val stale=envelope().dropLast(1)+",\"expectedDeploymentVersion\":1}"
        val rejected=api.invoke("asset-check",1,"operator","kim",stale.toByteArray()).body as dev.picasso.ops.service.requests.ReceiptAccepted
        assertEquals("REJECTED",rejected.receipt.state);assertContains(rejected.receipt.detail!!,"배포가 변경")
        assertEquals(1,bench.submitted.size)
    }
    @Test fun `미리보기 뒤 기체 조건이 달라지면 실제 제출에서 다시 판정한다`() {
        service.create(spec,actor);service.state("asset-check",1,"DRAFT","ENABLED","공개",actor)
        val bench=JobOrderBench();val api=controller(bench)
        assertEquals(200,api.eligibility("asset-check",1,"""{"input":$input}""".toByteArray()).statusCode.value())
        bench.judge={dev.picasso.ops.service.host.HostCall.Silent("offline")}
        val sent=api.invoke("asset-check",1,"operator","kim",envelope().toByteArray()).body as dev.picasso.ops.service.requests.ReceiptAccepted
        assertEquals("UNASSIGNED",sent.receipt.state);assertEquals(0,bench.submitted.size)
    }

}
