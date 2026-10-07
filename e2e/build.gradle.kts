// 통합 시험만 있는 모듈. 한 JVM 에 Postgres·registry·mimic·운영 서비스를 함께 띄운다(스펙 §10).
dependencies {
    testImplementation(project(":site"))
    testImplementation(project(":ops-service"))
    testImplementation(testFixtures("dev.picasso:registry"))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.jackson.databind)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("site/robots.json")).withPropertyName("roster")
    inputs.dir(rootProject.file("picasso/profile/profiles")).withPropertyName("profiles")
    inputs.dir(rootProject.file("picasso/profile/schema")).withPropertyName("profileSchema")
}
