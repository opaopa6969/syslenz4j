# Changelog

All notable changes to syslenz4j are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Published to Maven Central as `org.unlaxer.infra:syslenz4j`.

---

## [Unreleased]

### Fixed

- **TCP responses now use the Snapshot wire format** — `syslenz --connect` parses `{"timestamp", "entries": {...}}`, but the server sent a bare ProcEntry, so the real client could never parse it. Responses are now wrapped as `{"timestamp": <ISO 8601>, "entries": {"jvm": <ProcEntry>}}`. Stdout/plugin mode (`printSnapshot()`) still emits the bare ProcEntry.
- **One request per connection** — the syslenz client reads responses until EOF, so keeping the connection open stalled it until its read timeout. The server now closes after responding, mirroring `syslenz --serve`. `QUIT` or an empty line also closes.
- **NaN/Infinity metrics no longer corrupt the JSON** — `Float`/`Duration` metrics with non-finite values (e.g. a gauge dividing by zero) produced literals like `{"Float": NaN}` that strict JSON parsers reject, breaking the whole snapshot. Such metrics are now omitted.
- **Control characters can no longer corrupt the syslenz TUI** — ANSI escape sequences in text metrics are stripped and remaining control characters are replaced with spaces. Previously they were `\uXXXX`-escaped (valid JSON), but the client unescaped them and wrote raw ESC bytes to the terminal.
- **Publishing pipeline aligned to Maven Central (Central Portal)** — `publish.yml` was configured for GitHub Packages (`server-id: github`, `GITHUB_TOKEN`) while `pom.xml` targeted OSSRH/Central, so `mvn deploy` could never succeed. The workflow now targets the Central Publisher Portal: `server-id: central` with `MAVEN_USERNAME`/`MAVEN_TOKEN` (Portal user token) and `GPG_PRIVATE_KEY`/`GPG_PASSPHRASE` for signing. The legacy OSSRH `<distributionManagement>` block was removed (the `central-publishing-maven-plugin` handles deployment). Resolves #8.
- **An incompletely configured watch can no longer break the whole monitoring endpoint** — `WatchCondition.evaluate()` and `CompoundCondition.evaluate()` switched on `operator` without a null check, so a condition registered without ever calling an operator method (including `.and(metric)` with no operator chosen) threw a `NullPointerException` during evaluation. Because `WatchRegistry.evaluate()` had no per-entry isolation, that one bad watch aborted the whole evaluation loop: `SNAPSHOT` responses came back empty (the connection was closed without a response) and, on the `evaluateEvery(...)` path, every watch registered after it silently stopped firing. Both `evaluate()` methods now treat a missing operator as not-satisfied (fail-safe, consistent with the `default -> false` branch), `WatchRegistry.evaluate()` contains any per-condition failure to that condition, and `register()` prints a `stderr` warning naming the metric. `register()` still does not throw, so a watch misconfiguration cannot take down the monitored application. No public signatures changed. Resolves #22.
- **Compound watch secondary `>=` / `<=` are now evaluated** — `WatchCondition.CompoundCondition.evaluate()` only handled `GREATER_THAN` / `LESS_THAN` and fell through to `default -> true` for the `greaterThanOrEqual` / `lessThanOrEqual` overloads, so a compound watch could fire on the primary metric alone (false-positive alerts). All four secondary operators now use the same threshold semantics as the primary condition, and the unreachable `default` branch is fail-safe (`false`). The fluent chaining API is unchanged. Resolves #20.
- **Unbounded command-line reads no longer allow heap exhaustion DoS** — `handleClient` used `BufferedReader.readLine()`, which buffers until a newline arrives. A client that connects (e.g. against a `0.0.0.0` bind) and sends non-newline bytes without ever terminating the line could grow the buffer without limit during the `SO_TIMEOUT` (30s) window. Command lines are now capped at `MAX_COMMAND_BYTES` (1024); when the limit is hit the connection is closed immediately without a response (denying an attacker an amplification channel). Legitimate commands are far shorter than the limit and are unaffected.

### Added

- **Alerts in snapshots** — firing watches are exported as a top-level `alerts` array (`name`, `severity`, `value`, `message`, `condition`, `since`); the key is omitted when nothing is firing. syslenz (with the matching change) displays them in the TUI status bar and sidebar badges.
- **`SyslenzAgent.evaluateEvery(Duration)` / `stopEvaluator()`** — self-driven watch evaluation on a daemon thread, so alerts fire even when no client is connected (previously watches were only evaluated on `SNAPSHOT` requests).

---

## [1.1.1] - 2026-04-19

### Fixed

- **`CompoundCondition.greaterThan()` returned `null`** — broke the fluent chain after `.and(metric)`. Now returns the parent `WatchCondition` so the chain continues normally. Also added `lessThan`, `greaterThanOrEqual`, `lessThanOrEqual` overloads to `CompoundCondition` for completeness.
- **`WatchRegistry.evaluate()` was dead code** — `SyslenzServer.collectSnapshot()` now builds a metric-value map from every JVM + custom metric snapshot and passes it to `WatchRegistry.evaluate()`. Watch callbacks fire automatically on every `SNAPSHOT` request.
- **`SyslenzAgent.startServer(port, String bindAddress)` overload added** — allows binding to an explicit address. The existing `startServer(port)` now binds to `127.0.0.1` (loopback only) by default, instead of `0.0.0.0`.

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
