package com.example.dhapp.controller;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.dhapp.dto.SqsSendResponse;
import com.example.dhapp.service.SqsSendService;

/**
 * Amazon SQS への送信を確認する API。
 *
 * POST /api/sqs/send
 *
 * 設定されたキュー（{@code app.sqs.queue-url}）へ、半角スペース 1 文字をメッセージ本文として
 * SendMessage し、MessageId と SQS が返した本文の MD5 を返す（リクエストボディは不要）。
 * 送信失敗・未設定時も 500 ではなく、status=SQS_SEND_FAILED / SQS_NOT_CONFIGURED と
 * エラー内容をボディで返す（検証 API として結果を読み取りやすくするため）。
 */
@RestController
@RequestMapping("/api/sqs")
public class SqsController {

    private static final Logger log = LoggerFactory.getLogger(SqsController.class);

    private final SqsSendService sqsSendService;

    public SqsController(SqsSendService sqsSendService) {
        this.sqsSendService = sqsSendService;
    }

    @PostMapping(value = "/send", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SqsSendResponse> send() {
        String requestId = UUID.randomUUID().toString();
        log.info("POST /api/sqs/send received. requestId={}, queueUrl={}", requestId, sqsSendService.getQueueUrl());

        SqsSendResponse response = sqsSendService.send(requestId);

        log.info("POST /api/sqs/send done. requestId={}, status={}, messageId={}, md5Matched={}, elapsedMs={}",
                requestId, response.getStatus(), response.getMessageId(), response.getMd5Matched(),
                response.getElapsedMs());
        return ResponseEntity.ok(response);
    }
}
