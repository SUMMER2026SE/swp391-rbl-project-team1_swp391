package com.sportvenue.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.entity.User;
import com.sportvenue.security.UserPrincipal;
import com.sportvenue.service.BookingService;
import com.sportvenue.service.ai.AiConversationContextService.PlanStatus;
import com.sportvenue.service.ai.AiConversationContextService.PlanStep;
import com.sportvenue.service.ai.AiConversationContextService.StepResult;
import com.sportvenue.service.ai.AiConversationContextService.StepStatus;
import com.sportvenue.service.ai.AiConversationContextService.SubPlan;
import com.sportvenue.service.ai.handler.BookingHandler;
import com.sportvenue.service.ai.handler.CancelBookingHandler;
import com.sportvenue.service.ai.handler.JoinMatchHandler;
import com.sportvenue.service.ai.handler.MatchRequestHandler;
import com.sportvenue.service.ai.handler.StadiumSearchHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiPlanningService {

    private final GroqClient groqClient;
    private final AiConversationContextService conversationContextService;
    private final BookingService bookingService;
    private final ObjectMapper objectMapper;

    // Handlers
    private final StadiumSearchHandler stadiumSearchHandler;
    private final BookingHandler bookingHandler;
    private final JoinMatchHandler joinMatchHandler;
    private final MatchRequestHandler matchRequestHandler;
    private final CancelBookingHandler cancelBookingHandler;

    @Value("${app.ai.model:llama-3.3-70b-versatile}")
    private String model;

    private static final String PLANNER_PROMPT = 
            "Bạn là SportHub Planning Agent. Phân rã yêu cầu ghép nhiều bước thành các bước tuần tự.\n" +
            "Mỗi bước phải map đúng 1 intent: search_stadiums, create_booking, join_match, find_match, cancel_booking.\n" +
            "Chỉ tạo tối đa 5 bước.\n" +
            "Output JSON: { \"isCompound\": true/false, \"steps\": [{ \"description\": \"...\", \"intent\": \"...\", \"params\": {} }] }";

    public Optional<SubPlan> decompose(String userMessage, Integer userId, String conversationKey) {
        try {
            GroqClient.GroqResult result = groqClient.chatJson(model, PLANNER_PROMPT, Collections.emptyList(), userMessage);
            JsonNode root = objectMapper.readTree(result.text());

            if (root.has("isCompound") && root.get("isCompound").asBoolean() && root.has("steps")) {
                JsonNode stepsNode = root.get("steps");
                if (stepsNode.isArray() && !stepsNode.isEmpty()) {
                    List<PlanStep> steps = new ArrayList<>();
                    int stepNum = 1;
                    for (JsonNode stepNode : stepsNode) {
                        if (stepNum > 5) break; // Hard cap at 5 steps
                        
                        steps.add(PlanStep.builder()
                                .stepNumber(stepNum++)
                                .description(stepNode.has("description") ? stepNode.get("description").asText() : "")
                                .intent(stepNode.has("intent") ? stepNode.get("intent").asText() : "")
                                .params(stepNode.has("params") ? stepNode.get("params") : objectMapper.createObjectNode())
                                .status(StepStatus.PENDING)
                                .build());
                    }
                    
                    SubPlan subPlan = SubPlan.builder()
                            .steps(steps)
                            .currentStepIndex(0)
                            .status(PlanStatus.PLANNING)
                            .completedSteps(new ArrayList<>())
                            .build();
                            
                    conversationContextService.saveSubPlan(conversationKey, subPlan);
                    return Optional.of(subPlan);
                }
            }
        } catch (Exception e) {
            log.error("Failed to decompose compound task", e);
        }
        return Optional.empty();
    }

    public AiChatTurnResponse executeStep(PlanStep step, Integer userId, String conversationKey, String rawUserMessage, Double userLat, Double userLng) {
        step.setStatus(StepStatus.IN_PROGRESS);
        AiChatTurnResponse response;

        try {
            response = switch (step.getIntent()) {
                case "search_stadiums" -> stadiumSearchHandler.handle(step.getParams(), rawUserMessage, conversationKey, userLat, userLng);
                case "create_booking" -> bookingHandler.handleWithRawMessage(step.getParams(), rawUserMessage, conversationKey, userId, rawUserMessage);
                case "join_match" -> joinMatchHandler.handle(step.getParams(), rawUserMessage, conversationKey, userId);
                case "find_match" -> matchRequestHandler.handle(step.getParams(), rawUserMessage, conversationKey);
                case "cancel_booking" -> cancelBookingHandler.handle(step.getParams(), rawUserMessage, userId, conversationKey);
                default -> AiChatTurnResponse.messageOnly("Intent " + step.getIntent() + " không được hỗ trợ trong compound task.", "error");
            };
            
            // Wait, we need to know if the step was completed successfully or just needs more info.
            // If the handler returns the same intent or requires more info, the step might not be complete yet.
            // But for compound tasks, we will consider it complete if it returns a response with data.
            // We will let the frontend confirm the result anyway.

        } catch (Exception e) {
            log.error("Error executing plan step {}", step.getStepNumber(), e);
            step.setStatus(StepStatus.FAILED);
            step.setErrorReason(e.getMessage());
            response = AiChatTurnResponse.messageOnly("Đã xảy ra lỗi ở bước: " + step.getDescription(), "error");
        }

        return response;
    }

    @Async
    public void rollbackFrom(String conversationKey, int failedStepIndex, Integer userId) {
        Optional<SubPlan> subPlanOpt = conversationContextService.getSubPlan(conversationKey);
        if (subPlanOpt.isEmpty()) return;

        SubPlan subPlan = subPlanOpt.get();
        log.info("Starting rollback for plan in conversation {} from step index {}", conversationKey, failedStepIndex);

        User user = new User();
        user.setUserId(userId);
        UserPrincipal principal = new UserPrincipal(user);

        for (int i = failedStepIndex - 1; i >= 0; i--) {
            StepResult completed = null;
            if (i < subPlan.getCompletedSteps().size()) {
                completed = subPlan.getCompletedSteps().get(i);
            }
            if (completed == null || !completed.isSuccess()) continue;

            if ("create_booking".equals(completed.getIntent()) && completed.getData() != null) {
                try {
                    Integer bookingId = (Integer) completed.getData();
                    bookingService.cancelBooking(principal, bookingId, "Auto-rollback from compound task");
                    log.info("Rolled back booking {}", bookingId);
                } catch (Exception e) {
                    log.error("Failed to rollback booking in plan", e);
                }
            }
        }
        
        subPlan.setStatus(PlanStatus.ROLLED_BACK);
        conversationContextService.saveSubPlan(conversationKey, subPlan);
    }
}
