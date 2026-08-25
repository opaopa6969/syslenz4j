package org.unlaxer.infra.syslenz4j;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lightweight microbenchmark for the syslenz4j hot path.
 *
 * <p>Zero-dependency (JUnit only) manual timing benchmark. Not a
 * replacement for JMH, but sufficient to detect order-of-magnitude
 * regressions and improvements in the SNAPSHOT hot path:
 *
 * <ol>
 *   <li>JvmCollector.collect() — MXBean gathering</li>
 *   <li>JsonExporter.exportSnapshot() — JSON serialization</li>
 * </ol>
 *
 * <p>Results are printed to stdout in a parseable format and also
 * asserted to be finite/sane. Run with:
 * <pre>
 * mvn -o test -Dtest=HotPathBenchmark -DfailIfNoTests=false
 * </pre>
 */
class HotPathBenchmark {

    private static final int WARMUP_ITERS = 2_000;
    private static final int MEASURE_ITERS = 20_000;

    @Test
    void benchmarkCollectAndExport() {
        MetricRegistry registry = new MetricRegistry();
        registry.gauge("bench_gauge", () -> 42L);
        registry.counter("bench_counter", () -> 1_000L);
        registry.text("bench_text", () -> "v1.1.1");

        WatchRegistry watches = new WatchRegistry();

        // Warmup
        long warmBytes = 0;
        for (int i = 0; i < WARMUP_ITERS; i++) {
            warmBytes += runOnce(registry, watches);
        }
        assertTrue(warmBytes > 0);

        // Measure: collect phase
        JvmCollector collector = new JvmCollector();
        long collectNanos = 0;
        for (int i = 0; i < MEASURE_ITERS; i++) {
            long t0 = System.nanoTime();
            List<JvmCollector.Metric> jvm = collector.collect();
            List<JvmCollector.Metric> custom = registry.collect();
            long t1 = System.nanoTime();
            collectNanos += (t1 - t0);
            // keep references alive
            if (jvm.isEmpty() && custom.isEmpty()) throw new AssertionError();
        }

        // Measure: export phase (collect once, export many)
        List<JvmCollector.Metric> jvmMetrics = collector.collect();
        List<JvmCollector.Metric> customMetrics = registry.collect();
        watches.evaluate(WatchRegistry.metricValues(jvmMetrics, customMetrics));
        List<WatchRegistry.ActiveAlert> alerts = watches.activeAlerts();

        long exportNanos = 0;
        long exportBytes = 0;
        for (int i = 0; i < MEASURE_ITERS; i++) {
            long t0 = System.nanoTime();
            String json = JsonExporter.exportSnapshot(jvmMetrics, customMetrics, alerts);
            long t1 = System.nanoTime();
            exportNanos += (t1 - t0);
            exportBytes += json.length();
        }

        // Measure: full hot path (collect + evaluate + export)
        long fullNanos = 0;
        long fullBytes = 0;
        for (int i = 0; i < MEASURE_ITERS; i++) {
            long t0 = System.nanoTime();
            List<JvmCollector.Metric> jvm2 = collector.collect();
            List<JvmCollector.Metric> custom2 = registry.collect();
            watches.evaluate(WatchRegistry.metricValues(jvm2, custom2));
            List<WatchRegistry.ActiveAlert> alerts2 = watches.activeAlerts();
            String json2 = JsonExporter.exportSnapshot(jvm2, custom2, alerts2);
            long t1 = System.nanoTime();
            fullNanos += (t1 - t0);
            fullBytes += json2.length();
        }

        // Measure: SyslenzServer.collectSnapshot path (includes the .replace() sweep)
        long serverNanos = 0;
        long serverBytes = 0;
        for (int i = 0; i < MEASURE_ITERS; i++) {
            long t0 = System.nanoTime();
            long bytes = runOnce(registry, watches);
            long t1 = System.nanoTime();
            serverNanos += (t1 - t0);
            serverBytes += bytes;
        }

        // Measure: server path with a REUSED JvmCollector (no per-call instantiation,
        // no per-call ManagementFactory.get*()). This is what the real
        // SyslenzServer does after optimization: it holds one JvmCollector and
        // calls collect() on each SNAPSHOT.
        JvmCollector reused = new JvmCollector();
        long reusedNanos = 0;
        long reusedBytes = 0;
        for (int i = 0; i < MEASURE_ITERS; i++) {
            long t0 = System.nanoTime();
            List<JvmCollector.Metric> jvm3 = reused.collect();
            List<JvmCollector.Metric> custom3 = registry.collect();
            watches.evaluate(WatchRegistry.metricValues(jvm3, custom3));
            List<WatchRegistry.ActiveAlert> alerts3 = watches.activeAlerts();
            String json3 = JsonExporter.exportSnapshot(jvm3, custom3, alerts3);
            json3 = json3.replace("\n", "").replace("\r", "");
            long t1 = System.nanoTime();
            reusedNanos += (t1 - t0);
            reusedBytes += json3.length();
        }

        // Report (parseable: KEY=value ns/op, or us/op)
        double collectUs = collectNanos / 1000.0 / MEASURE_ITERS;
        double exportUs = exportNanos / 1000.0 / MEASURE_ITERS;
        double fullUs = fullNanos / 1000.0 / MEASURE_ITERS;
        double serverUs = serverNanos / 1000.0 / MEASURE_ITERS;
        double reusedUs = reusedNanos / 1000.0 / MEASURE_ITERS;
        double avgBytes = fullBytes / (double) MEASURE_ITERS;

        System.out.println();
        System.out.println("=== HotPathBenchmark (iters=" + MEASURE_ITERS + ") ===");
        System.out.printf("collect_us_per_op  = %.3f%n", collectUs);
        System.out.printf("export_us_per_op   = %.3f%n", exportUs);
        System.out.printf("full_us_per_op     = %.3f%n", fullUs);
        System.out.printf("server_us_per_op   = %.3f  (new JvmCollector each call)%n", serverUs);
        System.out.printf("reused_us_per_op   = %.3f  (reused JvmCollector)%n", reusedUs);
        System.out.printf("avg_snapshot_bytes = %.0f%n", avgBytes);
        System.out.printf("reused_throughput  = %.0f ops/s%n", 1_000_000.0 / reusedUs);
        System.out.println("=== END HotPathBenchmark ===");

        assertTrue(collectUs > 0);
        assertTrue(exportUs > 0);
        assertTrue(serverUs > 0);
        assertTrue(reusedUs > 0);
    }

    @Test
    void benchmarkCollectBreakdown() {
        JvmCollector collector = new JvmCollector();

        // Warmup
        for (int i = 0; i < WARMUP_ITERS; i++) {
            collector.collect();
        }

        // Measure each subsystem in isolation by timing many iterations.
        // We call the public collect() but also break it down by re-implementing
        // the same calls here against the cached MXBean refs (mirroring the
        // fields now stored in JvmCollector). This isolates which MXBean call
        // dominates the collect() cost.
        int n = MEASURE_ITERS;

        long memNanos = 0;
        long gcNanos = 0;
        long threadNanos = 0;
        long runtimeNanos = 0;
        long osNanos = 0;
        long classNanos = 0;
        long bufferNanos = 0;
        long allocNanos = 0;

        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            List<JvmCollector.Metric> m = new ArrayList<>();
            long t1 = System.nanoTime();
            allocNanos += (t1 - t0);
            // we discard m; just measuring ArrayList allocation baseline
        }

        java.lang.management.MemoryMXBean memBean =
                java.lang.management.ManagementFactory.getMemoryMXBean();
        java.util.List<java.lang.management.GarbageCollectorMXBean> gcBeans =
                java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
        java.lang.management.ThreadMXBean threadBean =
                java.lang.management.ManagementFactory.getThreadMXBean();
        java.lang.management.RuntimeMXBean runtimeBean =
                java.lang.management.ManagementFactory.getRuntimeMXBean();
        java.lang.management.OperatingSystemMXBean osBean =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        java.lang.management.ClassLoadingMXBean clBean =
                java.lang.management.ManagementFactory.getClassLoadingMXBean();
        java.util.List<java.lang.management.BufferPoolMXBean> bpBeans =
                java.lang.management.ManagementFactory.getPlatformMXBeans(
                        java.lang.management.BufferPoolMXBean.class);

        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            java.lang.management.MemoryUsage heap = memBean.getHeapMemoryUsage();
            java.lang.management.MemoryUsage nh = memBean.getNonHeapMemoryUsage();
            long t1 = System.nanoTime();
            memNanos += (t1 - t0);
            if (heap == null || nh == null) throw new AssertionError();
        }

        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            long tc = 0, tt = 0;
            for (java.lang.management.GarbageCollectorMXBean gc : gcBeans) {
                long c = gc.getCollectionCount();
                long tm = gc.getCollectionTime();
                if (c >= 0) tc += c;
                if (tm >= 0) tt += tm;
            }
            long t1 = System.nanoTime();
            gcNanos += (t1 - t0);
            if (tc < 0 || tt < 0) throw new AssertionError();
        }

        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            int a = threadBean.getThreadCount();
            int b = threadBean.getPeakThreadCount();
            int c = threadBean.getDaemonThreadCount();
            long[] d = threadBean.findDeadlockedThreads();
            long t1 = System.nanoTime();
            threadNanos += (t1 - t0);
            if (a < 0 || b < 0 || c < 0 || (d != null && d.length < 0)) throw new AssertionError();
        }

        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            long up = runtimeBean.getUptime();
            String name = runtimeBean.getVmName();
            long t1 = System.nanoTime();
            runtimeNanos += (t1 - t0);
            if (up < 0 || name == null) throw new AssertionError();
        }

        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            int p = osBean.getAvailableProcessors();
            double sl = osBean.getSystemLoadAverage();
            double pl = Double.NaN;
            long pt = 0;
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean s =
                        (com.sun.management.OperatingSystemMXBean) osBean;
                pl = s.getProcessCpuLoad();
                pt = s.getProcessCpuTime();
            }
            long t1 = System.nanoTime();
            osNanos += (t1 - t0);
            if (p < 0) throw new AssertionError();
        }

        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            int a = clBean.getLoadedClassCount();
            long b = clBean.getTotalLoadedClassCount();
            long c = clBean.getUnloadedClassCount();
            long t1 = System.nanoTime();
            classNanos += (t1 - t0);
            if (a < 0 || b < 0 || c < 0) throw new AssertionError();
        }

        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            for (java.lang.management.BufferPoolMXBean bp : bpBeans) {
                long u = bp.getMemoryUsed();
                long cap = bp.getTotalCapacity();
                long cnt = bp.getCount();
                if (u < 0 || cap < 0 || cnt < 0) throw new AssertionError();
            }
            long t1 = System.nanoTime();
            bufferNanos += (t1 - t0);
        }

        System.out.println();
        System.out.println("=== CollectBreakdown (iters=" + n + ") ===");
        System.out.printf("alloc_us     = %.3f%n", allocNanos / 1000.0 / n);
        System.out.printf("memory_us    = %.3f%n", memNanos / 1000.0 / n);
        System.out.printf("gc_us        = %.3f%n", gcNanos / 1000.0 / n);
        System.out.printf("thread_us    = %.3f%n", threadNanos / 1000.0 / n);
        System.out.printf("runtime_us   = %.3f%n", runtimeNanos / 1000.0 / n);
        System.out.printf("os_us        = %.3f%n", osNanos / 1000.0 / n);
        System.out.printf("class_us     = %.3f%n", classNanos / 1000.0 / n);
        System.out.printf("buffer_us    = %.3f%n", bufferNanos / 1000.0 / n);
        double total = (memNanos + gcNanos + threadNanos + runtimeNanos +
                osNanos + classNanos + bufferNanos + allocNanos) / 1000.0 / n;
        System.out.printf("total_us     = %.3f%n", total);
        System.out.println("=== END CollectBreakdown ===");
    }

    @Test
    void benchmarkThreadAndOsGranular() {
        java.lang.management.ThreadMXBean threadBean =
                java.lang.management.ManagementFactory.getThreadMXBean();
        java.lang.management.OperatingSystemMXBean osBean =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        com.sun.management.OperatingSystemMXBean sunOs =
                (com.sun.management.OperatingSystemMXBean) osBean;

        for (int i = 0; i < WARMUP_ITERS; i++) {
            threadBean.getThreadCount();
            threadBean.findDeadlockedThreads();
            sunOs.getProcessCpuLoad();
        }

        int n = MEASURE_ITERS;

        long tcNanos = 0, peakNanos = 0, daemonNanos = 0, deadlockNanos = 0;
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime(); int v = threadBean.getThreadCount(); long t1 = System.nanoTime();
            tcNanos += (t1 - t0);
            long t2 = System.nanoTime(); int p = threadBean.getPeakThreadCount(); long t3 = System.nanoTime();
            peakNanos += (t3 - t2);
            long t4 = System.nanoTime(); int d = threadBean.getDaemonThreadCount(); long t5 = System.nanoTime();
            daemonNanos += (t5 - t4);
            long t6 = System.nanoTime(); long[] dl = threadBean.findDeadlockedThreads(); long t7 = System.nanoTime();
            deadlockNanos += (t7 - t6);
            if (v < 0 || p < 0 || d < 0 || (dl != null && dl.length < 0)) throw new AssertionError();
        }

        long procNanos = 0, loadNanos = 0, timeNanos = 0, availNanos = 0, sysLoadNanos = 0;
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime(); double pl = sunOs.getProcessCpuLoad(); long t1 = System.nanoTime();
            procNanos += (t1 - t0);
            long t2 = System.nanoTime(); long pt = sunOs.getProcessCpuTime(); long t3 = System.nanoTime();
            timeNanos += (t3 - t2);
            long t4 = System.nanoTime(); int ap = osBean.getAvailableProcessors(); long t5 = System.nanoTime();
            availNanos += (t5 - t4);
            long t6 = System.nanoTime(); double sl = osBean.getSystemLoadAverage(); long t7 = System.nanoTime();
            sysLoadNanos += (t7 - t6);
            if (ap < 0) throw new AssertionError();
        }

        System.out.println();
        System.out.println("=== ThreadAndOsGranular (iters=" + n + ") ===");
        System.out.printf("threadCount_us      = %.3f%n", tcNanos / 1000.0 / n);
        System.out.printf("peakThreadCount_us  = %.3f%n", peakNanos / 1000.0 / n);
        System.out.printf("daemonThreadCount_us= %.3f%n", daemonNanos / 1000.0 / n);
        System.out.printf("findDeadlocked_us   = %.3f%n", deadlockNanos / 1000.0 / n);
        System.out.printf("availableProc_us    = %.3f%n", availNanos / 1000.0 / n);
        System.out.printf("systemLoadAvg_us    = %.3f%n", sysLoadNanos / 1000.0 / n);
        System.out.printf("processCpuLoad_us   = %.3f%n", procNanos / 1000.0 / n);
        System.out.printf("processCpuTime_us   = %.3f%n", timeNanos / 1000.0 / n);
        System.out.println("=== END ThreadAndOsGranular ===");
    }

    private static long runOnce(MetricRegistry registry, WatchRegistry watches) {
        JvmCollector collector = new JvmCollector();
        List<JvmCollector.Metric> jvmMetrics = collector.collect();
        List<JvmCollector.Metric> customMetrics = registry.collect();
        watches.evaluate(WatchRegistry.metricValues(jvmMetrics, customMetrics));
        List<WatchRegistry.ActiveAlert> alerts = watches.activeAlerts();
        String json = JsonExporter.exportSnapshot(jvmMetrics, customMetrics, alerts);
        return json.replace("\n", "").replace("\r", "").length();
    }
}
