package com.example.dhapp.dto;

/**
 * JVM 管理のトラストストアと JBoss EAP 管理のトラストストアで HTTPS 接続した結果の比較。
 */
public class SecureApiComparison {

    /** JVM 側の結果ステータス。 */
    private String jvmStatus;

    /** JBoss EAP(Elytron) 側の結果ステータス。 */
    private String jbossStatus;

    /** 対照実験（空のトラストストア）の結果ステータス。実行していない場合は null。 */
    private String noneStatus;

    private boolean jvmSucceeded;
    private boolean jbossSucceeded;

    /** 両方成功したか。 */
    private boolean bothSucceeded;

    /** 両方が同じ判定（両方成功／両方失敗）になったか。 */
    private boolean consistent;

    /** JVM 側で検証に使われたトラストストアのパス。 */
    private String jvmTrustStorePath;

    /** JBoss EAP 側で検証に使われたトラストストアのパス。 */
    private String jbossTrustStorePath;

    /** JVM 側でトラストアンカーになった証明書のエイリアス。 */
    private String jvmTrustAnchorAlias;

    /** JBoss EAP 側でトラストアンカーになった証明書のエイリアス。 */
    private String jbossTrustAnchorAlias;

    /** 両経路でサーバ証明書（リーフ）の SHA-256 が一致したか。 */
    private boolean sameServerCertificate;

    /** 結論（日本語）。 */
    private String summary;

    /** 失敗している場合の対処の手がかり。 */
    private String hint;

    public SecureApiComparison() {
    }

    public String getJvmStatus() {
        return jvmStatus;
    }

    public void setJvmStatus(String jvmStatus) {
        this.jvmStatus = jvmStatus;
    }

    public String getJbossStatus() {
        return jbossStatus;
    }

    public void setJbossStatus(String jbossStatus) {
        this.jbossStatus = jbossStatus;
    }

    public String getNoneStatus() {
        return noneStatus;
    }

    public void setNoneStatus(String noneStatus) {
        this.noneStatus = noneStatus;
    }

    public boolean isJvmSucceeded() {
        return jvmSucceeded;
    }

    public void setJvmSucceeded(boolean jvmSucceeded) {
        this.jvmSucceeded = jvmSucceeded;
    }

    public boolean isJbossSucceeded() {
        return jbossSucceeded;
    }

    public void setJbossSucceeded(boolean jbossSucceeded) {
        this.jbossSucceeded = jbossSucceeded;
    }

    public boolean isBothSucceeded() {
        return bothSucceeded;
    }

    public void setBothSucceeded(boolean bothSucceeded) {
        this.bothSucceeded = bothSucceeded;
    }

    public boolean isConsistent() {
        return consistent;
    }

    public void setConsistent(boolean consistent) {
        this.consistent = consistent;
    }

    public String getJvmTrustStorePath() {
        return jvmTrustStorePath;
    }

    public void setJvmTrustStorePath(String jvmTrustStorePath) {
        this.jvmTrustStorePath = jvmTrustStorePath;
    }

    public String getJbossTrustStorePath() {
        return jbossTrustStorePath;
    }

    public void setJbossTrustStorePath(String jbossTrustStorePath) {
        this.jbossTrustStorePath = jbossTrustStorePath;
    }

    public String getJvmTrustAnchorAlias() {
        return jvmTrustAnchorAlias;
    }

    public void setJvmTrustAnchorAlias(String jvmTrustAnchorAlias) {
        this.jvmTrustAnchorAlias = jvmTrustAnchorAlias;
    }

    public String getJbossTrustAnchorAlias() {
        return jbossTrustAnchorAlias;
    }

    public void setJbossTrustAnchorAlias(String jbossTrustAnchorAlias) {
        this.jbossTrustAnchorAlias = jbossTrustAnchorAlias;
    }

    public boolean isSameServerCertificate() {
        return sameServerCertificate;
    }

    public void setSameServerCertificate(boolean sameServerCertificate) {
        this.sameServerCertificate = sameServerCertificate;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }
}
