package com.example.dhapp.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.example.dhapp.dto.CertificateInfo;
import com.example.dhapp.dto.SecureApiCallResponse;
import com.example.dhapp.dto.SecureApiComparison;
import com.example.dhapp.dto.TrustStoreCallResult;
import com.example.dhapp.dto.TrustStoreInfo;
import com.example.dhapp.dto.TrustStoresResponse;
import com.example.dhapp.service.TrustStoreInspector.LoadedTrustStore;

/**
 * <b>JVM が管理するトラストストア</b>と<b>JBoss EAP(Elytron) が管理するトラストストア</b>の
 * それぞれで、compose の {@code secure-api} サービス（HTTPS 必須の WireMock）へ接続し、
 * 結果を並べて比較する。
 *
 * <h2>2 系統のトラストストア</h2>
 * <table border="1">
 *   <caption>トラストストアの管理主体</caption>
 *   <tr><th>trustSource</th><th>実体</th><th>SSLContext の作り方</th></tr>
 *   <tr>
 *     <td>{@code JVM}</td>
 *     <td>{@code -Djavax.net.ssl.trustStore} で渡されたストア
 *         （未指定なら {@code $JAVA_HOME/lib/security/cacerts}）</td>
 *     <td>{@link SSLContext#getDefault()}（アプリは何も設定しない）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code JBOSS_EAP}</td>
 *     <td>elytron の {@code key-store}（例: {@code appTrustStore} →
 *         {@code $JBOSS_HOME/standalone/configuration/jboss-truststore.p12}）</td>
 *     <td>そのファイルから {@link TrustManagerFactory} を組み立てた専用 SSLContext</td>
 *   </tr>
 *   <tr>
 *     <td>{@code NONE}</td>
 *     <td>空のトラストストア（対照実験）</td>
 *     <td>信頼できる CA が 0 枚 → 必ず失敗するのが正しい</td>
 *   </tr>
 * </table>
 *
 * <p>JBoss 側ストアの位置は、まず設定（{@code app.secure-api.jboss.truststore-path}）、
 * 無ければ <b>elytron の管理モデル</b>（{@link ElytronSslInspector#keyStoreAttributes(String)} の
 * {@code path} / {@code relative-to}）から解決する。つまり jboss-cli で定義した内容を
 * そのまま追いかけるので、CLI 側を変えてもアプリの設定を触らずに追随できる。</p>
 *
 * <p><b>検証を緩めるオプションは用意しない。</b>ホスト名検証も有効なままにする。
 * 「取り込んだ証明書で検証できたから通信できた」ことを確認するための API のため。</p>
 *
 * <p>接続先の既定値は compose の設定（{@code container_compose_file} リポジトリ）に合わせてある。</p>
 * <pre>
 * SECURE_API_URL          https://secure-api:8443/api/v1/ping   （secure-api へ直接）
 * SECURE_API_VIA_ALB_URL  https://alb/secure/v1/ping            （ALB で TLS 終端 → 再暗号化）
 * </pre>
 */
@Service
public class SecureApiTlsService {

    private static final Logger log = LoggerFactory.getLogger(SecureApiTlsService.class);

    public static final String TRUST_SOURCE_JVM = "JVM";
    public static final String TRUST_SOURCE_JBOSS = "JBOSS_EAP";
    public static final String TRUST_SOURCE_NONE = "NONE";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TLS_HANDSHAKE_FAILED = "TLS_HANDSHAKE_FAILED";
    public static final String STATUS_CONNECT_FAILED = "CONNECT_FAILED";
    public static final String STATUS_TRUSTSTORE_UNAVAILABLE = "TRUSTSTORE_UNAVAILABLE";
    public static final String STATUS_INVALID_URL = "INVALID_URL";
    public static final String STATUS_ERROR = "ERROR";

    public static final String TARGET_DIRECT = "direct";
    public static final String TARGET_ALB = "alb";
    public static final String TARGET_CUSTOM = "custom";

    /** JBoss EAP が設定ディレクトリを指すシステムプロパティ（elytron の relative-to 既定）。 */
    private static final String PROP_SERVER_CONFIG_DIR = "jboss.server.config.dir";

    /** レスポンスボディから読み取る最大バイト数。 */
    private static final int MAX_BODY_READ_BYTES = 8192;

    /** レスポンスに載せるボディプレビューの最大文字数。 */
    private static final int BODY_PREVIEW_MAX_CHARS = 2048;

    /** 例外の原因チェーンを何段まで残すか。 */
    private static final int MAX_CAUSE_DEPTH = 10;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final String HANDSHAKE_FAILURE_HINT_JVM =
            "JVM 側トラストストアに secure-api のサーバ証明書の発行元（cacert.crt）が入っていない。"
                    + " (1) GET /api/secure-api/truststores で JVM 側ストアの中身を確認する。"
                    + " (2) keytool -importcert -alias cacert -file cacert.crt -keystore <truststore> で取り込む。"
                    + " (3) JBoss EAP の起動パラメータ -Djavax.net.ssl.trustStore がそのストアを"
                    + " 指しているかを確認する。";

    private static final String HANDSHAKE_FAILURE_HINT_JBOSS =
            "JBoss EAP(Elytron) 側トラストストアに発行元証明書が入っていない。"
                    + " (1) GET /api/secure-api/truststores で elytron の key-store が指すファイルと"
                    + " その中身を確認する。"
                    + " (2) keytool -importcert -alias cacert -file cacert.crt"
                    + " -keystore $JBOSS_HOME/standalone/configuration/jboss-truststore.p12"
                    + " -storetype PKCS12 で取り込み、EAP を再起動する。"
                    + " (3) jboss-cli の /subsystem=elytron/key-store=<名前>:read-resource で"
                    + " path / relative-to が想定どおりかを確認する。";

    private final String directUrl;
    private final String albUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String configuredJbossTrustStorePath;
    private final String jbossTrustStorePassword;
    private final String jbossTrustStoreType;
    private final String jbossElytronKeyStoreName;
    private final String jbossTrustStoreFallbackName;

    private final TrustStoreInspector trustStoreInspector;
    private final ElytronSslInspector elytronSslInspector;
    private final TlsHttpsClient tlsHttpsClient;

    public SecureApiTlsService(
            @Value("${app.secure-api.url:https://secure-api:8443/api/v1/ping}") String directUrl,
            @Value("${app.secure-api.via-alb-url:https://alb/secure/v1/ping}") String albUrl,
            @Value("${app.secure-api.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${app.secure-api.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${app.secure-api.jboss.truststore-path:}") String configuredJbossTrustStorePath,
            @Value("${app.secure-api.jboss.truststore-password:changeit}") String jbossTrustStorePassword,
            @Value("${app.secure-api.jboss.truststore-type:}") String jbossTrustStoreType,
            @Value("${app.secure-api.jboss.elytron-key-store:appTrustStore}") String jbossElytronKeyStoreName,
            @Value("${app.secure-api.jboss.truststore-file-name:jboss-truststore.p12}")
            String jbossTrustStoreFallbackName,
            TrustStoreInspector trustStoreInspector,
            ElytronSslInspector elytronSslInspector,
            TlsHttpsClient tlsHttpsClient) {
        this.directUrl = directUrl;
        this.albUrl = albUrl;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.configuredJbossTrustStorePath = configuredJbossTrustStorePath;
        this.jbossTrustStorePassword = jbossTrustStorePassword;
        this.jbossTrustStoreType = jbossTrustStoreType;
        this.jbossElytronKeyStoreName = jbossElytronKeyStoreName;
        this.jbossTrustStoreFallbackName = jbossTrustStoreFallbackName;
        this.trustStoreInspector = trustStoreInspector;
        this.elytronSslInspector = elytronSslInspector;
        this.tlsHttpsClient = tlsHttpsClient;
    }

    public String getDirectUrl() {
        return directUrl;
    }

    public String getAlbUrl() {
        return albUrl;
    }

    /**
     * JBoss EAP 側トラストストアの解決結果。
     *
     * @param path            解決できたファイルパス（null なら特定できなかった）
     * @param type            ストア種別（null なら中身から自動判別）
     * @param resolution      どうやって位置を決めたかの説明
     * @param elytronKeyStore 参照した elytron の key-store 名（設定で直接指定した場合は null）
     * @param attributes      その key-store の属性（管理モデルを読めた場合のみ）
     */
    public record JbossTrustStore(String path, String type, String resolution,
                                  String elytronKeyStore, Map<String, String> attributes) {
    }

    /**
     * 指定された接続先へ、要求されたトラストストアごとに HTTPS 接続して結果を返す。
     *
     * @param urlOverride 接続先 URL。空なら {@code target} で決まる既定 URL
     * @param target      {@code direct}（既定） / {@code alb}
     * @param trustParam  {@code jvm,jboss}（既定） / {@code all}（none も含む） / 個別指定
     * @param methodValue HTTP メソッド。空なら GET
     * @param body        GET 以外のときに送るボディ（null 可）
     * @param contentType body の Content-Type。空なら application/json
     */
    public SecureApiCallResponse call(String urlOverride, String target, String trustParam,
            String methodValue, String body, String contentType, String requestId) {

        long startedAt = System.currentTimeMillis();

        SecureApiCallResponse response = new SecureApiCallResponse();
        response.setRequestId(requestId);
        response.setTimestamp(OffsetDateTime.now().format(TIMESTAMP));

        String resolvedTarget;
        String url;
        if (StringUtils.hasText(urlOverride)) {
            url = urlOverride.trim();
            resolvedTarget = TARGET_CUSTOM;
        } else if (TARGET_ALB.equalsIgnoreCase(String.valueOf(target).trim())) {
            url = albUrl;
            resolvedTarget = TARGET_ALB;
        } else {
            url = directUrl;
            resolvedTarget = TARGET_DIRECT;
        }
        String method = StringUtils.hasText(methodValue)
                ? methodValue.trim().toUpperCase(Locale.ROOT) : "GET";

        response.setTarget(resolvedTarget);
        response.setUrl(url);
        response.setMethod(method);

        List<String> trustSources = resolveTrustSources(trustParam);
        log.info("secure-api call start. requestId={}, url={}, method={}, target={}, trustSources={}",
                requestId, url, method, resolvedTarget, trustSources);

        List<TrustStoreCallResult> results = new ArrayList<>();
        for (String trustSource : trustSources) {
            results.add(callWith(trustSource, url, method, body, contentType, requestId));
        }
        response.setResults(results);
        response.setComparison(compare(results));
        response.setStatus(resolveOverallStatus(results));
        response.setElapsedMs(System.currentTimeMillis() - startedAt);

        String report = buildReport(response);
        response.setReport(report);
        emit(report);

        return response;
    }

    /**
     * {@code trust} パラメータを、実行するトラストストアの並びに変換する。
     * 既定は {@code JVM} と {@code JBOSS_EAP} の 2 経路。
     */
    public List<String> resolveTrustSources(String trustParam) {
        if (!StringUtils.hasText(trustParam) || "both".equalsIgnoreCase(trustParam.trim())) {
            return List.of(TRUST_SOURCE_JVM, TRUST_SOURCE_JBOSS);
        }
        String value = trustParam.trim().toLowerCase(Locale.ROOT);
        if ("all".equals(value)) {
            // 対照実験（空のトラストストア）まで含める。
            return List.of(TRUST_SOURCE_JVM, TRUST_SOURCE_JBOSS, TRUST_SOURCE_NONE);
        }
        Set<String> sources = new LinkedHashSet<>();
        for (String token : value.split("[,\\s]+")) {
            switch (token) {
                case "jvm", "jdk", "default" -> sources.add(TRUST_SOURCE_JVM);
                case "jboss", "eap", "elytron", "jboss_eap" -> sources.add(TRUST_SOURCE_JBOSS);
                case "none", "empty" -> sources.add(TRUST_SOURCE_NONE);
                default -> log.warn("Unknown trust source '{}' was ignored. (jvm|jboss|none|all)", token);
            }
        }
        return sources.isEmpty() ? List.of(TRUST_SOURCE_JVM, TRUST_SOURCE_JBOSS) : new ArrayList<>(sources);
    }

    // ------------------------------------------------------------------------
    // 1 経路分の HTTPS 接続
    // ------------------------------------------------------------------------

    /** 1 つのトラストストアで HTTPS 接続し、TLS とアプリ応答の詳細を集める。 */
    private TrustStoreCallResult callWith(String trustSource, String url, String method,
            String body, String contentType, String requestId) {

        long startedAt = System.currentTimeMillis();

        TrustStoreCallResult result = new TrustStoreCallResult();
        result.setTrustSource(trustSource);
        result.setLabel(labelOf(trustSource));
        result.setUrl(url);
        result.setMethod(method);

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return fail(result, STATUS_INVALID_URL, startedAt, e,
                    "URL を解析できない: " + e.getMessage(),
                    "https://<ホスト>[:<ポート>]/<パス> の形式で指定する。");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
            return fail(result, STATUS_INVALID_URL, startedAt, null,
                    "https の URL ではない、またはホストが取得できない: " + url,
                    "本 API は TLS 検証が目的のため https の URL のみを受け付ける。");
        }
        String host = uri.getHost();
        int port = uri.getPort() < 0 ? 443 : uri.getPort();
        result.setHost(host);
        result.setPort(port);

        // --- トラストストアと SSLContext の準備 ---
        LoadedTrustStore trustStore;
        SSLContext sslContext;
        try {
            switch (trustSource) {
                case TRUST_SOURCE_JVM -> {
                    trustStore = trustStoreInspector.load();
                    result.setTrustStore(trustStore.info());
                    result.setTrustStoreResolution("システムプロパティ "
                            + TrustStoreInspector.PROP_TRUST_STORE
                            + "（JBoss EAP の standalone 起動パラメータ）。未指定なら JVM 既定の cacerts。");
                    // アプリ側では何も設定せず JVM 既定の SSLContext をそのまま使う
                    // （「アプリ無改変で通ること」を確認するのが目的のため）。
                    sslContext = SSLContext.getDefault();
                    result.setSslContextOrigin("SSLContext.getDefault()（JVM 既定）");
                }
                case TRUST_SOURCE_JBOSS -> {
                    JbossTrustStore resolved = resolveJbossTrustStore();
                    result.setTrustStoreResolution(resolved.resolution());
                    result.setElytronKeyStoreName(resolved.elytronKeyStore());
                    result.setElytronKeyStoreAttributes(resolved.attributes());
                    trustStore = trustStoreInspector.loadFrom(resolved.path(), jbossTrustStorePassword,
                            resolved.type(), resolved.resolution());
                    result.setTrustStore(trustStore.info());
                    if (trustStore.keyStore() == null) {
                        return fail(result, STATUS_TRUSTSTORE_UNAVAILABLE, startedAt, null,
                                "JBoss EAP 側トラストストアを読み込めない: "
                                        + trustStore.info().getLoadErrorMessage(),
                                "jboss-cli の /subsystem=elytron/key-store=" + jbossElytronKeyStoreName
                                        + ":read-resource で path / relative-to を確認し、"
                                        + "実ファイルが存在するか（entrypoint 等で生成されているか）を確認する。"
                                        + " パスを直接指定する場合は app.secure-api.jboss.truststore-path"
                                        + "（環境変数 JBOSS_TRUSTSTORE_FILE）を設定する。");
                    }
                    sslContext = sslContextFrom(trustStore.keyStore());
                    result.setSslContextOrigin("elytron の key-store が指すファイルから組み立てた"
                            + " TrustManagerFactory ベースの SSLContext");
                }
                case TRUST_SOURCE_NONE -> {
                    KeyStore empty = KeyStore.getInstance(KeyStore.getDefaultType());
                    empty.load(null, null);
                    TrustStoreInfo info = new TrustStoreInfo();
                    info.setSource("空のトラストストア（対照実験）");
                    info.setType(empty.getType());
                    info.setLoaded(true);
                    info.setEntryCount(0);
                    info.setCertificateEntryCount(0);
                    info.setAliases(List.of());
                    info.setAliasesComplete(true);
                    trustStore = new LoadedTrustStore(info, empty);
                    result.setTrustStore(info);
                    result.setTrustStoreResolution("信頼する CA を 1 枚も持たないストアを実行時に作成");
                    sslContext = sslContextFrom(empty);
                    result.setSslContextOrigin("空のトラストストアから組み立てた SSLContext"
                            + "（必ず失敗するのが正しい対照実験）");
                }
                default -> {
                    return fail(result, STATUS_ERROR, startedAt, null,
                            "未知のトラストストア種別: " + trustSource, "jvm | jboss | none を指定する。");
                }
            }
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            return fail(result, STATUS_TRUSTSTORE_UNAVAILABLE, startedAt, e,
                    "SSLContext を準備できない: " + e.getMessage(),
                    "トラストストアのパス・パスワード・種別（PKCS12 / JKS）を確認する。");
        }

        result.setSslContextProtocol(sslContext.getProtocol());
        result.setSslContextProvider(sslContext.getProvider().getName());

        // --- 1. TLS ハンドシェイク（プロトコル・暗号スイート・証明書チェーン） ---
        TlsHttpsClient.Handshake handshake;
        try {
            handshake = tlsHttpsClient.handshake(sslContext, host, port);
        } catch (SSLHandshakeException e) {
            log.error("secure-api TLS handshake failed. requestId={}, trustSource={}, host={}, port={}",
                    requestId, trustSource, host, port, e);
            return fail(result, STATUS_TLS_HANDSHAKE_FAILED, startedAt, e,
                    "TLS ハンドシェイクに失敗した: " + e.getMessage(), handshakeHint(trustSource));
        } catch (IOException e) {
            log.error("secure-api connect failed. requestId={}, trustSource={}, host={}, port={}",
                    requestId, trustSource, host, port, e);
            return fail(result, STATUS_CONNECT_FAILED, startedAt, e,
                    "接続できない: " + e.getMessage(),
                    "compose の secure-api サービスが起動しているか"
                            + "（docker compose ps secure-api）と、ホスト名・ポートを確認する。"
                            + " secure-api は --disable-http のため平文 HTTP では待ち受けていない。");
        }
        result.setTlsProtocol(handshake.protocol());
        result.setCipherSuite(handshake.cipherSuite());
        result.setPeerPrincipal(handshake.peerPrincipal());
        result.setHandshakeElapsedMs(handshake.elapsedMs());
        result.setServerCertificates(tlsHttpsClient.describeChain(handshake.chain(), trustStore));

        String anchorAlias =
                trustStoreInspector.findTrustAnchorAlias(trustStore.keyStore(), handshake.chain());
        result.setTrustAnchorAlias(anchorAlias);
        result.setVerifiedByTrustStore(anchorAlias != null);

        // --- 2. HTTPS リクエスト（アプリ応答まで確認する） ---
        try {
            sendRequest(result, sslContext, uri, method, body, contentType);
        } catch (SSLHandshakeException e) {
            log.error("secure-api TLS handshake failed on HTTP request. requestId={}, trustSource={}, url={}",
                    requestId, trustSource, url, e);
            return fail(result, STATUS_TLS_HANDSHAKE_FAILED, startedAt, e,
                    "TLS ハンドシェイクに失敗した: " + e.getMessage(), handshakeHint(trustSource));
        } catch (IOException e) {
            log.error("secure-api HTTPS request failed. requestId={}, trustSource={}, url={}",
                    requestId, trustSource, url, e);
            return fail(result, STATUS_CONNECT_FAILED, startedAt, e,
                    "HTTPS リクエストに失敗した: " + e.getMessage(),
                    "ハンドシェイクは成功しているため、証明書ではなく接続先アプリ側の応答"
                            + "（マッピング未定義・タイムアウト・切断）を確認する。");
        } catch (RuntimeException e) {
            log.error("Unexpected error during secure-api call. requestId={}, trustSource={}, url={}",
                    requestId, trustSource, url, e);
            return fail(result, STATUS_ERROR, startedAt, e, "想定外のエラー: " + e.getMessage(), null);
        }

        result.setStatus(STATUS_SUCCESS);
        result.setMessage(labelOf(trustSource) + "（" + trustStore.info().getPath()
                + "）でサーバ証明書を検証し、HTTPS 通信に成功した。"
                + (anchorAlias != null ? "トラストアンカー: alias=" + anchorAlias : ""));
        result.setElapsedMs(System.currentTimeMillis() - startedAt);

        log.info("secure-api call done. requestId={}, trustSource={}, status={}, httpStatus={}, "
                        + "tlsProtocol={}, cipherSuite={}, verifiedByTrustStore={}, trustAnchorAlias={}, "
                        + "elapsedMs={}",
                requestId, trustSource, result.getStatus(), result.getHttpStatus(),
                result.getTlsProtocol(), result.getCipherSuite(), result.isVerifiedByTrustStore(),
                result.getTrustAnchorAlias(), result.getElapsedMs());
        return result;
    }

    /** トラストストアだけを設定した（クライアント証明書は提示しない）SSLContext を作る。 */
    private static SSLContext sslContextFrom(KeyStore trustStore) throws GeneralSecurityException {
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    /** HTTPS リクエストを送り、ステータス・Content-Type・ボディ先頭を詰める。 */
    private void sendRequest(TrustStoreCallResult result, SSLContext sslContext, URI uri,
            String method, String body, String contentType) throws IOException {

        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        try {
            // 経路ごとに異なるトラストストアで検証させるため、SSLSocketFactory を明示的に差し替える。
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("X-Probe-Source", "dhapp");
            connection.setRequestProperty("X-Probe-Trust", result.getTrustSource());

            if (StringUtils.hasText(body) && !"GET".equals(method) && !"HEAD".equals(method)) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type",
                        StringUtils.hasText(contentType) ? contentType : "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int statusCode = connection.getResponseCode();
            result.setHttpStatus(statusCode);
            result.setResponseContentType(connection.getContentType());
            long declaredLength = connection.getContentLengthLong();
            if (declaredLength >= 0) {
                result.setResponseBodyLength(declaredLength);
            }
            try (InputStream in = statusCode >= 400 ? connection.getErrorStream()
                    : connection.getInputStream()) {
                result.setResponseBodyPreview(readPreview(in));
            }
        } finally {
            connection.disconnect();
        }
    }

    // ------------------------------------------------------------------------
    // JBoss EAP 側トラストストアの解決
    // ------------------------------------------------------------------------

    /**
     * JBoss EAP(Elytron) が管理するトラストストアの実ファイルを突き止める。
     *
     * <p>優先順位:</p>
     * <ol>
     *   <li>{@code app.secure-api.jboss.truststore-path}（環境変数 {@code JBOSS_TRUSTSTORE_FILE}）</li>
     *   <li>elytron の {@code key-store}（設定名 → {@code app.tls.elytron.key-store} →
     *       定義されている全 key-store）の {@code path} / {@code relative-to}</li>
     *   <li>{@code ${jboss.server.config.dir}/jboss-truststore.p12}</li>
     * </ol>
     */
    public JbossTrustStore resolveJbossTrustStore() {
        if (StringUtils.hasText(configuredJbossTrustStorePath)) {
            return new JbossTrustStore(configuredJbossTrustStorePath.trim(),
                    emptyToNull(jbossTrustStoreType),
                    "設定 app.secure-api.jboss.truststore-path（環境変数 JBOSS_TRUSTSTORE_FILE）",
                    null, null);
        }

        // elytron の管理モデルから探す。候補は「設定した名前 → TLS API 用の名前 → 定義済み全件」。
        List<String> candidates = new ArrayList<>();
        addIfPresent(candidates, jbossElytronKeyStoreName);
        addIfPresent(candidates, elytronSslInspector.getExpectedKeyStoreName());
        for (String name : elytronSslInspector.keyStoreNames()) {
            addIfPresent(candidates, name);
        }

        JbossTrustStore firstFound = null;
        for (String name : candidates) {
            Map<String, String> attributes = elytronSslInspector.keyStoreAttributes(name);
            if (attributes == null) {
                continue;
            }
            String path = ElytronSslInspector.lookup(attributes, ElytronSslInspector.ATTR_PATH);
            if (!StringUtils.hasText(path)) {
                continue;
            }
            String relativeTo = ElytronSslInspector.lookup(attributes, "relative-to");
            String resolvedPath = resolvePath(path, relativeTo);
            String type = ElytronSslInspector.lookup(attributes, "type");
            String resolution = "elytron の key-store=" + name + "（path=" + path
                    + (StringUtils.hasText(relativeTo) ? ", relative-to=" + relativeTo : "") + "）";

            JbossTrustStore resolved = new JbossTrustStore(resolvedPath,
                    StringUtils.hasText(jbossTrustStoreType) ? jbossTrustStoreType : type,
                    resolution, name, attributes);
            if (Files.isReadable(Paths.get(resolvedPath))) {
                // 実体があるものを優先する（定義だけあってファイルが無い key-store を飛ばす）。
                return resolved;
            }
            if (firstFound == null) {
                firstFound = resolved;
            }
        }
        if (firstFound != null) {
            return firstFound;
        }

        // 管理モデルを読めない環境（EAP 以外での実行など）向けのフォールバック。
        String configDir = System.getProperty(PROP_SERVER_CONFIG_DIR);
        if (StringUtils.hasText(configDir)) {
            return new JbossTrustStore(
                    Paths.get(configDir, jbossTrustStoreFallbackName).toString(),
                    emptyToNull(jbossTrustStoreType),
                    "既定の配置（${" + PROP_SERVER_CONFIG_DIR + "}/" + jbossTrustStoreFallbackName
                            + "）。elytron の管理モデルからは解決できなかった。",
                    null, null);
        }
        return new JbossTrustStore(null, emptyToNull(jbossTrustStoreType),
                "elytron の管理モデルを読めず、" + PROP_SERVER_CONFIG_DIR
                        + " も未設定のため位置を特定できない。", null, null);
    }

    /** elytron の {@code path} と {@code relative-to} から実パスを組み立てる。 */
    private static String resolvePath(String path, String relativeTo) {
        Path file = Paths.get(path);
        if (file.isAbsolute()) {
            return file.toString();
        }
        String base = StringUtils.hasText(relativeTo)
                ? System.getProperty(relativeTo) : System.getProperty(PROP_SERVER_CONFIG_DIR);
        return StringUtils.hasText(base) ? Paths.get(base).resolve(file).toString() : file.toString();
    }

    // ------------------------------------------------------------------------
    // トラストストアの内容だけを見る（接続はしない）
    // ------------------------------------------------------------------------

    /**
     * JVM 管理・JBoss EAP 管理の 2 つのトラストストアの中身と、elytron の登録状態を返す。
     * 接続に失敗したときに「どちらのストアに何が入っているか」を切り分けるための API。
     */
    public TrustStoresResponse inspectTrustStores(String requestId) {
        TrustStoresResponse response = new TrustStoresResponse();
        response.setRequestId(requestId);
        response.setTimestamp(OffsetDateTime.now().format(TIMESTAMP));

        LoadedTrustStore jvm = trustStoreInspector.load();
        response.setJvm(jvm.info());
        response.setJvmResolution("システムプロパティ " + TrustStoreInspector.PROP_TRUST_STORE
                + "（未指定なら JVM 既定の cacerts）");

        JbossTrustStore resolved = resolveJbossTrustStore();
        response.setJbossEapResolution(resolved.resolution());
        response.setElytronKeyStoreName(resolved.elytronKeyStore());
        response.setElytronKeyStoreAttributes(resolved.attributes());
        response.setJbossEap(trustStoreInspector.loadFrom(resolved.path(), jbossTrustStorePassword,
                resolved.type(), resolved.resolution()).info());

        response.setElytron(elytronSslInspector.inspect());

        String report = buildTrustStoresReport(response);
        response.setReport(report);
        emit(report);
        return response;
    }

    /** {@link #inspectTrustStores(String)} 用のテキストレポート。 */
    private String buildTrustStoresReport(TrustStoresResponse response) {
        String nl = System.lineSeparator();
        String line = "================================================================================";

        StringBuilder sb = new StringBuilder(2048);
        sb.append(nl).append(line).append(nl);
        sb.append("トラストストアの内容（JVM 管理 / JBoss EAP 管理）").append(nl);
        sb.append("requestId=").append(response.getRequestId())
                .append(", timestamp=").append(response.getTimestamp()).append(nl);
        sb.append(line).append(nl);

        appendStore(sb, "[1] JVM が管理するトラストストア", response.getJvm(), response.getJvmResolution());
        appendStore(sb, "[2] JBoss EAP が管理するトラストストア", response.getJbossEap(),
                response.getJbossEapResolution());

        sb.append(nl).append("[3] elytron の登録状態").append(nl);
        sb.append("  keyStoreName    : ").append(response.getElytronKeyStoreName()).append(nl);
        if (response.getElytronKeyStoreAttributes() != null) {
            for (Map.Entry<String, String> attribute
                    : response.getElytronKeyStoreAttributes().entrySet()) {
                sb.append("      ").append(attribute.getKey()).append(" = ")
                        .append(attribute.getValue()).append(nl);
            }
        }
        if (response.getElytron() != null) {
            sb.append("  available       : ").append(response.getElytron().isAvailable()).append(nl);
            if (response.getElytron().getUnavailableReason() != null) {
                sb.append("  reason          : ")
                        .append(response.getElytron().getUnavailableReason()).append(nl);
            }
            if (response.getElytron().getKeyStoreNames() != null) {
                sb.append("  keyStores       : ")
                        .append(String.join(", ", response.getElytron().getKeyStoreNames())).append(nl);
            }
            if (response.getElytron().getTrustManagerNames() != null) {
                sb.append("  trustManagers   : ")
                        .append(String.join(", ", response.getElytron().getTrustManagerNames())).append(nl);
            }
            if (response.getElytron().getClientSslContextNames() != null) {
                sb.append("  clientSslCtxs   : ")
                        .append(String.join(", ", response.getElytron().getClientSslContextNames()))
                        .append(nl);
            }
            sb.append("  defaultSslCtx   : ")
                    .append(response.getElytron().getDefaultSslContext()).append(nl);
        }
        sb.append(line);
        return sb.toString();
    }

    private void appendStore(StringBuilder sb, String title, TrustStoreInfo store, String resolution) {
        String nl = System.lineSeparator();
        sb.append(nl).append(title).append(nl);
        sb.append("  resolution      : ").append(resolution).append(nl);
        if (store == null) {
            sb.append("  (読み取れなかった)").append(nl);
            return;
        }
        sb.append("  source          : ").append(store.getSource()).append(nl);
        sb.append("  path            : ").append(store.getPath()).append(nl);
        sb.append("  type            : ").append(store.getType()).append(nl);
        sb.append("  exists/readable : ").append(store.isExists()).append(" / ")
                .append(store.isReadable()).append(nl);
        sb.append("  loaded          : ").append(store.isLoaded()).append(nl);
        sb.append("  entries         : ").append(store.getEntryCount())
                .append("（証明書エントリ ").append(store.getCertificateEntryCount()).append("）").append(nl);
        if (store.getAliases() != null && !store.getAliases().isEmpty()) {
            sb.append("  aliases         : ").append(String.join(", ", store.getAliases()))
                    .append(store.isAliasesComplete() ? "" : " ...(以下略)").append(nl);
        }
        if (store.getLoadErrorMessage() != null) {
            sb.append("  error           : ").append(store.getLoadErrorMessage()).append(nl);
        }
    }

    // ------------------------------------------------------------------------
    // 比較・レポート
    // ------------------------------------------------------------------------

    /** JVM 側と JBoss EAP 側の結果を突き合わせる。 */
    public SecureApiComparison compare(List<TrustStoreCallResult> results) {
        SecureApiComparison comparison = new SecureApiComparison();

        TrustStoreCallResult jvm = findResult(results, TRUST_SOURCE_JVM);
        TrustStoreCallResult jboss = findResult(results, TRUST_SOURCE_JBOSS);
        TrustStoreCallResult none = findResult(results, TRUST_SOURCE_NONE);

        if (jvm != null) {
            comparison.setJvmStatus(jvm.getStatus());
            comparison.setJvmSucceeded(STATUS_SUCCESS.equals(jvm.getStatus()));
            comparison.setJvmTrustAnchorAlias(jvm.getTrustAnchorAlias());
            if (jvm.getTrustStore() != null) {
                comparison.setJvmTrustStorePath(jvm.getTrustStore().getPath());
            }
        }
        if (jboss != null) {
            comparison.setJbossStatus(jboss.getStatus());
            comparison.setJbossSucceeded(STATUS_SUCCESS.equals(jboss.getStatus()));
            comparison.setJbossTrustAnchorAlias(jboss.getTrustAnchorAlias());
            if (jboss.getTrustStore() != null) {
                comparison.setJbossTrustStorePath(jboss.getTrustStore().getPath());
            }
        }
        if (none != null) {
            comparison.setNoneStatus(none.getStatus());
        }
        comparison.setBothSucceeded(comparison.isJvmSucceeded() && comparison.isJbossSucceeded());
        comparison.setConsistent(jvm != null && jboss != null
                && comparison.isJvmSucceeded() == comparison.isJbossSucceeded());
        comparison.setSameServerCertificate(sameLeafCertificate(jvm, jboss));

        StringBuilder summary = new StringBuilder();
        if (jvm == null || jboss == null) {
            summary.append("JVM 側と JBoss EAP 側の両方を実行していないため比較できない"
                    + "（trust=jvm,jboss で両方実行する）。");
        } else if (comparison.isBothSucceeded()) {
            summary.append("★JVM 側・JBoss EAP 側のどちらのトラストストアでも secure-api への HTTPS 接続に成功した。"
                    + " 2 系統とも証明書の取り込みが効いている。");
            if (!comparison.isSameServerCertificate()) {
                summary.append(" ただし提示されたサーバ証明書が経路間で異なる（接続先を確認する）。");
            }
        } else if (comparison.isJvmSucceeded()) {
            summary.append("JVM 側は成功したが JBoss EAP 側は失敗した（")
                    .append(comparison.getJbossStatus())
                    .append("）。elytron の key-store が指すファイルに発行元証明書が入っていない可能性が高い。");
            comparison.setHint(jboss.getHint());
        } else if (comparison.isJbossSucceeded()) {
            summary.append("JBoss EAP 側は成功したが JVM 側は失敗した（")
                    .append(comparison.getJvmStatus())
                    .append("）。-Djavax.net.ssl.trustStore が指すストアへの取り込みを確認する。");
            comparison.setHint(jvm.getHint());
        } else {
            summary.append("どちらのトラストストアでも接続に失敗した（JVM=")
                    .append(comparison.getJvmStatus()).append(", JBoss EAP=")
                    .append(comparison.getJbossStatus())
                    .append("）。接続先の起動状態と、両ストアへの証明書取り込みを確認する。");
            comparison.setHint(jvm.getHint() != null ? jvm.getHint() : jboss.getHint());
        }
        if (none != null) {
            if (STATUS_SUCCESS.equals(none.getStatus())) {
                summary.append(" ★対照実験（空のトラストストア）でも接続できてしまっている——"
                        + "どこかで証明書検証が迂回されている疑いがある。");
            } else {
                summary.append(" 対照実験（空のトラストストア）は想定どおり失敗した（")
                        .append(none.getStatus()).append("）。");
            }
        }
        comparison.setSummary(summary.toString());
        return comparison;
    }

    /** 2 経路が同じサーバ証明書（リーフ）を見ているか。 */
    private static boolean sameLeafCertificate(TrustStoreCallResult a, TrustStoreCallResult b) {
        String left = leafFingerprint(a);
        String right = leafFingerprint(b);
        return left != null && Objects.equals(left, right);
    }

    private static String leafFingerprint(TrustStoreCallResult result) {
        if (result == null || result.getServerCertificates() == null
                || result.getServerCertificates().isEmpty()) {
            return null;
        }
        return result.getServerCertificates().get(0).getSha256Fingerprint();
    }

    private static TrustStoreCallResult findResult(List<TrustStoreCallResult> results, String trustSource) {
        for (TrustStoreCallResult result : results) {
            if (trustSource.equals(result.getTrustSource())) {
                return result;
            }
        }
        return null;
    }

    /**
     * 総合ステータス。対照実験（NONE）は「失敗するのが正しい」ので判定から除く。
     */
    private static String resolveOverallStatus(List<TrustStoreCallResult> results) {
        int considered = 0;
        int succeeded = 0;
        for (TrustStoreCallResult result : results) {
            if (TRUST_SOURCE_NONE.equals(result.getTrustSource())) {
                continue;
            }
            considered++;
            if (STATUS_SUCCESS.equals(result.getStatus())) {
                succeeded++;
            }
        }
        if (considered == 0 || succeeded == 0) {
            return STATUS_FAILED;
        }
        return succeeded == considered ? STATUS_SUCCESS : STATUS_PARTIAL;
    }

    /**
     * レポートをログとコンソールの両方へ出力する。
     *
     * <p>要件が「詳細に画面表示とログ出力」であるため、ログ基盤の設定に依存せず
     * 標準出力へも直接書き出す（{@link ConsoleWriter}）。画面表示はレスポンス JSON の
     * {@code report} および {@code ?format=text} でも取得できる。</p>
     */
    private void emit(String report) {
        log.info("{}", report);
        ConsoleWriter.println(report);
    }

    /** ログ・コンソール・レスポンスで共用するテキストレポートを組み立てる。 */
    public String buildReport(SecureApiCallResponse response) {
        String nl = System.lineSeparator();
        String line = "================================================================================";

        StringBuilder sb = new StringBuilder(4096);
        sb.append(nl).append(line).append(nl);
        sb.append("secure-api への HTTPS 接続確認（JVM / JBoss EAP の各トラストストア）").append(nl);
        sb.append("requestId=").append(response.getRequestId())
                .append(", timestamp=").append(response.getTimestamp())
                .append(", status=").append(response.getStatus())
                .append(", elapsedMs=").append(response.getElapsedMs()).append(nl);
        sb.append("target=").append(response.getTarget())
                .append(", url=").append(response.getUrl())
                .append(", method=").append(response.getMethod()).append(nl);
        sb.append(line).append(nl);

        int index = 1;
        for (TrustStoreCallResult result : response.getResults()) {
            appendResult(sb, "[" + index++ + "] " + result.getLabel(), result);
        }
        appendComparison(sb, response.getComparison());
        sb.append(line);
        return sb.toString();
    }

    private void appendResult(StringBuilder sb, String title, TrustStoreCallResult result) {
        String nl = System.lineSeparator();
        sb.append(nl).append(title).append(nl);
        sb.append("  trustSource     : ").append(result.getTrustSource()).append(nl);
        sb.append("  status          : ").append(result.getStatus()).append(nl);
        sb.append("  url             : ").append(result.getUrl())
                .append("  (host=").append(result.getHost())
                .append(", port=").append(result.getPort()).append(")").append(nl);
        sb.append("  trustStoreOrigin: ").append(result.getTrustStoreResolution()).append(nl);
        if (result.getElytronKeyStoreName() != null) {
            sb.append("  elytronKeyStore : ").append(result.getElytronKeyStoreName()).append(nl);
        }
        if (result.getElytronKeyStoreAttributes() != null) {
            for (Map.Entry<String, String> attribute : result.getElytronKeyStoreAttributes().entrySet()) {
                sb.append("      ").append(attribute.getKey()).append(" = ")
                        .append(attribute.getValue()).append(nl);
            }
        }
        TrustStoreInfo store = result.getTrustStore();
        if (store != null) {
            sb.append("  trustStorePath  : ").append(store.getPath()).append(nl);
            sb.append("  trustStoreType  : ").append(store.getType())
                    .append(", entries=").append(store.getEntryCount())
                    .append(", certEntries=").append(store.getCertificateEntryCount()).append(nl);
            if (store.getAliases() != null && !store.getAliases().isEmpty()) {
                sb.append("  aliases         : ").append(String.join(", ", store.getAliases()))
                        .append(store.isAliasesComplete() ? "" : " ...(以下略)").append(nl);
            }
            if (store.getLoadErrorMessage() != null) {
                sb.append("  trustStoreError : ").append(store.getLoadErrorMessage()).append(nl);
            }
        }
        sb.append("  sslContext      : ").append(result.getSslContextOrigin())
                .append(" [protocol=").append(result.getSslContextProtocol())
                .append(", provider=").append(result.getSslContextProvider()).append("]").append(nl);
        sb.append("  tlsProtocol     : ").append(result.getTlsProtocol()).append(nl);
        sb.append("  cipherSuite     : ").append(result.getCipherSuite()).append(nl);
        sb.append("  peerPrincipal   : ").append(result.getPeerPrincipal()).append(nl);
        sb.append("  handshakeMs     : ").append(result.getHandshakeElapsedMs()).append(nl);
        sb.append("  verifiedByStore : ").append(result.isVerifiedByTrustStore())
                .append(result.getTrustAnchorAlias() == null
                        ? "" : "（trustAnchorAlias=" + result.getTrustAnchorAlias() + "）").append(nl);
        if (result.getServerCertificates() != null) {
            sb.append("  --- サーバ証明書チェーン ---").append(nl);
            for (CertificateInfo certificate : result.getServerCertificates()) {
                sb.append("      [").append(certificate.getPosition()).append("] subject=")
                        .append(certificate.getSubjectDn()).append(nl);
                sb.append("          issuer   = ").append(certificate.getIssuerDn()).append(nl);
                sb.append("          validity = ").append(certificate.getNotBefore())
                        .append(" 〜 ").append(certificate.getNotAfter())
                        .append(certificate.isExpired() ? "（★期限切れ）" : "").append(nl);
                sb.append("          sha256   = ").append(certificate.getSha256Fingerprint()).append(nl);
                sb.append("          inStore  = ").append(certificate.isInTrustStore())
                        .append(certificate.getTrustStoreAlias() == null
                                ? "" : "（alias=" + certificate.getTrustStoreAlias() + "）").append(nl);
                if (certificate.getSubjectAlternativeNames() != null
                        && !certificate.getSubjectAlternativeNames().isEmpty()) {
                    sb.append("          san      = ")
                            .append(String.join(", ", certificate.getSubjectAlternativeNames())).append(nl);
                }
            }
        }
        sb.append("  httpStatus      : ").append(result.getHttpStatus()).append(nl);
        sb.append("  contentType     : ").append(result.getResponseContentType()).append(nl);
        if (result.getResponseBodyPreview() != null) {
            sb.append("  responseBody    : ").append(result.getResponseBodyPreview()).append(nl);
        }
        if (result.getMessage() != null) {
            sb.append("  message         : ").append(result.getMessage()).append(nl);
        }
        if (result.getExceptionClass() != null) {
            sb.append("  exceptionClass  : ").append(result.getExceptionClass()).append(nl);
        }
        if (result.getCauseChain() != null) {
            for (String cause : result.getCauseChain()) {
                sb.append("  causedBy        : ").append(cause).append(nl);
            }
        }
        if (result.getHint() != null) {
            sb.append("  hint            : ").append(result.getHint()).append(nl);
        }
        sb.append("  elapsedMs       : ").append(result.getElapsedMs()).append(nl);
    }

    private void appendComparison(StringBuilder sb, SecureApiComparison comparison) {
        String nl = System.lineSeparator();
        sb.append(nl).append("[比較] JVM 管理ストア vs JBoss EAP 管理ストア").append(nl);
        sb.append("  jvm             : ").append(comparison.getJvmStatus())
                .append("  store=").append(comparison.getJvmTrustStorePath())
                .append("  anchor=").append(comparison.getJvmTrustAnchorAlias()).append(nl);
        sb.append("  jbossEap        : ").append(comparison.getJbossStatus())
                .append("  store=").append(comparison.getJbossTrustStorePath())
                .append("  anchor=").append(comparison.getJbossTrustAnchorAlias()).append(nl);
        if (comparison.getNoneStatus() != null) {
            sb.append("  none(対照実験)  : ").append(comparison.getNoneStatus()).append(nl);
        }
        sb.append("  bothSucceeded   : ").append(comparison.isBothSucceeded()).append(nl);
        sb.append("  consistent      : ").append(comparison.isConsistent()).append(nl);
        sb.append("  sameServerCert  : ").append(comparison.isSameServerCertificate()).append(nl);
        sb.append("  summary         : ").append(comparison.getSummary()).append(nl);
        if (comparison.getHint() != null) {
            sb.append("  hint            : ").append(comparison.getHint()).append(nl);
        }
    }

    // ------------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------------

    private static String labelOf(String trustSource) {
        return switch (trustSource) {
            case TRUST_SOURCE_JVM -> "JVM が管理するトラストストア（-Djavax.net.ssl.trustStore）";
            case TRUST_SOURCE_JBOSS -> "JBoss EAP が管理するトラストストア（elytron key-store）";
            case TRUST_SOURCE_NONE -> "空のトラストストア（対照実験）";
            default -> trustSource;
        };
    }

    private static String handshakeHint(String trustSource) {
        return switch (trustSource) {
            case TRUST_SOURCE_JVM -> HANDSHAKE_FAILURE_HINT_JVM;
            case TRUST_SOURCE_JBOSS -> HANDSHAKE_FAILURE_HINT_JBOSS;
            case TRUST_SOURCE_NONE ->
                    "信頼できる CA を 1 枚も持たないストアで検証したため失敗している（これが期待どおり）。";
            default -> null;
        };
    }

    /** 失敗時の結果を組み立てる。HTTP は 200 で返し、status で失敗理由を示す。 */
    private static TrustStoreCallResult fail(TrustStoreCallResult result, String status, long startedAt,
            Throwable cause, String message, String hint) {
        result.setStatus(status);
        result.setMessage(message);
        result.setHint(hint);
        if (cause != null) {
            result.setExceptionClass(cause.getClass().getName());
            result.setCauseChain(causeChain(cause));
        }
        result.setElapsedMs(System.currentTimeMillis() - startedAt);
        return result;
    }

    /** 例外の原因チェーンを文字列化する（PKIX の失敗理由は最下段に出ることが多い）。 */
    private static List<String> causeChain(Throwable throwable) {
        List<String> causes = new ArrayList<>();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            causes.add(current.getClass().getName() + ": " + current.getMessage());
            current = current.getCause();
        }
        return causes;
    }

    /** ボディの先頭だけを読む（検証用途なので全量は読まない）。 */
    private static String readPreview(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int total = 0;
        int read;
        while (total < MAX_BODY_READ_BYTES && (read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
            total += read;
        }
        String text = buffer.toString(StandardCharsets.UTF_8);
        if (text.length() > BODY_PREVIEW_MAX_CHARS) {
            return text.substring(0, BODY_PREVIEW_MAX_CHARS) + "...(truncated)";
        }
        return text;
    }

    private static void addIfPresent(List<String> names, String name) {
        if (StringUtils.hasText(name) && !names.contains(name)) {
            names.add(name);
        }
    }

    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
