package com.example.dhapp.dto;

/**
 * /api/sqs/send（SQS キューへ半角スペースを SendMessage）のレスポンスボディ。
 *
 * <ul>
 *   <li>status … {@code SUCCESS} / {@code SQS_SEND_FAILED} / {@code SQS_NOT_CONFIGURED}</li>
 *   <li>messageBody / messageBodyHex … 送信した本文（半角スペース 1 文字 = {@code 20}）</li>
 *   <li>md5OfMessageBody … SQS が受け付けた本文の MD5。expectedMd5OfMessageBody と一致すれば
 *       md5Matched=true（半角スペースがそのまま届いたことの確認）</li>
 *   <li>errorType / awsErrorCode / awsRequestId / httpStatus / message … 失敗時の詳細</li>
 * </ul>
 */
public class SqsSendResponse {

    private String status;
    private String requestId;
    private String queueUrl;
    private String region;
    private String regionSource;
    private String endpoint;
    private boolean fifoQueue;
    private String messageGroupId;
    private String messageDeduplicationId;
    private String messageBody;
    private String messageBodyHex;
    private String messageId;
    private String sequenceNumber;
    private String md5OfMessageBody;
    private String expectedMd5OfMessageBody;
    private Boolean md5Matched;
    private String errorType;
    private String awsErrorCode;
    private String awsRequestId;
    private Integer httpStatus;
    private long elapsedMs;
    private String message;

    public SqsSendResponse() {
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getQueueUrl() {
        return queueUrl;
    }

    public void setQueueUrl(String queueUrl) {
        this.queueUrl = queueUrl;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getRegionSource() {
        return regionSource;
    }

    public void setRegionSource(String regionSource) {
        this.regionSource = regionSource;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public boolean isFifoQueue() {
        return fifoQueue;
    }

    public void setFifoQueue(boolean fifoQueue) {
        this.fifoQueue = fifoQueue;
    }

    public String getMessageGroupId() {
        return messageGroupId;
    }

    public void setMessageGroupId(String messageGroupId) {
        this.messageGroupId = messageGroupId;
    }

    public String getMessageDeduplicationId() {
        return messageDeduplicationId;
    }

    public void setMessageDeduplicationId(String messageDeduplicationId) {
        this.messageDeduplicationId = messageDeduplicationId;
    }

    public String getMessageBody() {
        return messageBody;
    }

    public void setMessageBody(String messageBody) {
        this.messageBody = messageBody;
    }

    public String getMessageBodyHex() {
        return messageBodyHex;
    }

    public void setMessageBodyHex(String messageBodyHex) {
        this.messageBodyHex = messageBodyHex;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(String sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public String getMd5OfMessageBody() {
        return md5OfMessageBody;
    }

    public void setMd5OfMessageBody(String md5OfMessageBody) {
        this.md5OfMessageBody = md5OfMessageBody;
    }

    public String getExpectedMd5OfMessageBody() {
        return expectedMd5OfMessageBody;
    }

    public void setExpectedMd5OfMessageBody(String expectedMd5OfMessageBody) {
        this.expectedMd5OfMessageBody = expectedMd5OfMessageBody;
    }

    public Boolean getMd5Matched() {
        return md5Matched;
    }

    public void setMd5Matched(Boolean md5Matched) {
        this.md5Matched = md5Matched;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public String getAwsErrorCode() {
        return awsErrorCode;
    }

    public void setAwsErrorCode(String awsErrorCode) {
        this.awsErrorCode = awsErrorCode;
    }

    public String getAwsRequestId() {
        return awsRequestId;
    }

    public void setAwsRequestId(String awsRequestId) {
        this.awsRequestId = awsRequestId;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
