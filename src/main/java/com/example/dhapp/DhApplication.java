package com.example.dhapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Spring Boot entry point.
 *
 * WildFly に WAR としてデプロイするため SpringBootServletInitializer を継承する。
 * DataSource は WildFly 側で XA データソースとして定義し JNDI 経由で参照するため、
 * Spring Boot の DataSourceAutoConfiguration は無効化する。
 */
@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
public class DhApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(DhApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(DhApplication.class, args);
    }
}
