package org.unlaxer.infra.syslenz4j;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MetricRegistry: registration, type inference, app_ prefix, exception handling.
 */
class MetricRegistryTest {

    private MetricRegistry registry;

    @BeforeEach
    void setUp() {
        // Use a fresh instance rather than the singleton to avoid state leakage
        registry = new MetricRegistry();
    }

    // ── gauge ────────────────────────────────────────────────────────────────

    @Test
    void gaugeWithDoubleValueProducesFloatMetric() {
        registry.gauge("response_time_ms", () -> 42.5);

        List<JvmCollector.Metric> metrics = registry.collect();
        JvmCollector.Metric m = findByName(metrics, "app_response_time_ms");

        assertNotNull(m, "Metric app_response_time_ms should be present");
        assertEquals("Float", m.type);
        assertEquals(42.5, ((Number) m.value).doubleValue(), 0.001);
    }

    @Test
    void gaugeWithLongValueProducesIntegerMetric() {
        registry.gauge("queue_depth", () -> 100L);

        List<JvmCollector.Metric> metrics = registry.collect();
        JvmCollector.Metric m = findByName(metrics, "app_queue_depth");

        assertNotNull(m, "Metric app_queue_depth should be present");
        assertEquals("Integer", m.type);
        assertEquals("count", m.unit);
        assertEquals(100L, ((Number) m.value).longValue());
    }

    @Test
    void gaugeWithIntegerValueProducesIntegerMetric() {
        registry.gauge("active_sessions", () -> 7);

        List<JvmCollector.Metric> metrics = registry.collect();
        JvmCollector.Metric m = findByName(metrics, "app_active_sessions");

        assertNotNull(m, "Metric app_active_sessions should be present");
        assertEquals("Integer", m.type);
    }

    @Test
    void gaugeWithAtomicLongValueProducesIntegerMetric() {
        AtomicLong counter = new AtomicLong(55);
        registry.gauge("requests", counter::get);

        List<JvmCollector.Metric> metrics = registry.collect();
        JvmCollector.Metric m = findByName(metrics, "app_requests");

        assertNotNull(m, "Metric app_requests should be present");
        assertEquals("Integer", m.type);
        assertEquals(55L, ((Number) m.value).longValue());
    }

    // ── counter ──────────────────────────────────────────────────────────────

    @Test
    void counterMetricHasAppPrefix() {
        registry.counter("requests_total", () -> 999L);

        List<JvmCollector.Metric> metrics = registry.collect();
        JvmCollector.Metric m = findByName(metrics, "app_requests_total");

        assertNotNull(m, "Counter metric should have app_ prefix");
        assertEquals(999L, ((Number) m.value).longValue());
    }

    // ── text ─────────────────────────────────────────────────────────────────

    @Test
    void textMetricProducesTextType() {
        registry.text("app_version", () -> "2.3.1");

        List<JvmCollector.Metric> metrics = registry.collect();
        JvmCollector.Metric m = findByName(metrics, "app_app_version");

        assertNotNull(m, "Text metric should be present with app_ prefix");
        assertEquals("Text", m.type);
        assertEquals("2.3.1", m.value);
    }

    @Test
    void textMetricWithDescriptionStoresDescription() {
        registry.text("env", () -> "production", "Deployment environment");

        List<JvmCollector.Metric> metrics = registry.collect();
        JvmCollector.Metric m = findByName(metrics, "app_env");

        assertNotNull(m);
        assertEquals("Deployment environment", m.description);
    }

    // ── remove ───────────────────────────────────────────────────────────────

    @Test
    void removeDropsMetricFromCollect() {
        registry.gauge("temp_metric", () -> 1.0);
        registry.remove("temp_metric");

        List<JvmCollector.Metric> metrics = registry.collect();
        assertNull(findByName(metrics, "app_temp_metric"),
                "Removed metric should not appear in collect()");
    }

    // ── overwrite ────────────────────────────────────────────────────────────

    @Test
    void registeringSameNameOverwritesPreviousValue() {
        registry.gauge("heap_pct", () -> 50.0);
        registry.gauge("heap_pct", () -> 75.0); // overwrite

        List<JvmCollector.Metric> metrics = registry.collect();
        List<JvmCollector.Metric> found = metrics.stream()
                .filter(m -> "app_heap_pct".equals(m.name))
                .toList();

        assertEquals(1, found.size(), "There should be exactly one metric after overwrite");
        assertEquals(75.0, ((Number) found.get(0).value).doubleValue(), 0.001);
    }

    // ── supplier exception handling ──────────────────────────────────────────

    @Test
    void supplierThrowingExceptionIsSkippedSilently() {
        registry.gauge("bad_metric", () -> { throw new RuntimeException("supplier error"); });
        registry.gauge("good_metric", () -> 42.0);

        // Should not throw; bad_metric is skipped, good_metric is collected
        List<JvmCollector.Metric> metrics = assertDoesNotThrow(() -> registry.collect());

        assertNull(findByName(metrics, "app_bad_metric"),
                "Metric whose supplier threw should be absent");
        assertNotNull(findByName(metrics, "app_good_metric"),
                "Good metric should still be collected despite sibling failure");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JvmCollector.Metric findByName(List<JvmCollector.Metric> metrics, String name) {
        return metrics.stream().filter(m -> name.equals(m.name)).findFirst().orElse(null);
    }
}
