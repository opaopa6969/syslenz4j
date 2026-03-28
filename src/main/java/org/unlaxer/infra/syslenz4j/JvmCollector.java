package org.unlaxer.infra.syslenz4j;

import java.lang.management.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects JVM metrics via {@code java.lang.management} MXBeans.
 *
 * <p>All data is gathered from standard JDK APIs with no external
 * dependencies. The collected metrics include:
 *
 * <ul>
 *   <li>Heap and non-heap memory (MemoryMXBean)</li>
 *   <li>GC count and time per collector (GarbageCollectorMXBean)</li>
 *   <li>Thread count, peak, daemon count, deadlock detection (ThreadMXBean)</li>
 *   <li>Uptime, VM name (RuntimeMXBean)</li>
 *   <li>System load, available processors, process CPU (OperatingSystemMXBean)</li>
 *   <li>Loaded class count (ClassLoadingMXBean)</li>
 *   <li>Direct and mapped buffer pool usage (BufferPoolMXBean)</li>
 * </ul>
 */
public class JvmCollector {

    /**
     * A single named metric with a typed value.
     */
    public static class Metric {
        public final String name;
        public final Object value;
        public final String type;       // "Bytes", "Integer", "Float", "Duration", "Text"
        public final String unit;       // nullable
        public final String description;

        public Metric(String name, Object value, String type, String unit, String description) {
            this.name = name;
            this.value = value;
            this.type = type;
            this.unit = unit;
            this.description = description;
        }
    }

    /**
     * Collect all JVM metrics and return them as a list.
     */
    public List<Metric> collect() {
        List<Metric> metrics = new ArrayList<>();
        collectMemory(metrics);
        collectGc(metrics);
        collectThreads(metrics);
        collectRuntime(metrics);
        collectOs(metrics);
        collectClassLoading(metrics);
        collectBufferPools(metrics);
        return metrics;
    }

    // ----- Memory --------------------------------------------------------

    private void collectMemory(List<Metric> metrics) {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();

        MemoryUsage heap = mem.getHeapMemoryUsage();
        metrics.add(new Metric("heap_used", heap.getUsed(), "Bytes", null,
                "Current heap memory usage"));
        metrics.add(new Metric("heap_committed", heap.getCommitted(), "Bytes", null,
                "Heap memory committed by the OS"));
        metrics.add(new Metric("heap_max", heap.getMax(), "Bytes", null,
                "Maximum heap memory (-Xmx)"));

        MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();
        metrics.add(new Metric("non_heap_used", nonHeap.getUsed(), "Bytes", null,
                "Non-heap memory usage (metaspace, code cache, etc.)"));
        metrics.add(new Metric("non_heap_committed", nonHeap.getCommitted(), "Bytes", null,
                "Non-heap memory committed by the OS"));
    }

    // ----- Garbage Collection --------------------------------------------

    private void collectGc(List<Metric> metrics) {
        long totalCount = 0;
        long totalTimeMs = 0;

        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            String safeName = gc.getName().replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
            long count = gc.getCollectionCount();
            long timeMs = gc.getCollectionTime();

            if (count >= 0) {
                metrics.add(new Metric("gc_" + safeName + "_count", count, "Integer", "count",
                        "GC count for " + gc.getName()));
                totalCount += count;
            }
            if (timeMs >= 0) {
                double timeSec = timeMs / 1000.0;
                metrics.add(new Metric("gc_" + safeName + "_time", timeSec, "Duration", "s",
                        "GC time for " + gc.getName()));
                totalTimeMs += timeMs;
            }
        }

        metrics.add(new Metric("gc_total_count", totalCount, "Integer", "count",
                "Total GC count across all collectors"));
        metrics.add(new Metric("gc_total_time", totalTimeMs / 1000.0, "Duration", "s",
                "Total GC time across all collectors"));
    }

    // ----- Threads -------------------------------------------------------

    private void collectThreads(List<Metric> metrics) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();

        metrics.add(new Metric("thread_count", threads.getThreadCount(), "Integer", "count",
                "Current live thread count"));
        metrics.add(new Metric("thread_peak", threads.getPeakThreadCount(), "Integer", "count",
                "Peak live thread count since JVM start"));
        metrics.add(new Metric("thread_daemon", threads.getDaemonThreadCount(), "Integer", "count",
                "Current daemon thread count"));

        // Deadlock detection
        long[] deadlocked = threads.findDeadlockedThreads();
        int deadlockCount = (deadlocked == null) ? 0 : deadlocked.length;
        metrics.add(new Metric("thread_deadlocked", deadlockCount, "Integer", "count",
                "Number of threads in deadlock (0 = healthy)"));
    }

    // ----- Runtime -------------------------------------------------------

    private void collectRuntime(List<Metric> metrics) {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();

        double uptimeSec = runtime.getUptime() / 1000.0;
        metrics.add(new Metric("uptime", uptimeSec, "Duration", "s",
                "JVM uptime"));
        metrics.add(new Metric("vm_name", runtime.getVmName(), "Text", null,
                "JVM implementation name"));
    }

    // ----- Operating System ----------------------------------------------

    private void collectOs(List<Metric> metrics) {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

        metrics.add(new Metric("available_processors", os.getAvailableProcessors(), "Integer", "count",
                "Number of processors available to the JVM"));

        double sysLoad = os.getSystemLoadAverage();
        if (sysLoad >= 0) {
            metrics.add(new Metric("system_load_average", sysLoad, "Float", null,
                    "System load average (1 minute)"));
        }

        // Try to get process CPU load via com.sun.management (available in
        // Oracle/OpenJDK but not guaranteed by the spec).
        if (os instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOs =
                    (com.sun.management.OperatingSystemMXBean) os;
            double processCpu = sunOs.getProcessCpuLoad();
            if (processCpu >= 0) {
                metrics.add(new Metric("process_cpu_load", processCpu * 100.0, "Float", "%",
                        "Process CPU usage (0-100%)"));
            }
            long processCpuTime = sunOs.getProcessCpuTime();
            if (processCpuTime >= 0) {
                metrics.add(new Metric("process_cpu_time", processCpuTime / 1_000_000_000.0, "Duration", "s",
                        "Total CPU time used by the process"));
            }
        }
    }

    // ----- Class Loading -------------------------------------------------

    private void collectClassLoading(List<Metric> metrics) {
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();

        metrics.add(new Metric("classes_loaded", cl.getLoadedClassCount(), "Integer", "count",
                "Currently loaded class count"));
        metrics.add(new Metric("classes_total_loaded", cl.getTotalLoadedClassCount(), "Integer", "count",
                "Total classes loaded since JVM start"));
        metrics.add(new Metric("classes_unloaded", cl.getUnloadedClassCount(), "Integer", "count",
                "Total classes unloaded since JVM start"));
    }

    // ----- Buffer Pools --------------------------------------------------

    private void collectBufferPools(List<Metric> metrics) {
        List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        for (BufferPoolMXBean pool : pools) {
            String safeName = pool.getName().replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();

            long used = pool.getMemoryUsed();
            if (used >= 0) {
                metrics.add(new Metric("buffer_" + safeName + "_used", used, "Bytes", null,
                        "Memory used by " + pool.getName() + " buffers"));
            }
            long capacity = pool.getTotalCapacity();
            if (capacity >= 0) {
                metrics.add(new Metric("buffer_" + safeName + "_capacity", capacity, "Bytes", null,
                        "Total capacity of " + pool.getName() + " buffers"));
            }
            long count = pool.getCount();
            if (count >= 0) {
                metrics.add(new Metric("buffer_" + safeName + "_count", count, "Integer", "count",
                        "Number of " + pool.getName() + " buffers"));
            }
        }
    }
}
