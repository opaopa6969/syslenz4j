package org.unlaxer.infra.syslenz4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the TCP command protocol beyond the SNAPSHOT happy path:
 * unknown-command error response (SPEC 6.4) and case-insensitivity of
 * the SNAPSHOT command (SPEC 2.4.1).
 */
class ServerCommandProtocolTest {

    @AfterEach
    void tearDown() {
        SyslenzAgent.stopServer();
        SyslenzAgent.clearWatches();
    }

    private static String exchange(int port, String command) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            writer.println(command);
            return reader.readLine();
        }
    }

    /**
     * 未知のコマンドに対して {@code ERROR unknown command: <cmd>} を1行返し、
     * 接続を閉じること (SPEC 6.4)。
     */
    @Test
    void unknownCommandReturnsErrorResponse() throws Exception {
        int port = 19194;
        SyslenzAgent.startServer(port, "127.0.0.1");
        Thread.sleep(200);

        String response = exchange(port, "HELLO");

        assertNotNull(response, "Unknown command must still produce a response line");
        assertTrue(response.startsWith("ERROR unknown command: "),
                "Unknown command should yield an ERROR response, got: " + response);
        assertTrue(response.contains("HELLO"),
                "Error response should echo the offending command");
    }

    /**
     * {@code SNAPSHOT} コマンドは case-insensitive であること (SPEC 2.4.1)。
     * {@code equalsIgnoreCase} への回帰を検知する。
     */
    @Test
    void snapshotCommandIsCaseInsensitive() throws Exception {
        int port = 19195;
        SyslenzAgent.startServer(port, "127.0.0.1");
        Thread.sleep(200);

        String response = exchange(port, "snapshot");

        assertNotNull(response, "Lowercase 'snapshot' must be accepted (case-insensitive)");
        assertTrue(response.contains("\"source\""),
                "Lowercase snapshot must return a ProcEntry JSON, got: " + response);
    }
}
