package org.unlaxer.infra.syslenz4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Registry for custom application metrics.
 *
 * <p>Applications use this to expose domain-specific values alongside
 * the built-in JVM metrics. All registered metrics are included in
 * every snapshot produced by {@link SyslenzAgent}.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * MetricRegistry reg = SyslenzAgent.registry();
 *
 * // Gauge: reads a value each time a snapshot is taken
 * reg.gauge("queue_size", () -> myQueue.size());
 *
 * // Counter: wraps an AtomicLong-style supplier
 * reg.counter("requests_total", requestCounter::get);
 *
 * // Text: a descriptive label
 * reg.text("app_version", () -> "2.3.1");
 * }</pre>
 *
 * <p>Thread-safe: metrics can be registered from any thread at any time.
 */
public class MetricRegistry {

    @FunctionalInterface
    public interface NumberSupplier {
        Number get();
    }

    @FunctionalInterface
    public interface StringSupplier {
        String get();
    }

    private static class Registration {
        final String name;
        final String description;
        final String kind;           // "gauge", "counter", "text"
        final NumberSupplier numberFn;
        final StringSupplier stringFn;

        Registration(String name, String description, String kind,
                     NumberSupplier numberFn, StringSupplier stringFn) {
            this.name = name;
            this.description = description;
            this.kind = kind;
            this.numberFn = numberFn;
            this.stringFn = stringFn;
        }
    }

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    /**
     * Register a gauge metric. The supplier is called each time a snapshot
     * is taken; it should return the current value.
     *
     * @param name        metric name (e.g. "queue_size")
     * @param supplier    supplies the current value
     */
    public void gauge(String name, NumberSupplier supplier) {
        gauge(name, supplier, "Custom gauge: " + name);
    }

    /**
     * Register a gauge metric with a description.
     */
    public void gauge(String name, NumberSupplier supplier, String description) {
        registrations.put(name, new Registration(name, description, "gauge", supplier, null));
    }

    /**
     * Register a counter metric. Semantically the same as a gauge but
     * signals that the value is monotonically increasing.
     *
     * @param name        metric name (e.g. "requests_total")
     * @param supplier    supplies the current count
     */
    public void counter(String name, NumberSupplier supplier) {
        counter(name, supplier, "Custom counter: " + name);
    }

    /**
     * Register a counter metric with a description.
     */
    public void counter(String name, NumberSupplier supplier, String description) {
        registrations.put(name, new Registration(name, description, "counter", supplier, null));
    }

    /**
     * Register a text metric (labels, versions, etc.).
     *
     * @param name        metric name (e.g. "app_version")
     * @param supplier    supplies the current text value
     */
    public void text(String name, StringSupplier supplier) {
        text(name, supplier, "Custom text: " + name);
    }

    /**
     * Register a text metric with a description.
     */
    public void text(String name, StringSupplier supplier, String description) {
        registrations.put(name, new Registration(name, description, "text", null, supplier));
    }

    /**
     * Remove a previously registered metric.
     */
    public void remove(String name) {
        registrations.remove(name);
    }

    /**
     * Collect all registered custom metrics as {@link JvmCollector.Metric}
     * entries for inclusion in the JSON export.
     */
    List<JvmCollector.Metric> collect() {
        List<JvmCollector.Metric> result = new ArrayList<>();
        for (Registration reg : registrations.values()) {
            try {
                if ("text".equals(reg.kind)) {
                    String val = reg.stringFn != null ? reg.stringFn.get() : "";
                    result.add(new JvmCollector.Metric(
                            "app_" + reg.name, val, "Text", null, reg.description));
                } else {
                    Number val = reg.numberFn != null ? reg.numberFn.get() : 0;
                    if (val instanceof Long || val instanceof Integer || val instanceof AtomicLong) {
                        result.add(new JvmCollector.Metric(
                                "app_" + reg.name, val.longValue(), "Integer", "count", reg.description));
                    } else {
                        result.add(new JvmCollector.Metric(
                                "app_" + reg.name, val.doubleValue(), "Float", null, reg.description));
                    }
                }
            } catch (Exception e) {
                // Supplier threw; skip this metric silently.
            }
        }
        return result;
    }
}
