package com.example.dhapp.dto;

import java.util.Map;

/**
 * {@code GET /api/secure-api/truststores} のレスポンス。
 *
 * <p>HTTPS 接続はせず、JVM 管理・JBoss EAP 管理の 2 つのトラストストアの中身と、
 * elytron の登録状態だけを返す。接続に失敗したときの切り分け用。</p>
 */
public class TrustStoresResponse {

    private String requestId;

    /** 処理時刻（ISO-8601）。 */
    private String timestamp;

    /** JVM 管理のトラストストア（{@code -Djavax.net.ssl.trustStore}）。 */
    private TrustStoreInfo jvm;

    /** JVM 側ストアの位置をどう決めたか。 */
    private String jvmResolution;

    /** JBoss EAP(Elytron) 管理のトラストストア。 */
    private TrustStoreInfo jbossEap;

    /** JBoss EAP 側ストアの位置をどう決めたか。 */
    private String jbossEapResolution;

    /** 参照した elytron の key-store 名。 */
    private String elytronKeyStoreName;

    /** その key-store の属性（path / relative-to / type など）。 */
    private Map<String, String> elytronKeyStoreAttributes;

    /** elytron サブシステムの登録状態。 */
    private ElytronSslInfo elytron;

    /** ログ・コンソールへ出力したものと同一のテキストレポート。 */
    private String report;

    public TrustStoresResponse() {
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

    public TrustStoreInfo getJvm() {
        return jvm;
    }

    public void setJvm(TrustStoreInfo jvm) {
        this.jvm = jvm;
    }

    public String getJvmResolution() {
        return jvmResolution;
    }

    public void setJvmResolution(String jvmResolution) {
        this.jvmResolution = jvmResolution;
    }

    public TrustStoreInfo getJbossEap() {
        return jbossEap;
    }

    public void setJbossEap(TrustStoreInfo jbossEap) {
        this.jbossEap = jbossEap;
    }

    public String getJbossEapResolution() {
        return jbossEapResolution;
    }

    public void setJbossEapResolution(String jbossEapResolution) {
        this.jbossEapResolution = jbossEapResolution;
    }

    public String getElytronKeyStoreName() {
        return elytronKeyStoreName;
    }

    public void setElytronKeyStoreName(String elytronKeyStoreName) {
        this.elytronKeyStoreName = elytronKeyStoreName;
    }

    public Map<String, String> getElytronKeyStoreAttributes() {
        return elytronKeyStoreAttributes;
    }

    public void setElytronKeyStoreAttributes(Map<String, String> elytronKeyStoreAttributes) {
        this.elytronKeyStoreAttributes = elytronKeyStoreAttributes;
    }

    public ElytronSslInfo getElytron() {
        return elytron;
    }

    public void setElytron(ElytronSslInfo elytron) {
        this.elytron = elytron;
    }

    public String getReport() {
        return report;
    }

    public void setReport(String report) {
        this.report = report;
    }
}
