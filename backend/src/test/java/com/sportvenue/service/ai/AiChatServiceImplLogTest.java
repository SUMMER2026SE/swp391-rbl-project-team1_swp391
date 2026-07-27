package com.sportvenue.service.ai;

import com.sportvenue.dto.request.AiChatTurnRequest;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.entity.AiUsageLog;
import com.sportvenue.repository.AiUsageLogRepository;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiChatServiceImplLogTest {

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
    private com.sportvenue.service.ai.ParamNormalizer paramNormalizer;
    @Mock
    private com.sportvenue.service.ai.IntentValidator intentValidator;
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
    void happyPath_savesUsageLogWithCorrectFields() {
        when(groqClient.chatJson(any(), any(), any(), any()))
                .thenReturn(new GroqClient.GroqResult(
                        "{\"intent\":\"search_stadiums\",\"message\":\"Ok\",\"params\":{}}",
                        120, 80, 200));

        when(stadiumSearchHandler.handle(any(), any(), any(), any(), any()))
                .thenReturn(AiChatTurnResponse.builder().intent("search_stadiums").build());

        service.handleChat(request("Tìm sân bóng đá"), null, "s:test");

        ArgumentCaptor<AiUsageLog> logCaptor = ArgumentCaptor.forClass(AiUsageLog.class);
        verify(aiUsageLogRepository).save(logCaptor.capture());

        AiUsageLog saved = logCaptor.getValue();
        assertThat(saved.getFeature()).isEqualTo("search_stadiums");
        assertThat(saved.getInputTokens()).isEqualTo(120);
        assertThat(saved.getOutputTokens()).isEqualTo(80);
        assertThat(saved.getLatencyMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void jsonParseFail_stillSavesUsageLogAsUnknown() {
        when(groqClient.chatJson(any(), any(), any(), any()))
                .thenReturn(new GroqClient.GroqResult(
                        "khong-phai-json-hop-le",
                        100, 50, 150));

        service.handleChat(request("Tìm sân bóng đá"), null, "s:test");

        ArgumentCaptor<AiUsageLog> logCaptor = ArgumentCaptor.forClass(AiUsageLog.class);
        verify(aiUsageLogRepository).save(logCaptor.capture());

        AiUsageLog saved = logCaptor.getValue();
        assertThat(saved.getFeature()).isEqualTo("unknown");
        assertThat(saved.getInputTokens()).isEqualTo(100);
        assertThat(saved.getOutputTokens()).isEqualTo(50);
    }

    @Test
    void llmGatewayException_doesNotSaveUsageLog() {
        when(groqClient.chatJson(any(), any(), any(), any()))
                .thenThrow(new LlmGatewayException(LlmGatewayException.Kind.RATE_LIMITED, "rate limited"));

        service.handleChat(request("Tìm sân bóng đá"), null, "s:test");

        verify(aiUsageLogRepository, never()).save(any());
    }
}
