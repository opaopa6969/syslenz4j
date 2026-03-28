package org.unlaxer.infra.syslenz4j;

import java.time.Instant;

/**
 * 監視条件が発火/解除された時のイベント。
 *
 * <p>コールバック ({@code onFire}, {@code onResolve}) に渡される。
 *
 * @param metricName メトリクス名
 * @param value      発火時の値
 * @param severity   重要度
 * @param state      FIRING or RESOLVED
 * @param timestamp  発生時刻
 * @param message    人間が読めるメッセージ
 */
public record WatchEvent(
    String metricName,
    double value,
    Severity severity,
    State state,
    Instant timestamp,
    String message
) {

    /**
     * アラート状態。
     */
    public enum State {
        /** 条件を満たして発火中 */
        FIRING,
        /** 条件が解除された */
        RESOLVED
    }

    /**
     * 発火イベントを生成。
     */
    static WatchEvent firing(String metric, double value, Severity severity) {
        String msg = String.format("[%s] %s = %.2f", severity.label(), metric, value);
        return new WatchEvent(metric, value, severity, State.FIRING, Instant.now(), msg);
    }

    /**
     * 解除イベントを生成。
     */
    static WatchEvent resolved(String metric, double value, Severity severity) {
        String msg = String.format("[RESOLVED] %s = %.2f", metric, value);
        return new WatchEvent(metric, value, severity, State.RESOLVED, Instant.now(), msg);
    }
}
