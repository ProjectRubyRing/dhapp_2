package com.example.dhapp.dto;

import java.util.List;

/**
 * {@code POST /api/tls/call} / {@code GET /api/tls/call} のレスポンスボディ。
 *
 * <p>接続失敗・ハンドシェイク失敗でも HTTP 500 にはせず、{@code status} と
 * {@code message} / {@code hint} で理由を返す（検証 API として結果を読み取りやすくするため。
 * 既存の {@code /api/external/execute} と同じ方針）。</p>
 *
 * <table>
 *   <caption>status の値</caption>
 *   <tr><td>{@code SUCCESS}</td><td>TLS ハンドシェイクと HTTP 応答の取得に成功</td></tr>
 *   <tr><td>{@code TLS_HANDSHAKE_FAILED}</td><td>証明書を検証できずハンドシェイクに失敗（トラストストア未登録が代表例）</td></tr>
 *   <tr><td>{@code CONNECT_FAILED}</td><td>TCP 接続不可・タイムアウト・名前解決失敗</td></tr>
 *   <tr><td>{@code INVALID_URL}</td><td>URL が不正、または https でない</td></tr>
 *   <tr><td>{@code ERROR}</td><td>上記以外の想定外エラー</td></tr>
 * </table>
 */
public class TlsCallResponse {

    private String status;
    private String requestId;

    private String url;
    private String host;
    private Integer port;
    private String method;

    /** HTTP ステータスコード。ハンドシェイク失敗時は null。 */
    private Integer httpStatus;

    private String responseContentType;
    private Long responseBodyLength;

    /** レスポンスボディの先頭のみ（既定 2048 文字）。 */
    private String responseBodyPreview;

    /** ネゴシエートされた TLS プロトコル（TLSv1.3 など）。 */
    private String tlsProtocol;

    /** ネゴシエートされた暗号スイート。 */
    private String cipherSuite;

    /** サーバ証明書の Subject（ピア識別名）。 */
    private String peerPrincipal;

    /** TLS ハンドシェイクに要した時間（ミリ秒）。 */
    private Long handshakeElapsedMs;

    /**
     * サーバ証明書チェーンのトラストアンカーが JVM トラストストア内に見つかったか。
     * 自己署名証明書（cacert.crt）による検証ができていれば true。
     */
    private boolean verifiedByTrustStore;

    /** トラストアンカーとして一致したトラストストアのエイリアス。 */
    private String trustAnchorAlias;

    /** サーバから提示された証明書チェーン。 */
    private List<CertificateInfo> serverCertificates;

    /** 通信に使った JVM のトラストストア情報。 */
    private TrustStoreInfo trustStore;

    /** 使用した SSLContext（{@code SSLContext.getDefault()}）のプロトコル。 */
    private String sslContextProtocol;

    /** 使用した SSLContext のプロバイダ名。Elytron の default-ssl-context が効いていると WildFly 系の名前になる。 */
    private String sslContextProvider;

    private long elapsedMs;

    private String message;
    private String hint;

    /** 失敗時の例外クラス名。 */
    private String exceptionClass;

    public TlsCallResponse() {
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

    public Long getHandshakeElapsedMs() {
        return handshakeElapsedMs;
    }

    public void setHandshakeElapsedMs(Long handshakeElapsedMs) {
        this.handshakeElapsedMs = handshakeElapsedMs;
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

    public List<CertificateInfo> getServerCertificates() {
        return serverCertificates;
    }

    public void setServerCertificates(List<CertificateInfo> serverCertificates) {
        this.serverCertificates = serverCertificates;
    }

    public TrustStoreInfo getTrustStore() {
        return trustStore;
    }

    public void setTrustStore(TrustStoreInfo trustStore) {
        this.trustStore = trustStore;
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
}
