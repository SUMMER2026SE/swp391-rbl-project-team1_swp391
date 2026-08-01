package com.sportvenue.service.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Client gọi Groq (OpenAI-compatible) — 1 lệnh gọi blocking/lượt chat, JSON mode bật để đảm bảo
 * output là JSON hợp lệ. Không hỗ trợ tool-calling/streaming — kiến trúc mới dùng đơn-JSON theo
 * lượt thay cho multi-turn tool-calling (xem docs/ai_chatbot_rebuild_plan.md).
 *
 * Sử dụng GroqKeyPoolManager để tự động failover giữa nhiều API keys khi bị rate limit.
 */
@Slf4j
@Component
public class GroqClient {

    private static final int MAX_RETRIES_PER_KEY = 1;
    private static final long RETRY_DELAY_MS = 1500;

    private final RestClient.Builder restClientBuilder;
    private final GroqKeyPoolManager keyPoolManager;

    public GroqClient(@Value("${app.ai.base-url}") String baseUrl,
                      @Value("${app.ai.api-keys:}") String apiKeysCsv,
                      GroqKeyPoolManager keyPoolManager) {
        this.restClientBuilder = RestClient.builder().baseUrl(baseUrl);
        this.keyPoolManager = keyPoolManager;

        // Parse comma-separated API keys
        if (apiKeysCsv != null && !apiKeysCsv.trim().isEmpty()) {
            List<String> keys = Arrays.stream(apiKeysCsv.split(","))
                    .map(String::trim)
                    .filter(k -> !k.isEmpty())
                    .collect(Collectors.toList());
            keyPoolManager.setApiKeys(keys);
        } else {
            log.warn("No GROQ_API_KEYS configured - GroqClient will fail!");
        }

        log.info("GroqClient initialized. Key pool count: {}", keyPoolManager.getTotalKeyCount());
    }

    public record ChatMessage(String role, String content) {
    }

    public record GroqResult(String text, int inputTokens, int outputTokens, int totalTokens) {
    }

    /**
     * Gọi Groq với JSON mode bật + lịch sử hội thoại — trả về JSON thô (caller tự parse thành
     * {@link ExtractedIntentResult}).
     * Tự động failover giữa các key khi bị rate limit.
     */
    public GroqResult chatJson(String model, String systemPrompt, List<ChatMessage> history, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", systemPrompt));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(new ChatMessage("user", userMessage));

        JsonChatRequest requestBody = new JsonChatRequest(model, messages, 0.1, new ResponseFormat("json_object"));

        int totalKeys = keyPoolManager.getTotalKeyCount();
        int maxTotalRetries = totalKeys * MAX_RETRIES_PER_KEY;
        int attempt = 0;
        String lastUsedKey = null;

        while (attempt < maxTotalRetries) {
            String apiKey = resolveNextKey(lastUsedKey);
            lastUsedKey = apiKey;

            RestClient restClient = restClientBuilder
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .build();

            try {
                ChatCompletionResponse response = doRequest(restClient, requestBody);

                if (response == null || response.choices() == null || response.choices().isEmpty()) {
                    throw new LlmGatewayException(LlmGatewayException.Kind.UNKNOWN, "Groq trả về phản hồi rỗng/không hợp lệ");
                }

                String text = response.choices().get(0).message().content();
                Usage usage = response.usage();
                return new GroqResult(
                        text,
                        usage != null ? usage.promptTokens() : 0,
                        usage != null ? usage.completionTokens() : 0,
                        usage != null ? usage.totalTokens() : 0
                );

            } catch (LlmGatewayException e) {
                if (e.getKind() == LlmGatewayException.Kind.RATE_LIMITED) {
                    attempt = handleRateLimited(e, apiKey, attempt, maxTotalRetries);
                } else if (e.getKind() == LlmGatewayException.Kind.AUTH_ERROR) {
                    attempt = handleExhausted(e, apiKey, attempt, maxTotalRetries);
                } else {
                    throw e;
                }
            } catch (RestClientException e) {
                attempt = handleRestClientFailure(e, apiKey, attempt, maxTotalRetries);
            }
        }

        throw new LlmGatewayException(LlmGatewayException.Kind.UNKNOWN,
                "Groq request failed after " + maxTotalRetries + " attempts");
    }

    /** Lấy key tiếp theo còn khả dụng từ pool, log nếu vừa chuyển sang key khác do rate limit. */
    private String resolveNextKey(String lastUsedKey) {
        String apiKey = keyPoolManager.getNextAvailableKey();
        if (apiKey == null) {
            throw new LlmGatewayException(LlmGatewayException.Kind.RATE_LIMITED, "Không có API key nào khả dụng");
        }
        if (lastUsedKey != null && !lastUsedKey.equals(apiKey)) {
            log.info("Switching from key ***{} to key ***{} due to rate limit", maskKey(lastUsedKey), maskKey(apiKey));
        }
        return apiKey;
    }

    /** Đánh dấu key hiện tại bị rate limit; trả về attempt kế tiếp, throw nếu đã hết lượt thử. */
    private int handleRateLimited(LlmGatewayException e, String apiKey, int attempt, int maxTotalRetries) {
        Long retryAfter = extractRetryAfter(e);
        keyPoolManager.markRateLimited(apiKey, retryAfter);

        int nextAttempt = attempt + 1;
        log.warn("Rate limited on key ***{}, trying next key (attempt {}/{})", maskKey(apiKey), nextAttempt, maxTotalRetries);

        if (nextAttempt >= maxTotalRetries) {
            log.error("All API keys rate limited after {} attempts", maxTotalRetries);
            throw e;
        }
        return nextAttempt;
    }

    /** Đánh dấu key hiện tại bị kiệt quệ (hết credit); trả về attempt kế tiếp. */
    private int handleExhausted(LlmGatewayException e, String apiKey, int attempt, int maxTotalRetries) {
        keyPoolManager.markExhausted(apiKey);

        int nextAttempt = attempt + 1;
        log.warn("Exhausted (Auth/Credit Error) on key ***{}, trying next key (attempt {}/{})", maskKey(apiKey), nextAttempt, maxTotalRetries);

        if (nextAttempt >= maxTotalRetries) {
            log.error("All API keys exhausted after {} attempts", maxTotalRetries);
            throw e;
        }
        return nextAttempt;
    }

    /** Request lỗi không phải rate limit — chờ 1 khoảng ngắn rồi thử key kế tiếp, throw nếu đã hết lượt thử. */
    private int handleRestClientFailure(RestClientException e, String apiKey, int attempt, int maxTotalRetries) {
        int nextAttempt = attempt + 1;
        log.warn("Groq request failed with key ***{} (attempt {}/{}): {}", maskKey(apiKey), nextAttempt, maxTotalRetries, e.getMessage());

        if (nextAttempt >= maxTotalRetries) {
            throw new LlmGatewayException(LlmGatewayException.Kind.TIMEOUT,
                    "Không gọi được Groq API sau " + maxTotalRetries + " lần thử: " + e.getMessage(), e);
        }
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmGatewayException(LlmGatewayException.Kind.UNKNOWN, "Interrupted", ie);
        }
        return nextAttempt;
    }

    private ChatCompletionResponse doRequest(RestClient restClient, JsonChatRequest requestBody) {
        String lastMessage = requestBody.messages().isEmpty() ? "N/A" :
                requestBody.messages().get(requestBody.messages().size() - 1).content();
        log.info(">>> GROQ API CALL - Message: {}", lastMessage.substring(0, Math.min(50, lastMessage.length())));

        try {
            return restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(ChatCompletionResponse.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new LlmGatewayException(LlmGatewayException.Kind.AUTH_ERROR,
                    "Groq API key sai hoặc hết hạn", e);
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new LlmGatewayException(LlmGatewayException.Kind.RATE_LIMITED,
                    "Groq đã vượt giới hạn tốc độ (rate limit)", e);
        } catch (RestClientException e) {
            throw e;
        }
    }

    private Long extractRetryAfter(LlmGatewayException e) {
        // Try to extract Retry-After from exception message or headers
        // For now, return null to use default cooldown
        return null;
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 4) {
            return "????";
        }
        return key.substring(key.length() - 4);
    }

    // ─── Internal request/response DTOs ──────────────────────────────────────

    record JsonChatRequest(
            String model,
            List<ChatMessage> messages,
            double temperature,
            @JsonProperty("response_format") ResponseFormat responseFormat
    ) {
    }

    record ResponseFormat(String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionResponse(List<Choice> choices, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(ChatMessage message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens
    ) {
    }
}
