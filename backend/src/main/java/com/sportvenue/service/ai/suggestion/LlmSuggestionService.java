package com.sportvenue.service.ai.suggestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportvenue.entity.UserPreference;
import com.sportvenue.repository.AiNotificationLogRepository;
import com.sportvenue.service.ai.GroqClient;
import com.sportvenue.service.ai.PersonalizationPromptBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmSuggestionService {

    private final GroqClient groqClient;
    private final AiNotificationLogRepository notificationLogRepository;
    private final PersonalizationPromptBuilder personalizationPromptBuilder;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.model:llama-3.3-70b-versatile}")
    private String model;

    public Optional<AiSuggestion> generateSuggestion(UserPreference pref) {
        if (pref == null || pref.getUserId() == null) {
            return Optional.empty();
        }

        // Rate limit: 1 LLM suggestion per user per day
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        int countToday = notificationLogRepository.countByUserUserIdAndGeneratedByLlmTrueAndCreatedAtAfter(pref.getUserId(), startOfDay);
        if (countToday >= 1) {
            log.info("LLM Suggestion rate limit reached for user {}", pref.getUserId());
            return Optional.empty();
        }

        String profileSection = personalizationPromptBuilder.buildSupplement(pref.getUserId());
        
        String systemPrompt = "Bạn là SportHub AI Assistant. Nhiệm vụ của bạn là tạo ra MỘT câu gợi ý ngắn gọn, hấp dẫn và cá nhân hóa cho người dùng để khuyến khích họ đặt sân thể thao.\n"
                + "Hãy phân tích thông tin người dùng và đưa ra một thông điệp sáng tạo. Format trả về bắt buộc là JSON: {\"suggestion\": \"Thông điệp gợi ý\"}.\n\n"
                + profileSection;

        String userMessage = "Tạo một thông điệp gợi ý (proactive notification) cho tôi. Ngắn gọn, thân thiện.";

        try {
            GroqClient.GroqResult result = groqClient.chatJson(model, systemPrompt, Collections.emptyList(), userMessage);
            
            JsonNode root = objectMapper.readTree(result.text());
            if (root.has("suggestion")) {
                String message = root.get("suggestion").asText();
                return Optional.of(AiSuggestion.builder()
                        .message(message)
                        .suggestionType("LLM_SUGGESTION")
                        .confidenceScore(pref.getConfidenceScore() != null ? pref.getConfidenceScore().doubleValue() : 0.5)
                        .generatedByLlm(true)
                        .llmModel(model)
                        .llmCostTokens(result.totalTokens())
                        .build());
            }
        } catch (Exception e) {
            log.error("Failed to generate LLM suggestion for user {}", pref.getUserId(), e);
        }

        return Optional.empty();
    }
}
