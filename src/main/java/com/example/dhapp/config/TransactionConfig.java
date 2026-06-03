package com.example.dhapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.jta.JtaTransactionManager;

/**
 * JTA トランザクション設定。
 *
 * WildFly が JNDI に bind している TransactionManager / UserTransaction を参照する
 * JtaTransactionManager を構成する。@Transactional はこの JTA TM をバックエンドに使う。
 * 2 つの XA データソースは JTA トランザクション内で使われると自動的に enlist され、
 * コミット時に 2 フェーズコミット(2PC)が実行される。
 */
@Configuration
@EnableTransactionManagement
public class TransactionConfig {

    @Bean
    public JtaTransactionManager transactionManager() {
        JtaTransactionManager tm = new JtaTransactionManager();
        // WildFly の標準 JNDI 名
        tm.setTransactionManagerName("java:/TransactionManager");
        tm.setUserTransactionName("java:comp/UserTransaction");
        // 上記が見つからない場合に備えて自動検出も有効化
        tm.setAutodetectTransactionManager(true);
        tm.setAutodetectUserTransaction(true);
        tm.setAllowCustomIsolationLevels(true);
        return tm;
    }
}
