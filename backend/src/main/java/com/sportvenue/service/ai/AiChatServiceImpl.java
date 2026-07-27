package com.sportvenue.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sportvenue.dto.request.AiChatTurnRequest;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.security.UserPrincipal;
import com.sportvenue.service.ai.handler.BookingHandler;
import com.sportvenue.service.ai.handler.JoinMatchHandler;
import com.sportvenue.service.ai.handler.MatchRequestHandler;
import com.sportvenue.service.ai.handler.PolicyHandler;
import com.sportvenue.service.ai.handler.SlotAvailabilityHandler;
import com.sportvenue.service.ai.handler.StadiumSearchHandler;
import com.sportvenue.service.ai.handler.MyBookingsHandler;
import com.sportvenue.service.ai.handler.BookingStatusHandler;
import com.sportvenue.service.ai.handler.CancelBookingHandler;
import com.sportvenue.service.ai.handler.GetPriceHandler;
import com.sportvenue.service.ai.handler.RecommendTimeHandler;
import com.sportvenue.entity.AiUsageLog;
import com.sportvenue.repository.AiUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Dispatcher trung tâm — 1 lần gọi Groq (JSON mode) mỗi lượt chat, parse ra
 * {@link ExtractedIntentResult} rồi dispatch sang đúng handler theo {@code intent}. Thay cho
 * kiến trúc multi-turn tool-calling cũ (nhánh ai-chatting đã bỏ) — xem
 * docs/ai_chatbot_rebuild_plan.md để biết lý do đổi kiến trúc.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements AiChatService {

    private final GroqClient groqClient;
    private final StadiumSearchHandler stadiumSearchHandler;
    private final SlotAvailabilityHandler slotAvailabilityHandler;
    private final MatchRequestHandler matchRequestHandler;
    private final PolicyHandler policyHandler;
    private final BookingHandler bookingHandler;
    private final JoinMatchHandler joinMatchHandler;
    private final MyBookingsHandler myBookingsHandler;
    private final BookingStatusHandler bookingStatusHandler;
    private final CancelBookingHandler cancelBookingHandler;
    private final GetPriceHandler getPriceHandler;
    private final RecommendTimeHandler recommendTimeHandler;
    private final AiUsageLogRepository aiUsageLogRepository;
    private final ParamNormalizer paramNormalizer;
    private final IntentValidator intentValidator;
    private final AiConversationContextService conversationContextService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PersonalizationPromptBuilder personalizationPromptBuilder;
    private final com.sportvenue.service.FeatureFlagService featureFlagService;
    private final AiPlanningService aiPlanningService;

    @Value("${app.ai.model:llama-3.3-70b-versatile}")
    private String model;

    private Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));

    void setClock(Clock clock) {
        this.clock = clock;
    }

    // Nội dung đầy đủ + rationale từng luật nằm ở backend/src/main/resources/prompts/customer/
    // — README.md trong thư mục đó giải thích lý do từng đoạn tồn tại. Sửa câu chữ prompt thì
    // sửa file .md, không sửa ở đây.
    private static final String CUSTOMER_SYSTEM_PROMPT = PromptLoader.load("prompts/customer/system-prompt.md");
    private static final String FEW_SHOT_BASE = PromptLoader.load("prompts/customer/few-shot-base.md");
    private static final String FEW_SHOT_BOOKING = PromptLoader.load("prompts/customer/few-shot-booking.md");
    private static final String FEW_SHOT_CANCEL = PromptLoader.load("prompts/customer/few-shot-cancel.md");
    private static final String FEW_SHOT_MATCH = PromptLoader.load("prompts/customer/few-shot-match.md");
    private static final String FEW_SHOT_FAQ = PromptLoader.load("prompts/customer/few-shot-faq.md");
    private static final String FAQ_PROMPT = PromptLoader.load("prompts/customer/faq.md");
    private static final String GUEST_SYSTEM_PROMPT_SUFFIX = PromptLoader.load("prompts/customer/guest-suffix.md");
    private static final String LOGGED_IN_SYSTEM_PROMPT_SUFFIX = PromptLoader.load("prompts/customer/logged-in-suffix.md");

    private static final String FALLBACK_MESSAGE =
            "Xin lỗi, tôi chưa giải quyết được vấn đề của bạn. Vui lòng liên hệ CSKH qua Hotline: 1900 xxxx hoặc Zalo SportHub để được hỗ trợ trực tiếp.";

    private record IntentParseResult(
        ExtractedIntentResult intentResult,
        boolean ruleOverride,
        String validationStatus,
        String errorReason
    ) { }

    @Override
    public AiChatTurnResponse handleChat(AiChatTurnRequest request, UserPrincipal userPrincipal, String conversationKey) {
        Integer userId = userPrincipal != null ? userPrincipal.getUserId() : null;
        log.debug("handleChat: conversationKey='{}', userId={}, message='{}'", conversationKey, userId, request.getMessage());
        String systemPrompt = buildSystemPrompt(userId, request.getMessage());

        // GUEST FIX: If LLM returns create_booking for a guest, override to need_more_info
        // This catches cases where the LLM doesn't follow the guest-suffix.md rule correctly
        final boolean isGuest = (userId == null);

        AiChatTurnResponse fastPathResponse = tryFastPaths(request, userId, conversationKey);
        if (fastPathResponse != null) {
            return fastPathResponse;
        }

        // FIX Bug 2: Check active plan BEFORE Groq call.
        // Groq re-classifies control phrases ("tiếp tục", "có", "đồng ý") as unknown,
        // bypassing the dispatch() check which only handles compound_task intent.
        // We handle these phrases directly here to advance/cancel the active plan.
        String trimmedMsg = request.getMessage() != null ? request.getMessage().toLowerCase(Locale.ROOT).trim() : "";
        boolean isControlMessage = trimmedMsg.equals("tiếp tục") || trimmedMsg.equals("tiếp")
                || trimmedMsg.equals("có") || trimmedMsg.equals("đồng ý") || trimmedMsg.equals("ok")
                || trimmedMsg.equals("vâng") || trimmedMsg.equals("có đấy") || trimmedMsg.equals("ừ")
                || trimmedMsg.equals("bắt đầu") || trimmedMsg.equals("thực hiện");
        Optional<AiConversationContextService.SubPlan> preCheckPlan =
                conversationContextService.getSubPlan(conversationKey);
        if (isControlMessage && preCheckPlan.isPresent()) {
            AiConversationContextService.SubPlan plan = preCheckPlan.get();
            if (plan.getStatus() == AiConversationContextService.PlanStatus.IN_PROGRESS
                    || plan.getStatus() == AiConversationContextService.PlanStatus.PAUSED
                    || plan.getStatus() == AiConversationContextService.PlanStatus.PLANNING) {
                log.info("BUG2-FIX: Routing control message '{}' directly to handleSubPlanTurn (intent={})",
                        trimmedMsg, "continue");
                ExtractedIntentResult dummyResult = new ExtractedIntentResult("compound_task", 1.0, "", null);
                return handleSubPlanTurn(dummyResult, request.getMessage(), plan, userId,
                        conversationKey, request.getUserLat(), request.getUserLng());
            }
        }

        List<GroqClient.ChatMessage> history = toGroqHistory(request.getHistory());

        GroqClient.GroqResult result;
        long startTime = System.currentTimeMillis();
        try {
            result = groqClient.chatJson(model, systemPrompt, history, request.getMessage());
            log.debug("Groq raw JSON cho message '{}': {}", request.getMessage(), result.text());
        } catch (LlmGatewayException e) {
            log.error("Lỗi gọi Groq gateway (kind={}): {}", e.getKind(), e.getMessage());
            String errorMessage = e.getKind() == LlmGatewayException.Kind.RATE_LIMITED
                    ? "Hệ thống trợ lý AI hiện đang quá tải do có quá nhiều yêu cầu. Vui lòng thử lại sau ít phút!"
                    : "Hệ thống AI đang tạm gián đoạn kết nối. Vui lòng thử lại sau.";
            return AiChatTurnResponse.messageOnly(errorMessage, "unknown");
        }
        long latencyMs = System.currentTimeMillis() - startTime;

        IntentParseResult parseResult = parseAndValidateIntent(result, request.getMessage());

        // GUEST FIX: If LLM returns create_booking for a guest, override to search_stadiums
        // (Guests can search stadiums, but to actually book they need to login.
        // The guest-suffix.md tells the LLM to return need_more_info, but the LLM sometimes
        // returns create_booking instead. This server-side fix ensures guests can still search.)
        if (isGuest && "create_booking".equals(parseResult.intentResult().getIntent())) {
            log.info("GUEST FIX: Overriding create_booking to search_stadiums for guest user");
            parseResult.intentResult().setIntent("search_stadiums");
            parseResult.intentResult().setMessage("Bạn cần đăng nhập để đặt sân. Trước tiên, để mình tìm các sân phù hợp cho bạn nhé.");
        }

        long handlerStartTime = System.currentTimeMillis();
        AiChatTurnResponse response = dispatch(parseResult.intentResult(), request.getMessage(), conversationKey, userId, request.getUserLat(), request.getUserLng());
        long processingTimeHandlerMs = System.currentTimeMillis() - handlerStartTime;

        log.debug("Parsed intent='{}' -> response intent='{}'", parseResult.intentResult().getIntent(), response.getIntent());

        saveUsageLog(userId, parseResult, result, request.getMessage(), response, latencyMs, processingTimeHandlerMs);

        return response;
    }

    /**
     * Thử lần lượt các fast-path bắt cứng bằng regex/state (bỏ qua gọi LLM hoàn toàn) — trả về
     * response nếu match, null nếu không path nào áp dụng (đi tiếp luồng gọi LLM bình thường).
     */
    private AiChatTurnResponse tryFastPaths(AiChatTurnRequest request, Integer userId, String conversationKey) {
        AiChatTurnResponse response = tryCancelConfirmFastPath(request, userId, conversationKey);
        if (response != null) {
            return response;
        }
        response = tryBookingConfirmFastPath(request, userId, conversationKey);
        if (response != null) {
            return response;
        }
        response = tryIndexFastPath(request, conversationKey, userId);
        if (response != null) {
            return response;
        }
        return tryIdFastPath(request, conversationKey, userId);
    }

    /** FAST PATH: Nếu đang ở bước chờ xác nhận (Cancel Confirm), BỎ QUA GỌI LLM hoàn toàn. */
    private AiChatTurnResponse tryCancelConfirmFastPath(AiChatTurnRequest request, Integer userId, String conversationKey) {
        if (!conversationContextService.isAwaitingCancelConfirmation(conversationKey)) {
            return null;
        }
        log.info("FAST PATH: Bỏ qua LLM do đang chờ xác nhận hủy đơn (isAwaitingCancelConfirmation=true)");
        long handlerStartTime = System.currentTimeMillis();
        AiChatTurnResponse response = cancelBookingHandler.handle(null, request.getMessage(), userId, conversationKey);
        long processingTimeHandlerMs = System.currentTimeMillis() - handlerStartTime;

        // Tạo mock object để ghi log
        ExtractedIntentResult dummyIntent = ExtractedIntentResult.unknown();
        dummyIntent.setIntent("cancel_booking_fast_path");
        IntentParseResult dummyParse = new IntentParseResult(dummyIntent, true, "FAST_PATH", null);
        GroqClient.GroqResult dummyGroq = new GroqClient.GroqResult("FAST_PATH_BYPASS", 0, 0, 0);

        saveUsageLog(userId, dummyParse, dummyGroq, request.getMessage(), response, 0, processingTimeHandlerMs);
        return response;
    }

    /** FAST PATH: Nếu đang chờ Booking Confirm (có draft booking) và message là confirm/cancel keyword. */
    private AiChatTurnResponse tryBookingConfirmFastPath(AiChatTurnRequest request, Integer userId, String conversationKey) {
        if (!conversationContextService.isAwaitingBookingConfirmation(conversationKey)) {
            return null;
        }
        String msgLower = request.getMessage().toLowerCase(Locale.ROOT).trim();
        if (!isLikelyConfirmMessage(msgLower) && !msgLower.equals("không") && !msgLower.equals("cancel") && !msgLower.equals("hủy")) {
            return null;
        }
        log.info("FAST PATH: Bỏ qua LLM do đang chờ xác nhận đặt sân (isAwaitingBookingConfirmation=true)");
        long handlerStartTime = System.currentTimeMillis();
        AiChatTurnResponse response = bookingHandler.handleConfirmation(request.getMessage(), userId, conversationKey);
        long processingTimeHandlerMs = System.currentTimeMillis() - handlerStartTime;

        ExtractedIntentResult dummyIntent = ExtractedIntentResult.unknown();
        dummyIntent.setIntent("create_booking_fast_path");
        IntentParseResult dummyParse = new IntentParseResult(dummyIntent, true, "FAST_PATH", null);
        GroqClient.GroqResult dummyGroq = new GroqClient.GroqResult("FAST_PATH_BYPASS", 0, 0, 0);

        saveUsageLog(userId, dummyParse, dummyGroq, request.getMessage(), response, 0, processingTimeHandlerMs);
        return response;
    }

    /** FAST PATH: Regex bắt index sân/đơn/kèo tường minh (sân số N, sân thứ N, sân đầu tiên). */
    private AiChatTurnResponse tryIndexFastPath(AiChatTurnRequest request, String conversationKey, Integer userId) {
        String rawMessage = request.getMessage().toLowerCase(Locale.ROOT).trim();
        java.util.regex.Matcher indexMatcher = java.util.regex.Pattern
                .compile("^(sân|đơn|kèo)\\s+(số\\s+|thứ\\s+)?(\\d+|đầu\\s*tiên)$").matcher(rawMessage);
        if (!indexMatcher.matches()) {
            return null;
        }
        String type = indexMatcher.group(1);
        String numberStr = indexMatcher.group(3);
        int targetIndex = -1;
        if (numberStr.equals("đầu tiên")) {
            targetIndex = 0;
        } else {
            try {
                targetIndex = Integer.parseInt(numberStr) - 1; // 1-based to 0-based
            } catch (NumberFormatException ignored) {
                // targetIndex vẫn -1, bị bỏ qua bên dưới
            }
        }
        if (targetIndex < 0) {
            return null;
        }

        // Xác định context hiện tại có gì
        List<Integer> lastShownStadiums = conversationContextService.getLastShownStadiumIds(conversationKey);
        List<Integer> lastShownBookings = conversationContextService.getLastShownBookingIds(conversationKey);

        String fastPathIntent = null;
        if ("đơn".equals(type) && lastShownBookings != null && !lastShownBookings.isEmpty()) {
            fastPathIntent = "cancel_booking";
        } else if (("sân".equals(type) || "kèo".equals(type)) && lastShownStadiums != null && !lastShownStadiums.isEmpty()) {
            fastPathIntent = "search_stadiums";
        }
        if (fastPathIntent == null) {
            return null;
        }

        log.info("FAST PATH: Bỏ qua LLM do bắt được index tường minh '{}' -> index={}", rawMessage, targetIndex);
        com.fasterxml.jackson.databind.node.ObjectNode dummyParams = objectMapper.createObjectNode();
        dummyParams.put("targetIndex", targetIndex);
        ExtractedIntentResult dummyIntent = ExtractedIntentResult.unknown();
        dummyIntent.setIntent(fastPathIntent);
        dummyIntent.setParams(dummyParams);
        dummyIntent.setConfidence(1.0);

        long handlerStartTime = System.currentTimeMillis();
        AiChatTurnResponse response = dispatch(dummyIntent, request.getMessage(), conversationKey, userId, request.getUserLat(), request.getUserLng());
        long processingTimeHandlerMs = System.currentTimeMillis() - handlerStartTime;

        IntentParseResult dummyParse = new IntentParseResult(dummyIntent, true, "FAST_PATH", null);
        GroqClient.GroqResult dummyGroq = new GroqClient.GroqResult("FAST_PATH_BYPASS", 0, 0, 0);
        saveUsageLog(userId, dummyParse, dummyGroq, request.getMessage(), response, 0, processingTimeHandlerMs);
        return response;
    }

    /** FAST PATH: Regex bắt mã ID tường minh (#12345, mã 12345) khi đã có danh sách booking vừa show. */
    private AiChatTurnResponse tryIdFastPath(AiChatTurnRequest request, String conversationKey, Integer userId) {
        String rawMessage = request.getMessage().toLowerCase(Locale.ROOT).trim();
        java.util.regex.Matcher idMatcher = java.util.regex.Pattern.compile("^(#|mã\\s+)?(\\d{3,6})$").matcher(rawMessage);
        if (!idMatcher.matches()) {
            return null;
        }
        int id = Integer.parseInt(idMatcher.group(2));
        List<Integer> lastShownBookings = conversationContextService.getLastShownBookingIds(conversationKey);
        if (lastShownBookings == null || lastShownBookings.isEmpty()) {
            return null;
        }

        log.info("FAST PATH: Bỏ qua LLM do bắt được ID tường minh '{}' -> id={}", rawMessage, id);
        com.fasterxml.jackson.databind.node.ObjectNode dummyParams = objectMapper.createObjectNode();
        dummyParams.put("bookingId", id);
        ExtractedIntentResult dummyIntent = ExtractedIntentResult.unknown();
        dummyIntent.setIntent("cancel_booking");
        dummyIntent.setParams(dummyParams);
        dummyIntent.setConfidence(1.0);

        long handlerStartTime = System.currentTimeMillis();
        AiChatTurnResponse response = dispatch(dummyIntent, request.getMessage(), conversationKey, userId, request.getUserLat(), request.getUserLng());
        long processingTimeHandlerMs = System.currentTimeMillis() - handlerStartTime;

        IntentParseResult dummyParse = new IntentParseResult(dummyIntent, true, "FAST_PATH", null);
        GroqClient.GroqResult dummyGroq = new GroqClient.GroqResult("FAST_PATH_BYPASS", 0, 0, 0);
        saveUsageLog(userId, dummyParse, dummyGroq, request.getMessage(), response, 0, processingTimeHandlerMs);
        return response;
    }

    private IntentParseResult parseAndValidateIntent(GroqClient.GroqResult result, String userMessage) {
        ExtractedIntentResult intentResult;
        boolean ruleOverride = false;
        String validationStatus = "PASS";
        String errorReason = null;

        try {
            intentResult = objectMapper.readValue(result.text(), ExtractedIntentResult.class);
            log.info("Intent nhận diện: '{}' -> '{}', confidence: {}", userMessage, intentResult.getIntent(), intentResult.getConfidence());

            // Normalize params
            intentResult = paramNormalizer.normalize(intentResult);

            // Confidence check — compound_task should NOT be overridden, let planner decide
            if (intentResult.getConfidence() < 0.5 && !"compound_task".equals(intentResult.getIntent())) {
                log.info("Low confidence ({} < 0.5), overriding to need_more_info", intentResult.getConfidence());
                intentResult.setIntent("need_more_info");
                intentResult.setMessage("Mình chưa rõ ý bạn lắm, bạn có thể nói cụ thể hơn được không?");
                ruleOverride = true;
                validationStatus = "LOW_CONFIDENCE";
            }

            if (!ruleOverride) {
                boolean overrideResult = applyRuleBasedOverrides(intentResult, userMessage);
                if (overrideResult) {
                    ruleOverride = true;
                    validationStatus = "RULE_OVERRIDE";
                }
            }
            
            // Validate
            IntentValidator.ValidationResult validationResult = intentValidator.validate(intentResult);
            validationStatus = validationResult.validationStatus();
            errorReason = validationResult.errorReason();
            if (!validationResult.valid()) {
                intentResult = validationResult.overriddenResult();
                ruleOverride = true;
            }

        } catch (Exception e) {
            log.warn("Không parse được JSON intent từ Groq, dùng fallback unknown", e);
            intentResult = ExtractedIntentResult.unknown();
            validationStatus = "PARSE_ERROR";
            errorReason = e.getMessage();
        }

        return new IntentParseResult(intentResult, ruleOverride, validationStatus, errorReason);
    }

    private void saveUsageLog(Integer userId, IntentParseResult parseResult, GroqClient.GroqResult result,
                              String userMessage, AiChatTurnResponse response, long latencyMs, long processingTimeHandlerMs) {
        try {
            log.info("[TOKEN MEASUREMENT] Input Tokens: {}, Output Tokens: {}", result.inputTokens(), result.outputTokens());
            AiUsageLog usageLog = AiUsageLog.builder()
                    .userId(userId)
                    .feature(response.getIntent())
                    .modelUsed(model)
                    .inputTokens(result.inputTokens())
                    .outputTokens(result.outputTokens())
                    .latencyMs(latencyMs)
                    .userInput(userMessage)
                    .rawLlmResponse(result.text())
                    .parsedIntent(parseResult.intentResult().getIntent())
                    .actionResult(objectMapper.writeValueAsString(response))
                    .promptVersion("1.1") // Version updated for dynamic prompts
                    .confidence(parseResult.intentResult().getConfidence())
                    .ruleOverride(parseResult.ruleOverride())
                    .validationResult(parseResult.validationStatus())
                    .errorReason(parseResult.errorReason())
                    .processingTimeAiMs(latencyMs)
                    .processingTimeHandlerMs(processingTimeHandlerMs)
                    .suggestionType(null) // populated by suggestion pipeline separately
                    .subPlanSteps(response.getSubPlan() != null ? response.getSubPlan().getSteps().size() : null)
                    .subPlanCompleted(response.getSubPlan() != null ? response.getSubPlan().getCurrentStepIndex() : null)
                    .subPlanRolledBack(response.getSubPlan() != null
                            && "ROLLED_BACK".equals(response.getSubPlan().getStatus()))
                    .build();
            aiUsageLogRepository.save(usageLog);
        } catch (Exception e) {
            log.error("Không thể ghi log AI usage", e);
        }
    }

    private AiChatTurnResponse dispatch(ExtractedIntentResult result, String rawUserMessage, String conversationKey, Integer userId,
                                        Double userLat, Double userLng) {
        // NEW: Check for active plan first
        Optional<AiConversationContextService.SubPlan> activePlanOpt = conversationContextService.getSubPlan(conversationKey);
        if (activePlanOpt.isPresent()) {
            AiConversationContextService.SubPlan activePlan = activePlanOpt.get();
            if (activePlan.getStatus() == AiConversationContextService.PlanStatus.IN_PROGRESS 
                || activePlan.getStatus() == AiConversationContextService.PlanStatus.PAUSED
                || activePlan.getStatus() == AiConversationContextService.PlanStatus.PLANNING) {
                return handleSubPlanTurn(result, rawUserMessage, activePlan, userId, conversationKey, userLat, userLng);
            }
        }

        String intent = result.getIntent();
        String message = result.getMessage();

        // NEW: Route compound_task to planner
        if ("compound_task".equals(intent) && featureFlagService.isCompoundTask()) {
            return handleCompoundTask(rawUserMessage, conversationKey, userId);
        }

        if (conversationContextService.isAwaitingCancelConfirmation(conversationKey)) {
            log.info("FORCE ROUTE to cancel_booking handler: isAwaitingCancelConfirmation=true (LLM intent was '{}')", intent);
            return cancelBookingHandler.handle(result.getParams(), rawUserMessage, userId, conversationKey);
        }

        if (isLikelyConfirmMessage(rawUserMessage) && conversationKeyServiceHasValidState(conversationKey)) {
            log.info("DEFENSIVE: Message looks like confirmation but Redis state missing. Attempting to resolve from lastShownBookings.");
            Optional<Integer> lastBookingId = conversationContextService.resolveLastBookingId(conversationKey);
            if (lastBookingId.isPresent()) {
                log.info("DEFENSIVE: Found last booking ID {} in context. Forcing cancel confirmation flow.", lastBookingId.get());
                return cancelBookingHandler.handleConfirmation(rawUserMessage, userId, conversationKey, lastBookingId.get());
            }
        }

        return switch (intent) {
            case "search_stadiums" -> stadiumSearchHandler.handle(result.getParams(), message, conversationKey, userLat, userLng);
            case "get_slots" -> slotAvailabilityHandler.handle(result.getParams(), message, conversationKey);
            case "find_match" -> matchRequestHandler.handle(result.getParams(), message, conversationKey);
            case "get_policy" -> policyHandler.handle(result.getParams(), message);
            case "create_booking" -> bookingHandler.handleWithRawMessage(result.getParams(), message, conversationKey, userId, rawUserMessage);
            case "join_match" -> joinMatchHandler.handle(result.getParams(), message, conversationKey, userId);
            case "my_bookings" -> myBookingsHandler.handle(result.getParams(), message, userId);
            case "booking_status" -> bookingStatusHandler.handle(result.getParams(), message, userId);
            case "cancel_booking" -> cancelBookingHandler.handle(result.getParams(), rawUserMessage, userId, conversationKey);
            case "get_price" -> getPriceHandler.handle(result.getParams(), message);
            case "recommend_time" -> recommendTimeHandler.handle(result.getParams(), message);
            case "need_more_info", "out_of_scope" ->
                    AiChatTurnResponse.messageOnly(message.isBlank() ? FALLBACK_MESSAGE : message, intent);
            default -> AiChatTurnResponse.messageOnly(FALLBACK_MESSAGE, "unknown");
        };
    }

    /**
     * Kiểm tra message có phải là confirm keyword (cứng, không cần LLM).
     * Dùng để defensive check khi LLM nhầm intent.
     */
    private boolean isLikelyConfirmMessage(String message) {
        if (message == null) {
            return false;
        }
        String msg = message.toLowerCase().trim();
        return msg.equals("có") || msg.equals("đồng ý") || msg.equals("ok")
                || msg.equals("ừ") || msg.equals("được") || msg.equals("hủy luôn")
                || msg.equals("confirm") || msg.equals("yes") || msg.equals("y")
                || msg.startsWith("có") || msg.startsWith("đồng ý") || msg.startsWith("hủy luôn")
                || msg.startsWith("xác nhận");
    }

    /**
     * Kiểm tra xem conversationContextService có state hợp lệ cho cancel flow không.
     * Nếu isAwaitingCancelConfirmation=true → có state (không cần fallback)
     * Nếu isAwaitingCancelConfirmation=false nhưng có lastShownBookings → vẫn có thể dùng được
     */
    private boolean conversationKeyServiceHasValidState(String conversationKey) {
        if (conversationKey == null) {
            return false;
        }
        // Nếu đang await confirm → có state
        if (conversationContextService.isAwaitingCancelConfirmation(conversationKey)) {
            return true;
        }
        // Nếu có lastShownBookings → có state
        List<Integer> lastBookings = conversationContextService.getLastShownBookingIds(conversationKey);
        return lastBookings != null && !lastBookings.isEmpty();
    }

    private boolean applyRuleBasedOverrides(ExtractedIntentResult intentResult, String message) {
        String msgLower = message.toLowerCase(Locale.ROOT);
        boolean overridden = false;

        if ("search_stadiums".equals(intentResult.getIntent()) || "get_slots".equals(intentResult.getIntent())) {
            // Chỉ override sang create_booking khi:
            // 1. Có keyword "đặt" VÀ
            // 2. Có thời gian VÀ
            // 3. CÓ tên sân cụ thể (keyword trong params hoặc chứa tên sân trong message)
            // VD: "đặt sân Mỹ Đình 14h" -> create_booking (có tên sân cụ thể)
            // VD: "đặt sân bóng đá ở Đà Nẵng 19h" -> KHÔNG override (không có tên sân cụ thể)
            boolean hasBookingAction = msgLower.contains("đặt") || msgLower.contains("book") || msgLower.contains("giữ chỗ");
            boolean hasTime = msgLower.matches(".*\\d+(h|:).*") || msgLower.contains("giờ") || msgLower.contains("chiều") || msgLower.contains("sáng") || msgLower.contains("tối") || msgLower.contains("mai");

            // Check nếu có tên sân cụ thể trong params (keyword thường là tên sân)
            boolean hasSpecificStadium = false;
            if (intentResult.getParams() != null && intentResult.getParams().isObject()) {
                hasSpecificStadium = intentResult.getParams().hasNonNull("keyword") &&
                        !intentResult.getParams().get("keyword").asText().isBlank();
            }

            if (hasBookingAction && hasTime && hasSpecificStadium) {
                log.warn("Rule-based check: Ghi đè intent từ {} thành create_booking do phát hiện keyword đặt sân + thời gian + tên sân cụ thể.", intentResult.getIntent());
                intentResult.setIntent("create_booking");
                overridden = true;

                com.fasterxml.jackson.databind.node.ObjectNode newParams = objectMapper.createObjectNode();
                if (intentResult.getParams() != null && intentResult.getParams().isObject()) {
                    newParams.setAll((com.fasterxml.jackson.databind.node.ObjectNode) intentResult.getParams());
                    if (intentResult.getParams().hasNonNull("targetDate")) {
                        newParams.put("date", intentResult.getParams().get("targetDate").asText());
                    }
                }
                intentResult.setParams(newParams);
            }
        } else if ("find_match".equals(intentResult.getIntent())) {
            boolean hasJoinAction = msgLower.contains("tham gia") || msgLower.contains("xin slot") || msgLower.contains("cho vô") || msgLower.contains("đăng ký");
            boolean hasTarget = msgLower.matches(".*(kèo|số)\\s*\\d+.*") || msgLower.contains("đầu tiên") || msgLower.contains("kèo đầu") || msgLower.contains("kèo trên");
            
            if (hasJoinAction && hasTarget) {
                log.warn("Rule-based check: Ghi đè intent từ {} thành join_match do phát hiện keyword tham gia + mục tiêu.", intentResult.getIntent());
                intentResult.setIntent("join_match");
                overridden = true;
                
                com.fasterxml.jackson.databind.node.ObjectNode newParams = objectMapper.createObjectNode();
                if (msgLower.contains("đầu") || msgLower.contains("1")) {
                    newParams.put("matchIndex", 0);
                } else if (msgLower.contains("2")) {
                    newParams.put("matchIndex", 1);
                } else if (msgLower.contains("3")) {
                    newParams.put("matchIndex", 2);
                }
                intentResult.setParams(newParams);
            }
        }
        return overridden;
    }

    private List<GroqClient.ChatMessage> toGroqHistory(List<AiChatTurnRequest.ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        
        // GIỚI HẠN LỊCH SỬ CHAT: Chỉ lấy 4 tin nhắn gần nhất (khoảng 2 lượt) để tránh cạn token nhanh
        int maxHistory = 4;
        int startIndex = Math.max(0, history.size() - maxHistory);
        List<AiChatTurnRequest.ChatMessage> trimmedHistory = history.subList(startIndex, history.size());

        List<GroqClient.ChatMessage> converted = new ArrayList<>();
        for (AiChatTurnRequest.ChatMessage turn : trimmedHistory) {
            converted.add(new GroqClient.ChatMessage(turn.getRole(), turn.getContent()));
        }
        return converted;
    }

    private String preClassifyIntent(String message) {
        if (message == null) {
            return "all";
        }
        String msgLower = message.toLowerCase(Locale.ROOT);

        if (msgLower.contains("hủy")) {
            return "cancel";
        }
        if (msgLower.contains("kèo") || msgLower.contains("tham gia") || msgLower.contains("xin slot") || msgLower.contains("đăng ký")) {
            return "match";
        }
        // FAQ check before booking - chính sách/khiếu nại/liên hệ cần ưu tiên
        if (msgLower.contains("giá") || msgLower.contains("bao nhiêu") || msgLower.contains("rẻ") || msgLower.contains("vắng")
                || msgLower.contains("chính sách") || msgLower.contains("khiếu nại") || msgLower.contains("liên hệ") || msgLower.contains("hỗ trợ") || msgLower.contains("complaint")) {
            return "faq";
        }
        if (msgLower.contains("đặt") || msgLower.contains("tìm") || msgLower.contains("sân") || msgLower.contains("giờ")) {
            return "booking";
        }
        return "all"; // Fallback: load everything
    }

    private String buildSystemPrompt(Integer userId, String rawMessage) {
        String roleSuffix = userId != null ? LOGGED_IN_SYSTEM_PROMPT_SUFFIX : GUEST_SYSTEM_PROMPT_SUFFIX;
        
        // Phân loại intent trước để thu gọn Few-Shot
        String intentCategory = preClassifyIntent(rawMessage);
        log.info("[TOKEN MEASUREMENT] Pre-classified intent category: {}", intentCategory);
        
        String dynamicFewShot = FEW_SHOT_BASE;
        switch (intentCategory) {
            case "cancel":
                dynamicFewShot += "\n\n" + FEW_SHOT_CANCEL;
                break;
            case "match":
                dynamicFewShot += "\n\n" + FEW_SHOT_MATCH;
                break;
            case "faq":
                dynamicFewShot += "\n\n" + FEW_SHOT_FAQ;
                break;
            case "booking":
                dynamicFewShot += "\n\n" + FEW_SHOT_BOOKING;
                break;
            default:
                dynamicFewShot += "\n\n" + FEW_SHOT_BOOKING + "\n\n" + FEW_SHOT_CANCEL + "\n\n" + FEW_SHOT_MATCH + "\n\n" + FEW_SHOT_FAQ;
                break;
        }

        String personalizationSection = "";
        if (userId != null && featureFlagService.isPersonalization()) {
            personalizationSection = personalizationPromptBuilder.buildSupplement(userId);
        }

        // Nối rõ ràng bằng "\n\n"
        return String.join("\n\n", CUSTOMER_SYSTEM_PROMPT, dynamicFewShot, FAQ_PROMPT, buildCurrentTimeContext(), personalizationSection, roleSuffix);
    }

    /**
     * Model không biết "hôm nay" là ngày nào — thiếu dòng này thì "tối mai", "thứ 7 tuần này"
     * sẽ bị đoán sai ngày khi điền tham số date/targetDate.
     */
    private String buildCurrentTimeContext() {
        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock);
        String dayOfWeekVi = today.getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.FULL, Locale.of("vi", "VN"));
        return "Bây giờ là " + now.format(DateTimeFormatter.ofPattern("HH:mm"))
                + " " + dayOfWeekVi + ", ngày " + today.format(DateTimeFormatter.ISO_LOCAL_DATE)
                + " (giờ Việt Nam). Hãy dùng mốc này để quy đổi 'hôm nay', 'ngày mai', 'tối nay', 'cuối tuần'... sang ngày YYYY-MM-DD khi điền params.";
    }

    private AiChatTurnResponse handleCompoundTask(String rawUserMessage, String conversationKey, Integer userId) {
        Optional<AiConversationContextService.SubPlan> subPlanOpt = aiPlanningService.decompose(rawUserMessage, userId, conversationKey);
        
        if (subPlanOpt.isEmpty() || subPlanOpt.get().getSteps() == null || subPlanOpt.get().getSteps().isEmpty()) {
            return AiChatTurnResponse.messageOnly("Xin lỗi, tôi không thể phân tích yêu cầu này thành các bước. Vui lòng thử lại với yêu cầu đơn giản hơn.", "error");
        }
        
        AiConversationContextService.SubPlan subPlan = subPlanOpt.get();
        subPlan.setStatus(AiConversationContextService.PlanStatus.IN_PROGRESS);
        conversationContextService.saveSubPlan(conversationKey, subPlan);
        
        AiChatTurnResponse response = AiChatTurnResponse.builder()
                .message("Tôi đã lên kế hoạch xử lý yêu cầu của bạn. Vui lòng kiểm tra các bước bên dưới.")
                .intent("compound_task")
                .subPlan(mapToResponse(subPlan))
                .build();
                
        return response;
    }

    private AiChatTurnResponse handleSubPlanTurn(ExtractedIntentResult result, String rawUserMessage, AiConversationContextService.SubPlan activePlan, Integer userId, String conversationKey, Double userLat, Double userLng) {
        String message = rawUserMessage.toLowerCase(Locale.ROOT).trim();
        
        if (message.equals("hủy") || message.equals("cancel") || message.equals("không")) {
            // Cancel plan
            activePlan.setStatus(AiConversationContextService.PlanStatus.FAILED);
            conversationContextService.saveSubPlan(conversationKey, activePlan);
            
            // Rollback if any bookings were made
            aiPlanningService.rollbackFrom(conversationKey, activePlan.getCurrentStepIndex(), userId);
            
            return AiChatTurnResponse.builder()
                    .message("Đã hủy bỏ kế hoạch.")
                    .intent("compound_task_cancelled")
                    .subPlan(mapToResponse(activePlan))
                    .build();
        }
        
        if (activePlan.getCurrentStepIndex() >= activePlan.getSteps().size()) {
            activePlan.setStatus(AiConversationContextService.PlanStatus.COMPLETED);
            conversationContextService.saveSubPlan(conversationKey, activePlan);
            return AiChatTurnResponse.builder()
                    .message("Kế hoạch đã hoàn thành toàn bộ!")
                    .intent("compound_task_completed")
                    .subPlan(mapToResponse(activePlan))
                    .build();
        }
        
        AiConversationContextService.PlanStep currentStep = activePlan.getSteps().get(activePlan.getCurrentStepIndex());

        // FIX Bug 3 root cause: inject stadium IDs directly into step params (JsonNode),
        // NOT into the text message. BookingHandler reads args.stadiumId/targetIndex, not message text.
        String effectiveMessage = rawUserMessage;
        AiConversationContextService.PlanStep stepToExecute = currentStep;
        if ("create_booking".equals(currentStep.getIntent())
                && (rawUserMessage == null || rawUserMessage.isBlank()
                    || rawUserMessage.toLowerCase(Locale.ROOT).trim().matches("tiếp tục|có|đồng ý|ok|vâng|ừ"))) {
            List<Integer> lastShownStadiumIds = conversationContextService.getLastShownStadiumIds(conversationKey);
            if (lastShownStadiumIds != null && !lastShownStadiumIds.isEmpty()) {
                // Deep-copy params to avoid mutating the shared Redis object
                com.fasterxml.jackson.databind.JsonNode originalParams = currentStep.getParams();
                ObjectNode injectedParams = objectMapper.valueToTree(originalParams);
                // Inject: stadiumId = first stadium in list (auto-book first result)
                injectedParams.put("stadiumId", lastShownStadiumIds.get(0));
                injectedParams.put("targetIndex", 0);
                // Normalize time from "17h" → "17:00" so BookingHandler.resolveSlot can parse it
                if (currentStep.getParams().has("time")) {
                    String rawTime = currentStep.getParams().get("time").asText().trim();
                    String normalizedTime = rawTime.replaceAll("(\\d+)h.*", "$1:00");
                    injectedParams.put("startTime", normalizedTime);
                    log.info("BUG3-FIX: Normalized time '{}' -> '{}'", rawTime, normalizedTime);
                }
                log.info("BUG3-FIX ROOT: Injected stadiumId={}, targetIndex=0, startTime={} into step 2 params. lastShown={}",
                        lastShownStadiumIds.get(0), injectedParams.get("startTime"), lastShownStadiumIds);
                // Build new PlanStep with injected params (same status/description/intent)
                stepToExecute = AiConversationContextService.PlanStep.builder()
                        .stepNumber(currentStep.getStepNumber())
                        .description(currentStep.getDescription())
                        .intent(currentStep.getIntent())
                        .params(injectedParams)
                        .status(currentStep.getStatus())
                        .errorReason(currentStep.getErrorReason())
                        .build();
            }
        }

        // Execute current step
        AiChatTurnResponse stepResponse = aiPlanningService.executeStep(stepToExecute, userId, conversationKey, effectiveMessage, userLat, userLng);

        // Update plan state — only advance if step truly completed.
        // executeStep() now returns PENDING for unclear responses, COMPLETED for success.
        if (currentStep.getStatus() == AiConversationContextService.StepStatus.FAILED) {
            activePlan.setStatus(AiConversationContextService.PlanStatus.PAUSED);
        } else if (currentStep.getStatus() == AiConversationContextService.StepStatus.PENDING
                || currentStep.getStatus() == AiConversationContextService.StepStatus.IN_PROGRESS) {
            // Step needs clarification — do NOT advance.
            activePlan.setStatus(AiConversationContextService.PlanStatus.PAUSED);
        } else {
            // Step genuinely completed.
            currentStep.setStatus(AiConversationContextService.StepStatus.COMPLETED);

            AiConversationContextService.StepResult stepResult = AiConversationContextService.StepResult.builder()
                    .stepNumber(currentStep.getStepNumber())
                    .intent(currentStep.getIntent())
                    .success(true)
                    .data(stepResponse.getBookingId() != null ? stepResponse.getBookingId() : stepResponse.getMatchId())
                    .build();

            activePlan.getCompletedSteps().add(stepResult);
            activePlan.setCurrentStepIndex(activePlan.getCurrentStepIndex() + 1);

            if (activePlan.getCurrentStepIndex() >= activePlan.getSteps().size()) {
                activePlan.setStatus(AiConversationContextService.PlanStatus.COMPLETED);
            }
        }
        
        conversationContextService.saveSubPlan(conversationKey, activePlan);
        
        // Attach subPlan to response
        stepResponse.setSubPlan(mapToResponse(activePlan));
        return stepResponse;
    }

    private com.sportvenue.dto.response.SubPlanResponse mapToResponse(AiConversationContextService.SubPlan subPlan) {
        if (subPlan == null) return null;
        List<com.sportvenue.dto.response.PlanStepResponse> stepResponses = new ArrayList<>();
        if (subPlan.getSteps() != null) {
            for (AiConversationContextService.PlanStep s : subPlan.getSteps()) {
                stepResponses.add(com.sportvenue.dto.response.PlanStepResponse.builder()
                        .stepNumber(s.getStepNumber())
                        .description(s.getDescription())
                        .status(s.getStatus().name())
                        .errorReason(s.getErrorReason())
                        .build());
            }
        }
        return com.sportvenue.dto.response.SubPlanResponse.builder()
                .steps(stepResponses)
                .currentStepIndex(subPlan.getCurrentStepIndex())
                .status(subPlan.getStatus().name())
                .build();
    }
}
