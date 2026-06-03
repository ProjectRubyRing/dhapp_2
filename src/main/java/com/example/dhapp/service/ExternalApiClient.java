package com.example.dhapp.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.example.dhapp.dto.DemoRequest;

/**
 * 設定可能な外部 URL に対して HTTP POST で REST API を呼び出す。
 * URL / 接続タイムアウト / 読取タイムアウト は設定値から取得する。
 *
 * 重要: HTTP 呼び出しはトランザクション資源ではなくロールバックできないため、
 * DB の 2PC には含めない。本クライアントは DB コミット後に呼び出される。
 */
@Service
public class ExternalApiClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiClient.class);

    private final RestClient restClient;
    private final String externalUrl;

    public ExternalApiClient(
            @Value("${app.external-api.url:http://localhost:9090/receive}") String externalUrl,
            @Value("${app.external-api.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${app.external-api.read-timeout-ms:5000}") int readTimeoutMs) {

        this.externalUrl = externalUrl;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * 外部 API を呼び出し、HTTP ステータスコードを返す。
     * exchange() を使うため 4xx/5xx でも例外を投げず、ステータスをそのまま取得する。
     * 接続不可・タイムアウト等の場合は RestClientException(ResourceAccessException) が送出される。
     */
    public int callExternalApi(DemoRequest request, String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("sessionId", request.getSessionId());
        body.put("userId", request.getUserId());
        body.put("message", request.getMessage());
        body.put("requestId", requestId);

        log.info("Calling external API (POST). url={}, requestId={}", externalUrl, requestId);

        int statusCode = restClient.post()
                .uri(externalUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> res.getStatusCode().value());

        log.info("External API responded. status={}, requestId={}", statusCode, requestId);
        return statusCode;
    }
}
