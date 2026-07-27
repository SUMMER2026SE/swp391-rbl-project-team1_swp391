package com.sportvenue.service.ai;

import com.sportvenue.dto.request.AiChatTurnRequest;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.service.ai.handler.BookingHandler;
import com.sportvenue.service.ai.handler.BookingStatusHandler;
import com.sportvenue.service.ai.handler.CancelBookingHandler;
import com.sportvenue.service.ai.handler.GetPriceHandler;
import com.sportvenue.service.ai.handler.JoinMatchHandler;
import com.sportvenue.service.ai.handler.MatchRequestHandler;
import com.sportvenue.service.ai.handler.MyBookingsHandler;
import com.sportvenue.service.ai.handler.PolicyHandler;
import com.sportvenue.service.ai.handler.RecommendTimeHandler;
import com.sportvenue.service.ai.handler.SlotAvailabilityHandler;
import com.sportvenue.service.ai.handler.StadiumSearchHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test riêng cho phần rủi ro nhất của kiến trúc mới (đơn-JSON): Groq JSON Mode chỉ đảm bảo
 * output là JSON hợp lệ, KHÔNG đảm bảo đúng schema — field thiếu/sai kiểu hoặc parse lỗi hoàn
 * toàn không được phép làm crash request (docs/ai_chatbot_rebuild_plan.md mục 6.1).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiChatServiceImplTest {

    @Mock
    private GroqClient groqClient;
    @Mock
    private StadiumSearchHandler stadiumSearchHandler;
    @Mock
    private SlotAvailabilityHandler slotAvailabilityHandler;
    @Mock
    private MatchRequestHandler matchRequestHandler;
    @Mock
    private PolicyHandler policyHandler;
    @Mock
    private BookingHandler bookingHandler;
    @Mock
    private JoinMatchHandler joinMatchHandler;
    @Mock
    private MyBookingsHandler myBookingsHandler;
    @Mock
    private BookingStatusHandler bookingStatusHandler;
    @Mock
    private CancelBookingHandler cancelBookingHandler;
    @Mock
    private GetPriceHandler getPriceHandler;
    @Mock
    private RecommendTimeHandler recommendTimeHandler;
    @Mock
    private ParamNormalizer paramNormalizer;
    @Mock
    private IntentValidator intentValidator;
    @Mock
    private com.sportvenue.repository.AiUsageLogRepository aiUsageLogRepository;
    @Mock
    private AiConversationContextService conversationContextService;
    @Mock
    private com.sportvenue.service.ai.PersonalizationPromptBuilder personalizationPromptBuilder;
    @Mock
    private com.sportvenue.service.FeatureFlagService featureFlagService;
    @Mock
    private com.sportvenue.service.ai.AiPlanningService aiPlanningService;

    private AiChatServiceImpl service;

    @BeforeEach
    void setUp() {
        when(paramNormalizer.normalize(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(intentValidator.validate(any())).thenAnswer(invocation -> {
            com.sportvenue.service.ai.ExtractedIntentResult result = invocation.getArgument(0);
            return new IntentValidator.ValidationResult(true, result, "PASS", null);
        });

        service = new AiChatServiceImpl(groqClient, stadiumSearchHandler, slotAvailabilityHandler,
                matchRequestHandler, policyHandler, bookingHandler, joinMatchHandler, myBookingsHandler,
                bookingStatusHandler, cancelBookingHandler, getPriceHandler, recommendTimeHandler,
                aiUsageLogRepository, paramNormalizer, intentValidator, conversationContextService,
                personalizationPromptBuilder, featureFlagService, aiPlanningService);
    }

    private AiChatTurnRequest request(String message) {
        return AiChatTurnRequest.builder().message(message).build();
    }

    @Test
    void malformedJson_fallsBackToUnknown_withoutCallingAnyHandler() {
        when(groqClient.chatJson(any(), any(), any(), any()))
                .thenReturn(new GroqClient.GroqResult("khong-phai-json{{{", 0, 0, 0));

        AiChatTurnResponse response = service.handleChat(request("có sân nào không"), null, "s:test");

        assertThat(response.getIntent()).isEqualTo("unknown");
        assertThat(response.getMessage()).isNotBlank();
        verify(stadiumSearchHandler, never()).handle(any(), any(), any());
        verify(slotAvailabilityHandler, never()).handle(any(), any(), any());
        verify(matchRequestHandler, never()).handle(any(), any());
        verify(policyHandler, never()).handle(any(), any());
    }

    @Test
    void missingIntentField_defaultsToUnknown_insteadOfNull() {
        when(groqClient.chatJson(any(), any(), any(), any()))
                .thenReturn(new GroqClient.GroqResult("{\"message\":\"xin chao\"}", 0, 0, 0));

        AiChatTurnResponse response = service.handleChat(request("xin chao"), null, "s:test");

        assertThat(response.getIntent()).isEqualTo("unknown");
    }

    @Test
    void llmGatewayException_returnsFriendlyFallback_insteadOfPropagating() {
        when(groqClient.chatJson(any(), any(), any(), any()))
                .thenThrow(new LlmGatewayException(LlmGatewayException.Kind.RATE_LIMITED, "rate limited"));

        AiChatTurnResponse response = service.handleChat(request("tìm sân"), null, "s:test");

        assertThat(response.getIntent()).isEqualTo("unknown");
        assertThat(response.getMessage()).contains("quá tải");
    }

    @Test
    void needMoreInfoIntent_returnsLlmMessageDirectly_withoutCallingHandlers() {
        when(groqClient.chatJson(any(), any(), any(), any()))
                .thenReturn(new GroqClient.GroqResult(
                        "{\"intent\":\"need_more_info\",\"message\":\"Bạn muốn tìm môn gì và ở đâu?\",\"params\":{}}",
                        0, 0, 0));

        AiChatTurnResponse response = service.handleChat(request("có sân trống không"), null, "s:test");

        assertThat(response.getIntent()).isEqualTo("need_more_info");
        assertThat(response.getMessage()).isEqualTo("Bạn muốn tìm môn gì và ở đâu?");
        verify(stadiumSearchHandler, never()).handle(any(), any(), any());
    }

    @Test
    void getPolicyIntent_dispatchesToPolicyHandler() {
        when(groqClient.chatJson(any(), any(), any(), any()))
                .thenReturn(new GroqClient.GroqResult(
                        "{\"intent\":\"get_policy\",\"message\":\"...\",\"params\":{\"topic\":\"refund\"}}",
                        0, 0, 0));
        AiChatTurnResponse expected = AiChatTurnResponse.builder().intent("get_policy").policyText("...").build();
        when(policyHandler.handle(any(), any())).thenReturn(expected);

        AiChatTurnResponse response = service.handleChat(request("chính sách hoàn tiền thế nào"), null, "s:test");

        assertThat(response).isSameAs(expected);
        verify(policyHandler).handle(any(), any());
    }
}
