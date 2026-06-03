package com.example.dhapp.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * /api/demo/execute のリクエストボディ。
 */
public class DemoRequest {

    @NotBlank
    private String sessionId;

    @NotBlank
    private String userId;

    private String message;

    /**
     * 動作確認用の障害注入フラグ（任意）。
     *   null            : 正常系
     *   "AFTER_DHCOMAP" : DHCOMAP への INSERT 後に意図的に例外を発生させる
     *                     （DHCOMAP の INSERT もロールバックされること = 2PC 原子性 を検証）
     *   "AFTER_DHINFAP" : DHINFAP への INSERT 後に意図的に例外を発生させる
     *                     （DHCOMAP・DHINFAP 両方がロールバックされることを検証）
     */
    private String failMode;

    public DemoRequest() {
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getFailMode() {
        return failMode;
    }

    public void setFailMode(String failMode) {
        this.failMode = failMode;
    }
}
