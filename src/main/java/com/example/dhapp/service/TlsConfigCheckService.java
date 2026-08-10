package com.example.dhapp.service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.example.dhapp.dto.CertificateInfo;
import com.example.dhapp.dto.ElytronSslInfo;
import com.example.dhapp.dto.TlsCheckResult;
import com.example.dhapp.dto.TlsConfigResponse;
import com.example.dhapp.dto.TrustStoreInfo;
import com.example.dhapp.service.TlsHttpsClient.Handshake;
import com.example.dhapp.service.TrustStoreInspector.LoadedTrustStore;

/**
 * 自己署名証明書（cacert.crt）が「JVM のトラストストア」と「JBoss EAP の elytron サブシステム」の
 * 両方に正しく登録されているかを確認する。
 *
 * <p>確認するのは次の 4 系統。jboss-cli で行った登録が実際にサーバへ反映されているかを、
 * アプリ（＝実際に HTTPS 通信を行う JVM）の内側から見る点に意味がある。</p>
 *
 * <ol>
 *   <li><b>JVM トラストストア</b> … standalone 起動パラメータ
 *       {@code -Djavax.net.ssl.trustStore} / {@code -Djavax.net.ssl.trustStorePassword} と、
 *       そのストアに cacert.crt が入っているか</li>
 *   <li><b>トラストマネージャー</b> … {@code /subsystem=elytron/trust-manager=...}</li>
 *   <li><b>クライアント SSL コンテキスト</b> … {@code /subsystem=elytron/client-ssl-context=...}</li>
 *   <li><b>JVM 既定の SSL コンテキスト</b> …
 *       {@code /subsystem=elytron:write-attribute(name=default-ssl-context, ...)} と、
 *       実行中の {@link SSLContext#getDefault()} の状態</li>
 * </ol>
 */
@Service
public class TlsConfigCheckService {

    private static final Logger log = LoggerFactory.getLogger(TlsConfigCheckService.class);

    private static final String CATEGORY_TRUST_STORE = "jvm-truststore";
    private static final String CATEGORY_ELYTRON = "elytron";
    private static final String CATEGORY_SSL_CONTEXT = "jvm-ssl-context";
    private static final String CATEGORY_HANDSHAKE = "tls-handshake";

    /** Elytron 由来の SSLContext かどうかを、プロバイダ名から推定するパターン。 */
    private static final Pattern ELYTRON_PROVIDER = Pattern.compile("(?i)(wildfly|elytron|openssl)");

    private static final String HINT_MANAGEMENT_MODEL_UNAVAILABLE =
            "管理モデルを JMX 経由で読めなかったため、jboss-cli で直接確認する: "
                    + "$JBOSS_HOME/bin/jboss-cli.sh --connect "
                    + "--command=\"/subsystem=elytron:read-resource(recursive=true)\"。"
                    + "アプリから読めるようにするには standalone.xml の jmx サブシステム"
                    + "（expose-resolved-model）を有効にする。";

    private final TrustStoreInspector trustStoreInspector;
    private final ElytronSslInspector elytronSslInspector;
    private final TlsHttpsClient tlsHttpsClient;

    /** cacert.crt ファイルが読めないときに、代わりに探すトラストストアのエイリアス。 */
    private final String expectedAlias;

    public TlsConfigCheckService(TrustStoreInspector trustStoreInspector,
            ElytronSslInspector elytronSslInspector,
            TlsHttpsClient tlsHttpsClient,
            @Value("${app.tls.expected-alias:cacert}") String expectedAlias) {
        this.trustStoreInspector = trustStoreInspector;
        this.elytronSslInspector = elytronSslInspector;
        this.tlsHttpsClient = tlsHttpsClient;
        this.expectedAlias = expectedAlias;
    }

    /**
     * 設定確認を実行する。
     *
     * @param probe true なら {@code app.tls.target-url} へ実際に TLS ハンドシェイクして、
     *              JVM 既定の SSLContext が本当に自己署名証明書を信頼するかまで確認する
     */
    public TlsConfigResponse check(boolean probe, String requestId) {
        long startedAt = System.currentTimeMillis();

        TlsConfigResponse response = new TlsConfigResponse();
        response.setRequestId(requestId);
        response.setCheckedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        response.setExpectedKeyStoreName(elytronSslInspector.getExpectedKeyStoreName());
        response.setExpectedTrustManagerName(elytronSslInspector.getExpectedTrustManagerName());
        response.setExpectedClientSslContextName(elytronSslInspector.getExpectedClientSslContextName());
        response.setCaCertPath(trustStoreInspector.getCaCertPath());

        LoadedTrustStore trustStore = trustStoreInspector.load();
        response.setTrustStore(trustStore.info());

        StringBuilder caCertError = new StringBuilder();
        X509Certificate caCertificate = trustStoreInspector.loadCaCertificate(caCertError);
        if (caCertificate != null) {
            response.setCaCertificate(trustStoreInspector.describe(caCertificate, null, trustStore.keyStore()));
        } else if (caCertError.length() > 0) {
            response.setCaCertLoadErrorMessage(caCertError.toString());
        }

        ElytronSslInfo elytron = elytronSslInspector.inspect();
        response.setElytron(elytron);

        List<TlsCheckResult> checks = new ArrayList<>();
        checks.add(checkTrustStoreProperty(trustStore.info()));
        checks.add(checkTrustStorePasswordProperty(trustStore.info()));
        checks.add(checkTrustStoreFile(trustStore.info()));

        TlsCheckResult containsCert = checkTrustStoreContainsCaCert(
                trustStore, caCertificate, response.getCaCertificate(), caCertError.toString());
        checks.add(containsCert);
        checks.add(checkAcceptedIssuer(trustStore, caCertificate));

        checks.add(checkElytronKeyStore(elytron, trustStore.info()));
        checks.add(checkElytronTrustManager(elytron));
        checks.add(checkElytronClientSslContext(elytron));
        checks.add(checkElytronDefaultSslContext(elytron));
        checks.add(checkRuntimeDefaultSslContext(response, elytron, containsCert));

        if (probe) {
            checks.add(handshakeProbe(trustStore));
        }

        response.setChecks(checks);
        summarize(response, checks, probe);
        response.setElapsedMs(System.currentTimeMillis() - startedAt);

        log.info("TLS config check done. requestId={}, status={}, ok={}, ng={}, unknown={}, probe={}, elapsedMs={}",
                requestId, response.getStatus(), response.getOkCount(), response.getNgCount(),
                response.getUnknownCount(), probe, response.getElapsedMs());
        return response;
    }

    // ------------------------------------------------------------------
    // 1. JVM トラストストア（standalone 起動パラメータ）
    // ------------------------------------------------------------------

    private TlsCheckResult checkTrustStoreProperty(TrustStoreInfo info) {
        return TlsCheckResult.of(info.isPathPropertyProvided(),
                "jvm.truststore.property", CATEGORY_TRUST_STORE,
                "-D" + TrustStoreInspector.PROP_TRUST_STORE + " が standalone 起動パラメータで指定されている",
                info.isPathPropertyProvided()
                        ? TrustStoreInspector.PROP_TRUST_STORE + "=" + info.getPath()
                        : "未指定（JVM 既定の cacerts を使用: " + info.getPath() + "）",
                "source=" + info.getSource(),
                "standalone.conf の JAVA_OPTS もしくは起動コマンドに "
                        + "-Djavax.net.ssl.trustStore=/path/to/truststore.jks を追加する。");
    }

    private TlsCheckResult checkTrustStorePasswordProperty(TrustStoreInfo info) {
        return TlsCheckResult.of(info.isPasswordPropertyProvided(),
                "jvm.truststore.password-property", CATEGORY_TRUST_STORE,
                "-D" + TrustStoreInspector.PROP_TRUST_STORE_PASSWORD + " が指定されている",
                info.isPasswordPropertyProvided() ? "指定あり（値は非表示）" : "未指定",
                "パスワードの値はレスポンスにもログにも出力しない。指定有無のみを確認する。",
                "standalone.conf の JAVA_OPTS に "
                        + "-Djavax.net.ssl.trustStorePassword=<password> を追加する。"
                        + "読み取り専用のトラストストアでは省略しても検証自体は動作するが、"
                        + "整合性チェック（改ざん検知）が行われなくなる。");
    }

    private TlsCheckResult checkTrustStoreFile(TrustStoreInfo info) {
        String actual = "exists=" + info.isExists() + ", readable=" + info.isReadable()
                + ", loaded=" + info.isLoaded() + ", type=" + info.getType()
                + ", entries=" + info.getEntryCount()
                + ", certificateEntries=" + info.getCertificateEntryCount();
        return TlsCheckResult.of(info.isLoaded(),
                "jvm.truststore.file", CATEGORY_TRUST_STORE,
                "トラストストアファイルが存在し、KeyStore としてロードできる",
                actual,
                info.getLoadErrorMessage(),
                "パス・パーミッション・ストア種別（JKS / PKCS12）を確認する: "
                        + "keytool -list -keystore " + info.getPath());
    }

    private TlsCheckResult checkTrustStoreContainsCaCert(LoadedTrustStore trustStore,
            X509Certificate caCertificate, CertificateInfo caCertificateInfo, String caCertError) {

        TrustStoreInfo info = trustStore.info();
        String importHint = "keytool -importcert -trustcacerts -noprompt -alias " + expectedAlias
                + " -file /path/to/cacert.crt -keystore " + info.getPath() + " -storepass <password>";

        if (!info.isLoaded()) {
            return TlsCheckResult.unknown("jvm.truststore.contains-cacert", CATEGORY_TRUST_STORE,
                    "cacert.crt がトラストストアに登録されている",
                    "トラストストアをロードできなかったため判定不可",
                    info.getLoadErrorMessage(),
                    "先に jvm.truststore.file の NG を解消する。");
        }

        if (caCertificate != null) {
            boolean found = caCertificateInfo != null && caCertificateInfo.isInTrustStore();
            return TlsCheckResult.of(found,
                    "jvm.truststore.contains-cacert", CATEGORY_TRUST_STORE,
                    "cacert.crt（SHA-256 フィンガープリント一致）がトラストストアに登録されている",
                    found ? "登録あり（alias=" + caCertificateInfo.getTrustStoreAlias() + "）" : "登録なし",
                    "照合方法: SHA-256 フィンガープリント。cacert.crt="
                            + (caCertificateInfo == null ? "-" : caCertificateInfo.getSha256Fingerprint()),
                    importHint);
        }

        // cacert.crt ファイルが読めない場合は、エイリアス名で存在確認する（確証は下がる）。
        if (trustStoreInspector.containsAlias(trustStore.keyStore(), expectedAlias)) {
            return TlsCheckResult.ok("jvm.truststore.contains-cacert", CATEGORY_TRUST_STORE,
                    "cacert.crt がトラストストアに登録されている",
                    "エイリアス \"" + expectedAlias + "\" が存在する",
                    "cacert.crt ファイルを読めなかったため、フィンガープリント照合ではなくエイリアス名で判定した。"
                            + "厳密に照合するには app.tls.ca-cert-path（TLS_CA_CERT_PATH）に cacert.crt を指定する。"
                            + (StringUtils.hasText(caCertError) ? " 理由: " + caCertError : ""));
        }
        return TlsCheckResult.unknown("jvm.truststore.contains-cacert", CATEGORY_TRUST_STORE,
                "cacert.crt がトラストストアに登録されている",
                "エイリアス \"" + expectedAlias + "\" は存在せず、cacert.crt ファイルも読めないため判定不可",
                caCertError,
                "app.tls.ca-cert-path（環境変数 TLS_CA_CERT_PATH）に cacert.crt のパスを設定するか、"
                        + "app.tls.expected-alias（TLS_EXPECTED_ALIAS）に実際のエイリアス名を設定する。"
                        + "未登録であれば: " + importHint);
    }

    private TlsCheckResult checkAcceptedIssuer(LoadedTrustStore trustStore, X509Certificate caCertificate) {
        if (!trustStore.info().isLoaded() || caCertificate == null) {
            return TlsCheckResult.unknown("jvm.default-trust-manager", CATEGORY_TRUST_STORE,
                    "既定の TrustManagerFactory が cacert.crt を信頼済みイシュアとして採用する",
                    "トラストストアまたは cacert.crt を読めないため判定不可",
                    null,
                    "先に jvm.truststore.contains-cacert を解消する。");
        }
        boolean accepted = trustStoreInspector.isAcceptedIssuer(trustStore.keyStore(), caCertificate);
        return TlsCheckResult.of(accepted,
                "jvm.default-trust-manager", CATEGORY_TRUST_STORE,
                "既定の TrustManagerFactory（PKIX）が cacert.crt を信頼済みイシュアとして採用する",
                accepted ? "採用される" : "採用されない",
                "トラストストアにエントリが在ることと、JSSE がトラストアンカーとして採用することは別なので"
                        + "両方を確認している。",
                "エントリが秘密鍵エントリになっていないか（trustedCertEntry であること）、"
                        + "証明書が破損・期限切れでないかを keytool -list -v で確認する。");
    }

    // ------------------------------------------------------------------
    // 2〜4. elytron サブシステム（jboss-cli で登録した内容）
    // ------------------------------------------------------------------

    private TlsCheckResult checkElytronKeyStore(ElytronSslInfo elytron, TrustStoreInfo trustStoreInfo) {
        String expected = elytronSslInspector.getExpectedKeyStoreName();
        String cliHint = "/subsystem=elytron/key-store=" + expected + ":add(path=\""
                + trustStoreInfo.getPath() + "\", type=" + trustStoreInfo.getType()
                + ", credential-reference={clear-text=\"<password>\"})";

        if (!elytron.isAvailable()) {
            return TlsCheckResult.unknown("elytron.key-store", CATEGORY_ELYTRON,
                    "key-store=" + expected + " が登録されている",
                    "管理モデルを読めないため判定不可",
                    elytron.getUnavailableReason(),
                    HINT_MANAGEMENT_MODEL_UNAVAILABLE);
        }
        Map<String, String> attributes = elytron.getKeyStoreAttributes();
        if (attributes == null) {
            return TlsCheckResult.ng("elytron.key-store", CATEGORY_ELYTRON,
                    "key-store=" + expected + " が登録されている",
                    "未登録（登録済み: " + elytron.getKeyStoreNames() + "）",
                    null,
                    cliHint);
        }
        String path = ElytronSslInspector.lookup(attributes, ElytronSslInspector.ATTR_PATH);
        boolean samePath = path != null && trustStoreInfo.getPath() != null
                && normalizePath(path).equals(normalizePath(trustStoreInfo.getPath()));
        return TlsCheckResult.ok("elytron.key-store", CATEGORY_ELYTRON,
                "key-store=" + expected + " が登録されている",
                "登録あり（path=" + path + ", type="
                        + ElytronSslInspector.lookup(attributes, "type") + "）",
                samePath
                        ? "javax.net.ssl.trustStore と同一のファイルを参照している。"
                        : "javax.net.ssl.trustStore（" + trustStoreInfo.getPath()
                          + "）とは別のファイルを参照している。"
                          + "elytron の default-ssl-context が有効な場合、実際の検証にはこちらが使われる。");
    }

    private TlsCheckResult checkElytronTrustManager(ElytronSslInfo elytron) {
        String expected = elytronSslInspector.getExpectedTrustManagerName();
        String expectedKeyStore = elytronSslInspector.getExpectedKeyStoreName();
        String cliHint = "/subsystem=elytron/trust-manager=" + expected
                + ":add(key-store=" + expectedKeyStore + ", algorithm=PKIX)";

        if (!elytron.isAvailable()) {
            return TlsCheckResult.unknown("elytron.trust-manager", CATEGORY_ELYTRON,
                    "trust-manager=" + expected + " が登録され key-store=" + expectedKeyStore + " を参照している",
                    "管理モデルを読めないため判定不可",
                    elytron.getUnavailableReason(),
                    HINT_MANAGEMENT_MODEL_UNAVAILABLE);
        }
        Map<String, String> attributes = elytron.getTrustManagerAttributes();
        if (attributes == null) {
            return TlsCheckResult.ng("elytron.trust-manager", CATEGORY_ELYTRON,
                    "trust-manager=" + expected + " が登録されている",
                    "未登録（登録済み: " + elytron.getTrustManagerNames() + "）",
                    null,
                    cliHint);
        }
        String keyStore = ElytronSslInspector.lookup(attributes, ElytronSslInspector.ATTR_KEY_STORE);
        boolean linked = expectedKeyStore.equals(keyStore);
        return TlsCheckResult.of(linked,
                "elytron.trust-manager", CATEGORY_ELYTRON,
                "trust-manager=" + expected + " が key-store=" + expectedKeyStore + " を参照している",
                "登録あり（key-store=" + keyStore + ", algorithm="
                        + ElytronSslInspector.lookup(attributes, "algorithm") + "）",
                null,
                "/subsystem=elytron/trust-manager=" + expected
                        + ":write-attribute(name=key-store, value=" + expectedKeyStore + ")");
    }

    private TlsCheckResult checkElytronClientSslContext(ElytronSslInfo elytron) {
        String expected = elytronSslInspector.getExpectedClientSslContextName();
        String expectedTrustManager = elytronSslInspector.getExpectedTrustManagerName();
        String cliHint = "/subsystem=elytron/client-ssl-context=" + expected
                + ":add(trust-manager=" + expectedTrustManager + ", protocols=[\"TLSv1.3\",\"TLSv1.2\"])";

        if (!elytron.isAvailable()) {
            return TlsCheckResult.unknown("elytron.client-ssl-context", CATEGORY_ELYTRON,
                    "client-ssl-context=" + expected + " が trust-manager=" + expectedTrustManager
                            + " を参照している",
                    "管理モデルを読めないため判定不可",
                    elytron.getUnavailableReason(),
                    HINT_MANAGEMENT_MODEL_UNAVAILABLE);
        }
        Map<String, String> attributes = elytron.getClientSslContextAttributes();
        if (attributes == null) {
            return TlsCheckResult.ng("elytron.client-ssl-context", CATEGORY_ELYTRON,
                    "client-ssl-context=" + expected + " が登録されている",
                    "未登録（登録済み: " + elytron.getClientSslContextNames() + "）",
                    null,
                    cliHint);
        }
        String trustManager = ElytronSslInspector.lookup(attributes, ElytronSslInspector.ATTR_TRUST_MANAGER);
        boolean linked = expectedTrustManager.equals(trustManager);
        return TlsCheckResult.of(linked,
                "elytron.client-ssl-context", CATEGORY_ELYTRON,
                "client-ssl-context=" + expected + " が trust-manager=" + expectedTrustManager
                        + " を参照している",
                "登録あり（trust-manager=" + trustManager + ", protocols="
                        + ElytronSslInspector.lookup(attributes, "protocols") + "）",
                null,
                "/subsystem=elytron/client-ssl-context=" + expected
                        + ":write-attribute(name=trust-manager, value=" + expectedTrustManager + ")");
    }

    private TlsCheckResult checkElytronDefaultSslContext(ElytronSslInfo elytron) {
        String expected = elytronSslInspector.getExpectedClientSslContextName();
        String cliHint = "/subsystem=elytron:write-attribute(name=default-ssl-context, value="
                + expected + ") を実行し :reload する（反映には再起動／reload が必要）。";

        if (!elytron.isAvailable()) {
            return TlsCheckResult.unknown("elytron.default-ssl-context", CATEGORY_ELYTRON,
                    "/subsystem=elytron の default-ssl-context が " + expected + " である",
                    "管理モデルを読めないため判定不可",
                    elytron.getUnavailableReason(),
                    HINT_MANAGEMENT_MODEL_UNAVAILABLE);
        }
        String actual = elytron.getDefaultSslContext();
        return TlsCheckResult.of(expected.equals(actual),
                "elytron.default-ssl-context", CATEGORY_ELYTRON,
                "/subsystem=elytron の default-ssl-context が " + expected + " である",
                actual == null ? "未設定" : actual,
                "この属性が設定されていると、Elytron が起動時に SSLContext.setDefault() を実行し、"
                        + "JVM 既定の SSLContext が client-ssl-context に置き換わる。",
                cliHint);
    }

    // ------------------------------------------------------------------
    // 5. 実行中の JVM 既定 SSLContext
    // ------------------------------------------------------------------

    private TlsCheckResult checkRuntimeDefaultSslContext(TlsConfigResponse response,
            ElytronSslInfo elytron, TlsCheckResult trustStoreContainsCert) {

        SSLContext context;
        try {
            context = SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            return TlsCheckResult.ng("jvm.default-ssl-context", CATEGORY_SSL_CONTEXT,
                    "SSLContext.getDefault() を取得できる",
                    "取得できない: " + e.getMessage(), null,
                    "JVM のセキュリティプロバイダ設定（java.security）を確認する。");
        }
        String provider = context.getProvider().getName();
        response.setDefaultSslContextProtocol(context.getProtocol());
        response.setDefaultSslContextProvider(provider);

        String actual = "protocol=" + context.getProtocol() + ", provider=" + provider;
        String expectedClientSslContext = elytronSslInspector.getExpectedClientSslContextName();
        boolean elytronConfigured = elytron.isAvailable()
                && expectedClientSslContext.equals(elytron.getDefaultSslContext());
        boolean elytronBacked = ELYTRON_PROVIDER.matcher(provider).find();

        if (elytronConfigured) {
            if (elytronBacked) {
                return TlsCheckResult.ok("jvm.default-ssl-context", CATEGORY_SSL_CONTEXT,
                        "JVM 既定の SSLContext が elytron の client-ssl-context に置き換わっている",
                        actual,
                        "default-ssl-context=" + expectedClientSslContext + " が実行中の JVM に反映されている。");
            }
            return TlsCheckResult.unknown("jvm.default-ssl-context", CATEGORY_SSL_CONTEXT,
                    "JVM 既定の SSLContext が elytron の client-ssl-context に置き換わっている",
                    actual,
                    "管理モデルでは default-ssl-context=" + expectedClientSslContext
                            + " だが、実行中の SSLContext のプロバイダ名からは Elytron 由来と断定できない"
                            + "（プロバイダ名は EAP のバージョンで異なる）。",
                    "設定直後であれば :reload / 再起動で反映する。反映済みかどうかは "
                            + "GET /api/tls/config?probe=true や POST /api/tls/call の"
                            + "実通信で最終確認する。");
        }

        boolean trustStoreOk = TlsCheckResult.STATUS_OK.equals(trustStoreContainsCert.getStatus());
        return TlsCheckResult.of(trustStoreOk,
                "jvm.default-ssl-context", CATEGORY_SSL_CONTEXT,
                "JVM 既定の SSLContext が cacert.crt を含むトラストストアで初期化されている",
                actual + ", elytron.default-ssl-context="
                        + (elytron.isAvailable() ? String.valueOf(elytron.getDefaultSslContext()) : "不明"),
                "elytron の default-ssl-context が未設定のため、JVM 既定の SSLContext は "
                        + "javax.net.ssl.trustStore から構築される（＝ jvm.truststore.* の結果がそのまま効く）。",
                "cacert.crt をトラストストアへ登録するか、elytron の default-ssl-context を "
                        + expectedClientSslContext + " に設定する。");
    }

    // ------------------------------------------------------------------
    // 6. 実通信での最終確認（probe=true のときのみ）
    // ------------------------------------------------------------------

    private TlsCheckResult handshakeProbe(LoadedTrustStore trustStore) {
        String url = tlsHttpsClient.getDefaultUrl();
        String expected = "app.tls.target-url（" + url + "）に対し、JVM 既定の SSLContext で"
                + "TLS ハンドシェイクが成功する";
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return TlsCheckResult.unknown("tls.handshake-probe", CATEGORY_HANDSHAKE, expected,
                    "URL を解析できない: " + url, e.getMessage(),
                    "app.tls.target-url（環境変数 TLS_TARGET_URL）に https の URL を設定する。");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
            return TlsCheckResult.unknown("tls.handshake-probe", CATEGORY_HANDSHAKE, expected,
                    "https の URL ではない: " + url, null,
                    "app.tls.target-url（環境変数 TLS_TARGET_URL）に https の URL を設定する。");
        }
        int port = uri.getPort() < 0 ? 443 : uri.getPort();

        try {
            Handshake handshake = tlsHttpsClient.handshake(uri.getHost(), port);
            String anchorAlias =
                    trustStoreInspector.findTrustAnchorAlias(trustStore.keyStore(), handshake.chain());
            return TlsCheckResult.ok("tls.handshake-probe", CATEGORY_HANDSHAKE, expected,
                    "成功（protocol=" + handshake.protocol() + ", cipherSuite=" + handshake.cipherSuite()
                            + ", peer=" + handshake.peerPrincipal() + "）",
                    anchorAlias != null
                            ? "トラストアンカーはトラストストアの alias=" + anchorAlias + " と一致した。"
                            : "ハンドシェイクは成功したが、トラストアンカーは "
                              + "javax.net.ssl.trustStore の中には見つからなかった"
                              + "（elytron の別トラストマネージャや OS 既定の CA で検証された可能性）。");
        } catch (IOException | NoSuchAlgorithmException e) {
            return TlsCheckResult.ng("tls.handshake-probe", CATEGORY_HANDSHAKE, expected,
                    "失敗: " + e.getClass().getSimpleName() + ": " + e.getMessage(), null,
                    "POST /api/tls/call を実行して詳細（証明書チェーン・失敗理由）を確認する。"
                            + "接続先が起動していない場合もここで失敗する。");
        }
    }

    // ------------------------------------------------------------------

    /** チェック結果を集計して全体ステータスを決める。 */
    private static void summarize(TlsConfigResponse response, List<TlsCheckResult> checks, boolean probe) {
        int ok = 0;
        int ng = 0;
        int unknown = 0;
        for (TlsCheckResult check : checks) {
            switch (check.getStatus()) {
                case TlsCheckResult.STATUS_OK -> ok++;
                case TlsCheckResult.STATUS_NG -> ng++;
                default -> unknown++;
            }
        }
        response.setOkCount(ok);
        response.setNgCount(ng);
        response.setUnknownCount(unknown);

        if (ng > 0) {
            response.setStatus("NG");
            response.setMessage(ng + " 件のチェックが NG。checks[].hint の対処を行う。");
        } else if (unknown > 0) {
            response.setStatus("WARN");
            response.setMessage(unknown + " 件のチェックが判定不可（UNKNOWN）。checks[].hint を参照。");
        } else {
            response.setStatus("OK");
            response.setMessage("トラストストア・トラストマネージャー・クライアント SSL コンテキスト・"
                    + "JVM 既定 SSL コンテキストのすべてが期待どおり設定されている。"
                    + (probe ? "" : " 実通信まで確認するには ?probe=true を付ける。"));
        }
    }

    /** パス比較用に区切り文字を揃える（Windows での検証も想定）。 */
    private static String normalizePath(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
