package com.example.dhapp.dto;

/**
 * /api/db/execute（DB のみ確認）のレスポンスボディ。
 */
public class DbResponse {

    private String status;
    private String requestId;
    private boolean dhcomapInserted;
    private boolean dhinfapInserted;

    public DbResponse() {
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
}
