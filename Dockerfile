# Multi-stage build: Rust cdylib + Spring Boot fat jar → JRE runtime with the
# .so where JNA can find it. Replaces Railway's Nixpacks autobuild so the
# crypto-core BBS+ binary is present in the runtime image (otherwise issuance
# emits bbsVc=null because JNA can't locate libstudentzkp_crypto.so).

# ── Stage 1: build the Rust cdylib for linux/amd64 ────────────────────────
FROM rust:1.83 AS crypto
WORKDIR /build
COPY crypto-core ./
RUN cargo build --release
# Output: /build/target/release/libstudentzkp_crypto.so

# ── Stage 2: build the Spring Boot fat jar ────────────────────────────────
FROM eclipse-temurin:21-jdk AS jvm
WORKDIR /build
COPY issuer-backend ./
RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon
# Output: /build/build/libs/student-zkp-issuer-*.jar

# ── Stage 3: runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=jvm /build/build/libs/student-zkp-issuer-*.jar /app/app.jar
COPY --from=crypto /build/target/release/libstudentzkp_crypto.so /app/lib/
# JNA looks here first; LD_LIBRARY_PATH covers Linux's default loader too.
ENV LD_LIBRARY_PATH=/app/lib
ENV JAVA_TOOL_OPTIONS="-Djna.library.path=/app/lib"
EXPOSE 8080
CMD ["sh", "-c", "echo '=== /app/lib contents ==='; ls -la /app/lib/ || echo 'MISSING'; echo '=== LD_LIBRARY_PATH:' \"$LD_LIBRARY_PATH\"; echo '=== JAVA_TOOL_OPTIONS:' \"$JAVA_TOOL_OPTIONS\"; java -jar /app/app.jar --server.port=$PORT --spring.profiles.active=dev-shortcut"]
