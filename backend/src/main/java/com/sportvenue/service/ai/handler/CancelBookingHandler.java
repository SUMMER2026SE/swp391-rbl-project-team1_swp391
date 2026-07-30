package com.sportvenue.service.ai.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.dto.response.BookingResponse;
import com.sportvenue.entity.Booking;
import com.sportvenue.entity.enums.BookingStatus;
import com.sportvenue.repository.BookingRepository;
import com.sportvenue.service.ai.AiConversationContextService;
import com.sportvenue.service.BookingService;
import com.sportvenue.util.StadiumUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class CancelBookingHandler {

    private static final int DEFAULT_PAGE_SIZE = 5;
    // Pattern extract số từ "#000648" hoặc "mã 648" hoặc "booking 648"
    private static final Pattern BOOKING_CODE_PATTERN = Pattern.compile("#?(\\d{3,})");

    private final BookingRepository bookingRepository;
    private final AiConversationContextService conversationContextService;
    private final BookingService bookingService;

    public AiChatTurnResponse handle(JsonNode args, String message, Integer userId, String conversationKey) {
        if (userId == null) {
            return AiChatTurnResponse.builder()
                    .intent("need_more_info")
                    .message("Bạn cần đăng nhập để hủy sân. Vui lòng đăng nhập và thử lại nhé.")
                    .build();
        }

        // Check nếu user đang trong confirmation flow
        if (conversationContextService.isAwaitingCancelConfirmation(conversationKey)) {
            Optional<Integer> pendingId = conversationContextService.getPendingCancelBookingId(conversationKey);
            return handleConfirmation(message, userId, conversationKey, pendingId.orElse(null));
        }

        // Resolve booking từ args
        ResolveResult resolved = resolveBookingId(args, message, userId, conversationKey);

        switch (resolved.status) {
            case LIST -> {
                // Chỉ lưu danh sách để user chọn - KHÔNG set awaiting confirmation ở đây
                // User phải nói rõ đơn nào trước (mã, tên, vị trí), rồi mới confirm
                List<Integer> ids = resolved.bookings.stream().map(Booking::getBookingId).toList();
                conversationContextService.saveLastShownBookings(conversationKey, ids);

                List<BookingResponse> responses = resolved.bookings.stream().map(this::toBookingResponse).toList();
                return AiChatTurnResponse.builder()
                        .intent("cancel_booking")
                        .message("Đây là các đơn đặt sân sắp tới của bạn. Bạn muốn hủy đơn nào? (Vui lòng nói rõ mã đơn, tên sân, hoặc vị trí đơn trong danh sách)")
                        .bookings(responses)
                        .build();
            }
            case NEED_CONFIRMATION -> {
                // Một booking xác định được → hỏi xác nhận
                List<Integer> ids = resolved.bookings.stream().map(Booking::getBookingId).toList();
                conversationContextService.saveLastShownBookings(conversationKey, ids);
                conversationContextService.setAwaitingCancelConfirmation(conversationKey, resolved.bookingId);

                BookingResponse response = toBookingResponse(resolved.bookings.get(0));
                return AiChatTurnResponse.builder()
                        .intent("cancel_booking")
                        .message(buildConfirmationMessage(resolved.bookings.get(0)))
                        .bookings(List.of(response))
                        .bookingId(resolved.bookingId)
                        .build();
            }
            case AMBIGUOUS -> {
                // Nhiều booking khớp → hỏi rõ hơn, không lặp lại list
                conversationContextService.saveLastShownBookings(conversationKey,
                        resolved.bookings.stream().map(Booking::getBookingId).toList());
                return AiChatTurnResponse.builder()
                        .intent("need_more_info")
                        .message(resolved.errorMessage)
                        .bookings(resolved.bookings.stream().map(this::toBookingResponse).toList())
                        .build();
            }
            case NOT_FOUND -> {
                conversationContextService.clearAwaitingCancelConfirmation(conversationKey);
                return AiChatTurnResponse.builder()
                        .intent("cancel_booking")
                        .message(resolved.errorMessage != null ? resolved.errorMessage
                                : "Không tìm thấy đơn đặt sân phù hợp. Vui lòng cung cấp mã đơn hoặc mô tả rõ hơn.")
                        .build();
            }
            case EXECUTE_CANCEL -> {
                // User đã xác nhận → thực hiện hủy
                conversationContextService.clearAwaitingCancelConfirmation(conversationKey);
                return executeCancel(resolved.bookingId, userId, resolved.confirmMessage);
            }
            default -> {
                return AiChatTurnResponse.builder()
                        .intent("cancel_booking")
                        .message("Mình chưa hiểu ý bạn. Bạn muốn hủy đơn đặt sân nào?")
                        .build();
            }
        }
    }

    /**
     * Resolve booking từ args và message.
     * Priority:
     * 1. bookingId tường minh trong args
     * 2. targetIndex (0-based) trong args → lookup trong lastShownBookingIds
     * 3. keyword trong args → fuzzy match trong lastShownBookings
     * 4. message chứa mã đơn (#000648) → parse và lookup
     * 5. message chứa confirm keywords → thực hiện hủy
     * 6. Không resolve được → hiển thị list
     */
    private ResolveResult resolveBookingId(JsonNode args, String message, Integer userId, String conversationKey) {
        // 1. bookingId tường minh
        if (args != null && args.hasNonNull("bookingId")) {
            int bookingId = args.get("bookingId").asInt();
            return findAndValidateBooking(bookingId, userId);
        }

        // 2. targetIndex
        if (args != null && args.hasNonNull("targetIndex")) {
            int targetIndex = args.get("targetIndex").asInt();
            // "đơn cuối cùng" → index = -1
            if (targetIndex < 0) {
                Optional<Integer> lastId = conversationContextService.resolveLastBookingId(conversationKey);
                if (lastId.isPresent()) {
                    return findAndValidateBooking(lastId.get(), userId);
                }
            }
            Optional<Integer> resolvedId = conversationContextService.resolveBookingIdByIndex(conversationKey, targetIndex);
            if (resolvedId.isPresent()) {
                return findAndValidateBooking(resolvedId.get(), userId);
            }
        }

        // 3. keyword
        if (args != null && args.hasNonNull("keyword")) {
            String keyword = args.get("keyword").asText().toLowerCase();
            return findByKeyword(keyword, userId);
        }

        // 4. Parse mã đơn từ message (#000648, mã 648, booking 648)
        Matcher matcher = BOOKING_CODE_PATTERN.matcher(message);
        if (matcher.find()) {
            int bookingId = Integer.parseInt(matcher.group(1));
            return findAndValidateBooking(bookingId, userId);
        }

        // 5. Confirm keywords
        String msgLower = message.toLowerCase().trim();
        if (isConfirmation(msgLower)) {
            // Lấy booking đang chờ confirm từ context
            Optional<Integer> pendingBookingId = conversationContextService.getPendingCancelBookingId(conversationKey);
            if (pendingBookingId.isPresent()) {
                return new ResolveResult(Status.EXECUTE_CANCEL, pendingBookingId.get(), null, List.of(), msgLower);
            }
            // Không có pending → thử lấy đơn cuối cùng đã show
            Optional<Integer> lastId = conversationContextService.resolveLastBookingId(conversationKey);
            if (lastId.isPresent()) {
                return new ResolveResult(Status.EXECUTE_CANCEL, lastId.get(), null, List.of(), msgLower);
            }
        }

        // 6. Không resolve được → hiển thị list
        return showList(userId, conversationKey);
    }

    private ResolveResult findAndValidateBooking(int bookingId, Integer userId) {
        List<BookingStatus> cancellableStatuses = List.of(
                BookingStatus.PENDING, BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED);
        // Tra thẳng theo bookingId (không giới hạn bởi DEFAULT_PAGE_SIZE) — lookup tường minh phải
        // tìm được cả những booking không nằm trong top 5 gần nhất.
        return bookingRepository.findByBookingIdAndUserUserIdAndBookingStatusIn(bookingId, userId, cancellableStatuses)
                .map(b -> new ResolveResult(Status.NEED_CONFIRMATION, bookingId, null, List.of(b), null))
                .orElseGet(() -> new ResolveResult(Status.NOT_FOUND, null,
                        "Không tìm thấy đơn đặt sân #" + bookingId + " có thể hủy. Đơn có thể đã bị hủy hoặc không thuộc về bạn.",
                        List.of(), null));
    }

    private ResolveResult findByKeyword(String keyword, Integer userId) {
        List<BookingStatus> cancellableStatuses = List.of(
                BookingStatus.PENDING, BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED);
        Page<Booking> allBookings = bookingRepository.findByUserUserIdAndBookingStatusInOrderByReservationDateDesc(
                userId, cancellableStatuses, PageRequest.of(0, DEFAULT_PAGE_SIZE));

        List<Booking> matched = new java.util.ArrayList<>();
        for (Booking b : allBookings.getContent()) {
            String stadiumName = b.getStadium() != null && b.getStadium().getStadiumName() != null
                ? b.getStadium().getStadiumName() : "null";
            String complexName = b.getStadium() != null && b.getStadium().getParentStadium() != null
                && b.getStadium().getParentStadium().getStadiumName() != null
                ? b.getStadium().getParentStadium().getStadiumName() : "null";
            String stadiumNameLower = stadiumName.toLowerCase();
            String complexNameLower = complexName.toLowerCase();
            String combinedLower = complexNameLower + " " + stadiumNameLower;

            String[] keywordParts = keyword.split("\\s+");
            boolean match = true;
            for (String part : keywordParts) {
                if (!combinedLower.contains(part)) {
                    match = false;
                    break;
                }
            }

            if (match) {
                matched.add(b);
            }
        }

        if (matched.isEmpty()) {
            return new ResolveResult(Status.NOT_FOUND, null,
                    "Không tìm thấy đơn đặt sân nào khớp với \"" + keyword + "\". Vui lòng cung cấp mã đơn hoặc mô tả rõ hơn.",
                    List.of(), null);
        }
        if (matched.size() == 1) {
            return new ResolveResult(Status.NEED_CONFIRMATION, matched.get(0).getBookingId(), null, matched, null);
        }

        // Nhiều hơn 1 → hỏi rõ hơn
        String options = matched.stream()
                .map(b -> formatBookingShort(b))
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
        return new ResolveResult(Status.AMBIGUOUS, null,
                "Có " + matched.size() + " đơn khớp với \"" + keyword + "\": " + options + ". Bạn muốn hủy đơn nào? Vui lòng nói rõ mã đơn (VD: #" + matched.get(0).getBookingId() + ").",
                matched, null);
    }

    private String formatBookingShort(Booking b) {
        String name = b.getStadium() != null ? (b.getStadium().getParentStadium() != null
                ? b.getStadium().getParentStadium().getStadiumName() + " - " + b.getStadium().getStadiumName()
                : b.getStadium().getStadiumName()) : "Sân không rõ";
        String date = b.getReservationDate() != null ? b.getReservationDate().toString() : "?";
        return "#" + b.getBookingId() + " (" + name + ", " + date + ")";
    }

    private ResolveResult showList(Integer userId, String conversationKey) {
        List<BookingStatus> cancellableStatuses = List.of(
                BookingStatus.PENDING, BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED);
        Page<Booking> bookings = bookingRepository.findByUserUserIdAndBookingStatusInOrderByReservationDateDesc(
                userId, cancellableStatuses, PageRequest.of(0, DEFAULT_PAGE_SIZE));

        if (bookings.isEmpty()) {
            conversationContextService.clearAwaitingCancelConfirmation(conversationKey);
            return new ResolveResult(Status.NOT_FOUND, null,
                    "Bạn không có đơn đặt sân nào có thể hủy. Các đơn đã hoàn thành hoặc đã bị hủy không thể hủy được.",
                    List.of(), null);
        }
        return new ResolveResult(Status.LIST, null, null, bookings.getContent(), null);
    }

    private String buildConfirmationMessage(Booking booking) {
        String name = booking.getStadium() != null ? (booking.getStadium().getParentStadium() != null
                ? booking.getStadium().getParentStadium().getStadiumName() + " - " + booking.getStadium().getStadiumName()
                : booking.getStadium().getStadiumName()) : "Sân không rõ";

        String date = booking.getReservationDate() != null ? booking.getReservationDate().toString() : "?";
        String time = booking.getSlot() != null && booking.getSlot().getStartTime() != null
                ? booking.getSlot().getStartTime().toString() : "?";
        String price = booking.getTotalPrice() != null ? booking.getTotalPrice().toString() : "?";

        return String.format(
                "Bạn muốn hủy đơn #%d tại %s, ngày %s lúc %s (giá %sđ)? Vui lòng xác nhận bằng \"có\" hoặc \"hủy luôn\" để tiến hành hủy.",
                booking.getBookingId(), name, date, time, price);
    }

    private boolean isConfirmation(String msg) {
        return msg.equals("có") || msg.equals("đồng ý") || msg.equals("ok")
                || msg.equals("ừ") || msg.equals("được") || msg.equals("hủy luôn")
                || msg.equals("confirm") || msg.equals("yes") || msg.equals("y")
                || msg.startsWith("có") || msg.startsWith("đồng ý") || msg.startsWith("hủy luôn")
                || msg.startsWith("xác nhận");
    }

    /**
     * Handle confirmation message when Redis state might be missing.
     * @param message confirmation message from user
     * @param userId user ID
     * @param conversationKey conversation key
     * @param preResolvedBookingId Optional booking ID resolved from lastShownBookings (pass if Redis state missing)
     */
    public AiChatTurnResponse handleConfirmation(String message, Integer userId, String conversationKey, Integer preResolvedBookingId) {
        String msgLower = message != null ? message.toLowerCase().trim() : "";

        Optional<Integer> pendingId = conversationContextService.getPendingCancelBookingId(conversationKey);

        // DEFENSIVE: If Redis state missing but we have a preResolvedBookingId, use it
        Integer effectiveBookingId = pendingId.orElse(preResolvedBookingId);
        if (effectiveBookingId == null) {
            conversationContextService.clearAwaitingCancelConfirmation(conversationKey);
            return showListAndReturn(userId, conversationKey);
        }

        if (isConfirmation(msgLower)) {
            conversationContextService.clearAwaitingCancelConfirmation(conversationKey);
            return executeCancel(effectiveBookingId, userId, message);
        }

        if (isCancellation(msgLower)) {
            conversationContextService.clearAwaitingCancelConfirmation(conversationKey);
            return AiChatTurnResponse.builder()
                    .intent("cancel_booking")
                    .message("Đã hủy thao tác. Bạn có thể hỏi gì khác hoặc chọn đơn khác để hủy nhé.")
                    .build();
        }

        // Không phải confirm/cancel → hỏi lại
        return AiChatTurnResponse.builder()
                .intent("need_more_info")
                .message("Mình chưa hiểu. Bạn có muốn hủy đơn này không? Vui lòng trả lời \"có\" để xác nhận hoặc \"không\" để hủy thao tác.")
                .bookingId(effectiveBookingId)
                .build();
    }

    private boolean isCancellation(String msg) {
        return msg.equals("không") || msg.equals("thôi") || msg.equals("bỏ")
                || msg.equals("no") || msg.equals("n") || msg.equals("hủy thao tác") || msg.equals("cancel");
    }

    private AiChatTurnResponse executeCancel(Integer bookingId, Integer userId, String reason) {
        try {
            var principal = new com.sportvenue.security.UserPrincipal(
                    com.sportvenue.entity.User.builder().userId(userId).build());
            var result = bookingService.cancelBooking(principal, bookingId, "Hủy qua chatbot AI");

            String message = result.getStatus() != null
                    && result.getStatus().equals(BookingStatus.CANCELLED.name())
                    ? "Đã hủy đơn đặt sân #" + bookingId + " thành công. Bạn có cần hỗ trợ gì thêm không?"
                    : "Đơn đặt sân #" + bookingId + " đã được xử lý. Trạng thái: " + result.getStatus();

            return AiChatTurnResponse.builder()
                    .intent("cancel_booking")
                    .message(message)
                    .bookingId(bookingId)
                    .build();
        } catch (Exception e) {
            log.error("Lỗi khi hủy booking #{}: {}", bookingId, e.getMessage(), e);
            return AiChatTurnResponse.builder()
                    .intent("cancel_booking")
                    .message("Xin lỗi, không thể hủy đơn #" + bookingId + " lúc này. Vui lòng thử lại sau hoặc liên hệ CSKH.")
                    .bookingId(bookingId)
                    .build();
        }
    }

    private AiChatTurnResponse showListAndReturn(Integer userId, String conversationKey) {
        List<BookingStatus> cancellableStatuses = List.of(
                BookingStatus.PENDING, BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED);
        Page<Booking> bookings = bookingRepository.findByUserUserIdAndBookingStatusInOrderByReservationDateDesc(
                userId, cancellableStatuses, PageRequest.of(0, DEFAULT_PAGE_SIZE));

        if (bookings.isEmpty()) {
            return AiChatTurnResponse.builder()
                    .intent("cancel_booking")
                    .message("Bạn không có đơn đặt sân nào có thể hủy.")
                    .bookings(List.of())
                    .build();
        }

        List<Integer> ids = bookings.getContent().stream().map(Booking::getBookingId).toList();
        conversationContextService.saveLastShownBookings(conversationKey, ids);

        return AiChatTurnResponse.builder()
                .intent("cancel_booking")
                .message("Đây là các đơn đặt sân sắp tới của bạn. Bạn muốn hủy đơn nào?")
                .bookings(bookings.getContent().stream().map(this::toBookingResponse).toList())
                .build();
    }

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

    // Inner classes
    private enum Status { LIST, NEED_CONFIRMATION, AMBIGUOUS, NOT_FOUND, EXECUTE_CANCEL }

    private record ResolveResult(
            Status status,
            Integer bookingId,
            String errorMessage,
            List<Booking> bookings,
            String confirmMessage
    ) { }
}
