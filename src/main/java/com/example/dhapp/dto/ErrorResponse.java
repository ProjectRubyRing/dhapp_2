package com.example.dhapp.dto;

/**
 * 例外発生時のエラーレスポンスボディ。
 */
public class ErrorResponse {

    private String status;
    private String message;
    private String requestId;

    public ErrorResponse() {
    }

    public ErrorResponse(String status, String message, String requestId) {
        this.status = status;
        this.message = message;
        this.requestId = requestId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
