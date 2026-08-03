package com.example.dhapp.dto;

/**
 * ファイルアップロード異常系のレスポンスボディ。
 *
 * <p>特に「サイズ上限（アプリ側 {@code spring.servlet.multipart.*} / AP サーバ側 {@code max-post-size}）に
 * 引っかかった」ケースで、どの上限にどれだけのサイズで違反したのかを 1 レスポンスで判別できるようにする。</p>
 *
 * <p>{@link #limitSource} で「アプリ側の上限で弾いたのか、AP サーバ側の上限で弾かれたのか」が分かる。
 * AP サーバ側で弾かれた場合は {@link #rootCauseMessage} に Undertow の {@code UT000020} が入る。</p>
 */
public class UploadErrorResponse {

    /** 常に "ERROR"。 */
    private String status;

    /** 機械判定用のエラーコード。MAX_UPLOAD_SIZE_EXCEEDED / MULTIPART_PARSE_ERROR / EMPTY_FILE / IO_ERROR など。 */
    private String errorCode;

    /** 返却した HTTP ステータスコード（ボディだけを見ても分かるように保持する）。 */
    private int httpStatus;

    /** 人が読めるエラーメッセージ。 */
    private String message;

    /** リクエスト単位の識別子。ログの requestId と突き合わせられる。 */
    private String requestId;

    /** エラー発生時刻（ISO 8601 / オフセット付き）。 */
    private String timestamp;

    /** リクエストの Content-Length ヘッダ値（不明なら -1）。 */
    private long requestContentLength;

    /** 同上を人が読める単位に整形した値。 */
    private String requestContentLengthReadable;

    /** 違反した上限のバイト数（判別できない場合は -1）。 */
    private long permittedMaxBytes;

    /** 同上を人が読める単位に整形した値。 */
    private String permittedMaxReadable;

    /**
     * どちらの上限で弾かれたか。
     * <ul>
     *   <li>{@code application(spring.servlet.multipart.*)} … アプリ側の上限</li>
     *   <li>{@code container(undertow max-post-size)} … AP サーバ側の上限</li>
     *   <li>{@code unknown} … 判別できなかった</li>
     * </ul>
     */
    private String limitSource;

    /** 送出された例外のクラス名。 */
    private String exceptionClass;

    /** 送出された例外のメッセージ。 */
    private String exceptionMessage;

    /** 根本原因（cause チェーンの末端）のクラス名。 */
    private String rootCauseClass;

    /** 根本原因のメッセージ。AP サーバ側で弾かれた場合はここに UT000020 が入る。 */
    private String rootCauseMessage;

    /** 対処方法の案内。 */
    private String hint;

    /** 適用されているアップロード上限。 */
    private UploadLimitsInfo limits;

    public UploadErrorResponse() {
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public long getRequestContentLength() {
        return requestContentLength;
    }

    public void setRequestContentLength(long requestContentLength) {
        this.requestContentLength = requestContentLength;
    }

    public String getRequestContentLengthReadable() {
        return requestContentLengthReadable;
    }

    public void setRequestContentLengthReadable(String requestContentLengthReadable) {
        this.requestContentLengthReadable = requestContentLengthReadable;
    }

    public long getPermittedMaxBytes() {
        return permittedMaxBytes;
    }

    public void setPermittedMaxBytes(long permittedMaxBytes) {
        this.permittedMaxBytes = permittedMaxBytes;
    }

    public String getPermittedMaxReadable() {
        return permittedMaxReadable;
    }

    public void setPermittedMaxReadable(String permittedMaxReadable) {
        this.permittedMaxReadable = permittedMaxReadable;
    }

    public String getLimitSource() {
        return limitSource;
    }

    public void setLimitSource(String limitSource) {
        this.limitSource = limitSource;
    }

    public String getExceptionClass() {
        return exceptionClass;
    }

    public void setExceptionClass(String exceptionClass) {
        this.exceptionClass = exceptionClass;
    }

    public String getExceptionMessage() {
        return exceptionMessage;
    }

    public void setExceptionMessage(String exceptionMessage) {
        this.exceptionMessage = exceptionMessage;
    }

    public String getRootCauseClass() {
        return rootCauseClass;
    }

    public void setRootCauseClass(String rootCauseClass) {
        this.rootCauseClass = rootCauseClass;
    }

    public String getRootCauseMessage() {
        return rootCauseMessage;
    }

    public void setRootCauseMessage(String rootCauseMessage) {
        this.rootCauseMessage = rootCauseMessage;
    }

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }

    public UploadLimitsInfo getLimits() {
        return limits;
    }

    public void setLimits(UploadLimitsInfo limits) {
        this.limits = limits;
    }
}
