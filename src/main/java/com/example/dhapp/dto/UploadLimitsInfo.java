package com.example.dhapp.dto;

/**
 * ファイルアップロードに適用されている上限値。
 *
 * <p>正常系・エラー系の両方のレスポンスに含める。クライアントは 413 を受け取ったときに
 * 「どの上限に何バイトで引っかかったのか」をレスポンスだけで判断できる。</p>
 *
 * <p>アプリ側の上限（{@code spring.servlet.multipart.*}）はこのオブジェクトが実値を持つが、
 * AP サーバ（WildFly/Undertow）側の {@code max-post-size} はアプリからは参照できないため、
 * 確認方法を {@link #containerMaxPostSizeNote} に文字列で持たせている。</p>
 */
public class UploadLimitsInfo {

    /** spring.servlet.multipart.max-file-size（1 ファイルあたりの上限）の設定値表記。 */
    private String maxFileSize;

    /** 同上をバイト数に換算した値。 */
    private long maxFileSizeBytes;

    /** spring.servlet.multipart.max-request-size（マルチパートリクエスト全体の上限）の設定値表記。 */
    private String maxRequestSize;

    /** 同上をバイト数に換算した値。 */
    private long maxRequestSizeBytes;

    /** spring.servlet.multipart.file-size-threshold（この値を超えるとメモリではなくディスクに退避）。 */
    private String fileSizeThreshold;

    /** 同上をバイト数に換算した値。 */
    private long fileSizeThresholdBytes;

    /** spring.servlet.multipart.location の設定値。空文字なら AP サーバ既定のテンポラリフォルダを使用。 */
    private String multipartLocation;

    /** AP サーバ側 max-post-size の確認方法（アプリからは値を取得できないため案内文を返す）。 */
    private String containerMaxPostSizeNote;

    public UploadLimitsInfo() {
    }

    public String getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(String maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public String getMaxRequestSize() {
        return maxRequestSize;
    }

    public void setMaxRequestSize(String maxRequestSize) {
        this.maxRequestSize = maxRequestSize;
    }

    public long getMaxRequestSizeBytes() {
        return maxRequestSizeBytes;
    }

    public void setMaxRequestSizeBytes(long maxRequestSizeBytes) {
        this.maxRequestSizeBytes = maxRequestSizeBytes;
    }

    public String getFileSizeThreshold() {
        return fileSizeThreshold;
    }

    public void setFileSizeThreshold(String fileSizeThreshold) {
        this.fileSizeThreshold = fileSizeThreshold;
    }

    public long getFileSizeThresholdBytes() {
        return fileSizeThresholdBytes;
    }

    public void setFileSizeThresholdBytes(long fileSizeThresholdBytes) {
        this.fileSizeThresholdBytes = fileSizeThresholdBytes;
    }

    public String getMultipartLocation() {
        return multipartLocation;
    }

    public void setMultipartLocation(String multipartLocation) {
        this.multipartLocation = multipartLocation;
    }

    public String getContainerMaxPostSizeNote() {
        return containerMaxPostSizeNote;
    }

    public void setContainerMaxPostSizeNote(String containerMaxPostSizeNote) {
        this.containerMaxPostSizeNote = containerMaxPostSizeNote;
    }
}
