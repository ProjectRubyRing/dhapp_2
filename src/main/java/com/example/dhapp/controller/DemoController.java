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

/**
 * POST /api/demo/execute
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final DemoService demoService;

    public DemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    @PostMapping(value = "/execute",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DemoResponse> execute(@Valid @RequestBody DemoRequest request) {
        log.info("POST /api/demo/execute received. sessionId={}", request.getSessionId());
        DemoResponse response = demoService.execute(request);
        return ResponseEntity.ok(response);
    }
}
