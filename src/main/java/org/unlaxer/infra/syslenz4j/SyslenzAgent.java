package org.unlaxer.infra.syslenz4j;

/**
 * Main entry point for the syslenz Java integration.
 *
 * <p>Embed this in any Java application to export JVM and custom metrics
 * to syslenz. Two modes of operation are supported:
 *
 * <ul>
 *   <li><b>Server mode</b> &mdash; starts a TCP server that responds to
 *       {@code SNAPSHOT} requests (compatible with {@code syslenz --connect}).
 *   <li><b>Stdout mode</b> &mdash; prints a single ProcEntry JSON snapshot
 *       to stdout (compatible with the syslenz plugin protocol).
 * </ul>
 *
 * <h3>Quick start (server mode)</h3>
 * <pre>{@code
 * // In your application's main():
 * SyslenzAgent.startServer(9100);
 *
 * // Then from terminal:
 * // syslenz --connect localhost:9100
 * }</pre>
 *
 * <h3>Quick start (plugin / stdout mode)</h3>
 * <pre>{@code
 * SyslenzAgent.printSnapshot();
 * }</pre>
 */
public final class SyslenzAgent {

    private static final MetricRegistry REGISTRY = new MetricRegistry();
    private static volatile SyslenzServer serverInstance;

    private SyslenzAgent() {
        // utility class
    }

    /**
     * Start a TCP server that serves metric snapshots on the given port.
     *
     * <p>The server runs on a daemon thread and will not prevent the JVM
     * from shutting down. It responds to the {@code SNAPSHOT\n} command
     * with a ProcEntry JSON payload followed by a newline.
     *
     * <p>Calling this method multiple times is safe; subsequent calls are
     * ignored if a server is already running.
     *
     * @param port TCP port to listen on (e.g. 9100)
     */
    public static void startServer(int port) {
        if (serverInstance != null) {
            return;
        }
        synchronized (SyslenzAgent.class) {
            if (serverInstance != null) {
                return;
            }
            SyslenzServer server = new SyslenzServer(port, REGISTRY);
            server.start();
            serverInstance = server;
        }
    }

    /**
     * Print a single metric snapshot to stdout in ProcEntry JSON format
     * and return. Suitable for use as a syslenz plugin.
     */
    public static void printSnapshot() {
        JvmCollector collector = new JvmCollector();
        String json = JsonExporter.export(collector.collect(), REGISTRY.collect());
        System.out.println(json);
    }

    /**
     * Return the shared {@link MetricRegistry} for registering custom
     * application metrics.
     *
     * <pre>{@code
     * SyslenzAgent.registry().gauge("queue_size", () -> myQueue.size());
     * SyslenzAgent.registry().counter("requests_total", requestCounter::get);
     * }</pre>
     *
     * @return the global metric registry
     */
    public static MetricRegistry registry() {
        return REGISTRY;
    }

    /**
     * Stop the TCP server if one is running. Mostly useful for testing.
     */
    public static void stopServer() {
        SyslenzServer s = serverInstance;
        if (s != null) {
            s.stop();
            serverInstance = null;
        }
    }
}
