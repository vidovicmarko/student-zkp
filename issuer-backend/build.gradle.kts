import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    kotlin("plugin.jpa") version "2.1.0"
    id("org.springframework.boot") version "3.4.0"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "hr.fer.studentzkp"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    // TODO Phase 1 — EUDI libraries publish to GitHub Packages; uncomment when wiring them in.
    // maven {
    //     url = uri("https://maven.pkg.github.com/eu-digital-identity-wallet/eudi-lib-jvm-openid4vci-kt")
    //     credentials {
    //         username = System.getenv("GITHUB_ACTOR")
    //         password = System.getenv("GITHUB_TOKEN")
    //     }
    // }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.nimbusds:nimbus-jose-jwt:10.0.1")

    // Flyway migrations (final_plan §5.1).
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Zero-install local dev: a real Postgres 16 binary downloaded + started in
    // the same JVM under the `local` Spring profile. Active in dev only — prod
    // builds should exclude this configuration. See EmbeddedPostgresConfig.kt.
    implementation("io.zonky.test:embedded-postgres:2.0.7")
    implementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:16.2.0"))

    // JNA binding to the Rust studentzkp-crypto cdylib (final_plan §5.2).
    implementation("net.java.dev.jna:jna:5.15.0")

    // Play Integrity server-side verification (final_plan §5.8 Layer 2).
    // Decodes and verifies integrity tokens issued by Google Play Services on the device.
    // Uncomment for prod builds with Google API credentials configured.
    // implementation("com.google.api-client:google-api-client:2.2.0")
    // implementation("com.google.apis:google-api-services-playintegrity:v1-rev20231219-2.0.0")

    // OpenAPI / Swagger UI for the issuer's HTTP surface. Auto-generates
    // /v3/api-docs (machine-readable) + /swagger-ui.html (browser). Consumed
    // locally by the swagger-api MCP server pointed at /v3/api-docs.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")

    // TODO Phase 1 — SD-JWT-VC + OID4VCI + OID4VP via walt.id / EUDI.
    // implementation("id.walt:waltid-identity:1.0.0")
    // implementation("eu.europa.ec.eudi:eudi-lib-jvm-openid4vci-kt:0.9.0")
    // implementation("eu.europa.ec.eudi:eudi-lib-jvm-openid4vp-kt:0.9.0")
    // implementation("eu.europa.ec.eudi:eudi-lib-jvm-sdjwt-kt:0.9.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Same crypto-core cdylib for tests as for bootRun.
    systemProperty("jna.library.path", file("build/native").absolutePath)
}

// Suppress the *-plain.jar — Spring Boot's bootJar is the only runnable artifact.
// Without this, a Dockerfile glob like `COPY *.jar` picks up the plain jar
// (alphabetically last) and `java -jar` fails with "no main manifest attribute".
tasks.named<Jar>("jar") {
    enabled = false
}

// Point JNA at the studentzkp-crypto cdylib produced by `scripts/build-crypto.sh host`.
// Path is set unconditionally — if the file is missing JNA only fails when
// BbsCryptoBridge is first touched, so unrelated bootRun usage stays unaffected.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    systemProperty("jna.library.path", file("build/native").absolutePath)
}
