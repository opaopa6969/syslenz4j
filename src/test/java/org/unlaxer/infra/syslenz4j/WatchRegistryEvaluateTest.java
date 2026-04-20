package org.unlaxer.infra.syslenz4j;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
}
