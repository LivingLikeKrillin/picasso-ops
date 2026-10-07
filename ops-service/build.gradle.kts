// 운영 서비스(스펙 §7). 화면의 유일한 백엔드이며 운영자 토큰을 쥔다.
// **main 은 picasso 모듈에 의존하지 않는다.** registry REST 만 부른다(스펙 §4).
plugins {
    application
}

application {
    mainClass.set("dev.picasso.ops.service.OpsApplicationKt")
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

dependencies {
    // registry 와 같이 BOM 만 쓰고 Spring Boot 플러그인은 붙이지 않는다. 판이 picasso 카탈로그 밖으로 나가지 않는다.
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(libs.jackson.module.kotlin)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(kotlin("test"))
    // 시험용 Postgres 는 registry 의 testFixtures 를 쓴다. 복사하지 않는다(스펙 §10).
    testImplementation(testFixtures("dev.picasso:registry"))
}

/**
 * «main 은 picasso 모듈에 의존하지 않는다»(스펙 §4)를 빌드가 집행한다. 시험 클래스패스는 registry testFixtures 로
 * picasso 를 보므로 main 의 runtimeClasspath 만 본다. picasso 모듈은 포함 빌드 `:picasso` 의 프로젝트로 보인다.
 */
val checkNoPicassoOnMain by tasks.registering {
    val root = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    doLast {
        val seen = mutableSetOf<org.gradle.api.artifacts.component.ComponentIdentifier>()
        fun walk(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
            if (!seen.add(component.id)) return
            component.dependencies
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                .forEach { walk(it.selected) }
        }
        walk(root.get())
        val leaked = seen
            .filterIsInstance<org.gradle.api.artifacts.component.ProjectComponentIdentifier>()
            .map { it.buildTreePath }
            .filter { it.startsWith(":picasso:") }
        check(leaked.isEmpty()) { "ops-service main 이 picasso 모듈에 의존한다: $leaked" }
    }
}

tasks.named("check") { dependsOn(checkNoPicassoOnMain) }
