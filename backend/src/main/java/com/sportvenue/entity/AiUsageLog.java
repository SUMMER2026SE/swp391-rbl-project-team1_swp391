package com.sportvenue.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_usage_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "feature", length = 50)
    private String feature;

    @Column(name = "model_used", length = 100)
    private String modelUsed;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;
    
    @Column(name = "user_input", columnDefinition = "TEXT")
    private String userInput;
    
    @Column(name = "raw_llm_response", columnDefinition = "TEXT")
    private String rawLlmResponse;
    
    @Column(name = "parsed_intent", length = 50)
    private String parsedIntent;
    
    @Column(name = "resolved_stadium_id")
    private Integer resolvedStadiumId;
    
    @Column(name = "resolved_slot_id")
    private Integer resolvedSlotId;
    
    @Column(name = "action_result", columnDefinition = "TEXT")
    private String actionResult;

    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "rule_override")
    private Boolean ruleOverride;

    @Column(name = "validation_result", length = 50)
    private String validationResult;

    @Column(name = "error_reason")
    private String errorReason;

    @Column(name = "processing_time_ai_ms")
    private Long processingTimeAiMs;

    @Column(name = "processing_time_handler_ms")
    private Long processingTimeHandlerMs;

    @Column(name = "redis_hit")
    private Boolean redisHit;

    @Column(name = "handler_name", length = 100)
    private String handlerName;

    @Column(name = "suggestion_type", length = 50)
    private String suggestionType;

    @Column(name = "sub_plan_steps")
    private Integer subPlanSteps;

    @Column(name = "sub_plan_completed")
    private Integer subPlanCompleted;

    @Column(name = "sub_plan_rolled_back")
    private Boolean subPlanRolledBack = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
