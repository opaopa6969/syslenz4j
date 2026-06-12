package org.unlaxer.infra.syslenz4j;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight TCP server compatible with the {@code syslenz --connect} protocol.
 *
 * <p>Protocol (mirrors {@code syslenz --serve}):
 * <ol>
 *   <li>Client connects to the configured port.</li>
 *   <li>Client sends {@code SNAPSHOT\n}.</li>
 *   <li>Server responds with a single-line Snapshot JSON
 *       ({@code {"timestamp": ..., "entries": {"jvm": ...}}}) followed by
 *       {@code \n}, then closes the connection (one request per connection —
 *       the syslenz client reads until EOF).</li>
 *   <li>{@code QUIT} or an empty line closes the connection without a response.</li>
 * </ol>
 *
 * <p>The server runs on a single daemon thread for accepting connections.
 * Each accepted connection is dispatched to a worker thread pool so that
 * idle or slow clients cannot block other connections.
 */
public class SyslenzServer {

    private static final String SNAPSHOT_CMD = "SNAPSHOT";
    private static final int SO_TIMEOUT_MS = 30_000;

    private final int port;
    private final String bindAddress;
    private final MetricRegistry registry;
    private final WatchRegistry watchRegistry;
    private volatile ServerSocket serverSocket;
    private volatile Thread serverThread;
    private volatile boolean running;
    private ExecutorService workerPool;

    SyslenzServer(int port, MetricRegistry registry) {
        this(port, "127.0.0.1", registry, null);
    }

    SyslenzServer(int port, String bindAddress, MetricRegistry registry, WatchRegistry watchRegistry) {
        this.port = port;
        this.bindAddress = bindAddress != null ? bindAddress : "127.0.0.1";
        this.registry = registry;
        this.watchRegistry = watchRegistry;
    }

    /**
     * Start the server on a daemon thread. Returns immediately.
     */
    void start() {
        if (running) return;
        running = true;

        workerPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "syslenz-worker-" + port);
            t.setDaemon(true);
            return t;
        });

        serverThread = new Thread(this::acceptLoop, "syslenz-server-" + port);
        serverThread.setDaemon(true);
        serverThread.start();
    }

    /**
     * Stop the server and close the listening socket.
     */
    void stop() {
        running = false;
        try {
            ServerSocket ss = serverSocket;
            if (ss != null && !ss.isClosed()) {
                ss.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        ExecutorService pool = workerPool;
        if (pool != null) {
            pool.shutdown();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ----- Internal ------------------------------------------------------

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
            serverSocket.setSoTimeout(1000); // allow periodic running check

            while (running) {
                Socket client;
                try {
                    client = serverSocket.accept();
                } catch (SocketTimeoutException e) {
                    continue; // check running flag
                }
                final Socket finalClient = client;
                workerPool.submit(() -> {
                    try {
                        handleClient(finalClient);
                    } catch (Exception e) {
                        // Log to stderr but don't crash the server
                        System.err.println("[syslenz-server] client error: " + e.getMessage());
                    } finally {
                        try { finalClient.close(); } catch (IOException ignored) {}
                    }
                });
            }
        } catch (SocketException e) {
            if (running) {
                System.err.println("[syslenz-server] socket error: " + e.getMessage());
            }
            // else: socket closed by stop(), expected
        } catch (IOException e) {
            System.err.println("[syslenz-server] failed to start on port " + port + ": " + e.getMessage());
        }
    }

    private void handleClient(Socket client) throws IOException {
        client.setSoTimeout(SO_TIMEOUT_MS);
        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
        OutputStream out = client.getOutputStream();

        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (SNAPSHOT_CMD.equalsIgnoreCase(trimmed)) {
                String json = collectSnapshot();
                // Send response as a single line followed by newline
                out.write(json.getBytes("UTF-8"));
                out.write('\n');
                out.flush();
                // One request per connection: the syslenz client reads the
                // response with read_to_string (until EOF), so keeping the
                // connection open would stall it until its read timeout.
                break;
            } else if (trimmed.isEmpty() || "QUIT".equalsIgnoreCase(trimmed)) {
                break;
            } else {
                // Unknown command: return an error response and close the connection
                String error = "ERROR unknown command: " + trimmed + "\n";
                out.write(error.getBytes("UTF-8"));
                out.flush();
                break;
            }
        }
    }

    private String collectSnapshot() {
        JvmCollector collector = new JvmCollector();
        List<JvmCollector.Metric> jvmMetrics = collector.collect();
        List<JvmCollector.Metric> customMetrics = registry.collect();

        // Drive WatchRegistry evaluation on each snapshot
        List<WatchRegistry.ActiveAlert> alerts = List.of();
        if (watchRegistry != null) {
            watchRegistry.evaluate(WatchRegistry.metricValues(jvmMetrics, customMetrics));
            alerts = watchRegistry.activeAlerts();
        }

        // Ensure single-line JSON (no embedded newlines)
        String json = JsonExporter.exportSnapshot(jvmMetrics, customMetrics, alerts);
        return json.replace("\n", "").replace("\r", "");
    }
}
