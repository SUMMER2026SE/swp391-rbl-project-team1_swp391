package com.sportvenue.service.ai.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportvenue.dto.request.CreateMatchRequest;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.dto.response.MatchEligibleBookingResponse;
import com.sportvenue.dto.response.MatchResponse;
import com.sportvenue.entity.enums.MatchingType;
import com.sportvenue.entity.enums.SkillLevel;
import com.sportvenue.service.MatchRequestService;
import com.sportvenue.service.ai.AiConversationContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateMatchHandlerTest {

    @Mock
    private MatchRequestService matchRequestService;
    @Mock
    private AiConversationContextService conversationContextService;

    private CreateMatchHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new CreateMatchHandler(matchRequestService, conversationContextService, objectMapper);
        lenient().when(conversationContextService.getPendingAction(any())).thenReturn(Optional.empty());
    }

    @Test
    void uniqueBookingWithoutMatchDetails_asksBeforeCreatingAndSavesDraft() throws Exception {
        when(matchRequestService.getEligibleBookingsForMatchCreation(7))
                .thenReturn(List.of(eligibleBooking(648, "Sân bóng đá số 5", "Bóng đá")));
        JsonNode args = objectMapper.readTree("""
                {"sportName":"Bóng đá","date":"2026-08-02","startTime":"14:00"}
                """);

        AiChatTurnResponse response = handler.handle(args, "", 7, "", "u:7");

        assertThat(response.getIntent()).isEqualTo("create_match");
        assertThat(response.getMessage())
                .contains("Đã chọn booking #648", "Tiêu đề sẽ được tạo tự động", "form bên dưới");
        assertThat(response.getDraftCreateMatch()).isNotNull();
        assertThat(response.getDraftCreateMatch().getDefaultTitle())
                .isEqualTo("Kèo Bóng đá tại Sân bóng đá số 5 — SportHub Complex");
        assertThat(response.getDraftCreateMatch().getMissingFields())
                .containsExactly("matchingType", "skillLevel", "splitPrice");
        verify(matchRequestService, never()).createMatch(any(), any());

        ArgumentCaptor<AiConversationContextService.PendingAction> captor =
                ArgumentCaptor.forClass(AiConversationContextService.PendingAction.class);
        verify(conversationContextService).savePendingAction(eq("u:7"), captor.capture());
        assertThat(captor.getValue().getIntent()).isEqualTo("create_match");
        assertThat(captor.getValue().getData()).containsEntry("bookingId", 648);
    }

    @Test
    void completeDetailsOnFirstMessage_stillRequiresFormConfirmation() throws Exception {
        MatchEligibleBookingResponse eligible = eligibleBooking(648, "Sân bóng đá số 5", "Bóng đá");
        when(matchRequestService.getEligibleBookingsForMatchCreation(7)).thenReturn(List.of(eligible));
        JsonNode args = objectMapper.readTree("""
                {"bookingId":648,"title":"Funny","matchingType":"INDIVIDUAL",
                 "maxPlayers":10,"skillLevel":"INTERMEDIATE","splitPrice":false}
                """);

        AiChatTurnResponse response = handler.handle(args, "", 7, "", "u:7");

        assertThat(response.getMessage()).contains("form bên dưới");
        assertThat(response.getDraftCreateMatch()).isNotNull();
        assertThat(response.getDraftCreateMatch().getDefaultTitle())
                .isEqualTo("Kèo Bóng đá tại Sân bóng đá số 5 — SportHub Complex");
        assertThat(response.getDraftCreateMatch().getMatchingType()).isEqualTo("INDIVIDUAL");
        verify(matchRequestService, never()).createMatch(any(), any());
    }

    @Test
    void formMessage_mergesWithDraftAndCreatesMatchWithoutDependingOnLlmParams() {
        Map<String, Object> saved = new HashMap<>();
        saved.put("bookingId", 648);
        AiConversationContextService.PendingAction pending =
                new AiConversationContextService.PendingAction("create_match", saved,
                        "matchingType,maxPlayers,skillLevel,splitPrice,pricePerPlayer");
        when(conversationContextService.getPendingAction("u:7")).thenReturn(Optional.of(pending));
        when(matchRequestService.getEligibleBookingsForMatchCreation(7))
                .thenReturn(List.of(eligibleBooking(648, "Sân 1", "Badminton")));
        when(matchRequestService.createMatch(any(CreateMatchRequest.class), eq(7)))
                .thenReturn(MatchResponse.builder().matchId(93).build());

        AiChatTurnResponse response = handler.handle(objectMapper.createObjectNode(), "", 7,
                "Xác nhận tạo kèo: Ghép lẻ, tối đa 8 người, trình độ trung bình, "
                        + "chia tiền 50000 đồng mỗi người", "u:7");

        assertThat(response.getMessage()).contains("Đã tạo kèo thành công");
        ArgumentCaptor<CreateMatchRequest> captor = ArgumentCaptor.forClass(CreateMatchRequest.class);
        verify(matchRequestService).createMatch(captor.capture(), eq(7));
        assertThat(captor.getValue().getMatchingType()).isEqualTo(MatchingType.INDIVIDUAL);
        assertThat(captor.getValue().getMaxPlayers()).isEqualTo(8);
        assertThat(captor.getValue().getSkillLevel()).isEqualTo(SkillLevel.INTERMEDIATE);
        assertThat(captor.getValue().getSplitPrice()).isTrue();
        assertThat(captor.getValue().getPricePerPlayer()).isEqualByComparingTo("50000");
        assertThat(captor.getValue().getTitle()).isEqualTo("Kèo Badminton tại Sân 1 — SportHub Complex");
    }

    @Test
    void followUpDetails_mergeWithSavedBookingDraftThenCreate() throws Exception {
        Map<String, Object> saved = new HashMap<>();
        saved.put("bookingId", 648);
        AiConversationContextService.PendingAction pending =
                new AiConversationContextService.PendingAction("create_match", saved, "matchingType");
        when(conversationContextService.getPendingAction("u:7")).thenReturn(Optional.of(pending));
        when(matchRequestService.getEligibleBookingsForMatchCreation(7))
                .thenReturn(List.of(eligibleBooking(648, "Sân 1", "Badminton")));
        when(matchRequestService.createMatch(any(CreateMatchRequest.class), eq(7)))
                .thenReturn(MatchResponse.builder().matchId(92).build());
        JsonNode followUp = objectMapper.readTree("""
                {"matchingType":"INDIVIDUAL","maxPlayers":4,
                 "skillLevel":"BEGINNER","splitPrice":false}
                """);

        AiChatTurnResponse response = handler.handle(followUp, "", 7, "Xác nhận tạo kèo:", "u:7");

        assertThat(response.getMessage()).contains("Đã tạo kèo thành công");
        ArgumentCaptor<CreateMatchRequest> captor = ArgumentCaptor.forClass(CreateMatchRequest.class);
        verify(matchRequestService).createMatch(captor.capture(), eq(7));
        assertThat(captor.getValue().getBookingId()).isEqualTo(648);
        assertThat(captor.getValue().getTitle()).isEqualTo("Kèo Badminton tại Sân 1 — SportHub Complex");
    }

    @Test
    void automaticTitle_includesComplexToDistinguishSameNamedCourtsWithoutDate() throws Exception {
        MatchEligibleBookingResponse booking = eligibleBooking(700, "Sân 1", "Basketball");
        booking.setComplexName("Nhà thi đấu Quân khu 7");
        when(matchRequestService.getEligibleBookingsForMatchCreation(7)).thenReturn(List.of(booking));

        AiChatTurnResponse response = handler.handle(
                objectMapper.readTree("{\"bookingId\":700}"), "", 7, "", "u:7");

        assertThat(response.getDraftCreateMatch().getDefaultTitle())
                .isEqualTo("Kèo Basketball tại Sân 1 — Nhà thi đấu Quân khu 7")
                .doesNotContain("02/08/2026", "14:00");
        verify(matchRequestService, never()).createMatch(any(), any());
    }

    @Test
    void automaticTitle_doesNotRepeatComplexAlreadyContainedInStadiumName() throws Exception {
        MatchEligibleBookingResponse booking = eligibleBooking(
                701, "Sân 1 — Nhà thi đấu Quân khu 7", "Basketball");
        booking.setComplexName("Nhà thi đấu Quân khu 7");
        when(matchRequestService.getEligibleBookingsForMatchCreation(7)).thenReturn(List.of(booking));

        AiChatTurnResponse response = handler.handle(
                objectMapper.readTree("{\"bookingId\":701}"), "", 7, "", "u:7");

        assertThat(response.getDraftCreateMatch().getDefaultTitle())
                .isEqualTo("Kèo Basketball tại Sân 1 — Nhà thi đấu Quân khu 7");
    }

    @Test
    void badmintonAlias_matchesVietnameseCauLongAndAsksForDetails() throws Exception {
        when(matchRequestService.getEligibleBookingsForMatchCreation(7))
                .thenReturn(List.of(eligibleBooking(686, "Sân 1", "Badminton")));
        JsonNode args = objectMapper.readTree("""
                {"sportName":"Cầu lông","date":"2026-08-02","startTime":"14:00"}
                """);

        AiChatTurnResponse response = handler.handle(args, "", 7, "", "u:7");

        assertThat(response.getMessage()).contains("Đã chọn booking #686", "form bên dưới");
        assertThat(response.getDraftCreateMatch()).isNotNull();
        assertThat(response.getDraftCreateMatch().getSportName()).isEqualTo("Badminton");
        assertThat(response.getMessage()).doesNotContain("Không tìm thấy booking");
        verify(matchRequestService, never()).createMatch(any(), any());
    }

    @Test
    void multipleMatchingBookings_asksForBookingIdWithoutCreating() throws Exception {
        when(matchRequestService.getEligibleBookingsForMatchCreation(7)).thenReturn(List.of(
                eligibleBooking(648, "Sân bóng đá A", "Bóng đá"),
                eligibleBooking(649, "Sân bóng đá B", "Bóng đá")));
        JsonNode args = objectMapper.readTree("""
                {"sportName":"Bóng đá","date":"2026-08-02","startTime":"14:00"}
                """);

        AiChatTurnResponse response = handler.handle(args, "", 7, "", "u:7");

        assertThat(response.getMessage()).contains("#648", "#649", "chọn mã booking");
        verify(matchRequestService, never()).createMatch(any(), any());
        verify(conversationContextService).savePendingAction(eq("u:7"), any());
    }

    @Test
    void missingLlmParams_usesRawVietnameseSelectionButStillAsksForDetails() {
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(1);
        MatchEligibleBookingResponse wrongTime = eligibleBooking(648, "Sân cầu lông A", "Badminton");
        wrongTime.setPlayDate(tomorrow);
        wrongTime.setStartTime(LocalTime.of(20, 0));
        MatchEligibleBookingResponse expectedBooking = eligibleBooking(649, "Sân cầu lông B", "Badminton");
        expectedBooking.setPlayDate(tomorrow);
        when(matchRequestService.getEligibleBookingsForMatchCreation(7))
                .thenReturn(List.of(wrongTime, expectedBooking));

        AiChatTurnResponse response = handler.handle(
                objectMapper.createObjectNode(), "", 7,
                "bây giờ tạo cho tôi kèo cầu lông lúc 14h00 ngày mai đi", "u:7");

        assertThat(response.getMessage()).contains("Đã chọn booking #649", "form bên dưới");
        assertThat(response.getDraftCreateMatch()).isNotNull();
        verify(matchRequestService, never()).createMatch(any(), any());
    }

    private MatchEligibleBookingResponse eligibleBooking(int bookingId, String stadiumName, String sportName) {
        return MatchEligibleBookingResponse.builder()
                .bookingId(bookingId)
                .stadiumName(stadiumName)
                .complexName("SportHub Complex")
                .sportName(sportName)
                .playDate(LocalDate.of(2026, 8, 2))
                .startTime(LocalTime.of(14, 0))
                .endTime(LocalTime.of(15, 0))
                .build();
    }
}
