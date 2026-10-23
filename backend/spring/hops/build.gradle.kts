plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.hihops"
version = "0.0.1-SNAPSHOT"
description = "hops"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    // DB 테이블 생성·변경을 버전 붙은 SQL 파일(src/main/resources/db/migration)로 관리한다.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Swagger UI. Boot가 관리하지 않는 라이브러리라 버전을 직접 적는다 (docs/AGENT_DEVELOPMENT.md 11장: 3.1.1).
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// OS 기본 인코딩(Windows MS949 등)과 상관없이 한글 소스를 UTF-8로 컴파일한다.
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}
