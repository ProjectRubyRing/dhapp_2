package com.example.dhapp.controller;

import java.util.Map;
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

import com.example.dhapp.dto.CacheResponse;
import com.example.dhapp.dto.DemoRequest;
import com.example.dhapp.service.ValkeySessionService;

/**
 * ElastiCache for Valkey のみを確認する API。
 *
 * POST /api/cache/execute
 *
 * ダミーセッションを保存し、保存内容を読み戻して返す（DB・外部 API は呼ばない）。
 * レスポンスの stored で「書いた値が実際に読めること」を確認できる。
 */
@RestController
@RequestMapping("/api/cache")
public class CacheController {

    private static final Logger log = LoggerFactory.getLogger(CacheController.class);

    private final ValkeySessionService valkeySessionService;

    public CacheController(ValkeySessionService valkeySessionService) {
        this.valkeySessionService = valkeySessionService;
    }

    @PostMapping(value = "/execute",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CacheResponse> execute(@Valid @RequestBody DemoRequest request) {
        long startedAt = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        log.info("POST /api/cache/execute received. requestId={}, sessionId={}, userId={}",
                requestId, request.getSessionId(), request.getUserId());
        log.debug("POST /api/cache/execute request body detail. requestId={}, sessionId={}, userId={}, messageLen={}",
                requestId, request.getSessionId(), request.getUserId(),
                request.getMessage() == null ? 0 : request.getMessage().length());

        String sessionKey = valkeySessionService.saveSession(request, requestId);
        Map<String, String> stored = valkeySessionService.getSession(request.getSessionId());
        log.debug("POST /api/cache/execute read-back stored fields={}, keys={}", stored.size(), stored.keySet());

        CacheResponse response = new CacheResponse();
        response.setStatus("SUCCESS");
        response.setRequestId(requestId);
        response.setSessionKey(sessionKey);
        response.setStored(stored);

        long elapsedMs = System.currentTimeMillis() - startedAt;
        log.info("POST /api/cache/execute done. requestId={}, status={}, key={}, storedFields={}, elapsedMs={}",
                requestId, response.getStatus(), sessionKey, stored.size(), elapsedMs);
        return ResponseEntity.ok(response);
    }
}
