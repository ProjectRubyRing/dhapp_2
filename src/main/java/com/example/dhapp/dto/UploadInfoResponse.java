package com.example.dhapp.dto;

/**
 * {@code GET /api/file/upload-info} のレスポンスボディ。
 *
 * <p>ファイルを送らずに「AP サーバのどのテンポラリフォルダへ保存されるか」「どの上限が効いているか」を
 * 事前確認するための API 用。max_post_size 超過テストを行う前に、どのサイズを送れば
 * どちらの上限に当たるかを確認できる。</p>
 */
public class UploadInfoResponse {

    /** 常に "OK"。 */
    private String status;

    /** 保存先ディレクトリの絶対パス（＝AP サーバのテンポラリフォルダ）。 */
    private String tempDirectory;

    /** 保存先ディレクトリをどの設定から解決したか。{@link FileUploadResponse#getTempDirSource()} と同じ。 */
    private String tempDirSource;

    /** 保存先ディレクトリが実在するか。 */
    private boolean tempDirectoryExists;

    /** 保存先ディレクトリへ書き込めるか。 */
    private boolean tempDirectoryWritable;

    /** 適用されているアップロード上限。 */
    private UploadLimitsInfo limits;

    public UploadInfoResponse() {
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTempDirectory() {
        return tempDirectory;
    }

    public void setTempDirectory(String tempDirectory) {
        this.tempDirectory = tempDirectory;
    }

    public String getTempDirSource() {
        return tempDirSource;
    }

    public void setTempDirSource(String tempDirSource) {
        this.tempDirSource = tempDirSource;
    }

    public boolean isTempDirectoryExists() {
        return tempDirectoryExists;
    }

    public void setTempDirectoryExists(boolean tempDirectoryExists) {
        this.tempDirectoryExists = tempDirectoryExists;
    }

    public boolean isTempDirectoryWritable() {
        return tempDirectoryWritable;
    }

    public void setTempDirectoryWritable(boolean tempDirectoryWritable) {
        this.tempDirectoryWritable = tempDirectoryWritable;
    }

    public UploadLimitsInfo getLimits() {
        return limits;
    }

    public void setLimits(UploadLimitsInfo limits) {
        this.limits = limits;
    }
}
