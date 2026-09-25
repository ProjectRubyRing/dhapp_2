package com.example.dhapp.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.dhapp.dto.SqsSendResponse;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * 設定された SQS キューへ、半角スペース 1 文字（U+0020）をメッセージ本文として SendMessage する。
 *
 * <p>SqsClient は初回呼び出し時に生成する。リージョンや認証情報が解決できない環境
 * （ローカル実行など）でもアプリの起動を止めないため。リージョンは
 * {@code app.sqs.region} → キュー URL のホスト名（{@code sqs.<region>.amazonaws.com}）→
 * SDK 既定のチェーン（{@code AWS_REGION} 等）の順に解決する。認証情報は SDK 既定のチェーン
 * （環境変数・Web Identity・ECS タスクロール・EC2 インスタンスプロファイル等）を使う。</p>
 *
 * <p>FIFO キュー（URL が {@code .fifo} で終わる）の場合は MessageGroupId に
 * {@code app.sqs.message-group-id}、MessageDeduplicationId に requestId を付ける
 * （本文が常に同じ半角スペースのため、内容ベースの重複排除で捨てられないようにする）。</p>
 */
@Service
public class SqsSendService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SqsSendService.class);

    /** 送信するメッセージ本文。半角スペース 1 文字。 */
    public static final String MESSAGE_BODY = " ";

    /** キュー URL のホスト名からリージョンを取り出す（sqs.<region>.amazonaws.com / 旧形式 <region>.queue.amazonaws.com）。 */
    private static final Pattern REGION_IN_QUEUE_HOST = Pattern.compile(
            "^(?:sqs\\.([a-z0-9-]+)|([a-z0-9-]+)\\.queue)\\.amazonaws\\.com(?:\\.cn)?$");

    /** 生成済みの SqsClient と、そのリージョンの解決元。 */
    private record ClientHolder(SqsClient client, String region, String regionSource) {
    }

    private final String queueUrl;
    private final String configuredRegion;
    private final String endpoint;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String messageGroupId;

    private ClientHolder clientHolder;

    public SqsSendService(
            @Value("${app.sqs.queue-url:}") String queueUrl,
            @Value("${app.sqs.region:}") String configuredRegion,
            @Value("${app.sqs.endpoint:}") String endpoint,
            @Value("${app.sqs.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${app.sqs.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${app.sqs.message-group-id:dhapp}") String messageGroupId) {
        this.queueUrl = queueUrl.trim();
        this.configuredRegion = configuredRegion.trim();
        this.endpoint = endpoint.trim();
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.messageGroupId = messageGroupId;
    }

    public String getQueueUrl() {
        return queueUrl;
    }

    public SqsSendResponse send(String requestId) {
        long startedAt = System.currentTimeMillis();

        SqsSendResponse response = new SqsSendResponse();
        response.setRequestId(requestId);
        response.setQueueUrl(queueUrl);
        response.setEndpoint(endpoint.isEmpty() ? null : endpoint);
        response.setMessageBody(MESSAGE_BODY);
        response.setMessageBodyHex(HexFormat.of().formatHex(MESSAGE_BODY.getBytes(StandardCharsets.UTF_8)));
        response.setExpectedMd5OfMessageBody(md5Hex(MESSAGE_BODY));

        if (queueUrl.isEmpty()) {
            log.warn("SQS queue URL is not configured. requestId={}", requestId);
            response.setStatus("SQS_NOT_CONFIGURED");
            response.setMessage("app.sqs.queue-url (SQS_QUEUE_URL) is not configured");
            response.setElapsedMs(System.currentTimeMillis() - startedAt);
            return response;
        }

        boolean fifo = queueUrl.endsWith(".fifo");
        response.setFifoQueue(fifo);
        try {
            ClientHolder holder = client();
            response.setRegion(holder.region());
            response.setRegionSource(holder.regionSource());

            SendMessageRequest.Builder request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(MESSAGE_BODY);
            if (fifo) {
                request.messageGroupId(messageGroupId).messageDeduplicationId(requestId);
                response.setMessageGroupId(messageGroupId);
                response.setMessageDeduplicationId(requestId);
            }

            log.info("Sending message to SQS. queueUrl={}, region={}, regionSource={}, endpoint={}, fifo={}, "
                            + "messageBodyHex={}, requestId={}",
                    queueUrl, holder.region(), holder.regionSource(), response.getEndpoint(), fifo,
                    response.getMessageBodyHex(), requestId);

            SendMessageResponse result = holder.client().sendMessage(request.build());

            response.setMessageId(result.messageId());
            response.setSequenceNumber(result.sequenceNumber());
            response.setMd5OfMessageBody(result.md5OfMessageBody());
            response.setMd5Matched(response.getExpectedMd5OfMessageBody().equals(result.md5OfMessageBody()));
            response.setStatus("SUCCESS");

            log.info("SQS SendMessage succeeded. messageId={}, sequenceNumber={}, md5OfMessageBody={}, "
                            + "md5Matched={}, requestId={}",
                    result.messageId(), result.sequenceNumber(), result.md5OfMessageBody(),
                    response.getMd5Matched(), requestId);
        } catch (AwsServiceException e) {
            // SQS が返したエラー（権限不足・キューが存在しない等）
            response.setStatus("SQS_SEND_FAILED");
            response.setErrorType(e.getClass().getSimpleName());
            response.setAwsErrorCode(e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null);
            response.setAwsRequestId(e.requestId());
            response.setHttpStatus(e.statusCode());
            response.setMessage(e.getMessage());
            log.error("SQS SendMessage failed (service error). queueUrl={}, awsErrorCode={}, httpStatus={}, "
                            + "awsRequestId={}, requestId={}",
                    queueUrl, response.getAwsErrorCode(), e.statusCode(), e.requestId(), requestId, e);
        } catch (SdkException | IllegalArgumentException e) {
            // 送信前・通信時の失敗（リージョン・認証情報が解決できない、接続できない、設定値が不正等）
            response.setStatus("SQS_SEND_FAILED");
            response.setErrorType(e.getClass().getSimpleName());
            response.setMessage(e.getMessage());
            log.error("SQS SendMessage failed (client error). queueUrl={}, requestId={}", queueUrl, requestId, e);
        }

        response.setElapsedMs(System.currentTimeMillis() - startedAt);
        return response;
    }

    private synchronized ClientHolder client() {
        if (clientHolder == null) {
            String region;
            String regionSource;
            String regionFromUrl = regionFromQueueUrl(queueUrl);
            if (!configuredRegion.isEmpty()) {
                region = configuredRegion;
                regionSource = "app.sqs.region";
            } else if (regionFromUrl != null) {
                region = regionFromUrl;
                regionSource = "queue-url";
            } else {
                // AWS_REGION / aws.region / プロファイル / EC2 メタデータ。解決できなければ SdkClientException。
                region = new DefaultAwsRegionProviderChain().getRegion().id();
                regionSource = "sdk-default-chain";
            }

            SqsClientBuilder builder = SqsClient.builder()
                    .region(Region.of(region))
                    .httpClientBuilder(UrlConnectionHttpClient.builder()
                            .connectionTimeout(Duration.ofMillis(connectTimeoutMs))
                            .socketTimeout(Duration.ofMillis(readTimeoutMs)));
            if (!endpoint.isEmpty()) {
                builder.endpointOverride(URI.create(endpoint));
            }
            clientHolder = new ClientHolder(builder.build(), region, regionSource);
            log.info("SQS client created. region={}, regionSource={}, endpoint={}",
                    region, regionSource, endpoint.isEmpty() ? "(sdk default)" : endpoint);
        }
        return clientHolder;
    }

    /** キュー URL のホスト名がリージョン付きの SQS エンドポイントならそのリージョン、そうでなければ null。 */
    static String regionFromQueueUrl(String queueUrl) {
        try {
            String host = URI.create(queueUrl).getHost();
            if (host == null) {
                return null;
            }
            Matcher m = REGION_IN_QUEUE_HOST.matcher(host);
            if (!m.matches()) {
                return null;
            }
            return m.group(1) != null ? m.group(1) : m.group(2);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String md5Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    @Override
    public synchronized void destroy() {
        if (clientHolder != null) {
            clientHolder.client().close();
            clientHolder = null;
        }
    }
}
