package com.example.dhapp.dto;

import java.util.List;
import java.util.Map;

/**
 * 1 つのトラストストア（JVM 管理 / JBoss EAP 管理）を使って HTTPS 接続した結果。
 *
 * <p>「つながったか」だけでなく、<b>どのストアの、どの証明書で検証できたのか</b>
 * （トラストアンカーのエイリアス・サーバ証明書チェーン）まで持たせる。
 * 2 つのストアで同じ接続先を叩いた結果を並べることで、どちらの取り込みが
 * 効いている／効いていないのかを切り分けられる。</p>
 */
public class TrustStoreCallResult {

    /** トラストストアの管理主体。{@code JVM} / {@code JBOSS_EAP} / {@code NONE}（対照実験）。 */
    private String trustSource;

    /** 表示用ラベル（日本語）。 */
    private String label;

    /** SSLContext をどう組み立てたかの説明。 */
    private String sslContextOrigin;

    /** どうやってトラストストアの位置を決めたか（システムプロパティ／elytron 管理モデル等）。 */
    private String trustStoreResolution;

    /** トラストストアの状態（パス・種別・エントリ数・エイリアス）。 */
    private TrustStoreInfo trustStore;

    /** JBoss EAP 側の場合に参照した elytron リソースの属性。 */
    private Map<String, String> elytronKeyStoreAttributes;

    /** JBoss EAP 側の場合に参照した elytron の key-store 名。 */
    private String elytronKeyStoreName;

    private String url;
    private String host;
    private Integer port;
    private String method;

    /**
     * 結果。{@code SUCCESS} / {@code TLS_HANDSHAKE_FAILED} / {@code CONNECT_FAILED} /
     * {@code TRUSTSTORE_UNAVAILABLE} / {@code INVALID_URL} / {@code ERROR}。
     */
    private String status;

    private String sslContextProtocol;
    private String sslContextProvider;

    /** ネゴシエートされた TLS プロトコル。 */
    private String tlsProtocol;

    /** ネゴシエートされた暗号スイート。 */
    private String cipherSuite;

    /** サーバ証明書の Subject。 */
    private String peerPrincipal;

    private long handshakeElapsedMs;

    /** サーバから提示された証明書チェーン。 */
    private List<CertificateInfo> serverCertificates;

    /** トラストストア内の証明書でチェーンを検証できたか。 */
    private boolean verifiedByTrustStore;

    /** 検証に使われたトラストストア内のエイリアス。 */
    private String trustAnchorAlias;

    private Integer httpStatus;
    private String responseContentType;
    private Long responseBodyLength;
    private String responseBodyPreview;

    /** 結果の説明（日本語）。 */
    private String message;

    /** 失敗時の対処の手がかり。 */
    private String hint;

    private String exceptionClass;

    /** 例外の原因チェーン（PKIX の失敗理由まで追えるように全段を残す）。 */
    private List<String> causeChain;

    private long elapsedMs;

    public TrustStoreCallResult() {
    }

    public String getTrustSource() {
        return trustSource;
    }

    public void setTrustSource(String trustSource) {
        this.trustSource = trustSource;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getSslContextOrigin() {
        return sslContextOrigin;
    }

    public void setSslContextOrigin(String sslContextOrigin) {
        this.sslContextOrigin = sslContextOrigin;
    }

    public String getTrustStoreResolution() {
        return trustStoreResolution;
    }

    public void setTrustStoreResolution(String trustStoreResolution) {
        this.trustStoreResolution = trustStoreResolution;
    }

    public TrustStoreInfo getTrustStore() {
        return trustStore;
    }

    public void setTrustStore(TrustStoreInfo trustStore) {
        this.trustStore = trustStore;
    }

    public Map<String, String> getElytronKeyStoreAttributes() {
        return elytronKeyStoreAttributes;
    }

    public void setElytronKeyStoreAttributes(Map<String, String> elytronKeyStoreAttributes) {
        this.elytronKeyStoreAttributes = elytronKeyStoreAttributes;
    }

    public String getElytronKeyStoreName() {
        return elytronKeyStoreName;
    }

    public void setElytronKeyStoreName(String elytronKeyStoreName) {
        this.elytronKeyStoreName = elytronKeyStoreName;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
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

    public String getSslContextProtocol() {
        return sslContextProtocol;
    }

    public void setSslContextProtocol(String sslContextProtocol) {
        this.sslContextProtocol = sslContextProtocol;
    }

    public String getSslContextProvider() {
        return sslContextProvider;
    }

    public void setSslContextProvider(String sslContextProvider) {
        this.sslContextProvider = sslContextProvider;
    }

    public String getTlsProtocol() {
        return tlsProtocol;
    }

    public void setTlsProtocol(String tlsProtocol) {
        this.tlsProtocol = tlsProtocol;
    }

    public String getCipherSuite() {
        return cipherSuite;
    }

    public void setCipherSuite(String cipherSuite) {
        this.cipherSuite = cipherSuite;
    }

    public String getPeerPrincipal() {
        return peerPrincipal;
    }

    public void setPeerPrincipal(String peerPrincipal) {
        this.peerPrincipal = peerPrincipal;
    }

    public long getHandshakeElapsedMs() {
        return handshakeElapsedMs;
    }

    public void setHandshakeElapsedMs(long handshakeElapsedMs) {
        this.handshakeElapsedMs = handshakeElapsedMs;
    }

    public List<CertificateInfo> getServerCertificates() {
        return serverCertificates;
    }

    public void setServerCertificates(List<CertificateInfo> serverCertificates) {
        this.serverCertificates = serverCertificates;
    }

    public boolean isVerifiedByTrustStore() {
        return verifiedByTrustStore;
    }

    public void setVerifiedByTrustStore(boolean verifiedByTrustStore) {
        this.verifiedByTrustStore = verifiedByTrustStore;
    }

    public String getTrustAnchorAlias() {
        return trustAnchorAlias;
    }

    public void setTrustAnchorAlias(String trustAnchorAlias) {
        this.trustAnchorAlias = trustAnchorAlias;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getResponseContentType() {
        return responseContentType;
    }

    public void setResponseContentType(String responseContentType) {
        this.responseContentType = responseContentType;
    }

    public Long getResponseBodyLength() {
        return responseBodyLength;
    }

    public void setResponseBodyLength(Long responseBodyLength) {
        this.responseBodyLength = responseBodyLength;
    }

    public String getResponseBodyPreview() {
        return responseBodyPreview;
    }

    public void setResponseBodyPreview(String responseBodyPreview) {
        this.responseBodyPreview = responseBodyPreview;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }

    public String getExceptionClass() {
        return exceptionClass;
    }

    public void setExceptionClass(String exceptionClass) {
        this.exceptionClass = exceptionClass;
    }

    public List<String> getCauseChain() {
        return causeChain;
    }

    public void setCauseChain(List<String> causeChain) {
        this.causeChain = causeChain;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }
}
