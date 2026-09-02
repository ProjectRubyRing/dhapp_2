package com.example.dhapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.dhapp.dto.ConfigSourceResult;
import com.example.dhapp.dto.DateConfigResponse;

/**
 * {@link DateConfigService} の読み込み・比較・overlay 検知の確認。
 *
 * <p>リソース読みの対象は war に同梱する {@code jp/iwin/base/tango/date_config.properties}
 * （テスト実行時もクラスパス上にある）を、ファイル読みの対象は一時ディレクトリに作った
 * ファイルを使う。</p>
 */
class DateConfigServiceTest {

    private static final String RESOURCE_NAME = "jp/iwin/base/tango/date_config.properties";

    @TempDir
    Path tempDir;

    private Path configFile;

    @BeforeEach
    void setUp() throws IOException {
        configFile = tempDir.resolve("date_config.properties");
        Files.writeString(configFile, """
                config.source=file:/webapp/webapp9mf02/serverlets/jp/iwin/base/tango/date_config.properties
                config.origin=filesystem
                config.revision=1
                date.format=yyyy-MM-dd
                date.timezone=Asia/Tokyo
                """, StandardCharsets.UTF_8);
    }

    private DateConfigService service(String filePath) {
        return new DateConfigService(filePath, RESOURCE_NAME, "UTF-8", true,
                new DeploymentOverlayInspector());
    }

    @Test
    void readsBothSourcesAndReportsDifferences() {
        DateConfigResponse response = service(configFile.toString()).read("test-1");

        ConfigSourceResult fileRead = response.getFileRead();
        assertEquals(DateConfigService.READ_TYPE_FILE, fileRead.getReadType());
        assertTrue(fileRead.isLoaded(), "ファイル読みが成功していること");
        assertEquals("filesystem", fileRead.getProperties().get("config.origin"));
        assertNotNull(fileRead.getSha256());

        ConfigSourceResult resourceRead = response.getResourceRead();
        assertEquals(DateConfigService.READ_TYPE_RESOURCE, resourceRead.getReadType());
        assertTrue(resourceRead.isLoaded(), "リソース読みが成功していること");
        assertEquals("war-archive", resourceRead.getProperties().get("config.origin"));
        assertNotNull(resourceRead.getResolvedUrl());

        // 2 経路とも読めたので総合ステータスは SUCCESS、内容は異なるので DIFFERENT。
        assertEquals(DateConfigService.STATUS_SUCCESS, response.getStatus());
        assertEquals(DateConfigService.VERDICT_DIFFERENT, response.getComparison().getVerdict());
        assertFalse(response.getComparison().isSha256Match());
        assertTrue(response.getComparison().getDifferentValues().containsKey("config.origin"));
        assertTrue(response.getComparison().getResourceOnlyKeyCount() > 0,
                "war 側にしか無いキーが検出されること");

        // ログ・コンソールへ出したものと同じレポートがレスポンスにも載る。
        assertTrue(response.getReport().contains("[1] ファイル読み"));
        assertTrue(response.getReport().contains("[2] リソース読み"));
        assertTrue(response.getReport().contains("[3] 比較結果"));
        assertTrue(response.getReport().contains("[4] deployment-overlay の検知"));
    }

    @Test
    void detectsContentChangeBetweenCalls() throws IOException {
        DateConfigService service = service(configFile.toString());

        DateConfigResponse first = service.read("test-2a");
        assertFalse(first.getDeploymentOverlay().isPreviousSnapshotAvailable(),
                "初回は前回の指紋を持たない");

        DateConfigResponse second = service.read("test-2b");
        assertTrue(second.getDeploymentOverlay().isPreviousSnapshotAvailable());
        assertFalse(second.getDeploymentOverlay().isFileContentChanged(), "内容が同じなら変化なし");
        assertFalse(second.getDeploymentOverlay().isResourceContentChanged());

        Files.writeString(configFile, "config.origin=filesystem\nconfig.revision=2\n",
                StandardCharsets.UTF_8);
        DateConfigResponse third = service.read("test-2c");
        assertTrue(third.getDeploymentOverlay().isFileContentChanged(),
                "ファイルを書き換えたら変化として検知されること");
        assertFalse(third.getDeploymentOverlay().isResourceContentChanged(),
                "war 同梱リソースは変わっていない");
    }

    @Test
    void reportsMissingFileWithoutThrowing() {
        DateConfigResponse response =
                service(tempDir.resolve("does-not-exist.properties").toString()).read("test-3");

        assertFalse(response.getFileRead().isExists());
        assertFalse(response.getFileRead().isLoaded());
        assertNotNull(response.getFileRead().getHint());
        assertTrue(response.getResourceRead().isLoaded(), "リソース読みは成功する");
        assertEquals(DateConfigService.STATUS_PARTIAL, response.getStatus());
        assertEquals(DateConfigService.VERDICT_RESOURCE_ONLY, response.getComparison().getVerdict());
    }

    @Test
    void treatsIdenticalContentAsIdentical() throws IOException {
        // war 同梱リソースと全く同じ内容のファイルを置くと SHA-256 まで一致する。
        byte[] resource;
        try (var in = getClass().getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            assertNotNull(in);
            resource = in.readAllBytes();
        }
        Path copy = tempDir.resolve("same.properties");
        Files.write(copy, resource);

        DateConfigResponse response = service(copy.toString()).read("test-4");
        assertEquals(DateConfigService.VERDICT_IDENTICAL, response.getComparison().getVerdict());
        assertTrue(response.getComparison().isSha256Match());
        assertTrue(response.getComparison().isKeySetMatch());
        assertTrue(response.getComparison().isValuesMatch());
    }
}
