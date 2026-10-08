package dev.picasso.ops.host

import dev.picasso.client.PicassoClient
import dev.picasso.middleware.ClientRobotPort
import dev.picasso.ops.host.cell.CellBandClient
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean

/**
 * 실행 호스트(S3a 스펙 §7). DB 가 없다(T5).
 *
 * JDBC·Flyway 자동설정을 이름으로 끈다. 이 모듈의 클래스패스에는 없지만 통합 시험은 registry·운영 서비스와 한 JVM 에
 * 호스트를 띄우고, 그때 JDBC 가 클래스패스에 와서 데이터 소스 주소 없이 기동이 멈춘다.
 */
@SpringBootApplication(
    excludeName = [
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
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

    @Bean
    open fun cellBand(@Value("\${host.cell.url}") url: String): CellBandClient = CellBandClient(url)

    @Bean(destroyMethod = "close")
    open fun missionHost(channel: ManagedChannel, cellBand: CellBandClient, clock: HostClock): MissionHost =
        MissionHost(ClientRobotPort(PicassoClient(channel, CLIENT_ID)), cellBand, clock).start()

    companion object {
        /** mimic 에 싣는 클라이언트 id. */
        const val CLIENT_ID = "mission-host"

        /**
         * 설정 파일 이름을 `mission-host` 로 둔다. 같은 JVM 의 registry `application.properties`·운영 서비스 설정과 가리지 않게.
         *
         * @param clock 주면 기본 시계 대신 이것을 쓴다(통합 시험이 현장 시계를 넣는다).
         */
        fun builder(clock: HostClock? = null): SpringApplicationBuilder {
            val builder = SpringApplicationBuilder(MissionHostApplication::class.java)
                .properties("spring.config.name=mission-host")
            if (clock != null) {
                builder.initializers(
                    ApplicationContextInitializer<ConfigurableApplicationContext> {
                        it.beanFactory.registerSingleton("hostClock", clock)
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
