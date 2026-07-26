package com.sportvenue.service.ai.suggestion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSuggestion {
    private String message;
    private String suggestionType; // PATTERN_MATCH, MATCH_INVITE, BOOKING_REMINDER, LLM_SUGGESTION
    private Double confidenceScore;
    private Integer matchedStadiumId;
    private Integer matchedMatchId;
    private boolean generatedByLlm;
    private String llmModel;
    private Integer llmCostTokens;
}
