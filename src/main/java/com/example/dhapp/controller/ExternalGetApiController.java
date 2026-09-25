package com.example.dhapp.controller;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import com.example.dhapp.dto.ExternalGetApiResponse;
import com.example.dhapp.service.ExternalGetApiClient;
import com.example.dhapp.service.ExternalGetApiClient.GetResult;

/**
 * 外部 REST API への HTTP GET を確認する API。
 *
 * GET /api/external-get/execute
 *
 * 設定された外部 URL（{@code app.external-get-api.url}）に HTTP GET し、HTTP ステータスと
 * レスポンス本文の先頭を返す（Valkey・DB は呼ばない）。HTTP POST の外部 API
 * （{@code /api/external/execute}・{@code app.external-api.*}）とは別コントローラ・別設定。
 * 接続不可・タイムアウト時は 500 ではなく、status=EXTERNAL_API_FAILED とエラー内容をボディで返す
 * （検証 API として結果を読み取りやすくするため）。
 */
@RestController
@RequestMapping("/api/external-get")
public class ExternalGetApiController {

    private static final Logger log = LoggerFactory.getLogger(ExternalGetApiController.class);

    private final ExternalGetApiClient externalGetApiClient;

    public ExternalGetApiController(ExternalGetApiClient externalGetApiClient) {
        this.externalGetApiClient = externalGetApiClient;
    }

    @GetMapping(value = "/execute", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExternalGetApiResponse> execute() {
        long startedAt = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        log.info("GET /api/external-get/execute received. requestId={}, url={}",
                requestId, externalGetApiClient.getUrl());

        ExternalGetApiResponse response = new ExternalGetApiResponse();
        response.setRequestId(requestId);
        response.setUrl(externalGetApiClient.getUrl());
        try {
            GetResult result = externalGetApiClient.callExternalApi(requestId);
            response.setExternalApiStatus(result.statusCode());
            response.setContentType(result.contentType());
            response.setContentLength(result.contentLength());
            response.setBodyHead(result.bodyHead());
            response.setBodyHeadChars(result.bodyHead().length());
            response.setBodyTruncated(result.bodyTruncated());
            response.setStatus("SUCCESS");
        } catch (RestClientException | IllegalArgumentException e) {
            log.error("External GET API call failed. requestId={}, url={}",
                    requestId, externalGetApiClient.getUrl(), e);
            response.setStatus("EXTERNAL_API_FAILED");
            response.setMessage(e.getMessage());
        }

        long elapsedMs = System.currentTimeMillis() - startedAt;
        response.setElapsedMs(elapsedMs);
        log.info("GET /api/external-get/execute done. requestId={}, status={}, externalApiStatus={}, "
                        + "bodyHeadChars={}, bodyTruncated={}, elapsedMs={}",
                requestId, response.getStatus(), response.getExternalApiStatus(),
                response.getBodyHeadChars(), response.getBodyTruncated(), elapsedMs);
        return ResponseEntity.ok(response);
    }
}
