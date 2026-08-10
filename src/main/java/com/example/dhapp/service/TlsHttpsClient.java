package com.example.dhapp.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.example.dhapp.dto.CertificateInfo;
import com.example.dhapp.dto.TlsCallResponse;
import com.example.dhapp.service.TrustStoreInspector.LoadedTrustStore;

/**
 * 指定された URL に対し、<b>JVM のトラストストアに登録された自己署名証明書（cacert.crt）</b>で
 * サーバ証明書を検証しながら HTTPS 通信を行う。
 *
 * <h2>どのトラスト設定が使われるか</h2>
 * 本クライアントは独自のトラストマネージャを組み立てず、<b>JVM 既定の SSLContext</b>
 * （{@link SSLContext#getDefault()}）だけを使う。これにより、以下のどちらの経路で登録された
 * 自己署名証明書でもそのまま検証に使われ、「設定が効いているか」の確認になる。
 *
 * <ul>
 *   <li>JBoss EAP standalone 起動パラメータ
 *       {@code -Djavax.net.ssl.trustStore} / {@code -Djavax.net.ssl.trustStorePassword}</li>
 *   <li>jboss-cli で登録した elytron の
 *       {@code /subsystem=elytron:write-attribute(name=default-ssl-context, ...)}
 *       （設定されていると Elytron が {@code SSLContext.setDefault()} を行うため、
 *       こちらが優先される）</li>
 * </ul>
 *
 * <p><b>検証を緩めるオプションは一切用意しない。</b>ホスト名検証（endpoint identification）も
 * 有効なままにしてある。「証明書を信頼できたから通信できた」ことを確認するための API なので、
 * 検証を無効化できると意味を失うため。</p>
 *
 * <h2>2 段階で確認する</h2>
 * <ol>
 *   <li>生の {@link SSLSocket} でハンドシェイクし、TLS プロトコル・暗号スイート・
 *       サーバ証明書チェーンを取得する（{@code HttpsURLConnection} からは
 *       ネゴシエートされた TLS バージョンが取れないため）</li>
 *   <li>{@link HttpsURLConnection} で実際に HTTP リクエストを送り、ステータスとボディを取得する</li>
 * </ol>
 * ハンドシェイクだけ成功して HTTP が失敗した場合でも、1 の結果はレスポンスに残る。
 */
@Service
public class TlsHttpsClient {

    private static final Logger log = LoggerFactory.getLogger(TlsHttpsClient.class);

    /** レスポンスボディから読み取る最大バイト数。 */
    private static final int MAX_BODY_READ_BYTES = 8192;

    /** レスポンスに載せるボディプレビューの最大文字数。 */
    private static final int BODY_PREVIEW_MAX_CHARS = 2048;

    /** SNI を付けてよいか（IPv4 / IPv6 リテラルには SNI を付けられない）。 */
    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9.]+$|^[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*$");

    /** ホスト名検証を有効にするための endpoint identification algorithm。 */
    private static final String ENDPOINT_ID_HTTPS = "HTTPS";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_TLS_HANDSHAKE_FAILED = "TLS_HANDSHAKE_FAILED";
    public static final String STATUS_CONNECT_FAILED = "CONNECT_FAILED";
    public static final String STATUS_INVALID_URL = "INVALID_URL";
    public static final String STATUS_ERROR = "ERROR";

    private static final String HANDSHAKE_FAILURE_HINT =
            "サーバ証明書をトラストストアで検証できていない。"
                    + "(1) GET /api/tls/config で cacert.crt がトラストストアに登録済みか確認する。"
                    + " (2) 未登録なら keytool -importcert -alias cacert -file cacert.crt -keystore <truststore> で登録する。"
                    + " (3) elytron の default-ssl-context が別のトラストマネージャを指していると"
                    + " -Djavax.net.ssl.trustStore より優先されるため、そのトラストマネージャの key-store も確認する。"
                    + " (4) 証明書は正しいがホスト名が一致しない場合は、URL のホスト名と証明書の"
                    + " subjectAltName / CN を合わせる。";

    private final String defaultUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final TrustStoreInspector trustStoreInspector;

    public TlsHttpsClient(
            @Value("${app.tls.target-url:https://localhost:8443/}") String defaultUrl,
            @Value("${app.tls.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${app.tls.read-timeout-ms:5000}") int readTimeoutMs,
            TrustStoreInspector trustStoreInspector) {
        this.defaultUrl = defaultUrl;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.trustStoreInspector = trustStoreInspector;
    }

    public String getDefaultUrl() {
        return defaultUrl;
    }

    /**
     * TLS ハンドシェイクの結果。
     *
     * @param protocol    ネゴシエートされた TLS プロトコル
     * @param cipherSuite ネゴシエートされた暗号スイート
     * @param peerPrincipal サーバ証明書の Subject
     * @param chain       サーバから提示された証明書チェーン
     * @param elapsedMs   ハンドシェイクに要した時間
     */
    public record Handshake(String protocol, String cipherSuite, String peerPrincipal,
                            List<X509Certificate> chain, long elapsedMs) {
    }

    /**
     * 指定 URL へ HTTPS でリクエストし、TLS ハンドシェイクの内容と HTTP 応答を返す。
     *
     * <p>失敗しても例外は送出せず、{@code status} に理由を入れて返す。</p>
     *
     * @param urlOverride 呼び出しごとの URL。空なら {@code app.tls.target-url}
     * @param methodValue HTTP メソッド。空なら GET
     * @param body        GET 以外のときに送るボディ（null 可）
     * @param contentType body の Content-Type。空なら application/json
     */
    public TlsCallResponse call(String urlOverride, String methodValue, String body,
            String contentType, String requestId) {

        long startedAt = System.currentTimeMillis();

        TlsCallResponse response = new TlsCallResponse();
        response.setRequestId(requestId);

        String url = StringUtils.hasText(urlOverride) ? urlOverride.trim() : defaultUrl;
        String method = StringUtils.hasText(methodValue)
                ? methodValue.trim().toUpperCase(Locale.ROOT) : "GET";
        response.setUrl(url);
        response.setMethod(method);

        LoadedTrustStore trustStore = trustStoreInspector.load();
        response.setTrustStore(trustStore.info());

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return fail(response, STATUS_INVALID_URL, startedAt, e,
                    "URL を解析できない: " + e.getMessage(),
                    "https://<ホスト>[:<ポート>]/<パス> の形式で指定する。");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
            return fail(response, STATUS_INVALID_URL, startedAt, null,
                    "https の URL ではない、またはホストが取得できない: " + url,
                    "本 API は TLS の検証が目的のため https の URL のみを受け付ける。");
        }
        String host = uri.getHost();
        int port = uri.getPort() < 0 ? 443 : uri.getPort();
        response.setHost(host);
        response.setPort(port);

        SSLContext sslContext;
        try {
            sslContext = SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            return fail(response, STATUS_ERROR, startedAt, e,
                    "JVM 既定の SSLContext を取得できない: " + e.getMessage(), null);
        }
        response.setSslContextProtocol(sslContext.getProtocol());
        response.setSslContextProvider(sslContext.getProvider().getName());

        log.info("TLS call start. requestId={}, url={}, method={}, host={}, port={}, "
                        + "trustStore={}, sslContextProvider={}",
                requestId, url, method, host, port, trustStore.info().getPath(),
                response.getSslContextProvider());

        // --- 1. ハンドシェイクの確認 ---
        Handshake handshake;
        try {
            handshake = handshake(sslContext, host, port);
        } catch (SSLHandshakeException e) {
            log.error("TLS handshake failed. requestId={}, host={}, port={}", requestId, host, port, e);
            return fail(response, STATUS_TLS_HANDSHAKE_FAILED, startedAt, e,
                    "TLS ハンドシェイクに失敗した: " + e.getMessage(), HANDSHAKE_FAILURE_HINT);
        } catch (IOException e) {
            log.error("TLS connect failed. requestId={}, host={}, port={}", requestId, host, port, e);
            return fail(response, STATUS_CONNECT_FAILED, startedAt, e,
                    "接続できない: " + e.getMessage(),
                    "ホスト名・ポート・ネットワーク経路（SG / FW）と、"
                            + "接続先が TLS を待ち受けているかを確認する。");
        }
        applyHandshake(response, handshake, trustStore);

        // --- 2. HTTP リクエストの実行 ---
        try {
            sendRequest(response, sslContext, uri, method, body, contentType);
            response.setStatus(STATUS_SUCCESS);
            response.setMessage("JVM 既定の SSLContext（トラストストア: "
                    + trustStore.info().getPath() + "）でサーバ証明書を検証し、HTTPS 通信に成功した。");
        } catch (SSLHandshakeException e) {
            log.error("TLS handshake failed on HTTP request. requestId={}, url={}", requestId, url, e);
            return fail(response, STATUS_TLS_HANDSHAKE_FAILED, startedAt, e,
                    "TLS ハンドシェイクに失敗した: " + e.getMessage(), HANDSHAKE_FAILURE_HINT);
        } catch (IOException e) {
            log.error("HTTPS request failed. requestId={}, url={}", requestId, url, e);
            return fail(response, STATUS_CONNECT_FAILED, startedAt, e,
                    "HTTPS リクエストに失敗した: " + e.getMessage(),
                    "ハンドシェイクは成功しているため、証明書ではなく接続先アプリ側の"
                            + "応答（タイムアウト・切断）を確認する。");
        } catch (RuntimeException e) {
            log.error("Unexpected error during HTTPS request. requestId={}, url={}", requestId, url, e);
            return fail(response, STATUS_ERROR, startedAt, e,
                    "想定外のエラー: " + e.getMessage(), null);
        }

        response.setElapsedMs(System.currentTimeMillis() - startedAt);
        log.info("TLS call done. requestId={}, status={}, httpStatus={}, tlsProtocol={}, cipherSuite={}, "
                        + "verifiedByTrustStore={}, trustAnchorAlias={}, elapsedMs={}",
                requestId, response.getStatus(), response.getHttpStatus(), response.getTlsProtocol(),
                response.getCipherSuite(), response.isVerifiedByTrustStore(),
                response.getTrustAnchorAlias(), response.getElapsedMs());
        return response;
    }

    /**
     * 生の {@link SSLSocket} でハンドシェイクだけを行い、TLS の詳細を取得する。
     * ホスト名検証（endpoint identification = HTTPS）は有効のままにする。
     */
    public Handshake handshake(SSLContext sslContext, String host, int port) throws IOException {
        long startedAt = System.currentTimeMillis();
        SSLSocketFactory factory = sslContext.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);

            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm(ENDPOINT_ID_HTTPS);
            if (!IP_LITERAL.matcher(host).matches()) {
                // IP リテラルには SNI を付けられない（SNIHostName が IllegalArgumentException を投げる）。
                parameters.setServerNames(List.of(new SNIHostName(host)));
            }
            socket.setSSLParameters(parameters);

            socket.startHandshake();

            SSLSession session = socket.getSession();
            String peerPrincipal = null;
            List<X509Certificate> chain = new ArrayList<>();
            try {
                peerPrincipal = session.getPeerPrincipal().getName();
                for (Certificate certificate : session.getPeerCertificates()) {
                    if (certificate instanceof X509Certificate x509) {
                        chain.add(x509);
                    }
                }
            } catch (SSLPeerUnverifiedException e) {
                // ハンドシェイク成功後は通常起きないが、匿名暗号スイート等では起こり得る。
                log.warn("Peer is not verified even though the handshake succeeded. host={}, port={}", host, port, e);
            }
            return new Handshake(session.getProtocol(), session.getCipherSuite(), peerPrincipal,
                    chain, System.currentTimeMillis() - startedAt);
        }
    }

    /** {@link SSLContext#getDefault()} を使ってハンドシェイクする簡易版（設定確認 API 用）。 */
    public Handshake handshake(String host, int port) throws IOException, NoSuchAlgorithmException {
        return handshake(SSLContext.getDefault(), host, port);
    }

    /** ハンドシェイク結果をレスポンスへ反映し、トラストアンカーの照合も行う。 */
    private void applyHandshake(TlsCallResponse response, Handshake handshake, LoadedTrustStore trustStore) {
        response.setTlsProtocol(handshake.protocol());
        response.setCipherSuite(handshake.cipherSuite());
        response.setPeerPrincipal(handshake.peerPrincipal());
        response.setHandshakeElapsedMs(handshake.elapsedMs());
        response.setServerCertificates(describeChain(handshake.chain(), trustStore));

        String anchorAlias = trustStoreInspector.findTrustAnchorAlias(trustStore.keyStore(), handshake.chain());
        response.setTrustAnchorAlias(anchorAlias);
        response.setVerifiedByTrustStore(anchorAlias != null);
    }

    /** 証明書チェーンを DTO に変換する。 */
    public List<CertificateInfo> describeChain(List<X509Certificate> chain, LoadedTrustStore trustStore) {
        List<CertificateInfo> certificates = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            certificates.add(trustStoreInspector.describe(chain.get(i), i, trustStore.keyStore()));
        }
        return certificates;
    }

    /** HTTPS リクエストを送り、ステータス・ヘッダ・ボディの先頭をレスポンスに詰める。 */
    private void sendRequest(TlsCallResponse response, SSLContext sslContext, URI uri,
            String method, String body, String contentType) throws IOException {

        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        try {
            // JVM 既定の SSLContext を明示的に使う（既定動作と同じだが、
            // 「どのトラスト設定で通信したか」を意図として残すため明示する）。
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "*/*");

            if (StringUtils.hasText(body) && !"GET".equals(method) && !"HEAD".equals(method)) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type",
                        StringUtils.hasText(contentType) ? contentType : "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int statusCode = connection.getResponseCode();
            response.setHttpStatus(statusCode);
            response.setResponseContentType(connection.getContentType());
            long declaredLength = connection.getContentLengthLong();
            if (declaredLength >= 0) {
                response.setResponseBodyLength(declaredLength);
            }

            try (InputStream in = statusCode >= 400 ? connection.getErrorStream()
                    : connection.getInputStream()) {
                response.setResponseBodyPreview(readPreview(in));
            }
        } finally {
            connection.disconnect();
        }
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

    /** 失敗時のレスポンスを組み立てる。HTTP は 200 で返し、status で失敗理由を示す。 */
    private static TlsCallResponse fail(TlsCallResponse response, String status, long startedAt,
            Throwable cause, String message, String hint) {
        response.setStatus(status);
        response.setMessage(message);
        response.setHint(hint);
        if (cause != null) {
            response.setExceptionClass(cause.getClass().getName());
        }
        response.setElapsedMs(System.currentTimeMillis() - startedAt);
        return response;
    }
}
