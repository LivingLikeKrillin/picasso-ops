package dev.picasso.ops.host.store

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * 실행 호스트의 `mission` 스키마 Flyway(S3b 스펙 §6.1, T3). 위치는 `classpath:db/mission`, 스키마와 이력 테이블은 `mission` 이다.
 *
 * **Spring Boot 의 Flyway 자동설정을 쓰지 않는다.** 운영 서비스 `OpsSchema` 와 같은 이유다. 자동설정의 기본 위치는
 * `classpath:db/migration` 이고, 통합 시험 JVM 에는 registry jar 가 같은 클래스패스에 있어 registry 마이그레이션을 집어 온다.
 * 위치와 스키마를 이 한 곳에 두고 기동과 시험이 같이 쓴다.
 */
object HostSchema {
    const val SCHEMA = "mission"
    const val LOCATION = "classpath:db/mission"

    fun flyway(dataSource: DataSource): Flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .locations(LOCATION)
            .load()
}

/** 기동 때 올린 마이그레이션 수. 저장소 빈이 이것에 기대어 마이그레이션 뒤에 만들어진다. */
data class HostSchemaMigrated(val migrationsExecuted: Int)
