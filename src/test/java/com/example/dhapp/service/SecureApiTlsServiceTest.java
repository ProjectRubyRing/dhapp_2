package com.example.dhapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.dhapp.dto.SecureApiCallResponse;
import com.example.dhapp.dto.TrustStoreCallResult;
import com.example.dhapp.dto.TrustStoresResponse;

/**
 * {@link SecureApiTlsService} の、接続先に依存しない部分（トラストストアの解決・
 * パラメータ解釈・失敗時の詰め方）の確認。
 *
 * <p>secure-api への実接続は compose 環境が必要なため、ここでは
 * 「誰も待ち受けていないポート」への接続を使い、例外を投げずに
 * {@code CONNECT_FAILED} として結果を返すことを確認する。</p>
 */
class SecureApiTlsServiceTest {

    @TempDir
    Path tempDir;

    private SecureApiTlsService service(String jbossTrustStorePath) {
        TrustStoreInspector trustStoreInspector = new TrustStoreInspector("");
        return new SecureApiTlsService(
                "https://secure-api:8443/api/v1/ping",
                "https://alb/secure/v1/ping",
                1000, 1000,
                jbossTrustStorePath, "changeit", "",
                "appTrustStore", "jboss-truststore.p12",
                trustStoreInspector,
                new ElytronSslInspector("cacertTrustStore", "cacertTrustManager",
                        "cacertClientSslContext"),
                new TlsHttpsClient("https://localhost:8443/", 1000, 1000, trustStoreInspector));
    }

    /** 空の PKCS12 トラストストアを作る（JBoss EAP 側ストアの代わり）。 */
    private Path emptyPkcs12() throws Exception {
        Path file = tempDir.resolve("jboss-truststore.p12");
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, "changeit".toCharArray());
        }
        return file;
    }

    @Test
    void resolvesTrustSourcesFromParameter() {
        SecureApiTlsService service = service("");

        assertEquals(List.of(SecureApiTlsService.TRUST_SOURCE_JVM,
                SecureApiTlsService.TRUST_SOURCE_JBOSS), service.resolveTrustSources(null));
        assertEquals(List.of(SecureApiTlsService.TRUST_SOURCE_JVM,
                        SecureApiTlsService.TRUST_SOURCE_JBOSS,
                        SecureApiTlsService.TRUST_SOURCE_NONE),
                service.resolveTrustSources("all"));
        assertEquals(List.of(SecureApiTlsService.TRUST_SOURCE_JBOSS),
                service.resolveTrustSources("jboss"));
        assertEquals(List.of(SecureApiTlsService.TRUST_SOURCE_JVM),
                service.resolveTrustSources("jdk"));
        // 未知の指定だけなら既定（両方）に戻す。
        assertEquals(List.of(SecureApiTlsService.TRUST_SOURCE_JVM,
                SecureApiTlsService.TRUST_SOURCE_JBOSS), service.resolveTrustSources("unknown"));
    }

    @Test
    void usesConfiguredPathAsJbossTrustStore() throws Exception {
        Path store = emptyPkcs12();
        SecureApiTlsService.JbossTrustStore resolved = service(store.toString()).resolveJbossTrustStore();

        assertEquals(store.toString(), resolved.path());
        assertTrue(resolved.resolution().contains("app.secure-api.jboss.truststore-path"));
    }

    @Test
    void inspectsBothTrustStoresWithoutConnecting() throws Exception {
        TrustStoresResponse response = service(emptyPkcs12().toString()).inspectTrustStores("test-1");

        assertNotNull(response.getJvm());
        assertNotNull(response.getJbossEap());
        assertTrue(response.getJbossEap().isLoaded(), "JBoss 側ストアが読めること");
        assertEquals(0, response.getJbossEap().getEntryCount());
        assertNotNull(response.getReport());
        assertTrue(response.getReport().contains("JVM が管理するトラストストア"));
        assertTrue(response.getReport().contains("JBoss EAP が管理するトラストストア"));
    }

    @Test
    void reportsConnectFailureWithoutThrowing() throws Exception {
        // 直後に閉じたポート = 誰も待ち受けていないポートを使う。
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        String url = "https://127.0.0.1:" + freePort + "/api/v1/ping";

        SecureApiCallResponse response = service(emptyPkcs12().toString())
                .call(url, null, "jvm,jboss", "GET", null, null, "test-2");

        assertEquals(SecureApiTlsService.TARGET_CUSTOM, response.getTarget());
        assertEquals(url, response.getUrl());
        assertEquals(2, response.getResults().size());
        for (TrustStoreCallResult result : response.getResults()) {
            assertEquals(SecureApiTlsService.STATUS_CONNECT_FAILED, result.getStatus());
            assertNotNull(result.getCauseChain());
            assertNotNull(result.getHint());
        }
        assertEquals(SecureApiTlsService.STATUS_FAILED, response.getStatus());
        assertFalse(response.getComparison().isBothSucceeded());
        assertTrue(response.getComparison().isConsistent(), "両方失敗なら判定は一致");
        assertTrue(response.getReport().contains("[比較] JVM 管理ストア vs JBoss EAP 管理ストア"));
    }

    @Test
    void rejectsNonHttpsUrl() throws Exception {
        SecureApiCallResponse response = service(emptyPkcs12().toString())
                .call("http://secure-api:8080/api/v1/ping", null, "jvm", "GET", null, null, "test-3");

        assertEquals(SecureApiTlsService.STATUS_INVALID_URL, response.getResults().get(0).getStatus());
    }

    @Test
    void picksDefaultUrlForTarget() throws IOException {
        SecureApiTlsService service = service("");
        assertEquals("https://secure-api:8443/api/v1/ping", service.getDirectUrl());
        assertEquals("https://alb/secure/v1/ping", service.getAlbUrl());
    }
}
