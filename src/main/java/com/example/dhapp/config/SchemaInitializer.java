package com.example.dhapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 起動時に demo_transaction_log テーブルを「存在しなければ作成」する。
 *
 * 重要: MySQL では CREATE TABLE などの DDL は暗黙コミットを伴うため、
 * JTA/XA(2PC) トランザクションの内側で実行してはならない（グローバルトランザクションが壊れる）。
 * そのため DDL はアプリ起動時（トランザクション境界の外、autocommit 接続）で一度だけ実行し、
 * リクエスト処理(@Transactional)では INSERT のみを行う。
 *
 * これにより「テーブルが無ければ作成、あればそのまま利用」という要求仕様を満たしつつ、
 * 2PC の健全性を保つ。
 */
@Component
public class SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS demo_transaction_log ("
            + "  id BIGINT AUTO_INCREMENT PRIMARY KEY,"
            + "  request_id VARCHAR(64) NOT NULL,"
            + "  session_id VARCHAR(128) NOT NULL,"
            + "  user_id VARCHAR(128) NOT NULL,"
            + "  message VARCHAR(1024),"
            + "  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
            + ") ENGINE=InnoDB";

    private final JdbcTemplate dhcomapJdbcTemplate;
    private final JdbcTemplate dhinfapJdbcTemplate;

    public SchemaInitializer(
            @Qualifier("dhcomapJdbcTemplate") JdbcTemplate dhcomapJdbcTemplate,
            @Qualifier("dhinfapJdbcTemplate") JdbcTemplate dhinfapJdbcTemplate) {
        this.dhcomapJdbcTemplate = dhcomapJdbcTemplate;
        this.dhinfapJdbcTemplate = dhinfapJdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Ensuring 'demo_transaction_log' exists on DHCOMAP and DHINFAP (DDL runs outside any 2PC tx)...");
        // XA は InnoDB が前提（MySQL の XA サポートは InnoDB のみ）
        dhcomapJdbcTemplate.execute(CREATE_TABLE_SQL);
        log.info("DHCOMAP schema ready.");
        dhinfapJdbcTemplate.execute(CREATE_TABLE_SQL);
        log.info("DHINFAP schema ready.");
    }
}
