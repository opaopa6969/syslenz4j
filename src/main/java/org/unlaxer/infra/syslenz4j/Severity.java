package org.unlaxer.infra.syslenz4j;

/**
 * アラートの重要度。syslenz 本体の severity と対応。
 *
 * <pre>{@code
 * SyslenzAgent.watch("heap_used_pct")
 *     .greaterThan(90.0)
 *     .severity(Severity.CRITICAL)  // 即座にアクション必要
 *     .register();
 * }</pre>
 */
public enum Severity {
    INFO("info", "情報", "Informational — noteworthy but not necessarily a problem"),
    WARNING("warning", "警告", "Warning — attention needed, issue may be developing"),
    CRITICAL("critical", "危険", "Critical — immediate action required, system may crash or degrade");

    private final String label;
    private final String descriptionJa;
    private final String descriptionEn;

    Severity(String label, String descriptionJa, String descriptionEn) {
        this.label = label;
        this.descriptionJa = descriptionJa;
        this.descriptionEn = descriptionEn;
    }

    public String label() { return label; }
    public String descriptionJa() { return descriptionJa; }
    public String descriptionEn() { return descriptionEn; }
    public String description() { return descriptionEn; }

    @Override
    public String toString() { return label; }
}
