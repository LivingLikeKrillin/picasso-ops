// 실행 호스트(S3a 스펙 §7). picasso 미들웨어를 세워 mimic 기체에서 작업 지시를 실행한다. 운영 서비스가 picasso 를 쓰지 않는
// 경계(`checkNoPicassoOnMain`)를 지키도록 별도 프로세스로 둔다(결정 1). 그 검사는 이 모듈에 걸지 않는다.
plugins {
    application
}

application {
    mainClass.set("dev.picasso.ops.host.MissionHostApplicationKt")
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

dependencies {
    implementation("dev.picasso:picasso")
    // picasso 가 client 를 implementation 으로 들므로 ClientRobotPort 에 넘길 PicassoClient 를 직접 의존한다.
    implementation("dev.picasso:client")

    // 운영 서비스와 같이 BOM 만 쓰고 Spring Boot 플러그인은 붙이지 않는다. 판이 picasso 카탈로그 밖으로 나가지 않는다.
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.module.kotlin)

    testImplementation(kotlin("test"))
    // 호스트 시험은 mimic 을 이 JVM 의 Netty 포트에 띄운다. 운영 배치와 같은 네트워크 채널을 지난다.
    testImplementation("dev.picasso:mimic")
    // 통합 시험은 registry·운영 서비스와 한 JVM 에 호스트를 띄우므로 JDBC 자동설정이 클래스패스에 온다. 호스트 시험도
    // 같은 조건에서 돌려 «DB 없이 뜬다» 를 매번 확인한다(MissionHostApplication 의 자동설정 제외).
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-jdbc")
    testRuntimeOnly(libs.postgresql)
}

tasks.withType<Test>().configureEach {
    // 시험이 저장소 파일을 읽는다. 선언하지 않으면 파일을 고쳐도 시험이 UP-TO-DATE 로 넘어간다.
    inputs.dir(rootProject.file("picasso/profile/profiles")).withPropertyName("profiles")
    inputs.dir(rootProject.file("picasso/profile/schema")).withPropertyName("profileSchema")
}
