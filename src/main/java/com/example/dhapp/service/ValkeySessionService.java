package com.example.dhapp.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.example.dhapp.dto.DemoRequest;
import com.example.dhapp.exception.DemoException;

/**
 * ElastiCache for Valkey にダミーセッションデータを保存する。
 * キー: session:{sessionId} （prefix は設定可能）
 * 値  : userId, message, requestId, createdAt を含むハッシュ
 * TTL : 設定可能
 *
 * 注意: Valkey(Redis) はトランザクション資源(XAResource)ではないため、
 * DB の 2PC には含めない。
 */
@Service
public class ValkeySessionService {

    private static final Logger log = LoggerFactory.getLogger(ValkeySessionService.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${app.valkey.session-prefix:session:}")
    private String sessionPrefix;

    @Value("${app.valkey.session-ttl-seconds:3600}")
    private long ttlSeconds;

    public ValkeySessionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String saveSession(DemoRequest request, String requestId) {
        String key = sessionPrefix + request.getSessionId();
        try {
            Map<String, String> data = new HashMap<>();
            data.put("userId", request.getUserId());
            data.put("message", request.getMessage() == null ? "" : request.getMessage());
            data.put("requestId", requestId);
            data.put("createdAt", OffsetDateTime.now().toString());

            HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
            hashOps.putAll(key, data);
            redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));

            log.info("Valkey session stored. key={}, ttlSeconds={}", key, ttlSeconds);
            return key;
        } catch (RuntimeException e) {
            log.error("Failed to store session into Valkey. key={}", key, e);
            throw new DemoException("Valkey へのセッション保存に失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * 保存済みのセッションハッシュを読み戻す。存在しない場合は空の Map を返す。
     * ElastiCache のみを確認する API で「書き込んだ内容を読めること」を検証するために使う。
     */
    public Map<String, String> getSession(String sessionId) {
        String key = sessionPrefix + sessionId;
        try {
            HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
            Map<String, String> data = hashOps.entries(key);
            log.info("Valkey session fetched. key={}, fields={}", key, data.size());
            return data;
        } catch (RuntimeException e) {
            log.error("Failed to fetch session from Valkey. key={}", key, e);
            throw new DemoException("Valkey からのセッション取得に失敗しました: " + e.getMessage(), e);
        }
    }
}
