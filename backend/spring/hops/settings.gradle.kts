plugins {
    // 로컬에 JDK 21이 없으면 Gradle이 자동으로 내려받는다 (팀원 PC마다 같은 Java 21 사용).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "hops"
