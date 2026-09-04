package org.unlaxer.infra.syslenz4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 演算子が選ばれないまま登録された監視条件が NPE を投げず、
 * かつ他の監視条件やスナップショット全体を巻き添えにしないこと (issue #22)。
 *
 * <p>修正前は {@code WatchCondition#evaluate} / {@code CompoundCondition#evaluate} が
 * {@code switch (operator)} で NPE を投げ、それが {@link WatchRegistry#evaluate(Map)} の
 * ループ全体を中断させていた。その結果 {@code SNAPSHOT} は空応答になり、
 * {@code evaluateEvery} 経路では例外が握り潰されて後続の watch が無言で止まっていた。
 */
class WatchNullOperatorGuardTest {

    @BeforeEach
    @AfterEach
    void reset() {
        SyslenzAgent.stopEvaluator();
        SyslenzAgent.stopServer();
        SyslenzAgent.clearWatches();
    }

    // ── 正常系: 既存の chain API は変わらない ──────────────────────────────

    /**
     * 演算子を選んだ通常のチェーンは従来どおり動作すること。
     * ガード追加による回帰がないことを確認する。
     */
    @Test
    void fullyConfiguredWatchStillFires() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("queue_size")
                .greaterThan(10_000.0)
                .and("error_rate").greaterThanOrEqual(5.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        Map<String, Double> values = new HashMap<>();
        values.put("queue_size", 20_000.0);
        values.put("error_rate", 5.0);
        SyslenzAgent.watches().evaluate(values);

        assertTrue(fired.get(), "a fully configured compound watch must still fire");
    }

    /**
     * {@code and(metric)} が返すセカンダリ条件の演算子メソッドは、引き続き
     * 同一の親 {@code WatchCondition} を返すこと（chain API の源泉互換性）。
     */
    @Test
    void chainApiStillReturnsTheSameParentInstance() {
        WatchCondition parent = SyslenzAgent.watch("queue_size").greaterThan(1.0);

        assertSame(parent, parent.and("error_rate").greaterThan(1.0));
        assertSame(parent, parent.and("error_rate").lessThan(1.0));
        assertSame(parent, parent.and("error_rate").greaterThanOrEqual(1.0));
        assertSame(parent, parent.and("error_rate").lessThanOrEqual(1.0));
    }

    // ── 境界系: register() 時点 ────────────────────────────────────────────

    /**
     * 演算子未設定でも {@code register()} は例外を投げないこと。
     * 監視の設定ミスで被監視アプリの起動を止めないための契約。
     */
    @Test
    void registerDoesNotThrowWhenPrimaryOperatorIsMissing() {
        assertDoesNotThrow(() ->
                SyslenzAgent.watch("queue_size").onFire(e -> {}).register());
    }

    /**
     * セカンダリ条件の演算子だけが未設定の場合も {@code register()} は投げないこと。
     */
    @Test
    void registerDoesNotThrowWhenSecondaryOperatorIsMissing() {
        assertDoesNotThrow(() -> {
            WatchCondition c = SyslenzAgent.watch("queue_size").greaterThan(1.0);
            c.and("error_rate");   // 演算子を選ばない
            c.onFire(e -> {}).register();
        });
    }

    // ── 境界系: evaluate() 時点 ───────────────────────────────────────────

    /**
     * 演算子未設定の条件（親）は、評価時に NPE を投げず、発火もしないこと。
     */
    @Test
    void watchWithoutPrimaryOperatorNeitherThrowsNorFires() {
        AtomicBoolean fired = new AtomicBoolean(false);
        SyslenzAgent.watch("queue_size").cooldown(0).onFire(e -> fired.set(true)).register();

        Map<String, Double> values = new HashMap<>();
        values.put("queue_size", 20_000.0);

        assertDoesNotThrow(() -> SyslenzAgent.watches().evaluate(values));
        assertFalse(fired.get(), "a watch with no operator must never fire");
    }

    /**
     * セカンダリ条件の演算子未設定でも NPE を投げず、発火もしないこと。
     * プライマリ条件だけで発火してしまう false positive も同時に防ぐ。
     */
    @Test
    void compoundWithoutSecondaryOperatorNeitherThrowsNorFires() {
        AtomicBoolean fired = new AtomicBoolean(false);
        WatchCondition c = SyslenzAgent.watch("queue_size").greaterThan(10_000.0).cooldown(0);
        c.and("error_rate");   // 演算子を選ばない
        c.onFire(e -> fired.set(true)).register();

        Map<String, Double> values = new HashMap<>();
        values.put("queue_size", 20_000.0);   // プライマリは成立
        values.put("error_rate", 99.0);       // セカンダリの値は存在する

        assertDoesNotThrow(() -> SyslenzAgent.watches().evaluate(values));
        assertFalse(fired.get(),
                "a compound watch whose secondary operator is unset must never fire");
    }

    /**
     * 発火しない以上、{@code alerts} にも現れないこと。
     * （{@code describe()} も {@code switch (operator)} を持つため、
     * 発火経路が塞がれていることを固定する。）
     */
    @Test
    void unconfiguredWatchNeverAppearsInActiveAlerts() {
        SyslenzAgent.watch("queue_size").register();

        Map<String, Double> values = new HashMap<>();
        values.put("queue_size", 20_000.0);
        SyslenzAgent.watches().evaluate(values);

        assertEquals(0, SyslenzAgent.watches().activeAlerts().size());
    }

    // ── 失敗系: 健全な watch の隔離 ───────────────────────────────────────

    /**
     * 壊れた条件を先に登録しても、後続の健全な条件が評価されること。
     * 修正前は NPE がループ全体を中断し、後続の watch が一切発火しなかった。
     */
    @Test
    void unconfiguredWatchDoesNotStarveLaterWatches() {
        AtomicBoolean healthyFired = new AtomicBoolean(false);

        WatchCondition broken = SyslenzAgent.watch("queue_size").greaterThan(1.0);
        broken.and("error_rate");   // 演算子未設定
        broken.onFire(e -> {}).register();

        SyslenzAgent.watch("cpu_load").greaterThan(1.0).cooldown(0)
                .onFire(e -> healthyFired.set(true))
                .register();

        Map<String, Double> values = new HashMap<>();
        values.put("queue_size", 20_000.0);
        values.put("error_rate", 99.0);
        values.put("cpu_load", 99.0);
        SyslenzAgent.watches().evaluate(values);

        assertTrue(healthyFired.get(),
                "a healthy watch registered after a broken one must still be evaluated");
    }

    /**
     * 評価中に任意の例外が出ても、その条件だけがスキップされ、
     * 後続の条件は評価されること（{@code WatchRegistry} 側の隔離）。
     * null operator 以外の将来の評価時例外も同じ扱いになることを固定する。
     */
    @Test
    void anyEvaluationExceptionIsContainedToItsOwnEntry() {
        AtomicBoolean healthyFired = new AtomicBoolean(false);
        WatchRegistry registry = SyslenzAgent.watches();

        registry.add(new ThrowingCondition(registry, "queue_size"));
        SyslenzAgent.watch("cpu_load").greaterThan(1.0).cooldown(0)
                .onFire(e -> healthyFired.set(true))
                .register();

        Map<String, Double> values = new HashMap<>();
        values.put("queue_size", 20_000.0);
        values.put("cpu_load", 99.0);

        assertDoesNotThrow(() -> registry.evaluate(values));
        assertTrue(healthyFired.get(),
                "an exception from one condition must not stop the remaining ones");
    }

    private static class ThrowingCondition extends WatchCondition {
        ThrowingCondition(WatchRegistry registry, String metricName) {
            super(registry, metricName);
        }

        @Override
        boolean evaluate(double value) {
            throw new IllegalStateException("boom");
        }
    }

    // ── 失敗系: SNAPSHOT 応答が壊れないこと ───────────────────────────────

    /**
     * 壊れた条件が登録されていても {@code SNAPSHOT} が正常な応答を返すこと。
     * 修正前は {@code collectSnapshot()} から NPE が上がり、応答を書かずに
     * 接続が閉じられていた（クライアントからはエージェント全体が死んで見える）。
     */
    @Test
    void snapshotStillRespondsWhenAnUnconfiguredWatchIsRegistered() throws Exception {
        SyslenzAgent.registry().gauge("queue_size", () -> 20_000.0);
        SyslenzAgent.watch("app_queue_size").register();   // 演算子未設定

        int port = 19271;
        SyslenzAgent.startServer(port, "127.0.0.1");
        Thread.sleep(200);

        String response = exchange(port, "SNAPSHOT");

        assertNotNull(response, "SNAPSHOT must still produce a response line");
        assertFalse(response.isEmpty(), "SNAPSHOT response must not be empty");
        assertTrue(response.contains("\"entries\": {\"jvm\": {"),
                "SNAPSHOT must return a well-formed snapshot, got: " + response);
    }

    private static String exchange(int port, String command) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            writer.println(command);
            return reader.readLine();
        }
    }
}
