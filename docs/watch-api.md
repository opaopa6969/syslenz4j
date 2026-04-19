[日本語版](watch-api-ja.md)

# Watch API

The Watch API lets you define threshold conditions on any metric and receive callbacks when the condition fires or resolves. It is built on a fluent builder that produces a `WatchCondition`, which is registered in `WatchRegistry`.

---

## Overview

```
SyslenzAgent.watch("heap_used")     ← choose metric
    .greaterThan(1_073_741_824L)    ← define condition
    .severity(Severity.WARNING)     ← set severity
    .cooldown(60_000)               ← min ms between re-fires
    .onFire(event -> ...)           ← callback when condition becomes true
    .onResolve(event -> ...)        ← callback when condition clears
    .register();                    ← commit to WatchRegistry
```

Each call to `register()` creates an independent watch with its own state (firing / not-firing) and cooldown timer.

---

## Full Example

```java
import org.unlaxer.infra.syslenz4j.SyslenzAgent;
import org.unlaxer.infra.syslenz4j.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AlertSetup {

    private static final Logger log = LoggerFactory.getLogger(AlertSetup.class);

    public static void configure() {

        // ── Heap pressure ──────────────────────────────────────────────────
        SyslenzAgent.watch("heap_used")
            .greaterThan(800_000_000L)          // > 800 MB
            .severity(Severity.WARNING)
            .cooldown(30_000)
            .onFire(event -> log.warn("[HEAP] {} bytes used — {}", event.value(), event.message()))
            .onResolve(event -> log.info("[HEAP] recovered — {} bytes", event.value()))
            .register();

        // ── Deadlock detection ─────────────────────────────────────────────
        SyslenzAgent.watch("thread_deadlocked")
            .greaterThan(0.0)
            .severity(Severity.CRITICAL)
            .cooldown(0)                        // always re-fire; deadlock is an emergency
            .onFire(event -> {
                log.error("[DEADLOCK] {} threads deadlocked!", (int) event.value());
                alertingService.triggerPagerDuty(event.message());
            })
            .register();

        // ── CPU range check ────────────────────────────────────────────────
        SyslenzAgent.watch("process_cpu_load")
            .outsideRange(0.0, 90.0)
            .severity(Severity.WARNING)
            .cooldown(60_000)
            .onFire(event -> log.warn("[CPU] out of range: {}%", String.format("%.1f", event.value())))
            .register();

        // ── Compound: queue overflow + errors ─────────────────────────────
        SyslenzAgent.watch("app_queue_size")
            .greaterThan(10_000)
            .and("app_error_rate").greaterThan(5.0)
            .severity(Severity.CRITICAL)
            .cooldown(120_000)
            .onFire(event -> scaleOutService.requestScaleOut())
            .register();
    }
}
```

---

## Builder Reference

### `watch(metricName)`

```java
static WatchCondition SyslenzAgent.watch(String metricName)
```

Starts building a watch condition for the named metric. The metric can be any JVM built-in metric (e.g. `heap_used`, `thread_count`) or a custom metric registered via `SyslenzAgent.registry()` (custom metrics use the `app_` prefix internally).

---

### Operator Methods

All operators return `this` for chaining.

#### Single-value operators

| Method | Fires when |
|--------|-----------|
| `greaterThan(double v)` | `metric > v` |
| `lessThan(double v)` | `metric < v` |
| `greaterThanOrEqual(double v)` | `metric >= v` |
| `lessThanOrEqual(double v)` | `metric <= v` |
| `equalTo(double v)` | `abs(metric - v) < 0.0001` |
| `notEqualTo(double v)` | `abs(metric - v) >= 0.0001` |

#### Range operators

| Method | Fires when |
|--------|-----------|
| `outsideRange(double min, double max)` | `metric < min \|\| metric > max` |
| `insideRange(double min, double max)` | `min <= metric <= max` |

---

### Configuration Methods

#### `.severity(Severity)`

Sets the severity level attached to fired `WatchEvent`s.

| Value | `label()` | Meaning |
|-------|----------|---------|
| `Severity.INFO` | `"info"` | Noteworthy, not a problem |
| `Severity.WARNING` | `"warning"` | Attention needed |
| `Severity.CRITICAL` | `"critical"` | Immediate action required |

Default: `Severity.WARNING`.

#### `.cooldown(long milliseconds)`

Minimum time between consecutive `onFire` invocations for the same condition. After a fire, the condition must clear (triggering `onResolve`) before it can fire again, and the cooldown must have elapsed since the last fire.

Default: `10_000` ms (10 seconds).

Setting `cooldown(0)` disables the minimum gap — the condition fires again on the next evaluation cycle if it still holds.

---

### Callback Methods

#### `.onFire(Consumer<WatchEvent>)`

Called when the condition transitions from **not-firing** to **firing** (after the cooldown has elapsed).

```java
.onFire(event -> {
    String msg = event.message();        // "[WARNING] heap_used = 850000000.00"
    double val = event.value();          // 850000000.0
    Severity sev = event.severity();     // Severity.WARNING
    Instant ts = event.timestamp();      // when it fired
})
```

#### `.onResolve(Consumer<WatchEvent>)`

Called when the condition transitions from **firing** to **not-firing**. The `WatchEvent.state()` is `RESOLVED`.

Both callbacks are optional. If `onFire` is null, the condition is still tracked (affects `WatchRegistry.firingCount()`) but no code runs on fire.

Exceptions thrown inside callbacks are caught and silently discarded — they will not crash the server thread.

---

### Compound Conditions

`.and(String otherMetricName)` adds a secondary condition. Both the primary and secondary conditions must hold simultaneously for the watch to fire.

```java
SyslenzAgent.watch("app_queue_size")
    .greaterThan(10_000)
    .and("app_error_rate").greaterThan(5.0)
    .severity(Severity.CRITICAL)
    .onFire(event -> scaleOut())
    .register();
```

> **Known limitation (v1.1.0)**: `CompoundCondition.greaterThan()` returns `null` instead of the parent `WatchCondition`, so any method calls after `.and("x").greaterThan(v)` will throw `NullPointerException`. Only `greaterThan` and `lessThan` are supported for the secondary condition, and you must not chain further after them. Fix tracked in [GitHub #3](https://github.com/opaopa6969/syslenz4j/issues/3).

---

### `.register()`

Commits the condition to `WatchRegistry`. After this call the `WatchCondition` object should not be reused or further modified.

---

## WatchEvent Reference

`WatchEvent` is a Java `record`:

```java
public record WatchEvent(
    String metricName,   // which metric fired
    double value,        // metric value at fire/resolve time
    Severity severity,   // severity from the condition
    State state,         // FIRING or RESOLVED
    Instant timestamp,   // Instant.now() at fire time
    String message       // human-readable summary
)
```

### `WatchEvent.State`

| Value | Meaning |
|-------|---------|
| `FIRING` | Condition became true |
| `RESOLVED` | Condition cleared |

### `message()` format

- FIRING: `"[WARNING] heap_used = 850000000.00"`
- RESOLVED: `"[RESOLVED] heap_used = 750000000.00"`

---

## WatchRegistry Internals

`WatchRegistry` is an internal class, but understanding it helps with debugging.

```
WatchRegistry
  entries: CopyOnWriteArrayList<WatchEntry>
    WatchEntry
      condition: WatchCondition
      wasFiring: boolean
      lastFiredAt: long (epoch ms)
```

`evaluate(Map<String, Double> currentValues)` is called with a metric name → value map. It iterates all entries and applies the state machine described in [architecture.md](architecture.md).

### State Machine

```
NOT_FIRING
  │  [condition matches AND cooldown elapsed]
  ▼
FIRING  ──── onFire callback called
  │  [condition no longer matches]
  ▼
NOT_FIRING  ──── onResolve callback called
```

Cooldown applies only to the NOT_FIRING → FIRING transition. There is no cooldown on FIRING → NOT_FIRING.

---

## Known Issues (v1.1.0)

1. **`WatchRegistry.evaluate()` is not called automatically.** The snapshot path (`SyslenzServer.collectSnapshot()`) does not invoke `evaluate()`. Watch callbacks never fire in production unless you call `evaluate()` manually. Fix planned for v1.2.0.

2. **`CompoundCondition.greaterThan()` returns `null`.** The fluent chain breaks after `.and("metric").greaterThan(v)`. Only `GREATER_THAN` and `LESS_THAN` work for the secondary condition, and you cannot chain further methods after them.

3. **Secondary condition operators are incomplete.** `CompoundCondition.evaluate()` only handles `GREATER_THAN` and `LESS_THAN`; all other operators fall through to `default: true` (always passes).

All three are tracked in [GitHub issue #3](https://github.com/opaopa6969/syslenz4j/issues/3).

---

## Tips

**Naming custom metrics for watches**: Register the metric with a descriptive name and watch it without the `app_` prefix — the prefix is added internally by `MetricRegistry`.

```java
// Register:
SyslenzAgent.registry().gauge("queue_depth", () -> q.size());

// Watch (no app_ prefix needed):
SyslenzAgent.watch("app_queue_depth").greaterThan(1000).register();
```

**Testing watch conditions**: Use `SyslenzAgent.clearWatches()` in test teardown to prevent conditions from one test affecting another.

**Clearing a specific watch**: There is no per-watch deregistration in v1.1.0. Use `clearWatches()` and re-register only the conditions you want.
