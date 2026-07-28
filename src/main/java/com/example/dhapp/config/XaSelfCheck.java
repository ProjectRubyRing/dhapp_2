package com.example.dhapp.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 起動時の 2PC 自己診断。
 *
 * DHCOMAP と DHINFAP の両方から 1 つの JTA トランザクション内でコネクションを取得し、
 * 2 本の XA ブランチが enlist できることだけを確認して必ずロールバックする
 * （SELECT のみなのでデータは一切変更しない）。
 *
 * 2PC が壊れている場合、実際のリクエスト処理では次の例外になって現れる:
 *
 *   java.sql.SQLException: jakarta.resource.ResourceException:
 *       IJ000457: Unchecked throwable in managedConnectionReconnected()
 *   java.sql.SQLException: XAER_INVAL: Invalid arguments (or unsupported command)
 *
 * これは 2 本目の enlist で発生するため、起動時に同じ操作をしておけば
 * 「デプロイ直後に」原因と対処をログへ出せる。原因の典型は WildFly の XA データソースに
 * same-rm-override=false が設定されていないこと（詳細は下の診断メッセージと README を参照）。
 *
 * 診断が不要な環境では app.xa.self-check.enabled=false で無効化できる。
 * 失敗しても起動は止めない（ログに ERROR を出すだけ）。
 */
@Component
@Order(1)
public class XaSelfCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(XaSelfCheck.class);

    private final DataSource dhcomapDataSource;
    private final DataSource dhinfapDataSource;
    private final JdbcTemplate dhcomapJdbcTemplate;
    private final JdbcTemplate dhinfapJdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final boolean enabled;

    public XaSelfCheck(
            @Qualifier("dhcomapDataSource") DataSource dhcomapDataSource,
            @Qualifier("dhinfapDataSource") DataSource dhinfapDataSource,
            @Qualifier("dhcomapJdbcTemplate") JdbcTemplate dhcomapJdbcTemplate,
            @Qualifier("dhinfapJdbcTemplate") JdbcTemplate dhinfapJdbcTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${app.xa.self-check.enabled:true}") boolean enabled) {
        this.dhcomapDataSource = dhcomapDataSource;
        this.dhinfapDataSource = dhinfapDataSource;
        this.dhcomapJdbcTemplate = dhcomapJdbcTemplate;
        this.dhinfapJdbcTemplate = dhinfapJdbcTemplate;
        this.transactionManager = transactionManager;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[2PC][self-check] disabled by app.xa.self-check.enabled=false");
            return;
        }

        logEnvironment("DHCOMAP", dhcomapDataSource);
        logEnvironment("DHINFAP", dhinfapDataSource);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            tx.executeWithoutResult(status -> {
                // 1 本目のブランチを enlist
                dhcomapJdbcTemplate.queryForObject("SELECT 1", Integer.class);
                // 2 本目のブランチを enlist（2PC が壊れているとここで失敗する）
                dhinfapJdbcTemplate.queryForObject("SELECT 1", Integer.class);
                // 検証のみ。データは書かないが念のため必ずロールバックさせる
                status.setRollbackOnly();
            });
            log.info("[2PC][self-check] OK. Both DHCOMAP and DHINFAP were enlisted as separate XA branches "
                    + "in a single JTA transaction.");
        } catch (RuntimeException e) {
            log.error("[2PC][self-check] FAILED. The application will start, but /api/db/execute and "
                    + "/api/demo/execute will fail at the second XA enlist.{}", diagnose(e), e);
        }
    }

    /**
     * 例外の連鎖から既知の症状を見つけ、対処方法を添える。
     */
    private String diagnose(Throwable error) {
        String chain = describeChain(error);
        if (chain.contains("XAER_INVAL") || chain.contains("IJ000457")) {
            return System.lineSeparator()
                    + "  Cause: WildFly's transaction manager tried to JOIN both branches into one XA branch "
                    + "(XA START <xid> JOIN), which MySQL does not support (ERROR 1398 XAER_INVAL)."
                    + System.lineSeparator()
                    + "  Why: MySQL Connector/J 9.5.0 and later (Bug #18403804) compare only host and port in "
                    + "XAResource.isSameRM() - the schema name is no longer part of the comparison. "
                    + "DHCOMAP and DHINFAP therefore look like the same resource manager when they live on the "
                    + "same MySQL instance."
                    + System.lineSeparator()
                    + "  Fix: set same-rm-override=false on BOTH XA data sources and reload WildFly, e.g."
                    + System.lineSeparator()
                    + "    /subsystem=datasources/xa-data-source=DHCOMAPXADS:write-attribute(name=same-rm-override,value=false)"
                    + System.lineSeparator()
                    + "    /subsystem=datasources/xa-data-source=DHINFAPXADS:write-attribute(name=same-rm-override,value=false)"
                    + System.lineSeparator()
                    + "  See wildfly/fix-xa-2pc.cli in this repository.";
        }
        if (chain.contains("XAER_RMFAIL")) {
            return System.lineSeparator()
                    + "  Cause: an XA command was issued on a connection that already had a transaction in "
                    + "progress. Set no-tx-separate-pool=true on both XA data sources so that transactional and "
                    + "non-transactional use (e.g. the startup DDL) do not share physical connections.";
        }
        return "";
    }

    private String describeChain(Throwable error) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = error; t != null && chain.length() < 4096; t = t.getCause()) {
            chain.append(t).append(System.lineSeparator());
            if (t.getCause() == t) {
                break;
            }
        }
        return chain.toString();
    }

    /**
     * 障害切り分けに必要な接続先・ドライバ・サーバのバージョンをログへ残す。
     */
    private void logEnvironment(String name, DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            log.info("[2PC][self-check] {}: url={}, catalog={}, server={} {}, driver={} {}",
                    name, meta.getURL(), connection.getCatalog(),
                    meta.getDatabaseProductName(), meta.getDatabaseProductVersion(),
                    meta.getDriverName(), meta.getDriverVersion());
        } catch (SQLException e) {
            log.warn("[2PC][self-check] {}: could not read connection metadata.", name, e);
        }
    }
}
