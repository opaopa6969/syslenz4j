package org.unlaxer.infra.syslenz4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

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

    // ── line length limit (DoS hardening) ────────────────────────────────────

    /**
     * {@code SNAPSHOT} などの正当なコマンドは {@link SyslenzServer#MAX_COMMAND_BYTES}
     * 以内であれば正常に処理されること。上限値付近の入力でも正常系が壊れない回帰保証。
     */
    @Test
    void commandUnderLimitIsProcessed() throws Exception {
        int port = 19196;
        SyslenzAgent.startServer(port, "127.0.0.1");
        Thread.sleep(200);

        String response = exchange(port, "SNAPSHOT");

        assertNotNull(response, "SNAPSHOT within the limit must be processed");
        assertTrue(response.contains("\"source\""),
                "SNAPSHOT must return a ProcEntry JSON, got: " + response);
    }

    /**
     * {@link SyslenzServer#MAX_COMMAND_BYTES} を超える1行(改行なしでも超える1行)を送ると、
     * 応答を返さず接続を閉じること。改行を送らないクライアントが
     * {@code readLine()} のバッファでヒープを無制限に消費するDoS経路を塞ぐ。
     *
     * <p>再現手順:
     * <ol>
     *   <li>{@code startServer(port, "0.0.0.0")} などでリモート公開している状態で</li>
     *   <li>攻撃者がソケットを開き、{@code MAX_COMMAND_BYTES}+1 バイトの非改行データを送る</li>
     *   <li>旧実装では {@code readLine()} がヒープを伸ばし続けた(改行が来ない限り)</li>
     * </ol>
     */
    @Test
    void oversizedLineClosesConnectionWithoutResponse() throws Exception {
        int port = 19197;
        SyslenzAgent.startServer(port, "127.0.0.1");
        Thread.sleep(200);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            // MAX_COMMAND_BYTES + 1 バイトの非改行データを送る(改行なし)
            byte[] flood = new byte[SyslenzServer.MAX_COMMAND_BYTES + 16];
            java.util.Arrays.fill(flood, (byte) 'A');
            out.write(flood);
            out.flush();

            // 応答が無く、接続が閉じられること
            InputStream in = socket.getInputStream();
            int first = in.read();
            assertEquals(-1, first,
                    "Oversized command line must close the connection with no response, "
                            + "denying an attacker an amplification channel");
        }
    }

    /**
     * 上限超過の接続を処理した後もサーバは次の接続を受け付けられること。
     * DoS 入力1件でサーバが停止しないことの保証。
     */
    @Test
    void serverSurvivesOversizedLine() throws Exception {
        int port = 19198;
        SyslenzAgent.startServer(port, "127.0.0.1");
        Thread.sleep(200);

        // 1回目: 上限超過の入力で接続を閉じる
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            byte[] flood = new byte[SyslenzServer.MAX_COMMAND_BYTES + 16];
            java.util.Arrays.fill(flood, (byte) 'A');
            out.write(flood);
            out.flush();
            // read until EOF
            InputStream in = socket.getInputStream();
            while (in.read() != -1) {
                // drain
            }
        }

        // 2回目: 正常な SNAPSHOT が処理されること
        String response = exchange(port, "SNAPSHOT");
        assertNotNull(response, "Server must still accept new connections after an oversized line");
        assertTrue(response.contains("\"source\""),
                "Server must still process SNAPSHOT after handling an oversized line, got: " + response);
    }

    /** Slow clients must not cause more than the bounded worker count or wait indefinitely. */
    @Test
    void rejectsConnectionsWhenWorkerPoolIsFull() throws Exception {
        int port = 19199;
        SyslenzAgent.startServer(port, "127.0.0.1");
        Thread.sleep(200);

        List<Socket> slowClients = new ArrayList<>();
        try {
            for (int i = 0; i < SyslenzServer.MAX_WORKER_THREADS; i++) {
                slowClients.add(new Socket("127.0.0.1", port));
            }

            long deadline = System.currentTimeMillis() + 3_000;
            while (workerCount(port) < SyslenzServer.MAX_WORKER_THREADS
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(SyslenzServer.MAX_WORKER_THREADS, workerCount(port),
                    "slow clients should occupy at most the configured worker count");

            try (Socket rejected = new Socket("127.0.0.1", port)) {
                rejected.setSoTimeout(2_000);
                assertEquals(-1, rejected.getInputStream().read(),
                        "connections over the worker limit must be closed without a response");
            } catch (SocketTimeoutException e) {
                fail("connection over the worker limit was left hanging", e);
            }
        } finally {
            for (Socket socket : slowClients) {
                socket.close();
            }
        }
    }

    private static int workerCount(int port) {
        String prefix = "syslenz-worker-" + port;
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().equals(prefix) && thread.isAlive()) count++;
        }
        return count;
    }
}
