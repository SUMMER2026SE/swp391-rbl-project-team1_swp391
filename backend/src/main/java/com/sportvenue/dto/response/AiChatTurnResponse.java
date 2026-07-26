package com.sportvenue.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response cho 1 lượt chat. Chỉ đúng 1 trong 4 field kết quả (stadiums/slots/matches/policyText)
 * được populate tuỳ theo {@link #intent} — Frontend render card tương ứng, không parse tag từ
 * {@link #message} (xem docs/ai_chatbot_rebuild_plan.md mục 2A).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatTurnResponse {

    /** Lời thoại tự nhiên hiển thị trong khung chat. */
    private String message;

    /** Intent đã nhận diện — FE dùng để quyết định render card loại nào. */
    private String intent;

    private List<StadiumResponse> stadiums;

    private List<TimeSlotResponse> slots;

    private List<MatchResponse> matches;

    private List<BookingResponse> bookings; // Bug #5: Thêm field để trả dữ liệu booking thật

    private String policyText;

    /** ID của booking vừa tạo (intent: create_booking) - sẽ bị deprecate khi bỏ auto-book */
    private Integer bookingId;

    /** Thông tin booking nháp (intent: confirm_booking) */
    private DraftBookingResponse draftBooking;

    /** ID của kèo ghép vừa tham gia (intent: join_match) */
    private Integer matchId;

    /** Thông tin kèo ghép nháp (intent: confirm_join_match) */
    private DraftJoinMatchResponse draftJoinMatch;

    /** Multi-step planning state */
    private SubPlanResponse subPlan;

    public static AiChatTurnResponse messageOnly(String message, String intent) {
        return AiChatTurnResponse.builder().message(message).intent(intent).build();
    }
}
