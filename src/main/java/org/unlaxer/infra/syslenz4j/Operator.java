package org.unlaxer.infra.syslenz4j;

/**
 * 比較演算子。監視条件で使用する。
 *
 * <pre>{@code
 * SyslenzAgent.watch("heap_used_pct")
 *     .greaterThan(80.0)  // Operator.GREATER_THAN が使われる
 *     .register();
 * }</pre>
 */
public enum Operator {
    GREATER_THAN(">", "より大きい", "Greater than threshold"),
    LESS_THAN("<", "より小さい", "Less than threshold"),
    GREATER_THAN_OR_EQUAL(">=", "以上", "Greater than or equal to threshold"),
    LESS_THAN_OR_EQUAL("<=", "以下", "Less than or equal to threshold"),
    EQUAL("==", "等しい", "Equal to threshold"),
    NOT_EQUAL("!=", "等しくない", "Not equal to threshold"),
    OUTSIDE_RANGE("outside", "範囲外", "Outside the specified range [min, max]"),
    INSIDE_RANGE("inside", "範囲内", "Inside the specified range [min, max]");

    private final String symbol;
    private final String descriptionJa;
    private final String descriptionEn;

    Operator(String symbol, String descriptionJa, String descriptionEn) {
        this.symbol = symbol;
        this.descriptionJa = descriptionJa;
        this.descriptionEn = descriptionEn;
    }

    public String symbol() { return symbol; }
    public String descriptionJa() { return descriptionJa; }
    public String descriptionEn() { return descriptionEn; }
    public String description() { return descriptionEn; }

    @Override
    public String toString() { return symbol; }
}
