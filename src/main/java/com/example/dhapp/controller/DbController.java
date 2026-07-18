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

import com.example.dhapp.dto.DbResponse;
import com.example.dhapp.dto.DemoRequest;
import com.example.dhapp.service.TransactionalDbService;

/**
 * データベース部分のみを確認する API。
 *
 * POST /api/db/execute
 *
 * DHCOMAP と DHINFAP への 2PC INSERT のみを実行する（Valkey・外部 API は呼ばない）。
 * failMode（AFTER_DHCOMAP / AFTER_DHINFAP）を指定すると 2PC ロールバックを単体で検証できる。
 */
@RestController
@RequestMapping("/api/db")
public class DbController {

    private static final Logger log = LoggerFactory.getLogger(DbController.class);

    private final TransactionalDbService transactionalDbService;

    public DbController(TransactionalDbService transactionalDbService) {
        this.transactionalDbService = transactionalDbService;
    }

    @PostMapping(value = "/execute",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DbResponse> execute(@Valid @RequestBody DemoRequest request) {
        long startedAt = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        log.info("POST /api/db/execute received. requestId={}, sessionId={}, userId={}, failMode={}",
                requestId, request.getSessionId(), request.getUserId(), request.getFailMode());
        log.debug("POST /api/db/execute request body detail. requestId={}, sessionId={}, userId={}, messageLen={}, failMode={}",
                requestId, request.getSessionId(), request.getUserId(),
                request.getMessage() == null ? 0 : request.getMessage().length(), request.getFailMode());

        // 2PC INSERT。例外時は両方ロールバックされ、GlobalExceptionHandler が 500 を返す。
        transactionalDbService.insertIntoBothDatabases(request, requestId);

        DbResponse response = new DbResponse();
        response.setStatus("SUCCESS");
        response.setRequestId(requestId);
        response.setDhcomapInserted(true);
        response.setDhinfapInserted(true);

        long elapsedMs = System.currentTimeMillis() - startedAt;
        log.info("POST /api/db/execute done. requestId={}, status={}, dhcomapInserted={}, dhinfapInserted={}, elapsedMs={}",
                requestId, response.getStatus(), response.isDhcomapInserted(), response.isDhinfapInserted(), elapsedMs);
        return ResponseEntity.ok(response);
    }
}
