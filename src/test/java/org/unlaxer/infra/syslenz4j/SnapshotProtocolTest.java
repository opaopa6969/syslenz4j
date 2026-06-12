package org.unlaxer.infra.syslenz4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Snapshot-shaped protocol output (what syslenz --connect
 * parses), the alerts section, and the self-driven watch evaluator.
 */
class SnapshotProtocolTest {

    @AfterEach
    void tearDown() {
        SyslenzAgent.stopEvaluator();
        SyslenzAgent.clearWatches();
    }

    private static JvmCollector.Metric metric(String name, Object value, String type) {
        return new JvmCollector.Metric(name, value, type, null, "test metric");
    }

    // ── Snapshot shape ───────────────────────────────────────────────────────

    @Test
    void snapshotWrapsEntriesUnderJvmKey() {
        String json = JsonExporter.exportSnapshot(
                List.of(metric("heap_used", 1024L, "Bytes")), List.of(), List.of());

        assertTrue(json.contains("\"timestamp\": \""));
        assertTrue(json.contains("\"entries\": {\"jvm\": {"));
        assertTrue(json.contains("\"source\": \"jvm/pid-"));
    }

    @Test
    void timestampIsIso8601WithTrailingZ() {
        String json = JsonExporter.exportSnapshot(List.of(), List.of(), List.of());

        // e.g. "timestamp": "2026-06-12T01:02:03.456Z"
        assertTrue(json.matches(
                "(?s).*\"timestamp\": \"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z\".*"));
    }

    @Test
    void alertsKeyOmittedWhenNoWatchIsFiring() {
        String json = JsonExporter.exportSnapshot(
                List.of(metric("heap_used", 1024L, "Bytes")), List.of(), List.of());

        assertFalse(json.contains("\"alerts\""));
    }

    // ── alerts section ───────────────────────────────────────────────────────

    @Test
    void firingWatchAppearsInAlerts() {
        WatchRegistry registry = new WatchRegistry();
        new WatchCondition(registry, "queue_size")
                .greaterThan(100)
                .severity(Severity.CRITICAL)
                .register();

        registry.evaluate(Map.of("queue_size", 250.0));
        List<WatchRegistry.ActiveAlert> alerts = registry.activeAlerts();

        assertEquals(1, alerts.size());
        WatchRegistry.ActiveAlert alert = alerts.get(0);
        assertEquals("queue_size", alert.name());
        assertEquals(Severity.CRITICAL, alert.severity());
        assertEquals(250.0, alert.value());
        assertEquals("> 100", alert.condition());

        String json = JsonExporter.exportSnapshot(List.of(), List.of(), alerts);
        assertTrue(json.contains("\"alerts\": [{\"name\": \"queue_size\""));
        assertTrue(json.contains("\"severity\": \"critical\""));
        assertTrue(json.contains("\"condition\": \"> 100\""));
        assertTrue(json.contains("\"since\": \""));
    }

    @Test
    void resolvedWatchDisappearsFromAlerts() {
        WatchRegistry registry = new WatchRegistry();
        new WatchCondition(registry, "queue_size").greaterThan(100).register();

        registry.evaluate(Map.of("queue_size", 250.0));
        assertEquals(1, registry.activeAlerts().size());

        registry.evaluate(Map.of("queue_size", 10.0));
        assertTrue(registry.activeAlerts().isEmpty());
    }

    @Test
    void rangeConditionDescriptionIsReadable() {
        WatchRegistry registry = new WatchRegistry();
        new WatchCondition(registry, "cpu_temperature")
                .outsideRange(30.0, 85.0)
                .register();

        registry.evaluate(Map.of("cpu_temperature", 95.0));

        assertEquals("outside [30, 85]", registry.activeAlerts().get(0).condition());
    }

    // ── self-driven evaluator ────────────────────────────────────────────────

    @Test
    void evaluateEveryFiresWatchWithoutAnyClient() throws Exception {
        AtomicLong queueDepth = new AtomicLong(0);
        SyslenzAgent.registry().gauge("evaluator_test_depth", queueDepth::get);

        CountDownLatch fired = new CountDownLatch(1);
        SyslenzAgent.watch("evaluator_test_depth")
                .greaterThan(50)
                .onFire(e -> fired.countDown())
                .register();

        SyslenzAgent.evaluateEvery(Duration.ofMillis(20));
        queueDepth.set(100);

        assertTrue(fired.await(2, TimeUnit.SECONDS),
                "watch should fire via the scheduler with no client connected");

        SyslenzAgent.registry().remove("evaluator_test_depth");
    }

    @Test
    void evaluateEveryRejectsZeroInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> SyslenzAgent.evaluateEvery(Duration.ZERO));
    }
}
