[日本語版はこちら / Japanese](README-ja.md)

# syslenz4j

Java binding for [syslenz](https://github.com/opaopa6969/syslenz) — zero-dependency JVM metrics exporter with Watch API.

Collects **JVM internals via MXBeans** and exposes them over a TCP port compatible with `syslenz --connect`. Custom application metrics and threshold-based alerting are built in.

---

## Table of Contents

- [Quick Start](#quick-start)
- [Installation](#installation)
- [Core Concepts](#core-concepts)
- [Collected Metrics](#collected-metrics)
- [Watch API](#watch-api)
- [Protocol](#protocol)
- [Spring Boot Integration](#spring-boot-integration)
- [Security Notes](#security-notes)
- [Requirements](#requirements)

---

## Quick Start

```java
import org.unlaxer.infra.syslenz4j.SyslenzAgent;
import org.unlaxer.infra.syslenz4j.Severity;

public class MyApp {
    public static void main(String[] args) {
        // Start TCP server (daemon thread, non-blocking)
        SyslenzAgent.startServer(9100);

        // Register custom metrics
        SyslenzAgent.registry().gauge("queue_size", () -> myQueue.size());
        SyslenzAgent.registry().counter("requests_total", requestCounter::get);

        // Alert when heap usage exceeds 1 GiB
        SyslenzAgent.watch("heap_used")
            .greaterThan(1_073_741_824L)
            .severity(Severity.WARNING)
            .cooldown(30_000)
            .onFire(event -> log.warn("Heap high: {}", event.message()))
            .onResolve(event -> log.info("Heap recovered"))
            .register();

        // ... your application ...
    }
}
```

Connect from terminal:

```bash
syslenz --connect localhost:9100
```

---

## Installation

### Maven

```xml
<dependency>
    <groupId>org.unlaxer.infra</groupId>
    <artifactId>syslenz4j</artifactId>
    <version>1.1.1</version>
</dependency>
```

### Gradle

```kotlin
implementation("org.unlaxer.infra:syslenz4j:1.1.1")
```

### Build from Source

```bash
git clone https://github.com/opaopa6969/syslenz4j.git
cd syslenz4j
mvn package -DskipTests
```

---

## Core Concepts

syslenz4j has three entry points, all accessed through `SyslenzAgent`:

| Entry point | Purpose |
|------------|---------|
| `startServer(port)` | TCP server, `syslenz --connect` compatible |
| `printSnapshot()` | One-shot stdout export (plugin mode) |
| `registry()` | Register custom application metrics |
| `watch(metric)` | Fluent builder for threshold alerts |

### SyslenzAgent

The singleton facade. Manages one `MetricRegistry`, one `WatchRegistry`, and at most one `SyslenzServer`. Thread-safe; `startServer()` is idempotent.

### MetricRegistry

Holds supplier functions for custom metrics. Three kinds:

- **gauge** — any `Number`-returning supplier (queue depth, cache size, …)
- **counter** — monotonically increasing `Number` (request count, bytes written, …)
- **text** — `String` supplier (version, environment label, …)

All registered metrics are included in every snapshot alongside the built-in JVM metrics.

### JvmCollector

Reads all metrics from standard JDK `java.lang.management` MXBeans. No native code, no agents, no external libraries. See [Collected Metrics](#collected-metrics) for the full list.

### SyslenzServer

Listens on a TCP port. Accepts `SNAPSHOT\n` commands and replies with a single-line ProcEntry JSON. Runs on a daemon thread so it does not prevent JVM shutdown.

### Watch API

Fluent condition builder that fires callbacks when a metric crosses a threshold. See [Watch API](#watch-api).

---

## Collected Metrics

All metrics are collected on each `SNAPSHOT` request.

| Name | Type | Source MXBean | Description |
|------|------|--------------|-------------|
| `heap_used` | Bytes | MemoryMXBean | Current heap usage |
| `heap_committed` | Bytes | MemoryMXBean | Heap committed by OS |
| `heap_max` | Bytes | MemoryMXBean | Maximum heap (-Xmx) |
| `non_heap_used` | Bytes | MemoryMXBean | Non-heap (metaspace, code cache) |
| `non_heap_committed` | Bytes | MemoryMXBean | Non-heap committed by OS |
| `gc_<name>_count` | Integer | GarbageCollectorMXBean | GC count per collector |
| `gc_<name>_time` | Duration (s) | GarbageCollectorMXBean | GC wall time per collector |
| `gc_total_count` | Integer | GarbageCollectorMXBean | Total GC count |
| `gc_total_time` | Duration (s) | GarbageCollectorMXBean | Total GC time |
| `thread_count` | Integer | ThreadMXBean | Live thread count |
| `thread_peak` | Integer | ThreadMXBean | Peak live threads since start |
| `thread_daemon` | Integer | ThreadMXBean | Daemon thread count |
| `thread_deadlocked` | Integer | ThreadMXBean | Threads in deadlock (0 = healthy) |
| `uptime` | Duration (s) | RuntimeMXBean | JVM uptime |
| `vm_name` | Text | RuntimeMXBean | JVM implementation name |
| `available_processors` | Integer | OperatingSystemMXBean | CPU count available to JVM |
| `system_load_average` | Float | OperatingSystemMXBean | 1-min system load average |
| `process_cpu_load` | Float (%) | com.sun.management | Process CPU usage 0–100% |
| `process_cpu_time` | Duration (s) | com.sun.management | Total CPU time used |
| `classes_loaded` | Integer | ClassLoadingMXBean | Currently loaded classes |
| `classes_total_loaded` | Integer | ClassLoadingMXBean | Total classes loaded since start |
| `classes_unloaded` | Integer | ClassLoadingMXBean | Total classes unloaded since start |
| `buffer_direct_used` | Bytes | BufferPoolMXBean | Direct buffer memory |
| `buffer_direct_capacity` | Bytes | BufferPoolMXBean | Direct buffer capacity |
| `buffer_direct_count` | Integer | BufferPoolMXBean | Number of direct buffers |
| `buffer_mapped_used` | Bytes | BufferPoolMXBean | Memory-mapped buffer memory |
| `buffer_mapped_capacity` | Bytes | BufferPoolMXBean | Memory-mapped buffer capacity |
| `buffer_mapped_count` | Integer | BufferPoolMXBean | Number of memory-mapped buffers |

Custom metrics registered via `MetricRegistry` are prefixed `app_` (e.g. `app_queue_size`).

---

## Watch API

Define threshold conditions with a fluent builder. Callbacks fire when a condition transitions FIRING → RESOLVED or vice versa.

```java
// Single threshold
SyslenzAgent.watch("heap_used")
    .greaterThan(1_073_741_824L)    // > 1 GiB
    .severity(Severity.WARNING)
    .cooldown(60_000)               // don't re-fire within 60 s
    .onFire(event -> alertService.send(event.message()))
    .onResolve(event -> alertService.resolve(event.metricName()))
    .register();

// Range check
SyslenzAgent.watch("process_cpu_load")
    .outsideRange(0.0, 90.0)
    .severity(Severity.CRITICAL)
    .onFire(event -> ops.page("CPU anomaly: " + event.value()))
    .register();

// Compound: both conditions must hold simultaneously
SyslenzAgent.watch("app_queue_size")
    .greaterThan(10_000)
    .and("app_error_rate").greaterThan(5.0)
    .severity(Severity.CRITICAL)
    .onFire(event -> scaleOut())
    .register();
```

### Operators

| Method | Meaning |
|--------|---------|
| `greaterThan(v)` | value > v |
| `lessThan(v)` | value < v |
| `greaterThanOrEqual(v)` | value >= v |
| `lessThanOrEqual(v)` | value <= v |
| `equalTo(v)` | value ≈ v (epsilon 0.0001) |
| `notEqualTo(v)` | value ≇ v |
| `outsideRange(min, max)` | value < min \|\| value > max |
| `insideRange(min, max)` | min <= value <= max |

### Severity levels

| Value | Meaning |
|-------|---------|
| `Severity.INFO` | Noteworthy, not a problem |
| `Severity.WARNING` | Attention needed |
| `Severity.CRITICAL` | Immediate action required |

### WatchEvent fields

```java
event.metricName()  // String — which metric fired
event.value()       // double — value at fire time
event.severity()    // Severity
event.state()       // WatchEvent.State.FIRING | RESOLVED
event.timestamp()   // Instant
event.message()     // human-readable summary string
```

### Self-driven evaluation

By default, watches are evaluated only when a client requests a `SNAPSHOT`. To fire alerts even while no syslenz client is connected (e.g. overnight), start the built-in evaluator:

```java
SyslenzAgent.evaluateEvery(Duration.ofSeconds(10));  // daemon thread
SyslenzAgent.stopEvaluator();                        // stop it again
```

### Alerts in snapshots

Firing watches are included in every snapshot response as an `alerts` array. A syslenz TUI connected via `--connect` shows them in its status bar (`[!!1 CRIT]`) and as severity badges on the `jvm` source.

> **Note on compound conditions**: The `.and()` fluent chain works — `CompoundCondition.greaterThan()` / `lessThan()` / `greaterThanOrEqual()` / `lessThanOrEqual()` all return the parent `WatchCondition`, so the chain continues normally. All four secondary operators are evaluated with the same threshold semantics as the primary condition. Watch callbacks now fire automatically: `WatchRegistry.evaluate()` is wired into the `SNAPSHOT` path, so every snapshot request evaluates all watches (use `evaluateEvery(Duration)` to also evaluate while no client is connected).

---

## Protocol

The TCP server uses a line-oriented text protocol, mirroring `syslenz --serve`:

```
Client → Server:  SNAPSHOT\n
Server → Client:  <single-line Snapshot JSON>\n   (then the server closes the connection)
```

Snapshot JSON structure (the shape `syslenz --connect` parses):

```json
{
  "timestamp": "2026-06-12T01:02:03.456Z",
  "entries": {
    "jvm": {
      "source": "jvm/pid-12345",
      "fields": [
        {"name": "heap_used", "value": {"Bytes": 524288000}, "unit": null, "description": "Current heap memory usage"},
        {"name": "thread_count", "value": {"Integer": 42}, "unit": "count", "description": "Current live thread count"}
      ]
    }
  },
  "alerts": [
    {"name": "queue_size", "severity": "critical", "value": 250.0,
     "message": "[critical] queue_size > 100 (value: 250.00)",
     "condition": "> 100", "since": "2026-06-12T01:02:00Z"}
  ]
}
```

One request per connection: the syslenz client reads the response until EOF, so the server closes after responding (`QUIT` or an empty line also closes). Unknown commands get an `ERROR unknown command: ...` response and the connection is closed. The `alerts` key is omitted when no watch is firing.

Output hygiene (protects the syslenz TUI):

- Metrics whose `Float`/`Duration` value is NaN or ±Infinity are omitted — they have no valid JSON representation and would break strict parsers.
- ANSI escape sequences in text values are stripped, and remaining control characters are replaced with spaces, so terminal output can never be corrupted by metric content.

---

## Spring Boot Integration

```java
@Configuration
public class SyslenzConfig {

    @Value("${syslenz.port:9100}")
    private int port;

    @Bean
    public SyslenzLifecycle syslenzLifecycle(MeterRegistry meterRegistry) {
        return new SyslenzLifecycle(port, meterRegistry);
    }
}

@Component
public class SyslenzLifecycle implements SmartLifecycle {

    private final int port;
    private final MeterRegistry meterRegistry;
    private volatile boolean running;

    public SyslenzLifecycle(int port, MeterRegistry meterRegistry) {
        this.port = port;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void start() {
        // Bridge Micrometer counters to syslenz4j
        SyslenzAgent.registry().gauge("http_requests_active",
            () -> meterRegistry.find("http.server.requests").gauge() != null
                ? meterRegistry.find("http.server.requests").gauge().value()
                : 0.0
        );

        SyslenzAgent.watch("process_cpu_load")
            .greaterThan(85.0)
            .severity(Severity.WARNING)
            .onFire(event -> log.warn("High CPU: {}%", event.value()))
            .register();

        SyslenzAgent.startServer(port);
        running = true;
    }

    @Override
    public void stop() {
        SyslenzAgent.stopServer();
        running = false;
    }

    @Override public boolean isRunning() { return running; }
}
```

---

## Security Notes

- `SyslenzServer` binds to loopback (`127.0.0.1`) by default. To expose it on other interfaces, pass an explicit bind address (`startServer(port, "0.0.0.0")`) — and **do not expose the port to the public internet**.
- There is no authentication on the TCP endpoint. Anyone who can reach the port can read JVM internals (heap sizes, thread counts, CPU usage, deadlocks).
- In container environments, publish the port only to internal networks (e.g. Docker `--network internal`, Kubernetes `ClusterIP`).

---

## MCP (volta participation)

syslenz4j participates in the volta MCP facade (`https://mcp.unlaxer.org/mcp`) as **skill-only** — no MCP server is hosted (a standalone JVM process can only monitor itself, not the target app). Instead, the embed procedure is distributed as a **skill**.

- **Namespace**: `syslenz4j`
- **Skill**: `embed-syslenz4j` (embed steps, metrics catalog, Watch API, protocol spec)
- **Source**: volta-mcp repo `docs/skills/syslenz4j__embed-syslenz4j/SKILL.md`
- **Access**: MCP tool `skill__resolve(goal="embed JVM monitoring in a Java app")` or `skill__export(name="syslenz4j__embed-syslenz4j")`
- **Design**: [docs/mcp/DESIGN.md](docs/mcp/DESIGN.md)
- **Status**: [docs/mcp/STATUS.md](docs/mcp/STATUS.md)

Agents read the skill, generate embed code, and observe metrics via syslenz (namespace `syslenz`, already in catalog). syslenz4j covers JVM metrics; syslenz covers OS/network — complementary.

---

## Requirements

- **Java 17** or later (uses `record`, sealed `switch` expressions)
- No external runtime dependencies — only standard JDK `java.lang.management` APIs
