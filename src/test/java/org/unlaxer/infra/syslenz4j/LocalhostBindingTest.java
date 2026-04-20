package org.unlaxer.infra.syslenz4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the new startServer(port, bindAddress) overload added in v1.1.1.
 */
class LocalhostBindingTest {

    @AfterEach
    void tearDown() {
        SyslenzAgent.stopServer();
        SyslenzAgent.clearWatches();
    }

    @Test
    void startServerWithLocalhostBindAddressAcceptsConnections() throws Exception {
        int port = 19191;
        SyslenzAgent.startServer(port, "127.0.0.1");

        // Give the daemon thread a moment to bind
        Thread.sleep(200);

        // Connect to 127.0.0.1 — must succeed
        try (Socket socket = new Socket("127.0.0.1", port)) {
            assertTrue(socket.isConnected());
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));

            writer.println("SNAPSHOT");
            String response = reader.readLine();

            assertNotNull(response, "Server should return a snapshot JSON line");
            assertTrue(response.contains("\"source\""), "Response should contain ProcEntry source field");
        }
    }

    @Test
    void startServerDefaultBindsToAllInterfaces() throws Exception {
        int port = 19192;
        SyslenzAgent.startServer(port); // default: 0.0.0.0

        Thread.sleep(200);

        // Should be reachable via 127.0.0.1 too (loopback is part of 0.0.0.0)
        try (Socket socket = new Socket("127.0.0.1", port)) {
            assertTrue(socket.isConnected());
        }
    }

    @Test
    void startServerIdempotentWithBindAddress() throws Exception {
        int port = 19193;
        // Calling twice should not throw; second call is silently ignored
        SyslenzAgent.startServer(port, "127.0.0.1");
        assertDoesNotThrow(() -> SyslenzAgent.startServer(port, "127.0.0.1"));

        Thread.sleep(200);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            assertTrue(socket.isConnected());
        }
    }
}
