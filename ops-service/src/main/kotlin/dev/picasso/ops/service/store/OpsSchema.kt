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

    /**
     * 주소로 ops 스키마를 올린다. 통합 시험의 스택이 실행 호스트보다 먼저 부른다(S3c 스펙 §7.1). 호스트가 기동 안에서 현장
     * 시간값 뷰를 한 번 읽으므로, 그 전에 뷰가 있어야 호스트가 미적용으로 뜨지 않는다. 시험 모듈은 Flyway 를 직접 보지 않으므로
     * 올린 수만 돌려준다.
     */
    fun migrate(url: String, user: String, password: String): Int =
        Flyway.configure()
            .dataSource(url, user, password)
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .locations(LOCATION)
            .load()
            .migrate()
            .migrationsExecuted
}

/** 기동 때 올린 마이그레이션 수. 조작 기록 빈이 이것에 기대어 마이그레이션 뒤에 만들어진다. */
data class OpsSchemaMigrated(val migrationsExecuted: Int)
