package com.example.dhapp.exception;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.example.dhapp.dto.ErrorResponse;
import com.example.dhapp.dto.UploadErrorResponse;
import com.example.dhapp.service.FileUploadService;

/**
 * 例外を HTTP ステータス付きのレスポンスに変換する。
 * 2PC ロールバックテストで送出される DemoException は 500 として返るため、
 * クライアントはエラーを確認した上で「両 DB にレコードが存在しないこと」を検証できる。
 *
 * <p>ファイルアップロード関連の例外（サイズ上限超過・マルチパート解析失敗）は
 * {@link UploadErrorResponse} として、どの上限に何バイトで違反したのかまで含めて返す。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Undertow が AP サーバ側の {@code max-post-size} 超過で送出するエラーコード。
     * メッセージ例: {@code UT000020: Connection terminated as request was larger than 10485760}
     */
    private static final String UNDERTOW_MAX_POST_SIZE_CODE = "UT000020";

    /**
     * サーブレット（{@code spring.servlet.multipart.*}）側の上限超過を示すメッセージの言い回し。
     * メッセージ本文は AP サーバ・バージョンによって異なるため、エラーコードではなく文言で判定する。
     * 例: {@code UT000067: Request entity was too large, max size is 5242880} /
     * {@code the request was rejected because its size (8388608) exceeds the configured maximum (5242880)}
     */
    private static final Pattern SIZE_LIMIT_WORDING = Pattern.compile(
            "(?i)(too large|larger than|exceeds? the configured maximum|max size is|size .{0,20}exceed)");

    /** 例外メッセージ内の数値（バイト数）を拾うためのパターン。最後の一致が上限値になる。 */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    /** アプリ側（spring.servlet.multipart.*）の上限で弾かれたことを示す値。 */
    private static final String LIMIT_SOURCE_APPLICATION = "application(spring.servlet.multipart.*)";

    /** AP サーバ側（Undertow の max-post-size）の上限で弾かれたことを示す値。 */
    private static final String LIMIT_SOURCE_CONTAINER = "container(undertow max-post-size)";

    /** どちらの上限か判別できなかったことを示す値。 */
    private static final String LIMIT_SOURCE_UNKNOWN = "unknown";

    private final FileUploadService fileUploadService;

    public GlobalExceptionHandler(FileUploadService fileUploadService) {
        this.fileUploadService = fileUploadService;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("validation error");
        log.warn("Validation failed: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("ERROR", msg, null));
    }

    /**
     * アップロードサイズが上限を超えた場合。
     *
     * <p>{@code spring.servlet.multipart.max-file-size} / {@code max-request-size} の超過は
     * サーブレットコンテナ（WildFly では Undertow）が検知し、Spring がこの例外に変換する。
     * HTTP 413 と共に、違反した上限値・リクエストサイズ・原因例外を返す。</p>
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<UploadErrorResponse> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {

        long permittedMax = ex.getMaxUploadSize();
        if (permittedMax <= 0) {
            // Undertow 経由では例外に上限値が入らない（-1）。原因メッセージから拾い、
            // 取れなければ設定値（max-request-size）を表示する。
            permittedMax = extractLimitBytes(ex);
        }
        if (permittedMax <= 0) {
            permittedMax = fileUploadService.getMaxRequestSizeBytes();
        }

        String limitSource = detectLimitSource(ex, LIMIT_SOURCE_APPLICATION);
        UploadErrorResponse body = buildUploadError(ex, request,
                "MAX_UPLOAD_SIZE_EXCEEDED",
                HttpStatus.PAYLOAD_TOO_LARGE,
                "アップロードサイズが上限を超えました。許容される最大サイズは "
                        + FileUploadService.toReadableSize(permittedMax)
                        + " (" + permittedMax + " バイト) です。",
                permittedMax,
                limitSource,
                buildSizeLimitHint(limitSource));

        log.error("File upload rejected: size limit exceeded. requestId={}, limitSource={}, contentLength={}, "
                        + "permittedMaxBytes={}, rootCause={}",
                body.getRequestId(), limitSource, body.getRequestContentLength(), permittedMax,
                body.getRootCauseMessage(), ex);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    /**
     * マルチパートリクエストの解析に失敗した場合。
     *
     * <p>AP サーバ側の {@code max-post-size} 超過（Undertow の {@code UT000020}）はサイズ超過を示す
     * 文言を含まないため、Spring は {@link MaxUploadSizeExceededException} ではなくこの例外に変換する。
     * そのため原因メッセージを見て 413 と 400 を出し分ける。</p>
     *
     * <p>なお {@code max-post-size} 超過時は Undertow がリクエストを読み切らずに接続を切断するため、
     * curl 側ではこの JSON を受け取れず {@code curl: (55)} 等になる場合がある（FILE_UPLOAD_API.md 参照）。</p>
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<UploadErrorResponse> handleMultipart(
            MultipartException ex, HttpServletRequest request) {

        String rootMessage = causeChainMessage(ex);
        boolean sizeRelated = rootMessage != null
                && (rootMessage.contains(UNDERTOW_MAX_POST_SIZE_CODE)
                    || SIZE_LIMIT_WORDING.matcher(rootMessage).find());

        if (sizeRelated) {
            long permittedMax = extractLimitBytes(ex);
            String limitSource = detectLimitSource(ex, LIMIT_SOURCE_UNKNOWN);
            UploadErrorResponse body = buildUploadError(ex, request,
                    "MAX_POST_SIZE_EXCEEDED",
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "AP サーバ側のリクエストサイズ上限 (max-post-size) を超えたため、"
                            + "リクエストボディの読み取りが中断されました。"
                            + (permittedMax > 0
                                    ? "上限は " + FileUploadService.toReadableSize(permittedMax)
                                      + " (" + permittedMax + " バイト) です。"
                                    : ""),
                    permittedMax,
                    limitSource,
                    buildSizeLimitHint(limitSource));

            log.error("File upload rejected: container post size limit exceeded. requestId={}, limitSource={}, "
                            + "contentLength={}, permittedMaxBytes={}, rootCause={}",
                    body.getRequestId(), limitSource, body.getRequestContentLength(), permittedMax,
                    rootMessage, ex);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
        }

        UploadErrorResponse body = buildUploadError(ex, request,
                "MULTIPART_PARSE_ERROR",
                HttpStatus.BAD_REQUEST,
                "multipart/form-data リクエストの解析に失敗しました: " + ex.getMessage(),
                -1L,
                LIMIT_SOURCE_UNKNOWN,
                "curl では -F \"file=@<ファイルパス>\" を使う（-d や --data-binary ではボディが "
                        + "multipart/form-data にならない）。Content-Type ヘッダは curl が boundary 付きで "
                        + "自動設定するため手動で指定しない。");

        log.warn("File upload rejected: multipart parse error. requestId={}, message={}",
                body.getRequestId(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * multipart リクエストではあるが、必要なパート（{@code file}）が含まれていない場合。
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<UploadErrorResponse> handleMissingPart(
            MissingServletRequestPartException ex, HttpServletRequest request) {

        UploadErrorResponse body = buildUploadError(ex, request,
                "MISSING_FILE_PART",
                HttpStatus.BAD_REQUEST,
                "必須のパート \"" + ex.getRequestPartName() + "\" がリクエストに含まれていません。",
                -1L,
                LIMIT_SOURCE_UNKNOWN,
                "curl で -F \"" + ex.getRequestPartName() + "=@<ファイルパス>\" を指定する。"
                        + "パート名が \"" + ex.getRequestPartName() + "\" 以外だとこのエラーになる。");

        log.warn("File upload rejected: missing part. requestId={}, partName={}",
                body.getRequestId(), ex.getRequestPartName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * アプリ側が検知したアップロードエラー（空ファイル・不正ファイル名・保存時 I/O エラー）。
     */
    @ExceptionHandler(FileUploadException.class)
    public ResponseEntity<UploadErrorResponse> handleFileUpload(
            FileUploadException ex, HttpServletRequest request) {

        HttpStatus status = "IO_ERROR".equals(ex.getErrorCode())
                ? HttpStatus.INTERNAL_SERVER_ERROR
                : HttpStatus.BAD_REQUEST;

        UploadErrorResponse body = buildUploadError(ex, request,
                ex.getErrorCode(),
                status,
                ex.getMessage(),
                -1L,
                LIMIT_SOURCE_UNKNOWN,
                "IO_ERROR".equals(ex.getErrorCode())
                        ? "AP サーバのテンポラリフォルダの存在・書き込み権限・空き容量を確認する。"
                          + "保存先は GET /api/file/upload-info で確認できる。"
                        : "アップロードするファイルのパスと内容（0 バイトでないこと）を確認する。");

        log.error("File upload failed. requestId={}, errorCode={}, message={}",
                body.getRequestId(), ex.getErrorCode(), ex.getMessage(), ex);
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(DemoException.class)
    public ResponseEntity<ErrorResponse> handleDemoException(DemoException ex) {
        log.error("DemoException: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("ERROR", ex.getMessage(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("ERROR", "Internal error: " + ex.getMessage(), null));
    }

    /**
     * アップロード系エラーレスポンスの共通部分を組み立てる。
     */
    private UploadErrorResponse buildUploadError(Throwable ex, HttpServletRequest request,
            String errorCode, HttpStatus status, String message,
            long permittedMaxBytes, String limitSource, String hint) {

        long contentLength = request.getContentLengthLong();
        Throwable rootCause = rootCause(ex);

        UploadErrorResponse body = new UploadErrorResponse();
        body.setStatus("ERROR");
        body.setErrorCode(errorCode);
        body.setHttpStatus(status.value());
        body.setMessage(message);
        body.setRequestId(UUID.randomUUID().toString());
        body.setTimestamp(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        body.setRequestContentLength(contentLength);
        body.setRequestContentLengthReadable(FileUploadService.toReadableSize(contentLength));
        body.setPermittedMaxBytes(permittedMaxBytes);
        body.setPermittedMaxReadable(FileUploadService.toReadableSize(permittedMaxBytes));
        body.setLimitSource(limitSource);
        body.setExceptionClass(ex.getClass().getName());
        body.setExceptionMessage(ex.getMessage());
        body.setRootCauseClass(rootCause.getClass().getName());
        body.setRootCauseMessage(rootCause.getMessage());
        body.setHint(hint);
        body.setLimits(fileUploadService.buildLimitsInfo());
        return body;
    }

    /**
     * 原因メッセージから、アプリ側と AP サーバ側のどちらの上限で弾かれたかを判定する。
     *
     * <p>AP サーバ側の {@code max-post-size} 超過は Undertow が接続を打ち切るため、
     * 「サイズ超過」ではなく「接続終了」を意味する {@code UT000020} になる。これが唯一の確実な
     * 判別材料なので最優先で見る。それ以外のサイズ超過はサーブレット側（＝アプリ設定）の上限。</p>
     *
     * @param fallback 判定できなかった場合に返す値
     */
    private static String detectLimitSource(Throwable ex, String fallback) {
        String rootMessage = causeChainMessage(ex);
        if (rootMessage == null) {
            return fallback;
        }
        if (rootMessage.contains(UNDERTOW_MAX_POST_SIZE_CODE)) {
            return LIMIT_SOURCE_CONTAINER;
        }
        if (SIZE_LIMIT_WORDING.matcher(rootMessage).find()) {
            return LIMIT_SOURCE_APPLICATION;
        }
        return fallback;
    }

    /**
     * 上限超過の原因メッセージから上限バイト数を取り出す。
     * UT000020 / UT000054 のいずれも、メッセージ中の最後の数値が上限値になっている。
     *
     * @return 取り出せなかった場合は -1
     */
    private static long extractLimitBytes(Throwable ex) {
        String rootMessage = causeChainMessage(ex);
        if (rootMessage == null) {
            return -1L;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(rootMessage);
        long last = -1L;
        while (matcher.find()) {
            try {
                last = Long.parseLong(matcher.group());
            } catch (NumberFormatException e) {
                // 桁溢れ等は無視して次の一致を見る。
                last = -1L;
            }
        }
        return last;
    }

    /**
     * サイズ上限超過時の対処案内を組み立てる。
     */
    private static String buildSizeLimitHint(String limitSource) {
        if (LIMIT_SOURCE_CONTAINER.equals(limitSource)) {
            return "AP サーバ側の上限を引き上げる場合は WildFly の http-listener の max-post-size を変更する: "
                    + "/subsystem=undertow/server=default-server/http-listener=default:"
                    + "write-attribute(name=max-post-size,value=<バイト数>) 実行後 :reload。";
        }
        return "アプリ側の上限は spring.servlet.multipart.max-file-size / max-request-size "
                + "（環境変数 MULTIPART_MAX_FILE_SIZE / MULTIPART_MAX_REQUEST_SIZE）で変更する。"
                + "AP サーバ側の max-post-size より小さく設定しておくと、この詳細な JSON レスポンスを返せる。";
    }

    /** cause チェーンの末端を返す。 */
    private static Throwable rootCause(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** cause チェーン全体のメッセージを連結して返す（どの階層に UT000020 等があっても検出できるようにする）。 */
    private static String causeChainMessage(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(current.getMessage());
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
