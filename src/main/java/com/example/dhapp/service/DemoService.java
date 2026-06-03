package com.example.dhapp.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.example.dhapp.dto.DemoRequest;
import com.example.dhapp.dto.DemoResponse;

/**
 * 1 回の API 呼び出しで実行する一連の処理をオーケストレーションする。
 *
 * 処理順序:
 *   1) Valkey にダミーセッションを保存（非トランザクション）
 *   2)-4) DHCOMAP / DHINFAP へ 2PC で INSERT（TransactionalDbService に委譲し、ここでコミット完了）
 *   5) 外部 REST API を呼び出し（DB コミット後 = トランザクション外）
 *
 * この Bean 自体は @Transactional を付けない。
 * 2PC の境界は TransactionalDbService.insertIntoBothDatabases に閉じ込め、
 * 外部 API 呼び出しはその外側に置くことで「HTTP をトランザクションに巻き込まない」設計とする。
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    private final ValkeySessionService valkeySessionService;
    private final TransactionalDbService transactionalDbService;
    private final ExternalApiClient externalApiClient;

    public DemoService(ValkeySessionService valkeySessionService,
                       TransactionalDbService transactionalDbService,
                       ExternalApiClient externalApiClient) {
        this.valkeySessionService = valkeySessionService;
        this.transactionalDbService = transactionalDbService;
        this.externalApiClient = externalApiClient;
    }

    public DemoResponse execute(DemoRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.info("execute start. requestId={}, sessionId={}", requestId, request.getSessionId());

        // 1) Valkey へダミーセッション保存
        String sessionKey = valkeySessionService.saveSession(request, requestId);

        // 2)-4) DHCOMAP / DHINFAP への 2PC INSERT（例外時はここで両方ロールバックされ、上位に伝播）
        transactionalDbService.insertIntoBothDatabases(request, requestId);

        DemoResponse response = new DemoResponse();
        response.setRequestId(requestId);
        response.setSessionKey(sessionKey);
        response.setDhcomapInserted(true);
        response.setDhinfapInserted(true);

        // 5) 外部 REST API 呼び出し（DB コミット後）
        try {
            int externalStatus = externalApiClient.callExternalApi(request, requestId);
            response.setExternalApiStatus(externalStatus);
            response.setStatus("SUCCESS");
        } catch (RestClientException e) {
            // DB は既にコミット済み。外部 API のみ失敗 = 結果整合性のギャップが生じる。
            // 本番では Outbox パターン等で後追い配信・補償すべき（詳細は本番運用上の注意点を参照）。
            log.error("External API call failed AFTER DB commit (consistency gap). requestId={}", requestId, e);
            response.setExternalApiStatus(null);
            response.setStatus("EXTERNAL_API_FAILED");
        }

        log.info("execute end. requestId={}, status={}", requestId, response.getStatus());
        return response;
    }
}
