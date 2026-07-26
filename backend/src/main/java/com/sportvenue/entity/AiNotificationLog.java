package com.sportvenue.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "notification_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiNotificationLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "suggestion_type", length = 50, nullable = false)
    private String suggestionType;

    @Column(name = "notification_id")
    private Long notificationId;

    @Column(name = "trigger_reason", columnDefinition = "TEXT")
    private String triggerReason;

    @Column(name = "matched_stadium_id")
    private Integer matchedStadiumId;

    @Column(name = "matched_match_id")
    private Integer matchedMatchId;

    @Column(name = "was_sent")
    private Boolean wasSent;

    @Column(name = "was_clicked")
    private Boolean wasClicked;

    @Column(name = "was_dismissed")
    private Boolean wasDismissed;

    @Column(name = "generated_by_llm")
    private Boolean generatedByLlm;

    @Column(name = "llm_model", length = 100)
    private String llmModel;

    @Column(name = "llm_cost_tokens")
    private Integer llmCostTokens;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
