package org.unlaxer.infra.syslenz4j;

/**
 * syslenz Java 統合のメインエントリポイント。
 *
 * <p>Java アプリケーションに組み込んで、JVM メトリクスとカスタムメトリクスを
 * syslenz にエクスポートする。3つの機能を提供:
 *
 * <ul>
 *   <li><b>Server モード</b> — TCP サーバーで {@code syslenz --connect} に対応
 *   <li><b>Stdout モード</b> — ProcEntry JSON をプラグインとして出力
 *   <li><b>Watch API</b> — メトリクスを監視してコールバックで通知
 * </ul>
 *
 * <h3>サーバーモード</h3>
 * <pre>{@code
 * SyslenzAgent.startServer(9100);
 * // → syslenz --connect localhost:9100
 * }</pre>
 *
 * <h3>Watch API（監視条件）</h3>
 * <pre>{@code
 * SyslenzAgent.watch("heap_used_pct")
 *     .greaterThan(80.0)
 *     .severity(Severity.WARNING)
 *     .cooldown(30_000)
 *     .onFire(event -> logger.warn("Heap high: {}", event.value()))
 *     .onResolve(event -> logger.info("Heap recovered"))
 *     .register();
 * }</pre>
 */
public final class SyslenzAgent {

    private static final MetricRegistry REGISTRY = new MetricRegistry();
    private static final WatchRegistry WATCHES = new WatchRegistry();
    private static volatile SyslenzServer serverInstance;
    private static volatile java.util.concurrent.ScheduledExecutorService evaluatorInstance;

    private SyslenzAgent() {}

    /**
     * TCP サーバーを起動する。
     *
     * <p>デーモンスレッドで動作し、JVM のシャットダウンを妨げない。
     * {@code SNAPSHOT\n} コマンドに ProcEntry JSON で応答する。
     * 複数回呼んでも安全（既に起動中なら無視）。
     * デフォルトのバインドアドレスは {@code 127.0.0.1}（ループバックのみ）。
     * 外部インタフェースへの公開が必要な場合は
     * {@link #startServer(int, String)} で明示的に指定してください。
     *
     * @param port TCP ポート（例: 9100）
     */
    public static void startServer(int port) {
        startServer(port, "127.0.0.1");
    }

    /**
     * TCP サーバーを指定バインドアドレスで起動する。
     *
     * <p>デーモンスレッドで動作し、JVM のシャットダウンを妨げない。
     * {@code SNAPSHOT\n} コマンドに ProcEntry JSON で応答する。
     * 複数回呼んでも安全（既に起動中なら無視）。
     *
     * <pre>{@code
     * // ループバックのみにバインドする例
     * SyslenzAgent.startServer(9100, "127.0.0.1");
     * }</pre>
     *
     * @param port        TCP ポート（例: 9100）
     * @param bindAddress バインドアドレス（例: {@code "127.0.0.1"} または {@code "0.0.0.0"}）
     */
    public static void startServer(int port, String bindAddress) {
        if (serverInstance != null) return;
        synchronized (SyslenzAgent.class) {
            if (serverInstance != null) return;
            SyslenzServer server = new SyslenzServer(port, bindAddress, REGISTRY, WATCHES);
            server.start();
            serverInstance = server;
        }
    }

    /**
     * ProcEntry JSON を stdout に1回出力する（プラグインモード）。
     */
    public static void printSnapshot() {
        JvmCollector collector = new JvmCollector();
        String json = JsonExporter.export(collector.collect(), REGISTRY.collect());
        System.out.println(json);
    }

    /**
     * カスタムメトリクス登録用の {@link MetricRegistry} を取得する。
     *
     * <pre>{@code
     * SyslenzAgent.registry().gauge("queue_size", () -> myQueue.size());
     * SyslenzAgent.registry().counter("requests_total", counter::get);
     * }</pre>
     */
    public static MetricRegistry registry() {
        return REGISTRY;
    }

    /**
     * メトリクスの監視条件を fluent API で構築する。
     *
     * <pre>{@code
     * SyslenzAgent.watch("heap_used_pct")
     *     .greaterThan(80.0)
     *     .severity(Severity.WARNING)
     *     .onFire(event -> alert(event.message()))
     *     .register();
     *
     * SyslenzAgent.watch("queue_size")
     *     .greaterThan(10000)
     *     .and("error_rate").greaterThan(5.0)
     *     .severity(Severity.CRITICAL)
     *     .onFire(event -> scaleOut())
     *     .register();
     *
     * SyslenzAgent.watch("cpu_temperature")
     *     .outsideRange(20.0, 85.0)
     *     .severity(Severity.WARNING)
     *     .cooldown(60_000)
     *     .onFire(event -> ops.page("Temperature: " + event.value()))
     *     .register();
     * }</pre>
     *
     * @param metricName 監視するメトリクス名
     * @return fluent ビルダー
     */
    public static WatchCondition watch(String metricName) {
        return new WatchCondition(WATCHES, metricName);
    }

    /**
     * 内部用: 監視条件の評価（スナップショット取得時に呼ばれる）。
     */
    static WatchRegistry watches() {
        return WATCHES;
    }

    /**
     * 監視条件を一定間隔で自己評価する。
     *
     * <p>通常、Watch の評価はクライアントが {@code SNAPSHOT} を要求した
     * タイミングでしか行われない。つまり syslenz が接続していない間は
     * アラートが一切発火しない。このメソッドを呼ぶと、デーモンスレッドが
     * 指定間隔でメトリクスを収集して Watch を評価するため、クライアントの
     * 接続有無に関係なくアラートが発火する。
     *
     * <pre>{@code
     * SyslenzAgent.watch("heap_used_pct").greaterThan(80.0)
     *     .onFire(e -> log.warn(e.message())).register();
     * SyslenzAgent.evaluateEvery(Duration.ofSeconds(10));
     * }</pre>
     *
     * <p>再度呼ぶと前のスケジューラを停止して新しい間隔で起動し直す。
     *
     * @param interval 評価間隔（1ミリ秒以上）
     */
    public static void evaluateEvery(java.time.Duration interval) {
        if (interval.toMillis() < 1) {
            throw new IllegalArgumentException("interval must be at least 1ms: " + interval);
        }
        synchronized (SyslenzAgent.class) {
            stopEvaluator();
            java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "syslenz-watch-evaluator");
                    t.setDaemon(true);
                    return t;
                });
            scheduler.scheduleAtFixedRate(SyslenzAgent::evaluateOnce,
                    interval.toMillis(), interval.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            evaluatorInstance = scheduler;
        }
    }

    /**
     * {@link #evaluateEvery(java.time.Duration)} のスケジューラを停止する。
     */
    public static void stopEvaluator() {
        java.util.concurrent.ScheduledExecutorService s = evaluatorInstance;
        if (s != null) {
            s.shutdownNow();
            evaluatorInstance = null;
        }
    }

    /**
     * メトリクスを1回収集して全監視条件を評価する。
     */
    static void evaluateOnce() {
        try {
            JvmCollector collector = new JvmCollector();
            WATCHES.evaluate(WatchRegistry.metricValues(
                    collector.collect(), REGISTRY.collect()));
        } catch (Exception e) {
            // 収集の一時的な失敗でスケジューラを止めない
        }
    }

    /**
     * サーバーを停止する（主にテスト用）。
     */
    public static void stopServer() {
        SyslenzServer s = serverInstance;
        if (s != null) {
            s.stop();
            serverInstance = null;
        }
    }

    /**
     * 全監視条件をクリアする。
     */
    public static void clearWatches() {
        WATCHES.clear();
    }
}
