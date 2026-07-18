package com.example.dhapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.dhapp.dto.DemoRequest;
import com.example.dhapp.exception.DemoException;

/**
 * DHCOMAP と DHINFAP の両方へ 1 件ずつ INSERT する 2PC トランザクション境界。
 *
 * このメソッドは Spring の AOP プロキシ経由で呼ばれる必要があるため、
 * オーケストレーション(DemoService)とは別 Bean に分離している（自己呼び出しだと
 * @Transactional が効かないため）。
 *
 * @Transactional によって JtaTransactionManager がグローバルトランザクションを開始し、
 * 2 つの WildFly XA データソースが自動 enlist され、正常終了時に 2 フェーズコミットされる。
 * 途中で RuntimeException が発生した場合は両方の INSERT がロールバックされる。
 */
@Service
public class TransactionalDbService {

    private static final Logger log = LoggerFactory.getLogger(TransactionalDbService.class);

    private static final String INSERT_SQL =
            "INSERT INTO demo_transaction_log "
            + "(request_id, session_id, user_id, message) VALUES (?, ?, ?, ?)";

    private final JdbcTemplate dhcomapJdbcTemplate;
    private final JdbcTemplate dhinfapJdbcTemplate;

    public TransactionalDbService(
            @Qualifier("dhcomapJdbcTemplate") JdbcTemplate dhcomapJdbcTemplate,
            @Qualifier("dhinfapJdbcTemplate") JdbcTemplate dhinfapJdbcTemplate) {
        this.dhcomapJdbcTemplate = dhcomapJdbcTemplate;
        this.dhinfapJdbcTemplate = dhinfapJdbcTemplate;
    }

    @Transactional
    public void insertIntoBothDatabases(DemoRequest request, String requestId) {
        log.info("[2PC] transaction begin. requestId={}", requestId);
        log.debug("[2PC] INSERT SQL={} | params: sessionId={}, userId={}, failMode={}",
                INSERT_SQL, request.getSessionId(), request.getUserId(), request.getFailMode());

        int rows1 = dhcomapJdbcTemplate.update(INSERT_SQL,
                requestId, request.getSessionId(), request.getUserId(), request.getMessage());
        log.info("[2PC] inserted into DHCOMAP. rows={}, requestId={}", rows1, requestId);

        // 動作確認用: DHCOMAP INSERT 後に意図的に例外 → DHCOMAP の INSERT もロールバックされる
        if ("AFTER_DHCOMAP".equalsIgnoreCase(request.getFailMode())) {
            throw new DemoException("Intentional failure AFTER DHCOMAP insert (2PC rollback test)");
        }

        int rows2 = dhinfapJdbcTemplate.update(INSERT_SQL,
                requestId, request.getSessionId(), request.getUserId(), request.getMessage());
        log.info("[2PC] inserted into DHINFAP. rows={}, requestId={}", rows2, requestId);

        // 動作確認用: DHINFAP INSERT 後に意図的に例外 → 両方ロールバックされる
        if ("AFTER_DHINFAP".equalsIgnoreCase(request.getFailMode())) {
            throw new DemoException("Intentional failure AFTER DHINFAP insert (2PC rollback test)");
        }

        log.info("[2PC] both inserts done. JTA TM will perform 2-phase commit on method return. requestId={}",
                requestId);
    }
}
