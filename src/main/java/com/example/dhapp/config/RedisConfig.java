package com.example.dhapp.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

/**
 * ElastiCache for Valkey (Redis 互換) 用の Lettuce 接続設定。
 * ホスト / ポート / TLS 有無 / パスワード / タイムアウト をすべて設定値から取得する。
 */
@Configuration
public class RedisConfig {

    @Value("${app.valkey.host:localhost}")
    private String host;

    @Value("${app.valkey.port:6379}")
    private int port;

    @Value("${app.valkey.password:}")
    private String password;

    @Value("${app.valkey.ssl:false}")
    private boolean useSsl;

    @Value("${app.valkey.timeout-ms:2000}")
    private long timeoutMs;

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(host);
        serverConfig.setPort(port);
        if (StringUtils.hasText(password)) {
            serverConfig.setPassword(password);
        }

        LettuceClientConfiguration.LettuceClientConfigurationBuilder clientConfigBuilder =
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(timeoutMs));
        if (useSsl) {
            // ElastiCache の転送中暗号化(TLS)が有効な場合
            clientConfigBuilder.useSsl();
        }

        return new LettuceConnectionFactory(serverConfig, clientConfigBuilder.build());
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
