package com.example.dhapp.dto;

/**
 * {@code GET /api/config/date-config} のレスポンス。
 *
 * <p>ファイル読み・リソース読みの結果と、その比較、deployment-overlay の検知結果を
 * 1 つにまとめて返す。詳細は {@code CONFIG_READ_API.md} を参照。</p>
 */
public class DateConfigResponse {

    private String requestId;

    /** 処理時刻（ISO-8601）。 */
    private String timestamp;

    /**
     * 総合ステータス。{@code SUCCESS}（両方読めた） /
     * {@code PARTIAL}（片方だけ読めた） / {@code FAILED}（どちらも読めない）。
     */
    private String status;

    /** ★ファイル読み: /webapp/webapp9mf02/serverlets/jp/iwin/base/tango/date_config.properties */
    private ConfigSourceResult fileRead;

    /** ★リソース読み: クラスパス上の jp/iwin/base/tango/date_config.properties（war 同梱） */
    private ConfigSourceResult resourceRead;

    /** 上記 2 つの比較結果。 */
    private ConfigComparison comparison;

    /** deployment-overlay による差し替えの検知結果。 */
    private DeploymentOverlayInfo deploymentOverlay;

    /** ログ・コンソールへ出力したものと同一のテキストレポート。 */
    private String report;

    private long elapsedMs;

    public DateConfigResponse() {
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public ConfigSourceResult getFileRead() {
        return fileRead;
    }

    public void setFileRead(ConfigSourceResult fileRead) {
        this.fileRead = fileRead;
    }

    public ConfigSourceResult getResourceRead() {
        return resourceRead;
    }

    public void setResourceRead(ConfigSourceResult resourceRead) {
        this.resourceRead = resourceRead;
    }

    public ConfigComparison getComparison() {
        return comparison;
    }

    public void setComparison(ConfigComparison comparison) {
        this.comparison = comparison;
    }

    public DeploymentOverlayInfo getDeploymentOverlay() {
        return deploymentOverlay;
    }

    public void setDeploymentOverlay(DeploymentOverlayInfo deploymentOverlay) {
        this.deploymentOverlay = deploymentOverlay;
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
