package com.example.dhapp.controller;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.dhapp.dto.DemoRequest;
import com.example.dhapp.dto.DemoResponse;
import com.example.dhapp.service.DemoService;
import com.example.dhapp.service.DummyPdfService;

/**
 * POST /api/demo/execute
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final DemoService demoService;
    private final DummyPdfService dummyPdfService;

    public DemoController(DemoService demoService, DummyPdfService dummyPdfService) {
        this.demoService = demoService;
        this.dummyPdfService = dummyPdfService;
    }

    @PostMapping(value = "/execute",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DemoResponse> execute(@Valid @RequestBody DemoRequest request) {
        long startedAt = System.currentTimeMillis();
        log.info("POST /api/demo/execute received. sessionId={}, userId={}, failMode={}",
                request.getSessionId(), request.getUserId(), request.getFailMode());
        log.debug("POST /api/demo/execute request body detail. sessionId={}, userId={}, messageLen={}, failMode={}",
                request.getSessionId(), request.getUserId(),
                request.getMessage() == null ? 0 : request.getMessage().length(), request.getFailMode());

        // 各 REST API 呼び出し時に DATA_OUTPUT_DIR 配下へ "DUMMY" と記載した PDF を生成する。
        dummyPdfService.createDummyPdf("demo");

        DemoResponse response = demoService.execute(request);

        long elapsedMs = System.currentTimeMillis() - startedAt;
        log.info("POST /api/demo/execute done. requestId={}, status={}, dhcomapInserted={}, dhinfapInserted={}, "
                        + "externalApiStatus={}, sessionKey={}, elapsedMs={}",
                response.getRequestId(), response.getStatus(), response.isDhcomapInserted(),
                response.isDhinfapInserted(), response.getExternalApiStatus(),
                response.getSessionKey(), elapsedMs);
        return ResponseEntity.ok(response);
    }
}
