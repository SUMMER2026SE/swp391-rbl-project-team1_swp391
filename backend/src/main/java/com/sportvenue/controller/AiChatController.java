package com.sportvenue.controller;

import com.sportvenue.dto.request.AiChatTurnRequest;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.dto.response.ApiResponse;
import com.sportvenue.security.UserPrincipal;
import com.sportvenue.service.ai.AiChatService;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Endpoint chat AI — 1 request/response JSON thường (không SSE/streaming). Kiến trúc đơn-JSON
 * thay cho multi-turn tool-calling cũ (xem docs/ai_chatbot_rebuild_plan.md).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;
    private final ProxyManager<byte[]> proxyManager;

    private static final int CUSTOMER_REQUESTS_PER_MINUTE = 10;
    private static final int CUSTOMER_REQUESTS_PER_DAY = 150;
    private static final int GUEST_REQUESTS_PER_MINUTE = 5;
    private static final int GUEST_REQUESTS_PER_DAY = 50;

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<AiChatTurnResponse>> chat(
            @Valid @RequestBody AiChatTurnRequest request,
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            HttpServletRequest httpRequest) {

        Integer userId = userPrincipal != null ? userPrincipal.getUserId() : null;
        String sessionId = httpRequest.getHeader("X-Session-ID");

        // Rate-limit: theo userId (nếu login) hoặc session/IP (guest)
        String identity = userId != null ? "u:" + userId
                : (sessionId != null && !sessionId.isBlank() ? "s:" + sessionId : "ip:" + getClientIp(httpRequest));
        String rateLimitKey = "ai_rate_limit:" + identity;

        // CRITICAL FIX: conversationKey DÙNG userId nếu login, fallback session cho guest.
        // Dùng session cho guest để họ giữ được context trong phiên trình duyệt.
        // Khi login → key đổi sang userId → KHÔNG bị trộn với context guest.
        // Khi logout → key về guest/session → KHÔNG truy cập được context của user đã logout.
        String conversationKey = userId != null ? "u:" + userId
                : ((sessionId != null && !sessionId.isBlank()) ? "s:" + sessionId : "ip:" + getClientIp(httpRequest));

        BucketConfiguration bucketConfig = userId != null ? getCustomerConfig() : getGuestConfig();
        Bucket bucket = proxyManager.builder().build(rateLimitKey.getBytes(StandardCharsets.UTF_8), bucketConfig);

        if (!bucket.tryConsume(1)) {
            log.warn("Rate limit exceeded for: {}", rateLimitKey);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                    ApiResponse.<AiChatTurnResponse>builder()
                            .code(HttpStatus.TOO_MANY_REQUESTS.value())
                            .message("Quá tải yêu cầu hoặc bạn đã hết lượt chat hôm nay. Vui lòng thử lại sau.")
                            .build());
        }

        AiChatTurnResponse response = aiChatService.handleChat(request, userPrincipal, conversationKey);
        return ResponseEntity.ok(ApiResponse.<AiChatTurnResponse>builder()
                .result(response)
                .build());
    }

    private BucketConfiguration getCustomerConfig() {
        return BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(CUSTOMER_REQUESTS_PER_MINUTE).refillGreedy(CUSTOMER_REQUESTS_PER_MINUTE, Duration.ofMinutes(1)))
                .addLimit(limit -> limit.capacity(CUSTOMER_REQUESTS_PER_DAY).refillGreedy(CUSTOMER_REQUESTS_PER_DAY, Duration.ofDays(1)))
                .build();
    }

    private BucketConfiguration getGuestConfig() {
        return BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(GUEST_REQUESTS_PER_MINUTE).refillGreedy(GUEST_REQUESTS_PER_MINUTE, Duration.ofMinutes(1)))
                .addLimit(limit -> limit.capacity(GUEST_REQUESTS_PER_DAY).refillGreedy(GUEST_REQUESTS_PER_DAY, Duration.ofDays(1)))
                .build();
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @DeleteMapping("/chat/context")
    public ResponseEntity<ApiResponse<Void>> clearContext(
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        if (userPrincipal != null && userPrincipal.getUserId() != null) {
            aiChatService.clearContextForUser(userPrincipal.getUserId());
        }
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .result(null)
                .message("Context cleared")
                .build());
    }
}
