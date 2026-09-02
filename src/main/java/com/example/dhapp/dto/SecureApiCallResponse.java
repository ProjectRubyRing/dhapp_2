package com.example.dhapp.dto;

import java.util.List;

/**
 * {@code GET /api/secure-api/call} のレスポンス。
 *
 * <p>JVM 管理のトラストストアと JBoss EAP(Elytron) 管理のトラストストアで、
 * それぞれ同じ HTTPS 接続先（compose の {@code secure-api} サービス）へ接続した結果を並べ、
 * どちらの証明書取り込みが効いているかを比較できるようにする。</p>
 */
public class SecureApiCallResponse {

    private String requestId;

    /** 処理時刻（ISO-8601）。 */
    private String timestamp;

    /** 接続先の種別。{@code direct}（secure-api へ直接） / {@code alb} / {@code custom}。 */
    private String target;

    private String url;
    private String method;

    /**
     * 総合ステータス。{@code SUCCESS}（試した経路が全て成功） /
     * {@code PARTIAL}（一部だけ成功） / {@code FAILED}（全て失敗）。
     */
    private String status;

    /** トラストストアごとの結果（JVM / JBOSS_EAP / NONE）。 */
    private List<TrustStoreCallResult> results;

    /** 2 つのトラストストアの結果を突き合わせた比較。 */
    private SecureApiComparison comparison;

    /** ログ・コンソールへ出力したものと同一のテキストレポート（画面表示用）。 */
    private String report;

    private long elapsedMs;

    public SecureApiCallResponse() {
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<TrustStoreCallResult> getResults() {
        return results;
    }

    public void setResults(List<TrustStoreCallResult> results) {
        this.results = results;
    }

    public SecureApiComparison getComparison() {
        return comparison;
    }

    public void setComparison(SecureApiComparison comparison) {
        this.comparison = comparison;
    }

    public String getReport() {
        return report;
    }

    public void setReport(String report) {
        this.report = report;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }
}
