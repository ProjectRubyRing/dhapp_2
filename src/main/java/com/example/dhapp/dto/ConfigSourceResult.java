package com.example.dhapp.dto;

import java.util.List;
import java.util.Map;

/**
 * {@code date_config.properties} を 1 つの読み込み経路（ファイル読み／リソース読み）で
 * 読んだ結果。
 *
 * <p>「読めたか」だけでなく、<b>どこの実体を読んだのか</b>（解決後の URL・実ファイルパス）と、
 * <b>その実体が何だったのか</b>（サイズ・最終更新時刻・SHA-256）まで持たせている。
 * deployment-overlay で差し替えられると、リソース読み側だけ解決先 URL や SHA-256 が
 * 変わるため、この 3 点があれば反映有無を機械的に判定できる。</p>
 */
public class ConfigSourceResult {

    /** 読み込み経路。{@code FILE}（ファイル読み） / {@code RESOURCE}（リソース読み）。 */
    private String readType;

    /** 何を指定して読んだか（ファイルパス、またはクラスパス上のリソース名）。 */
    private String location;

    /** 実際に解決された URL（リソース読みのみ。overlay 適用時はここが VFS の別実体を指す）。 */
    private String resolvedUrl;

    /** 解決された URL から取り出せた実ファイルパス（取り出せない場合は null）。 */
    private String resolvedFilePath;

    /** どのクラスローダで解決したか（リソース読みのみ）。 */
    private String classLoader;

    private boolean exists;
    private boolean readable;

    /** {@link java.util.Properties} として解釈できたか。 */
    private boolean loaded;

    /** 読み取ったバイト数。 */
    private Long sizeBytes;

    /** 最終更新時刻（ISO-8601）。取得できない場合は null。 */
    private String lastModified;

    /** 読み取った内容の SHA-256（内容が変わったかの判定に使う）。 */
    private String sha256;

    /** 復号に使った文字セット。 */
    private String charset;

    /** 指定文字セットで復号しきれない byte 列があったか（文字化けの検知）。 */
    private boolean charsetMismatch;

    /** 読み取れたプロパティ（定義順）。 */
    private Map<String, String> properties;

    private int propertyCount;

    /** 生テキスト（コメント込み・先頭のみ）。長い場合は末尾を切る。 */
    private String rawTextPreview;

    /** 生テキストを切り詰めたか。 */
    private boolean rawTextTruncated;

    /** 読み込みに失敗した理由（成功時は null）。 */
    private String errorMessage;

    /** 失敗時の例外クラス名。 */
    private String exceptionClass;

    /** 対処の手がかり。 */
    private String hint;

    private long elapsedMs;

    /** 参考情報（クラスパス上に同名リソースが複数ある場合の一覧など）。 */
    private List<String> notes;

    public ConfigSourceResult() {
    }

    public String getReadType() {
        return readType;
    }

    public void setReadType(String readType) {
        this.readType = readType;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getResolvedUrl() {
        return resolvedUrl;
    }

    public void setResolvedUrl(String resolvedUrl) {
        this.resolvedUrl = resolvedUrl;
    }

    public String getResolvedFilePath() {
        return resolvedFilePath;
    }

    public void setResolvedFilePath(String resolvedFilePath) {
        this.resolvedFilePath = resolvedFilePath;
    }

    public String getClassLoader() {
        return classLoader;
    }

    public void setClassLoader(String classLoader) {
        this.classLoader = classLoader;
    }

    public boolean isExists() {
        return exists;
    }

    public void setExists(boolean exists) {
        this.exists = exists;
    }

    public boolean isReadable() {
        return readable;
    }

    public void setReadable(boolean readable) {
        this.readable = readable;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getLastModified() {
        return lastModified;
    }

    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public boolean isCharsetMismatch() {
        return charsetMismatch;
    }

    public void setCharsetMismatch(boolean charsetMismatch) {
        this.charsetMismatch = charsetMismatch;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    public int getPropertyCount() {
        return propertyCount;
    }

    public void setPropertyCount(int propertyCount) {
        this.propertyCount = propertyCount;
    }

    public String getRawTextPreview() {
        return rawTextPreview;
    }

    public void setRawTextPreview(String rawTextPreview) {
        this.rawTextPreview = rawTextPreview;
    }

    public boolean isRawTextTruncated() {
        return rawTextTruncated;
    }

    public void setRawTextTruncated(boolean rawTextTruncated) {
        this.rawTextTruncated = rawTextTruncated;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getExceptionClass() {
        return exceptionClass;
    }

    public void setExceptionClass(String exceptionClass) {
        this.exceptionClass = exceptionClass;
    }

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public List<String> getNotes() {
        return notes;
    }

    public void setNotes(List<String> notes) {
        this.notes = notes;
    }
}
