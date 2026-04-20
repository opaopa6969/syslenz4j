# Changelog

All notable changes to syslenz4j are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Published to Maven Central as `org.unlaxer.infra:syslenz4j`.

---

## [1.1.1] - 2026-04-19

### Fixed

- **`CompoundCondition.greaterThan()` returned `null`** — broke the fluent chain after `.and(metric)`. Now returns the parent `WatchCondition` so the chain continues normally. Also added `lessThan`, `greaterThanOrEqual`, `lessThanOrEqual` overloads to `CompoundCondition` for completeness.
- **`WatchRegistry.evaluate()` was dead code** — `SyslenzServer.collectSnapshot()` now builds a metric-value map from every JVM + custom metric snapshot and passes it to `WatchRegistry.evaluate()`. Watch callbacks fire automatically on every `SNAPSHOT` request.
- **`SyslenzAgent.startServer(port, String bindAddress)` overload added** — allows binding to `127.0.0.1` for localhost-only exposure. The existing `startServer(port)` is unchanged and still binds to `0.0.0.0`.

---

## [1.1.0] - 2026-04-17

### Added

- **Watch API** — fluent condition builder for threshold-based alerting
  - `SyslenzAgent.watch(metricName)` returns a `WatchCondition` builder
  - Operators: `greaterThan`, `lessThan`, `greaterThanOrEqual`, `lessThanOrEqual`, `equalTo`, `notEqualTo`, `outsideRange`, `insideRange`
  - Severity levels: `INFO`, `WARNING`, `CRITICAL`
  - Cooldown support: `.cooldown(ms)` prevents re-fire within the window
  - Callbacks: `.onFire(Consumer<WatchEvent>)` and `.onResolve(Consumer<WatchEvent>)`
  - Compound conditions: `.and(metricName)` for multi-metric AND logic
  - `WatchEvent` record with `metricName`, `value`, `severity`, `state`, `timestamp`, `message`
  - `WatchRegistry` manages registered conditions and evaluates them against metric snapshots
  - `SyslenzAgent.clearWatches()` for test teardown
- **`SyslenzAgent.stopServer()`** — graceful server shutdown, primarily for testing
- `Severity` enum with `INFO`, `WARNING`, `CRITICAL`; carries `label()`, `description()`, `descriptionJa()`
- Correct Maven coordinates published to Maven Central: `org.unlaxer.infra:syslenz4j:1.1.0`
- Java 17 minimum requirement enforced in `pom.xml` (`maven.compiler.source/target = 17`)

### Changed

- GroupId changed from `io.syslenz` to `org.unlaxer.infra`
- ArtifactId changed from `syslenz-java` to `syslenz4j`
- Package changed from `io.syslenz` to `org.unlaxer.infra.syslenz4j`

### Known Issues

- `WatchRegistry.evaluate()` is not called from `SyslenzServer.collectSnapshot()` — Watch callbacks do not fire automatically in this release (dead code path). Fix planned for v1.2.0.
- `CompoundCondition.greaterThan()` returns `null`, breaking the fluent chain after `.and(metric)`. Only `GREATER_THAN` and `LESS_THAN` work for the secondary condition. Fix planned for v1.2.0.
- `SyslenzServer` binds to `0.0.0.0`; no bind-address configuration available yet.

---

## [1.0.0] - 2026-04-10

### Added

- **`SyslenzAgent`** — main entry point with server mode and plugin mode
  - `startServer(port)` — starts TCP server on daemon thread (idempotent)
  - `printSnapshot()` — writes one ProcEntry JSON line to stdout
  - `registry()` — access to `MetricRegistry`
- **`JvmCollector`** — collects JVM metrics via `java.lang.management` MXBeans
  - Heap and non-heap memory (MemoryMXBean)
  - GC count and time per collector (GarbageCollectorMXBean)
  - Thread count, peak, daemon, deadlock detection (ThreadMXBean)
  - Uptime, VM name (RuntimeMXBean)
  - System load average, available processors, process CPU (OperatingSystemMXBean)
  - Loaded/unloaded class counts (ClassLoadingMXBean)
  - Direct and mapped buffer pool usage (BufferPoolMXBean)
- **`MetricRegistry`** — register custom gauge, counter, and text metrics
- **`SyslenzServer`** — TCP server implementing `SNAPSHOT\n` → ProcEntry JSON protocol
- **`JsonExporter`** — serializes metrics to syslenz ProcEntry JSON format (zero external deps)
- Zero external runtime dependencies — JDK standard library only
- Maven Central publishing via `central-publishing-maven-plugin`
- CI/CD via GitHub Actions
