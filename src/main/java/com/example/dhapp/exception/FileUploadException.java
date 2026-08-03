package com.example.dhapp.exception;

/**
 * ファイルアップロード処理でアプリ側が検知したエラー。
 *
 * <p>サイズ上限違反（{@code MaxUploadSizeExceededException} / {@code MultipartException}）は
 * Spring/AP サーバが送出するためこの例外は使わず、GlobalExceptionHandler で個別に扱う。
 * 本例外は「ファイルが空」「ファイル名が不正」「保存時の I/O エラー」など、
 * アプリ自身が判定したエラーを表す。</p>
 *
 * <p>{@link #getErrorCode()} はレスポンスの {@code errorCode} にそのまま出力される。</p>
 */
public class FileUploadException extends RuntimeException {

    /** レスポンスの errorCode に出す機械判定用コード（EMPTY_FILE / INVALID_FILE_NAME / IO_ERROR）。 */
    private final String errorCode;

    public FileUploadException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public FileUploadException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
