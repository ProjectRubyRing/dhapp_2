package com.example.dhapp.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import com.example.dhapp.dto.DemoRequest;
import com.example.dhapp.dto.ExternalApiResponse;
import com.example.dhapp.service.ExternalApiClient;

/**
 * 外部 REST API の呼び出しのみを確認する API。
 *
 * POST /api/external/execute
 *
 * 設定された外部 URL に HTTP POST し、その結果（HTTP ステータス）を返す（Valkey・DB は呼ばない）。
 * 接続不可・タイムアウト時は 500 ではなく、status=EXTERNAL_API_FAILED とエラー内容をボディで返す
 * （検証 API として結果を読み取りやすくするため）。
 */
@RestController
@RequestMapping("/api/external")
public class ExternalApiController {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiController.class);

    private final ExternalApiClient externalApiClient;

    public ExternalApiController(ExternalApiClient externalApiClient) {
        this.externalApiClient = externalApiClient;
    }

    @PostMapping(value = "/execute",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExternalApiResponse> execute(@Valid @RequestBody DemoRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.info("POST /api/external/execute received. requestId={}, sessionId={}", requestId, request.getSessionId());

        ExternalApiResponse response = new ExternalApiResponse();
        response.setRequestId(requestId);
        try {
            int statusCode = externalApiClient.callExternalApi(request, requestId);
            response.setExternalApiStatus(statusCode);
            response.setStatus("SUCCESS");
        } catch (RestClientException e) {
            log.error("External API call failed. requestId={}", requestId, e);
            response.setExternalApiStatus(null);
            response.setStatus("EXTERNAL_API_FAILED");
            response.setMessage(e.getMessage());
        }

        log.info("POST /api/external/execute done. requestId={}, status={}", requestId, response.getStatus());
        return ResponseEntity.ok(response);
    }
}
