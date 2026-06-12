package org.unlaxer.infra.syslenz4j;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JsonExporter: RFC 8259 validity (no NaN/Infinity literals)
 * and terminal-safety of exported strings (no raw control characters
 * reaching the client after JSON unescaping).
 */
class JsonExporterTest {

    private static JvmCollector.Metric metric(String name, Object value, String type) {
        return new JvmCollector.Metric(name, value, type, null, "test metric");
    }

    // ── non-finite numeric values ────────────────────────────────────────────

    @Test
    void nanFloatMetricIsOmitted() {
        String json = JsonExporter.export(List.of(metric("ratio", Double.NaN, "Float")));

        assertFalse(json.contains("NaN"));
        assertFalse(json.contains("ratio"));
    }

    @Test
    void infiniteFloatMetricIsOmitted() {
        String json = JsonExporter.export(List.of(
                metric("rate", Double.POSITIVE_INFINITY, "Float"),
                metric("delta", Double.NEGATIVE_INFINITY, "Float")));

        assertFalse(json.contains("Infinity"));
    }

    @Test
    void nanDurationMetricIsOmitted() {
        String json = JsonExporter.export(List.of(metric("elapsed", Double.NaN, "Duration")));

        assertFalse(json.contains("NaN"));
    }

    @Test
    void finiteFloatMetricIsKept() {
        String json = JsonExporter.export(List.of(metric("ratio", 0.75, "Float")));

        assertTrue(json.contains("{\"Float\": 0.75}"));
    }

    @Test
    void omittedMetricDoesNotLeaveDanglingComma() {
        String json = JsonExporter.export(List.of(
                metric("good", 1.0, "Float"),
                metric("bad", Double.NaN, "Float")));

        assertFalse(json.contains(",\n\n"));
        assertFalse(json.replace("\n", "").contains(",  ]"));
    }

    // ── control characters and ANSI escape sequences ─────────────────────────

    @Test
    void ansiColorSequenceIsStrippedFromTextValue() {
        String colored = "\u001b[31mERROR\u001b[0m disk full";
        String json = JsonExporter.export(List.of(metric("status", colored, "Text")));

        assertTrue(json.contains("\"Text\": \"ERROR disk full\""));
        assertFalse(json.contains("\\u001b"));
        assertFalse(json.contains("\u001b"));
    }

    @Test
    void bareControlCharactersBecomeSpaces() {
        String json = JsonExporter.export(List.of(metric("status", "a\nb\tc\u0007d\u007fe", "Text")));

        assertTrue(json.contains("\"Text\": \"a b c d e\""));
    }

    @Test
    void quotesAndBackslashesAreStillEscaped() {
        String json = JsonExporter.export(List.of(metric("status", "say \"hi\" C:\\tmp", "Text")));

        assertTrue(json.contains("say \\\"hi\\\" C:\\\\tmp"));
    }
}
