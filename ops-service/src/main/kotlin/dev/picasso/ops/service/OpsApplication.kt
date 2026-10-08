package dev.picasso.ops.service

import dev.picasso.ops.service.operations.ProfileOperations
import dev.picasso.ops.service.profiles.ProfileListService
import dev.picasso.ops.service.adapters.AdapterListService
import dev.picasso.ops.service.cell.CellSignalOperations
import dev.picasso.ops.service.host.HostClient
import dev.picasso.ops.service.joborders.JobOrderEligibility
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.missions.MissionOperations
import dev.picasso.ops.service.operations.AdapterOperations
import dev.picasso.ops.service.operations.RobotOperations
import dev.picasso.ops.service.registry.RegistryClient
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.settings.SiteSettingsOperations
import dev.picasso.ops.service.settings.SiteSettingsStore
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.store.OpsSchemaMigrated
import dev.picasso.ops.service.web.SiteId
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import javax.sql.DataSource

/** 운영 서비스(스펙 §7). Flyway 자동설정을 끄는 이유는 [OpsSchema] 에 있다. */
@SpringBootApplication(exclude = [FlywayAutoConfiguration::class])
open class OpsApplication {

    @Bean
    open fun clock(): Clock = Clock.systemUTC()

    @Bean
    open fun opsSchemaMigrated(dataSource: DataSource): OpsSchemaMigrated =
        OpsSchemaMigrated(OpsSchema.flyway(dataSource).migrate().migrationsExecuted)

    @Bean
    open fun siteId(@Value("\${ops.site-id}") value: String): SiteId {
        require(value.isNotBlank()) { "ops.site-id(SITE_ID)가 비었다" }
        return SiteId(value)
    }

    /** 주소 형식은 [RegistryClient.checkBaseUrl] 이 기동에서 본다. */
    @Bean
    open fun registryClient(
        @Value("\${ops.registry.url}") url: String,
        @Value("\${ops.registry.operator-token}") token: String,
    ): RegistryClient {
        // registry 는 빈 토큰을 전부 401 로 다룬다. 설정 실수를 401 이 아니라 기동에서 드러낸다.
        require(token.isNotBlank()) { "운영자 토큰(ops.registry.operator-token)이 비었다" }
        return RegistryClient(url, token)
    }

    /** 연결 칸의 기준 시간은 현장 설정 버전에서 읽는다(S2 스펙 §6.4). S1 에서는 설정 파일 값이었다. */
    @Bean
    open fun robotList(
        registry: RegistryClient,
        siteId: SiteId,
        clock: Clock,
        settings: SiteSettingsStore,
    ): RobotListService = RobotListService(registry, registry, siteId.value, clock, settings, commissioning = registry)

    @Bean
    open fun adapterList(registry: RegistryClient, siteId: SiteId, clock: Clock): AdapterListService =
        AdapterListService(registry, siteId.value, clock)

    @Bean
    open fun robotOperations(
        registry: RegistryClient,
        log: OperationLog,
        siteId: SiteId,
        clock: Clock,
    ): RobotOperations = RobotOperations(registry, registry, log, siteId.value, clock)

    @Bean
    open fun adapterOperations(
        registry: RegistryClient,
        log: OperationLog,
        siteId: SiteId,
        clock: Clock,
    ): AdapterOperations = AdapterOperations(registry, registry, log, siteId.value, clock)

    @Bean
    open fun profileList(registry: RegistryClient, clock: Clock): ProfileListService = ProfileListService(registry, clock)

    @Bean
    open fun profileOperations(
        registry: RegistryClient,
        log: OperationLog,
        siteId: SiteId,
        clock: Clock,
    ): ProfileOperations = ProfileOperations(registry, registry, registry, registry, log, siteId.value, clock)

    /** [migrated] 는 쓰지 않는다. 받는 것만으로 ops 마이그레이션 뒤에 이 빈이 만들어진다. */
    @Bean
    open fun siteSettings(
        jdbc: JdbcClient,
        @Suppress("UNUSED_PARAMETER") migrated: OpsSchemaMigrated,
    ): SiteSettingsStore = SiteSettingsStore(jdbc)

    /** 새 버전 행과 조작 기록 행을 한 트랜잭션에 넣는다(S2 스펙 §6.2). */
    @Bean
    open fun siteSettingsOperations(
        settings: SiteSettingsStore,
        log: OperationLog,
        dataSource: DataSource,
        clock: Clock,
    ): SiteSettingsOperations =
        SiteSettingsOperations(settings, log, TransactionTemplate(DataSourceTransactionManager(dataSource)), clock)

    /** 실행 호스트 클라이언트(S3a 스펙 §8). 주소 형식은 [HostClient.checkBaseUrl] 이 기동에서 본다. */
    @Bean
    open fun hostClient(@Value("\${ops.host.url}") url: String): HostClient = HostClient(url)

    /** 시운전·연결은 기체 목록의 판정을 그대로 쓴다(T3). 기체 목록 빈을 같이 써서 그 직전 값도 같다. */
    @Bean
    open fun jobOrderEligibility(robots: RobotListService, host: HostClient, clock: Clock): JobOrderEligibility =
        JobOrderEligibility(robots, host, clock)

    @Bean
    open fun jobOrderOperations(
        eligibility: JobOrderEligibility,
        host: HostClient,
        log: OperationLog,
        clock: Clock,
    ): JobOrderOperations = JobOrderOperations(eligibility, host, host, log, clock)

    /** 시운전 완료 기체는 기체 목록의 판정을 그대로 쓴다(S3b 스펙 §7, T7). */
    @Bean
    open fun missionOperations(robots: RobotListService, host: HostClient, log: OperationLog): MissionOperations =
        MissionOperations(robots, host, log)

    @Bean
    open fun cellSignalOperations(host: HostClient, log: OperationLog): CellSignalOperations =
        CellSignalOperations(host, host, log)

    /** [migrated] 는 쓰지 않는다. 받는 것만으로 ops 마이그레이션 뒤에 이 빈이 만들어진다. */
    @Bean
    open fun operationLog(
        jdbc: JdbcClient,
        @Suppress("UNUSED_PARAMETER") migrated: OpsSchemaMigrated,
    ): OperationLog = OperationLog(jdbc)

    companion object {
        /** 설정 파일 이름을 `ops-service` 로 둔다. 같은 JVM 의 registry `application.properties` 와 가리지 않게(스펙 §7.1). */
        fun builder(): SpringApplicationBuilder =
            SpringApplicationBuilder(OpsApplication::class.java)
                .properties("spring.config.name=ops-service")
    }
}

fun main(args: Array<String>) {
    OpsApplication.builder().run(*args)
}
