package com.example.dhapp.dto;

/**
 * /api/demo/execute の正常系レスポンスボディ。
 */
public class DemoResponse {

    private String status;
    private String sessionKey;
    private boolean dhcomapInserted;
    private boolean dhinfapInserted;
    private Integer externalApiStatus;
    private String requestId;

    public DemoResponse() {
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public boolean isDhcomapInserted() {
        return dhcomapInserted;
    }

    public void setDhcomapInserted(boolean dhcomapInserted) {
        this.dhcomapInserted = dhcomapInserted;
    }

    public boolean isDhinfapInserted() {
        return dhinfapInserted;
    }

    public void setDhinfapInserted(boolean dhinfapInserted) {
        this.dhinfapInserted = dhinfapInserted;
    }

    public Integer getExternalApiStatus() {
        return externalApiStatus;
    }

    public void setExternalApiStatus(Integer externalApiStatus) {
        this.externalApiStatus = externalApiStatus;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
