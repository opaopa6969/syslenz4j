package org.unlaxer.infra.syslenz4j;

import java.util.function.Consumer;

/**
 * メトリクスの監視条件をfluent APIで構築する。
 *
 * <h3>使い方</h3>
 * <pre>{@code
 * SyslenzAgent.watch("heap_used_pct")
 *     .greaterThan(80.0)
 *     .severity(Severity.WARNING)
 *     .cooldown(30_000)  // 30秒間再発火しない
 *     .onFire(event -> logger.warn("Heap high: {}%", event.value()))
 *     .onResolve(event -> logger.info("Heap recovered: {}%", event.value()))
 *     .register();
 *
 * // 複合条件
 * SyslenzAgent.watch("queue_size")
 *     .greaterThan(10000)
 *     .and("error_rate").greaterThan(5.0)
 *     .severity(Severity.CRITICAL)
 *     .onFire(event -> alertService.fire("Queue overflow + errors"))
 *     .register();
 *
 * // 範囲外
 * SyslenzAgent.watch("cpu_temperature")
 *     .outsideRange(30.0, 85.0)
 *     .severity(Severity.WARNING)
 *     .onFire(event -> ops.notify("Temperature anomaly: " + event.value()))
 *     .register();
 * }</pre>
 */
public class WatchCondition {

    private final WatchRegistry registry;
    private String metricName;
    private Operator operator;
    private double threshold;
    private double rangeMin;
    private double rangeMax;
    private Severity severity = Severity.WARNING;
    private long cooldownMs = 10_000;
    private Consumer<WatchEvent> onFire;
    private Consumer<WatchEvent> onResolve;
    private CompoundCondition compound;

    WatchCondition(WatchRegistry registry, String metricName) {
        this.registry = registry;
        this.metricName = metricName;
    }

    // ── 比較演算子 (fluent) ──

    public WatchCondition greaterThan(double value) {
        this.operator = Operator.GREATER_THAN;
        this.threshold = value;
        return this;
    }

    public WatchCondition lessThan(double value) {
        this.operator = Operator.LESS_THAN;
        this.threshold = value;
        return this;
    }

    public WatchCondition greaterThanOrEqual(double value) {
        this.operator = Operator.GREATER_THAN_OR_EQUAL;
        this.threshold = value;
        return this;
    }

    public WatchCondition lessThanOrEqual(double value) {
        this.operator = Operator.LESS_THAN_OR_EQUAL;
        this.threshold = value;
        return this;
    }

    public WatchCondition equalTo(double value) {
        this.operator = Operator.EQUAL;
        this.threshold = value;
        return this;
    }

    public WatchCondition notEqualTo(double value) {
        this.operator = Operator.NOT_EQUAL;
        this.threshold = value;
        return this;
    }

    public WatchCondition outsideRange(double min, double max) {
        this.operator = Operator.OUTSIDE_RANGE;
        this.rangeMin = min;
        this.rangeMax = max;
        return this;
    }

    public WatchCondition insideRange(double min, double max) {
        this.operator = Operator.INSIDE_RANGE;
        this.rangeMin = min;
        this.rangeMax = max;
        return this;
    }

    // ── 複合条件 ──

    public CompoundCondition and(String otherMetric) {
        this.compound = new CompoundCondition(otherMetric, this);
        return this.compound;
    }

    // ── 設定 ──

    public WatchCondition severity(Severity severity) {
        this.severity = severity;
        return this;
    }

    public WatchCondition cooldown(long milliseconds) {
        this.cooldownMs = milliseconds;
        return this;
    }

    // ── コールバック ──

    public WatchCondition onFire(Consumer<WatchEvent> callback) {
        this.onFire = callback;
        return this;
    }

    public WatchCondition onResolve(Consumer<WatchEvent> callback) {
        this.onResolve = callback;
        return this;
    }

    // ── 登録 ──

    public void register() {
        registry.add(this);
    }

    // ── 内部 ──

    boolean evaluate(double value) {
        return switch (operator) {
            case GREATER_THAN -> value > threshold;
            case LESS_THAN -> value < threshold;
            case GREATER_THAN_OR_EQUAL -> value >= threshold;
            case LESS_THAN_OR_EQUAL -> value <= threshold;
            case EQUAL -> Math.abs(value - threshold) < 0.0001;
            case NOT_EQUAL -> Math.abs(value - threshold) >= 0.0001;
            case OUTSIDE_RANGE -> value < rangeMin || value > rangeMax;
            case INSIDE_RANGE -> value >= rangeMin && value <= rangeMax;
        };
    }

    String metricName() { return metricName; }
    Severity severity() { return severity; }
    long cooldownMs() { return cooldownMs; }
    Consumer<WatchEvent> onFire() { return onFire; }
    Consumer<WatchEvent> onResolve() { return onResolve; }
    CompoundCondition compound() { return compound; }
    Operator operator() { return operator; }
    double threshold() { return threshold; }

    static class CompoundCondition {
        final String metricName;
        private final WatchCondition parent;
        Operator operator;
        double threshold;

        CompoundCondition(String metricName, WatchCondition parent) {
            this.metricName = metricName;
            this.parent = parent;
        }

        public WatchCondition greaterThan(double value) {
            this.operator = Operator.GREATER_THAN;
            this.threshold = value;
            return parent;
        }

        public WatchCondition lessThan(double value) {
            this.operator = Operator.LESS_THAN;
            this.threshold = value;
            return parent;
        }

        public WatchCondition greaterThanOrEqual(double value) {
            this.operator = Operator.GREATER_THAN_OR_EQUAL;
            this.threshold = value;
            return parent;
        }

        public WatchCondition lessThanOrEqual(double value) {
            this.operator = Operator.LESS_THAN_OR_EQUAL;
            this.threshold = value;
            return parent;
        }

        boolean evaluate(double value) {
            return switch (operator) {
                case GREATER_THAN -> value > threshold;
                case LESS_THAN -> value < threshold;
                default -> true;
            };
        }
    }
}
