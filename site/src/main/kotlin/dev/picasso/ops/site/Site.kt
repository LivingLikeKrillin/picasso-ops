package dev.picasso.ops.site

import dev.picasso.mimic.cli.MimicCli
import dev.picasso.registry.web.RegistryApplication
import dev.picasso.uplink.report.RegistryLink
import org.flywaydb.core.Flyway
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.time.Duration

/**
 * 띄운 가짜 현장 하나. registry 와 mimic 을 이 프로세스 안에 둔다(스펙 §6).
 *
 * mimic CLI 를 쓰지 않는 이유는 스펙 §6 첫 문단이다. CLI 에는 시간을 진행시키는 루프가 없고,
 * registry 는 실행 jar 가 없으며 기동 때 Flyway 를 돌리지 않는다.
 */
class Site private constructor(
    private val registry: ConfigurableApplicationContext,
    private val mimic: MimicCli.Started,
    val registryUrl: String,
    val robotIds: Set<String>,
) : AutoCloseable {

    /** mimic 의 가상 시계를 민다. 상태 발행이 이 시계로 정해지고, 상태 발행이 곧 생존 보고다. */
    fun advance(by: Duration) = mimic.server.advance(by)

    /** registry 만 멈춘다. 운영 서비스가 «모름» 을 보이는지 볼 때 쓴다(스펙 §3 S1a). */
    fun stopRegistry() = registry.close()

    override fun close() {
        try {
            mimic.server.shutdown()
        } finally {
            if (registry.isActive) registry.close()
        }
    }

    companion object {

        fun start(config: SiteConfig): Site {
            // ① registry 스키마는 런처가 올린다. ops 스키마는 운영 서비스의 몫이다(스펙 §6 ①, §7.1).
            migrateRegistrySchema(config.db)

            // ② registry 를 클래스패스로 띄운다. 설정은 실행 인자로만 넣는다(스펙 §10).
            val registry = SpringApplicationBuilder(RegistryApplication::class.java).run(
                "--server.port=${config.registryPort}",
                // 토큰이 공개 저장소의 .env 에 있으므로 이 기계 밖에 열지 않는다. mimic 의 gRPC 는 picasso 가
                // 주소를 정하므로(모든 인터페이스) 여기서 못 바꾼다.
                "--server.address=127.0.0.1",
                "--picasso.db.url=${config.db.url}",
                "--picasso.db.user=${config.db.user}",
                "--picasso.db.password=${config.db.password}",
                "--picasso.operator.token=${config.operatorToken}",
                "--picasso.ingest.token=${config.ingestToken}",
                "--picasso.profile.schema=${config.schema}",
            )
            val port = (registry as WebServerApplicationContext).webServer.port
            val registryUrl = "http://127.0.0.1:$port"

            // ③ mimic 을 가상 시계로 띄운다. 실시간 시계는 advance 에서 오류를 낸다(스펙 §6 ③).
            // ④ 사이트 id 는 .env 의 SITE_ID 하나에서 온다.
            val err = StringBuilder()
            val mimic = try {
                MimicCli().start(
                    robots = config.roster.associate { it.robotId to config.profile(it) },
                    schema = config.schema,
                    port = 0,
                    virtual = true,
                    seed = 0L,
                    err = err,
                    // 폴백 디렉터리는 두지 않는다. 생존 보고는 폴백이 없고, S1 은 태스크 관측을 쓰지 않는다.
                    link = RegistryLink.http(registryUrl, config.ingestToken, config.siteId, null),
                    broker = null,
                    site = config.siteId,
                )
            } catch (e: Exception) {
                registry.close()
                throw e
            }
            if (mimic == null) {
                registry.close()
                error("mimic 기동 거부: $err")
            }
            return Site(registry, mimic, registryUrl, mimic.robotIds)
        }

        /** registry jar 의 `classpath:db/migration` 을 기본 스키마(public)에 올린다. registry 시험 픽스처와 같은 설정이다. */
        fun migrateRegistrySchema(db: DbConfig): Int =
            Flyway.configure()
                .dataSource(db.url, db.user, db.password)
                .load()
                .migrate()
                .migrationsExecuted
    }
}
