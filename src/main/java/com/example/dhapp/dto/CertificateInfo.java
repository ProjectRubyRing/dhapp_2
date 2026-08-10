package com.example.dhapp.dto;

import java.util.List;

/**
 * X.509 証明書 1 枚分の情報。
 *
 * <p>TLS ハンドシェイクでサーバから受け取った証明書チェーン、および JVM のトラストストア／
 * {@code cacert.crt} から読み込んだ自己署名証明書を、レスポンスで表示するために使う。</p>
 *
 * <p>{@code inTrustStore} / {@code trustStoreAlias} は、この証明書と同一の証明書
 * （SHA-256 フィンガープリントが一致するもの）が JVM のトラストストアに登録されているかを示す。
 * 自己署名証明書での HTTPS 通信では、サーバ証明書そのものがトラストアンカーになるため、
 * ここが {@code true} であれば「トラストストアの cacert.crt で検証された」と判断できる。</p>
 */
public class CertificateInfo {

    /** チェーン内の位置（0 = サーバ証明書）。チェーン以外から読み込んだ場合は null。 */
    private Integer position;

    private String subjectDn;
    private String issuerDn;
    private String serialNumber;
    private String signatureAlgorithm;
    private String notBefore;
    private String notAfter;

    /** 現在時刻が有効期間外なら true。 */
    private boolean expired;

    /** Subject と Issuer が一致する（＝自己署名証明書）なら true。 */
    private boolean selfSigned;

    /** SHA-256 フィンガープリント（大文字 16 進・コロン区切り）。keytool -list -v の表示と同じ形式。 */
    private String sha256Fingerprint;

    /** 同一証明書が JVM のトラストストアに登録されているか。 */
    private boolean inTrustStore;

    /** トラストストア内で一致したエイリアス。未登録なら null。 */
    private String trustStoreAlias;

    /** subjectAltName（dNSName / iPAddress）。ホスト名検証の確認用。 */
    private List<String> subjectAlternativeNames;

    public CertificateInfo() {
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public String getSubjectDn() {
        return subjectDn;
    }

    public void setSubjectDn(String subjectDn) {
        this.subjectDn = subjectDn;
    }

    public String getIssuerDn() {
        return issuerDn;
    }

    public void setIssuerDn(String issuerDn) {
        this.issuerDn = issuerDn;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getSignatureAlgorithm() {
        return signatureAlgorithm;
    }

    public void setSignatureAlgorithm(String signatureAlgorithm) {
        this.signatureAlgorithm = signatureAlgorithm;
    }

    public String getNotBefore() {
        return notBefore;
    }

    public void setNotBefore(String notBefore) {
        this.notBefore = notBefore;
    }

    public String getNotAfter() {
        return notAfter;
    }

    public void setNotAfter(String notAfter) {
        this.notAfter = notAfter;
    }

    public boolean isExpired() {
        return expired;
    }

    public void setExpired(boolean expired) {
        this.expired = expired;
    }

    public boolean isSelfSigned() {
        return selfSigned;
    }

    public void setSelfSigned(boolean selfSigned) {
        this.selfSigned = selfSigned;
    }

    public String getSha256Fingerprint() {
        return sha256Fingerprint;
    }

    public void setSha256Fingerprint(String sha256Fingerprint) {
        this.sha256Fingerprint = sha256Fingerprint;
    }

    public boolean isInTrustStore() {
        return inTrustStore;
    }

    public void setInTrustStore(boolean inTrustStore) {
        this.inTrustStore = inTrustStore;
    }

    public String getTrustStoreAlias() {
        return trustStoreAlias;
    }

    public void setTrustStoreAlias(String trustStoreAlias) {
        this.trustStoreAlias = trustStoreAlias;
    }

    public List<String> getSubjectAlternativeNames() {
        return subjectAlternativeNames;
    }

    public void setSubjectAlternativeNames(List<String> subjectAlternativeNames) {
        this.subjectAlternativeNames = subjectAlternativeNames;
    }
}
