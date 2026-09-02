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

import com.example.dhapp.dto.SecureApiCallResponse;
import com.example.dhapp.dto.TrustStoresResponse;
import com.example.dhapp.service.DummyPdfService;
import com.example.dhapp.service.SecureApiTlsService;

/**
 * <b>JVM が管理するトラストストア</b>と<b>JBoss EAP(Elytron) が管理するトラストストア</b>の
 * 証明書を使って、compose の {@code secure-api} サービスへ HTTPS 接続し、結果を比較する API。
 *
 * <ul>
 *   <li>{@code GET  /api/secure-api/call} … 2 系統のトラストストアで接続し、詳細を JSON で返す</li>
 *   <li>{@code POST /api/secure-api/call} … 同上（他 API と揃えた POST 版）</li>
 *   <li>{@code GET  /api/secure-api/call?format=text} … ログ・コンソールと同じテキストレポートを返す</li>
 *   <li>{@code GET  /api/secure-api/truststores} … 接続せず、両ストアの中身と elytron の登録状態を返す</li>
 * </ul>
 *
 * <p>接続先の既定値は別リポジトリ {@code Container_Compose_file} の compose 定義に合わせている。</p>
 * <pre>
 * target=direct（既定） … https://secure-api:8443/api/v1/ping   （WireMock。--disable-http で HTTPS 必須）
 * target=alb            … https://alb/secure/v1/ping            （ALB で TLS 終端 → secure-api へ再暗号化）
 * </pre>
 *
 * <p>接続に失敗しても HTTP 500 にはせず 200 + {@code status} で返す
 * （「どちらのストアで失敗したか」を読み取るための API のため）。
 * 利用方法の詳細は {@code SECURE_API_TLS.md} を参照。</p>
 */
@RestController
@RequestMapping("/api/secure-api")
public class SecureApiController {

    private static final Logger log = LoggerFactory.getLogger(SecureApiController.class);

    private final SecureApiTlsService secureApiTlsService;
    private final DummyPdfService dummyPdfService;

    public SecureApiController(SecureApiTlsService secureApiTlsService,
            DummyPdfService dummyPdfService) {
        this.secureApiTlsService = secureApiTlsService;
        this.dummyPdfService = dummyPdfService;
    }

    /**
     * JVM 側・JBoss EAP 側のトラストストアそれぞれで secure-api へ HTTPS 接続する。
     *
     * <pre>
     * # 2 系統（JVM / JBoss EAP）で secure-api へ直接接続
     * curl -i http://localhost:8080/iwinmichl/api/secure-api/call
     *
     * # 画面表示用のテキストレポート（ログ・コンソール出力と同じ内容）
     * curl -s "http://localhost:8080/iwinmichl/api/secure-api/call?format=text"
     *
     * # ALB 経由 / 対照実験（空ストア）込み / 任意 URL
     * curl -s "http://localhost:8080/iwinmichl/api/secure-api/call?target=alb"
     * curl -s "http://localhost:8080/iwinmichl/api/secure-api/call?trust=all"
     * curl -s "http://localhost:8080/iwinmichl/api/secure-api/call?url=https://secure-api:8443/api/v1/health"
     * </pre>
     *
     * @param url    接続先 URL。省略時は {@code target} で決まる既定 URL
     * @param target {@code direct}（既定） / {@code alb}
     * @param trust  {@code jvm,jboss}（既定） / {@code all}（対照実験の空ストアも実行） / 個別指定
     * @param method HTTP メソッド（既定 GET）
     * @param body   GET 以外のときに送るボディ
     * @param format {@code text} でテキストレポートを返す
     */
    @GetMapping(value = "/call", produces = {MediaType.APPLICATION_JSON_VALUE,
            MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<?> call(
            @RequestParam(name = "url", required = false) String url,
            @RequestParam(name = "target", required = false) String target,
            @RequestParam(name = "trust", required = false) String trust,
            @RequestParam(name = "method", required = false) String method,
            @RequestParam(name = "body", required = false) String body,
            @RequestParam(name = "contentType", required = false) String contentType,
            @RequestParam(name = "format", required = false) String format) {
        return execute(url, target, trust, method, body, contentType, format);
    }

    /** {@code GET /api/secure-api/call} の POST 版。 */
    @PostMapping(value = "/call", produces = {MediaType.APPLICATION_JSON_VALUE,
            MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<?> callByPost(
            @RequestParam(name = "url", required = false) String url,
            @RequestParam(name = "target", required = false) String target,
            @RequestParam(name = "trust", required = false) String trust,
            @RequestParam(name = "method", required = false) String method,
            @RequestParam(name = "body", required = false) String body,
            @RequestParam(name = "contentType", required = false) String contentType,
            @RequestParam(name = "format", required = false) String format) {
        return execute(url, target, trust, method, body, contentType, format);
    }

    private ResponseEntity<?> execute(String url, String target, String trust, String method,
            String body, String contentType, String format) {

        String requestId = UUID.randomUUID().toString();
        log.info("/api/secure-api/call received. requestId={}, url={}, target={}, trust={}, method={}",
                requestId, url, target, trust, method);

        // 他の REST API と同様、呼び出しごとに DATA_OUTPUT_DIR 配下へダミー PDF を生成する。
        dummyPdfService.createDummyPdf("secure-api-call");

        SecureApiCallResponse response =
                secureApiTlsService.call(url, target, trust, method, body, contentType, requestId);

        log.info("/api/secure-api/call done. requestId={}, status={}, url={}, jvm={}, jbossEap={}, "
                        + "bothSucceeded={}, elapsedMs={}",
                requestId, response.getStatus(), response.getUrl(),
                response.getComparison().getJvmStatus(), response.getComparison().getJbossStatus(),
                response.getComparison().isBothSucceeded(), response.getElapsedMs());

        if ("text".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                    .body(response.getReport());
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 接続はせず、JVM 側・JBoss EAP 側のトラストストアの中身と elytron の登録状態を返す。
     *
     * <pre>
     * curl -i http://localhost:8080/iwinmichl/api/secure-api/truststores
     * curl -s "http://localhost:8080/iwinmichl/api/secure-api/truststores?format=text"
     * </pre>
     */
    @GetMapping(value = "/truststores", produces = {MediaType.APPLICATION_JSON_VALUE,
            MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<?> trustStores(
            @RequestParam(name = "format", required = false) String format) {

        String requestId = UUID.randomUUID().toString();
        log.info("/api/secure-api/truststores received. requestId={}", requestId);

        dummyPdfService.createDummyPdf("secure-api-truststores");

        TrustStoresResponse response = secureApiTlsService.inspectTrustStores(requestId);

        log.info("/api/secure-api/truststores done. requestId={}, jvmLoaded={}, jbossLoaded={}",
                requestId, response.getJvm().isLoaded(), response.getJbossEap().isLoaded());

        if ("text".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                    .body(response.getReport());
        }
        return ResponseEntity.ok(response);
    }
}
