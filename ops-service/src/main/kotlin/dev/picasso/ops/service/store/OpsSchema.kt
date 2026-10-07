package dev.picasso.ops.service.store

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * ops 스키마의 Flyway(스펙 §7.1). 위치는 `classpath:db/ops`, 스키마와 이력 테이블은 `ops` 다.
 *
 * **Spring Boot 의 Flyway 자동설정을 쓰지 않는다.** 자동설정의 기본 위치는 `classpath:db/migration` 이고,
 * 통합 시험 JVM 에는 registry jar 가 같은 클래스패스에 있어 registry 마이그레이션을 집어 온다.
 * 위치와 스키마를 이 한 곳에 두고 기동과 시험이 같이 쓴다.
 */
object OpsSchema {
    const val SCHEMA = "ops"
    const val LOCATION = "classpath:db/ops"

    fun flyway(dataSource: DataSource, cleanable: Boolean = false): Flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .locations(LOCATION)
            .cleanDisabled(!cleanable)
            .load()
}

/** 기동 때 올린 마이그레이션 수. 조작 기록 빈이 이것에 기대어 마이그레이션 뒤에 만들어진다. */
data class OpsSchemaMigrated(val migrationsExecuted: Int)
