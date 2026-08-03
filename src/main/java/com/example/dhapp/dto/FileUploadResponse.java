package com.example.dhapp.dto;

/**
 * {@code POST /api/file/upload} の正常系レスポンスボディ。
 *
 * <p>「どこに」「何バイトで」保存されたかをクライアント側だけで検証できるよう、
 * 保存先の絶対パス・保存先ディレクトリ・そのディレクトリをどの設定から解決したか
 * （{@link #tempDirSource}）まで含めて返す。同じ内容が application.log にも出力される。</p>
 */
public class FileUploadResponse {

    /** 処理結果。正常時は "SUCCESS"。 */
    private String status;

    /** リクエスト単位の識別子。ログの requestId と突き合わせられる。 */
    private String requestId;

    /** リクエスト受信時刻（ISO 8601 / オフセット付き）。 */
    private String receivedAt;

    /** multipart のパート名（curl の -F "<名前>=@..." で指定した名前）。 */
    private String formFieldName;

    /** クライアントが送信した元のファイル名。 */
    private String originalFilename;

    /** クライアントが申告した Content-Type（未指定なら null）。 */
    private String contentType;

    /** 保存したファイルのサイズ（バイト）。 */
    private long sizeBytes;

    /** 保存したファイルのサイズを人が読める単位に整形した値（例 "1.50 MB"）。 */
    private String sizeReadable;

    /** 保存したファイルの SHA-256（送信内容と一致するかの検証用）。 */
    private String sha256;

    /** 実際に保存したファイル名（衝突回避のためタイムスタンプと UUID を付与している）。 */
    private String storedFileName;

    /** 保存したファイルの絶対パス。 */
    private String storedPath;

    /** 保存先ディレクトリの絶対パス（＝AP サーバのテンポラリフォルダ）。 */
    private String storedDirectory;

    /**
     * 保存先ディレクトリをどの設定から解決したか。
     * <ul>
     *   <li>{@code spring.servlet.multipart.location} … アプリ設定で明示指定</li>
     *   <li>{@code servletContext:jakarta.servlet.context.tempdir} … AP サーバがデプロイに割り当てたテンポラリフォルダ</li>
     *   <li>{@code system:java.io.tmpdir} … 上記いずれも取得できなかった場合のフォールバック</li>
     * </ul>
     */
    private String tempDirSource;

    /** 任意の付随メッセージ（curl の -F "note=..." で送った値）。 */
    private String note;

    /** サーバ側の処理時間（ミリ秒）。 */
    private long elapsedMs;

    /** 適用されているアップロード上限。 */
    private UploadLimitsInfo limits;

    public FileUploadResponse() {
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(String receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getFormFieldName() {
        return formFieldName;
    }

    public void setFormFieldName(String formFieldName) {
        this.formFieldName = formFieldName;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSizeReadable() {
        return sizeReadable;
    }

    public void setSizeReadable(String sizeReadable) {
        this.sizeReadable = sizeReadable;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getStoredFileName() {
        return storedFileName;
    }

    public void setStoredFileName(String storedFileName) {
        this.storedFileName = storedFileName;
    }

    public String getStoredPath() {
        return storedPath;
    }

    public void setStoredPath(String storedPath) {
        this.storedPath = storedPath;
    }

    public String getStoredDirectory() {
        return storedDirectory;
    }

    public void setStoredDirectory(String storedDirectory) {
        this.storedDirectory = storedDirectory;
    }

    public String getTempDirSource() {
        return tempDirSource;
    }

    public void setTempDirSource(String tempDirSource) {
        this.tempDirSource = tempDirSource;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public UploadLimitsInfo getLimits() {
        return limits;
    }

    public void setLimits(UploadLimitsInfo limits) {
        this.limits = limits;
    }
}
