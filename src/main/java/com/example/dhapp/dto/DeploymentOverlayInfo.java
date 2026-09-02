package com.example.dhapp.dto;

import java.util.List;

/**
 * JBoss EAP の deployment-overlay による差し替えが効いているかの検知結果。
 *
 * <p>2 つの経路で検知する。どちらか一方でも反応すれば overlay の反映と判断できる。</p>
 * <ol>
 *   <li><b>管理モデル</b>（JMX ファサード {@code jboss.as:deployment-overlay=*}）を読み、
 *       overlay の定義とリンク先デプロイメントを列挙する（設定として存在するか）</li>
 *   <li><b>実際に読めた内容の指紋</b>（解決 URL・SHA-256・最終更新時刻）を前回呼び出し時と
 *       比較する（実際に差し替わったか）</li>
 * </ol>
 */
public class DeploymentOverlayInfo {

    /** 管理モデルを読めたか。false の場合は {@code unavailableReason} を参照。 */
    private boolean managementModelAvailable;

    /** 管理モデルを読めなかった理由。 */
    private String unavailableReason;

    /** 実行中のデプロイメント名（例: {@code dhapp.war}）。 */
    private String deploymentName;

    /** サーバに定義されている overlay 名の一覧。 */
    private List<String> overlayNames;

    /** overlay の詳細。 */
    private List<DeploymentOverlayEntry> overlays;

    /** このデプロイメントに適用される overlay があるか。 */
    private boolean overlayAppliedToThisDeployment;

    /** date_config.properties を差し替える overlay があるか。 */
    private boolean dateConfigOverlayDefined;

    /** date_config.properties を差し替えている overlay 名。 */
    private List<String> dateConfigOverlayNames;

    // --- 前回呼び出しとの比較（実際に反映されたかの検知） ---

    /** 前回の呼び出し結果を保持しているか（false なら今回が初回）。 */
    private boolean previousSnapshotAvailable;

    /** 前回呼び出しの時刻（ISO-8601）。 */
    private String previousObservedAt;

    /** ファイル読みの内容が前回から変わったか。 */
    private boolean fileContentChanged;

    /** リソース読みの内容が前回から変わったか（overlay 反映の主シグナル）。 */
    private boolean resourceContentChanged;

    /** リソースの解決先 URL が前回から変わったか。 */
    private boolean resourceUrlChanged;

    /** 前回のリソース SHA-256。 */
    private String previousResourceSha256;

    /** 前回のファイル SHA-256。 */
    private String previousFileSha256;

    /** 検知結果の説明（日本語 1〜2 行。ログ・画面表示用）。 */
    private String detectionSummary;

    public DeploymentOverlayInfo() {
    }

    public boolean isManagementModelAvailable() {
        return managementModelAvailable;
    }

    public void setManagementModelAvailable(boolean managementModelAvailable) {
        this.managementModelAvailable = managementModelAvailable;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    public void setUnavailableReason(String unavailableReason) {
        this.unavailableReason = unavailableReason;
    }

    public String getDeploymentName() {
        return deploymentName;
    }

    public void setDeploymentName(String deploymentName) {
        this.deploymentName = deploymentName;
    }

    public List<String> getOverlayNames() {
        return overlayNames;
    }

    public void setOverlayNames(List<String> overlayNames) {
        this.overlayNames = overlayNames;
    }

    public List<DeploymentOverlayEntry> getOverlays() {
        return overlays;
    }

    public void setOverlays(List<DeploymentOverlayEntry> overlays) {
        this.overlays = overlays;
    }

    public boolean isOverlayAppliedToThisDeployment() {
        return overlayAppliedToThisDeployment;
    }

    public void setOverlayAppliedToThisDeployment(boolean overlayAppliedToThisDeployment) {
        this.overlayAppliedToThisDeployment = overlayAppliedToThisDeployment;
    }

    public boolean isDateConfigOverlayDefined() {
        return dateConfigOverlayDefined;
    }

    public void setDateConfigOverlayDefined(boolean dateConfigOverlayDefined) {
        this.dateConfigOverlayDefined = dateConfigOverlayDefined;
    }

    public List<String> getDateConfigOverlayNames() {
        return dateConfigOverlayNames;
    }

    public void setDateConfigOverlayNames(List<String> dateConfigOverlayNames) {
        this.dateConfigOverlayNames = dateConfigOverlayNames;
    }

    public boolean isPreviousSnapshotAvailable() {
        return previousSnapshotAvailable;
    }

    public void setPreviousSnapshotAvailable(boolean previousSnapshotAvailable) {
        this.previousSnapshotAvailable = previousSnapshotAvailable;
    }

    public String getPreviousObservedAt() {
        return previousObservedAt;
    }

    public void setPreviousObservedAt(String previousObservedAt) {
        this.previousObservedAt = previousObservedAt;
    }

    public boolean isFileContentChanged() {
        return fileContentChanged;
    }

    public void setFileContentChanged(boolean fileContentChanged) {
        this.fileContentChanged = fileContentChanged;
    }

    public boolean isResourceContentChanged() {
        return resourceContentChanged;
    }

    public void setResourceContentChanged(boolean resourceContentChanged) {
        this.resourceContentChanged = resourceContentChanged;
    }

    public boolean isResourceUrlChanged() {
        return resourceUrlChanged;
    }

    public void setResourceUrlChanged(boolean resourceUrlChanged) {
        this.resourceUrlChanged = resourceUrlChanged;
    }

    public String getPreviousResourceSha256() {
        return previousResourceSha256;
    }

    public void setPreviousResourceSha256(String previousResourceSha256) {
        this.previousResourceSha256 = previousResourceSha256;
    }

    public String getPreviousFileSha256() {
        return previousFileSha256;
    }

    public void setPreviousFileSha256(String previousFileSha256) {
        this.previousFileSha256 = previousFileSha256;
    }

    public String getDetectionSummary() {
        return detectionSummary;
    }

    public void setDetectionSummary(String detectionSummary) {
        this.detectionSummary = detectionSummary;
    }
}
