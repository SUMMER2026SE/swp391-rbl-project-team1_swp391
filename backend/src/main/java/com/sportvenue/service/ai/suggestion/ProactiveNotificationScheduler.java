package com.sportvenue.service.ai.suggestion;

import com.sportvenue.entity.AiNotificationLog;
import com.sportvenue.entity.User;
import com.sportvenue.entity.UserPreference;
import com.sportvenue.entity.enums.NotificationType;
import com.sportvenue.repository.AiNotificationLogRepository;
import com.sportvenue.repository.UserPreferenceRepository;
import com.sportvenue.repository.UserRepository;
import com.sportvenue.service.FeatureFlagService;
import com.sportvenue.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProactiveNotificationScheduler {

    private final RuleBasedSuggestionService ruleBasedSuggestionService;
    private final LlmSuggestionService llmSuggestionService;
    private final UserPreferenceRepository userPreferenceRepository;
    private final AiNotificationLogRepository notificationLogRepository;
    private final NotificationService notificationService;
    private final FeatureFlagService featureFlagService;
    private final UserRepository userRepository;

    @Scheduled(cron = "0 */30 * * * *")
    @SchedulerLock(name = "proactive_notification_lock", lockAtMostFor = "25m")
    public void runProactiveSuggestions() {
        if (!featureFlagService.isProactiveNotifications()) {
            log.info("Proactive notifications are disabled via feature flag. Skipping scheduler.");
            return;
        }

        log.info("Starting ProactiveNotificationScheduler...");
        List<UserPreference> preferences = userPreferenceRepository.findAll();
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();

        for (UserPreference pref : preferences) {
            Integer userId = pref.getUserId();

            // 1. Check global rate limit (Max 3 proactive notifications/user/day)
            int notificationsToday = notificationLogRepository.countByUserUserIdAndCreatedAtAfter(userId, startOfDay);
            if (notificationsToday >= 3) {
                continue;
            }

            Optional<AiSuggestion> suggestionOpt = Optional.empty();

            // 2. Try Rule-based suggestion first
            Optional<AiSuggestion> ruleSuggestion = ruleBasedSuggestionService.generateSuggestion(pref);
            if (ruleSuggestion.isPresent()) {
                suggestionOpt = ruleSuggestion;
            } else {
                // 3. Fallback to LLM if rule-based fails and user has enough confidence
                double userConfidence = pref.getConfidenceScore() != null ? pref.getConfidenceScore().doubleValue() : 0.0;
                
                // Gate LLM at confidence >= 0.3
                if (userConfidence >= 0.3) {
                    suggestionOpt = llmSuggestionService.generateSuggestion(pref);
                }
            }

            // 4. Send notification if generated
            if (suggestionOpt.isPresent()) {
                AiSuggestion suggestion = suggestionOpt.get();
                sendNotificationAndLog(userId, suggestion);
            }
        }
        log.info("Finished ProactiveNotificationScheduler.");
    }

    private void sendNotificationAndLog(Integer userId, AiSuggestion suggestion) {
        try {
            // Write to log
            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                return;
            }
            
            AiNotificationLog logEntry = AiNotificationLog.builder()
                    .user(userOpt.get())
                    .suggestionType(suggestion.getSuggestionType())
                    .matchedStadiumId(suggestion.getMatchedStadiumId())
                    .matchedMatchId(suggestion.getMatchedMatchId())
                    .wasSent(true)
                    .generatedByLlm(suggestion.isGeneratedByLlm())
                    .llmModel(suggestion.getLlmModel())
                    .llmCostTokens(suggestion.getLlmCostTokens())
                    .createdAt(LocalDateTime.now())
                    .build();
            
            // Assuming AiNotificationLog triggers the DB insert
            notificationLogRepository.save(logEntry);

            // Send notification
            notificationService.createNotification(
                    userId,
                    "Gợi ý từ SportHub AI",
                    suggestion.getMessage(),
                    NotificationType.AI_SUGGESTION,
                    suggestion.getMatchedStadiumId() != null ? String.valueOf(suggestion.getMatchedStadiumId()) : null
            );
            
            log.info("Sent proactive notification to user {}: {}", userId, suggestion.getSuggestionType());
        } catch (Exception e) {
            log.error("Failed to send notification for user {}", userId, e);
        }
    }
}
