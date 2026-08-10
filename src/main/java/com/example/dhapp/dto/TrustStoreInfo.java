package com.example.dhapp.dto;

import java.util.List;

/**
 * JVM が使用しているトラストストアの状態。
 *
 * <p>JBoss EAP の standalone 起動パラメータで渡される
 * {@code -Djavax.net.ssl.trustStore} / {@code -Djavax.net.ssl.trustStorePassword} /
 * {@code -Djavax.net.ssl.trustStoreType} を読み取り、その実体（ファイル）を
 * ロードした結果を表す。</p>
 *
 * <p><b>パスワードの値そのものは絶対にレスポンスへ含めない。</b>
 * 起動パラメータで渡されているかどうか（{@code passwordPropertyProvided}）だけを返す。</p>
 */
public class TrustStoreInfo {

    /** どこからトラストストアを決めたか（起動パラメータ／JVM 既定の cacerts）。 */
    private String source;

    /** トラストストアファイルの絶対パス。 */
    private String path;

    /** ストア種別（JKS / PKCS12 など）。ロードできた場合は実際に検出された種別。 */
    private String type;

    /** {@code -Djavax.net.ssl.trustStore} が指定されているか。 */
    private boolean pathPropertyProvided;

    /** {@code -Djavax.net.ssl.trustStorePassword} が指定されているか（値は返さない）。 */
    private boolean passwordPropertyProvided;

    private boolean exists;
    private boolean readable;

    /** KeyStore としてロードできたか。 */
    private boolean loaded;

    /** ロードに失敗した場合の理由。 */
    private String loadErrorMessage;

    /** 全エントリ数。 */
    private int entryCount;

    /** うち証明書エントリ（trustedCertEntry）の数。 */
    private int certificateEntryCount;

    /** エイリアス一覧（多すぎる場合は先頭のみ）。 */
    private List<String> aliases;

    /** aliases が全件かどうか（false なら truncated）。 */
    private boolean aliasesComplete;

    public TrustStoreInfo() {
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isPathPropertyProvided() {
        return pathPropertyProvided;
    }

    public void setPathPropertyProvided(boolean pathPropertyProvided) {
        this.pathPropertyProvided = pathPropertyProvided;
    }

    public boolean isPasswordPropertyProvided() {
        return passwordPropertyProvided;
    }

    public void setPasswordPropertyProvided(boolean passwordPropertyProvided) {
        this.passwordPropertyProvided = passwordPropertyProvided;
    }

    public boolean isExists() {
        return exists;
    }

    public void setExists(boolean exists) {
        this.exists = exists;
    }

    public boolean isReadable() {
        return readable;
    }

    public void setReadable(boolean readable) {
        this.readable = readable;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    public String getLoadErrorMessage() {
        return loadErrorMessage;
    }

    public void setLoadErrorMessage(String loadErrorMessage) {
        this.loadErrorMessage = loadErrorMessage;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public void setEntryCount(int entryCount) {
        this.entryCount = entryCount;
    }

    public int getCertificateEntryCount() {
        return certificateEntryCount;
    }

    public void setCertificateEntryCount(int certificateEntryCount) {
        this.certificateEntryCount = certificateEntryCount;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
        this.aliases = aliases;
    }

    public boolean isAliasesComplete() {
        return aliasesComplete;
    }

    public void setAliasesComplete(boolean aliasesComplete) {
        this.aliasesComplete = aliasesComplete;
    }
}
