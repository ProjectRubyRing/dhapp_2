package com.example.dhapp.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import jakarta.servlet.ServletContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import com.example.dhapp.dto.FileUploadResponse;
import com.example.dhapp.dto.UploadLimitsInfo;
import com.example.dhapp.exception.FileUploadException;

/**
 * アップロードされたファイルを AP サーバのテンポラリフォルダへ保存するサービス。
 *
 * <h2>保存先（テンポラリフォルダ）の決定順序</h2>
 * <ol>
 *   <li>{@code spring.servlet.multipart.location} が設定されていればそのディレクトリ</li>
 *   <li>未設定なら AP サーバがこのデプロイに割り当てたテンポラリフォルダ
 *       （ServletContext 属性 {@code jakarta.servlet.context.tempdir}）。
 *       WildFly/JBoss EAP では通常 {@code $JBOSS_HOME/standalone/tmp/<war 名>} 配下になる。</li>
 *   <li>いずれも取得できない場合はシステムプロパティ {@code java.io.tmpdir}</li>
 * </ol>
 *
 * <p>2 番目が既定の動作で、これが「AP サーバ上に設定されているファイルアップロード先の
 * テンポラリフォルダ」にあたる。どの経路で決まったかは {@code tempDirSource} として
 * レスポンスとログの両方に出力するため、環境ごとの実際の保存先を追跡できる。</p>
 *
 * <h2>保存ファイル名</h2>
 * <p>{@code upload_<yyyyMMddHHmmssSSS>_<UUID 先頭 8 桁>_<サニタイズした元ファイル名>}。
 * 同名アップロードでの上書きを避けるためタイムスタンプと UUID を付与し、
 * クライアント由来のファイル名はディレクトリ区切り文字などを除去してから使う
 * （パストラバーサル対策。保存直前に保存先ディレクトリ配下であることも検証する）。</p>
 */
@Service
public class FileUploadService {

    private static final Logger log = LoggerFactory.getLogger(FileUploadService.class);

    /** 保存ファイル名の接頭辞。 */
    private static final String STORED_FILE_PREFIX = "upload_";

    /** 保存ファイル名に埋め込むタイムスタンプの書式。 */
    private static final DateTimeFormatter FILE_NAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 元ファイル名から除去する文字（ディレクトリ区切り・Windows 予約文字・制御文字）。 */
    private static final String UNSAFE_FILE_NAME_CHARS = "[\\\\/:*?\"<>|\\p{Cntrl}]";

    /** 保存ファイル名に残す元ファイル名の最大長。 */
    private static final int MAX_ORIGINAL_NAME_LENGTH = 100;

    /** 元ファイル名が空・不正だった場合に使う代替名。 */
    private static final String FALLBACK_FILE_NAME = "unnamed";

    /** AP サーバ側 max-post-size の確認方法（アプリからは値を参照できない）。 */
    private static final String CONTAINER_MAX_POST_SIZE_NOTE =
            "AP サーバ側の上限はアプリからは参照できない。WildFly/JBoss EAP では "
                    + "/subsystem=undertow/server=default-server/http-listener=default:read-attribute(name=max-post-size) "
                    + "で確認する（既定 10485760 バイト = 10MB）。";

    /** spring.servlet.multipart.location。空文字なら AP サーバ既定のテンポラリフォルダを使う。 */
    private final String multipartLocation;

    /** spring.servlet.multipart.max-file-size（1 ファイルあたりの上限）。 */
    private final DataSize maxFileSize;

    /** spring.servlet.multipart.max-request-size（マルチパートリクエスト全体の上限）。 */
    private final DataSize maxRequestSize;

    /** spring.servlet.multipart.file-size-threshold（超過分をディスクへ退避する閾値）。 */
    private final DataSize fileSizeThreshold;

    /** AP サーバのテンポラリフォルダ解決に使う ServletContext。 */
    private final ServletContext servletContext;

    public FileUploadService(
            ServletContext servletContext,
            @Value("${spring.servlet.multipart.location:}") String multipartLocation,
            @Value("${spring.servlet.multipart.max-file-size:1MB}") DataSize maxFileSize,
            @Value("${spring.servlet.multipart.max-request-size:10MB}") DataSize maxRequestSize,
            @Value("${spring.servlet.multipart.file-size-threshold:0B}") DataSize fileSizeThreshold) {
        this.servletContext = servletContext;
        this.multipartLocation = multipartLocation;
        this.maxFileSize = maxFileSize;
        this.maxRequestSize = maxRequestSize;
        this.fileSizeThreshold = fileSizeThreshold;
    }

    /**
     * アップロードされたファイルをテンポラリフォルダへ保存し、保存場所とサイズをログに出力する。
     *
     * @param file      受信したマルチパートファイル
     * @param fieldName multipart のパート名（curl の {@code -F "<名前>=@..."} で指定した名前）
     * @param note      任意の付随メッセージ
     * @param requestId リクエスト識別子（ログ突き合わせ用）
     * @return 保存結果（保存先絶対パス・サイズ等）
     * @throws FileUploadException ファイルが空、または保存に失敗した場合
     */
    public FileUploadResponse store(MultipartFile file, String fieldName, String note, String requestId) {
        long startedAt = System.currentTimeMillis();
        String receivedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        if (file == null || file.isEmpty()) {
            log.warn("File upload rejected: empty file. requestId={}, fieldName={}, originalFilename={}",
                    requestId, fieldName, file == null ? null : file.getOriginalFilename());
            throw new FileUploadException("EMPTY_FILE",
                    "アップロードされたファイルが空です。curl の -F \"" + fieldName + "=@<ファイルパス>\" で"
                            + "存在するファイルを指定してください。");
        }

        ResolvedTempDir tempDir = resolveTempDir();
        String originalFilename = file.getOriginalFilename();
        String storedFileName = buildStoredFileName(originalFilename);
        Path target = tempDir.directory().resolve(storedFileName).normalize();

        // サニタイズ後も念のため保存先ディレクトリ配下に収まっていることを検証する。
        if (!target.startsWith(tempDir.directory())) {
            log.error("File upload rejected: resolved path escaped the temp directory. "
                            + "requestId={}, tempDir={}, target={}",
                    requestId, tempDir.directory(), target);
            throw new FileUploadException("INVALID_FILE_NAME",
                    "ファイル名が不正です（保存先ディレクトリの外を指しています）。");
        }

        log.debug("File upload starting. requestId={}, fieldName={}, originalFilename={}, declaredSizeBytes={}, "
                        + "contentType={}, tempDir={}, tempDirSource={}, storedFileName={}",
                requestId, fieldName, originalFilename, file.getSize(), file.getContentType(),
                tempDir.directory(), tempDir.source(), storedFileName);

        long writtenBytes;
        String sha256;
        try {
            Files.createDirectories(tempDir.directory());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(file.getInputStream(), digest);
                 OutputStream out = Files.newOutputStream(target,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writtenBytes = in.transferTo(out);
            }
            sha256 = HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 は JRE 標準のため通常発生しない。
            log.error("File upload failed: SHA-256 is unavailable. requestId={}", requestId, e);
            throw new FileUploadException("IO_ERROR", "ハッシュ計算に失敗しました: " + e.getMessage(), e);
        } catch (IOException e) {
            log.error("File upload failed while writing to the temp directory. requestId={}, target={}",
                    requestId, target, e);
            throw new FileUploadException("IO_ERROR",
                    "テンポラリフォルダへの保存に失敗しました: " + target + " (" + e.getMessage() + ")", e);
        }

        long storedSize;
        try {
            storedSize = Files.size(target);
        } catch (IOException e) {
            // 書き込み自体は成功しているため、転送バイト数で代替する。
            log.warn("Could not stat the stored file, falling back to the transferred byte count. "
                    + "requestId={}, target={}", requestId, target, e);
            storedSize = writtenBytes;
        }

        // 【要件】保存場所と保存したファイルのサイズをログに出力する。
        log.info("File uploaded and stored. requestId={}, storedPath={}, sizeBytes={}, sizeReadable={}",
                requestId, target.toAbsolutePath(), storedSize, toReadableSize(storedSize));
        log.debug("File upload detail. requestId={}, fieldName={}, originalFilename={}, contentType={}, "
                        + "storedDirectory={}, tempDirSource={}, storedFileName={}, declaredSizeBytes={}, "
                        + "writtenBytes={}, sha256={}",
                requestId, fieldName, originalFilename, file.getContentType(),
                tempDir.directory(), tempDir.source(), storedFileName, file.getSize(), writtenBytes, sha256);

        FileUploadResponse response = new FileUploadResponse();
        response.setStatus("SUCCESS");
        response.setRequestId(requestId);
        response.setReceivedAt(receivedAt);
        response.setFormFieldName(fieldName);
        response.setOriginalFilename(originalFilename);
        response.setContentType(file.getContentType());
        response.setSizeBytes(storedSize);
        response.setSizeReadable(toReadableSize(storedSize));
        response.setSha256(sha256);
        response.setStoredFileName(storedFileName);
        response.setStoredPath(target.toAbsolutePath().toString());
        response.setStoredDirectory(tempDir.directory().toString());
        response.setTempDirSource(tempDir.source());
        response.setNote(note);
        response.setElapsedMs(System.currentTimeMillis() - startedAt);
        response.setLimits(buildLimitsInfo());
        return response;
    }

    /**
     * 保存先テンポラリフォルダを解決する。決定順序はクラスの Javadoc を参照。
     */
    public ResolvedTempDir resolveTempDir() {
        if (StringUtils.hasText(multipartLocation)) {
            return new ResolvedTempDir(
                    Paths.get(multipartLocation).toAbsolutePath().normalize(),
                    "spring.servlet.multipart.location");
        }
        // AP サーバ（WildFly/Undertow）がこのデプロイに割り当てたテンポラリフォルダ。
        Object attribute = servletContext.getAttribute(ServletContext.TEMPDIR);
        if (attribute instanceof File dir) {
            return new ResolvedTempDir(
                    dir.toPath().toAbsolutePath().normalize(),
                    "servletContext:" + ServletContext.TEMPDIR);
        }
        if (attribute instanceof String dir && StringUtils.hasText(dir)) {
            return new ResolvedTempDir(
                    Paths.get(dir).toAbsolutePath().normalize(),
                    "servletContext:" + ServletContext.TEMPDIR);
        }
        return new ResolvedTempDir(
                Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(),
                "system:java.io.tmpdir");
    }

    /**
     * 現在適用されているアップロード上限を組み立てる。正常系・異常系の両レスポンスで使う。
     */
    public UploadLimitsInfo buildLimitsInfo() {
        UploadLimitsInfo limits = new UploadLimitsInfo();
        limits.setMaxFileSize(toReadableSize(maxFileSize.toBytes()));
        limits.setMaxFileSizeBytes(maxFileSize.toBytes());
        limits.setMaxRequestSize(toReadableSize(maxRequestSize.toBytes()));
        limits.setMaxRequestSizeBytes(maxRequestSize.toBytes());
        limits.setFileSizeThreshold(toReadableSize(fileSizeThreshold.toBytes()));
        limits.setFileSizeThresholdBytes(fileSizeThreshold.toBytes());
        limits.setMultipartLocation(multipartLocation);
        limits.setContainerMaxPostSizeNote(CONTAINER_MAX_POST_SIZE_NOTE);
        return limits;
    }

    /** マルチパートリクエスト全体の上限（バイト）。エラーレスポンスの上限表示に使う。 */
    public long getMaxRequestSizeBytes() {
        return maxRequestSize.toBytes();
    }

    /** 1 ファイルあたりの上限（バイト）。エラーレスポンスの上限表示に使う。 */
    public long getMaxFileSizeBytes() {
        return maxFileSize.toBytes();
    }

    /**
     * バイト数を人が読める単位に整形する（1KB = 1024B）。
     *
     * @param bytes バイト数。負値は不明として "unknown" を返す。
     */
    public static String toReadableSize(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = { "KB", "MB", "GB", "TB", "PB" };
        double value = bytes;
        int unitIndex = -1;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unitIndex]);
    }

    /**
     * 保存ファイル名を組み立てる。
     * クライアント由来のファイル名はディレクトリ部分と危険な文字を落としてから使う。
     */
    private static String buildStoredFileName(String originalFilename) {
        return STORED_FILE_PREFIX
                + OffsetDateTime.now().format(FILE_NAME_TIMESTAMP)
                + "_" + UUID.randomUUID().toString().substring(0, 8)
                + "_" + sanitizeFileName(originalFilename);
    }

    /**
     * 元ファイル名からディレクトリ部分と危険な文字を除去する。
     * 日本語などのマルチバイト文字はそのまま残す（元ファイル名は originalFilename でも返す）。
     */
    private static String sanitizeFileName(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            return FALLBACK_FILE_NAME;
        }
        String name = StringUtils.getFilename(StringUtils.cleanPath(originalFilename));
        if (!StringUtils.hasText(name)) {
            return FALLBACK_FILE_NAME;
        }
        name = name.replaceAll(UNSAFE_FILE_NAME_CHARS, "_");
        // "." や ".." だけになった場合も保存先の外を指さないようにする。
        name = name.replaceAll("^\\.+$", FALLBACK_FILE_NAME);
        if (name.length() > MAX_ORIGINAL_NAME_LENGTH) {
            name = name.substring(name.length() - MAX_ORIGINAL_NAME_LENGTH);
        }
        return StringUtils.hasText(name) ? name : FALLBACK_FILE_NAME;
    }

    /**
     * 解決したテンポラリフォルダと、その解決経路。
     *
     * @param directory 保存先ディレクトリ（絶対パス）
     * @param source    どの設定から解決したか
     */
    public record ResolvedTempDir(Path directory, String source) {
    }
}
