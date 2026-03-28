package org.unlaxer.infra.syslenz4j;

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
            if (!first) sb.append(",\n");
            first = false;
            appendField(sb, m);
        }

        // Custom application metrics
        for (JvmCollector.Metric m : customMetrics) {
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

    /**
     * Minimal JSON string escaping.
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
