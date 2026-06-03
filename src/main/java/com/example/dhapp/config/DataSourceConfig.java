package com.example.dhapp.config;

import javax.naming.NamingException;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jndi.JndiObjectFactoryBean;

/**
 * WildFly で定義した 2 つの XA データソースを JNDI から参照する設定。
 *
 * DHCOMAP : java:/jdbc/DHCOMAPXADS
 * DHINFAP : java:/jdbc/DHINFAPXADS
 *
 * これらは WildFly 管理の XA データソースであるため、JTA トランザクション内で
 * connection を取得すると自動的に XAResource として enlist される。
 * アプリ側は単に JdbcTemplate で SQL を実行するだけでよい。
 */
@Configuration
public class DataSourceConfig {

    @Bean(name = "dhcomapDataSource")
    public DataSource dhcomapDataSource(
            @Value("${app.datasource.dhcomap.jndi-name:java:/jdbc/DHCOMAPXADS}") String jndiName)
            throws NamingException {
        return lookupDataSource(jndiName);
    }

    @Bean(name = "dhinfapDataSource")
    public DataSource dhinfapDataSource(
            @Value("${app.datasource.dhinfap.jndi-name:java:/jdbc/DHINFAPXADS}") String jndiName)
            throws NamingException {
        return lookupDataSource(jndiName);
    }

    @Bean(name = "dhcomapJdbcTemplate")
    public JdbcTemplate dhcomapJdbcTemplate(@Qualifier("dhcomapDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "dhinfapJdbcTemplate")
    public JdbcTemplate dhinfapJdbcTemplate(@Qualifier("dhinfapDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    private DataSource lookupDataSource(String jndiName) throws NamingException {
        JndiObjectFactoryBean factory = new JndiObjectFactoryBean();
        factory.setJndiName(jndiName);
        factory.setExpectedType(DataSource.class);
        // 完全修飾名 (java:/...) を使うため resourceRef は false
        factory.setResourceRef(false);
        factory.afterPropertiesSet();
        return (DataSource) factory.getObject();
    }
}
