[日本語版](getting-started-ja.md)

# Getting Started

This guide walks you through adding syslenz4j to a Java project, connecting it to the syslenz daemon, and integrating it with Spring Boot.

---

## Prerequisites

- Java 17 or later
- Maven 3.6+ or Gradle 7+
- [syslenz](https://github.com/opaopa6969/syslenz) installed (for the `--connect` command)

---

## Step 1 — Add the Dependency

### Maven

```xml
<dependency>
    <groupId>org.unlaxer.infra</groupId>
    <artifactId>syslenz4j</artifactId>
    <version>1.1.0</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("org.unlaxer.infra:syslenz4j:1.1.0")
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'org.unlaxer.infra:syslenz4j:1.1.0'
}
```

syslenz4j has no transitive dependencies. It uses only the JDK standard library.

---

## Step 2 — Start the Server

Add one line to your application's entry point:

```java
import org.unlaxer.infra.syslenz4j.SyslenzAgent;

public class Main {
    public static void main(String[] args) {
        SyslenzAgent.startServer(9100);

        // ... rest of your application ...
    }
}
```

`startServer()` is non-blocking — it starts a daemon thread and returns immediately. The JVM will shut down normally even with the server running.

---

## Step 3 — Connect syslenz

From a terminal:

```bash
syslenz --connect localhost:9100
```

You should see a live dashboard of JVM metrics: heap usage, GC pressure, thread counts, CPU load, and more.

---

## Step 4 — Register Custom Metrics

```java
import org.unlaxer.infra.syslenz4j.SyslenzAgent;

// Anywhere after SyslenzAgent.startServer() is called:

// Gauge — reads the current value on each snapshot
SyslenzAgent.registry().gauge("queue_size", () -> taskQueue.size());
SyslenzAgent.registry().gauge("cache_hit_rate", () -> cache.hitRate());

// Counter — monotonically increasing value
SyslenzAgent.registry().counter("requests_total", requestCounter::get);
SyslenzAgent.registry().counter("errors_total", errorCounter::get);

// Text — labels, version strings, environment markers
SyslenzAgent.registry().text("app_version", () -> "2.3.1");
SyslenzAgent.registry().text("environment", () -> System.getenv("APP_ENV"));
```

Custom metrics appear in the dashboard with the `app_` prefix (e.g. `app_queue_size`).

---

## Step 5 — Set Up Watch Conditions

Define threshold alerts before or after starting the server:

```java
import org.unlaxer.infra.syslenz4j.SyslenzAgent;
import org.unlaxer.infra.syslenz4j.Severity;

// Alert when heap exceeds 80% of max
// (compute heap_used / heap_max in a custom gauge)
SyslenzAgent.registry().gauge("heap_used_pct",
    () -> {
        var mem = java.lang.management.ManagementFactory.getMemoryMXBean();
        var heap = mem.getHeapMemoryUsage();
        return heap.getMax() > 0 ? (heap.getUsed() * 100.0 / heap.getMax()) : 0.0;
    }
);

SyslenzAgent.watch("heap_used_pct")
    .greaterThan(80.0)
    .severity(Severity.WARNING)
    .cooldown(30_000)                          // don't re-fire within 30 s
    .onFire(event -> log.warn("Heap high: {}%", event.value()))
    .onResolve(event -> log.info("Heap recovered: {}%", event.value()))
    .register();

SyslenzAgent.watch("thread_deadlocked")
    .greaterThan(0.0)
    .severity(Severity.CRITICAL)
    .onFire(event -> pagerDuty.trigger("Deadlock detected!"))
    .register();
```

> **Note**: Watch callbacks do not fire automatically in v1.1.0 because `WatchRegistry.evaluate()` is not yet wired into the snapshot path. This is a known issue tracked in [GitHub #3](https://github.com/opaopa6969/syslenz4j/issues/3). In the meantime, call `SyslenzAgent.watches().evaluate(metricsMap)` manually if needed.

---

## Plugin Mode (One-Shot Stdout)

If you're running a short-lived batch job and want to emit metrics once to stdout:

```java
SyslenzAgent.printSnapshot();
```

This prints a single ProcEntry JSON line to stdout. You can pipe it to syslenz or process it with any JSON tool.

---

## Spring Boot Integration

### Add Configuration

```java
// src/main/java/com/example/config/SyslenzConfig.java
package com.example.config;

import org.unlaxer.infra.syslenz4j.SyslenzAgent;
import org.unlaxer.infra.syslenz4j.Severity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class SyslenzLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SyslenzLifecycle.class);

    @Value("${syslenz.port:9100}")
    private int port;

    private final MeterRegistry meterRegistry;
    private volatile boolean running;

    public SyslenzLifecycle(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void start() {
        // Bridge Micrometer gauges into syslenz4j
        SyslenzAgent.registry().gauge("http_server_active_requests",
            () -> {
                var gauge = meterRegistry.find("http.server.requests").gauge();
                return gauge != null ? gauge.value() : 0.0;
            }
        );

        SyslenzAgent.registry().text("spring_profiles",
            () -> String.join(",",
                org.springframework.core.env.StandardEnvironment.class
                    .cast(null) != null ? new String[]{} : new String[]{"unknown"}
            )
        );

        // Watch conditions
        SyslenzAgent.watch("process_cpu_load")
            .greaterThan(85.0)
            .severity(Severity.WARNING)
            .cooldown(60_000)
            .onFire(event -> log.warn("High CPU load: {}%", String.format("%.1f", event.value())))
            .register();

        SyslenzAgent.watch("thread_deadlocked")
            .greaterThan(0.0)
            .severity(Severity.CRITICAL)
            .onFire(event -> log.error("DEADLOCK DETECTED — {} threads", (int) event.value()))
            .register();

        SyslenzAgent.startServer(port);
        running = true;
        log.info("syslenz4j started on port {}", port);
    }

    @Override
    public void stop() {
        SyslenzAgent.stopServer();
        running = false;
        log.info("syslenz4j stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;  // start last, stop first
    }
}
```

### application.properties

```properties
# syslenz monitoring port (default: 9100)
syslenz.port=9100
```

### Verify

Start your Spring Boot application and run:

```bash
syslenz --connect localhost:9100
```

You should see both JVM metrics and any custom metrics you registered.

---

## Minimal Example (No Framework)

```java
import org.unlaxer.infra.syslenz4j.SyslenzAgent;
import org.unlaxer.infra.syslenz4j.Severity;
import java.util.concurrent.atomic.AtomicLong;

public class App {

    static final AtomicLong requestCount = new AtomicLong();
    static final AtomicLong errorCount = new AtomicLong();

    public static void main(String[] args) throws InterruptedException {
        // Start metrics server
        SyslenzAgent.startServer(9100);

        // Register custom metrics
        SyslenzAgent.registry().counter("requests_total", requestCount::get);
        SyslenzAgent.registry().counter("errors_total", errorCount::get);
        SyslenzAgent.registry().text("version", () -> "1.0.0");

        // Watch for error spike
        SyslenzAgent.watch("app_errors_total")
            .greaterThan(100)
            .severity(Severity.WARNING)
            .onFire(event -> System.err.println("Error spike: " + event.value()))
            .register();

        // Simulate work
        while (true) {
            requestCount.incrementAndGet();
            Thread.sleep(100);
        }
    }
}
```

---

## Troubleshooting

**Port already in use**: Change the port number or check with `lsof -i :9100`.

**syslenz cannot connect**: Verify the server started (look for no exception from `startServer()`), and check firewall rules.

**No custom metrics visible**: Ensure `registry().gauge(...)` is called after or before `startServer()` — both work. Custom metrics are collected on each `SNAPSHOT` request, not at registration time.

**Watch callbacks not firing**: This is a known issue in v1.1.0. See [GitHub #3](https://github.com/opaopa6969/syslenz4j/issues/3).
