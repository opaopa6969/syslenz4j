package org.unlaxer.infra.syslenz4j;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Converts collected metrics into the syslenz ProcEntry JSON format.
 *
 * <p>Output example:
 * <pre>{@code
 * {
 *   "source": "jvm/pid-12345",
 *   "fields": [
 *     {"name": "heap_used", "value": {"Bytes": 524288000}, "unit": null, "description": "Current heap memory usage"},
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * <p>No external JSON library is used; output is built via simple string
 * concatenation. This keeps the library dependency-free.
 */
public class JsonExporter {

    private JsonExporter() {
        // utility class
    }

    /**
     * Export JVM metrics and custom metrics as a ProcEntry JSON string.
     *
     * @param jvmMetrics    metrics from {@link JvmCollector}
     * @param customMetrics metrics from {@link MetricRegistry}
     * @return a JSON string in ProcEntry format
     */
    public static String export(List<JvmCollector.Metric> jvmMetrics,
                                List<JvmCollector.Metric> customMetrics) {
        long pid = ProcessHandle.current().pid();
        StringBuilder sb = new StringBuilder(4096);

        sb.append("{\n");
        sb.append("  \"source\": \"jvm/pid-").append(pid).append("\",\n");
        sb.append("  \"fields\": [\n");

        boolean first = true;

        // JVM metrics
        for (JvmCollector.Metric m : jvmMetrics) {
            if (skip(m)) continue;
            if (!first) sb.append(",\n");
            first = false;
            appendField(sb, m);
        }

        // Custom application metrics
        for (JvmCollector.Metric m : customMetrics) {
            if (skip(m)) continue;
            if (!first) sb.append(",\n");
            first = false;
            appendField(sb, m);
        }

        sb.append("\n  ]\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Export only JVM metrics (convenience overload).
     */
    public static String export(List<JvmCollector.Metric> jvmMetrics) {
        return export(jvmMetrics, List.of());
    }

    /**
     * Export metrics wrapped in the syslenz {@code Snapshot} shape, which is
     * what {@code syslenz --connect} actually parses:
     *
     * <pre>{@code
     * {
     *   "timestamp": "2026-06-12T01:02:03.456Z",
     *   "entries": { "jvm": { <ProcEntry> } },
     *   "alerts": [ {"name": ..., "severity": "warning", ...} ]
     * }
     * }</pre>
     *
     * <p>The bare ProcEntry form ({@link #export(List, List)}) remains for
     * plugin/stdout mode; this wrapper is for the TCP server. The
     * {@code alerts} key is omitted when no watch is firing, keeping the
     * output identical to older versions in the common case.
     */
    static String exportSnapshot(List<JvmCollector.Metric> jvmMetrics,
                                 List<JvmCollector.Metric> customMetrics,
                                 List<WatchRegistry.ActiveAlert> alerts) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{");
        sb.append("\"timestamp\": \"")
          .append(DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
          .append("\", ");
        sb.append("\"entries\": {\"jvm\": ");
        sb.append(export(jvmMetrics, customMetrics));
        sb.append("}");
        if (alerts != null && !alerts.isEmpty()) {
            sb.append(", \"alerts\": [");
            boolean first = true;
            for (WatchRegistry.ActiveAlert a : alerts) {
                if (!Double.isFinite(a.value())) continue;
                if (!first) sb.append(", ");
                first = false;
                sb.append("{\"name\": \"").append(escapeJson(a.name())).append("\", ");
                sb.append("\"severity\": \"").append(a.severity().label()).append("\", ");
                sb.append("\"value\": ").append(a.value()).append(", ");
                sb.append("\"message\": \"").append(escapeJson(a.message())).append("\", ");
                sb.append("\"condition\": \"").append(escapeJson(a.condition())).append("\", ");
                sb.append("\"since\": \"")
                  .append(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(a.sinceEpochMs())))
                  .append("\"}");
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * A metric whose numeric value is NaN or infinite cannot be represented
     * in RFC 8259 JSON ({@code NaN}/{@code Infinity} literals break strict
     * parsers such as serde_json on the syslenz side), so it is omitted from
     * the snapshot — the same policy as e.g. a negative system load average.
     */
    private static boolean skip(JvmCollector.Metric m) {
        if (("Float".equals(m.type) || "Duration".equals(m.type))
                && m.value instanceof Number) {
            return !Double.isFinite(((Number) m.value).doubleValue());
        }
        return false;
    }

    private static void appendField(StringBuilder sb, JvmCollector.Metric m) {
        sb.append("    {\"name\": \"").append(escapeJson(m.name)).append("\", ");
        sb.append("\"value\": ").append(formatValue(m)).append(", ");
        sb.append("\"unit\": ").append(m.unit != null ? "\"" + escapeJson(m.unit) + "\"" : "null").append(", ");
        sb.append("\"description\": \"").append(escapeJson(m.description)).append("\"}");
    }

    private static String formatValue(JvmCollector.Metric m) {
        switch (m.type) {
            case "Bytes":
                return "{\"Bytes\": " + ((Number) m.value).longValue() + "}";
            case "Integer":
                return "{\"Integer\": " + ((Number) m.value).longValue() + "}";
            case "Float":
                return "{\"Float\": " + ((Number) m.value).doubleValue() + "}";
            case "Duration":
                return "{\"Duration\": " + ((Number) m.value).doubleValue() + "}";
            case "Text":
                return "{\"Text\": \"" + escapeJson(String.valueOf(m.value)) + "\"}";
            default:
                return "{\"Text\": \"" + escapeJson(String.valueOf(m.value)) + "\"}";
        }
    }

    /** Matches ANSI escape sequences: CSI (ESC[...cmd) and other ESC-prefixed forms. */
    private static final java.util.regex.Pattern ANSI_SEQUENCE =
            java.util.regex.Pattern.compile("\\u001B(\\[[0-9;?]*[ -/]*[@-~]|[@-Z\\\\^_])");

    /**
     * JSON string escaping, hardened for terminal display.
     *
     * <p>Escaping control characters as {@code \\uXXXX} alone is not enough:
     * the JSON stays valid, but the syslenz client unescapes them and writes
     * the raw bytes to the terminal, corrupting the TUI (e.g. ANSI color
     * codes leaking in via a Text metric). So ANSI escape sequences are
     * stripped entirely and any remaining control character is replaced
     * with a space.
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        s = ANSI_SEQUENCE.matcher(s).replaceAll("");
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(' ');
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
