package org.unlaxer.infra.syslenz4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 監視条件の登録と評価を管理する。
 *
 * <p>スナップショット取得のたびに {@link #evaluate(Map)} が呼ばれ、
 * 登録された全条件をチェックする。条件を満たしたらコールバックを発火し、
 * クールダウン中は再発火しない。
 */
class WatchRegistry {

    private final List<WatchEntry> entries = new CopyOnWriteArrayList<>();

    private static class WatchEntry {
        final WatchCondition condition;
        boolean wasFiring = false;
        long lastFiredAt = 0;

        WatchEntry(WatchCondition condition) {
            this.condition = condition;
        }
    }

    /**
     * 条件を登録する（{@link WatchCondition#register()} から呼ばれる）。
     */
    void add(WatchCondition condition) {
        entries.add(new WatchEntry(condition));
    }

    /**
     * 全条件を現在のメトリクス値で評価する。
     *
     * @param currentValues メトリクス名 → 現在値のマップ
     */
    void evaluate(Map<String, Double> currentValues) {
        long now = System.currentTimeMillis();

        for (WatchEntry entry : entries) {
            WatchCondition cond = entry.condition;
            Double value = currentValues.get(cond.metricName());
            if (value == null) continue;

            boolean matches = cond.evaluate(value);

            // 複合条件のチェック
            if (matches && cond.compound() != null) {
                Double otherValue = currentValues.get(cond.compound().metricName);
                if (otherValue == null || !cond.compound().evaluate(otherValue)) {
                    matches = false;
                }
            }

            if (matches && !entry.wasFiring) {
                // 新規発火（クールダウン確認）
                if (now - entry.lastFiredAt >= cond.cooldownMs()) {
                    entry.wasFiring = true;
                    entry.lastFiredAt = now;
                    if (cond.onFire() != null) {
                        try {
                            cond.onFire().accept(
                                WatchEvent.firing(cond.metricName(), value, cond.severity())
                            );
                        } catch (Exception e) {
                            // コールバックの例外はスキップ
                        }
                    }
                }
            } else if (!matches && entry.wasFiring) {
                // 解除
                entry.wasFiring = false;
                if (cond.onResolve() != null) {
                    try {
                        cond.onResolve().accept(
                            WatchEvent.resolved(cond.metricName(), value, cond.severity())
                        );
                    } catch (Exception e) {
                        // コールバックの例外はスキップ
                    }
                }
            }
        }
    }

    /**
     * 現在発火中の条件数。
     */
    int firingCount() {
        return (int) entries.stream().filter(e -> e.wasFiring).count();
    }

    /**
     * 登録を全クリア。
     */
    void clear() {
        entries.clear();
    }
}
