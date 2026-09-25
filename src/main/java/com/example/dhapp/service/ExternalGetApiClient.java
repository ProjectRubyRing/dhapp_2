package com.example.dhapp.service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 設定可能な外部 URL に対して HTTP GET を行い、ステータスとレスポンス本文の先頭を取得する。
 * URL / 接続タイムアウト / 読取タイムアウト / 本文の取得文字数は {@code app.external-get-api.*} から取得する
 * （HTTP POST の {@link ExternalApiClient}（{@code app.external-api.*}）とは独立した設定）。
 *
 * <p>本文は先頭の指定文字数だけを読み、それ以上は読み込まない（大きな本文でもメモリを使わない）。
 * 文字コードは Content-Type の charset、無ければ UTF-8 で復号する。</p>
 */
@Service
public class ExternalGetApiClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalGetApiClient.class);

    /** 外部 API の応答。bodyTruncated は本文が bodyHead より長かったことを示す。 */
    public record GetResult(int statusCode, String contentType, Long contentLength,
            String bodyHead, boolean bodyTruncated) {
    }

    private final RestClient restClient;
    private final String url;
    private final int bodyHeadChars;

    public ExternalGetApiClient(
            @Value("${app.external-get-api.url:http://localhost:9090/health}") String url,
            @Value("${app.external-get-api.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${app.external-get-api.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${app.external-get-api.body-head-chars:500}") int bodyHeadChars) {

        this.url = url;
        this.bodyHeadChars = Math.max(0, bodyHeadChars);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    public String getUrl() {
        return url;
    }

    public int getBodyHeadChars() {
        return bodyHeadChars;
    }

    /**
     * 外部 API を HTTP GET で呼び出し、ステータスと本文の先頭を返す。
     * exchange() を使うため 4xx/5xx でも例外を投げず、ステータスと本文（エラー本文）をそのまま取得する。
     * 接続不可・タイムアウト等の場合は RestClientException(ResourceAccessException) が、
     * URL が不正な場合は IllegalArgumentException が送出される。
     */
    public GetResult callExternalApi(String requestId) {
        log.info("Calling external API (GET). url={}, bodyHeadChars={}, requestId={}",
                url, bodyHeadChars, requestId);

        GetResult result = restClient.get()
                .uri(url)
                .exchange((req, res) -> readResponse(res));

        log.info("External GET API responded. status={}, contentType={}, contentLength={}, "
                        + "bodyHeadChars={}, bodyTruncated={}, requestId={}",
                result.statusCode(), result.contentType(), result.contentLength(),
                result.bodyHead().length(), result.bodyTruncated(), requestId);
        log.debug("External GET API body head. requestId={}, bodyHead={}", requestId, result.bodyHead());
        return result;
    }

    private GetResult readResponse(ClientHttpResponse res) throws IOException {
        int statusCode = res.getStatusCode().value();
        String contentType = res.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        long contentLength = res.getHeaders().getContentLength();

        // 本文ストリームは exchange 終了時に RestClient が閉じるため、ここでは閉じない。
        Reader reader = new InputStreamReader(res.getBody(), resolveCharset(contentType));
        char[] buf = new char[bodyHeadChars];
        int filled = 0;
        int n;
        while (filled < buf.length && (n = reader.read(buf, filled, buf.length - filled)) != -1) {
            filled += n;
        }
        // 上限まで読めた場合のみ、続きがあるかを 1 文字だけ読んで確かめる。
        boolean truncated = filled == buf.length && reader.read() != -1;

        return new GetResult(statusCode, contentType, contentLength >= 0 ? contentLength : null,
                new String(buf, 0, filled), truncated);
    }

    /** Content-Type の charset。無い・解釈できない場合は UTF-8。 */
    private static Charset resolveCharset(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            Charset charset = MediaType.parseMediaType(contentType).getCharset();
            return charset != null ? charset : StandardCharsets.UTF_8;
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
