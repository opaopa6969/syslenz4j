package org.unlaxer.infra.syslenz4j;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;

/**
 * Lightweight TCP server compatible with the {@code syslenz --connect} protocol.
 *
 * <p>Protocol:
 * <ol>
 *   <li>Client connects to the configured port.</li>
 *   <li>Client sends {@code SNAPSHOT\n}.</li>
 *   <li>Server responds with a single-line ProcEntry JSON followed by {@code \n}.</li>
 *   <li>Connection may be kept open for further requests or closed by either side.</li>
 * </ol>
 *
 * <p>The server runs on a single daemon thread using blocking I/O. Each
 * accepted connection is handled sequentially (adequate for a monitoring
 * endpoint that is polled every few seconds). If higher concurrency is
 * needed, wrap the handler in a thread pool.
 */
public class SyslenzServer {

    private static final String SNAPSHOT_CMD = "SNAPSHOT";
    private static final int SO_TIMEOUT_MS = 30_000;

    private final int port;
    private final MetricRegistry registry;
    private volatile ServerSocket serverSocket;
    private volatile Thread serverThread;
    private volatile boolean running;

    SyslenzServer(int port, MetricRegistry registry) {
        this.port = port;
        this.registry = registry;
    }

    /**
     * Start the server on a daemon thread. Returns immediately.
     */
    void start() {
        if (running) return;
        running = true;

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
    }

    // ----- Internal ------------------------------------------------------

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setSoTimeout(1000); // allow periodic running check

            while (running) {
                Socket client;
                try {
                    client = serverSocket.accept();
                } catch (SocketTimeoutException e) {
                    continue; // check running flag
                }
                try {
                    handleClient(client);
                } catch (Exception e) {
                    // Log to stderr but don't crash the server
                    System.err.println("[syslenz-server] client error: " + e.getMessage());
                } finally {
                    try { client.close(); } catch (IOException ignored) {}
                }
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
            }
            // Unknown commands are silently ignored
        }
    }

    private String collectSnapshot() {
        JvmCollector collector = new JvmCollector();
        List<JvmCollector.Metric> jvmMetrics = collector.collect();
        List<JvmCollector.Metric> customMetrics = registry.collect();
        // Ensure single-line JSON (no embedded newlines)
        String json = JsonExporter.export(jvmMetrics, customMetrics);
        return json.replace("\n", "").replace("\r", "");
    }
}
