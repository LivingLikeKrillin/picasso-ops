rootProject.name = "picasso-ops"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

// 판은 picasso 의 카탈로그 하나에서 온다(스펙 §4). 여기 따로 적으면 두 저장소의 판이 갈라진다.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("picasso/gradle/libs.versions.toml")) }
    }
}

// picasso 서브프로젝트에는 Gradle group 이 없어 좌표가 자동으로 맞지 않는다(스펙 §11).
// 쓰는 모듈만 명시한다. uplink 는 mimic 의 api 로 따라온다. harness 는 가짜 현장이 개정판 시험 실행기를 띄우려고 쓴다.
includeBuild("picasso") {
    dependencySubstitution {
        substitute(module("dev.picasso:registry")).using(project(":registry"))
        substitute(module("dev.picasso:mimic")).using(project(":mimic"))
        substitute(module("dev.picasso:harness")).using(project(":harness"))
    }
}

include("site")
include("ops-service")
include("e2e")
