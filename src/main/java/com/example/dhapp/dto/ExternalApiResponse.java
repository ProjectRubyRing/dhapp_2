package com.example.dhapp.dto;

/**
 * /api/external/execute（外部 API のみ確認）のレスポンスボディ。
 * externalApiStatus は外部 API が返した HTTP ステータス。失敗時は null で message に理由が入る。
 */
public class ExternalApiResponse {

    private String status;
    private String requestId;
    private Integer externalApiStatus;
    private String message;

    public ExternalApiResponse() {
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

    public Integer getExternalApiStatus() {
        return externalApiStatus;
    }

    public void setExternalApiStatus(Integer externalApiStatus) {
        this.externalApiStatus = externalApiStatus;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
