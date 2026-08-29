package org.unlaxer.infra.syslenz4j;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that WatchRegistry.evaluate() fires callbacks correctly (was dead code in v1.1.0).
 */
class WatchRegistryEvaluateTest {

    @BeforeEach
    void tearDown() {
        SyslenzAgent.clearWatches();
        SyslenzAgent.stopServer();
    }

    @Test
    void evaluateFiresCallbackWhenConditionMatches() {
        AtomicBoolean fired = new AtomicBoolean(false);
        AtomicReference<WatchEvent> capturedEvent = new AtomicReference<>();

        SyslenzAgent.watch("heap_used_pct")
                .greaterThan(80.0)
                .severity(Severity.WARNING)
                .cooldown(0)
                .onFire(event -> {
                    fired.set(true);
                    capturedEvent.set(event);
                })
                .register();

        WatchRegistry registry = SyslenzAgent.watches();
        Map<String, Double> values = new HashMap<>();
        values.put("heap_used_pct", 90.0);
        registry.evaluate(values);

        assertTrue(fired.get(), "onFire callback should have been invoked");
        assertNotNull(capturedEvent.get());
        assertEquals("heap_used_pct", capturedEvent.get().metricName());
        assertEquals(WatchEvent.State.FIRING, capturedEvent.get().state());
        assertEquals(90.0, capturedEvent.get().value(), 0.001);
    }

    @Test
    void evaluateResolvesWhenConditionClears() {
        AtomicBoolean resolved = new AtomicBoolean(false);

        SyslenzAgent.watch("cpu_load")
                .greaterThan(50.0)
                .severity(Severity.WARNING)
                .cooldown(0)
                .onFire(e -> {})
                .onResolve(e -> resolved.set(true))
                .register();

        WatchRegistry registry = SyslenzAgent.watches();
        Map<String, Double> high = new HashMap<>();
        high.put("cpu_load", 80.0);
        registry.evaluate(high); // fires

        Map<String, Double> low = new HashMap<>();
        low.put("cpu_load", 20.0);
        registry.evaluate(low); // resolves

        assertTrue(resolved.get(), "onResolve callback should have been invoked when condition clears");
    }

    @Test
    void firingCountReflectsEvaluationResult() {
        SyslenzAgent.watch("queue_size")
                .greaterThan(100.0)
                .cooldown(0)
                .register();

        WatchRegistry registry = SyslenzAgent.watches();
        assertEquals(0, registry.firingCount());

        Map<String, Double> values = new HashMap<>();
        values.put("queue_size", 200.0);
        registry.evaluate(values);

        assertEquals(1, registry.firingCount());
    }

    @Test
    void concurrentEvaluationsAreSerialized() throws Exception {
        CountDownLatch firingCallbackStarted = new CountDownLatch(1);
        CountDownLatch releaseFiringCallback = new CountDownLatch(1);
        CountDownLatch resolvingEvaluationStarted = new CountDownLatch(1);
        CountDownLatch resolveCallbackCalled = new CountDownLatch(1);

        SyslenzAgent.watch("cpu_load")
                .greaterThan(50.0)
                .cooldown(0)
                .onFire(event -> {
                    firingCallbackStarted.countDown();
                    try {
                        if (!releaseFiringCallback.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("timed out waiting to release onFire callback");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("onFire callback was interrupted", e);
                    }
                })
                .onResolve(event -> resolveCallbackCalled.countDown())
                .register();

        WatchRegistry registry = SyslenzAgent.watches();
        ExecutorService evaluators = Executors.newFixedThreadPool(2);
        try {
            Future<?> firingEvaluation = evaluators.submit(
                    () -> registry.evaluate(Map.of("cpu_load", 80.0)));
            assertTrue(firingCallbackStarted.await(2, TimeUnit.SECONDS),
                    "first evaluation should reach onFire callback");

            Future<?> resolvingEvaluation = evaluators.submit(() -> {
                resolvingEvaluationStarted.countDown();
                registry.evaluate(Map.of("cpu_load", 20.0));
            });
            assertTrue(resolvingEvaluationStarted.await(2, TimeUnit.SECONDS),
                    "second evaluation should attempt to enter evaluate()");
            assertFalse(resolveCallbackCalled.await(200, TimeUnit.MILLISECONDS),
                    "second evaluation must wait while the first callback holds the registry lock");

            releaseFiringCallback.countDown();
            firingEvaluation.get(2, TimeUnit.SECONDS);
            resolvingEvaluation.get(2, TimeUnit.SECONDS);
            assertTrue(resolveCallbackCalled.await(2, TimeUnit.SECONDS),
                    "second evaluation should run after the first evaluation completes");
        } finally {
            releaseFiringCallback.countDown();
            evaluators.shutdownNow();
        }
    }

    // ── callback exception isolation (SPEC 10.4 fail-safe) ────────────────────

    /**
     * onFire が例外を投げても wasFiring は true に遷移し、firingCount() に反映されること。
     * これが壊れると、コールバック例外でアラート状態が不整合になり監視全体が腐る。
     */
    @Test
    void onFireThrowingExceptionDoesNotCorruptFiringState() {
        SyslenzAgent.watch("heap_used_pct")
                .greaterThan(80.0)
                .cooldown(0)
                .onFire(e -> { throw new RuntimeException("callback boom"); })
                .register();

        WatchRegistry registry = SyslenzAgent.watches();
        // 例外が漏れないこと
        assertDoesNotThrow(() -> registry.evaluate(Map.of("heap_used_pct", 90.0)));

        // 例外が飛んでも状態遷移は完了していること
        assertEquals(1, registry.firingCount(),
                "wasFiring must be true even if onFire throws");
    }

    /**
     * ある Watch のコールバックが例外を投げても、後続 Watch の評価が継続すること。
     * CopyOnWriteArrayList の順序に依存するため、例外を投げる側を先に登録する。
     */
    @Test
    void onFireExceptionInOneWatchDoesNotBlockSubsequentWatches() {
        AtomicBoolean secondFired = new AtomicBoolean(false);

        SyslenzAgent.watch("primary_metric")
                .greaterThan(50.0)
                .cooldown(0)
                .onFire(e -> { throw new RuntimeException("first watch boom"); })
                .register();
        SyslenzAgent.watch("secondary_metric")
                .greaterThan(50.0)
                .cooldown(0)
                .onFire(e -> secondFired.set(true))
                .register();

        WatchRegistry registry = SyslenzAgent.watches();
        Map<String, Double> values = new HashMap<>();
        values.put("primary_metric", 80.0);
        values.put("secondary_metric", 80.0);

        assertDoesNotThrow(() -> registry.evaluate(values));
        assertTrue(secondFired.get(),
                "Second watch must still fire even if the first watch's onFire throws");
    }
}
