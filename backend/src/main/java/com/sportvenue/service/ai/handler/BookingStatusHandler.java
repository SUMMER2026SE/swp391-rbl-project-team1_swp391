package com.sportvenue.service.ai.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.dto.response.BookingResponse;
import com.sportvenue.entity.Booking;
import com.sportvenue.entity.enums.BookingStatus;
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
public class BookingStatusHandler {

    private static final int DEFAULT_PAGE_SIZE = 5;

    private final BookingRepository bookingRepository;

    public AiChatTurnResponse handle(JsonNode args, String message, Integer userId) {
        if (userId == null) {
            return AiChatTurnResponse.builder()
                    .intent("need_more_info")
                    .message("Bạn cần đăng nhập để kiểm tra trạng thái đơn đặt sân. Vui lòng đăng nhập và thử lại nhé.")
                    .build();
        }

        // Bug #5: Lấy danh sách booking đang chờ (PENDING, CONFIRMED) từ database
        List<BookingStatus> activeStatuses = List.of(BookingStatus.PENDING, BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED);
        Page<Booking> bookings = bookingRepository.findByUserUserIdAndBookingStatusInOrderByReservationDateDesc(
                userId, activeStatuses, PageRequest.of(0, DEFAULT_PAGE_SIZE));

        if (bookings.isEmpty()) {
            // Bug #5: Trả lời rõ ràng khi không có đơn đang xử lý
            return AiChatTurnResponse.builder()
                    .intent("booking_status")
                    .message("Bạn không có đơn đặt sân nào đang chờ xử lý. Các đơn của bạn đều đã hoàn thành hoặc đã bị hủy.")
                    .bookings(List.of())
                    .build();
        }

        // Chuyển đổi sang response DTO
        List<BookingResponse> bookingResponses = bookings.getContent().stream()
                .map(this::toBookingResponse)
                .toList();

        return AiChatTurnResponse.builder()
                .intent("booking_status")
                .message("Đây là trạng thái các đơn đặt sân đang chờ của bạn:")
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
