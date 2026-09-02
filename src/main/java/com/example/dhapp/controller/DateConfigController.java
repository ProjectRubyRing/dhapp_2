package com.example.dhapp.controller;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.dhapp.dto.DateConfigResponse;
import com.example.dhapp.service.DateConfigService;
import com.example.dhapp.service.DummyPdfService;

/**
 * {@code date_config.properties} を<b>ファイル読み</b>と<b>リソース読み</b>の 2 経路で読み、
 * 読み込み状況を比較する API。
 *
 * <ul>
 *   <li>{@code GET  /api/config/date-config} … 2 経路で読み込み、結果と比較を JSON で返す</li>
 *   <li>{@code POST /api/config/date-config} … 同上（他 API と揃えた POST 版）</li>
 *   <li>{@code GET  /api/config/date-config?format=text} … ログ・コンソールと同じ
 *       テキストレポートをそのまま返す（curl でそのまま読める形）</li>
 * </ul>
 *
 * <p>読み込み対象は次の 2 つ。</p>
 * <ol>
 *   <li><b>ファイル読み</b>:
 *       {@code /webapp/webapp9mf02/serverlets/jp/iwin/base/tango/date_config.properties}
 *       （war の外・AP サーバのファイルシステム上）。結果は<b>ログとコンソール</b>へ出力する。</li>
 *   <li><b>リソース読み</b>: クラスパス配下の {@code jp/iwin/base/tango/date_config.properties}
 *       （war にアーカイブ対象として同梱 → {@code WEB-INF/classes/} 配下）。
 *       結果は<b>コンソール</b>へ出力する（ログにも同時に出す）。</li>
 * </ol>
 *
 * <p>あわせて JBoss EAP の deployment-overlay による差し替えが反映されているかも検知する。
 * 読み込みに失敗しても HTTP 500 にはせず 200 + {@code status} で返す
 * （「読めなかったこと」自体が確認したい結果のため。既存の TLS 系 API と同方針）。
 * 利用方法の詳細は {@code CONFIG_READ_API.md} を参照。</p>
 */
@RestController
@RequestMapping("/api/config")
public class DateConfigController {

    private static final Logger log = LoggerFactory.getLogger(DateConfigController.class);

    private final DateConfigService dateConfigService;
    private final DummyPdfService dummyPdfService;

    public DateConfigController(DateConfigService dateConfigService, DummyPdfService dummyPdfService) {
        this.dateConfigService = dateConfigService;
        this.dummyPdfService = dummyPdfService;
    }

    /**
     * ファイル読み・リソース読みを実行し、両者の読み込み状況を比較して返す。
     *
     * <pre>
     * curl -i http://localhost:8080/iwinmichl/api/config/date-config
     * curl -s "http://localhost:8080/iwinmichl/api/config/date-config?format=text"
     * </pre>
     *
     * @param format {@code text} を指定すると、ログ・コンソールと同じテキストレポートを返す
     */
    @GetMapping(value = "/date-config", produces = {MediaType.APPLICATION_JSON_VALUE,
            MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<?> dateConfig(
            @RequestParam(name = "format", required = false) String format) {
        return execute(format);
    }

    /** {@code GET /api/config/date-config} の POST 版（他 API と呼び出し方を揃えるため）。 */
    @PostMapping(value = "/date-config", produces = {MediaType.APPLICATION_JSON_VALUE,
            MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<?> dateConfigByPost(
            @RequestParam(name = "format", required = false) String format) {
        return execute(format);
    }

    private ResponseEntity<?> execute(String format) {
        String requestId = UUID.randomUUID().toString();
        log.info("/api/config/date-config received. requestId={}, filePath={}, resource={}, format={}",
                requestId, dateConfigService.getFilePath(), dateConfigService.getResourceName(), format);

        // 他の REST API と同様、呼び出しごとに DATA_OUTPUT_DIR 配下へダミー PDF を生成する。
        dummyPdfService.createDummyPdf("date-config");

        DateConfigResponse response = dateConfigService.read(requestId);

        log.info("/api/config/date-config done. requestId={}, status={}, verdict={}, "
                        + "fileLoaded={}, resourceLoaded={}, overlayForPath={}, resourceChanged={}, "
                        + "elapsedMs={}",
                requestId, response.getStatus(), response.getComparison().getVerdict(),
                response.getFileRead().isLoaded(), response.getResourceRead().isLoaded(),
                response.getDeploymentOverlay().isDateConfigOverlayDefined(),
                response.getDeploymentOverlay().isResourceContentChanged(),
                response.getElapsedMs());

        if ("text".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                    .body(response.getReport());
        }
        return ResponseEntity.ok(response);
    }
}
