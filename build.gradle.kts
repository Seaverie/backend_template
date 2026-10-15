plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.spring") version "2.2.20"
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.tenantcore"
version = "0.1.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

// 1.21.4 speaks the Docker API versions required by Docker Engine 29+ (Docker Desktop 4.5x).
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.nimbusds:nimbus-jose-jwt:10.4.2")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // S3-compatible object storage (AWS S3, Cloudflare R2, MinIO, Backblaze B2...).
    implementation(platform("software.amazon.awssdk:bom:2.34.0"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")
    // Argon2id password hashing (Spring Security's encoder uses Bouncy Castle).
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    // Reading .xlsx imports (streaming, no Apache POI)
    implementation("org.dhatim:fastexcel-reader:0.19.0")
    // QR codes
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("org.dhatim:fastexcel:0.19.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        allWarningsAsErrors = false
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.bootJar { archiveFileName.set("tenantcore.jar") }
tasks.jar { enabled = false }

// The dashboard (web/, built with `npm run build`) is served by the API from classpath:/static.
// When web/dist does not exist (backend-only builds), the jar simply has no dashboard.
tasks.processResources {
    from("web/dist") { into("static") }
}
