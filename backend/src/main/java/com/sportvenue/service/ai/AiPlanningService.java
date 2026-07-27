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
            "QUAN TRỌNG: Trích xuất TẤT CẢ thông tin người dùng cung cấp vào field 'params' của mỗi bước.\n" +
            "Các trường params cần trích xuất: location/address/venue_name (tên quận/huyện/tỉnh/tên sân), " +
            "sport_type/sport (loại sân: bóng đá/đá bóng/bóng chuyền/cầu lông/... hoặc số người như '5 người'), " +
            "date (ngày: mai/hôm nay/sau mai hoặc dd/MM/yyyy), " +
            "time/slot_time (giờ: 17h/17:00/chiều mai), " +
            "player_count (số người chơi), price_range, target_step_index (chỉ mục sân người dùng chọn từ kết quả tìm kiếm).\n" +
            "QUAN TRỌNG: Bạn phải trả lời CHỈ bằng một khối JSON hợp lệ duy nhất, KHÔNG có text hay markdown nào khác.\n" +
            "Output JSON: { \"isCompound\": true/false, \"steps\": [{ \"description\": \"...\", \"intent\": \"...\", \"params\": {location:\"...\", date:\"...\", time:\"...\", sport:\"...\"} }] }";

    public Optional<SubPlan> decompose(String userMessage, Integer userId, String conversationKey) {
        try {
            GroqClient.GroqResult result = groqClient.chatJson(model, PLANNER_PROMPT, Collections.emptyList(), userMessage);
            JsonNode root = objectMapper.readTree(result.text());

            boolean isCompound = root.has("isCompound") && !root.get("isCompound").isNull()
                    && root.get("isCompound").asBoolean();
            JsonNode stepsNode = root.has("steps") && !root.get("steps").isNull() ? root.get("steps") : null;

            if (isCompound && stepsNode != null && stepsNode.isArray() && !stepsNode.isEmpty()) {
                List<PlanStep> steps = new ArrayList<>();
                int stepNum = 1;
                for (JsonNode stepNode : stepsNode) {
                    if (stepNum > 5) break; // Hard cap at 5 steps

                    steps.add(PlanStep.builder()
                            .stepNumber(stepNum++)
                            .description(stepNode.has("description") && !stepNode.get("description").isNull()
                                    ? stepNode.get("description").asText() : "")
                            .intent(stepNode.has("intent") && !stepNode.get("intent").isNull()
                                    ? stepNode.get("intent").asText() : "")
                            .params(stepNode.has("params") && !stepNode.get("params").isNull()
                                    ? stepNode.get("params") : objectMapper.createObjectNode())
                            .status(StepStatus.PENDING)
                            .build());
                }

                if (!steps.isEmpty()) {
                    SubPlan subPlan = SubPlan.builder()
                            .steps(steps)
                            .currentStepIndex(0)
                            .status(PlanStatus.PLANNING)
                            .completedSteps(new ArrayList<>())
                            .build();

                    conversationContextService.saveSubPlan(conversationKey, subPlan);
                    log.info("Created SubPlan with {} steps for message='{}'", steps.size(), userMessage);
                    for (PlanStep s : steps) {
                        log.info("  step {}: intent='{}', desc='{}', params={}",
                                s.getStepNumber(), s.getIntent(), s.getDescription(), s.getParams());
                    }
                    return Optional.of(subPlan);
                }
            }
            log.info("Planner returned non-compound or empty plan — will fall through to single-intent route");
        } catch (Exception e) {
            log.error("Failed to decompose compound task for '{}': {}", userMessage, e.getMessage(), e);
        }
        return Optional.empty();
    }

    public AiChatTurnResponse executeStep(PlanStep step, Integer userId, String conversationKey, String rawUserMessage, Double userLat, Double userLng) {
        step.setStatus(StepStatus.IN_PROGRESS);
        log.info("executeStep: stepNum={}, intent='{}', params={}, message='{}'",
                step.getStepNumber(), step.getIntent(), step.getParams(), rawUserMessage);
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

            log.info("executeStep result: intent='{}', stadiums={}, bookingId={}, draftBooking={}, stepStatus={}",
                    response.getIntent(),
                    response.getStadiums() != null ? response.getStadiums().size() : "null",
                    response.getBookingId(),
                    response.getDraftBooking() != null ? "present" : "null",
                    step.getStatus());

            // Determine step outcome based on handler response.
            // CRITICAL: Only mark COMPLETED on genuine success (bookingId/draftBooking).
            // False-positive guard: stadium list + need_more_info + error = NOT complete.
            if (step.getStatus() == StepStatus.IN_PROGRESS) {
                if (response.getBookingId() != null || response.getDraftBooking() != null) {
                    // Genuine success — booking created or draft confirmed.
                    step.setStatus(StepStatus.COMPLETED);
                    log.info("executeStep step {} COMPLETED (bookingId={})", step.getStepNumber(), response.getBookingId());
                } else if (response.getStadiums() != null && !response.getStadiums().isEmpty()) {
                    // Stadium list returned — step 1 succeeded. Stay PENDING until user confirms.
                    step.setStatus(StepStatus.COMPLETED);
                    log.info("executeStep step {} COMPLETED with {} stadiums", step.getStepNumber(), response.getStadiums().size());
                } else if ("need_more_info".equals(response.getIntent())
                        || response.getMessage() == null
                        || response.getMessage().isBlank()
                        || response.getMessage().contains("chưa xác định")
                        || response.getMessage().contains("muốn đặt sân nào")
                        || response.getMessage().contains("khu vực nào")) {
                    // Clarification needed or failure — stay PENDING/IN_PROGRESS.
                    step.setStatus(StepStatus.PENDING);
                    log.info("executeStep step {} stayed PENDING (need clarification: {})", step.getStepNumber(), response.getMessage());
                } else {
                    // Unknown response — conservative: don't mark complete.
                    step.setStatus(StepStatus.PENDING);
                    log.info("executeStep step {} stayed PENDING (unclear response)", step.getStepNumber());
                }
            }

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
