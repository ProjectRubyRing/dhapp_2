package com.example.dhapp.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.example.dhapp.dto.CertificateInfo;
import com.example.dhapp.dto.TrustStoreInfo;

/**
 * JVM のトラストストアと、そこに登録された自己署名証明書（{@code cacert.crt}）を調べる。
 *
 * <p>JBoss EAP の standalone 起動時に以下のパラメータでトラストストアが渡されている前提で、
 * その内容を読み取る。</p>
 *
 * <pre>
 * -Djavax.net.ssl.trustStore=/opt/jboss/certs/truststore.jks
 * -Djavax.net.ssl.trustStorePassword=&lt;password&gt;
 * -Djavax.net.ssl.trustStoreType=JKS
 * </pre>
 *
 * <p><b>パスワードの値は決して外部（レスポンス・ログ）へ出さない。</b>
 * 「起動パラメータで渡されているか」だけを扱う。</p>
 *
 * <p>証明書の同一性判定は Subject DN やエイリアス名ではなく <b>SHA-256 フィンガープリント</b>で行う。
 * DN は重複し得るし、エイリアスは import 時に任意に付けられるため、
 * 「cacert.crt そのものが登録されているか」を確実に判定できるのはフィンガープリントだけ。</p>
 */
@Service
public class TrustStoreInspector {

    private static final Logger log = LoggerFactory.getLogger(TrustStoreInspector.class);

    /** JBoss EAP 起動時に渡されるトラストストアのパス。 */
    public static final String PROP_TRUST_STORE = "javax.net.ssl.trustStore";

    /** 同パスワード。値は外部に出さない。 */
    public static final String PROP_TRUST_STORE_PASSWORD = "javax.net.ssl.trustStorePassword";

    /** 同ストア種別（JKS / PKCS12）。 */
    public static final String PROP_TRUST_STORE_TYPE = "javax.net.ssl.trustStoreType";

    /** {@code javax.net.ssl.trustStore=NONE} は「ファイルを使わない」ことを示す JSSE の特別値。 */
    private static final String NONE = "NONE";

    /** JVM 既定 cacerts のパスワード。 */
    private static final String DEFAULT_CACERTS_PASSWORD = "changeit";

    /** レスポンスに載せるエイリアスの最大件数（cacerts は 100 件以上あるため制限する）。 */
    private static final int MAX_ALIASES = 50;

    /** 証明書の有効期間を表示する書式。 */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** subjectAltName の種別コード（RFC 5280）。 */
    private static final int SAN_DNS_NAME = 2;
    private static final int SAN_IP_ADDRESS = 7;

    /** 照合対象の自己署名証明書ファイル（cacert.crt）。 */
    private final String caCertPath;

    public TrustStoreInspector(@Value("${app.tls.ca-cert-path:}") String caCertPath) {
        this.caCertPath = caCertPath == null ? "" : caCertPath.trim();
    }

    /**
     * ロード済みトラストストアと、その状態。
     *
     * @param info     レスポンス用の状態
     * @param keyStore ロードできた場合の KeyStore。失敗時は null
     */
    public record LoadedTrustStore(TrustStoreInfo info, KeyStore keyStore) {
    }

    public String getCaCertPath() {
        return caCertPath;
    }

    /**
     * {@code javax.net.ssl.trustStore*} システムプロパティを読み、トラストストアをロードする。
     *
     * <p>プロパティが未指定の場合は JSSE と同じ規則で JVM 既定
     * （{@code $JAVA_HOME/lib/security/jssecacerts} → 無ければ {@code cacerts}）を対象にする。
     * どちらを見ているかは {@link TrustStoreInfo#getSource()} で区別できる。</p>
     *
     * <p>ロードに失敗しても例外は投げず、{@code info.loaded=false} と理由を返す
     * （設定確認 API がその状態を NG として報告するため）。</p>
     */
    public LoadedTrustStore load() {
        String configuredPath = System.getProperty(PROP_TRUST_STORE);
        String password = System.getProperty(PROP_TRUST_STORE_PASSWORD);
        String configuredType = System.getProperty(PROP_TRUST_STORE_TYPE);

        TrustStoreInfo info = new TrustStoreInfo();
        info.setPathPropertyProvided(StringUtils.hasText(configuredPath));
        info.setPasswordPropertyProvided(StringUtils.hasText(password));
        info.setType(StringUtils.hasText(configuredType) ? configuredType : KeyStore.getDefaultType());

        if (StringUtils.hasText(configuredPath) && NONE.equalsIgnoreCase(configuredPath.trim())) {
            info.setSource(PROP_TRUST_STORE + "=NONE（ファイルによるトラストストアを使用しない指定）");
            info.setPath(NONE);
            info.setLoadErrorMessage("javax.net.ssl.trustStore=NONE のため、ファイルからは読み込めない。");
            return new LoadedTrustStore(info, null);
        }

        Path file;
        String effectivePassword = password;
        if (StringUtils.hasText(configuredPath)) {
            file = Paths.get(configuredPath.trim());
            info.setSource(PROP_TRUST_STORE + "（JBoss EAP standalone 起動パラメータ）");
        } else {
            file = defaultTrustStorePath();
            info.setSource("JVM 既定（" + PROP_TRUST_STORE + " 未指定）");
            if (!StringUtils.hasText(effectivePassword)) {
                effectivePassword = DEFAULT_CACERTS_PASSWORD;
            }
        }
        return openAndFill(file, effectivePassword, info);
    }

    /**
     * 任意のパス・パスワード・種別でトラストストアを読み込む。
     *
     * <p>{@code javax.net.ssl.trustStore*} システムプロパティ以外で位置が決まるストア
     * ——JBoss EAP(Elytron) の {@code key-store} が指すファイルなど——を、
     * JVM 既定のストアと同じ形（{@link TrustStoreInfo}）で扱うために使う。</p>
     *
     * @param path     トラストストアのパス
     * @param password パスワード（null 可。誤っている場合は整合性チェック無しで読み直す）
     * @param type     ストア種別（null なら中身から自動判別）
     * @param source   どこから位置を決めたかの説明（レスポンスにそのまま載せる）
     */
    public LoadedTrustStore loadFrom(String path, String password, String type, String source) {
        TrustStoreInfo info = new TrustStoreInfo();
        info.setSource(source);
        info.setPathPropertyProvided(StringUtils.hasText(path));
        info.setPasswordPropertyProvided(StringUtils.hasText(password));
        info.setType(StringUtils.hasText(type) ? type : KeyStore.getDefaultType());

        if (!StringUtils.hasText(path)) {
            info.setLoadErrorMessage("トラストストアのパスを特定できない。");
            return new LoadedTrustStore(info, null);
        }
        return openAndFill(Paths.get(path.trim()), password, info);
    }

    /** 実ファイルを開いて {@link TrustStoreInfo} を埋める、load / loadFrom 共通の後半処理。 */
    private LoadedTrustStore openAndFill(Path file, String password, TrustStoreInfo info) {
        info.setPath(file.toAbsolutePath().toString());
        info.setExists(Files.exists(file));
        info.setReadable(Files.isReadable(file));

        if (!info.isExists()) {
            info.setLoadErrorMessage("トラストストアファイルが存在しない: " + info.getPath());
            return new LoadedTrustStore(info, null);
        }
        if (!info.isReadable()) {
            info.setLoadErrorMessage("トラストストアファイルを読み取れない（パーミッションを確認）: " + info.getPath());
            return new LoadedTrustStore(info, null);
        }

        KeyStore keyStore = openKeyStore(file, password, info);
        if (keyStore == null) {
            return new LoadedTrustStore(info, null);
        }

        info.setLoaded(true);
        info.setType(keyStore.getType());
        fillEntries(keyStore, info);
        return new LoadedTrustStore(info, keyStore);
    }

    /**
     * KeyStore を開く。
     *
     * <p>{@code KeyStore.getInstance(File, char[])} はファイルの中身からストア種別を自動判別するため、
     * {@code javax.net.ssl.trustStoreType} の指定漏れ・JKS/PKCS12 の取り違えがあっても読める。
     * パスワードが誤っている（整合性チェックに失敗する）場合は、パスワード無しで再試行する
     * ——トラストストアの参照は公開情報の読み取りであり、一覧表示だけならパスワードは不要なため。</p>
     */
    private KeyStore openKeyStore(Path file, String password, TrustStoreInfo info) {
        try {
            return KeyStore.getInstance(file.toFile(), password == null ? null : password.toCharArray());
        } catch (IOException | KeyStoreException | java.security.NoSuchAlgorithmException
                 | CertificateException e) {
            log.warn("Failed to load trust store with the configured password. path={}, reason={}",
                    file, e.toString());
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(file.toFile(), (char[]) null);
            info.setLoadErrorMessage("指定されたパスワードでは整合性チェックに失敗したため、"
                    + "パスワード無し（整合性チェック無し）で内容のみ読み取った。"
                    + PROP_TRUST_STORE_PASSWORD + " の値を確認すること。");
            return keyStore;
        } catch (IOException | KeyStoreException | java.security.NoSuchAlgorithmException
                 | CertificateException e) {
            info.setLoadErrorMessage("トラストストアをロードできない: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            log.error("Failed to load trust store. path={}", file, e);
            return null;
        }
    }

    /** エントリ数・エイリアス一覧を詰める。 */
    private void fillEntries(KeyStore keyStore, TrustStoreInfo info) {
        List<String> aliases = new ArrayList<>();
        int total = 0;
        int certEntries = 0;
        try {
            Enumeration<String> e = keyStore.aliases();
            while (e.hasMoreElements()) {
                String alias = e.nextElement();
                total++;
                if (keyStore.isCertificateEntry(alias)) {
                    certEntries++;
                }
                if (aliases.size() < MAX_ALIASES) {
                    aliases.add(alias);
                }
            }
        } catch (KeyStoreException e) {
            info.setLoadErrorMessage("エイリアスを列挙できない: " + e.getMessage());
        }
        info.setEntryCount(total);
        info.setCertificateEntryCount(certEntries);
        info.setAliases(aliases);
        info.setAliasesComplete(aliases.size() == total);
    }

    /** JSSE と同じ規則で JVM 既定のトラストストアを決める。 */
    private static Path defaultTrustStorePath() {
        Path securityDir = Paths.get(System.getProperty("java.home", "")).resolve("lib").resolve("security");
        Path jssecacerts = securityDir.resolve("jssecacerts");
        return Files.exists(jssecacerts) ? jssecacerts : securityDir.resolve("cacerts");
    }

    /**
     * {@code app.tls.ca-cert-path} が指す自己署名証明書（cacert.crt）を読み込む。
     * PEM / DER のどちらでも読める。
     *
     * @return 未設定・読み込み失敗の場合は null（理由は {@code errorHolder} に入る）
     */
    public X509Certificate loadCaCertificate(StringBuilder errorHolder) {
        if (!StringUtils.hasText(caCertPath)) {
            errorHolder.append("app.tls.ca-cert-path（環境変数 TLS_CA_CERT_PATH）が未設定のため、"
                    + "cacert.crt との照合をスキップした。");
            return null;
        }
        Path file = Paths.get(caCertPath);
        if (!Files.isReadable(file)) {
            errorHolder.append("cacert.crt を読み取れない: ").append(file.toAbsolutePath());
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Certificate certificate = factory.generateCertificate(in);
            if (certificate instanceof X509Certificate x509) {
                return x509;
            }
            errorHolder.append("X.509 証明書ではない: ").append(file.toAbsolutePath());
            return null;
        } catch (IOException | CertificateException e) {
            errorHolder.append("cacert.crt の読み込みに失敗: ").append(e.getMessage());
            log.warn("Failed to read CA certificate. path={}", file, e);
            return null;
        }
    }

    /**
     * トラストストアから、指定証明書と同一（SHA-256 フィンガープリントが一致）のエントリを探す。
     *
     * @return 一致したエイリアス。見つからなければ null
     */
    public String findAlias(KeyStore keyStore, X509Certificate target) {
        if (keyStore == null || target == null) {
            return null;
        }
        String targetFingerprint = sha256Fingerprint(target);
        if (targetFingerprint == null) {
            return null;
        }
        for (Map.Entry<String, X509Certificate> entry : certificatesByAlias(keyStore).entrySet()) {
            if (targetFingerprint.equals(sha256Fingerprint(entry.getValue()))) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * サーバから提示された証明書チェーンのトラストアンカーを、トラストストアの中から探す。
     *
     * <ol>
     *   <li>チェーン内の証明書そのものがトラストストアに入っているか（自己署名サーバ証明書や、
     *       サーバがルート CA まで送ってくる構成で一致する）</li>
     *   <li>入っていなければ、チェーン末端の発行者（Issuer DN）と Subject DN が一致する
     *       トラストストアのエントリ（サーバがルート CA を送らない一般的な構成）</li>
     * </ol>
     *
     * @return 一致したエイリアス。見つからなければ null
     */
    public String findTrustAnchorAlias(KeyStore keyStore, List<X509Certificate> chain) {
        if (keyStore == null || chain == null || chain.isEmpty()) {
            return null;
        }
        Map<String, X509Certificate> trusted = certificatesByAlias(keyStore);

        for (X509Certificate cert : chain) {
            String fingerprint = sha256Fingerprint(cert);
            for (Map.Entry<String, X509Certificate> entry : trusted.entrySet()) {
                if (fingerprint != null && fingerprint.equals(sha256Fingerprint(entry.getValue()))) {
                    return entry.getKey();
                }
            }
        }

        X509Certificate last = chain.get(chain.size() - 1);
        for (Map.Entry<String, X509Certificate> entry : trusted.entrySet()) {
            if (last.getIssuerX500Principal().equals(entry.getValue().getSubjectX500Principal())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 既定アルゴリズムの {@link TrustManagerFactory} をこのトラストストアで初期化し、
     * 指定証明書が「信頼されたイシュア」として採用されるかを確認する。
     *
     * <p>トラストストアにエントリが在ることと、JSSE がそれをトラストアンカーとして
     * 採用することは別問題（鍵用途の不備・破損エントリなどで落ちる）なので、両方を確認する。</p>
     */
    public boolean isAcceptedIssuer(KeyStore keyStore, X509Certificate target) {
        if (keyStore == null || target == null) {
            return false;
        }
        String targetFingerprint = sha256Fingerprint(target);
        if (targetFingerprint == null) {
            return false;
        }
        try {
            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore);
            for (TrustManager trustManager : factory.getTrustManagers()) {
                if (trustManager instanceof X509TrustManager x509) {
                    for (X509Certificate issuer : x509.getAcceptedIssuers()) {
                        if (targetFingerprint.equals(sha256Fingerprint(issuer))) {
                            return true;
                        }
                    }
                }
            }
        } catch (java.security.NoSuchAlgorithmException | KeyStoreException e) {
            log.warn("Failed to initialize TrustManagerFactory with the trust store. reason={}", e.toString());
        }
        return false;
    }

    /**
     * エイリアスがトラストストアに存在するか。
     *
     * <p>{@link TrustStoreInfo#getAliases()} は表示用に件数を打ち切っているため、
     * 存在確認にはこちらを使う（cacerts のように 100 件超のストアで取りこぼさないため）。</p>
     */
    public boolean containsAlias(KeyStore keyStore, String alias) {
        if (keyStore == null || !StringUtils.hasText(alias)) {
            return false;
        }
        try {
            return keyStore.containsAlias(alias);
        } catch (KeyStoreException e) {
            log.warn("Failed to check the trust store alias. alias={}, reason={}", alias, e.toString());
            return false;
        }
    }

    /** トラストストア内の証明書をエイリアスごとに取り出す。 */
    public Map<String, X509Certificate> certificatesByAlias(KeyStore keyStore) {
        Map<String, X509Certificate> result = new LinkedHashMap<>();
        if (keyStore == null) {
            return result;
        }
        try {
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Certificate certificate = keyStore.getCertificate(alias);
                if (certificate instanceof X509Certificate x509) {
                    result.put(alias, x509);
                }
            }
        } catch (KeyStoreException e) {
            log.warn("Failed to enumerate trust store certificates. reason={}", e.toString());
        }
        return result;
    }

    /**
     * 証明書をレスポンス用の DTO に変換する。
     *
     * @param position  チェーン内の位置。単体の証明書なら null
     * @param keyStore  トラストストア（null 可）。渡すと登録有無とエイリアスを埋める
     */
    public CertificateInfo describe(X509Certificate certificate, Integer position, KeyStore keyStore) {
        CertificateInfo info = new CertificateInfo();
        info.setPosition(position);
        info.setSubjectDn(certificate.getSubjectX500Principal().getName());
        info.setIssuerDn(certificate.getIssuerX500Principal().getName());
        info.setSerialNumber(certificate.getSerialNumber().toString(16));
        info.setSignatureAlgorithm(certificate.getSigAlgName());
        info.setNotBefore(format(certificate.getNotBefore()));
        info.setNotAfter(format(certificate.getNotAfter()));
        info.setSelfSigned(certificate.getSubjectX500Principal()
                .equals(certificate.getIssuerX500Principal()));
        info.setSha256Fingerprint(sha256Fingerprint(certificate));
        info.setSubjectAlternativeNames(subjectAltNames(certificate));

        Date now = new Date();
        info.setExpired(now.before(certificate.getNotBefore()) || now.after(certificate.getNotAfter()));

        if (keyStore != null) {
            String alias = findAlias(keyStore, certificate);
            info.setInTrustStore(alias != null);
            info.setTrustStoreAlias(alias);
        }
        return info;
    }

    /**
     * SHA-256 フィンガープリントを {@code AA:BB:CC:...} 形式で返す。
     * {@code keytool -list -v} や {@code openssl x509 -fingerprint -sha256} の表示と同じ形式。
     */
    public static String sha256Fingerprint(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder sb = new StringBuilder(digest.length * 3);
            for (byte b : digest) {
                if (sb.length() > 0) {
                    sb.append(':');
                }
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException | CertificateEncodingException e) {
            log.warn("Failed to compute SHA-256 fingerprint. reason={}", e.toString());
            return null;
        }
    }

    /** subjectAltName のうち dNSName / iPAddress を文字列化する。 */
    private static List<String> subjectAltNames(X509Certificate certificate) {
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names == null) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            for (List<?> name : names) {
                if (name.size() < 2 || !(name.get(0) instanceof Integer type)) {
                    continue;
                }
                String value = String.valueOf(name.get(1));
                if (type == SAN_DNS_NAME) {
                    result.add("DNS:" + value);
                } else if (type == SAN_IP_ADDRESS) {
                    result.add("IP:" + value);
                }
            }
            return result;
        } catch (java.security.cert.CertificateParsingException e) {
            return Collections.emptyList();
        }
    }

    private static String format(Date date) {
        return OffsetDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).format(DATE_FORMAT);
    }
}
