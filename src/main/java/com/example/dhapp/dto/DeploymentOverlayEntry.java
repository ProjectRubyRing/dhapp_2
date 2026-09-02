package com.example.dhapp.dto;

import java.util.List;
import java.util.Map;

/**
 * JBoss EAP の deployment-overlay 1 件分（{@code /deployment-overlay=<名前>}）。
 */
public class DeploymentOverlayEntry {

    /** overlay 名（{@code /deployment-overlay=<名前>}）。 */
    private String name;

    /**
     * この overlay が差し替えるデプロイ内のパス一覧
     * （{@code /deployment-overlay=X/content=<パス>}）。
     * war 内のクラスパスリソースを差し替える場合は
     * {@code WEB-INF/classes/jp/iwin/tango/date_config.properties} のような値になる。
     */
    private List<String> contentPaths;

    /** content ごとの属性（content-hash など）。キーは content のパス。 */
    private Map<String, String> contentAttributes;

    /**
     * この overlay を適用するデプロイメント名／正規表現
     * （{@code /deployment-overlay=X/deployment=<名前>}）。
     */
    private List<String> deployments;

    /** このアプリ（実行中のデプロイメント）に適用され得るか。 */
    private boolean appliesToThisDeployment;

    /** 本 API が読む date_config.properties を差し替える content を持つか。 */
    private boolean overridesDateConfig;

    public DeploymentOverlayEntry() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getContentPaths() {
        return contentPaths;
    }

    public void setContentPaths(List<String> contentPaths) {
        this.contentPaths = contentPaths;
    }

    public Map<String, String> getContentAttributes() {
        return contentAttributes;
    }

    public void setContentAttributes(Map<String, String> contentAttributes) {
        this.contentAttributes = contentAttributes;
    }

    public List<String> getDeployments() {
        return deployments;
    }

    public void setDeployments(List<String> deployments) {
        this.deployments = deployments;
    }

    public boolean isAppliesToThisDeployment() {
        return appliesToThisDeployment;
    }

    public void setAppliesToThisDeployment(boolean appliesToThisDeployment) {
        this.appliesToThisDeployment = appliesToThisDeployment;
    }

    public boolean isOverridesDateConfig() {
        return overridesDateConfig;
    }

    public void setOverridesDateConfig(boolean overridesDateConfig) {
        this.overridesDateConfig = overridesDateConfig;
    }
}
