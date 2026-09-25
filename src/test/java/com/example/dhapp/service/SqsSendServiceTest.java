package com.example.dhapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.dhapp.dto.SqsSendResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * {@link SqsSendService} が SQS へ半角スペース 1 文字を SendMessage することの確認。
 *
 * <p>SQS の JSON プロトコル（{@code X-Amz-Target: AmazonSQS.SendMessage}）を話す最小の
 * 偽エンドポイントを立て、{@code app.sqs.endpoint} でそこへ向ける。偽エンドポイントは
 * 受け取った MessageBody の MD5 を返すため、SDK の MD5 検証も実際に通る。</p>
 */
class SqsSendServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String endpoint;
    private final List<JsonNode> receivedBodies = new CopyOnWriteArrayList<>();
    private final List<String> receivedTargets = new CopyOnWriteArrayList<>();
    private volatile boolean respondQueueDoesNotExist;
    private SqsSendService service;

    @BeforeEach
    void startFakeSqs() throws IOException {
        System.setProperty("aws.accessKeyId", "test-access-key");
        System.setProperty("aws.secretAccessKey", "test-secret-key");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopFakeSqs() {
        if (service != null) {
            service.destroy();
        }
        server.stop(0);
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    private void handle(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        receivedBodies.add(request);
        receivedTargets.add(exchange.getRequestHeaders().getFirst("X-Amz-Target"));

        int status;
        String body;
        if (respondQueueDoesNotExist) {
            status = 400;
            body = "{\"__type\":\"com.amazonaws.sqs#QueueDoesNotExist\","
                    + "\"message\":\"The specified queue does not exist.\"}";
        } else {
            status = 200;
            String md5 = md5Hex(request.path("MessageBody").asText());
            body = "{\"MD5OfMessageBody\":\"" + md5 + "\",\"MessageId\":\"msg-0001\""
                    + (request.has("MessageGroupId") ? ",\"SequenceNumber\":\"18800000000000000001\"" : "")
                    + "}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/x-amz-json-1.0");
        exchange.getResponseHeaders().add("x-amzn-RequestId", "fake-aws-request-id");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private SqsSendService newService(String queueUrl) {
        service = new SqsSendService(queueUrl, "ap-northeast-1", endpoint, 2000, 5000, "dhapp");
        return service;
    }

    @Test
    void sendsSingleHalfWidthSpaceToStandardQueue() {
        SqsSendResponse response = newService(endpoint + "/000000000000/test-queue").send("req-1");

        assertEquals("SUCCESS", response.getStatus(), response.getMessage());
        assertEquals(1, receivedBodies.size());
        assertEquals("AmazonSQS.SendMessage", receivedTargets.get(0));
        JsonNode sent = receivedBodies.get(0);
        assertEquals(" ", sent.path("MessageBody").asText());
        assertEquals(endpoint + "/000000000000/test-queue", sent.path("QueueUrl").asText());
        assertFalse(sent.has("MessageGroupId"));
        assertFalse(sent.has("MessageDeduplicationId"));

        assertEquals("msg-0001", response.getMessageId());
        assertEquals("20", response.getMessageBodyHex());
        assertEquals(md5Hex(" "), response.getMd5OfMessageBody());
        assertTrue(response.getMd5Matched());
        assertEquals("ap-northeast-1", response.getRegion());
        assertEquals("app.sqs.region", response.getRegionSource());
        assertFalse(response.isFifoQueue());
    }

    @Test
    void fifoQueueGetsGroupIdAndPerRequestDeduplicationId() {
        SqsSendResponse response = newService(endpoint + "/000000000000/test-queue.fifo").send("req-fifo-1");

        assertEquals("SUCCESS", response.getStatus(), response.getMessage());
        JsonNode sent = receivedBodies.get(0);
        assertEquals(" ", sent.path("MessageBody").asText());
        assertEquals("dhapp", sent.path("MessageGroupId").asText());
        assertEquals("req-fifo-1", sent.path("MessageDeduplicationId").asText());
        assertTrue(response.isFifoQueue());
        assertEquals("18800000000000000001", response.getSequenceNumber());
    }

    @Test
    void reportsSqsServiceErrorWithoutThrowing() {
        respondQueueDoesNotExist = true;

        SqsSendResponse response = newService(endpoint + "/000000000000/missing-queue").send("req-err-1");

        assertEquals("SQS_SEND_FAILED", response.getStatus());
        assertEquals("QueueDoesNotExistException", response.getErrorType());
        assertEquals("QueueDoesNotExist", response.getAwsErrorCode());
        assertEquals(400, response.getHttpStatus());
        assertEquals("fake-aws-request-id", response.getAwsRequestId());
        assertNull(response.getMessageId());
        assertNotNull(response.getMessage());
    }

    @Test
    void returnsNotConfiguredWhenQueueUrlIsBlank() {
        SqsSendResponse response = newService("").send("req-none-1");

        assertEquals("SQS_NOT_CONFIGURED", response.getStatus());
        assertTrue(receivedBodies.isEmpty());
    }

    @Test
    void resolvesRegionFromQueueUrlHost() {
        assertEquals("ap-northeast-1",
                SqsSendService.regionFromQueueUrl("https://sqs.ap-northeast-1.amazonaws.com/123456789012/q"));
        assertEquals("ap-northeast-1",
                SqsSendService.regionFromQueueUrl("https://ap-northeast-1.queue.amazonaws.com/123456789012/q"));
        assertEquals("cn-north-1",
                SqsSendService.regionFromQueueUrl("https://sqs.cn-north-1.amazonaws.com.cn/123456789012/q"));
        assertNull(SqsSendService.regionFromQueueUrl("http://localhost:4566/000000000000/q"));
        assertNull(SqsSendService.regionFromQueueUrl("not a url"));
    }

    private static String md5Hex(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
