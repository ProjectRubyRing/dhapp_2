package com.example.dhapp.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.dhapp.dto.TlsCallRequest;
import com.example.dhapp.dto.TlsCallResponse;
import com.example.dhapp.dto.TlsConfigResponse;
import com.example.dhapp.service.TlsConfigCheckService;
import com.example.dhapp.service.TlsHttpsClient;

/**
 * 自己署名証明書（{@code cacert.crt}）による HTTPS 通信と、その設定状態を確認する API。
 *
 * <ul>
 *   <li>{@code POST /api/tls/call} … 指定 URL へ、JVM のトラストストアに登録された
 *       cacert.crt でサーバ証明書を検証しながら HTTPS 通信する</li>
 *   <li>{@code GET /api/tls/call?url=...} … 同上（curl から 1 行で叩けるようにした簡易版）</li>
 *   <li>{@code GET /api/tls/config} … トラストストア／トラストマネージャー／
 *       クライアント SSL コンテキスト／JVM 既定 SSL コンテキストの設定が正しいかを確認する</li>
 * </ul>
 *
 * <p>前提: 自己署名証明書は JVM のトラストストアに追加済みで、JBoss EAP の standalone 起動時に
 * {@code -Djavax.net.ssl.trustStore} / {@code -Djavax.net.ssl.trustStorePassword} で
 * 渡されている。elytron への登録は {@code wildfly/configure-truststore.cli} で行う。</p>
 *
 * <p>通信失敗・設定不備でも HTTP 500 にはせず 200 + {@code status} で返す
 * （検証 API として結果を読み取りやすくするため。既存の {@code /api/external/execute} と同方針）。
 * 利用方法の詳細は {@code TLS_SELFSIGNED_API.md} を参照。</p>
 */
@RestController
@RequestMapping("/api/tls")
public class TlsController {

    private static final Logger log = LoggerFactory.getLogger(TlsController.class);

    private final TlsHttpsClient tlsHttpsClient;
    private final TlsConfigCheckService tlsConfigCheckService;

    public TlsController(TlsHttpsClient tlsHttpsClient,
            TlsConfigCheckService tlsConfigCheckService) {
        this.tlsHttpsClient = tlsHttpsClient;
        this.tlsConfigCheckService = tlsConfigCheckService;
    }

    /**
     * 指定 URL へ HTTPS 通信し、TLS ハンドシェイクの内容（プロトコル・暗号スイート・
     * サーバ証明書チェーン）と HTTP 応答を返す。
     *
     * <pre>
     * curl -i -X POST http://localhost:8080/iwinmichl/api/tls/call \
     *   -H 'Content-Type: application/json' \
     *   -d '{"url":"https://example.internal:8443/health"}'
     * </pre>
     */
    @PostMapping(value = "/call",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TlsCallResponse> call(@Valid @RequestBody TlsCallRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.info("POST /api/tls/call received. requestId={}, url={}, method={}",
                requestId, request.getUrl(), request.getMethod());

        TlsCallResponse response = tlsHttpsClient.call(request.getUrl(), request.getMethod(),
                request.getBody(), request.getContentType(), requestId);

        log.info("POST /api/tls/call done. requestId={}, status={}, httpStatus={}, verifiedByTrustStore={}, "
                        + "elapsedMs={}",
                requestId, response.getStatus(), response.getHttpStatus(),
                response.isVerifiedByTrustStore(), response.getElapsedMs());
        return ResponseEntity.ok(response);
    }

    /**
     * {@code POST /api/tls/call} の GET 版。ボディを組み立てずに curl 1 行で確認したいとき用。
     *
     * <pre>
     * curl -i "http://localhost:8080/iwinmichl/api/tls/call?url=https://example.internal:8443/health"
     * </pre>
     *
     * @param url 接続先。省略時は {@code app.tls.target-url}
     */
    @GetMapping(value = "/call", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TlsCallResponse> callByGet(
            @RequestParam(name = "url", required = false) String url) {

        String requestId = UUID.randomUUID().toString();
        log.info("GET /api/tls/call received. requestId={}, url={}", requestId, url);

        TlsCallResponse response = tlsHttpsClient.call(url, "GET", null, null, requestId);

        log.info("GET /api/tls/call done. requestId={}, status={}, httpStatus={}, verifiedByTrustStore={}, "
                        + "elapsedMs={}",
                requestId, response.getStatus(), response.getHttpStatus(),
                response.isVerifiedByTrustStore(), response.getElapsedMs());
        return ResponseEntity.ok(response);
    }

    /**
     * トラストストア／elytron の設定が正しいかを確認する。
     *
     * <pre>
     * curl -i http://localhost:8080/iwinmichl/api/tls/config
     * curl -i "http://localhost:8080/iwinmichl/api/tls/config?probe=true"
     * </pre>
     *
     * @param probe true にすると {@code app.tls.target-url} へ実際に TLS ハンドシェイクして、
     *              設定が実通信に効いているかまで確認する（既定 false）
     */
    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TlsConfigResponse> config(
            @RequestParam(name = "probe", defaultValue = "false") boolean probe) {

        String requestId = UUID.randomUUID().toString();
        log.info("GET /api/tls/config received. requestId={}, probe={}", requestId, probe);

        TlsConfigResponse response = tlsConfigCheckService.check(probe, requestId);

        log.info("GET /api/tls/config done. requestId={}, status={}, ok={}, ng={}, unknown={}, elapsedMs={}",
                requestId, response.getStatus(), response.getOkCount(), response.getNgCount(),
                response.getUnknownCount(), response.getElapsedMs());
        return ResponseEntity.ok(response);
    }
}
