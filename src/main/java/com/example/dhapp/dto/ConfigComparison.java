package com.example.dhapp.dto;

import java.util.List;
import java.util.Map;

/**
 * ファイル読みとリソース読みの結果を突き合わせた比較結果。
 *
 * <p>「両方読めた／片方しか読めない」「同じ内容か」「どのキーが違うか」を
 * この 1 オブジェクトだけ見れば判断できるようにしている。</p>
 */
public class ConfigComparison {

    /**
     * 比較の総合判定。
     * <ul>
     *   <li>{@code IDENTICAL} … バイト列レベルで一致</li>
     *   <li>{@code SAME_PROPERTIES} … バイト列は違うが、プロパティの集合は一致
     *       （コメント・改行コード・並び順だけの差）</li>
     *   <li>{@code DIFFERENT} … プロパティに差分がある</li>
     *   <li>{@code FILE_ONLY} / {@code RESOURCE_ONLY} … 片方しか読めなかった</li>
     *   <li>{@code BOTH_UNAVAILABLE} … どちらも読めなかった</li>
     * </ul>
     */
    private String verdict;

    /** 判定を日本語 1 行で説明したもの（ログ・画面表示用）。 */
    private String summary;

    private boolean fileReadSucceeded;
    private boolean resourceReadSucceeded;

    /** 内容（バイト列）の SHA-256 が一致するか。 */
    private boolean sha256Match;

    /** プロパティのキー集合が一致するか。 */
    private boolean keySetMatch;

    /** 全キーの値が一致するか。 */
    private boolean valuesMatch;

    private int fileOnlyKeyCount;
    private int resourceOnlyKeyCount;
    private int differentValueCount;
    private int commonKeyCount;

    /** ファイル読み側にしか無いキー。 */
    private List<String> keysOnlyInFile;

    /** リソース読み側にしか無いキー。 */
    private List<String> keysOnlyInResource;

    /**
     * 値が異なるキー。値は {@code "file=<値> / resource=<値>"} 形式。
     */
    private Map<String, String> differentValues;

    public ConfigComparison() {
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public boolean isFileReadSucceeded() {
        return fileReadSucceeded;
    }

    public void setFileReadSucceeded(boolean fileReadSucceeded) {
        this.fileReadSucceeded = fileReadSucceeded;
    }

    public boolean isResourceReadSucceeded() {
        return resourceReadSucceeded;
    }

    public void setResourceReadSucceeded(boolean resourceReadSucceeded) {
        this.resourceReadSucceeded = resourceReadSucceeded;
    }

    public boolean isSha256Match() {
        return sha256Match;
    }

    public void setSha256Match(boolean sha256Match) {
        this.sha256Match = sha256Match;
    }

    public boolean isKeySetMatch() {
        return keySetMatch;
    }

    public void setKeySetMatch(boolean keySetMatch) {
        this.keySetMatch = keySetMatch;
    }

    public boolean isValuesMatch() {
        return valuesMatch;
    }

    public void setValuesMatch(boolean valuesMatch) {
        this.valuesMatch = valuesMatch;
    }

    public int getFileOnlyKeyCount() {
        return fileOnlyKeyCount;
    }

    public void setFileOnlyKeyCount(int fileOnlyKeyCount) {
        this.fileOnlyKeyCount = fileOnlyKeyCount;
    }

    public int getResourceOnlyKeyCount() {
        return resourceOnlyKeyCount;
    }

    public void setResourceOnlyKeyCount(int resourceOnlyKeyCount) {
        this.resourceOnlyKeyCount = resourceOnlyKeyCount;
    }

    public int getDifferentValueCount() {
        return differentValueCount;
    }

    public void setDifferentValueCount(int differentValueCount) {
        this.differentValueCount = differentValueCount;
    }

    public int getCommonKeyCount() {
        return commonKeyCount;
    }

    public void setCommonKeyCount(int commonKeyCount) {
        this.commonKeyCount = commonKeyCount;
    }

    public List<String> getKeysOnlyInFile() {
        return keysOnlyInFile;
    }

    public void setKeysOnlyInFile(List<String> keysOnlyInFile) {
        this.keysOnlyInFile = keysOnlyInFile;
    }

    public List<String> getKeysOnlyInResource() {
        return keysOnlyInResource;
    }

    public void setKeysOnlyInResource(List<String> keysOnlyInResource) {
        this.keysOnlyInResource = keysOnlyInResource;
    }

    public Map<String, String> getDifferentValues() {
        return differentValues;
    }

    public void setDifferentValues(Map<String, String> differentValues) {
        this.differentValues = differentValues;
    }
}
