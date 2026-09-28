# API Failure Lab — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Spring Boot 4 "failure lab". It has six reproducible failure scenarios, each with a
`/broken` and a `/fixed` endpoint, a real Docker dependency stack, a Grafana dashboard, a k6 load script
per scenario, and documentation for every scenario.

**Architecture:**
- A single Spring Boot app runs on the host.
- Postgres and a WireMock "downstream" service run in Docker. The app reaches both **only through Toxiproxy**, so real network faults can be injected.
- Prometheus scrapes Actuator, and Grafana visualizes the results.
- Each scenario is a self-contained package under `scenarios/`. The only shared code is `common/`.

**Tech Stack:**
- Java 17, Spring Boot 4.1.1 (webmvc, restclient, data-jpa, flyway, actuator), Gradle Kotlin DSL (wrapper 9.6)
- PostgreSQL 17, Caffeine, Micrometer/Prometheus
- Testcontainers 2, WireMock 3.13.2
- Docker: Toxiproxy 2.12.0, Prometheus v3.15.0, Grafana 13.0.9, k6 2.3.0

**Spec:** `docs/superpowers/specs/2026-09-28-api-failure-lab-phase1-design.md`

## Global Constraints

- Java 17 only. Do not use virtual threads or Java 21 APIs.
- Spring Boot 4.1.1 with the modular starters verified via Initializr: `spring-boot-starter-webmvc`,
  `spring-boot-starter-restclient`, `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`,
  `spring-boot-starter-actuator`. Test starters are `spring-boot-starter-webmvc-test` and `spring-boot-starter-data-jpa-test`.
- Base package: `com.example.failurelab`.
- Scenario slugs are exactly: `thread-pool`, `n-plus-one`, `pool-exhaustion`, `downstream-timeout`,
  `memory-leak`, `redos`. Endpoints are `GET /scenarios/{slug}/broken` and `GET /scenarios/{slug}/fixed`.
- Deliberately small limits:
  - `server.tomcat.threads.max=20`
  - Hikari `maximum-pool-size=5`, `connection-timeout=3000`
  - bootRun JVM `-Xmx256m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=dumps`
  - `spring.jpa.open-in-view=false`
- The app reaches Postgres at `localhost:15432` and the downstream at `localhost:18080`. Both are Toxiproxy ports, never direct.
- Scenario packages must not import each other. Shared code lives in `common/` only.
- Response bodies never contain stack traces. Errors are RFC 9457 `ProblemDetail` responses carrying a `correlationId`.
- Every scenario doc uses the template sections, in order: Symptom · Reproduce · What you'll see ·
  Diagnose · Root cause · Fix · Takeaway · Further reading.
- Tests use real HTTP (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `java.net.http.HttpClient`
  pinned to HTTP/1.1). Assertions run on status, headers and raw JSON strings. There is no Jackson in the tests
  because Boot 4 ships Jackson 3, whose package names differ from the tutorials learners will read.

**Deviations from the spec (decided while planning):**
1. **ReDoS pattern.** JDK 9+ memoizes backtracking for simple greedy loops, so the textbook patterns
   (`^(a+)+$`, `^([a-zA-Z0-9]+)*@…$`, `^(\w+\s?)*$`) run in **0 ms** on JDK 17. This was measured on this
   machine. The lab instead uses `^(.*,){10}\d+$`, a "CSV line has 11 fields" validator. Its bounded
   repetition is not memoized and backtracks polynomially. Measured: 31 chars → 938 ms, 41 chars → 14.4 s.
   The fix is `^([^,]*,){10}\d+$`, which takes 0 ms on 5,001 chars. This makes a good lesson for the docs.
2. **Memory-leak map.** It uses `ConcurrentHashMap` instead of `HashMap`, so a second, unrelated concurrency
   bug can't muddy the leak lesson.
3. **WireMock `/flaky` stub.** Dropped, because no phase-1 scenario uses it (YAGNI).

## Review Focus

1. **Missing query parameter** (`/memory-leak/*` without `key`, `/redos/*` without `input`) → expect a `400`
   ProblemDetail, not a 500. Tests are in Task 8 and Task 9.
2. **Downstream returns 5xx or resets the connection** on `/downstream-timeout/fixed` → expect the fallback, not a
   500. Tests are in Task 7.
3. **ReDoS input exactly at the length limit** (200 chars accepted, 201 rejected) → tested in Task 9.
4. **Same cache key repeated** on `/memory-leak/broken` → entries must not grow. Tested in Task 8.
5. **Unhandled exception message containing a secret** → it must not appear in the response body. Tested in Task 1.

---

## File Map

```
build.gradle.kts                         (replace)  Boot 4 build + bootRun JVM flags
.gitignore                               (modify)   ignore dumps, build
Makefile                                            lab workflow commands
docker-compose.yml                                  postgres, wiremock, toxiproxy, prometheus, grafana, k6
lab/toxiproxy/toxiproxy.json                        proxy definitions
lab/wiremock/mappings/{slow,fast}.json              downstream stubs
lab/prometheus/prometheus.yml                       scrape config
lab/grafana/provisioning/datasources/prometheus.yml
lab/grafana/provisioning/dashboards/provider.yml
lab/grafana/dashboards/failure-lab.json             the dashboard
scripts/toxics.sh                                   Toxiproxy helper
k6/lib.js, k6/01-thread-pool.js … k6/06-redos.js    load scripts
dumps/.gitkeep                                      heap/thread dump target
src/main/resources/application.yml
src/main/resources/db/migration/V1__authors_and_books.sql
src/main/resources/db/migration/V2__downstream_call_log.sql
src/main/java/com/example/failurelab/
  FailureLabApplication.java
  common/ScenarioHeaderFilter.java        X-Scenario / X-Variant / X-Correlation-Id + MDC
  common/GlobalExceptionHandler.java      ProblemDetail, no stack traces
  common/PingController.java              GET /api/ping (canary)
  common/ScenarioCatalogController.java   GET /scenarios
  common/ScenarioInfo.java
  common/DownstreamClients.java           untimedDownstream + timedDownstream RestClients
  scenarios/threadpool/{Bulkhead,ThreadPoolConfig,ThreadPoolController}.java
  scenarios/nplusone/{Author,Book,AuthorRepository,AuthorReport,AuthorReportService,QueryCounter,NPlusOneController}.java
  scenarios/poolexhaustion/{CallLogService,CallLogResult,PoolExhaustionController}.java
  scenarios/downstreamtimeout/{DownstreamResult,DownstreamTimeoutController}.java
  scenarios/memoryleak/MemoryLeakController.java
  scenarios/redos/{CsvRecordValidator,RedosController}.java
src/test/java/com/example/failurelab/
  support/{AbstractLabIT,TestcontainersConfiguration,DownstreamStubs,BoomTestController}.java
  common/{ScenarioHeaderFilterTest,FoundationIT,DownstreamClientsIT}.java
  scenarios/threadpool/{BulkheadTest,ThreadPoolScenarioIT}.java
  scenarios/nplusone/NPlusOneScenarioIT.java
  scenarios/poolexhaustion/PoolExhaustionScenarioIT.java
  scenarios/downstreamtimeout/DownstreamTimeoutScenarioIT.java
  scenarios/memoryleak/MemoryLeakScenarioIT.java
  scenarios/redos/{CsvRecordValidatorTest,RedosScenarioIT}.java
README.md
docs/{architecture,diagnosis-toolkit,failure-taxonomy,roadmap}.md
docs/scenarios/_template.md, 01-thread-pool-exhaustion.md … 06-redos.md
```

**Learner contributions (learning mode):** Task 4 Step 3 (`Bulkhead.tryCall`) and Task 7 Step 3 (the
fallback in `DownstreamTimeoutController.fixed`). The executor **pauses and asks the learner** to write
these 5–10 lines. The reference solution is used only if the learner declines, or for comparison afterwards.

---

### Task 1: Foundation — build, app skeleton, shared web plumbing, test harness

**Files:**
- Replace: `build.gradle.kts`
- Modify: `.gitignore`
- Create: `src/main/resources/application.yml`, `src/main/java/com/example/failurelab/FailureLabApplication.java`,
  `common/ScenarioHeaderFilter.java`, `common/GlobalExceptionHandler.java`, `common/PingController.java`,
  `common/ScenarioInfo.java`, `common/ScenarioCatalogController.java`, `dumps/.gitkeep`, `docs/scenarios/_template.md`
- Test: `support/TestcontainersConfiguration.java`, `support/DownstreamStubs.java`, `support/AbstractLabIT.java`,
  `support/BoomTestController.java`, `common/ScenarioHeaderFilterTest.java`, `common/FoundationIT.java`

**Interfaces:**
- Produces:
  - `ScenarioHeaderFilter.CORRELATION_ID` (`"correlationId"`, MDC key)
  - response headers `X-Scenario`, `X-Variant`, `X-Correlation-Id`
  - `AbstractLabIT` with `protected static final WireMockServer DOWNSTREAM`,
    `protected HttpResponse<String> get(String path)`, `protected HttpResponse<String> delete(String path)`,
    `protected CompletableFuture<HttpResponse<String>> getAsync(String path)`,
    `protected static long elapsedMs(long startNanos)`, `protected static long jsonLong(String body, String field)`
  - the `DownstreamStubs.register(WireMockServer)` stubs: `GET /slow` (2000 ms delay, `{"value":"slow"}`) and `GET /fast` (`{"value":"fast"}`)
  - property `lab.downstream.base-url`

- [ ] **Step 1: Initialize git and replace the build**

```bash
git init
```

`build.gradle.kts`:
```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.github.ben-manes.caffeine:caffeine")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Deliberately small heap so the memory-leak scenario reaches OutOfMemoryError in minutes, not hours.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("-Xmx256m", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=dumps")
}
```

Append to `.gitignore` (read it first; add only the lines that are missing):
```
build/
dumps/*
!dumps/.gitkeep
```
Create an empty `dumps/.gitkeep`.

- [ ] **Step 2: Verify the dependencies resolve**

Run: `./gradlew dependencies --configuration runtimeClasspath -q | head -20`
Expected: no `FAILED` entries. If any coordinate fails, compare it against the Initializr output for Boot 4.1.1
before changing it.

- [ ] **Step 3: Write the failing tests**

`src/test/java/com/example/failurelab/support/TestcontainersConfiguration.java`:
```java
package com.example.failurelab.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
    }
}
```

`src/test/java/com/example/failurelab/support/DownstreamStubs.java`:
```java
package com.example.failurelab.support;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;

/** The same stubs docker-compose loads from lab/wiremock/mappings, registered in-JVM for tests. */
public final class DownstreamStubs {

    public static final int SLOW_DELAY_MS = 2000;

    private DownstreamStubs() {
    }

    public static void register(WireMockServer server) {
        server.stubFor(get("/slow").willReturn(okJson("{\"value\":\"slow\"}").withFixedDelay(SLOW_DELAY_MS)));
        server.stubFor(get("/fast").willReturn(okJson("{\"value\":\"fast\"}")));
    }
}
```

`src/test/java/com/example/failurelab/support/AbstractLabIT.java`:
```java
package com.example.failurelab.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Base class for integration tests: the real app on a random port, Postgres in Testcontainers,
 * and an in-JVM WireMock playing the downstream service. One Spring context is shared by all subclasses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class AbstractLabIT {

    protected static final WireMockServer DOWNSTREAM =
            new WireMockServer(options().dynamicPort().containerThreads(100));

    static {
        DOWNSTREAM.start();
    }

    @DynamicPropertySource
    static void downstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("lab.downstream.base-url", DOWNSTREAM::baseUrl);
    }

    @Value("${local.server.port}")
    protected int port;

    protected final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void resetDownstream() {
        DOWNSTREAM.resetAll();
        DownstreamStubs.register(DOWNSTREAM);
    }

    protected HttpResponse<String> get(String path) {
        return send(request(path).GET().build());
    }

    protected HttpResponse<String> delete(String path) {
        return send(request(path).DELETE().build());
    }

    protected CompletableFuture<HttpResponse<String>> getAsync(String path) {
        return http.sendAsync(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Extracts a numeric field from a flat JSON body, e.g. jsonLong("{\"entries\":3}", "entries") == 3. */
    protected static long jsonLong(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(\\d+)").matcher(body);
        if (!m.find()) {
            throw new AssertionError("field '" + field + "' not found in " + body);
        }
        return Long.parseLong(m.group(1));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
```

`src/test/java/com/example/failurelab/support/BoomTestController.java`:
```java
package com.example.failurelab.support;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint that throws, to prove exception details never reach the response body. */
@RestController
class BoomTestController {

    @GetMapping("/test/boom")
    String boom() {
        throw new IllegalStateException("db password is hunter2");
    }
}
```

`src/test/java/com/example/failurelab/common/ScenarioHeaderFilterTest.java`:
```java
package com.example.failurelab.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioHeaderFilterTest {

    private final ScenarioHeaderFilter filter = new ScenarioHeaderFilter();

    @Test
    void tagsScenarioRequests() throws Exception {
        MockHttpServletResponse response = run("/scenarios/redos/fixed");

        assertThat(response.getHeader("X-Scenario")).isEqualTo("redos");
        assertThat(response.getHeader("X-Variant")).isEqualTo("fixed");
        assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void tagsSubPathsOfAVariant() throws Exception {
        MockHttpServletResponse response = run("/scenarios/memory-leak/broken/cache");

        assertThat(response.getHeader("X-Scenario")).isEqualTo("memory-leak");
        assertThat(response.getHeader("X-Variant")).isEqualTo("broken");
    }

    @Test
    void leavesOtherRequestsUntaggedButCorrelated() throws Exception {
        MockHttpServletResponse response = run("/api/ping");

        assertThat(response.getHeader("X-Scenario")).isNull();
        assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
    }

    private MockHttpServletResponse run(String uri) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", uri), response, new MockFilterChain());
        return response;
    }
}
```

`src/test/java/com/example/failurelab/common/FoundationIT.java`:
```java
package com.example.failurelab.common;

import com.example.failurelab.support.AbstractLabIT;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class FoundationIT extends AbstractLabIT {

    @Test
    void pingAnswersPong() {
        HttpResponse<String> response = get("/api/ping");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"pong\"");
    }

    @Test
    void catalogListsAllSixScenarios() {
        HttpResponse<String> response = get("/scenarios");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"slug\":\"thread-pool\"", "\"slug\":\"n-plus-one\"",
                "\"slug\":\"pool-exhaustion\"", "\"slug\":\"downstream-timeout\"",
                "\"slug\":\"memory-leak\"", "\"slug\":\"redos\"");
    }

    @Test
    void unhandledExceptionsBecomeProblemDetailsWithoutLeakingDetails() {
        HttpResponse<String> response = get("/test/boom");

        String correlationId = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body()).contains("\"correlationId\":\"" + correlationId + "\"");
        assertThat(response.body()).doesNotContain("hunter2").doesNotContain("at com.example");
    }

    @Test
    void prometheusEndpointIsExposed() {
        HttpResponse<String> response = get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes");
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew test`
Expected: FAIL. Compilation errors say `ScenarioHeaderFilter` and `FailureLabApplication` do not exist.

- [ ] **Step 5: Implement the foundation**

`src/main/java/com/example/failurelab/FailureLabApplication.java`:
```java
package com.example.failurelab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FailureLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(FailureLabApplication.class, args);
    }
}
```

`src/main/resources/application.yml`:
```yaml
# Limits here are deliberately SMALL so failures show up at laptop-scale load.
# See docs/architecture.md, "The deliberate limits".
spring:
  application:
    name: failure-lab
  datasource:
    # Port 15432 is Toxiproxy, not Postgres. Every DB byte can have network faults injected.
    url: jdbc:postgresql://localhost:15432/failurelab
    username: lab
    password: lab
    hikari:
      maximum-pool-size: 5
      minimum-idle: 5
      connection-timeout: 3000        # ms a request waits for a free connection before failing
      leak-detection-threshold: 2000  # log a warning when a connection is held > 2s
  jpa:
    open-in-view: false               # never hold a DB connection for the whole HTTP request
    hibernate:
      ddl-auto: validate              # Flyway owns the schema; Hibernate only checks it
    properties:
      hibernate:
        session_factory:
          statement_inspector: com.example.failurelab.scenarios.nplusone.QueryCounter
  mvc:
    problemdetails:
      enabled: true

server:
  tomcat:
    threads:
      max: 20                         # the whole app can serve 20 requests concurrently
      min-spare: 20
    accept-count: 100                 # connections that queue once all 20 threads are busy
    mbeanregistry:
      enabled: true                   # required for tomcat_threads_* metrics

lab:
  downstream:
    # Port 18080 is Toxiproxy in front of WireMock.
    base-url: http://localhost:18080

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,threaddump,heapdump
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
        http.client.requests: true

logging:
  pattern:
    level: "%5p [%X{correlationId:-}]"
```

`src/main/java/com/example/failurelab/common/ScenarioHeaderFilter.java`:
```java
package com.example.failurelab.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gives every request a correlation id (response header + logging MDC) and tags scenario requests
 * with X-Scenario / X-Variant so load-test results and logs are easy to filter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScenarioHeaderFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID = "correlationId";

    private static final Pattern SCENARIO_PATH = Pattern.compile("^/scenarios/([^/]+)/(broken|fixed)(/.*)?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = UUID.randomUUID().toString();
        MDC.put(CORRELATION_ID, correlationId);
        response.setHeader("X-Correlation-Id", correlationId);

        Matcher scenario = SCENARIO_PATH.matcher(request.getRequestURI());
        if (scenario.matches()) {
            response.setHeader("X-Scenario", scenario.group(1));
            response.setHeader("X-Variant", scenario.group(2));
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID);
        }
    }
}
```

`src/main/java/com/example/failurelab/common/GlobalExceptionHandler.java`:
```java
package com.example.failurelab.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 ProblemDetail. Spring's own exceptions (400s, 404s, 405s...) are
 * handled by the superclass; anything else becomes a 500 whose body carries only the exception type and
 * a correlation id. The stack trace goes to the log, never to the client.
 *
 * This does NOT hide failures: broken scenarios still return their natural 500s.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        String correlationId = MDC.get(ScenarioHeaderFilter.CORRELATION_ID);
        log.error("Unhandled exception [correlationId={}]", correlationId, ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected " + ex.getClass().getSimpleName() + "; search the logs for the correlationId");
        problem.setProperty("correlationId", correlationId);
        return problem;
    }
}
```

`src/main/java/com/example/failurelab/common/PingController.java`:
```java
package com.example.failurelab.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The canary. It does no work at all, so if its latency rises, the app itself is starved
 * (threads, CPU, GC) - not this endpoint.
 */
@RestController
class PingController {

    @GetMapping("/api/ping")
    Map<String, String> ping() {
        return Map.of("status", "pong");
    }
}
```

`src/main/java/com/example/failurelab/common/ScenarioInfo.java`:
```java
package com.example.failurelab.common;

public record ScenarioInfo(String id, String slug, String title, String category, String doc) {
}
```

`src/main/java/com/example/failurelab/common/ScenarioCatalogController.java`:
```java
package com.example.failurelab.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class ScenarioCatalogController {

    private static final List<ScenarioInfo> SCENARIOS = List.of(
            new ScenarioInfo("01", "thread-pool", "Tomcat thread-pool exhaustion", "threads",
                    "docs/scenarios/01-thread-pool-exhaustion.md"),
            new ScenarioInfo("02", "n-plus-one", "N+1 queries", "database",
                    "docs/scenarios/02-n-plus-one.md"),
            new ScenarioInfo("03", "pool-exhaustion", "Hikari connection-pool exhaustion", "database",
                    "docs/scenarios/03-connection-pool-exhaustion.md"),
            new ScenarioInfo("04", "downstream-timeout", "Downstream call with no timeout", "downstream",
                    "docs/scenarios/04-downstream-no-timeout.md"),
            new ScenarioInfo("05", "memory-leak", "Memory leak -> GC thrash -> OOM", "memory",
                    "docs/scenarios/05-memory-leak.md"),
            new ScenarioInfo("06", "redos", "Catastrophic regex backtracking (ReDoS)", "cpu",
                    "docs/scenarios/06-redos.md"));

    @GetMapping("/scenarios")
    List<ScenarioInfo> list() {
        return SCENARIOS;
    }
}
```

**Note:** `application.yml` names `QueryCounter` as a Hibernate statement inspector, but that class arrives in Task 5.
Until then, create a minimal version so Hibernate can start. Task 5 replaces it.
`src/main/java/com/example/failurelab/scenarios/nplusone/QueryCounter.java`:
```java
package com.example.failurelab.scenarios.nplusone;

import org.hibernate.resource.jdbc.spi.StatementInspector;

public class QueryCounter implements StatementInspector {

    @Override
    public String inspect(String sql) {
        return sql;
    }
}
```

`docs/scenarios/_template.md`:
```markdown
# NN — <Scenario title>

> **Category:** <threads | database | downstream | memory | cpu> · **Endpoints:** `GET /scenarios/<slug>/broken`, `GET /scenarios/<slug>/fixed` · **Load script:** `k6/NN-<slug>.js`

## Symptom
What a user or on-call engineer would notice first, in their words (e.g. "the whole API got slow, even health checks").

## Reproduce
Exact commands, copy-pasteable, starting from `make up` + `make run`.

## What you'll see
Measured numbers for broken vs fixed under the same load (a table): latency p95, error rate, the key resource metric.

## Diagnose
Step by step, in the order you would do it during a real incident: which Grafana panel, which metric query,
which dump or log line, and what in it proves the cause.

## Root cause
The mechanism, in plain words, and why this load triggers it.

## Fix
The broken code and the fixed code, side by side, with the one idea that makes the difference.

## Takeaway
One or two sentences worth remembering, plus how to spot this class of bug in code review.

## Further reading
Links.
```

- [ ] **Step 6: Run the tests to verify they pass**

Docker must be running (Testcontainers starts `postgres:17`).
Run: `./gradlew test`
Expected: PASS. `ScenarioHeaderFilterTest` has 3 tests and `FoundationIT` has 4 tests.
If `prometheusEndpointIsExposed` fails with a 404, check the Boot 4 actuator property names
(`management.endpoints.web.exposure.include`) against the reference docs before changing anything.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: foundation - Boot 4 build, error handling, canary, scenario catalog, test harness"
```

---

### Task 2: The Docker lab — dependencies, network faults, observability

**Files:**
- Create: `docker-compose.yml`, `lab/toxiproxy/toxiproxy.json`, `lab/wiremock/mappings/slow.json`,
  `lab/wiremock/mappings/fast.json`, `lab/prometheus/prometheus.yml`,
  `lab/grafana/provisioning/datasources/prometheus.yml`, `lab/grafana/provisioning/dashboards/provider.yml`,
  `lab/grafana/dashboards/failure-lab.json`, `scripts/toxics.sh`, `Makefile`, `k6/lib.js`

**Interfaces:**
- Consumes: DB `failurelab`/`lab`/`lab` and the ports `15432`/`18080` from `application.yml` (Task 1)
- Produces:
  - Toxiproxy proxies named `postgres` and `downstream`
  - `make up|down|run|test|load S=NN V=broken|fixed|toxic-latency TARGET= MS=|toxic-timeout TARGET=|toxic-reset|psql|threaddump`
  - `k6/lib.js` exports `BASE_URL`, `VARIANT`, `canary()`

- [ ] **Step 1: Write the compose file and service configs**

`docker-compose.yml`:
```yaml
name: failure-lab

services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: failurelab
      POSTGRES_USER: lab
      POSTGRES_PASSWORD: lab
    ports:
      - "5432:5432"     # direct access for psql / pg_stat_activity (the app uses 15432 via toxiproxy)
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U lab -d failurelab"]
      interval: 2s
      timeout: 2s
      retries: 20

  wiremock:
    image: wiremock/wiremock:3.13.2
    command: ["--container-threads", "200", "--no-request-journal"]
    volumes:
      - ./lab/wiremock:/home/wiremock:ro
    ports:
      - "8089:8080"     # direct access, bypassing toxiproxy

  toxiproxy:
    image: ghcr.io/shopify/toxiproxy:2.12.0
    command: ["-host=0.0.0.0", "-config=/config/toxiproxy.json"]
    volumes:
      - ./lab/toxiproxy/toxiproxy.json:/config/toxiproxy.json:ro
    ports:
      - "8474:8474"     # toxiproxy control API
      - "15432:15432"   # app -> postgres
      - "18080:18080"   # app -> wiremock
    depends_on:
      postgres:
        condition: service_healthy
      wiremock:
        condition: service_started

  prometheus:
    image: prom/prometheus:v3.15.0
    volumes:
      - ./lab/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports:
      - "9090:9090"
    extra_hosts:
      - "host.docker.internal:host-gateway"

  grafana:
    image: grafana/grafana:13.0.9
    environment:
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: Admin
      GF_AUTH_DISABLE_LOGIN_FORM: "true"
      GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH: /var/lib/grafana/dashboards/failure-lab.json
    volumes:
      - ./lab/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./lab/grafana/dashboards:/var/lib/grafana/dashboards:ro
    ports:
      - "3000:3000"
    depends_on:
      - prometheus

  k6:
    image: grafana/k6:2.3.0
    profiles: ["tools"]
    volumes:
      - ./k6:/scripts:ro
    environment:
      BASE_URL: http://host.docker.internal:8080
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

`lab/toxiproxy/toxiproxy.json`:
```json
[
  { "name": "postgres",   "listen": "0.0.0.0:15432", "upstream": "postgres:5432", "enabled": true },
  { "name": "downstream", "listen": "0.0.0.0:18080", "upstream": "wiremock:8080", "enabled": true }
]
```

`lab/wiremock/mappings/slow.json`:
```json
{
  "request": { "method": "GET", "url": "/slow" },
  "response": {
    "status": 200,
    "fixedDelayMilliseconds": 2000,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": { "value": "slow" }
  }
}
```

`lab/wiremock/mappings/fast.json`:
```json
{
  "request": { "method": "GET", "url": "/fast" },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": { "value": "fast" }
  }
}
```

`lab/prometheus/prometheus.yml`:
```yaml
global:
  scrape_interval: 5s
  evaluation_interval: 5s

scrape_configs:
  - job_name: failure-lab
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["host.docker.internal:8080"]
```

`lab/grafana/provisioning/datasources/prometheus.yml`:
```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    uid: prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```

`lab/grafana/provisioning/dashboards/provider.yml`:
```yaml
apiVersion: 1
providers:
  - name: failure-lab
    folder: Failure Lab
    type: file
    options:
      path: /var/lib/grafana/dashboards
```

- [ ] **Step 2: Write the Grafana dashboard**

`lab/grafana/dashboards/failure-lab.json`. Every panel uses datasource `{"type":"prometheus","uid":"prometheus"}`:
```json
{
  "uid": "failure-lab",
  "title": "Failure Lab",
  "schemaVersion": 39,
  "refresh": "5s",
  "time": { "from": "now-15m", "to": "now" },
  "panels": [
    { "id": 1, "type": "timeseries", "title": "Server latency p99 by endpoint",
      "gridPos": { "x": 0, "y": 0, "w": 12, "h": 8 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "s" } },
      "targets": [ { "refId": "A", "legendFormat": "{{uri}}",
        "expr": "histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket{uri=~\"/scenarios/.*|/api/ping\"}[1m])))" } ] },
    { "id": 2, "type": "timeseries", "title": "Request rate by endpoint and status",
      "gridPos": { "x": 12, "y": 0, "w": 12, "h": 8 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "reqps" } },
      "targets": [ { "refId": "A", "legendFormat": "{{uri}} {{status}}",
        "expr": "sum by (uri, status) (rate(http_server_requests_seconds_count{uri=~\"/scenarios/.*|/api/ping\"}[1m]))" } ] },
    { "id": 3, "type": "timeseries", "title": "Tomcat threads: busy vs max",
      "gridPos": { "x": 0, "y": 8, "w": 8, "h": 8 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "targets": [
        { "refId": "A", "legendFormat": "busy", "expr": "sum(tomcat_threads_busy_threads)" },
        { "refId": "B", "legendFormat": "max",  "expr": "sum(tomcat_threads_config_max_threads)" } ] },
    { "id": 4, "type": "timeseries", "title": "Hikari connections",
      "gridPos": { "x": 8, "y": 8, "w": 8, "h": 8 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "targets": [
        { "refId": "A", "legendFormat": "active",  "expr": "sum(hikaricp_connections_active)" },
        { "refId": "B", "legendFormat": "pending", "expr": "sum(hikaricp_connections_pending)" },
        { "refId": "C", "legendFormat": "max",     "expr": "sum(hikaricp_connections_max)" },
        { "refId": "D", "legendFormat": "timeouts/s", "expr": "sum(rate(hikaricp_connections_timeout_total[1m]))" } ] },
    { "id": 5, "type": "timeseries", "title": "Outbound HTTP (downstream) latency p99",
      "gridPos": { "x": 16, "y": 8, "w": 8, "h": 8 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "s" } },
      "targets": [ { "refId": "A", "legendFormat": "{{uri}} {{outcome}}",
        "expr": "histogram_quantile(0.99, sum by (le, uri, outcome) (rate(http_client_requests_seconds_bucket[1m])))" } ] },
    { "id": 6, "type": "timeseries", "title": "JVM heap used vs max",
      "gridPos": { "x": 0, "y": 16, "w": 8, "h": 8 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "bytes" } },
      "targets": [
        { "refId": "A", "legendFormat": "used", "expr": "sum(jvm_memory_used_bytes{area=\"heap\"})" },
        { "refId": "B", "legendFormat": "max",  "expr": "sum(jvm_memory_max_bytes{area=\"heap\"})" } ] },
    { "id": 7, "type": "timeseries", "title": "GC pause time per second",
      "gridPos": { "x": 8, "y": 16, "w": 8, "h": 8 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "s" } },
      "targets": [ { "refId": "A", "legendFormat": "{{action}} {{cause}}",
        "expr": "sum by (action, cause) (rate(jvm_gc_pause_seconds_sum[1m]))" } ] },
    { "id": 8, "type": "timeseries", "title": "CPU usage (process vs system)",
      "gridPos": { "x": 16, "y": 16, "w": 8, "h": 8 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": { "defaults": { "unit": "percentunit", "max": 1 } },
      "targets": [
        { "refId": "A", "legendFormat": "process", "expr": "process_cpu_usage" },
        { "refId": "B", "legendFormat": "system",  "expr": "system_cpu_usage" } ] },
    { "id": 9, "type": "timeseries", "title": "Lab: bulkhead permits available / rejected per second",
      "gridPos": { "x": 0, "y": 24, "w": 12, "h": 8 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "targets": [
        { "refId": "A", "legendFormat": "available permits", "expr": "lab_bulkhead_available" },
        { "refId": "B", "legendFormat": "rejected/s", "expr": "rate(lab_bulkhead_rejected_total[1m])" } ] },
    { "id": 10, "type": "timeseries", "title": "Lab: cache entries (memory-leak scenario)",
      "gridPos": { "x": 12, "y": 24, "w": 12, "h": 8 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "targets": [ { "refId": "A", "legendFormat": "{{variant}}", "expr": "lab_cache_entries" } ] }
  ]
}
```

- [ ] **Step 3: Write the Toxiproxy helper, Makefile, and k6 shared lib**

`scripts/toxics.sh` (then `chmod +x scripts/toxics.sh`):
```bash
#!/usr/bin/env bash
# Inject REAL network faults between the app and its dependencies via Toxiproxy's HTTP API.
# Proxies: "postgres" (app -> :15432 -> postgres) and "downstream" (app -> :18080 -> wiremock).
set -euo pipefail

API="${TOXIPROXY_API:-http://localhost:8474}"

usage() {
  cat <<'EOF'
Usage: scripts/toxics.sh <command> [args]
  latency <proxy> <ms>   add latency to every response from <proxy>
  timeout <proxy>        stop all data; connections hang until the client gives up
  reset <proxy>          reset connections (TCP RST) immediately
  down <proxy>           disable the proxy (connection refused)
  up <proxy>             re-enable the proxy
  list                   show proxies and active toxics
  clear                  remove all toxics and re-enable all proxies
EOF
}

add_toxic() { # proxy name type attributes-json
  curl -sf -X POST "$API/proxies/$1/toxics" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$2\",\"type\":\"$3\",\"stream\":\"downstream\",\"attributes\":$4}"
  echo
}

case "${1:-}" in
  latency) add_toxic "$2" "latency_$2" latency "{\"latency\":$3,\"jitter\":0}" ;;
  timeout) add_toxic "$2" "timeout_$2" timeout '{"timeout":0}' ;;
  reset)   add_toxic "$2" "reset_$2" reset_peer '{"timeout":0}' ;;
  down)    curl -sf -X POST "$API/proxies/$2" -H 'Content-Type: application/json' -d '{"enabled":false}'; echo ;;
  up)      curl -sf -X POST "$API/proxies/$2" -H 'Content-Type: application/json' -d '{"enabled":true}'; echo ;;
  list)    curl -sf "$API/proxies"; echo ;;
  clear)   curl -sf -X POST "$API/reset"; echo "all toxics removed" ;;
  *)       usage; exit 1 ;;
esac
```

`Makefile`:
```make
.PHONY: up down run test load toxic-latency toxic-timeout toxic-reset psql threaddump

up:            ## start postgres, wiremock, toxiproxy, prometheus, grafana
	docker compose up -d --wait postgres wiremock toxiproxy prometheus grafana

down:          ## stop everything and delete volumes
	docker compose down -v

run:           ## run the app on the host with lab JVM flags (see build.gradle.kts)
	mkdir -p dumps && ./gradlew bootRun

test:
	./gradlew test

load:          ## make load S=01 V=broken
	docker compose run --rm k6 run -e VARIANT=$(V) /scripts/$(notdir $(wildcard k6/$(S)-*.js))

toxic-latency: ## make toxic-latency TARGET=downstream MS=30000
	./scripts/toxics.sh latency $(TARGET) $(MS)

toxic-timeout: ## make toxic-timeout TARGET=postgres
	./scripts/toxics.sh timeout $(TARGET)

toxic-reset:   ## remove all toxics
	./scripts/toxics.sh clear

psql:          ## psql straight into postgres (bypasses toxiproxy)
	docker compose exec postgres psql -U lab failurelab

threaddump:    ## write a thread dump of the running app to dumps/
	mkdir -p dumps && jcmd $$(jps | awk '/FailureLabApplication/{print $$1}') Thread.print > dumps/threads-$$(date +%s).txt && ls -t dumps | head -1
```

`k6/lib.js`:
```js
import http from 'k6/http';
import { check, sleep } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
export const VARIANT = __ENV.VARIANT || 'broken';

if (VARIANT !== 'broken' && VARIANT !== 'fixed') {
  throw new Error(`VARIANT must be "broken" or "fixed", got "${VARIANT}"`);
}

// The canary: one virtual user pinging an endpoint that does no work. If its latency rises,
// the whole app is starved, not just the endpoint under load.
export function canary() {
  const res = http.get(`${BASE_URL}/api/ping`, { tags: { name: 'canary' } });
  check(res, { 'canary 200': (r) => r.status === 200 });
  sleep(0.5);
}
```

- [ ] **Step 4: Verify the lab comes up and the proxies work**

Run:
```bash
make up
curl -s localhost:8474/proxies            # expect "postgres" and "downstream", both enabled
curl -s localhost:18080/fast              # through toxiproxy -> {"value":"fast"}
curl -s -o /dev/null -w '%{time_total}\n' localhost:18080/slow   # ~2.0
./scripts/toxics.sh latency downstream 1000
curl -s -o /dev/null -w '%{time_total}\n' localhost:18080/fast   # ~1.0 -> the toxic works
./scripts/toxics.sh clear
docker compose exec postgres psql -U lab failurelab -c 'select 1'
curl -s localhost:3000/api/dashboards/uid/failure-lab | head -c 200   # dashboard is provisioned
```
Expected: every command matches its comment. Prometheus target `host.docker.internal:8080` shows **DOWN**
at http://localhost:9090/targets until the app runs. That is correct.

- [ ] **Step 5: Verify the app runs against the lab**

Run `make run` in one terminal. Then:
```bash
curl -s localhost:8080/api/ping
curl -s localhost:8080/actuator/prometheus | grep -c tomcat_threads_busy_threads   # >= 1
```
Then check that http://localhost:9090/targets shows the target **UP**. Stop the app with Ctrl-C.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: docker lab - postgres, wiremock, toxiproxy, prometheus, grafana dashboard, k6 lib"
```

---

### Task 3: Downstream HTTP clients (untimed vs timed)

**Files:**
- Create: `src/main/java/com/example/failurelab/common/DownstreamClients.java`
- Test: `src/test/java/com/example/failurelab/common/DownstreamClientsIT.java`

**Interfaces:**
- Consumes: `lab.downstream.base-url`, `AbstractLabIT`
- Produces: `RestClient` beans named `untimedDownstream` (no timeouts) and `timedDownstream`
  (connect 500 ms, read 1 s), plus `DownstreamClients.CONNECT_TIMEOUT` and `DownstreamClients.READ_TIMEOUT`.
  Inject them with `@Qualifier("untimedDownstream")` or `@Qualifier("timedDownstream")`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.failurelab.common;

import com.example.failurelab.support.AbstractLabIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownstreamClientsIT extends AbstractLabIT {

    @Autowired
    @Qualifier("untimedDownstream")
    RestClient untimed;

    @Autowired
    @Qualifier("timedDownstream")
    RestClient timed;

    @Test
    void untimedClientWaitsForAsLongAsTheDownstreamTakes() {
        DOWNSTREAM.stubFor(get("/delayed").willReturn(okJson("{\"value\":\"late\"}").withFixedDelay(3000)));

        long start = System.nanoTime();
        String body = untimed.get().uri("/delayed").retrieve().body(String.class);

        assertThat(body).contains("late");
        assertThat(elapsedMs(start)).isGreaterThanOrEqualTo(2900);
    }

    @Test
    void timedClientGivesUpAfterTheReadTimeout() {
        DOWNSTREAM.stubFor(get("/delayed").willReturn(okJson("{\"value\":\"late\"}").withFixedDelay(3000)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> timed.get().uri("/delayed").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(elapsedMs(start)).isBetween(900L, 2000L);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*DownstreamClientsIT'`
Expected: FAIL, with `NoSuchBeanDefinitionException` for `untimedDownstream`.

- [ ] **Step 3: Implement**

```java
package com.example.failurelab.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Two clients for the same downstream service, differing ONLY in timeouts.
 *
 * - untimedDownstream: the JDK HttpClient default - no read timeout at all. A slow downstream holds
 *   the calling thread for as long as it likes. Used by broken variants, and by scenarios 01/03 where
 *   the 2s /slow call is the point of the lesson.
 * - timedDownstream: connect 500ms, read 1s. Used by the fixed variant of scenario 04.
 *
 * Both are built from Boot's RestClient.Builder so they record http.client.requests metrics.
 */
@Configuration
public class DownstreamClients {

    public static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    @Bean
    RestClient untimedDownstream(RestClient.Builder builder, @Value("${lab.downstream.base-url}") String baseUrl) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return builder.baseUrl(baseUrl).requestFactory(new JdkClientHttpRequestFactory(http)).build();
    }

    @Bean
    RestClient timedDownstream(RestClient.Builder builder, @Value("${lab.downstream.base-url}") String baseUrl) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*DownstreamClientsIT'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: untimed and timed downstream RestClients"
```

---

### Task 4: Scenario 01 — Tomcat thread-pool exhaustion

**Files:**
- Create: `scenarios/threadpool/Bulkhead.java`, `scenarios/threadpool/ThreadPoolConfig.java`,
  `scenarios/threadpool/ThreadPoolController.java`, `k6/01-thread-pool.js`,
  `docs/scenarios/01-thread-pool-exhaustion.md`
- Test: `scenarios/threadpool/BulkheadTest.java`, `scenarios/threadpool/ThreadPoolScenarioIT.java`

**Interfaces:**
- Consumes: `untimedDownstream`, `AbstractLabIT`, `DownstreamStubs.SLOW_DELAY_MS`, and `k6/lib.js` `canary`
- Produces:
  - `Bulkhead(int maxConcurrentCalls, Duration maxWait)`
  - `<T> Optional<T> tryCall(Supplier<T> call)`
  - `int availablePermits()`
  - metrics `lab.bulkhead.available` and `lab.bulkhead.rejected`

- [ ] **Step 1: Write the Bulkhead skeleton and its failing unit test**

`Bulkhead.java`:
```java
package com.example.failurelab.scenarios.threadpool;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A bulkhead caps how many threads may be inside a slow dependency at once. When it is full, callers
 * are turned away quickly instead of joining the pile-up, so the rest of the thread pool stays free
 * for other endpoints. (Named after the watertight compartments in a ship's hull.)
 */
public class Bulkhead {

    private final Semaphore permits;
    private final Duration maxWait;

    public Bulkhead(int maxConcurrentCalls, Duration maxWait) {
        this.permits = new Semaphore(maxConcurrentCalls);
        this.maxWait = maxWait;
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    /**
     * Runs {@code call} if a permit becomes available within {@code maxWait}; otherwise returns
     * {@link Optional#empty()} WITHOUT running it.
     */
    public <T> Optional<T> tryCall(Supplier<T> call) {
        throw new UnsupportedOperationException("TODO(learner): implement - see Task 4 Step 3");
    }
}
```

`BulkheadTest.java`:
```java
package com.example.failurelab.scenarios.threadpool;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkheadTest {

    @Test
    void runsTheCallWhenAPermitIsFree() {
        Bulkhead bulkhead = new Bulkhead(1, Duration.ofMillis(50));

        assertThat(bulkhead.tryCall(() -> "ok")).contains("ok");
        assertThat(bulkhead.availablePermits()).isEqualTo(1);
    }

    @Test
    void rejectsQuicklyWhenFullAndRecoversAfterRelease() throws Exception {
        Bulkhead bulkhead = new Bulkhead(1, Duration.ofMillis(50));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> bulkhead.tryCall(() -> {
            inside.countDown();
            awaitQuietly(release);
            return "held";
        }));
        holder.start();
        inside.await();

        long start = System.nanoTime();
        Optional<String> rejected = bulkhead.tryCall(() -> "should not run");
        long waitedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(rejected).isEmpty();
        assertThat(waitedMs).isLessThan(500);

        release.countDown();
        holder.join();
        assertThat(bulkhead.tryCall(() -> "after")).contains("after");
    }

    @Test
    void releasesThePermitWhenTheCallThrows() {
        Bulkhead bulkhead = new Bulkhead(1, Duration.ofMillis(50));

        assertThatThrownBy(() -> bulkhead.tryCall(() -> {
            throw new IllegalStateException("downstream blew up");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(bulkhead.availablePermits()).isEqualTo(1);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*BulkheadTest'`
Expected: FAIL with `UnsupportedOperationException: TODO(learner)`.

- [ ] **Step 3: LEARNER CONTRIBUTION — implement `Bulkhead.tryCall`**

Pause and ask the learner to write this method (about 8 lines). Explain the decision points:
- **Waiting:** `tryAcquire(timeout)` waits briefly, while `tryAcquire()` rejects instantly. Which is kinder to a burst?
- **Interruption:** what should happen when the thread is interrupted while waiting?
- **Releasing:** the permit must be released even when `call.get()` throws. `releasesThePermitWhenTheCallThrows` checks this.
- **No permit, no release:** never release a permit you didn't acquire.

Reference solution (use it only if the learner declines, or show it afterwards for comparison):
```java
    public <T> Optional<T> tryCall(Supplier<T> call) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (!acquired) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(call.get());
        } finally {
            permits.release();
        }
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests '*BulkheadTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Write the failing scenario integration test**

`ThreadPoolScenarioIT.java`:
```java
package com.example.failurelab.scenarios.threadpool;

import com.example.failurelab.support.AbstractLabIT;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 25 concurrent calls to a 2s downstream against a 20-thread Tomcat, then one canary ping.
 */
class ThreadPoolScenarioIT extends AbstractLabIT {

    private static final int CONCURRENT_CALLS = 25;

    @Test
    void brokenBlocksEveryThreadSoTheCanaryQueues() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> load = fire("/scenarios/thread-pool/broken");
        Thread.sleep(300);

        long start = System.nanoTime();
        HttpResponse<String> ping = get("/api/ping");
        long pingMs = elapsedMs(start);
        CompletableFuture.allOf(load.toArray(CompletableFuture[]::new)).join();

        assertThat(ping.statusCode()).isEqualTo(200);
        assertThat(pingMs).as("canary latency while all 20 Tomcat threads are blocked").isGreaterThan(1000);
        assertThat(load).allSatisfy(f -> assertThat(f.join().statusCode()).isEqualTo(200));
    }

    @Test
    void fixedShedsExcessLoadAndTheCanaryStaysFast() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> load = fire("/scenarios/thread-pool/fixed");
        Thread.sleep(300);

        long start = System.nanoTime();
        HttpResponse<String> ping = get("/api/ping");
        long pingMs = elapsedMs(start);
        CompletableFuture.allOf(load.toArray(CompletableFuture[]::new)).join();

        List<HttpResponse<String>> responses = load.stream().map(CompletableFuture::join).toList();
        long ok = responses.stream().filter(r -> r.statusCode() == 200).count();
        List<HttpResponse<String>> shed = responses.stream().filter(r -> r.statusCode() == 503).toList();

        assertThat(pingMs).as("canary latency with the bulkhead in place").isLessThan(500);
        assertThat(ok).as("calls admitted by the 8-permit bulkhead").isEqualTo(8);
        assertThat(shed).hasSize(CONCURRENT_CALLS - 8)
                .allSatisfy(r -> assertThat(r.headers().firstValue("Retry-After")).hasValue("1"));
    }

    private List<CompletableFuture<HttpResponse<String>>> fire(String path) {
        return IntStream.range(0, CONCURRENT_CALLS).mapToObj(i -> getAsync(path)).toList();
    }
}
```

- [ ] **Step 6: Run to verify it fails**

Run: `./gradlew test --tests '*ThreadPoolScenarioIT'`
Expected: FAIL. Both endpoints return 404.

- [ ] **Step 7: Implement the config and controller**

`ThreadPoolConfig.java`:
```java
package com.example.failurelab.scenarios.threadpool;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
class ThreadPoolConfig {

    /** 8 of Tomcat's 20 threads may wait on the slow dependency; the other 12 always stay free. */
    static final int MAX_CONCURRENT_SLOW_CALLS = 8;
    static final Duration MAX_WAIT_FOR_PERMIT = Duration.ofMillis(100);

    @Bean
    Bulkhead slowDownstreamBulkhead(MeterRegistry registry) {
        Bulkhead bulkhead = new Bulkhead(MAX_CONCURRENT_SLOW_CALLS, MAX_WAIT_FOR_PERMIT);
        Gauge.builder("lab.bulkhead.available", bulkhead, Bulkhead::availablePermits)
                .description("Free bulkhead permits for the slow downstream")
                .register(registry);
        return bulkhead;
    }
}
```

`ThreadPoolController.java`:
```java
package com.example.failurelab.scenarios.threadpool;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Scenario 01. Both variants call the same 2-second downstream on the request thread.
 * broken: unlimited, so under load every Tomcat thread ends up waiting on the downstream.
 * fixed:  a bulkhead admits 8 at a time and sheds the rest with a fast 503.
 */
@RestController
@RequestMapping("/scenarios/thread-pool")
class ThreadPoolController {

    private final RestClient downstream;
    private final Bulkhead bulkhead;
    private final Counter rejected;

    ThreadPoolController(@Qualifier("untimedDownstream") RestClient downstream, Bulkhead slowDownstreamBulkhead,
                         MeterRegistry registry) {
        this.downstream = downstream;
        this.bulkhead = slowDownstreamBulkhead;
        this.rejected = Counter.builder("lab.bulkhead.rejected")
                .description("Calls turned away because the bulkhead was full")
                .register(registry);
    }

    @GetMapping("/broken")
    Map<String, String> broken() {
        return Map.of("downstream", callSlowDownstream());
    }

    @GetMapping("/fixed")
    ResponseEntity<?> fixed() {
        return bulkhead.tryCall(this::callSlowDownstream)
                .<ResponseEntity<?>>map(body -> ResponseEntity.ok(Map.of("downstream", body)))
                .orElseGet(this::shed);
    }

    private ResponseEntity<?> shed() {
        rejected.increment();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The slow dependency is at capacity; retry shortly");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    private String callSlowDownstream() {
        return downstream.get().uri("/slow").retrieve().body(String.class);
    }
}
```

- [ ] **Step 8: Run to verify it passes**

Run: `./gradlew test --tests '*ThreadPoolScenarioIT' --tests '*BulkheadTest'`
Expected: PASS (5 tests).

- [ ] **Step 9: Write the k6 script**

`k6/01-thread-pool.js`:
```js
// Scenario 01 - Tomcat thread-pool exhaustion.
// 40 users hammer a 2s endpoint (Tomcat has 20 threads) while 1 canary pings /api/ping.
// Watch the canary threshold: it fails for "broken" and passes for "fixed".
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, VARIANT, canary } from './lib.js';

export { canary };

export const options = {
  scenarios: {
    load:   { executor: 'constant-vus', vus: 40, duration: '60s', exec: 'load' },
    canary: { executor: 'constant-vus', vus: 1,  duration: '60s', exec: 'canary' },
  },
  thresholds: {
    'http_req_duration{name:canary}': ['p(95)<250'],
  },
};

export function load() {
  const res = http.get(`${BASE_URL}/scenarios/thread-pool/${VARIANT}`, { tags: { name: 'load' } });
  check(res, {
    'served (200)': (r) => r.status === 200,
    'shed fast (503)': (r) => r.status === 503,
  });
}
```

- [ ] **Step 10: Write the scenario doc**

`docs/scenarios/01-thread-pool-exhaustion.md` follows `_template.md` exactly, with this content:
- **Symptom:** "Everything got slow, even `/api/ping` and health checks, although only one feature
  depends on the slow service." Health-check failures can make the orchestrator restart healthy pods.
- **Reproduce:** `make up`, `make run`, `make load S=01 V=broken`, then `make load S=01 V=fixed`.
  Also a manual variant: `for i in $(seq 25); do curl -s localhost:8080/scenarios/thread-pool/broken & done; time curl localhost:8080/api/ping`.
- **What you'll see:** a table with rows for canary p95, load p95, % 200 and % 503, and columns for broken and fixed.
  Leave it as `(measured in Task 11)` for now; Task 11 fills in the real numbers.
- **Diagnose:**
  1. Grafana "Tomcat threads: busy vs max": busy is pinned at 20.
  2. "Server latency p99": `/api/ping` rises with the scenario endpoint, so it's the app, not one endpoint.
  3. `make threaddump`. Count the threads named `http-nio-8080-exec-*` in state `RUNNABLE` in
     `jdk.internal.net.http...`/`sun.nio.ch...` or `WAITING` on `CompletableFuture`. Show the grep:
     `grep -A15 'http-nio-8080-exec' dumps/threads-*.txt | grep -E 'ThreadPoolController|java.lang.Thread.State'`.
  4. Little's Law: concurrent = rate × latency. 20 req/s × 2 s = 40 needed, but only 20 threads exist.
- **Root cause:** blocking I/O on a fixed-size request pool. The slow dependency's latency becomes
  *every* endpoint's latency because requests queue in `accept-count` waiting for a thread.
- **Fix:** show broken `broken()` vs fixed `fixed()` and `Bulkhead.tryCall`. Explain why 8 permits:
  the other 12 threads stay available. Explain why a fast 503 with `Retry-After` beats a slow 200.
  Mention the alternatives: a Resilience4j `Bulkhead`, a separate executor, virtual threads (Java 21: they
  remove the thread limit, but the *downstream* still gets swamped, so you still need a concurrency limit).
- **Takeaway:** "A shared pool is only as fast as its slowest consumer. Isolate slow dependencies."
  In code review, look for a synchronous remote call inside a request handler with no concurrency limit.
- **Further reading:** Release It! (Nygard), "Bulkhead" chapter; Little's Law; the Tomcat connector
  `maxThreads`/`acceptCount` docs.
- **Relation to 04:** 01 limits *how many* threads a dependency can hold; 04 limits *how long*. You need both.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat(scenario-01): thread-pool exhaustion with bulkhead fix, k6 script, docs"
```

---

### Task 5: Scenario 02 — N+1 queries

**Files:**
- Create: `src/main/resources/db/migration/V1__authors_and_books.sql`, `scenarios/nplusone/Author.java`,
  `Book.java`, `AuthorRepository.java`, `AuthorReport.java`, `AuthorReportService.java`,
  `NPlusOneController.java`, `k6/02-n-plus-one.js`, `docs/scenarios/02-n-plus-one.md`
- Replace: `scenarios/nplusone/QueryCounter.java` (the Task 1 stub)
- Test: `scenarios/nplusone/NPlusOneScenarioIT.java`

**Interfaces:**
- Consumes: `AbstractLabIT.get`, `AbstractLabIT.jsonLong`
- Produces:
  - tables `author(id, name)` and `book(id, title, author_id)` with 200 authors × 10 books
  - `QueryCounter.reset()`, `QueryCounter.count()`
  - response header `X-Query-Count`

- [ ] **Step 1: Write the failing test**

```java
package com.example.failurelab.scenarios.nplusone;

import com.example.failurelab.support.AbstractLabIT;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class NPlusOneScenarioIT extends AbstractLabIT {

    @Test
    void brokenIssuesOneQueryForAuthorsPlusOnePerAuthor() {
        HttpResponse<String> response = get("/scenarios/n-plus-one/broken");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Query-Count")).hasValue("201");
        assertThat(jsonLong(response.body(), "authors")).isEqualTo(200);
        assertThat(jsonLong(response.body(), "books")).isEqualTo(2000);
    }

    @Test
    void fixedLoadsEverythingInOneJoinQuery() {
        HttpResponse<String> response = get("/scenarios/n-plus-one/fixed");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Query-Count")).hasValue("1");
        assertThat(jsonLong(response.body(), "authors")).isEqualTo(200);
        assertThat(jsonLong(response.body(), "books")).isEqualTo(2000);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*NPlusOneScenarioIT'`
Expected: FAIL with 404.

- [ ] **Step 3: Write the migration and entities**

`V1__authors_and_books.sql`:
```sql
create table author (
    id   bigserial primary key,
    name varchar(255) not null
);

create table book (
    id        bigserial primary key,
    title     varchar(255) not null,
    author_id bigint not null references author (id)
);

create index book_author_id_idx on book (author_id);

-- 200 authors x 10 books = 2,000 books
insert into author (name)
select 'Author ' || g from generate_series(1, 200) as g;

insert into book (title, author_id)
select 'Book ' || a.id || '-' || n, a.id
from author a
cross join generate_series(1, 10) as n;
```

`Author.java`:
```java
package com.example.failurelab.scenarios.nplusone;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.util.ArrayList;
import java.util.List;

@Entity
public class Author {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    // LAZY is the JPA default for collections and usually right. The N+1 problem is not LAZY itself,
    // it is touching a lazy collection once per row in a loop.
    @OneToMany(mappedBy = "author", fetch = FetchType.LAZY)
    private List<Book> books = new ArrayList<>();

    protected Author() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<Book> getBooks() {
        return books;
    }
}
```

`Book.java`:
```java
package com.example.failurelab.scenarios.nplusone;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private Author author;

    protected Book() {
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Author getAuthor() {
        return author;
    }
}
```

`AuthorRepository.java`:
```java
package com.example.failurelab.scenarios.nplusone;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuthorRepository extends JpaRepository<Author, Long> {

    /** One SQL statement: authors LEFT JOIN books. Hibernate 6+ de-duplicates the root entities. */
    @Query("select a from Author a left join fetch a.books")
    List<Author> findAllWithBooks();
}
```

- [ ] **Step 4: Replace QueryCounter, and write the service and controller**

`QueryCounter.java`:
```java
package com.example.failurelab.scenarios.nplusone;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate calls inspect() for every SQL statement it prepares. Counting per thread gives
 * "queries issued by this request", because a request runs on one thread from start to finish.
 * Registered in application.yml (hibernate.session_factory.statement_inspector).
 */
public class QueryCounter implements StatementInspector {

    private static final ThreadLocal<long[]> COUNT = ThreadLocal.withInitial(() -> new long[1]);

    public static void reset() {
        COUNT.get()[0] = 0;
    }

    public static long count() {
        return COUNT.get()[0];
    }

    @Override
    public String inspect(String sql) {
        COUNT.get()[0]++;
        return sql;
    }
}
```

`AuthorReport.java`:
```java
package com.example.failurelab.scenarios.nplusone;

public record AuthorReport(int authors, int books) {
}
```

`AuthorReportService.java`:
```java
package com.example.failurelab.scenarios.nplusone;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
class AuthorReportService {

    private final AuthorRepository authors;

    AuthorReportService(AuthorRepository authors) {
        this.authors = authors;
    }

    /** 1 query for the authors, then author.getBooks().size() fires 1 more query PER author. */
    @Transactional(readOnly = true)
    public AuthorReport brokenReport() {
        return summarize(authors.findAll());
    }

    /** Books are fetched in the same statement as the authors, so size() touches no database. */
    @Transactional(readOnly = true)
    public AuthorReport fixedReport() {
        return summarize(authors.findAllWithBooks());
    }

    private static AuthorReport summarize(List<Author> all) {
        int books = all.stream().mapToInt(author -> author.getBooks().size()).sum();
        return new AuthorReport(all.size(), books);
    }
}
```

`NPlusOneController.java`:
```java
package com.example.failurelab.scenarios.nplusone;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/** Scenario 02. Same report, 201 queries (broken) vs 1 query (fixed). See the X-Query-Count header. */
@RestController
@RequestMapping("/scenarios/n-plus-one")
class NPlusOneController {

    private final AuthorReportService reports;

    NPlusOneController(AuthorReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/broken")
    ResponseEntity<AuthorReport> broken() {
        return counted(reports::brokenReport);
    }

    @GetMapping("/fixed")
    ResponseEntity<AuthorReport> fixed() {
        return counted(reports::fixedReport);
    }

    private static ResponseEntity<AuthorReport> counted(Supplier<AuthorReport> report) {
        QueryCounter.reset();
        AuthorReport result = report.get();
        return ResponseEntity.ok()
                .header("X-Query-Count", String.valueOf(QueryCounter.count()))
                .body(result);
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew test --tests '*NPlusOneScenarioIT'`
Expected: PASS. If the broken count is 202 rather than 201, print the SQL with
`logging.level.org.hibernate.SQL=debug` to find the extra statement. Fix the cause, not the assertion.

- [ ] **Step 6: Write the k6 script**

`k6/02-n-plus-one.js`:
```js
// Scenario 02 - N+1 queries. Same response, 201 SQL statements vs 1.
// Compare http_req_duration between runs, and watch the X-Query-Count header.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, VARIANT } from './lib.js';

export const options = {
  vus: 10,
  duration: '30s',
};

export default function () {
  const res = http.get(`${BASE_URL}/scenarios/n-plus-one/${VARIANT}`);
  check(res, {
    'status 200': (r) => r.status === 200,
    'single query': (r) => r.headers['X-Query-Count'] === '1',
  });
}
```

- [ ] **Step 7: Write the scenario doc**

`docs/scenarios/02-n-plus-one.md` follows the template, with this content:
- **Symptom:** "The endpoint is fine in dev and slow in prod." Latency grows with data size, and the DB shows
  thousands of tiny identical queries.
- **Reproduce:** `curl -i localhost:8080/scenarios/n-plus-one/broken | grep X-Query-Count`, then do the same for fixed.
  Then run `make load S=02 V=broken` and `make load S=02 V=fixed`.
- **What you'll see:** a table of query count (201 vs 1), p95 latency, and req/s. Leave the numbers as
  `(measured in Task 11)`.
- **Diagnose:**
  1. The `X-Query-Count` header, or temporarily set `logging.level.org.hibernate.SQL=debug`. You see the
     same `select ... from book where author_id=?` repeated.
  2. In Postgres, via `make psql`:
     `select calls, query from pg_stat_statements order by calls desc limit 5;`.
     Note that this needs `pg_stat_statements`, so explain the alternative
     `select count(*) from pg_stat_activity` / log_min_duration_statement, and show `docker compose exec postgres
     psql -U lab failurelab -c "alter system set log_min_duration_statement = 0; select pg_reload_conf();"` followed by
     `docker compose logs postgres`.
  3. Explain why the network multiplies it: every query pays a round trip. Add a `make toxic-latency TARGET=postgres
     MS=5` and re-run. Broken gets ~1 s slower (201 × 5 ms), fixed barely moves. **This is the key demo.**
- **Root cause:** a lazy collection accessed in a loop. Explain the `open-in-view` trap: with OSIV on, this
  code would also work *outside* a transaction and hide the problem, which is why the lab sets it `false`.
- **Fix:** `JOIN FETCH`. Alternatives: `@EntityGraph(attributePaths = "books")`,
  `hibernate.default_batch_fetch_size` (turns N+1 into N/batch+1), or DTO projections. Warn that JOIN FETCH
  on two collections gives a cartesian product (`MultipleBagFetchException`).
- **Takeaway:** "Count queries per request, not just latency. Latency hides N+1 on a local DB."
  In code review, look for a getter on a relationship inside a loop or stream.
- **Further reading:** the Vlad Mihalcea N+1 article, and the Hibernate fetching docs.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(scenario-02): N+1 queries with join-fetch fix, query counter, k6 script, docs"
```

---

### Task 6: Scenario 03 — Hikari connection-pool exhaustion

**Files:**
- Create: `src/main/resources/db/migration/V2__downstream_call_log.sql`, `scenarios/poolexhaustion/CallLogResult.java`,
  `CallLogService.java`, `PoolExhaustionController.java`, `k6/03-pool-exhaustion.js`,
  `docs/scenarios/03-connection-pool-exhaustion.md`
- Test: `scenarios/poolexhaustion/PoolExhaustionScenarioIT.java`

**Interfaces:**
- Consumes: `untimedDownstream`, the `author` table (Task 5), `AbstractLabIT.getAsync`
- Produces: table `downstream_call_log(id, called_at, response)`

- [ ] **Step 1: Write the failing test**

```java
package com.example.failurelab.scenarios.poolexhaustion;

import com.example.failurelab.support.AbstractLabIT;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 12 concurrent requests, a pool of 5 connections, a 3s connection timeout, and a 2s downstream call.
 * Broken holds a connection during the call: 5 finish at ~2s, 5 more at ~4s, and the rest time out at 3s.
 */
class PoolExhaustionScenarioIT extends AbstractLabIT {

    private static final int CONCURRENT_CALLS = 12;

    @Test
    void brokenRunsOutOfConnectionsAndFails() {
        List<HttpResponse<String>> responses = fireAndWait("/scenarios/pool-exhaustion/broken");

        long failures = responses.stream().filter(r -> r.statusCode() == 500).count();
        assertThat(failures).as("requests that timed out waiting for a connection").isGreaterThanOrEqualTo(1);
        assertThat(responses).filteredOn(r -> r.statusCode() == 500)
                .allSatisfy(r -> assertThat(r.body()).contains("correlationId"));
    }

    @Test
    void fixedServesTheSameConcurrencyWithoutErrors() {
        List<HttpResponse<String>> responses = fireAndWait("/scenarios/pool-exhaustion/fixed");

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        assertThat(responses.get(0).body()).contains("\"downstream\":");
    }

    private List<HttpResponse<String>> fireAndWait(String path) {
        List<CompletableFuture<HttpResponse<String>>> calls =
                IntStream.range(0, CONCURRENT_CALLS).mapToObj(i -> getAsync(path)).toList();
        return calls.stream().map(CompletableFuture::join).toList();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*PoolExhaustionScenarioIT'`
Expected: FAIL. All responses are 404.

- [ ] **Step 3: Implement**

`V2__downstream_call_log.sql`:
```sql
create table downstream_call_log (
    id        bigserial primary key,
    called_at timestamptz not null default now(),
    response  text        not null
);
```

`CallLogResult.java`:
```java
package com.example.failurelab.scenarios.poolexhaustion;

public record CallLogResult(String downstream, int previousCalls) {
}
```

`CallLogService.java`:
```java
package com.example.failurelab.scenarios.poolexhaustion;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Scenario 03. Both variants: read from the DB, call a 2s downstream, write the result to the DB.
 * The ONLY difference is where the transaction boundary is, and so how long a pooled connection is held.
 */
@Service
class CallLogService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RestClient downstream;

    CallLogService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                   @Qualifier("untimedDownstream") RestClient downstream) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.downstream = downstream;
    }

    /** BROKEN: the transaction (and its connection) stays open for the whole 2s remote call. */
    @Transactional
    public CallLogResult brokenCallAndRecord() {
        int previousCalls = countCalls();
        String response = callSlowDownstream();   // <- holding a pooled connection, doing nothing with it
        record(response);
        return new CallLogResult(response, previousCalls);
    }

    /** FIXED: no connection is held during the remote call; the write gets its own short transaction. */
    public CallLogResult fixedCallAndRecord() {
        int previousCalls = countCalls();          // auto-commit: connection returned immediately
        String response = callSlowDownstream();    // no connection held
        tx.executeWithoutResult(status -> record(response));
        return new CallLogResult(response, previousCalls);
    }

    private int countCalls() {
        Integer count = jdbc.queryForObject("select count(*) from downstream_call_log", Integer.class);
        return count == null ? 0 : count;
    }

    private void record(String response) {
        jdbc.update("insert into downstream_call_log (response) values (?)", response);
    }

    private String callSlowDownstream() {
        return downstream.get().uri("/slow").retrieve().body(String.class);
    }
}
```

`PoolExhaustionController.java`:
```java
package com.example.failurelab.scenarios.poolexhaustion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/scenarios/pool-exhaustion")
class PoolExhaustionController {

    private final CallLogService service;

    PoolExhaustionController(CallLogService service) {
        this.service = service;
    }

    @GetMapping("/broken")
    CallLogResult broken() {
        return service.brokenCallAndRecord();
    }

    @GetMapping("/fixed")
    CallLogResult fixed() {
        return service.fixedCallAndRecord();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests '*PoolExhaustionScenarioIT'`
Expected: PASS (2 tests). The test log for the broken run should show a Hikari
`Connection is not available, request timed out after 3000ms` and an `Apparent connection leak detected`
warning. Record the exact exception class that reaches `GlobalExceptionHandler` (its simple name
appears in the 500 body `detail`) for the doc.

- [ ] **Step 5: Write the k6 script**

`k6/03-pool-exhaustion.js`:
```js
// Scenario 03 - Hikari pool exhaustion. 20 users, a pool of 5, a 2s downstream call.
// Broken: ~3s waits and then 500s. Fixed: every request ~2s and 200.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, VARIANT } from './lib.js';

export const options = {
  vus: 20,
  duration: '45s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/scenarios/pool-exhaustion/${VARIANT}`);
  check(res, { 'status 200': (r) => r.status === 200 });
}
```

- [ ] **Step 6: Write the scenario doc**

`docs/scenarios/03-connection-pool-exhaustion.md` follows the template, with this content:
- **Symptom:** intermittent 500s under load. The logs say `Connection is not available, request timed out
  after 3000ms`, yet the database itself is idle (low CPU, few queries). That's the confusing part.
- **Reproduce:** `make load S=03 V=broken` / `V=fixed`. During the broken run, in another terminal:
  `make psql` → `select pid, state, now() - xact_start as open_for, query from pg_stat_activity where datname = 'failurelab';`
- **What you'll see:** a table of error rate, p95, and max `hikaricp_connections_pending`. Leave it as `(measured in Task 11)`.
- **Diagnose:**
  1. Grafana "Hikari connections": active = max = 5, pending > 0, and timeouts/s > 0.
  2. `pg_stat_activity`: 5 sessions in state **`idle in transaction`**. This is the smoking gun: the
     connections are checked out and inside a transaction, but not running any SQL.
  3. The log line `Apparent connection leak detected` (from `leak-detection-threshold: 2000`), with a stack trace
     pointing at `CallLogService.brokenCallAndRecord`.
  4. Math: a pool of 5 ÷ 2 s held per request = a max throughput of 2.5 req/s, whatever the DB speed.
- **Root cause:** the transaction scope includes a remote call. Explain that the connection is taken at the first
  query and returned only at commit. List other ways the same thing happens: OSIV
  (`spring.jpa.open-in-view=true`, Boot's default!), `@Transactional` on a controller, and slow processing
  inside a transaction.
- **Fix:** show the two methods side by side. Rule: *never do I/O to anything but the database inside a DB
  transaction*. Mention why a bigger pool is not the fix: it moves the bottleneck to Postgres
  `max_connections` and gives worse DB contention. Explain the pool sizing formula
  `connections = ((core_count * 2) + effective_spindle_count)` from the HikariCP wiki.
- **Takeaway:** "A connection pool's throughput = pool size ÷ hold time. Shrink the hold time."
  In code review, look for `@Transactional` methods that call HTTP clients, message brokers or files.
- **Further reading:** HikariCP "About Pool Sizing", and the Spring OSIV warning log explanation.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(scenario-03): connection-pool exhaustion with narrow-transaction fix, k6 script, docs"
```

---

### Task 7: Scenario 04 — Downstream call with no timeout

**Files:**
- Create: `scenarios/downstreamtimeout/DownstreamResult.java`, `DownstreamTimeoutController.java`,
  `k6/04-downstream-timeout.js`, `docs/scenarios/04-downstream-no-timeout.md`
- Test: `scenarios/downstreamtimeout/DownstreamTimeoutScenarioIT.java`

**Interfaces:**
- Consumes: `untimedDownstream`, `timedDownstream`, `DownstreamClients.READ_TIMEOUT`, and `DOWNSTREAM` from `AbstractLabIT`
- Produces: fallback response `{"source":"fallback", ...}` with header `X-Degraded: true`

- [ ] **Step 1: Write the failing test**

```java
package com.example.failurelab.scenarios.downstreamtimeout;

import com.example.failurelab.support.AbstractLabIT;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static org.assertj.core.api.Assertions.assertThat;

class DownstreamTimeoutScenarioIT extends AbstractLabIT {

    private static void makeFastEndpointHang(int delayMs) {
        DOWNSTREAM.stubFor(get("/fast").willReturn(okJson("{\"value\":\"fast\"}").withFixedDelay(delayMs)));
    }

    @Test
    void healthyDownstreamIsServedNormally() {
        HttpResponse<String> response = get("/scenarios/downstream-timeout/fixed");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"source\":\"downstream\"");
        assertThat(response.headers().firstValue("X-Degraded")).isEmpty();
    }

    @Test
    void brokenWaitsAsLongAsTheDownstreamDoes() {
        makeFastEndpointHang(3000);

        long start = System.nanoTime();
        HttpResponse<String> response = get("/scenarios/downstream-timeout/broken");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(elapsedMs(start)).isGreaterThanOrEqualTo(2900);
    }

    @Test
    void fixedGivesUpAfterTheTimeoutAndServesTheFallback() {
        makeFastEndpointHang(3000);

        long start = System.nanoTime();
        HttpResponse<String> response = get("/scenarios/downstream-timeout/fixed");

        assertThat(elapsedMs(start)).isLessThan(1500);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Degraded")).hasValue("true");
        assertThat(response.body()).contains("\"source\":\"fallback\"");
    }

    @Test
    void fixedServesTheFallbackWhenTheDownstreamReturns5xx() {
        DOWNSTREAM.stubFor(get("/fast").willReturn(serviceUnavailable()));

        HttpResponse<String> response = get("/scenarios/downstream-timeout/fixed");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Degraded")).hasValue("true");
    }

    @Test
    void fixedServesTheFallbackWhenTheConnectionIsReset() {
        DOWNSTREAM.stubFor(get("/fast").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpResponse<String> response = get("/scenarios/downstream-timeout/fixed");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Degraded")).hasValue("true");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*DownstreamTimeoutScenarioIT'`
Expected: FAIL with 404.

- [ ] **Step 3: Write the controller skeleton, then the LEARNER CONTRIBUTION**

`DownstreamResult.java`:
```java
package com.example.failurelab.scenarios.downstreamtimeout;

public record DownstreamResult(String source, String value) {
}
```

`DownstreamTimeoutController.java` (the skeleton, where `fixed()` has a TODO in its catch block):
```java
package com.example.failurelab.scenarios.downstreamtimeout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

/**
 * Scenario 04. Both variants call the downstream /fast endpoint. Break it for real with
 * `make toxic-latency TARGET=downstream MS=30000`.
 * broken: no timeout, so each request waits 30s, threads pile up, and the whole app stalls (see 01).
 * fixed:  1s read timeout plus a degraded-but-useful fallback response.
 */
@RestController
@RequestMapping("/scenarios/downstream-timeout")
class DownstreamTimeoutController {

    private static final Logger log = LoggerFactory.getLogger(DownstreamTimeoutController.class);

    /** What we serve when the downstream is unavailable: last-known-good or a safe default. */
    static final String FALLBACK_VALUE = "{\"value\":\"default\"}";

    private final RestClient untimed;
    private final RestClient timed;

    DownstreamTimeoutController(@Qualifier("untimedDownstream") RestClient untimed,
                                @Qualifier("timedDownstream") RestClient timed) {
        this.untimed = untimed;
        this.timed = timed;
    }

    @GetMapping("/broken")
    DownstreamResult broken() {
        return new DownstreamResult("downstream", untimed.get().uri("/fast").retrieve().body(String.class));
    }

    @GetMapping("/fixed")
    ResponseEntity<DownstreamResult> fixed() {
        try {
            String body = timed.get().uri("/fast").retrieve().body(String.class);
            return ResponseEntity.ok(new DownstreamResult("downstream", body));
        } catch (RestClientException ex) {
            throw ex; // TODO(learner): decide how a failed/slow downstream is answered - see Task 7 Step 3
        }
    }
}
```

Pause and ask the learner to replace the `catch` body (5–8 lines). Explain the decision points:
- **Which failures fall back?** `RestClientException` covers timeouts (`ResourceAccessException`) and HTTP
  errors (`HttpServerErrorException`). The tests expect a timeout, a 5xx and a connection reset to all fall back.
  Should a 4xx? Probably not, because a 4xx is *our* bug.
- **Status code:** a degraded `200` with a marker header, or a `503`. Tell the learner the tests expect
  `200` plus `X-Degraded: true` plus `"source":"fallback"`, and ask whether they agree and why.
- **Logging:** WARN with the exception *type*, not the full stack, because under an outage this line fires on
  every request.

Reference solution:
```java
        } catch (RestClientException ex) {
            log.warn("Downstream unavailable ({}); serving fallback", ex.getClass().getSimpleName());
            return ResponseEntity.ok()
                    .header("X-Degraded", "true")
                    .body(new DownstreamResult("fallback", FALLBACK_VALUE));
        }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests '*DownstreamTimeoutScenarioIT'`
Expected: PASS (5 tests).

- [ ] **Step 5: Write the k6 script**

`k6/04-downstream-timeout.js`:
```js
// Scenario 04 - downstream with no timeout.
// FIRST inject the fault:   make toxic-latency TARGET=downstream MS=30000
// AFTERWARDS remove it:     make toxic-reset
// 30 users + 1 canary. Broken: requests hang for 30s, all 20 threads are stuck, and the canary dies.
// Fixed: ~1s degraded responses and a healthy canary.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, VARIANT, canary } from './lib.js';

export { canary };

export const options = {
  scenarios: {
    load:   { executor: 'constant-vus', vus: 30, duration: '60s', exec: 'load' },
    canary: { executor: 'constant-vus', vus: 1,  duration: '60s', exec: 'canary' },
  },
  thresholds: {
    'http_req_duration{name:canary}': ['p(95)<250'],
    'http_req_duration{name:load}': ['p(95)<1500'],
  },
};

export function load() {
  const res = http.get(`${BASE_URL}/scenarios/downstream-timeout/${VARIANT}`, {
    tags: { name: 'load' },
    timeout: '60s',
  });
  check(res, {
    'status 200': (r) => r.status === 200,
    'degraded': (r) => r.headers['X-Degraded'] === 'true',
  });
}
```

- [ ] **Step 6: Write the scenario doc**

`docs/scenarios/04-downstream-no-timeout.md` follows the template, with this content:
- **Symptom:** "A partner API got slow and *our* API went down." Error rates are low at first. Requests don't
  fail, they just never finish.
- **Reproduce:** `make toxic-latency TARGET=downstream MS=30000`, then `make load S=04 V=broken`,
  then `make load S=04 V=fixed`, then `make toxic-reset`. Also demonstrate `./scripts/toxics.sh timeout downstream`
  (data stops entirely) and `./scripts/toxics.sh down downstream` (connection refused, which fails *fast* even
  without a timeout). Explain why refused connections are the kind outage and hangs are the cruel one.
- **What you'll see:** a table of load p95, canary p95, and Tomcat busy threads. Leave it as `(measured in Task 11)`.
- **Diagnose:**
  1. "Outbound HTTP latency p99" shows ~30 s, and "Tomcat threads" shows busy = 20.
  2. `make threaddump`: request threads parked in the JDK `HttpClient` waiting for a response
     (`CompletableFuture.get` / `jdk.internal.net.http`).
  3. `./scripts/toxics.sh list` shows the toxic. In real life, the equivalent is the partner's status page
     or your own client metrics.
- **Root cause:** most HTTP clients have **no read timeout by default** (JDK `HttpClient`, and historically
  `RestTemplate` / `HttpURLConnection` too). Without one, a slow dependency's latency is imported unchanged,
  and combined with 01 it exhausts threads.
- **Fix:** connect timeout 500 ms, read timeout 1 s, and a fallback. Explain how to choose the numbers
  (the downstream's p99.9 plus headroom, which must be below *your* caller's timeout, i.e. a timeout budget).
  Explain connect vs read timeouts. Mention the next steps, which are phase-2 scenarios: retries with backoff
  and jitter, and a circuit breaker so you stop calling a dead service at all.
- **Takeaway:** "Every network call needs a timeout. No timeout = your SLA is your dependency's worst day."
  In code review, look for any HTTP client, JDBC driver or cache client built without explicit timeouts.
- **Relation to 01:** a timeout bounds how long each thread is stuck, and a bulkhead bounds how many. With
  a 1 s timeout at 30 req/s you still hold ~30 threads, so you need both.
- **Further reading:** AWS Builders' Library "Timeouts, retries, and backoff with jitter"; Release It!, the "Integration Points" chapter.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(scenario-04): downstream without timeout vs timeout + fallback, k6 script, docs"
```

---

### Task 8: Scenario 05 — Memory leak → GC thrash → OOM

**Files:**
- Create: `scenarios/memoryleak/MemoryLeakController.java`, `k6/05-memory-leak.js`, `docs/scenarios/05-memory-leak.md`
- Test: `scenarios/memoryleak/MemoryLeakScenarioIT.java`

**Interfaces:**
- Consumes: `AbstractLabIT.get/delete/jsonLong`, Caffeine
- Produces:
  - `GET /scenarios/memory-leak/{broken|fixed}?key=` → `{"entries":N,"approxMegabytes":M}`
  - `DELETE /scenarios/memory-leak/broken/cache` → 204
  - gauge `lab.cache.entries{variant}`

- [ ] **Step 1: Write the failing test**

```java
package com.example.failurelab.scenarios.memoryleak;

import com.example.failurelab.support.AbstractLabIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryLeakScenarioIT extends AbstractLabIT {

    private static final int UNIQUE_KEYS = 600;

    @BeforeEach
    void clearLeakyCache() {
        assertThat(delete("/scenarios/memory-leak/broken/cache").statusCode()).isEqualTo(204);
    }

    @Test
    void brokenKeepsEveryEntryForever() {
        HttpResponse<String> last = fill("broken", "leak");

        assertThat(jsonLong(last.body(), "entries")).isEqualTo(UNIQUE_KEYS);
    }

    @Test
    void brokenDoesNotGrowForARepeatedKey() {
        get("/scenarios/memory-leak/broken?key=same");
        HttpResponse<String> second = get("/scenarios/memory-leak/broken?key=same");

        assertThat(jsonLong(second.body(), "entries")).isEqualTo(1);
    }

    @Test
    void fixedStaysWithinItsMaximumSize() {
        HttpResponse<String> last = fill("fixed", "bounded");

        assertThat(jsonLong(last.body(), "entries")).isLessThanOrEqualTo(500);
    }

    @Test
    void missingKeyIsABadRequestNotAServerError() {
        assertThat(get("/scenarios/memory-leak/broken").statusCode()).isEqualTo(400);
        assertThat(get("/scenarios/memory-leak/fixed").statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> fill(String variant, String prefix) {
        HttpResponse<String> last = null;
        for (int i = 0; i < UNIQUE_KEYS; i++) {
            last = get("/scenarios/memory-leak/" + variant + "?key=" + prefix + "-" + i);
            assertThat(last.statusCode()).isEqualTo(200);
        }
        return last;
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*MemoryLeakScenarioIT'`
Expected: FAIL. The `@BeforeEach` DELETE returns 404.

- [ ] **Step 3: Implement**

```java
package com.example.failurelab.scenarios.memoryleak;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scenario 05. A "report cache" keyed by a request parameter. Each report is 100 KB.
 * broken: a static map that nothing ever removes from. Every distinct key lives until the JVM dies.
 * fixed:  Caffeine with a maximum size and a TTL, so memory use has a ceiling.
 */
@RestController
@RequestMapping("/scenarios/memory-leak")
class MemoryLeakController {

    static final int ENTRY_BYTES = 100 * 1024;
    static final int MAX_ENTRIES = 500;

    // BROKEN: static + unbounded + no eviction. With -Xmx256m, ~2,000 unique keys fill the heap.
    private static final Map<String, byte[]> LEAKY_CACHE = new ConcurrentHashMap<>();

    // FIXED: at most 500 x 100 KB = ~50 MB, and entries expire anyway.
    private final Cache<String, byte[]> boundedCache = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    MemoryLeakController(MeterRegistry registry) {
        Gauge.builder("lab.cache.entries", LEAKY_CACHE, Map::size).tag("variant", "broken").register(registry);
        Gauge.builder("lab.cache.entries", boundedCache, Cache::estimatedSize).tag("variant", "fixed").register(registry);
    }

    @GetMapping("/broken")
    CacheReport broken(@RequestParam String key) {
        LEAKY_CACHE.computeIfAbsent(key, MemoryLeakController::renderReport);
        return CacheReport.of(LEAKY_CACHE.size());
    }

    @GetMapping("/fixed")
    CacheReport fixed(@RequestParam String key) {
        boundedCache.get(key, MemoryLeakController::renderReport);
        // Caffeine evicts in the background; cleanUp() makes the reported size deterministic for the lab.
        boundedCache.cleanUp();
        return CacheReport.of(boundedCache.estimatedSize());
    }

    /** Lets you recover from the leak without restarting the app. */
    @DeleteMapping("/broken/cache")
    ResponseEntity<Void> clearLeak() {
        LEAKY_CACHE.clear();
        return ResponseEntity.noContent().build();
    }

    /** Stands in for an expensive rendering we would want to cache. */
    private static byte[] renderReport(String key) {
        byte[] report = new byte[ENTRY_BYTES];
        Arrays.fill(report, (byte) key.hashCode());
        return report;
    }

    record CacheReport(long entries, long approxMegabytes) {
        static CacheReport of(long entries) {
            return new CacheReport(entries, entries * ENTRY_BYTES / (1024 * 1024));
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests '*MemoryLeakScenarioIT'`
Expected: PASS (4 tests).

- [ ] **Step 5: Write the k6 script**

`k6/05-memory-leak.js`:
```js
// Scenario 05 - memory leak. A steady 20 req/s, each with a NEW cache key (~2 MB/s of retained heap).
// Broken with -Xmx256m: heap climbs, GC pause time rises, latency spikes, and then OutOfMemoryError
// (heap dump in ./dumps). Fixed: heap levels off at ~50 MB of cache.
// Recover a broken run without restarting: curl -X DELETE localhost:8080/scenarios/memory-leak/broken/cache
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, VARIANT } from './lib.js';

export const options = {
  scenarios: {
    leak: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: '4m',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
};

export default function () {
  const key = `${__VU}-${__ITER}-${Date.now()}`;
  const res = http.get(`${BASE_URL}/scenarios/memory-leak/${VARIANT}?key=${key}`);
  check(res, { 'status 200': (r) => r.status === 200 });
}
```

- [ ] **Step 6: Write the scenario doc**

`docs/scenarios/05-memory-leak.md` follows the template, with this content:
- **Symptom:** "The service gets slower the longer it runs and a restart fixes it". A sawtooth that never
  returns to baseline, then an `OutOfMemoryError` and a crash. It often shows up as periodic restarts of pods OOM-killed by Kubernetes.
- **Reproduce:** `make run` (note the `-Xmx256m` flag in `build.gradle.kts`), then `make load S=05 V=broken`.
  Watch the Grafana heap panel. After the OOM, find `dumps/java_pid*.hprof`. Then restart and run `V=fixed`.
- **What you'll see:** a table of heap after 1/2/3 minutes, GC pause/s, p95 latency, and the time to OOM. Leave it as
  `(measured in Task 11)`.
- **Diagnose:**
  1. Grafana "JVM heap used vs max": the floor rises after every GC. That "rising floor" is the leak
     signature, as opposed to high-but-flat usage.
  2. "GC pause time per second" rises as the heap fills, because the GC works harder to free less. This is the
     "GC thrash" phase, where latency is terrible but nothing has crashed yet.
  3. A live histogram without a dump: `jcmd $(jps | awk '/FailureLabApplication/{print $1}') GC.class_histogram | head -15`.
     `[B` (byte arrays) dominates.
  4. The heap dump, from the automatic OOM or on demand with `jcmd <pid> GC.heap_dump dumps/manual.hprof` or
     `curl -o dumps/actuator.hprof localhost:8080/actuator/heapdump`. Open it in Eclipse MAT: "Leak Suspects"
     report → dominator tree → `MemoryLeakController.LEAKY_CACHE` retains ~X MB. Use "Path to GC Roots" to see
     *why* it can't be collected: a static field.
  5. Security note: `/actuator/heapdump` exposes secrets in memory, so never expose it publicly.
- **Root cause:** an unbounded collection reachable from a GC root (a static field). Other real-world
  versions: listeners never unregistered, `ThreadLocal`s in pooled threads, and caches keyed by unbounded input
  (user ids, URLs, query strings). Mention that metric tags with unbounded values leak the same way (cardinality explosion).
- **Fix:** a bounded Caffeine cache (size plus TTL). Explain why `maximumSize` bounds memory only if entries have a
  bounded size (otherwise use `maximumWeight`). Explain `-XX:+HeapDumpOnOutOfMemoryError` as a production default.
- **Takeaway:** "Every cache needs an eviction policy. A map that only grows is a leak with a delay."
  In code review, look for `static Map`, or a collection field on a singleton that only has `put`/`add` calls.
- **Further reading:** the Eclipse MAT docs, the Caffeine wiki (eviction), and the JDK `jcmd` docs.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(scenario-05): memory leak vs bounded Caffeine cache, k6 script, docs"
```

---

### Task 9: Scenario 06 — Catastrophic regex backtracking (ReDoS)

**Files:**
- Create: `scenarios/redos/CsvRecordValidator.java`, `scenarios/redos/RedosController.java`, `k6/06-redos.js`,
  `docs/scenarios/06-redos.md`
- Test: `scenarios/redos/CsvRecordValidatorTest.java`, `scenarios/redos/RedosScenarioIT.java`

**Interfaces:**
- Consumes: `AbstractLabIT.get/jsonLong`
- Produces:
  - `CsvRecordValidator.brokenMatches(String)`, `fixedMatches(String)`, `MAX_INPUT_LENGTH = 200`
  - `GET /scenarios/redos/{variant}?input=` → `{"valid":bool,"elapsedMs":N}`

- [ ] **Step 1: Write the failing validator test**

```java
package com.example.failurelab.scenarios.redos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvRecordValidatorTest {

    /** 33 commas then a non-digit: "almost" 11 fields, forcing the broken pattern to try every split. */
    static final String NEAR_MISS = ",".repeat(33) + "x";

    @Test
    void bothPatternsAcceptAValidElevenFieldRecord() {
        String record = "a,b,c,d,e,f,g,h,i,j,42";

        assertThat(CsvRecordValidator.brokenMatches(record)).isTrue();
        assertThat(CsvRecordValidator.fixedMatches(record)).isTrue();
    }

    @Test
    void brokenPatternBacktracksForHundredsOfMillisecondsOnANearMiss() {
        long start = System.nanoTime();
        boolean valid = CsvRecordValidator.brokenMatches(NEAR_MISS);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(valid).isFalse();
        assertThat(ms).as("broken regex on a %d-char input", NEAR_MISS.length()).isGreaterThan(500);
    }

    @Test
    void fixedPatternIsLinearEvenOnHugeInput() {
        String huge = ",".repeat(5000) + "x";

        long start = System.nanoTime();
        boolean valid = CsvRecordValidator.fixedMatches(huge);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(valid).isFalse();
        assertThat(ms).isLessThan(50);
    }

    @Test
    void brokenPatternWasAlsoWrongItAcceptsTwelveFields() {
        String twelveFields = "a,b,c,d,e,f,g,h,i,j,k,42";

        assertThat(CsvRecordValidator.brokenMatches(twelveFields)).isTrue();
        assertThat(CsvRecordValidator.fixedMatches(twelveFields)).isFalse();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*CsvRecordValidatorTest'`
Expected: FAIL to compile, because `CsvRecordValidator` is missing.

- [ ] **Step 3: Implement the validator**

```java
package com.example.failurelab.scenarios.redos;

import java.util.regex.Pattern;

/**
 * Validates "11 comma-separated fields, the last one numeric", e.g. {@code a,b,c,d,e,f,g,h,i,j,42}.
 *
 * Why not the textbook {@code ^(a+)+$}? Since JDK 9, java.util.regex memoizes simple greedy loops, so the
 * classic exponential examples run in ~0 ms on JDK 17. Bounded repetition like {@code {10}} is NOT
 * memoized, so this realistic-looking pattern backtracks polynomially: about 0.9 s at 31 chars and about
 * 14 s at 41 chars (measured on the lab machine).
 */
public final class CsvRecordValidator {

    public static final int MAX_INPUT_LENGTH = 200;

    // BROKEN: `.*` can also swallow commas, so there are a huge number of ways to carve the input into
    // 10 "field," chunks. On a near-miss the engine tries every one of them before giving up.
    private static final Pattern BROKEN = Pattern.compile("^(.*,){10}\\d+$");

    // FIXED: `[^,]*` cannot match a comma, so there is exactly ONE way to split the input. Linear time.
    private static final Pattern FIXED = Pattern.compile("^([^,]*,){10}\\d+$");

    private CsvRecordValidator() {
    }

    public static boolean brokenMatches(String line) {
        return BROKEN.matcher(line).matches();
    }

    public static boolean fixedMatches(String line) {
        return FIXED.matcher(line).matches();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests '*CsvRecordValidatorTest'`
Expected: PASS (4 tests). If `brokenPatternBacktracks...` finishes in under 500 ms on a faster machine,
raise the comma count in `NEAR_MISS` by 2 at a time until it takes about 1 s. Do **not** lower the threshold.

- [ ] **Step 5: Write the failing endpoint test**

```java
package com.example.failurelab.scenarios.redos;

import com.example.failurelab.support.AbstractLabIT;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RedosScenarioIT extends AbstractLabIT {

    @Test
    void fixedHandlesTheAttackPayloadInstantly() {
        long start = System.nanoTime();
        HttpResponse<String> response = get("/scenarios/redos/fixed?input=" + CsvRecordValidatorTest.NEAR_MISS);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"valid\":false");
        assertThat(elapsedMs(start)).isLessThan(200);
    }

    @Test
    void fixedAcceptsInputExactlyAtTheLimit() {
        String atLimit = "a".repeat(CsvRecordValidator.MAX_INPUT_LENGTH);

        assertThat(get("/scenarios/redos/fixed?input=" + atLimit).statusCode()).isEqualTo(200);
    }

    @Test
    void fixedRejectsInputOverTheLimitBeforeRunningTheRegex() {
        String overLimit = "a".repeat(CsvRecordValidator.MAX_INPUT_LENGTH + 1);

        HttpResponse<String> response = get("/scenarios/redos/fixed?input=" + overLimit);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("200 characters");
    }

    @Test
    void missingInputIsABadRequest() {
        assertThat(get("/scenarios/redos/fixed").statusCode()).isEqualTo(400);
        assertThat(get("/scenarios/redos/broken").statusCode()).isEqualTo(400);
    }

    @Test
    void brokenReportsHowLongTheRegexTook() {
        HttpResponse<String> response = get("/scenarios/redos/broken?input=a,b,c,d,e,f,g,h,i,j,42");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"valid\":true");
        assertThat(jsonLong(response.body(), "elapsedMs")).isGreaterThanOrEqualTo(0);
    }
}
```

- [ ] **Step 6: Run to verify it fails**

Run: `./gradlew test --tests '*RedosScenarioIT'`
Expected: FAIL with 404s.

- [ ] **Step 7: Implement the controller**

```java
package com.example.failurelab.scenarios.redos;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Scenario 06. Each attack request burns one CPU core for seconds, and keeps burning even after the
 * client gives up, because regex matching cannot be interrupted.
 */
@RestController
@RequestMapping("/scenarios/redos")
class RedosController {

    @GetMapping("/broken")
    ValidationResult broken(@RequestParam String input) {
        long start = System.nanoTime();
        boolean valid = CsvRecordValidator.brokenMatches(input);
        return new ValidationResult(valid, elapsedMs(start));
    }

    @GetMapping("/fixed")
    ValidationResult fixed(@RequestParam String input) {
        // Defence in depth: even a linear regex should not be fed unbounded input.
        if (input.length() > CsvRecordValidator.MAX_INPUT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "input exceeds " + CsvRecordValidator.MAX_INPUT_LENGTH + " characters");
        }
        long start = System.nanoTime();
        boolean valid = CsvRecordValidator.fixedMatches(input);
        return new ValidationResult(valid, elapsedMs(start));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    record ValidationResult(boolean valid, long elapsedMs) {
    }
}
```

- [ ] **Step 8: Run to verify it passes**

Run: `./gradlew test --tests '*RedosScenarioIT' --tests '*CsvRecordValidatorTest'`
Expected: PASS (9 tests).

- [ ] **Step 9: Write the k6 script**

`k6/06-redos.js`:
```js
// Scenario 06 - ReDoS. A few "attackers" send a 34-char near-miss; 1 canary pings.
// Broken: each request pins a CPU core for seconds, and the canary slows because the CPU is saturated.
// Fixed: microseconds per request.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, VARIANT, canary } from './lib.js';

export { canary };

const ATTACK = encodeURIComponent(','.repeat(33) + 'x');

export const options = {
  scenarios: {
    attack: { executor: 'constant-vus', vus: 8, duration: '45s', exec: 'attack' },
    canary: { executor: 'constant-vus', vus: 1, duration: '45s', exec: 'canary' },
  },
  thresholds: {
    'http_req_duration{name:canary}': ['p(95)<250'],
  },
};

export function attack() {
  const res = http.get(`${BASE_URL}/scenarios/redos/${VARIANT}?input=${ATTACK}`, {
    tags: { name: 'attack' },
    timeout: '120s',
  });
  check(res, { 'status 200': (r) => r.status === 200 });
}
```

- [ ] **Step 10: Write the scenario doc**

`docs/scenarios/06-redos.md` follows the template, with this content:
- **Symptom:** CPU at 100%, throughput near zero, and no errors in the logs. A handful of requests (not a flood)
  takes the service down. That's what makes it a *DoS*.
- **Reproduce:** `curl -s "localhost:8080/scenarios/redos/broken?input=$(printf ',%.0s' {1..33})x"` (a single
  request takes seconds). Then `make load S=06 V=broken` and `V=fixed`.
- **What you'll see:** a table of single-request latency by input length (21/26/31/33 chars → measured), CPU,
  and canary p95. Leave it as `(measured in Task 11)`. Use the planning measurements as a preview: 31 chars ≈ 0.9 s,
  41 chars ≈ 14 s.
- **Diagnose:**
  1. Grafana "CPU usage": process ≈ 1.0 while request rate is tiny. High CPU plus low throughput means the CPU is
     spent on something pathological.
  2. `make threaddump`: busy `http-nio-8080-exec-*` threads are `RUNNABLE` in
     `java.util.regex.Pattern$Loop.match` / `Pattern$CharProperty.match`, with `RedosController.broken` below.
     Take two dumps 5 s apart. If the *same* threads are in the same regex frames, that's your culprit.
  3. Optional: record with JFR (`jcmd <pid> JFR.start duration=30s filename=dumps/redos.jfr`), open it in JDK
     Mission Control, and look at "Hot Methods".
- **Root cause:** a backtracking regex engine and ambiguous sub-patterns. `(.*,){10}` can split the input in
  many ways. On a failed match the engine tries all of them. Explain why the JDK memoization (JDK 9+) defeats
  `(a+)+` but not bounded repetition, and why that means "it's fine on my JDK" is not a safe assumption.
  Note that matching cannot be interrupted, so request timeouts do not stop the CPU burn.
- **Fix:** remove the ambiguity (`[^,]*`), bound the input length *before* regex, and in general prefer a
  parser (`String.split(",", -1)` and a count) over a regex for structured data. Mention RE2/J as a
  linear-time engine for untrusted patterns. Note that the broken pattern was also **functionally wrong**:
  it accepted 12 fields, as the test shows.
- **Takeaway:** "Nested or repeated quantifiers over overlapping character classes plus untrusted input = CPU
  DoS." In code review, look for `.*` inside a repeated group, `(x+)+`, and alternations with overlap.
- **Further reading:** OWASP "Regular expression Denial of Service", the Cloudflare 2019 outage postmortem
  (a real ReDoS), RE2/J.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat(scenario-06): ReDoS with linear regex + length limit, k6 script, docs"
```

---

### Task 10: Cross-cutting documentation

**Files:**
- Create: `README.md`, `docs/architecture.md`, `docs/diagnosis-toolkit.md`, `docs/failure-taxonomy.md`, `docs/roadmap.md`

**Interfaces:**
- Consumes: every command, port, metric name and doc path defined in Tasks 1–9. Every one of them must match exactly.

- [ ] **Step 1: Write `README.md`**

Sections:
1. **What this is:** one paragraph, plus the learning goal (the spec §1 success criterion).
2. **Prerequisites:** Java 17, Docker, and optionally JDK Mission Control or Eclipse MAT.
3. **Quickstart:** `make up`, `make run`, open Grafana at http://localhost:3000 (the Failure Lab dashboard is home),
   `make load S=01 V=broken`, then `V=fixed`, then compare.
4. **Lab map:** the ASCII architecture diagram from the spec §3, plus a ports table: app 8080, Grafana 3000,
   Prometheus 9090, Toxiproxy API 8474, Postgres via proxy 15432 / direct 5432, WireMock via proxy 18080 / direct 8089.
5. **Scenarios:** a table of #, name, category, and one-line symptom, with each row linking to its doc.
6. **How to study a scenario:** read *Symptom* only → run broken → try to diagnose using the toolkit
   *before* reading further → then read Root cause/Fix → run fixed → compare.
7. **Make targets:** copied from the Makefile.
8. **Running the tests:** `make test` (needs Docker for Testcontainers). Explain that the tests prove each
   doc's claims.
9. **Doc index:** links to architecture, taxonomy, toolkit and roadmap.

- [ ] **Step 2: Write `docs/architecture.md`**

- The components and why each exists (Toxiproxy = real network faults; WireMock = a controllable dependency;
  Prometheus/Grafana = what an on-call engineer actually sees).
- The request path through Toxiproxy for the DB and the downstream.
- **The deliberate limits**, as a table: the setting, its value, the typical production value, and which
  scenario it makes visible. The settings are Tomcat threads 20, accept-count 100, Hikari 5 / 3 s / leak-detection 2 s,
  heap 256 MB, OSIV off, and timed-client 500 ms / 1 s.
- Code layout: one package per scenario, `common/` shared, and how to add a scenario (the checklist: package,
  controller with broken/fixed, IT test, k6 script, doc from the template, catalog entry in
  `ScenarioCatalogController`, dashboard panel if new metrics are needed).
- The test strategy (spec §7) and why the tests use real HTTP rather than MockMvc (thread pools only exist in a real server).

- [ ] **Step 3: Write `docs/failure-taxonomy.md`**

- **Where a request spends its time:** queueing for a thread → CPU work → waiting on a pool
  (connections) → network → the dependency's own time → GC pauses (stop-the-world, hitting everything).
  Each of these maps to a scenario.
- **The four ways an API fails:** resource exhaustion (threads, connections, memory, CPU, file
  descriptors), latency amplification (no timeouts, retries, N+1), errors (5xx, validation, serialization),
  and correctness under load (races, stale caches). There's a table mapping each to phase-1 and phase-2 scenarios.
- **The USE method** (Utilization, Saturation, Errors) for resources and the **RED method** (Rate, Errors,
  Duration) for endpoints. Each is mapped to the concrete Grafana panels in this lab.
- **Little's Law** (`L = λW`), with the worked example from scenario 01, and why it explains 01 and 03.
- **Cascading failure:** how 04 plus 01 combine, and why the canary endpoint pattern exists.
- The "latency is the new outage" idea, why p99 and not the average, and the coordinated-omission caveat for load testing.

- [ ] **Step 4: Write `docs/diagnosis-toolkit.md`**

For each tool, cover what question it answers, the exact command in this lab, and how to read the output:
- **Grafana/Prometheus:** each dashboard panel and the PromQL behind it, copied from `failure-lab.json`.
- **Actuator:** `/actuator/health`, `/actuator/metrics/{name}` (e.g.
  `curl -s localhost:8080/actuator/metrics/hikaricp.connections.pending`), `/actuator/threaddump`,
  `/actuator/heapdump` (with the security warning).
- **Thread dumps:** `make threaddump`, or `jcmd <pid> Thread.print` / `jstack`. How to read thread states
  (RUNNABLE, WAITING, TIMED_WAITING, BLOCKED), the "take 3 dumps 5 s apart" technique, and grep recipes.
- **Heap:** `jcmd <pid> GC.class_histogram`, `GC.heap_dump`, and Eclipse MAT (Leak Suspects, dominator tree,
  path to GC roots).
- **JFR:** `jcmd <pid> JFR.start duration=30s filename=dumps/rec.jfr` and JDK Mission Control (hot methods,
  lock contention, allocation).
- **Postgres:** `make psql`, `pg_stat_activity` (states, especially `idle in transaction`), `pg_locks`,
  `log_min_duration_statement`, and `EXPLAIN ANALYZE`.
- **Toxiproxy:** every `scripts/toxics.sh` command, and when to use each.
- **k6:** reading the end-of-test summary (p95, `http_req_failed`, thresholds, checks), and tags.
- **Logs:** correlation ids, i.e. going from a 500 body `correlationId` to the stack trace in the app log with
  `grep <id>`.

- [ ] **Step 5: Write `docs/roadmap.md`**

Phase 2+ scenarios, grouped by category. For each, give one line on the broken and fixed behavior:
deadlock, `synchronized` lock contention, `@Async`/common-pool starvation, missing index (seq scan on a large
table), row-lock waits, unbounded result set, retry storm (retries without backoff/jitter), no circuit
breaker, GC allocation churn, huge JSON payloads, an O(n²) algorithm, synchronous logging under load,
validation errors returning 500 instead of 400, oversized request bodies, JPA bidirectional recursion
(StackOverflowError), cache stampede, no rate limiting, and graceful shutdown dropping in-flight requests.
Mark the ones that need new infrastructure (e.g. Resilience4j for the circuit breaker; check that it's compatible with Boot 4 first).

- [ ] **Step 6: Verify that the links and commands in the docs are real**

Run:
```bash
grep -rhoE '\]\(([^)#]+)\)' README.md docs | sed -E 's/\]\(([^)]+)\)/\1/' | grep -v '^http' | sort -u
```
Check that every relative link printed exists (resolve paths relative to the linking file).
Run:
```bash
grep -rhoE 'make [a-z-]+' README.md docs | sort -u
```
Check that every target printed exists in the `Makefile`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "docs: README, architecture, failure taxonomy, diagnosis toolkit, roadmap"
```

---

### Task 11: End-to-end lab verification and measured numbers

**Files:**
- Modify: `docs/scenarios/01…06-*.md` (the "What you'll see" tables)

**Interfaces:**
- Consumes: everything.

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew test`
Expected: all tests pass. Record the count (it should be 36) in the commit message.

- [ ] **Step 2: Run every scenario against the real lab and record the numbers**

With `make up` and `make run` active, for S in 01…06 and V in broken, fixed:
- Scenario 04 only: first run `make toxic-latency TARGET=downstream MS=30000`, and afterwards `make toxic-reset`.
- Scenario 05 broken: expect an OOM. Confirm that `dumps/java_pid*.hprof` exists, then restart the app with `make run`
  before running the next scenario.
- Run `make load S=$S V=$V` and copy the k6 summary's key lines (p95 per tag, `http_req_failed`, checks).
- Read the matching Grafana panel values.

Fill in each doc's "What you'll see" table with these **measured** values, and replace `(measured in Task 11)`.
If a scenario does NOT show its documented behavior, stop and debug it (superpowers:systematic-debugging)
rather than editing the doc to match.

- [ ] **Step 3: Check that no placeholders remain**

Run: `grep -rn "measured in Task 11\|TODO\|TBD" docs README.md src`
Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "docs: record measured results for all six scenarios"
```
