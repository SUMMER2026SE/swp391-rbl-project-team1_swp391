package com.sportvenue.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Quản lý pool API keys cho Groq - tự động failover khi bị rate limit.
 * Các key thuộc các tài khoản Groq khác nhau để đảm bảo rate limit độc lập.
 */
@Slf4j
@Component
public class GroqKeyPoolManager {

    private static final long DEFAULT_COOLDOWN_MS = 60000; // 60 seconds
    private static final long MIN_COOLDOWN_MS = 30000; // 30 seconds minimum

    private final List<String> apiKeys = new ArrayList<>();
    private final AtomicInteger currentKeyIndex = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Long> coolingDownKeys = new ConcurrentHashMap<>();

    public GroqKeyPoolManager() {
        // Will be initialized via setApiKeys() after Spring loads the config
    }

    public void setApiKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            log.warn("GroqKeyPoolManager: No API keys provided!");
            return;
        }
        apiKeys.clear();
        apiKeys.addAll(keys);
        log.info("GroqKeyPoolManager: Initialized with {} API keys", apiKeys.size());
    }

    /**
     * Lấy key tiếp theo đang AVAILABLE (không đang cooldown).
     * Dùng round-robin: ưu tiên key theo thứ tự, bỏ qua key đang cooldown.
     */
    public synchronized String getNextAvailableKey() {
        if (apiKeys.isEmpty()) {
            log.error("GroqKeyPoolManager: No API keys available!");
            return null;
        }

        int startIndex = currentKeyIndex.get();
        int checked = 0;

        while (checked < apiKeys.size()) {
            int index = currentKeyIndex.getAndIncrement() % apiKeys.size();
            String key = apiKeys.get(index);

            Long cooldownUntil = coolingDownKeys.get(key);
            if (cooldownUntil == null || System.currentTimeMillis() >= cooldownUntil) {
                // Key is available
                coolingDownKeys.remove(key); // Clean up expired cooldown
                log.debug("GroqKeyPoolManager: Using key ****{}", maskKey(key));
                return key;
            }

            log.debug("GroqKeyPoolManager: Skipping key ****{} - still in cooldown", maskKey(key));
            checked++;
        }

        // All keys are cooling down - return the one with shortest remaining cooldown
        log.warn("GroqKeyPoolManager: All keys in cooldown, returning first key anyway");
        return apiKeys.get(startIndex);
    }

    /**
     * Đánh dấu key bị rate limit - chuyển sang trạng thái COOLING_DOWN.
     * Đọc Retry-After header nếu có, hoặc dùng default cooldown.
     */
    public void markRateLimited(String key, Long retryAfterSeconds) {
        if (key == null || !apiKeys.contains(key)) {
            return;
        }

        long cooldownMs;
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            cooldownMs = Math.max(retryAfterSeconds * 1000, MIN_COOLDOWN_MS);
        } else {
            cooldownMs = DEFAULT_COOLDOWN_MS;
        }

        long cooldownUntil = System.currentTimeMillis() + cooldownMs;
        coolingDownKeys.put(key, cooldownUntil);

        log.warn("GroqKeyPoolManager: Key ***{} marked as RATE LIMITED, cooldown for {} seconds (until {})",
                maskKey(key), cooldownMs / 1000, cooldownUntil);
    }

    /**
     * Đánh dấu key bị lỗi xác thực (401/403) - hết credit hoặc bị block.
     * Chuyển sang trạng thái cooldown rất lâu (ví dụ 24h) để tránh dùng lại liên tục.
     */
    public void markExhausted(String key) {
        if (key == null || !apiKeys.contains(key)) {
            return;
        }

        long cooldownMs = 24 * 60 * 60 * 1000L; // 24 hours
        long cooldownUntil = System.currentTimeMillis() + cooldownMs;
        coolingDownKeys.put(key, cooldownUntil);

        log.error("GroqKeyPoolManager: Key ***{} marked as EXHAUSTED (Auth/Credit Error), cooldown for 24h", maskKey(key));
    }

    /**
     * Lấy số lượng key đang AVAILABLE (không cooldown).
     */
    public int getAvailableKeyCount() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (String key : apiKeys) {
            Long cooldownUntil = coolingDownKeys.get(key);
            if (cooldownUntil == null || now >= cooldownUntil) {
                count++;
            }
        }
        return count;
    }

    /**
     * Kiểm tra có key nào available không.
     */
    public boolean hasAvailableKeys() {
        return getAvailableKeyCount() > 0;
    }

    /**
     * Lấy tổng số key.
     */
    public int getTotalKeyCount() {
        return apiKeys.size();
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 4) {
            return "????";
        }
        return key.substring(key.length() - 4);
    }
}
