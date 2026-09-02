package com.example.dhapp.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.example.dhapp.dto.ConfigComparison;
import com.example.dhapp.dto.ConfigSourceResult;
import com.example.dhapp.dto.DateConfigResponse;
import com.example.dhapp.dto.DeploymentOverlayEntry;
import com.example.dhapp.dto.DeploymentOverlayInfo;

/**
 * {@code date_config.properties} を<b>ファイル読み</b>と<b>リソース読み</b>の 2 経路で読み込み、
 * 結果をログ・コンソールへ出力したうえで両者を比較する。
 *
 * <h2>2 つの読み込み経路</h2>
 * <table border="1">
 *   <caption>読み込み経路</caption>
 *   <tr><th>経路</th><th>対象</th><th>API</th></tr>
 *   <tr>
 *     <td>ファイル読み</td>
 *     <td>{@code /webapp/webapp9mf02/serverlets/jp/iwin/base/tango/date_config.properties}
 *         （war の外・AP サーバのファイルシステム上）</td>
 *     <td>{@link Files#readAllBytes(Path)}</td>
 *   </tr>
 *   <tr>
 *     <td>リソース読み</td>
 *     <td>クラスパス配下の {@code jp/iwin/base/tango/date_config.properties}
 *         （war にアーカイブ済み → {@code WEB-INF/classes/} 配下）</td>
 *     <td>{@link ClassLoader#getResource(String)}</td>
 *   </tr>
 * </table>
 *
 * <h2>deployment-overlay の検知</h2>
 * JBoss EAP の deployment-overlay は war を作り直さずに中のファイルを差し替えるため、
 * <b>リソース読み側だけ</b>が変化する。反映は次の 2 つで検知する。
 * <ol>
 *   <li>管理モデル（{@link DeploymentOverlayInspector}）に overlay 定義があるか</li>
 *   <li>リソースの解決先 URL・物理パス・SHA-256 が<b>前回の呼び出しから変化</b>したか</li>
 * </ol>
 * 2 のために、直近の読み取り結果の指紋を JVM 内に保持する（{@link Snapshot}）。
 * overlay 適用 → {@code --redeploy-affected} の後にもう一度この API を呼べば、
 * {@code resourceContentChanged=true} として反映を確認できる。
 *
 * <p>どの経路も失敗しても例外は投げず、結果オブジェクトに理由を残す
 * （「読めなかったこと」自体が確認したい結果であるため）。</p>
 */
@Service
public class DateConfigService {

    private static final Logger log = LoggerFactory.getLogger(DateConfigService.class);

    /** 読み込み経路の識別子。 */
    public static final String READ_TYPE_FILE = "FILE";
    public static final String READ_TYPE_RESOURCE = "RESOURCE";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";

    public static final String VERDICT_IDENTICAL = "IDENTICAL";
    public static final String VERDICT_SAME_PROPERTIES = "SAME_PROPERTIES";
    public static final String VERDICT_DIFFERENT = "DIFFERENT";
    public static final String VERDICT_FILE_ONLY = "FILE_ONLY";
    public static final String VERDICT_RESOURCE_ONLY = "RESOURCE_ONLY";
    public static final String VERDICT_BOTH_UNAVAILABLE = "BOTH_UNAVAILABLE";

    /** 生テキストをレスポンス／ログに載せる最大文字数。 */
    private static final int RAW_TEXT_MAX_CHARS = 4000;

    /** クラスパス上の同名リソースを何件まで列挙するか。 */
    private static final int MAX_DUPLICATE_RESOURCES = 10;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final String filePath;
    private final String resourceName;
    private final String charsetName;
    private final boolean includeRawText;
    private final DeploymentOverlayInspector deploymentOverlayInspector;

    /** 直近の読み取り結果の指紋（overlay 反映の検知に使う）。 */
    private final AtomicReference<Snapshot> lastSnapshot = new AtomicReference<>();

    public DateConfigService(
            @Value("${app.config.date-config.file-path:"
                    + "/webapp/webapp9mf02/serverlets/jp/iwin/base/tango/date_config.properties}")
            String filePath,
            @Value("${app.config.date-config.resource-name:jp/iwin/base/tango/date_config.properties}")
            String resourceName,
            @Value("${app.config.date-config.charset:UTF-8}") String charsetName,
            @Value("${app.config.date-config.include-raw-text:true}") boolean includeRawText,
            DeploymentOverlayInspector deploymentOverlayInspector) {
        this.filePath = filePath;
        this.resourceName = normalizeResourceName(resourceName);
        this.charsetName = charsetName;
        this.includeRawText = includeRawText;
        this.deploymentOverlayInspector = deploymentOverlayInspector;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getResourceName() {
        return resourceName;
    }

    /** overlay の {@code --content=} 指定に対応する、デプロイ内のパス。 */
    public String getResourcePathInDeployment() {
        return "WEB-INF/classes/" + resourceName;
    }

    /** 直近の読み取り結果の指紋。 */
    private record Snapshot(String observedAt, String fileSha256, String resourceSha256,
                            String resourceUrl, String resourceFilePath) {
    }

    /**
     * ファイル読み・リソース読みを実行し、ログ・コンソールへ出力してから結果を返す。
     *
     * @param requestId 呼び出しの識別子（ログ追跡用）
     */
    public DateConfigResponse read(String requestId) {
        long startedAt = System.currentTimeMillis();

        DateConfigResponse response = new DateConfigResponse();
        response.setRequestId(requestId);
        response.setTimestamp(OffsetDateTime.now().format(TIMESTAMP));

        // --- 1. ファイル読み（war の外のファイルを直接読む） ---
        ConfigSourceResult fileRead = readFromFile();
        response.setFileRead(fileRead);

        // --- 2. リソース読み（war にアーカイブされたクラスパス上のリソースを読む） ---
        ConfigSourceResult resourceRead = readFromClasspath();
        response.setResourceRead(resourceRead);

        // --- 3. 2 経路の比較 ---
        ConfigComparison comparison = compare(fileRead, resourceRead);
        response.setComparison(comparison);

        // --- 4. deployment-overlay の検知（管理モデル + 前回との差分） ---
        DeploymentOverlayInfo overlay =
                deploymentOverlayInspector.inspect(getResourcePathInDeployment());
        applyChangeDetection(overlay, fileRead, resourceRead);
        response.setDeploymentOverlay(overlay);

        response.setStatus(resolveStatus(fileRead, resourceRead));
        response.setElapsedMs(System.currentTimeMillis() - startedAt);

        // --- 5. ログとコンソールへ出力 ---
        String report = buildReport(requestId, response);
        response.setReport(report);
        emit(report);

        return response;
    }

    // ------------------------------------------------------------------------
    // 1. ファイル読み
    // ------------------------------------------------------------------------

    /**
     * {@code app.config.date-config.file-path}（既定
     * {@code /webapp/webapp9mf02/serverlets/jp/iwin/base/tango/date_config.properties}）を
     * ファイルとして読む。
     */
    public ConfigSourceResult readFromFile() {
        long startedAt = System.currentTimeMillis();

        ConfigSourceResult result = new ConfigSourceResult();
        result.setReadType(READ_TYPE_FILE);
        result.setLocation(filePath);

        try {
            Path path = Paths.get(filePath);
            result.setResolvedFilePath(path.toAbsolutePath().toString());
            result.setExists(Files.exists(path));
            result.setReadable(Files.isReadable(path));

            if (!result.isExists()) {
                result.setErrorMessage("ファイルが存在しない: " + result.getResolvedFilePath());
                result.setHint("AP サーバ上に "
                        + filePath + " を配置するか、環境変数 DATE_CONFIG_FILE_PATH "
                        + "（app.config.date-config.file-path）で実際の配置先を指定する。");
                return finish(result, startedAt);
            }
            if (!result.isReadable()) {
                result.setErrorMessage("ファイルを読み取れない（パーミッション）: "
                        + result.getResolvedFilePath());
                result.setHint("JBoss EAP の実行ユーザー（jboss）に読み取り権限があるか確認する。");
                return finish(result, startedAt);
            }

            byte[] bytes = Files.readAllBytes(path);
            result.setSizeBytes((long) bytes.length);
            result.setLastModified(formatInstant(Files.getLastModifiedTime(path).toInstant()));
            fillContent(result, bytes);
        } catch (IOException | RuntimeException e) {
            result.setErrorMessage("ファイル読みに失敗した: " + e.getMessage());
            result.setExceptionClass(e.getClass().getName());
            log.error("File read failed. path={}", filePath, e);
        }
        return finish(result, startedAt);
    }

    // ------------------------------------------------------------------------
    // 2. リソース読み
    // ------------------------------------------------------------------------

    /**
     * クラスパス配下の {@code jp/iwin/base/tango/date_config.properties}（war 同梱）を読む。
     *
     * <p>解決先 URL と、そこから取り出せる物理パスも記録する。deployment-overlay で
     * 差し替えられると、WildFly の VFS 上では同じ URL のまま実体（物理ファイル）が
     * 差し替え元へ切り替わるため、URL だけでなく内容の SHA-256 でも判定できるようにする。</p>
     */
    public ConfigSourceResult readFromClasspath() {
        long startedAt = System.currentTimeMillis();

        ConfigSourceResult result = new ConfigSourceResult();
        result.setReadType(READ_TYPE_RESOURCE);
        result.setLocation("classpath:" + resourceName);

        ClassLoader classLoader = resolveClassLoader();
        result.setClassLoader(String.valueOf(classLoader));

        try {
            URL url = classLoader.getResource(resourceName);
            if (url == null) {
                result.setErrorMessage("クラスパス上にリソースが見つからない: " + resourceName);
                result.setHint("war のアーカイブ対象に src/main/resources/" + resourceName
                        + " が含まれているか（WEB-INF/classes/" + resourceName
                        + " として展開されているか）を確認する。");
                return finish(result, startedAt);
            }
            result.setExists(true);
            result.setResolvedUrl(url.toString());
            result.setResolvedFilePath(physicalPathOf(url));
            result.setNotes(duplicateResources(classLoader, url));

            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            long lastModified = connection.getLastModified();
            if (lastModified > 0) {
                result.setLastModified(formatInstant(Instant.ofEpochMilli(lastModified)));
            }
            byte[] bytes;
            try (InputStream in = connection.getInputStream()) {
                bytes = in.readAllBytes();
            }
            result.setReadable(true);
            result.setSizeBytes((long) bytes.length);
            fillContent(result, bytes);
        } catch (IOException | RuntimeException e) {
            result.setErrorMessage("リソース読みに失敗した: " + e.getMessage());
            result.setExceptionClass(e.getClass().getName());
            log.error("Resource read failed. resource={}", resourceName, e);
        }
        return finish(result, startedAt);
    }

    /**
     * war のクラスローダを取得する。WildFly では TCCL がデプロイメントの
     * {@code ModuleClassLoader} になっているため、まずそちらを使う。
     */
    private ClassLoader resolveClassLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : getClass().getClassLoader();
    }

    /**
     * クラスパス上に同名リソースが複数ある場合に一覧を返す（先勝ちの事故を検知するため）。
     * 1 件だけなら null を返す。
     */
    private List<String> duplicateResources(ClassLoader classLoader, URL selected) {
        try {
            Enumeration<URL> found = classLoader.getResources(resourceName);
            Set<String> urls = new LinkedHashSet<>();
            while (found.hasMoreElements() && urls.size() < MAX_DUPLICATE_RESOURCES) {
                urls.add(found.nextElement().toString());
            }
            if (urls.size() <= 1) {
                return null;
            }
            List<String> notes = new ArrayList<>();
            notes.add("クラスパス上に同名リソースが " + urls.size() + " 件ある。"
                    + "実際に読まれるのは先頭の " + selected + "。");
            notes.addAll(urls);
            return notes;
        } catch (IOException e) {
            log.debug("Failed to enumerate duplicate resources. resource={}, reason={}",
                    resourceName, e.toString());
            return null;
        }
    }

    /**
     * リソース URL から物理ファイルパスを取り出す。
     *
     * <p>{@code file:} ならそのまま。WildFly の {@code vfs:} URL の場合は、
     * JBoss VFS の {@code VirtualFile#getPhysicalFile()} をリフレクションで呼ぶ
     * （war 側に org.jboss.vfs への依存を持たせないため）。取り出せない場合は null。</p>
     */
    private String physicalPathOf(URL url) {
        try {
            if ("file".equalsIgnoreCase(url.getProtocol())) {
                return Paths.get(url.toURI()).toAbsolutePath().toString();
            }
            if ("vfs".equalsIgnoreCase(url.getProtocol())) {
                // WildFly の VFS URL。getContent() は org.jboss.vfs.VirtualFile を返すので、
                // war 側に org.jboss.vfs への依存を持たせずリフレクションで実体を取り出す。
                Object content = url.openConnection().getContent();
                if (content != null && content.getClass().getName().endsWith("VirtualFile")) {
                    Object physical = content.getClass().getMethod("getPhysicalFile").invoke(content);
                    return String.valueOf(physical);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to resolve the physical path of {}. reason={}", url, e.toString());
        }
        return null;
    }

    // ------------------------------------------------------------------------
    // 読み取ったバイト列の解釈
    // ------------------------------------------------------------------------

    /** バイト列を復号し、SHA-256・生テキスト・プロパティを詰める。 */
    private void fillContent(ConfigSourceResult result, byte[] bytes) {
        result.setSha256(sha256(bytes));

        Charset charset = resolveCharset();
        result.setCharset(charset.name());

        String text;
        try {
            // まず厳密に復号して、文字化け（文字セット不一致）を検知する。
            CharsetDecoder strict = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer decoded = strict.decode(ByteBuffer.wrap(bytes));
            text = decoded.toString();
        } catch (CharacterCodingException e) {
            // 復号できない byte 列があっても読み取り自体は続ける（値の一部が化けるだけ）。
            result.setCharsetMismatch(true);
            text = new String(bytes, charset);
            log.warn("The content could not be decoded with {}. readType={}, location={}",
                    charset.name(), result.getReadType(), result.getLocation());
        }

        if (includeRawText) {
            if (text.length() > RAW_TEXT_MAX_CHARS) {
                result.setRawTextPreview(text.substring(0, RAW_TEXT_MAX_CHARS));
                result.setRawTextTruncated(true);
            } else {
                result.setRawTextPreview(text);
            }
        }

        Map<String, String> properties = loadProperties(text, result);
        result.setProperties(properties);
        result.setPropertyCount(properties.size());
        result.setLoaded(result.getErrorMessage() == null);
    }

    /**
     * properties 形式として解釈する。
     *
     * <p>{@link Properties} はキーの順序を保持しないため、{@code put} をフックして
     * 定義順の {@link LinkedHashMap} にも溜める。{@link Properties#load(java.io.Reader)}
     * を使うので、{@code \\uXXXX} エスケープも従来どおり解釈される。</p>
     */
    private Map<String, String> loadProperties(String text, ConfigSourceResult result) {
        Map<String, String> ordered = new LinkedHashMap<>();
        Properties properties = new Properties() {
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Object put(Object key, Object value) {
                ordered.put(String.valueOf(key), String.valueOf(value));
                return super.put(key, value);
            }
        };
        try (StringReader reader = new StringReader(text)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            result.setErrorMessage("properties として解釈できない: " + e.getMessage());
            result.setExceptionClass(e.getClass().getName());
            log.warn("Failed to parse properties. readType={}, location={}",
                    result.getReadType(), result.getLocation(), e);
        }
        return ordered;
    }

    private Charset resolveCharset() {
        try {
            return Charset.forName(charsetName);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            log.warn("Unknown charset '{}' was configured. Falling back to UTF-8.", charsetName);
            return StandardCharsets.UTF_8;
        }
    }

    // ------------------------------------------------------------------------
    // 3. 比較
    // ------------------------------------------------------------------------

    /** ファイル読みとリソース読みの結果を突き合わせる。 */
    public ConfigComparison compare(ConfigSourceResult fileRead, ConfigSourceResult resourceRead) {
        ConfigComparison comparison = new ConfigComparison();
        boolean fileOk = fileRead.isLoaded();
        boolean resourceOk = resourceRead.isLoaded();
        comparison.setFileReadSucceeded(fileOk);
        comparison.setResourceReadSucceeded(resourceOk);

        if (!fileOk || !resourceOk) {
            if (!fileOk && !resourceOk) {
                comparison.setVerdict(VERDICT_BOTH_UNAVAILABLE);
                comparison.setSummary("ファイル読み・リソース読みのどちらも読み込めなかったため比較できない。");
            } else if (fileOk) {
                comparison.setVerdict(VERDICT_FILE_ONLY);
                comparison.setSummary("ファイル読みだけ成功した（リソース読みは失敗）。"
                        + "war に " + resourceName + " が同梱されているかを確認する。");
            } else {
                comparison.setVerdict(VERDICT_RESOURCE_ONLY);
                comparison.setSummary("リソース読みだけ成功した（ファイル読みは失敗）。"
                        + filePath + " の配置と権限を確認する。");
            }
            return comparison;
        }

        Map<String, String> fileProperties = fileRead.getProperties();
        Map<String, String> resourceProperties = resourceRead.getProperties();

        List<String> onlyInFile = new ArrayList<>();
        List<String> onlyInResource = new ArrayList<>();
        Map<String, String> different = new LinkedHashMap<>();
        int common = 0;

        for (Map.Entry<String, String> entry : fileProperties.entrySet()) {
            String key = entry.getKey();
            if (!resourceProperties.containsKey(key)) {
                onlyInFile.add(key);
                continue;
            }
            common++;
            String resourceValue = resourceProperties.get(key);
            if (!Objects.equals(entry.getValue(), resourceValue)) {
                different.put(key, "file=" + entry.getValue() + " / resource=" + resourceValue);
            }
        }
        for (String key : resourceProperties.keySet()) {
            if (!fileProperties.containsKey(key)) {
                onlyInResource.add(key);
            }
        }

        comparison.setKeysOnlyInFile(onlyInFile);
        comparison.setKeysOnlyInResource(onlyInResource);
        comparison.setDifferentValues(different);
        comparison.setFileOnlyKeyCount(onlyInFile.size());
        comparison.setResourceOnlyKeyCount(onlyInResource.size());
        comparison.setDifferentValueCount(different.size());
        comparison.setCommonKeyCount(common);
        comparison.setKeySetMatch(onlyInFile.isEmpty() && onlyInResource.isEmpty());
        comparison.setValuesMatch(different.isEmpty());
        comparison.setSha256Match(Objects.equals(fileRead.getSha256(), resourceRead.getSha256()));

        if (comparison.isSha256Match()) {
            comparison.setVerdict(VERDICT_IDENTICAL);
            comparison.setSummary("ファイル読みとリソース読みは同一内容（SHA-256 が一致）。");
        } else if (comparison.isKeySetMatch() && comparison.isValuesMatch()) {
            comparison.setVerdict(VERDICT_SAME_PROPERTIES);
            comparison.setSummary("プロパティは全て一致するが、バイト列は異なる"
                    + "（コメント・並び順・改行コードの差）。");
        } else {
            comparison.setVerdict(VERDICT_DIFFERENT);
            comparison.setSummary("プロパティに差分がある。"
                    + "ファイルのみのキー=" + onlyInFile.size()
                    + ", リソースのみのキー=" + onlyInResource.size()
                    + ", 値が異なるキー=" + different.size() + "。");
        }
        return comparison;
    }

    // ------------------------------------------------------------------------
    // 4. deployment-overlay の反映検知
    // ------------------------------------------------------------------------

    /**
     * 前回の呼び出し時の指紋と比較して、内容が差し替わったかを判定する。
     * 判定後、今回の指紋を保存する。
     */
    private void applyChangeDetection(DeploymentOverlayInfo overlay,
            ConfigSourceResult fileRead, ConfigSourceResult resourceRead) {

        Snapshot current = new Snapshot(OffsetDateTime.now().format(TIMESTAMP),
                fileRead.getSha256(), resourceRead.getSha256(),
                resourceRead.getResolvedUrl(), resourceRead.getResolvedFilePath());
        Snapshot previous = lastSnapshot.getAndSet(current);

        overlay.setPreviousSnapshotAvailable(previous != null);
        if (previous != null) {
            overlay.setPreviousObservedAt(previous.observedAt());
            overlay.setPreviousFileSha256(previous.fileSha256());
            overlay.setPreviousResourceSha256(previous.resourceSha256());
            overlay.setFileContentChanged(!Objects.equals(previous.fileSha256(), current.fileSha256()));
            overlay.setResourceContentChanged(
                    !Objects.equals(previous.resourceSha256(), current.resourceSha256()));
            overlay.setResourceUrlChanged(
                    !Objects.equals(previous.resourceUrl(), current.resourceUrl())
                            || !Objects.equals(previous.resourceFilePath(), current.resourceFilePath()));
        }
        overlay.setDetectionSummary(buildDetectionSummary(overlay));
    }

    /** overlay 検知の結論を日本語で組み立てる。 */
    private String buildDetectionSummary(DeploymentOverlayInfo overlay) {
        StringBuilder sb = new StringBuilder();
        if (overlay.isManagementModelAvailable()) {
            if (overlay.isDateConfigOverlayDefined()) {
                sb.append("管理モデル上、").append(getResourcePathInDeployment())
                        .append(" を差し替える deployment-overlay が定義されている（")
                        .append(String.join(", ", overlay.getDateConfigOverlayNames())).append("）。");
            } else if (overlay.getOverlayNames() != null && !overlay.getOverlayNames().isEmpty()) {
                sb.append("deployment-overlay は定義されているが、")
                        .append(getResourcePathInDeployment())
                        .append(" を差し替えるものは無い。");
            } else {
                sb.append("deployment-overlay は 1 件も定義されていない。");
            }
            if (overlay.isOverlayAppliedToThisDeployment()) {
                sb.append(" このデプロイメント（").append(overlay.getDeploymentName())
                        .append("）にリンクされた overlay がある。");
            }
        } else {
            sb.append("管理モデルを読めないため overlay 定義は確認できない（")
                    .append(overlay.getUnavailableReason()).append("）。");
        }

        if (!overlay.isPreviousSnapshotAvailable()) {
            sb.append(" 内容の指紋を記録した（初回）。"
                    + "overlay 適用後にもう一度呼び出すと、差し替えの有無を差分として検知できる。");
        } else if (overlay.isResourceContentChanged()) {
            sb.append(" ★前回の呼び出しからリソース読みの内容が変化している"
                    + "（deployment-overlay または再デプロイが反映されたと判断できる）。");
        } else if (overlay.isResourceUrlChanged()) {
            sb.append(" リソースの解決先が変化しているが、内容（SHA-256）は同じ。");
        } else {
            sb.append(" 前回の呼び出しからリソース読みの内容は変化していない。");
        }
        if (overlay.isFileContentChanged()) {
            sb.append(" ファイル読み側の内容も前回から変化している"
                    + "（overlay ではなくファイル自体の更新）。");
        }
        return sb.toString();
    }

    private static String resolveStatus(ConfigSourceResult fileRead, ConfigSourceResult resourceRead) {
        if (fileRead.isLoaded() && resourceRead.isLoaded()) {
            return STATUS_SUCCESS;
        }
        if (fileRead.isLoaded() || resourceRead.isLoaded()) {
            return STATUS_PARTIAL;
        }
        return STATUS_FAILED;
    }

    // ------------------------------------------------------------------------
    // 5. ログ・コンソール出力
    // ------------------------------------------------------------------------

    /**
     * レポートをログとコンソールの両方へ出力する。
     *
     * <p>logback の設定上、{@code com.example.dhapp} のログは各ログファイルと
     * コンソール（root の CONSOLE アペンダ）へ出るが、この API は
     * 「ファイル読み・リソース読みの結果をコンソールに出す」ことが要件のため、
     * ログ基盤の設定に依存せず標準出力へも直接書き出す（{@link ConsoleWriter}）。</p>
     */
    private void emit(String report) {
        // ログ（application.log / server.log ほか）へ 1 イベントとして出す。
        log.info("{}", report);
        // コンソール（標準出力）へ直接出す。UTF-8 固定（ConsoleWriter）。
        ConsoleWriter.println(report);
    }

    /** ログ・コンソール・レスポンスで共用するテキストレポートを組み立てる。 */
    public String buildReport(String requestId, DateConfigResponse response) {
        StringBuilder sb = new StringBuilder(2048);
        String line = "================================================================================";
        sb.append(System.lineSeparator());
        sb.append(line).append(System.lineSeparator());
        sb.append("date_config.properties 読み込み結果（ファイル読み vs リソース読み）")
                .append(System.lineSeparator());
        sb.append("requestId=").append(requestId)
                .append(", timestamp=").append(response.getTimestamp())
                .append(", status=").append(response.getStatus())
                .append(", elapsedMs=").append(response.getElapsedMs())
                .append(System.lineSeparator());
        sb.append(line).append(System.lineSeparator());

        appendSource(sb, "[1] ファイル読み（war の外のファイルを直接読む）", response.getFileRead());
        appendSource(sb, "[2] リソース読み（war 同梱・クラスパス配下）", response.getResourceRead());
        appendComparison(sb, response.getComparison());
        appendOverlay(sb, response.getDeploymentOverlay());

        sb.append(line);
        return sb.toString();
    }

    private void appendSource(StringBuilder sb, String title, ConfigSourceResult result) {
        String nl = System.lineSeparator();
        sb.append(nl).append(title).append(nl);
        sb.append("  readType        : ").append(result.getReadType()).append(nl);
        sb.append("  location        : ").append(result.getLocation()).append(nl);
        if (result.getResolvedUrl() != null) {
            sb.append("  resolvedUrl     : ").append(result.getResolvedUrl()).append(nl);
        }
        if (result.getResolvedFilePath() != null) {
            sb.append("  resolvedPath    : ").append(result.getResolvedFilePath()).append(nl);
        }
        if (result.getClassLoader() != null) {
            sb.append("  classLoader     : ").append(result.getClassLoader()).append(nl);
        }
        sb.append("  exists/readable : ").append(result.isExists()).append(" / ")
                .append(result.isReadable()).append(nl);
        sb.append("  loaded          : ").append(result.isLoaded()).append(nl);
        sb.append("  sizeBytes       : ").append(result.getSizeBytes()).append(nl);
        sb.append("  lastModified    : ").append(result.getLastModified()).append(nl);
        sb.append("  sha256          : ").append(result.getSha256()).append(nl);
        sb.append("  charset         : ").append(result.getCharset())
                .append(result.isCharsetMismatch() ? "（★復号できない byte 列があった）" : "").append(nl);
        sb.append("  elapsedMs       : ").append(result.getElapsedMs()).append(nl);
        if (result.getErrorMessage() != null) {
            sb.append("  error           : ").append(result.getErrorMessage()).append(nl);
        }
        if (result.getExceptionClass() != null) {
            sb.append("  exceptionClass  : ").append(result.getExceptionClass()).append(nl);
        }
        if (result.getHint() != null) {
            sb.append("  hint            : ").append(result.getHint()).append(nl);
        }
        if (result.getNotes() != null) {
            for (String note : result.getNotes()) {
                sb.append("  note            : ").append(note).append(nl);
            }
        }
        sb.append("  properties      : ").append(result.getPropertyCount()).append(" 件").append(nl);
        if (result.getProperties() != null) {
            for (Map.Entry<String, String> entry : result.getProperties().entrySet()) {
                sb.append("      ").append(entry.getKey()).append(" = ")
                        .append(entry.getValue()).append(nl);
            }
        }
        if (result.getRawTextPreview() != null) {
            sb.append("  --- 生テキスト").append(result.isRawTextTruncated() ? "（先頭のみ）" : "")
                    .append(" ---").append(nl);
            for (String textLine : result.getRawTextPreview().split("\\R", -1)) {
                sb.append("      ").append(textLine).append(nl);
            }
        }
    }

    private void appendComparison(StringBuilder sb, ConfigComparison comparison) {
        String nl = System.lineSeparator();
        sb.append(nl).append("[3] 比較結果（ファイル読み vs リソース読み）").append(nl);
        sb.append("  verdict         : ").append(comparison.getVerdict()).append(nl);
        sb.append("  summary         : ").append(comparison.getSummary()).append(nl);
        sb.append("  sha256Match     : ").append(comparison.isSha256Match()).append(nl);
        sb.append("  keySetMatch     : ").append(comparison.isKeySetMatch()).append(nl);
        sb.append("  valuesMatch     : ").append(comparison.isValuesMatch()).append(nl);
        sb.append("  commonKeys      : ").append(comparison.getCommonKeyCount()).append(nl);
        if (comparison.getKeysOnlyInFile() != null && !comparison.getKeysOnlyInFile().isEmpty()) {
            sb.append("  keysOnlyInFile  : ")
                    .append(String.join(", ", comparison.getKeysOnlyInFile())).append(nl);
        }
        if (comparison.getKeysOnlyInResource() != null
                && !comparison.getKeysOnlyInResource().isEmpty()) {
            sb.append("  keysOnlyInRes   : ")
                    .append(String.join(", ", comparison.getKeysOnlyInResource())).append(nl);
        }
        if (comparison.getDifferentValues() != null && !comparison.getDifferentValues().isEmpty()) {
            sb.append("  differentValues :").append(nl);
            for (Map.Entry<String, String> entry : comparison.getDifferentValues().entrySet()) {
                sb.append("      ").append(entry.getKey()).append(" -> ")
                        .append(entry.getValue()).append(nl);
            }
        }
    }

    private void appendOverlay(StringBuilder sb, DeploymentOverlayInfo overlay) {
        String nl = System.lineSeparator();
        sb.append(nl).append("[4] deployment-overlay の検知").append(nl);
        sb.append("  deployment      : ").append(overlay.getDeploymentName()).append(nl);
        sb.append("  mgmtModel       : ").append(overlay.isManagementModelAvailable()).append(nl);
        if (overlay.getUnavailableReason() != null) {
            sb.append("  mgmtModelReason : ").append(overlay.getUnavailableReason()).append(nl);
        }
        sb.append("  overlayNames    : ").append(overlay.getOverlayNames() == null
                ? "(none)" : String.join(", ", overlay.getOverlayNames())).append(nl);
        sb.append("  targetPath      : ").append(getResourcePathInDeployment()).append(nl);
        sb.append("  overlayForPath  : ").append(overlay.isDateConfigOverlayDefined()).append(nl);
        sb.append("  appliedToThis   : ").append(overlay.isOverlayAppliedToThisDeployment()).append(nl);
        if (overlay.getOverlays() != null) {
            for (DeploymentOverlayEntry entry : overlay.getOverlays()) {
                sb.append("      overlay=").append(entry.getName())
                        .append(", deployments=").append(String.join(",", entry.getDeployments()))
                        .append(", contents=").append(String.join(",", entry.getContentPaths()))
                        .append(nl);
                if (entry.getContentAttributes() != null) {
                    for (Map.Entry<String, String> attribute : entry.getContentAttributes().entrySet()) {
                        sb.append("        ").append(attribute.getKey()).append(" = ")
                                .append(attribute.getValue()).append(nl);
                    }
                }
            }
        }
        sb.append("  prevSnapshot    : ").append(overlay.isPreviousSnapshotAvailable())
                .append(overlay.getPreviousObservedAt() == null
                        ? "" : "（" + overlay.getPreviousObservedAt() + "）").append(nl);
        sb.append("  fileChanged     : ").append(overlay.isFileContentChanged()).append(nl);
        sb.append("  resourceChanged : ").append(overlay.isResourceContentChanged()).append(nl);
        sb.append("  resourceUrlChg  : ").append(overlay.isResourceUrlChanged()).append(nl);
        sb.append("  detection       : ").append(overlay.getDetectionSummary()).append(nl);
    }

    // ------------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------------

    private static ConfigSourceResult finish(ConfigSourceResult result, long startedAt) {
        result.setElapsedMs(System.currentTimeMillis() - startedAt);
        return result;
    }

    private static String formatInstant(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneId.systemDefault()).format(TIMESTAMP);
    }

    /** 内容の同一性判定に使う SHA-256（{@code AA:BB:...} ではなく小文字 hex）。 */
    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 は JDK 必須アルゴリズムなので到達しない。
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    /** クラスパスリソース名の先頭スラッシュを落とす（ClassLoader#getResource は絶対名を取らない）。 */
    private static String normalizeResourceName(String name) {
        String normalized = StringUtils.hasText(name)
                ? name.trim() : "jp/iwin/base/tango/date_config.properties";
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /** 直近の指紋を捨てる（テスト・手動確認用）。 */
    public void resetSnapshot() {
        lastSnapshot.set(null);
    }
}
