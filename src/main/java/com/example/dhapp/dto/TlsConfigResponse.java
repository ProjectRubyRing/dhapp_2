package com.example.dhapp.dto;

import java.util.List;

/**
 * {@code GET /api/tls/config}（トラストストア／Elytron の設定確認）のレスポンスボディ。
 *
 * <p>{@code status} は個々のチェック結果の集約。</p>
 *
 * <table>
 *   <caption>status の値</caption>
 *   <tr><td>{@code OK}</td><td>全チェックが OK</td></tr>
 *   <tr><td>{@code NG}</td><td>1 つ以上のチェックが NG（{@code checks[].hint} に対処方法）</td></tr>
 *   <tr><td>{@code WARN}</td><td>NG は無いが判定できないチェック（UNKNOWN）がある</td></tr>
 * </table>
 */
public class TlsConfigResponse {

    private String status;
    private String requestId;
    private String checkedAt;

    private int okCount;
    private int ngCount;
    private int unknownCount;

    /** JVM のトラストストア（standalone 起動パラメータで渡されたもの）。 */
    private TrustStoreInfo trustStore;

    /** 照合に使った自己署名証明書ファイル（app.tls.ca-cert-path）。 */
    private String caCertPath;

    /** cacert.crt を読み込めた場合の証明書情報。トラストストア内の一致状況もここに入る。 */
    private CertificateInfo caCertificate;

    /** cacert.crt を読み込めなかった場合の理由。 */
    private String caCertLoadErrorMessage;

    /** elytron サブシステムの登録状態。 */
    private ElytronSslInfo elytron;

    /** {@code SSLContext.getDefault()} のプロトコル。 */
    private String defaultSslContextProtocol;

    /** {@code SSLContext.getDefault()} のプロバイダ名。 */
    private String defaultSslContextProvider;

    /** 期待する elytron リソース名（設定値）。 */
    private String expectedKeyStoreName;
    private String expectedTrustManagerName;
    private String expectedClientSslContextName;

    private List<TlsCheckResult> checks;

    private long elapsedMs;
    private String message;

    public TlsConfigResponse() {
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(String checkedAt) {
        this.checkedAt = checkedAt;
    }

    public int getOkCount() {
        return okCount;
    }

    public void setOkCount(int okCount) {
        this.okCount = okCount;
    }

    public int getNgCount() {
        return ngCount;
    }

    public void setNgCount(int ngCount) {
        this.ngCount = ngCount;
    }

    public int getUnknownCount() {
        return unknownCount;
    }

    public void setUnknownCount(int unknownCount) {
        this.unknownCount = unknownCount;
    }

    public TrustStoreInfo getTrustStore() {
        return trustStore;
    }

    public void setTrustStore(TrustStoreInfo trustStore) {
        this.trustStore = trustStore;
    }

    public String getCaCertPath() {
        return caCertPath;
    }

    public void setCaCertPath(String caCertPath) {
        this.caCertPath = caCertPath;
    }

    public CertificateInfo getCaCertificate() {
        return caCertificate;
    }

    public void setCaCertificate(CertificateInfo caCertificate) {
        this.caCertificate = caCertificate;
    }

    public String getCaCertLoadErrorMessage() {
        return caCertLoadErrorMessage;
    }

    public void setCaCertLoadErrorMessage(String caCertLoadErrorMessage) {
        this.caCertLoadErrorMessage = caCertLoadErrorMessage;
    }

    public ElytronSslInfo getElytron() {
        return elytron;
    }

    public void setElytron(ElytronSslInfo elytron) {
        this.elytron = elytron;
    }

    public String getDefaultSslContextProtocol() {
        return defaultSslContextProtocol;
    }

    public void setDefaultSslContextProtocol(String defaultSslContextProtocol) {
        this.defaultSslContextProtocol = defaultSslContextProtocol;
    }

    public String getDefaultSslContextProvider() {
        return defaultSslContextProvider;
    }

    public void setDefaultSslContextProvider(String defaultSslContextProvider) {
        this.defaultSslContextProvider = defaultSslContextProvider;
    }

    public String getExpectedKeyStoreName() {
        return expectedKeyStoreName;
    }

    public void setExpectedKeyStoreName(String expectedKeyStoreName) {
        this.expectedKeyStoreName = expectedKeyStoreName;
    }

    public String getExpectedTrustManagerName() {
        return expectedTrustManagerName;
    }

    public void setExpectedTrustManagerName(String expectedTrustManagerName) {
        this.expectedTrustManagerName = expectedTrustManagerName;
    }

    public String getExpectedClientSslContextName() {
        return expectedClientSslContextName;
    }

    public void setExpectedClientSslContextName(String expectedClientSslContextName) {
        this.expectedClientSslContextName = expectedClientSslContextName;
    }

    public List<TlsCheckResult> getChecks() {
        return checks;
    }

    public void setChecks(List<TlsCheckResult> checks) {
        this.checks = checks;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
