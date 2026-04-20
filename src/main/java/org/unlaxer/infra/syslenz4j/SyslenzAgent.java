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

    private SyslenzAgent() {}

    /**
     * TCP サーバーを起動する。
     *
     * <p>デーモンスレッドで動作し、JVM のシャットダウンを妨げない。
     * {@code SNAPSHOT\n} コマンドに ProcEntry JSON で応答する。
     * 複数回呼んでも安全（既に起動中なら無視）。
     * バインドアドレスは {@code 0.0.0.0}（全インタフェース）。
     *
     * @param port TCP ポート（例: 9100）
     */
    public static void startServer(int port) {
        startServer(port, "0.0.0.0");
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
