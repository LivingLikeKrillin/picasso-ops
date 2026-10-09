package dev.picasso.ops.host

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.client.PicassoClient
import dev.picasso.middleware.ClientRobotPort
import dev.picasso.middleware.RobotPort
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.mission.MissionVersions
import dev.picasso.ops.host.mission.MockRunner
import dev.picasso.ops.host.mission.StoredMissionCatalog
import dev.picasso.ops.host.store.HostSchema
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.ops.host.store.HostSchemaMigrated
import dev.picasso.ops.host.store.MissionStore
import dev.picasso.ops.host.timings.SiteTimingsReader
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.time.Duration
import javax.sql.DataSource

/**
 * 실행 호스트(S3a 스펙 §7, S3b 스펙 §6). 임무 버전을 같은 Postgres 의 자기 스키마 `mission` 에 둔다(S3b T3).
 *
 * Flyway 자동설정은 이름으로 끈다. 마이그레이션은 [HostSchema] 가 맡는다. 통합 시험은 registry·운영 서비스와 한 JVM 에
 * 호스트를 띄우고, 그때 자동설정이 켜지면 registry 마이그레이션 위치를 집어 온다.
 */
@SpringBootApplication(excludeName = ["org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"])
open class MissionHostApplication {

    /** 기본은 실제 시각이다. 시험은 [builder] 로 다른 시계를 먼저 넣고, 그러면 이 빈은 만들어지지 않는다. */
    @Bean
    @ConditionalOnMissingBean(HostClock::class)
    open fun hostClock(): HostClock = HostClock.SYSTEM

    /** 같은 기계의 현장 mimic gRPC 에 평문으로 붙는다(S3a 스펙 §7.2). */
    @Bean(destroyMethod = "shutdownNow")
    open fun mimicChannel(@Value("\${host.mimic.port}") port: Int): ManagedChannel {
        require(port in 1..65535) { "mimic gRPC 포트(host.mimic.port, MIMIC_GRPC_PORT)가 틀리다: $port" }
        return ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    }

    /** 시험은 [builder] 로 다른 포트를 먼저 넣고, 그러면 이 빈은 만들어지지 않는다. */
    @Bean
    @ConditionalOnMissingBean(RobotPort::class)
    open fun robotPort(channel: ManagedChannel): RobotPort = ClientRobotPort(PicassoClient(channel, CLIENT_ID))

    @Bean
    open fun cellBand(@Value("\${host.cell.url}") url: String): CellBandClient = CellBandClient(url)

    @Bean
    open fun hostSchemaMigrated(dataSource: DataSource): HostSchemaMigrated =
        HostSchemaMigrated(HostSchema.flyway(dataSource).migrate().migrationsExecuted)

    /** [migrated] 는 쓰지 않는다. 받는 것만으로 mission 마이그레이션 뒤에 이 빈이 만들어진다. */
    @Bean
    open fun missionStore(
        jdbc: JdbcClient,
        @Suppress("UNUSED_PARAMETER") migrated: HostSchemaMigrated,
    ): MissionStore = MissionStore(jdbc)

    /**
     * 기동 때 DB 의 활성 버전으로 카탈로그를 세운다(S3b 스펙 §6.2, T1). 다시 검증하지 않는다. 저장된 정의를 파싱하지 못하면
     * 예외가 나가 기동이 멈춘다.
     */
    @Bean
    open fun missionCatalog(store: MissionStore): StoredMissionCatalog =
        StoredMissionCatalog().apply { restore(store.activeVersions()) }

    /**
     * 현장 시간값 읽기 주기(S3c 스펙 §7.1). 기동 안에서 운영 서비스의 뷰를 한 번 동기로 읽고, 실패하면 미적용으로 뜬 뒤
     * [interval](`host.site-timings.read-interval`, 기본 1초)마다 다시 읽는다. ops 스키마가 아직 없어도 기동은 멈추지 않는다.
     */
    @Bean(destroyMethod = "close")
    open fun siteTimingsReader(
        jdbc: JdbcClient,
        clock: HostClock,
        @Value("\${host.site-timings.read-interval}") interval: Duration,
    ): SiteTimingsReader = SiteTimingsReader(jdbc, clock).start(interval)

    /** 실행 일지, 송신 기록, 인시던트 사본(S4b 스펙 §6.1). pump 뒤 기록은 이 데이터 소스의 트랜잭션 하나로 묶는다. */
    @Bean
    open fun hostRecords(
        jdbc: JdbcClient,
        dataSource: DataSource,
        json: ObjectMapper,
        @Suppress("UNUSED_PARAMETER") migrated: HostSchemaMigrated,
    ): HostRecords = HostRecords(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)), json)

    /** 빈을 만들 때 일지로 실행을 복원하고 pump 를 켠다(S4b 스펙 T3). 웹 서버는 그 뒤에 열린다. */
    @Bean(destroyMethod = "close")
    open fun missionHost(
        robots: RobotPort,
        cellBand: CellBandClient,
        clock: HostClock,
        catalog: StoredMissionCatalog,
        timings: SiteTimingsReader,
        records: HostRecords,
        store: MissionStore,
        json: ObjectMapper,
    ): MissionHost = MissionHost(robots, cellBand, clock, catalog, timings, records, store, json).start()

    /**
     * 모의 실행기(S3b 스펙 §6.4). 프로파일과 스키마 경로는 작업 디렉터리 기준으로 푼다. `:mission-host:run` 은 저장소 루트에서
     * 돌고(site 의 `run` 과 같음), 시험은 절대 경로를 실행 인자로 넘긴다. 파일이 없으면 기동에서 멈춘다.
     */
    @Bean
    open fun mockRunner(
        @Value("\${host.mock-run.profile}") profile: String,
        @Value("\${host.mock-run.schema}") schema: String,
        @Value("\${host.mock-run.virtual-limit}") virtualLimit: Duration,
    ): MockRunner = MockRunner(Path.of(profile).toAbsolutePath().normalize(), Path.of(schema).toAbsolutePath().normalize(), virtualLimit)

    @Bean
    open fun missionVersions(
        host: MissionHost,
        store: MissionStore,
        catalog: StoredMissionCatalog,
        runner: MockRunner,
        clock: HostClock,
        json: ObjectMapper,
    ): MissionVersions = MissionVersions(host, store, catalog, runner, clock, json)

    companion object {
        /** mimic 에 싣는 클라이언트 id. */
        const val CLIENT_ID = "mission-host"

        /**
         * 설정 파일 이름을 `mission-host` 로 둔다. 같은 JVM 의 registry `application.properties`·운영 서비스 설정과 가리지 않게.
         *
         * @param clock 주면 기본 시계 대신 이것을 쓴다(통합 시험이 현장 시계를 넣는다).
         * @param robots 주면 기본 하위 포트 대신 이것을 쓴다(호스트 시험이 기체 스냅숏을 막는 포트를 넣는다).
         */
        fun builder(clock: HostClock? = null, robots: RobotPort? = null): SpringApplicationBuilder {
            val builder = SpringApplicationBuilder(MissionHostApplication::class.java)
                .properties("spring.config.name=mission-host")
            if (clock != null || robots != null) {
                builder.initializers(
                    ApplicationContextInitializer<ConfigurableApplicationContext> {
                        clock?.let { c -> it.beanFactory.registerSingleton("hostClock", c) }
                        robots?.let { r -> it.beanFactory.registerSingleton("testRobotPort", r) }
                    },
                )
            }
            return builder
        }
    }
}

fun main(args: Array<String>) {
    MissionHostApplication.builder().run(*args)
}
