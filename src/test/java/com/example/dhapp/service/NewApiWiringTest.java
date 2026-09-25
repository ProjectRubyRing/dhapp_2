package com.example.dhapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import com.example.dhapp.controller.DateConfigController;
import com.example.dhapp.controller.ExternalGetApiController;
import com.example.dhapp.controller.SecureApiController;
import com.example.dhapp.controller.SqsController;

/**
 * 新設した API（date-config / secure-api / external-get / sqs）のビーン定義と、
 * {@code application.yml} の {@code @Value} プレースホルダが解決できることの確認。
 *
 * <p>DB / Valkey には触れない最小のコンテキストだけを立てる（デプロイして初めて
 * 「プレースホルダが解決できない」と分かる事態を避けるため）。</p>
 */
class NewApiWiringTest {

    @Test
    void resolvesConfiguredDefaultsFromApplicationYml() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            List<PropertySource<?>> sources = loadApplicationYml();
            sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));

            context.register(PropertySourcesPlaceholderConfigurer.class,
                    TrustStoreInspector.class, ElytronSslInspector.class, TlsHttpsClient.class,
                    DeploymentOverlayInspector.class, DateConfigService.class,
                    SecureApiTlsService.class, ExternalGetApiClient.class, SqsSendService.class,
                    DateConfigController.class, SecureApiController.class,
                    ExternalGetApiController.class, SqsController.class);
            context.refresh();

            DateConfigService dateConfigService = context.getBean(DateConfigService.class);
            assertEquals("/webapp/webapp9mf02/servlets/jp/iwin/base/tango/date_config.properties",
                    dateConfigService.getFilePath());
            assertEquals("jp/iwin/base/tango/date_config.properties", dateConfigService.getResourceName());
            assertEquals("WEB-INF/classes/jp/iwin/base/tango/date_config.properties",
                    dateConfigService.getResourcePathInDeployment());

            SecureApiTlsService secureApiTlsService = context.getBean(SecureApiTlsService.class);
            assertEquals("https://secure-api:8443/api/v1/ping", secureApiTlsService.getDirectUrl());
            assertEquals("https://alb/secure/v1/ping", secureApiTlsService.getAlbUrl());

            ExternalGetApiClient externalGetApiClient = context.getBean(ExternalGetApiClient.class);
            assertEquals("http://localhost:9090/health", externalGetApiClient.getUrl());
            assertEquals(500, externalGetApiClient.getBodyHeadChars());

            // SQS_QUEUE_URL 未設定でも（SqsClient を遅延生成するため）起動できること。
            assertEquals("", context.getBean(SqsSendService.class).getQueueUrl());

            assertNotNull(context.getBean(DateConfigController.class));
            assertNotNull(context.getBean(SecureApiController.class));
            assertNotNull(context.getBean(ExternalGetApiController.class));
            assertNotNull(context.getBean(SqsController.class));
        }
    }

    private static List<PropertySource<?>> loadApplicationYml() {
        try {
            return new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
        } catch (Exception e) {
            throw new IllegalStateException("application.yml を読み込めない", e);
        }
    }
}
