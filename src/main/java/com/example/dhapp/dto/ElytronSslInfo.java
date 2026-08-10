package com.example.dhapp.dto;

import java.util.List;
import java.util.Map;

/**
 * JBoss EAP / WildFly の elytron サブシステムに登録された SSL 関連リソースの状態。
 *
 * <p>jboss-cli で実行した以下の登録が反映されているかを、アプリ内から
 * JMX（{@code jboss.as} ドメイン = 管理モデルの JMX ファサード）経由で読み取った結果。</p>
 *
 * <pre>
 * /subsystem=elytron/key-store=...          … トラストストアファイルの登録
 * /subsystem=elytron/trust-manager=...      … トラストマネージャーへの登録
 * /subsystem=elytron/client-ssl-context=... … クライアント SSL コンテキストへの登録
 * /subsystem=elytron:write-attribute(name=default-ssl-context, ...)
 *                                           … JVM 既定の SSL コンテキストへの登録
 * </pre>
 *
 * <p>jmx サブシステムが無効な場合など、管理モデルが読めない場合は
 * {@code available=false} となり {@code unavailableReason} に理由が入る。</p>
 */
public class ElytronSslInfo {

    /** 管理モデルを読み取れたか。 */
    private boolean available;

    /** 読み取り元（例: {@code JMX (jboss.as:subsystem=elytron)}）。 */
    private String source;

    /** 読み取れなかった場合の理由。 */
    private String unavailableReason;

    /** {@code /subsystem=elytron:read-attribute(name=default-ssl-context)} の値。 */
    private String defaultSslContext;

    /** 定義済みの key-store 名一覧。 */
    private List<String> keyStoreNames;

    /** 定義済みの trust-manager 名一覧。 */
    private List<String> trustManagerNames;

    /** 定義済みの client-ssl-context 名一覧。 */
    private List<String> clientSslContextNames;

    /** 期待する key-store の属性（存在しない場合は null）。 */
    private Map<String, String> keyStoreAttributes;

    /** 期待する trust-manager の属性（存在しない場合は null）。 */
    private Map<String, String> trustManagerAttributes;

    /** 期待する client-ssl-context の属性（存在しない場合は null）。 */
    private Map<String, String> clientSslContextAttributes;

    public ElytronSslInfo() {
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    public void setUnavailableReason(String unavailableReason) {
        this.unavailableReason = unavailableReason;
    }

    public String getDefaultSslContext() {
        return defaultSslContext;
    }

    public void setDefaultSslContext(String defaultSslContext) {
        this.defaultSslContext = defaultSslContext;
    }

    public List<String> getKeyStoreNames() {
        return keyStoreNames;
    }

    public void setKeyStoreNames(List<String> keyStoreNames) {
        this.keyStoreNames = keyStoreNames;
    }

    public List<String> getTrustManagerNames() {
        return trustManagerNames;
    }

    public void setTrustManagerNames(List<String> trustManagerNames) {
        this.trustManagerNames = trustManagerNames;
    }

    public List<String> getClientSslContextNames() {
        return clientSslContextNames;
    }

    public void setClientSslContextNames(List<String> clientSslContextNames) {
        this.clientSslContextNames = clientSslContextNames;
    }

    public Map<String, String> getKeyStoreAttributes() {
        return keyStoreAttributes;
    }

    public void setKeyStoreAttributes(Map<String, String> keyStoreAttributes) {
        this.keyStoreAttributes = keyStoreAttributes;
    }

    public Map<String, String> getTrustManagerAttributes() {
        return trustManagerAttributes;
    }

    public void setTrustManagerAttributes(Map<String, String> trustManagerAttributes) {
        this.trustManagerAttributes = trustManagerAttributes;
    }

    public Map<String, String> getClientSslContextAttributes() {
        return clientSslContextAttributes;
    }

    public void setClientSslContextAttributes(Map<String, String> clientSslContextAttributes) {
        this.clientSslContextAttributes = clientSslContextAttributes;
    }
}
