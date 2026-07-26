package com.sportvenue.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Cache "kết quả vừa hiển thị" theo lượt chat — giải quyết câu hỏi kiểu "sân đầu tiên/thứ hai"
 * ở lượt sau bằng cách tra ID THẬT theo thứ tự đã hiển thị, thay vì để LLM tự đoán/bịa ID.
 * Đồng thời giữ lại currentStadiumId để các bước booking không cần LLM nhắc lại ID sân.
 * Redis lỗi/timeout không được làm crash luồng chat chính — mọi thao tác đọc/ghi đều nuốt lỗi và log warn.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiConversationContextService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "ai_ctx:";
    private static final Duration TTL = Duration.ofMinutes(15);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingAction {
        private String intent;
        private java.util.Map<String, Object> data;
        private String missingField;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConversationContext {
        private List<Integer> lastShownStadiumIds;
        private List<Integer> lastShownMatchIds;
        private List<Integer> lastShownSlotIds;
        private List<Integer> lastShownBookingIds; // CancelBookingHandler: danh sách booking vừa show để user chọn
        private Integer currentStadiumId;
        private PendingAction pendingAction;

        // Cancel booking flow state
        private Boolean awaitingCancelConfirmation;
        private Integer pendingCancelBookingId; // booking đang chờ user confirm

        // Expanded context
        private String currentIntent;
        private String currentSport;
        private String currentDistrict;
        private String currentProvince; // Bug #3: Cần lưu province để ưu tiên khu vực người dùng nhắc
        private String currentDate;
        private String currentTime;
        private java.util.Map<String, Object> bookingDraft;
        private java.util.Map<String, Object> joinMatchDraft;
        
        // Multi-Step Planning (Compound Task)
        private SubPlan subPlan;

        // Optimistic locking version for SubPlan updates — prevents race conditions
        // when 2 messages arrive simultaneously for the same conversationKey
        private Long subPlanVersion;
    }

    public enum PlanStatus {
        PLANNING, IN_PROGRESS, PAUSED, COMPLETED, FAILED, ROLLED_BACK
    }

    public enum StepStatus {
        PENDING, IN_PROGRESS, COMPLETED, SKIPPED, FAILED, ROLLED_BACK
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StepResult {
        private int stepNumber;
        private String intent;
        private boolean success;
        private String message;
        private Object data; // Could store bookingId, matchId etc for rollback
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlanStep {
        private int stepNumber;
        private String description;
        private String intent;
        private com.fasterxml.jackson.databind.JsonNode params;
        private StepStatus status = StepStatus.PENDING;
        private String errorReason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SubPlan {
        private List<PlanStep> steps;
        private int currentStepIndex;
        private PlanStatus status = PlanStatus.PLANNING;
        private List<StepResult> completedSteps;
        private java.time.LocalDateTime expiresAt;
    }

    public void saveLastShownStadiums(String conversationKey, List<Integer> stadiumIds) {
        if (conversationKey == null || stadiumIds == null || stadiumIds.isEmpty()) {
            return;
        }
        ConversationContext ctx = load(conversationKey).orElse(new ConversationContext());
        ctx.setLastShownStadiumIds(stadiumIds);
        save(conversationKey, ctx);
    }

    public void saveLastShownMatches(String conversationKey, List<Integer> matchIds) {
        if (conversationKey == null || matchIds == null || matchIds.isEmpty()) {
            return;
        }
        ConversationContext ctx = load(conversationKey).orElse(new ConversationContext());
        ctx.setLastShownMatchIds(matchIds);
        save(conversationKey, ctx);
    }

    public void saveLastShownSlots(String conversationKey, List<Integer> slotIds) {
        if (conversationKey == null || slotIds == null || slotIds.isEmpty()) {
            return;
        }
        ConversationContext ctx = load(conversationKey).orElse(new ConversationContext());
        ctx.setLastShownSlotIds(slotIds);
        save(conversationKey, ctx);
    }

    public void saveLastShownBookings(String conversationKey, List<Integer> bookingIds) {
        if (conversationKey == null || bookingIds == null || bookingIds.isEmpty()) {
            return;
        }
        ConversationContext ctx = load(conversationKey).orElse(new ConversationContext());
        ctx.setLastShownBookingIds(bookingIds);
        save(conversationKey, ctx);
    }

    public List<Integer> getLastShownBookingIds(String conversationKey) {
        if (conversationKey == null) {
            return null;
        }
        return load(conversationKey).map(ConversationContext::getLastShownBookingIds).orElse(null);
    }

    public Optional<Integer> resolveBookingIdByIndex(String conversationKey, int targetIndex) {
        if (conversationKey == null) {
            return Optional.empty();
        }
        return load(conversationKey)
                .map(ConversationContext::getLastShownBookingIds)
                .filter(ids -> ids != null && targetIndex >= 0 && targetIndex < ids.size())
                .map(ids -> ids.get(targetIndex));
    }

    public Optional<Integer> resolveLastBookingId(String conversationKey) {
        if (conversationKey == null) {
            return Optional.empty();
        }
        return load(conversationKey)
                .map(ConversationContext::getLastShownBookingIds)
                .filter(ids -> ids != null && !ids.isEmpty())
                .map(ids -> ids.get(ids.size() - 1)); // Last item = đơn cuối cùng
    }

    // Cancel booking flow methods
    public boolean isAwaitingCancelConfirmation(String conversationKey) {
        return load(conversationKey)
                .map(ctx -> Boolean.TRUE.equals(ctx.getAwaitingCancelConfirmation()))
                .orElse(false);
    }

    public void setAwaitingCancelConfirmation(String conversationKey, Integer bookingId) {
        ConversationContext ctx = load(conversationKey).orElse(new ConversationContext());
        ctx.setAwaitingCancelConfirmation(true);
        ctx.setPendingCancelBookingId(bookingId);
        save(conversationKey, ctx);
    }

    public Optional<Integer> getPendingCancelBookingId(String conversationKey) {
        return load(conversationKey)
                .map(ConversationContext::getPendingCancelBookingId);
    }

    public void clearAwaitingCancelConfirmation(String conversationKey) {
        ConversationContext ctx = load(conversationKey).orElse(new ConversationContext());
        ctx.setAwaitingCancelConfirmation(false);
        ctx.setPendingCancelBookingId(null);
        save(conversationKey, ctx);
    }

    public void saveCurrentStadiumId(String conversationKey, Integer stadiumId) {
        if (conversationKey == null || stadiumId == null) {
            return;
        }
        ConversationContext ctx = load(conversationKey).orElse(new ConversationContext());
        ctx.setCurrentStadiumId(stadiumId);
        save(conversationKey, ctx);
    }

    public Optional<Integer> getCurrentStadiumId(String conversationKey) {
        if (conversationKey == null) {
            return Optional.empty();
        }
        return load(conversationKey).map(ConversationContext::getCurrentStadiumId);
    }

    public Optional<Integer> resolveStadiumIdByIndex(String conversationKey, int targetIndex) {
        if (conversationKey == null) {
            return Optional.empty();
        }
        return load(conversationKey)
                .map(ConversationContext::getLastShownStadiumIds)
                .filter(ids -> ids != null && targetIndex >= 0 && targetIndex < ids.size())
                .map(ids -> ids.get(targetIndex));
    }

    public List<Integer> getLastShownStadiumIds(String conversationKey) {
        if (conversationKey == null) {
            return null;
        }
        return load(conversationKey).map(ConversationContext::getLastShownStadiumIds).orElse(null);
    }

    public Optional<Integer> resolveMatchIdByIndex(String conversationKey, int matchIndex) {
        if (conversationKey == null) {
            return Optional.empty();
        }
        return load(conversationKey)
                .map(ConversationContext::getLastShownMatchIds)
                .filter(ids -> ids != null && matchIndex >= 0 && matchIndex < ids.size())
                .map(ids -> ids.get(matchIndex));
    }

    public Optional<Integer> resolveSlotIdByIndex(String conversationKey, int slotIndex) {
        if (conversationKey == null) {
            return Optional.empty();
        }
        return load(conversationKey)
                .map(ConversationContext::getLastShownSlotIds)
                .filter(ids -> ids != null && slotIndex >= 0 && slotIndex < ids.size())
                .map(ids -> ids.get(slotIndex));
    }

    public void savePendingAction(String conversationKey, PendingAction pendingAction) {
        if (conversationKey == null) {
            return;
        }
        ConversationContext ctx = load(conversationKey).orElse(new ConversationContext());
        ctx.setPendingAction(pendingAction);
        save(conversationKey, ctx);
    }

    public Optional<PendingAction> getPendingAction(String conversationKey) {
        if (conversationKey == null) {
            return Optional.empty();
        }
        return load(conversationKey).map(ConversationContext::getPendingAction);
    }

    public void clearPendingAction(String conversationKey) {
        if (conversationKey == null) {
            return;
        }
        ConversationContext ctx = load(conversationKey).orElse(new ConversationContext());
        ctx.setPendingAction(null);
        save(conversationKey, ctx);
    }

    // Expanded context methods
    public void saveCurrentFilters(String conversationKey, String sport, String district, String province, String date, String time) {
        if (conversationKey == null) {
            return;
        }
        ConversationContext ctx = load(conversationKey).orElse(new ConversationContext());
        if (sport != null) {
            ctx.setCurrentSport(sport);
        }
        if (district != null) {
            ctx.setCurrentDistrict(district);
        }
        if (province != null) {
            ctx.setCurrentProvince(province); // Bug #3: Lưu province để ưu tiên khu vực người dùng nhắc
        }
        if (date != null) {
            ctx.setCurrentDate(date);
        }
        if (time != null) {
            ctx.setCurrentTime(time);
        }
        save(conversationKey, ctx);
    }

    /**
     * Get currentDate from conversation context as LocalDate.
     */
    public Optional<java.time.LocalDate> getCurrentDate(String conversationKey) {
        if (conversationKey == null) {
            return Optional.empty();
        }
        return load(conversationKey)
                .map(ConversationContext::getCurrentDate)
                .filter(dateStr -> dateStr != null && !dateStr.isBlank())
                .map(java.time.LocalDate::parse);
    }

    public Optional<ConversationContext> getContext(String conversationKey) {
        if (conversationKey == null) {
            return Optional.empty();
        }
        return load(conversationKey);
    }
    
    public void saveBookingDraft(String conversationKey, java.util.Map<String, Object> draft) {
        if (conversationKey == null) {
            return;
        }
        ConversationContext ctx = load(conversationKey).orElse(new ConversationContext());
        ctx.setBookingDraft(draft);
        save(conversationKey, ctx);
    }

    public boolean isAwaitingBookingConfirmation(String conversationKey) {
        return load(conversationKey)
                .map(ctx -> ctx.getBookingDraft() != null && !ctx.getBookingDraft().isEmpty())
                .orElse(false);
    }

    public void deleteJoinMatchDraft(String conversationKey) {
        if (conversationKey == null) return;
        ConversationContext ctx = load(conversationKey).orElse(new ConversationContext());
        ctx.setJoinMatchDraft(null);
        save(conversationKey, ctx);
    }
    
    // SubPlan Methods
    public void saveSubPlan(String conversationKey, SubPlan subPlan) {
        if (conversationKey == null) return;
        // Use optimistic locking for SubPlan updates to prevent race conditions
        boolean saved = saveSubPlanOptimistic(conversationKey, subPlan);
        if (!saved) {
            log.warn("Optimistic lock conflict saving SubPlan for conversationKey={}, retrying once", conversationKey);
            saved = saveSubPlanOptimistic(conversationKey, subPlan);
            if (!saved) {
                log.error("Failed to save SubPlan after retry for conversationKey={}", conversationKey);
            }
        }
    }

    /**
     * Saves SubPlan using Redis WATCH/MULTI/EXEC for optimistic locking.
     * Returns true if saved successfully, false if a version conflict was detected.
     */
    private boolean saveSubPlanOptimistic(String conversationKey, SubPlan subPlan) {
        if (conversationKey == null) return false;

        String redisKey = KEY_PREFIX + conversationKey;

        try {
            return redisTemplate.execute(new RedisCallback<Boolean>() {
                @Override
                public Boolean doInRedis(RedisConnection connection)
                        throws DataAccessException {
                    byte[] key = redisKey.getBytes();
                    long maxRetries = 3;

                    for (long attempt = 0; attempt < maxRetries; attempt++) {
                        connection.watch(key);

                        byte[] existing = connection.get(key);
                        Long currentVersion = null;
                        if (existing != null) {
                            try {
                                ConversationContext existingCtx = objectMapper.readValue(
                                        new String(existing), ConversationContext.class);
                                currentVersion = existingCtx.getSubPlanVersion();
                            } catch (Exception e) {
                                // New key or corrupt data — proceed with version 0
                                currentVersion = 0L;
                            }
                        }

                        // Build updated context
                        ConversationContext ctx;
                        if (existing != null) {
                            try {
                                ctx = objectMapper.readValue(new String(existing), ConversationContext.class);
                            } catch (Exception e) {
                                ctx = new ConversationContext();
                            }
                        } else {
                            ctx = new ConversationContext();
                        }

                        ctx.setSubPlan(subPlan);
                        ctx.setSubPlanVersion(currentVersion != null ? currentVersion + 1 : 1L);
                        if (subPlan != null) {
                            subPlan.setExpiresAt(java.time.LocalDateTime.now().plus(TTL));
                        }

                        String ctxJson;
                        try {
                            ctxJson = objectMapper.writeValueAsString(ctx);
                        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                            return false;
                        }
                        connection.multi();
                        connection.stringCommands().setEx(key, TTL.toSeconds(), ctxJson.getBytes());
                        List<Object> results = connection.exec();

                        if (results != null && !results.isEmpty()) {
                            return true; // SUCCESS
                        }
                        // results == null → WATCH was triggered, retry
                        log.debug("Optimistic lock conflict on attempt {} for key {}", attempt + 1, redisKey);
                    }
                    return false; // All retries exhausted
                }
            });
        } catch (Exception e) {
            log.warn("Error during optimistic SubPlan save for {}: {}", conversationKey, e.getMessage());
            return false;
        }
    }

    public Optional<SubPlan> getSubPlan(String conversationKey) {
        return load(conversationKey).map(ConversationContext::getSubPlan)
                .filter(plan -> plan.getExpiresAt() == null || plan.getExpiresAt().isAfter(java.time.LocalDateTime.now()));
    }

    public void deleteSubPlan(String conversationKey) {
        if (conversationKey == null) return;
        ConversationContext ctx = load(conversationKey).orElse(new ConversationContext());
        ctx.setSubPlan(null);
        save(conversationKey, ctx);
    }


    public void clearBookingDraft(String conversationKey) {
        if (conversationKey == null) {
            return;
        }
        ConversationContext ctx = load(conversationKey).orElse(new ConversationContext());
        ctx.setBookingDraft(null);
        save(conversationKey, ctx);
    }

    private void save(String conversationKey, ConversationContext results) {
        try {
            String json = objectMapper.writeValueAsString(results);
            redisTemplate.opsForValue().set(KEY_PREFIX + conversationKey, json, TTL);
        } catch (Exception e) {
            log.warn("Không lưu được context: {}", e.getMessage());
        }
    }

    private Optional<ConversationContext> load(String conversationKey) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + conversationKey);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(json, ConversationContext.class));
        } catch (Exception e) {
            log.warn("Không đọc được context: {}", e.getMessage());
            return Optional.empty();
        }
    }
}

