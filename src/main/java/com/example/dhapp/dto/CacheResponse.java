package com.example.dhapp.dto;

import java.util.Map;

/**
 * /api/cache/execute（ElastiCache のみ確認）のレスポンスボディ。
 * stored には Valkey から読み戻したセッションハッシュが入る。
 */
public class CacheResponse {

    private String status;
    private String requestId;
    private String sessionKey;
    private Map<String, String> stored;

    public CacheResponse() {
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

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public Map<String, String> getStored() {
        return stored;
    }

    public void setStored(Map<String, String> stored) {
        this.stored = stored;
    }
}
