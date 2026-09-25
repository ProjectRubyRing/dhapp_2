package com.example.dhapp.dto;

/**
 * /api/external-get/execute（外部 API への HTTP GET 確認）のレスポンスボディ。
 *
 * <p>externalApiStatus は外部 API が返した HTTP ステータス（4xx/5xx もそのまま入る）。
 * bodyHead はレスポンス本文の先頭 {@code app.external-get-api.body-head-chars} 文字で、
 * それより長い場合は bodyTruncated=true になる。
 * 接続不可・タイムアウト時は status=EXTERNAL_API_FAILED、externalApiStatus=null で message に理由が入る。</p>
 */
public class ExternalGetApiResponse {

    private String status;
    private String requestId;
    private String url;
    private Integer externalApiStatus;
    private String contentType;
    private Long contentLength;
    private String bodyHead;
    private Integer bodyHeadChars;
    private Boolean bodyTruncated;
    private long elapsedMs;
    private String message;

    public ExternalGetApiResponse() {
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

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Integer getExternalApiStatus() {
        return externalApiStatus;
    }

    public void setExternalApiStatus(Integer externalApiStatus) {
        this.externalApiStatus = externalApiStatus;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getContentLength() {
        return contentLength;
    }

    public void setContentLength(Long contentLength) {
        this.contentLength = contentLength;
    }

    public String getBodyHead() {
        return bodyHead;
    }

    public void setBodyHead(String bodyHead) {
        this.bodyHead = bodyHead;
    }

    public Integer getBodyHeadChars() {
        return bodyHeadChars;
    }

    public void setBodyHeadChars(Integer bodyHeadChars) {
        this.bodyHeadChars = bodyHeadChars;
    }

    public Boolean getBodyTruncated() {
        return bodyTruncated;
    }

    public void setBodyTruncated(Boolean bodyTruncated) {
        this.bodyTruncated = bodyTruncated;
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
