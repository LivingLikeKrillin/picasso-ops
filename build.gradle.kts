plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

/**
 * 루트 `.env`. 런처·운영 서비스의 실행 작업과 시험이 같은 값을 환경 변수로 받는다(스펙 §4).
 * Spring Boot 는 `.env` 를 스스로 읽지 않는다.
 */
val dotenv: Map<String, String> = file(".env").readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .associate { line ->
        require("=" in line) { ".env 줄에 '=' 가 없다: $line" }
        line.substringBefore("=").trim() to line.substringAfter("=").trim()
    }

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories { mavenCentral() }

    configurations.configureEach {
        // picasso 루트 build.gradle.kts 와 같은 이유다. 낮은 protobuf-java 가 이기면 컴파일이 아니라 런타임에 터진다.
        resolutionStrategy.force(
            "com.google.protobuf:protobuf-java:${rootProject.libs.versions.protobuf.get()}",
        )
        // mimic 의 slf4j-nop 과 Spring Boot 의 logback 이 함께 있으면 Spring 이 기동을 거부한다
        // (picasso registry/build.gradle.kts 의 주석). registry 와 mimic 이 한 클래스패스에 오는 곳마다 걸린다.
        exclude(group = "org.slf4j", module = "slf4j-nop")
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(21)
        // picasso 루트와 같이 언어 판을 적는다. 안 적으면 플러그인 판을 올릴 때 언어 판이 함께 움직인다.
        compilerOptions {
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        }
    }

    dependencies {
        add("testImplementation", rootProject.libs.junit.jupiter)
        add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
    }

    // 토큰을 쥐는 곳은 스펙 §4 의 표대로다. 적재 토큰은 mimic 이 있는 site 만 받는다.
    // site 가 운영자 토큰도 받는 것은 같은 프로세스에서 registry(토큰을 검증하는 쪽)를 띄우기 때문이다.
    val env = if (name == "site") dotenv else dotenv - "PICASSO_INGEST_TOKEN"

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        environment(env)
        // environment(...) 는 더하기만 한다. 셸에서 상속된 적재 토큰도 site 밖에서는 지운다.
        if (project.name != "site") environment.remove("PICASSO_INGEST_TOKEN")
        // 환경 변수는 Gradle 의 시험 입력 추적에 들어가지 않는다. 선언하지 않으면 .env 를 고쳐도 UP-TO-DATE 다.
        inputs.file(rootProject.file(".env")).withPropertyName("dotenv")
    }

    tasks.withType<JavaExec>().configureEach {
        environment(env)
        if (project.name != "site") environment.remove("PICASSO_INGEST_TOKEN")
    }
}
