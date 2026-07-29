package com.sportvenue.service.ai.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.dto.response.BookingResponse;
import com.sportvenue.entity.Booking;
import com.sportvenue.repository.BookingRepository;
import com.sportvenue.util.StadiumUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MyBookingsHandler {

    private static final int DEFAULT_PAGE_SIZE = 5;

    private final BookingRepository bookingRepository;

    public AiChatTurnResponse handle(JsonNode args, String message, Integer userId) {
        if (userId == null) {
            return AiChatTurnResponse.builder()
                    .intent("need_more_info")
                    .message("Bạn cần đăng nhập để xem danh sách sân đã đặt. Vui lòng đăng nhập và thử lại nhé.")
                    .build();
        }

        // Bug #5: Lấy danh sách booking THỰC từ database
        Page<Booking> bookings = bookingRepository.findByUserUserIdOrderByReservationDateDesc(
                userId, PageRequest.of(0, DEFAULT_PAGE_SIZE));

        if (bookings.isEmpty()) {
            // Bug #5: Trả lời rõ ràng khi không có dữ liệu
            return AiChatTurnResponse.builder()
                    .intent("my_bookings")
                    .message("Bạn chưa có đơn đặt sân nào. Hãy tìm và đặt sân để trải nghiệm nhé!")
                    .bookings(List.of())
                    .build();
        }

        // Chuyển đổi sang response DTO
        List<BookingResponse> bookingResponses = bookings.getContent().stream()
                .map(this::toBookingResponse)
                .toList();

        return AiChatTurnResponse.builder()
                .intent("my_bookings")
                .message("Đây là danh sách các sân bạn đã đặt gần đây:")
                .bookings(bookingResponses)
                .build();
    }

    /**
     * Chuyển đổi Booking entity sang BookingResponse DTO
     */
    private BookingResponse toBookingResponse(Booking booking) {
        BookingResponse.CustomerInfo customerInfo = null;
        if (booking.getUser() != null) {
            customerInfo = BookingResponse.CustomerInfo.builder()
                    .userId(booking.getUser().getUserId())
                    .fullName(booking.getUser().getFullName())
                    .email(booking.getUser().getEmail())
                    .phoneNumber(booking.getUser().getPhoneNumber())
                    .avatarUrl(booking.getUser().getAvatarUrl())
                    .build();
        }

        BookingResponse.StadiumInfo stadiumInfo = null;
        if (booking.getStadium() != null) {
            var stadium = booking.getStadium();
            stadiumInfo = BookingResponse.StadiumInfo.builder()
                    .stadiumId(stadium.getStadiumId())
                    .stadiumName(stadium.getStadiumName())
                    .complexId(StadiumUtils.resolveComplexId(stadium))
                    .complexName(StadiumUtils.resolveComplexName(stadium))
                    .facilityId(StadiumUtils.resolveFacilityId(stadium))
                    .facilityName(StadiumUtils.resolveFacilityName(stadium))
                    .address(stadium.getAddress())
                    .sportType(stadium.getSportType() != null ? stadium.getSportType().getSportName() : null)
                    .build();
        }

        BookingResponse.SlotInfo slotInfo = null;
        if (booking.getSlot() != null) {
            var slot = booking.getSlot();
            slotInfo = BookingResponse.SlotInfo.builder()
                    .slotId(slot.getSlotId())
                    .startTime(java.time.LocalDateTime.of(booking.getReservationDate(), slot.getStartTime()))
                    .endTime(java.time.LocalDateTime.of(booking.getReservationDate(), slot.getEndTime()))
                    .build();
        }

        return BookingResponse.builder()
                .bookingId(booking.getBookingId())
                .customer(customerInfo)
                .stadium(stadiumInfo)
                .slot(slotInfo)
                .totalPrice(booking.getTotalPrice())
                .bookingStatus(booking.getBookingStatus() != null ? booking.getBookingStatus().name() : null)
                .paymentStatus(booking.getPaymentStatus() != null ? booking.getPaymentStatus().name() : null)
                .note(booking.getNote())
                .bookingDate(booking.getBookingDate())
                .recurringGroupId(booking.getRecurringGroupId())
                .build();
    }
}
