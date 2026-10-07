// 가짜 현장 런처(스펙 §6). registry 스키마 마이그레이션, registry 기동, mimic 기동과 시간 진행을 맡는다.
// 개정판 시험 실행기도 여기서 뜬다. 적재 토큰을 가진 것이 이 모듈뿐이다(P2·S1d 스펙 §7).
plugins {
    application
}

application {
    mainClass.set("dev.picasso.ops.site.SiteLauncherKt")
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

dependencies {
    implementation("dev.picasso:registry")
    implementation("dev.picasso:mimic")
    implementation("dev.picasso:harness")

    // registry 는 Spring Boot·Flyway 를 implementation 으로만 쓴다. 런처가 직접 부르므로 여기서도 적는다.
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.jackson.databind)

    testImplementation(kotlin("test"))
    testImplementation(testFixtures("dev.picasso:registry"))
}

// 저장소 루트에서 돈다. robots.json 의 프로파일 경로가 루트 기준이다.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.withType<Test>().configureEach {
    // 시험이 저장소 파일을 읽는다. 선언하지 않으면 파일을 고쳐도 시험이 UP-TO-DATE 로 넘어간다.
    inputs.file(rootProject.file("site/robots.json")).withPropertyName("roster")
    inputs.dir(rootProject.file("picasso/profile/profiles")).withPropertyName("profiles")
    inputs.dir(rootProject.file("picasso/profile/schema")).withPropertyName("profileSchema")
}
