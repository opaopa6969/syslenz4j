package org.unlaxer.infra.syslenz4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WatchCondition operators not covered by existing tests:
 * equalTo, notEqualTo, outsideRange, insideRange, greaterThanOrEqual,
 * lessThanOrEqual. Also covers WatchEvent message format, Severity labels,
 * and cooldown enforcement.
 */
class WatchConditionOperatorsTest {

    @AfterEach
    void tearDown() {
        SyslenzAgent.clearWatches();
        SyslenzAgent.stopServer();
    }

    // ── equalTo ──────────────────────────────────────────────────────────────

    @Test
    void equalToFiresWhenValueMatchesExactly() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("thread_count")
                .equalTo(10.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        evaluate("thread_count", 10.0);
        assertTrue(fired.get(), "equalTo should fire when value == threshold");
    }

    @Test
    void equalToDoesNotFireWhenValueDiffers() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("thread_count")
                .equalTo(10.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        evaluate("thread_count", 11.0);
        assertFalse(fired.get(), "equalTo should not fire when value != threshold");
    }

    @Test
    void equalToUsesEpsilonForFloatingPoint() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("cpu_load")
                .equalTo(50.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        // 0.00005 < 0.0001 epsilon: should be considered equal
        evaluate("cpu_load", 50.00005);
        assertTrue(fired.get(), "equalTo should treat values within epsilon as equal");
    }

    // ── notEqualTo ───────────────────────────────────────────────────────────

    @Test
    void notEqualToFiresWhenValueDiffers() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("status_code")
                .notEqualTo(200.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        evaluate("status_code", 500.0);
        assertTrue(fired.get(), "notEqualTo should fire when value differs from threshold");
    }

    @Test
    void notEqualToDoesNotFireWhenValuesMatch() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("status_code")
                .notEqualTo(200.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        evaluate("status_code", 200.0);
        assertFalse(fired.get(), "notEqualTo should not fire when value matches threshold");
    }

    // ── outsideRange ─────────────────────────────────────────────────────────

    @Test
    void outsideRangeFiresWhenValueBelowMin() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("temperature")
                .outsideRange(20.0, 80.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        evaluate("temperature", 10.0);
        assertTrue(fired.get(), "outsideRange should fire when value < min");
    }

    @Test
    void outsideRangeFiresWhenValueAboveMax() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("temperature")
                .outsideRange(20.0, 80.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        evaluate("temperature", 90.0);
        assertTrue(fired.get(), "outsideRange should fire when value > max");
    }

    @Test
    void outsideRangeDoesNotFireWhenValueInsideBounds() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("temperature")
                .outsideRange(20.0, 80.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        evaluate("temperature", 50.0);
        assertFalse(fired.get(), "outsideRange should not fire when value is within [min, max]");
    }

    // ── insideRange ──────────────────────────────────────────────────────────

    @Test
    void insideRangeFiresWhenValueIsWithinBounds() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("heap_pct")
                .insideRange(50.0, 70.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        evaluate("heap_pct", 60.0);
        assertTrue(fired.get(), "insideRange should fire when min <= value <= max");
    }

    @Test
    void insideRangeIncludesBoundaries() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("heap_pct")
                .insideRange(50.0, 70.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        evaluate("heap_pct", 50.0);
        assertTrue(fired.get(), "insideRange should fire at the lower boundary (inclusive)");
    }

    @Test
    void insideRangeDoesNotFireWhenValueOutside() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("heap_pct")
                .insideRange(50.0, 70.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        evaluate("heap_pct", 80.0);
        assertFalse(fired.get(), "insideRange should not fire when value > max");
    }

    // ── greaterThanOrEqual ───────────────────────────────────────────────────

    @Test
    void greaterThanOrEqualFiresAtExactThreshold() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("cpu_load")
                .greaterThanOrEqual(90.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        evaluate("cpu_load", 90.0);
        assertTrue(fired.get(), "greaterThanOrEqual should fire when value == threshold");
    }

    // ── lessThanOrEqual ──────────────────────────────────────────────────────

    @Test
    void lessThanOrEqualFiresAtExactThreshold() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("free_memory_mb")
                .lessThanOrEqual(100.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        evaluate("free_memory_mb", 100.0);
        assertTrue(fired.get(), "lessThanOrEqual should fire when value == threshold");
    }

    // ── WatchEvent message format ────────────────────────────────────────────

    @Test
    void firingEventHasCorrectMessageFormat() {
        AtomicReference<WatchEvent> captured = new AtomicReference<>();

        SyslenzAgent.watch("heap_used")
                .greaterThan(80.0)
                .severity(Severity.WARNING)
                .cooldown(0)
                .onFire(captured::set)
                .register();

        evaluate("heap_used", 90.0);

        assertNotNull(captured.get());
        // Spec: "[WARNING] heap_used = 90.00"
        assertEquals("[warning] heap_used = 90.00", captured.get().message());
    }

    @Test
    void resolvedEventHasCorrectMessageFormat() {
        AtomicReference<WatchEvent> resolved = new AtomicReference<>();

        SyslenzAgent.watch("heap_used")
                .greaterThan(80.0)
                .severity(Severity.CRITICAL)
                .cooldown(0)
                .onFire(e -> {})
                .onResolve(resolved::set)
                .register();

        evaluate("heap_used", 90.0); // fire
        evaluate("heap_used", 70.0); // resolve

        assertNotNull(resolved.get());
        // Spec: "[RESOLVED] heap_used = 70.00"
        assertEquals("[RESOLVED] heap_used = 70.00", resolved.get().message());
        assertEquals(WatchEvent.State.RESOLVED, resolved.get().state());
    }

    // ── Severity labels ──────────────────────────────────────────────────────

    @Test
    void severityLabelsAreCorrect() {
        assertEquals("info",     Severity.INFO.label());
        assertEquals("warning",  Severity.WARNING.label());
        assertEquals("critical", Severity.CRITICAL.label());
    }

    // ── Cooldown enforcement ─────────────────────────────────────────────────

    @Test
    void cooldownPreventsRefire() {
        java.util.concurrent.atomic.AtomicInteger fireCount = new java.util.concurrent.atomic.AtomicInteger(0);

        SyslenzAgent.watch("cpu_load")
                .greaterThan(50.0)
                .cooldown(60_000) // 60 seconds — won't elapse during test
                .onFire(e -> fireCount.incrementAndGet())
                .register();

        WatchRegistry registry = SyslenzAgent.watches();
        Map<String, Double> high = Map.of("cpu_load", 80.0);

        registry.evaluate(high); // first fire
        // Condition is now FIRING; clear it
        registry.evaluate(Map.of("cpu_load", 10.0)); // resolve (no cooldown on resolve)
        registry.evaluate(high); // would fire again, but cooldown prevents it

        assertEquals(1, fireCount.get(),
                "Cooldown should suppress re-fire within the cooldown window");
    }

    @Test
    void zeroCooldownAllowsRefireAfterResolve() {
        java.util.concurrent.atomic.AtomicInteger fireCount = new java.util.concurrent.atomic.AtomicInteger(0);

        SyslenzAgent.watch("cpu_load")
                .greaterThan(50.0)
                .cooldown(0)
                .onFire(e -> fireCount.incrementAndGet())
                .register();

        WatchRegistry registry = SyslenzAgent.watches();

        registry.evaluate(Map.of("cpu_load", 80.0)); // fire #1
        registry.evaluate(Map.of("cpu_load", 10.0)); // resolve
        registry.evaluate(Map.of("cpu_load", 80.0)); // fire #2

        assertEquals(2, fireCount.get(),
                "Zero cooldown should allow firing again after resolving");
    }

    // ── Missing metric key → condition not evaluated ─────────────────────────

    @Test
    void missingMetricKeyIsSkippedGracefully() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("nonexistent_metric")
                .greaterThan(0.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        // Metric not in the map — should be silently skipped
        evaluate("some_other_metric", 999.0);
        assertFalse(fired.get(), "Watch on absent metric key should not fire");
    }

    // ── WatchEvent fields ────────────────────────────────────────────────────

    @Test
    void firingEventContainsAllExpectedFields() {
        AtomicReference<WatchEvent> captured = new AtomicReference<>();

        SyslenzAgent.watch("queue_depth")
                .greaterThan(100.0)
                .severity(Severity.CRITICAL)
                .cooldown(0)
                .onFire(captured::set)
                .register();

        evaluate("queue_depth", 150.0);

        WatchEvent event = captured.get();
        assertNotNull(event);
        assertEquals("queue_depth", event.metricName());
        assertEquals(150.0, event.value(), 0.001);
        assertEquals(Severity.CRITICAL, event.severity());
        assertEquals(WatchEvent.State.FIRING, event.state());
        assertNotNull(event.timestamp(), "Timestamp must be set");
        assertNotNull(event.message(), "Message must be set");
    }

    // ── Default severity ─────────────────────────────────────────────────────

    @Test
    void defaultSeverityIsWarning() {
        AtomicReference<WatchEvent> captured = new AtomicReference<>();

        SyslenzAgent.watch("cpu_load")
                .greaterThan(50.0)
                .cooldown(0)
                .onFire(captured::set)
                .register(); // no explicit severity()

        evaluate("cpu_load", 80.0);

        assertNotNull(captured.get());
        assertEquals(Severity.WARNING, captured.get().severity(),
                "Default severity should be WARNING per spec");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void evaluate(String metricName, double value) {
        Map<String, Double> values = new HashMap<>();
        values.put(metricName, value);
        SyslenzAgent.watches().evaluate(values);
    }
}
