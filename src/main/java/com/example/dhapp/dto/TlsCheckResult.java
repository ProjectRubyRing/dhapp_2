package com.example.dhapp.dto;

/**
 * 設定確認 API（{@code GET /api/tls/config}）の個々のチェック結果。
 *
 * <ul>
 *   <li>{@link #STATUS_OK} … 期待どおり設定されている</li>
 *   <li>{@link #STATUS_NG} … 設定されていない／期待と異なる（{@code hint} に対処方法）</li>
 *   <li>{@link #STATUS_UNKNOWN} … 判定材料が取得できなかった（管理モデルが読めない等）。
 *       この場合は {@code hint} に、jboss-cli で手動確認するコマンドを載せる</li>
 * </ul>
 */
public class TlsCheckResult {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_NG = "NG";
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /** チェック識別子（例: {@code elytron.client-ssl-context}）。 */
    private final String name;

    /** 分類（{@code jvm-truststore} / {@code elytron} / {@code jvm-ssl-context}）。 */
    private final String category;

    private final String status;

    /** 期待している状態。 */
    private final String expected;

    /** 実際に検出した状態。 */
    private final String actual;

    /** 補足情報。 */
    private final String detail;

    /** NG / UNKNOWN のときの対処方法。 */
    private final String hint;

    private TlsCheckResult(String name, String category, String status,
            String expected, String actual, String detail, String hint) {
        this.name = name;
        this.category = category;
        this.status = status;
        this.expected = expected;
        this.actual = actual;
        this.detail = detail;
        this.hint = hint;
    }

    public static TlsCheckResult ok(String name, String category, String expected, String actual, String detail) {
        return new TlsCheckResult(name, category, STATUS_OK, expected, actual, detail, null);
    }

    public static TlsCheckResult ng(String name, String category, String expected, String actual,
            String detail, String hint) {
        return new TlsCheckResult(name, category, STATUS_NG, expected, actual, detail, hint);
    }

    public static TlsCheckResult unknown(String name, String category, String expected, String actual,
            String detail, String hint) {
        return new TlsCheckResult(name, category, STATUS_UNKNOWN, expected, actual, detail, hint);
    }

    /**
     * 条件によって OK / NG を切り替える。
     */
    public static TlsCheckResult of(boolean ok, String name, String category, String expected, String actual,
            String detail, String hint) {
        return ok
                ? ok(name, category, expected, actual, detail)
                : ng(name, category, expected, actual, detail, hint);
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public String getStatus() {
        return status;
    }

    public String getExpected() {
        return expected;
    }

    public String getActual() {
        return actual;
    }

    public String getDetail() {
        return detail;
    }

    public String getHint() {
        return hint;
    }
}
