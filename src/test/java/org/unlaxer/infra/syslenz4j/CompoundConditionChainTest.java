package org.unlaxer.infra.syslenz4j;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that CompoundCondition.greaterThan() returns the parent WatchCondition (was returning null in v1.1.0).
 */
class CompoundConditionChainTest {

    @BeforeEach
    void tearDown() {
        SyslenzAgent.clearWatches();
    }

    @Test
    void compoundGreaterThanDoesNotBreakChain() {
        // Previously this would NPE or not compile because and().greaterThan() returned null
        AtomicBoolean fired = new AtomicBoolean(false);

        // This fluent chain must not throw NullPointerException
        assertDoesNotThrow(() ->
            SyslenzAgent.watch("queue_size")
                    .greaterThan(10_000.0)
                    .and("error_rate").greaterThan(5.0)
                    .severity(Severity.CRITICAL)
                    .cooldown(0)
                    .onFire(e -> fired.set(true))
                    .register()
        );

        // Both conditions met -> should fire
        WatchRegistry registry = SyslenzAgent.watches();
        Map<String, Double> values = new HashMap<>();
        values.put("queue_size", 20_000.0);
        values.put("error_rate", 10.0);
        registry.evaluate(values);

        assertTrue(fired.get(), "Compound condition should fire when both metrics exceed thresholds");
    }

    @Test
    void compoundConditionDoesNotFireWhenOnlyPrimaryMatches() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("queue_size")
                .greaterThan(10_000.0)
                .and("error_rate").greaterThan(5.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        WatchRegistry registry = SyslenzAgent.watches();
        Map<String, Double> values = new HashMap<>();
        values.put("queue_size", 20_000.0);
        values.put("error_rate", 1.0); // below threshold
        registry.evaluate(values);

        assertFalse(fired.get(), "Should not fire when compound secondary condition is not met");
    }

    @Test
    void compoundLessThanChainWorks() {
        AtomicBoolean fired = new AtomicBoolean(false);

        SyslenzAgent.watch("available_memory")
                .lessThan(100.0)
                .and("cpu_load").greaterThan(90.0)
                .cooldown(0)
                .onFire(e -> fired.set(true))
                .register();

        WatchRegistry registry = SyslenzAgent.watches();
        Map<String, Double> values = new HashMap<>();
        values.put("available_memory", 50.0);
        values.put("cpu_load", 95.0);
        registry.evaluate(values);

        assertTrue(fired.get(), "lessThan compound chain should fire correctly");
    }
}
