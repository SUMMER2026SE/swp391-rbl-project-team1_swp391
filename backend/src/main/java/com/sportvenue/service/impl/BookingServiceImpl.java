package com.sportvenue.service.impl;

import com.sportvenue.dto.booking.BookingDetailResponse;
import com.sportvenue.dto.booking.BookingHistoryItemDto;
import com.sportvenue.dto.request.AccessoryItem;
import com.sportvenue.dto.request.CreateBookingRequest;
import com.sportvenue.dto.response.PageResponse;
import com.sportvenue.dto.response.TimeSlotResponse;
import com.sportvenue.dto.response.WeeklySlotDayDto;
import com.sportvenue.dto.response.WeeklySlotItemDto;
import com.sportvenue.dto.response.WeeklySlotResponse;
import com.sportvenue.entity.Accessory;
import com.sportvenue.entity.Booking;
import com.sportvenue.entity.BookingAccessory;
import com.sportvenue.entity.Owner;
import com.sportvenue.entity.SportType;
import com.sportvenue.entity.Stadium;
import com.sportvenue.entity.StadiumImage;
import com.sportvenue.entity.TimeSlot;
import com.sportvenue.entity.User;
import com.sportvenue.entity.enums.BookingStatus;
import com.sportvenue.entity.enums.PaymentStatus;
import com.sportvenue.entity.enums.RefundReasonType;
import com.sportvenue.entity.enums.SlotStatus;
import com.sportvenue.exception.BadRequestException;
import com.sportvenue.exception.DuplicateResourceException;
import com.sportvenue.exception.ForbiddenException;
import com.sportvenue.exception.ResourceNotFoundException;
import com.sportvenue.entity.Payment;
import com.sportvenue.entity.enums.PaymentMethod;
import com.sportvenue.entity.enums.TransactionStatus;
import com.sportvenue.repository.AccessoryRepository;
import com.sportvenue.repository.BookingAccessoryRepository;
import com.sportvenue.repository.BookingRepository;
import com.sportvenue.repository.PaymentRepository;
import com.sportvenue.repository.StadiumRepository;
import com.sportvenue.repository.TimeSlotRepository;
import com.sportvenue.repository.UserRepository;
import com.sportvenue.repository.TimeSlotExceptionRepository;
import com.sportvenue.entity.TimeSlotException;
import com.sportvenue.service.WalletService;
import com.sportvenue.entity.enums.WalletTransactionType;
import com.sportvenue.security.UserPrincipal;
import com.sportvenue.service.AdminDashboardService;
import com.sportvenue.service.BookingService;
import com.sportvenue.service.MaintenanceScheduleService;
import com.sportvenue.service.CustomerNotificationService;
import com.sportvenue.service.EmailService;
import com.sportvenue.service.NotificationService;
import com.sportvenue.util.AfterCommitExecutor;
import com.sportvenue.util.StadiumUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * UC-CUS-01: Triển khai single booking cho Customer.
 *
 * Quy tắc availability (xem thêm {@link #isSlotAvailable}):
 * <ul>
 *   <li>Slot unavailable nếu đã có booking PENDING/CONFIRMED cho cùng
 *       (stadiumId, slotId, reservationDate).</li>
 *   <li>Slot unavailable nếu (reservationDate + slot.startTime) đã qua so với hiện tại.</li>
 *   <li>Slot unavailable nếu {@code slotStatus != AVAILABLE} (MAINTENANCE / BOOKED).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingServiceImpl implements BookingService {

    /** Trạng thái booking chiếm chỗ slot — dùng cho conflict detection. */
    private static final List<BookingStatus> ACTIVE_STATUSES =
            List.of(BookingStatus.PENDING_PAYMENT, BookingStatus.PENDING, BookingStatus.CONFIRMED);

    /**
     * Tính toán phí dịch vụ động dựa trên giá sân: 5% giá sân cơ sở, sàn 10k, trần 30k.
     */
    public static BigDecimal calculateServiceFee(BigDecimal basePrice) {
        BigDecimal rawFee = basePrice.multiply(new BigDecimal("0.05"));
        BigDecimal fee = rawFee.setScale(0, java.math.RoundingMode.HALF_UP);
        if (fee.compareTo(new BigDecimal("10000")) < 0) {
            return new BigDecimal("10000");
        }
        if (fee.compareTo(new BigDecimal("30000")) > 0) {
            return new BigDecimal("30000");
        }
        return fee;
    }

    /** UC-CUS-01: Booking mới tạo được giữ 5 phút chờ thanh toán. */
    private static final long PAYMENT_HOLD_MINUTES = 5L;

    private final UserRepository userRepository;
    private final StadiumRepository stadiumRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final AccessoryRepository accessoryRepository;
    private final BookingAccessoryRepository bookingAccessoryRepository;
    private final TimeSlotExceptionRepository timeSlotExceptionRepository;
    private final MaintenanceScheduleService maintenanceScheduleService;
    private final TransactionTemplate transactionTemplate;
    private final EmailService emailService;
    private final NotificationService notificationService;
    private final CustomerNotificationService customerNotificationService;
    private final AfterCommitExecutor afterCommitExecutor;
    private final AdminDashboardService adminDashboardService;
    private final WalletService walletService;

    @Override
    @Transactional
    public BookingDetailResponse createBooking(UserPrincipal principal, CreateBookingRequest request) {
        User customer = userRepository.findById(principal.getUser().getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));

        Stadium stadium = stadiumRepository.findById(request.getStadiumId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy sân với ID " + request.getStadiumId()));

        // UC-CUS-01: Pessimistic Write lock trên slot row — 2 request đồng thời cho
        // cùng (stadium, slot, date) sẽ serialize qua bước conflict-check + insert,
        // kết hợp với partial unique index V5.5 để chặn double-booking ở tầng DB.
        TimeSlot slot = timeSlotRepository.findByIdForUpdate(request.getSlotId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy khung giờ với ID " + request.getSlotId()));

        Optional<TimeSlotException> exceptionOpt = timeSlotExceptionRepository.findBySlotSlotIdAndExceptionDate(
                slot.getSlotId(), request.getReservationDate());
        java.time.LocalTime effectiveStart = exceptionOpt
                .filter(e -> e.getStartTimeOverride() != null)
                .map(TimeSlotException::getStartTimeOverride)
                .orElse(slot.getStartTime());
        java.time.LocalTime effectiveEnd = exceptionOpt
                .filter(e -> e.getEndTimeOverride() != null)
                .map(TimeSlotException::getEndTimeOverride)
                .orElse(slot.getEndTime());

        validateSlotForBooking(slot, stadium, request.getReservationDate(), effectiveStart, effectiveEnd);

        // Conflict check: 1 slot chỉ có 1 booking active tại một ngày
        if (bookingRepository.existsActiveBooking(
                stadium.getStadiumId(),
                slot.getSlotId(),
                request.getReservationDate(),
                ACTIVE_STATUSES)) {
            throw new DuplicateResourceException(
                    "Khung giờ này đã được đặt cho ngày " + request.getReservationDate()
                            + ". Vui lòng chọn khung giờ hoặc ngày khác.");
        }
        if (exceptionOpt.isPresent()) {
            TimeSlotException ex = exceptionOpt.get();
            if (Boolean.TRUE.equals(ex.getClosed())) {
                throw new BadRequestException("Khung giờ này đã tạm đóng vào ngày " + request.getReservationDate());
            }
            if (Boolean.TRUE.equals(ex.getHidden())) {
                throw new BadRequestException("Khung giờ này không tồn tại vào ngày " + request.getReservationDate());
            }
        }

        BigDecimal basePrice = (exceptionOpt.isPresent() && exceptionOpt.get().getPriceOverride() != null)
                ? exceptionOpt.get().getPriceOverride()
                : slot.getPricePerSlot();

        AccessoryComputation accessoryComp = computeAccessories(request.getAccessories());
        BigDecimal serviceFee = calculateServiceFee(basePrice);
        BigDecimal totalPrice = basePrice
                .add(accessoryComp.total)
                .add(serviceFee);

        Booking saved = persistBooking(customer, stadium, slot, request, totalPrice, serviceFee,
                accessoryComp.entities);

        log.info("✅ UC-CUS-01: Customer {} đặt sân {} slot {} ngày {} — bookingId={}, totalPrice={}, serviceFee={}",
                customer.getEmail(), stadium.getStadiumId(), slot.getSlotId(),
                request.getReservationDate(), saved.getBookingId(), totalPrice, serviceFee);

        // Booking mới ở trạng thái PENDING_PAYMENT (giữ chỗ 5 phút chờ thanh toán theo
        // PAYMENT_HOLD_MINUTES) — KHÔNG gửi "đã xác nhận" ở bước này. Nếu khách không thanh
        // toán, đơn tự expire/huỷ — họ sẽ nhận thông báo "đã xác nhận" sai sự thật trước đó.
        // Notification "đã xác nhận" được gửi đúng chỗ trong PaymentServiceImpl sau callback
        // VNPay/CASH thành công (qua notifyPaymentReceived) — đảm bảo chỉ thông báo khi
        // booking thật sự đã được xác nhận thanh toán.

        return toBookingDetailResponse(saved, stadium, slot);
    }

    /**
     * Validate slot thuộc đúng sân, không MAINTENANCE, và chưa qua giờ.
     * Tách riêng để giữ createBooking dưới 80 dòng (checkstyle MethodLength).
     */
    private void validateSlotForBooking(TimeSlot slot, Stadium stadium, java.time.LocalDate reservationDate,
                                        java.time.LocalTime effectiveStart, java.time.LocalTime effectiveEnd) {
        if (!slot.getStadium().getStadiumId().equals(stadium.getStadiumId())) {
            throw new BadRequestException(
                    "Khung giờ #" + slot.getSlotId() + " không thuộc sân #" + stadium.getStadiumId());
        }
        if (slot.getSlotStatus() == SlotStatus.MAINTENANCE) {
            throw new BadRequestException(
                    "Khung giờ #" + slot.getSlotId() + " đang bảo trì, không thể đặt");
        }
        if (maintenanceScheduleService.isSlotUnderMaintenance(stadium, reservationDate, effectiveStart, effectiveEnd)) {
            throw new BadRequestException(
                    "Sân đang trong lịch bảo trì ngày " + reservationDate + ", không thể đặt");
        }
        LocalDateTime slotStart = LocalDateTime.of(reservationDate, effectiveStart);
        if (!slotStart.isAfter(LocalDateTime.now())) {
            throw new BadRequestException(
                    "Khung giờ đã qua — không thể đặt sân cho thời điểm trong quá khứ");
        }
    }

    /**
     * Tính tổng phụ kiện + build danh sách entity sẽ persist sau khi booking có ID.
     * Server TỰ lookup giá từ DB — KHÔNG tin unitPrice client.
     */
    private AccessoryComputation computeAccessories(List<AccessoryItem> items) {
        AccessoryComputation result = new AccessoryComputation();
        if (items == null || items.isEmpty()) {
            return result;
        }
        for (AccessoryItem item : items) {
            Accessory acc = accessoryRepository.findById(item.getAccessoryId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Không tìm thấy phụ kiện với ID " + item.getAccessoryId()));

            if (!Boolean.TRUE.equals(acc.getIsAvailable())) {
                throw new BadRequestException(
                        "Phụ kiện #" + acc.getAccessoryId() + " hiện không khả dụng");
            }

            BigDecimal lineTotal = acc.getPricePerUnit()
                    .multiply(BigDecimal.valueOf(item.getQuantity()));
            result.total = result.total.add(lineTotal);
            result.entities.add(BookingAccessory.builder()
                    .accessoryId(acc.getAccessoryId())
                    .quantity(item.getQuantity())
                    .unitPrice(acc.getPricePerUnit())
                    .build());
        }
        return result;
    }

    /** Persist booking + accessories trong cùng transaction. */
    private Booking persistBooking(User customer, Stadium stadium, TimeSlot slot,
                                   CreateBookingRequest request, BigDecimal totalPrice, BigDecimal serviceFee,
                                   List<BookingAccessory> accessories) {
        LocalDateTime now = LocalDateTime.now();
        Booking booking = Booking.builder()
                .user(customer)
                .stadium(stadium)
                .slot(slot)
                .totalPrice(totalPrice)
                .serviceFee(serviceFee)
                .bookingStatus(BookingStatus.PENDING_PAYMENT)
                .paymentStatus(PaymentStatus.UNPAID)
                .reservationDate(request.getReservationDate())
                .note(request.getNote())
                .expiredAt(now.plusMinutes(PAYMENT_HOLD_MINUTES))
                .build();

        Booking saved = bookingRepository.save(booking);

        if (!accessories.isEmpty()) {
            for (BookingAccessory ba : accessories) {
                ba.setBooking(saved);
            }
            bookingAccessoryRepository.saveAll(accessories);
            log.info("🎾 UC-CUS-01: Booking #{} kèm {} phụ kiện",
                    saved.getBookingId(), accessories.size());
        }
        return saved;
    }

    /** Kết quả tính phụ kiện: tổng tiền + danh sách entity chờ persist. */
    private static final class AccessoryComputation {
        BigDecimal total = BigDecimal.ZERO;
        final List<BookingAccessory> entities = new ArrayList<>();
    }

    @Override
    @Transactional
    public BookingDetailResponse confirmPayment(UserPrincipal principal, Integer bookingId) {
        Booking booking = bookingRepository.findDetailById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy booking với ID " + bookingId));

        // Chỉ chủ booking mới được xác nhận.
        Integer currentUserId = principal.getUser().getUserId();
        if (booking.getUser() == null || !booking.getUser().getUserId().equals(currentUserId)) {
            throw new BadRequestException("Bạn không có quyền xác nhận thanh toán booking này");
        }

        if (booking.getBookingStatus() == BookingStatus.CANCELLED) {
            throw new BadRequestException(
                    "Booking #" + bookingId + " đã bị huỷ (quá hạn thanh toán)");
        }
        if (booking.getBookingStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new BadRequestException(
                    "Booking #" + bookingId + " không ở trạng thái chờ thanh toán. "
                            + "Hiện tại: " + booking.getBookingStatus());
        }

        // Chỉ CẢNH BÁO, không chặn: đây là luồng "khách xác nhận trả tiền mặt tại sân" (VNPay
        // dùng handleVnpayReturn() riêng, không đi qua đây). Chặn ở đây sẽ khiến khách mất chỗ
        // dù đã đồng ý trả tiền mặt — tệ hơn hiện trạng.
        // createSchedule chỉ conflict-check với booking CONFIRMED (không tính PENDING_PAYMENT) nên
        // vẫn còn khe hở hẹp (~5 phút) nếu Owner đặt bảo trì đúng lúc khách đang thanh toán — log lại
        // rõ ràng để Owner/Admin chủ động xử lý thay vì âm thầm trôi qua. Check theo đúng khung giờ
        // của slot (không phải cả ngày) để tránh cảnh báo giả khi bảo trì chỉ chặn 1 khung giờ khác.
        if (booking.getStadium() != null
                && maintenanceScheduleService.isSlotUnderMaintenance(booking.getStadium(), booking.getReservationDate(),
                        booking.getSlot().getStartTime(), booking.getSlot().getEndTime())) {
            log.warn("⚠️ Booking #{} được xác nhận thanh toán trong lúc sân {} đang bảo trì ngày {} — "
                            + "cần Owner/Admin kiểm tra và liên hệ khách để xử lý (đổi lịch/hoàn tiền) nếu cần.",
                    booking.getBookingId(), booking.getStadium().getStadiumId(), booking.getReservationDate());
        }

        // AWAITING_CASH_PAYMENT (không phải PAID) vì tiền chưa thực sự được thu qua cổng thanh
        // toán nào — khách mới chỉ xác nhận ý định trả tiền mặt tại sân. Đánh dấu PAID ở đây từng
        // khiến refund/revenue hiểu nhầm là tiền đã thu thật (docs/qa_findings_refactor_plan.md mục 1.5).
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        booking.setPaymentStatus(PaymentStatus.AWAITING_CASH_PAYMENT);
        booking.setExpiredAt(null);
        Booking saved = bookingRepository.save(booking);

        // Ghi nhận Payment PENDING cho cash — giống hệt cách savePayment() làm cho VNPay trước khi
        // gateway callback — để refund/revenue có 1 dòng ledger để tra cứu thay vì không có gì.
        paymentRepository.save(Payment.builder()
                .booking(saved)
                .paymentMethod(PaymentMethod.CASH)
                .amount(saved.getTotalPrice())
                .transactionCode("CASH_" + saved.getBookingId())
                .paymentStatus(TransactionStatus.PENDING)
                .paidAt(null)
                .build());

        log.info("💳 UC-CUS-01: Booking #{} xác nhận trả tiền mặt — CONFIRMED, chờ thu tiền tại sân, expiredAt cleared",
                saved.getBookingId());

        return toBookingDetailResponse(saved, saved.getStadium(), saved.getSlot());
    }

    @Override
    @Transactional
    public BookingDetailResponse confirmCashPaymentReceived(UserPrincipal principal, Integer bookingId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy booking với ID " + bookingId));

        Integer currentUserId = principal.getUser().getUserId();
        checkBookingOwnership(principal, booking);

        // Idempotent — double-click hoặc network retry, trả trạng thái hiện tại thay vì lỗi.
        if (booking.getPaymentStatus() == PaymentStatus.PAID) {
            return toBookingDetailResponse(booking, booking.getStadium(), booking.getSlot());
        }

        validateCashPaymentStatus(booking, bookingId);

        Payment cashPayment = paymentRepository
                .findCashPaymentsByBookingIdAndMethodAndStatus(
                        bookingId, PaymentMethod.CASH, TransactionStatus.PENDING)
                .stream().findFirst()
                .orElseThrow(() -> {
                    log.warn("Booking #{} ở AWAITING_CASH_PAYMENT nhưng không có Payment CASH/PENDING tương ứng",
                            bookingId);
                    return new BadRequestException(
                            "Không tìm thấy giao dịch tiền mặt đang chờ xác nhận cho đơn này");
                });
        cashPayment.setPaymentStatus(TransactionStatus.SUCCESS);
        cashPayment.setPaidAt(LocalDateTime.now());
        paymentRepository.save(cashPayment);

        booking.setPaymentStatus(PaymentStatus.PAID);
        Booking saved = bookingRepository.save(booking);

        // Ghi nhận vào ví nội bộ
        recordWalletForCashPayment(saved);

        try {
            notificationService.publishNotificationEvent(
                    saved.getUser().getUserId(),
                    "Đã xác nhận thu tiền mặt",
                    "Chủ sân đã xác nhận thu tiền mặt cho đơn đặt sân #" + saved.getBookingId()
                            + " tại " + saved.getStadium().getStadiumName() + ".",
                    com.sportvenue.entity.enums.NotificationType.PAYMENT,
                    String.valueOf(saved.getBookingId()));
        } catch (Exception e) {
            log.error("Failed to publish cash-payment-confirmed notification for booking {}", saved.getBookingId(), e);
        }

        adminDashboardService.evictDashboardCache();
        log.info("💵 Owner {} xác nhận đã thu tiền mặt cho booking #{}", currentUserId, saved.getBookingId());

        return toBookingDetailResponse(saved, saved.getStadium(), saved.getSlot());
    }

    @Override
    @Transactional
    public BookingDetailResponse confirmRemainingPaymentReceived(UserPrincipal principal, Integer bookingId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy booking với ID " + bookingId));

        Integer currentUserId = principal.getUser().getUserId();
        checkBookingOwnership(principal, booking);

        // Idempotent — double-click hoặc network retry, trả trạng thái hiện tại thay vì lỗi.
        if (booking.getPaymentStatus() == PaymentStatus.PAID) {
            return toBookingDetailResponse(booking, booking.getStadium(), booking.getSlot());
        }

        validateRemainingPaymentStatus(booking, bookingId);

        Payment depositPayment = paymentRepository.findSuccessPaymentsByBookingId(bookingId)
                .stream().findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "Không tìm thấy giao dịch cọc đã thanh toán cho đơn này"));

        BigDecimal depositAmount = depositPayment.getAmount();
        BigDecimal totalPrice = booking.getTotalPrice();
        BigDecimal remainingAmount = totalPrice.subtract(depositAmount);

        Payment remainingPayment = Payment.builder()
                .booking(booking)
                .paymentMethod(PaymentMethod.CASH)
                .amount(remainingAmount)
                .transactionCode("CASH-REMAINING-" + bookingId)
                .paymentStatus(TransactionStatus.SUCCESS)
                .paidAt(LocalDateTime.now())
                .build();
        paymentRepository.save(remainingPayment);

        booking.setPaymentStatus(PaymentStatus.PAID);
        Booking saved = bookingRepository.save(booking);

        // Không ghi nhận tăng ví Owner vì Owner đã thu tiền mặt trực tiếp bên ngoài hệ thống.
        // Tránh tình trạng Owner được nhận tiền 2 lần (offline + rút tiền online từ ví ảo).
        // (Khác với payRemainingWithWallet khi tiền được trừ từ ví Customer chuyển sang ví Owner).

        try {
            notificationService.publishNotificationEvent(
                    saved.getUser().getUserId(),
                    "Đã xác nhận thanh toán đủ",
                    "Chủ sân đã xác nhận thu đủ phần còn lại cho đơn đặt sân #" + saved.getBookingId()
                            + " tại " + saved.getStadium().getStadiumName() + ".",
                    com.sportvenue.entity.enums.NotificationType.PAYMENT,
                    String.valueOf(saved.getBookingId()));
        } catch (Exception e) {
            log.error("Failed to publish remaining-payment-confirmed notification for booking {}", saved.getBookingId(), e);
        }

        adminDashboardService.evictDashboardCache();
        log.info("💵 Owner {} xác nhận đã thu nốt phần còn lại cho booking #{}", currentUserId, saved.getBookingId());

        return toBookingDetailResponse(saved, saved.getStadium(), saved.getSlot());
    }

    @Override
    @Transactional
    public BookingDetailResponse payWithWallet(UserPrincipal principal, Integer bookingId, String paymentOption) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy booking với ID " + bookingId));

        checkBookingBelongsToCustomer(principal, booking);

        // Idempotent — double-click hoặc network retry, trả trạng thái hiện tại thay vì lỗi.
        if (booking.getPaymentStatus() == PaymentStatus.PAID) {
            return toBookingDetailResponse(booking, booking.getStadium(), booking.getSlot());
        }

        if (booking.getBookingStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new BadRequestException("Đơn này không thể thanh toán (trạng thái hiện tại: "
                    + booking.getBookingStatus() + ")");
        }
        if (booking.getExpiredAt() != null && booking.getExpiredAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Đơn đặt sân đã hết hạn giữ chỗ — vui lòng đặt lại");
        }

        BigDecimal totalPrice = booking.getTotalPrice();
        if (totalPrice == null) {
            throw new BadRequestException("Đơn đặt sân chưa có tổng tiền — không thể thanh toán");
        }

        boolean isDeposit = "DEPOSIT".equalsIgnoreCase(paymentOption);
        BigDecimal amountToPay = isDeposit
                ? com.sportvenue.util.BookingPricingUtils.calculateDepositAmount(totalPrice)
                : totalPrice;

        Integer customerId = principal.getUserId();
        walletService.debitCustomerWalletForPayment(
                customerId, amountToPay, bookingId,
                isDeposit ? "Đặt cọc đơn đặt sân #" + bookingId + " bằng Ví"
                        : "Thanh toán đơn đặt sân #" + bookingId + " bằng Ví");

        Payment payment = Payment.builder()
                .booking(booking)
                .paymentMethod(PaymentMethod.WALLET)
                .amount(amountToPay)
                .transactionCode("WALLET-" + bookingId + "-" + System.currentTimeMillis())
                .paymentStatus(TransactionStatus.SUCCESS)
                .paidAt(LocalDateTime.now())
                .build();
        paymentRepository.save(payment);

        booking.setBookingStatus(BookingStatus.CONFIRMED);
        booking.setPaymentStatus(isDeposit ? PaymentStatus.DEPOSITED : PaymentStatus.PAID);
        booking.setExpiredAt(null);
        Booking saved = bookingRepository.save(booking);

        creditOwnerAndPlatformForWalletPayment(saved, amountToPay, isDeposit
                ? "Tiền đặt cọc đặt sân #" + saved.getBookingId() + " bằng Ví (đã trừ phí dịch vụ)"
                : "Doanh thu đặt sân #" + saved.getBookingId() + " bằng Ví (đã trừ phí dịch vụ)");

        try {
            customerNotificationService.notifyPaymentReceived(saved.getUser().getUserId(), payment);
        } catch (Exception e) {
            log.error("Failed to publish wallet payment notification for booking {}", saved.getBookingId(), e);
        }

        adminDashboardService.evictDashboardCache();
        log.info("💳 Customer {} thanh toán {} bằng Ví cho booking #{} — amount={}",
                customerId, isDeposit ? "cọc" : "toàn phần", saved.getBookingId(), amountToPay);

        return toBookingDetailResponse(saved, saved.getStadium(), saved.getSlot());
    }

    @Override
    @Transactional
    public BookingDetailResponse payRemainingWithWallet(UserPrincipal principal, Integer bookingId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy booking với ID " + bookingId));

        checkBookingBelongsToCustomer(principal, booking);

        // Idempotent guard — bắt buộc để tránh race với confirmRemainingPaymentReceived (Owner tự
        // xác nhận tiền mặt) khi cả 2 đường đóng đơn cùng nhắm vào 1 booking DEPOSITED: nếu thiếu,
        // ví Customer có thể bị trừ tiền dù đơn đã được Owner xác nhận thu tiền mặt xong.
        if (booking.getPaymentStatus() == PaymentStatus.PAID) {
            return toBookingDetailResponse(booking, booking.getStadium(), booking.getSlot());
        }

        validateRemainingPaymentStatus(booking, bookingId);

        Payment depositPayment = paymentRepository.findSuccessPaymentsByBookingId(bookingId)
                .stream().findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "Không tìm thấy giao dịch cọc đã thanh toán cho đơn này"));

        BigDecimal depositAmount = depositPayment.getAmount();
        BigDecimal totalPrice = booking.getTotalPrice();
        BigDecimal remainingAmount = totalPrice.subtract(depositAmount);

        Integer customerId = principal.getUserId();
        walletService.debitCustomerWalletForPayment(
                customerId, remainingAmount, bookingId,
                "Thanh toán nốt phần còn lại đơn đặt sân #" + bookingId + " bằng Ví");

        Payment remainingPayment = Payment.builder()
                .booking(booking)
                .paymentMethod(PaymentMethod.WALLET)
                .amount(remainingAmount)
                .transactionCode("WALLET-REMAINING-" + bookingId)
                .paymentStatus(TransactionStatus.SUCCESS)
                .paidAt(LocalDateTime.now())
                .build();
        paymentRepository.save(remainingPayment);

        booking.setPaymentStatus(PaymentStatus.PAID);
        Booking saved = bookingRepository.save(booking);

        // Không trừ phí dịch vụ nữa — đã trừ lúc đặt cọc, y hệt confirmRemainingPaymentReceived.
        recordWalletForRemainingPayment(saved, remainingAmount);

        try {
            customerNotificationService.notifyPaymentReceived(saved.getUser().getUserId(), remainingPayment);
        } catch (Exception e) {
            log.error("Failed to publish wallet remaining-payment notification for booking {}", saved.getBookingId(), e);
        }

        adminDashboardService.evictDashboardCache();
        log.info("💳 Customer {} tự thanh toán nốt phần còn lại bằng Ví cho booking #{} — amount={}",
                customerId, saved.getBookingId(), remainingAmount);

        return toBookingDetailResponse(saved, saved.getStadium(), saved.getSlot());
    }

    private void checkBookingBelongsToCustomer(UserPrincipal principal, Booking booking) {
        Integer currentUserId = principal.getUser().getUserId();
        if (booking.getUser() == null || !booking.getUser().getUserId().equals(currentUserId)) {
            throw new ForbiddenException("Bạn không có quyền thanh toán đơn đặt sân này");
        }
    }

    private void creditOwnerAndPlatformForWalletPayment(Booking booking, BigDecimal amount, String note) {
        Owner resolvedOwner = booking.getStadium() != null ? booking.getStadium().resolveOwner() : null;
        if (resolvedOwner == null) {
            return;
        }
        BigDecimal serviceFee = booking.getServiceFee() != null ? booking.getServiceFee() : BigDecimal.ZERO;
        BigDecimal ownerShare = amount.subtract(serviceFee);
        walletService.recordOwnerTransaction(
                resolvedOwner.getOwnerId(), ownerShare, booking.getBookingId(),
                WalletTransactionType.BOOKING_CREDIT, note);
        if (serviceFee.compareTo(BigDecimal.ZERO) > 0) {
            walletService.recordPlatformTransaction(
                    serviceFee, booking.getBookingId(),
                    WalletTransactionType.SERVICE_FEE_CREDIT,
                    "Phí dịch vụ từ đơn đặt sân #" + booking.getBookingId());
        }
    }

    private void checkBookingOwnership(UserPrincipal principal, Booking booking) {
        Integer currentUserId = principal.getUser().getUserId();
        Owner resolvedOwner = booking.getStadium() != null ? booking.getStadium().resolveOwner() : null;
        if (resolvedOwner == null || resolvedOwner.getUser() == null
                || !resolvedOwner.getUser().getUserId().equals(currentUserId)) {
            throw new ForbiddenException("Bạn không có quyền xác nhận thanh toán cho đơn đặt sân này");
        }
    }

    private void validateCashPaymentStatus(Booking booking, Integer bookingId) {
        if (booking.getBookingStatus() == BookingStatus.CANCELLED) {
            throw new BadRequestException("Booking #" + bookingId + " đã bị hủy — không thể xác nhận thu tiền");
        }
        if (booking.getPaymentStatus() != PaymentStatus.AWAITING_CASH_PAYMENT) {
            throw new BadRequestException(
                    "Booking #" + bookingId + " không ở trạng thái chờ thu tiền mặt. Hiện tại: "
                            + booking.getPaymentStatus());
        }
    }

    private void validateRemainingPaymentStatus(Booking booking, Integer bookingId) {
        if (booking.getBookingStatus() == BookingStatus.CANCELLED) {
            throw new BadRequestException("Booking #" + bookingId + " đã bị hủy — không thể xác nhận thu tiền");
        }
        if (booking.getPaymentStatus() != PaymentStatus.DEPOSITED) {
            throw new BadRequestException(
                    "Booking #" + bookingId + " không ở trạng thái đặt cọc. Hiện tại: "
                            + booking.getPaymentStatus());
        }
    }

    private void recordWalletForCashPayment(Booking booking) {
        Owner bookingOwner = booking.getStadium() != null ? booking.getStadium().resolveOwner() : null;
        if (bookingOwner == null) {
            return;
        }
        BigDecimal serviceFee = booking.getServiceFee() != null ? booking.getServiceFee() : BigDecimal.ZERO;
        if (serviceFee.compareTo(BigDecimal.ZERO) > 0) {
            walletService.recordOwnerTransaction(
                    bookingOwner.getOwnerId(),
                    serviceFee.negate(),
                    booking.getBookingId(),
                    WalletTransactionType.SERVICE_FEE_DEBIT,
                    "Khấu trừ phí dịch vụ đơn đặt sân tiền mặt #" + booking.getBookingId()
            );
            walletService.recordPlatformTransaction(
                    serviceFee,
                    booking.getBookingId(),
                    WalletTransactionType.SERVICE_FEE_CREDIT,
                    "Phí dịch vụ từ đơn tiền mặt #" + booking.getBookingId()
            );
        }
    }

    private void recordWalletForRemainingPayment(Booking booking, BigDecimal remainingAmount) {
        Owner bookingOwner = booking.getStadium() != null ? booking.getStadium().resolveOwner() : null;
        if (bookingOwner != null) {
            walletService.recordOwnerTransaction(
                    bookingOwner.getOwnerId(),
                    remainingAmount,
                    booking.getBookingId(),
                    WalletTransactionType.BOOKING_CREDIT,
                    "Thu nốt phần còn lại đơn đặt cọc #" + booking.getBookingId()
            );
        }
    }

    @Override
    public BookingDetailResponse cancelBooking(UserPrincipal principal, Integer bookingId, String reason) {
        Integer currentUserId = principal.getUser().getUserId();
        
        // 1. Transaction 1: Khóa DB, cập nhật trạng thái Booking và tạo Refund Payment (PENDING)
        CancelProcessContext ctx = processLocalCancellationTx(bookingId, currentUserId, reason);

        // 2. Gọi Gateway và Transaction 2: Cập nhật trạng thái SUCCESS/FAILED
        if (ctx.refundPayment != null && ctx.originalPayment != null) {
            processGatewayRefundTx(ctx, bookingId, reason, currentUserId);
        }

        // 3. Fetch lại Booking mới nhất để build response
        Booking finalBooking = transactionTemplate.execute(status -> 
                bookingRepository.findDetailById(bookingId).orElse(ctx.booking));
        return toBookingDetailResponse(finalBooking, finalBooking.getStadium(), finalBooking.getSlot());
    }

    private CancelProcessContext processLocalCancellationTx(Integer bookingId, Integer currentUserId, String reason) {
        return transactionTemplate.execute(status -> {
            Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Không tìm thấy booking với ID " + bookingId));

            validateCancellation(booking, currentUserId);

            boolean wasReallyPaid = booking.getPaymentStatus() == PaymentStatus.PAID
                    || booking.getPaymentStatus() == PaymentStatus.DEPOSITED;
            
            Payment refundPayment = null;
            Payment originalPayment = null;

            if (wasReallyPaid) {
                // Kiểm tra xem đã có giao dịch hoàn tiền nào (PENDING hoặc SUCCESS) đang tồn tại chưa để tránh Double Refund
                paymentRepository.findRefundPaymentByBookingId(bookingId).stream().findFirst().ifPresent(p -> {
                    if (p.getPaymentStatus() == TransactionStatus.PENDING || p.getPaymentStatus() == TransactionStatus.SUCCESS) {
                        throw new BadRequestException("Yêu cầu hủy đơn và hoàn tiền đang được xử lý hoặc đã thành công.");
                    }
                });

                List<Payment> successPaymentsForCancel = paymentRepository.findSuccessPaymentsByBookingId(bookingId);
                originalPayment = successPaymentsForCancel.isEmpty()
                        ? null
                        : RefundServiceImpl.pickReferencePayment(successPaymentsForCancel);

                if (originalPayment != null) {
                    // docs/qa_findings_refactor_plan.md mục 1.2: PHẢI áp cùng công thức tiering
                    // (24h/12h/OWNER_FAULT) với /owner/bookings/{id}/refund — trước đây hoàn thẳng
                    // 100% originalPayment.getAmount() bất kể còn bao lâu tới giờ chơi.
                    // SUM tất cả payment SUCCESS (không chỉ payment tham chiếu) — đơn cọc đã thu nốt
                    // có 2 dòng (VNPay cọc + CASH còn lại), tính thiếu sẽ hoàn thiếu tiền cho khách.
                    BigDecimal totalPaidForCancel = RefundServiceImpl.sumPaidAmount(successPaymentsForCancel);
                    RefundServiceImpl.RefundCalculation calculation = RefundServiceImpl.calculateRefund(
                            booking, totalPaidForCancel, RefundReasonType.CUSTOMER_REQUEST, null, false);

                    refundPayment = Payment.builder()
                        .booking(booking)
                        .paymentMethod(originalPayment.getPaymentMethod())
                        .amount(calculation.getAmount().negate())
                        .transactionCode("RFND_CUST_" + originalPayment.getTransactionCode())
                        .paymentStatus(TransactionStatus.PENDING)
                        .paidAt(LocalDateTime.now())
                        .build();
                    refundPayment = paymentRepository.save(refundPayment);
                }
            } else {
                booking.setBookingStatus(BookingStatus.CANCELLED);
                booking.setCancelReason(reason);
                booking.setExpiredAt(null);

                TimeSlot slot = booking.getSlot();
                if (slot != null && slot.getSlotStatus() == SlotStatus.BOOKED) {
                    slot.setSlotStatus(SlotStatus.AVAILABLE);
                    timeSlotRepository.save(slot);
                }
                booking = bookingRepository.save(booking);
                
                sendCancellationEmailAndNotification(booking, reason, currentUserId);
            }

            log.info("[UC-CUS-03] Booking #{} was locally checked for cancellation by userId={}, reason={}",
                    booking.getBookingId(), currentUserId, reason);

            return new CancelProcessContext(booking, originalPayment, refundPayment);
        });
    }

    private void validateCancellation(Booking booking, Integer currentUserId) {
        boolean isCustomer = booking.getUser() != null
                && booking.getUser().getUserId().equals(currentUserId);
        boolean isVenueOwner = booking.getStadium() != null
                && booking.getStadium().getOwner() != null
                && booking.getStadium().getOwner().getUser() != null
                && booking.getStadium().getOwner().getUser().getUserId().equals(currentUserId);

        if (!isCustomer && !isVenueOwner) {
            throw new ForbiddenException("Bạn không có quyền hủy đơn đặt sân này");
        }

        BookingStatus currentStatus = booking.getBookingStatus();
        if (currentStatus == BookingStatus.COMPLETED || currentStatus == BookingStatus.CANCELLED) {
            throw new BadRequestException(
                    "Không thể hủy đơn đặt sân ở trạng thái " + currentStatus);
        }
        
        if (Boolean.TRUE.equals(booking.getIsWalkIn())) {
            throw new BadRequestException("Không thể hoàn/hủy cho đơn khách vãng lai thanh toán tại sân");
        }

        // docs/qa_findings_refactor_plan.md mục 1.2: luồng hủy chung này luôn hoàn 100% không
        // tiering theo giờ, khác với /owner/bookings/{id}/refund áp dụng chính sách chặt chẽ hơn
        // (24h/12h/OWNER_FAULT + bằng chứng). Nếu để Owner dùng luồng này cho booking đã thu tiền
        // thật (PAID/DEPOSITED), họ có thể né hoàn toàn chính sách đó. Chỉ chặn khi ĐÃ thu tiền —
        // Owner vẫn được hủy thẳng booking UNPAID/AWAITING_CASH_PAYMENT (chưa có gì để hoàn) vì đó
        // không phải đường né chính sách, chỉ là dọn đơn chưa phát sinh tiền thật.
        boolean wasReallyPaid = booking.getPaymentStatus() == PaymentStatus.PAID
                || booking.getPaymentStatus() == PaymentStatus.DEPOSITED;
        if (isVenueOwner && !isCustomer && wasReallyPaid) {
            throw new ForbiddenException(
                    "Đơn đã thanh toán — vui lòng dùng chức năng \"Hoàn tiền\" ở trang quản lý booking "
                            + "để áp dụng đúng chính sách hoàn tiền, không thể hủy thẳng qua đây.");
        }
    }

    /**
     * Hoàn tiền cho Customer khi tự hủy đơn — credit thẳng vào Ví nội bộ thay vì gọi gateway VNPay
     * (mock, không làm gì thật). Không còn network call nào để chờ, nên toàn bộ cập nhật Payment +
     * Booking + Customer wallet chạy chung 1 transaction ACID duy nhất.
     */
    private void processGatewayRefundTx(CancelProcessContext ctx, Integer bookingId, String reason, Integer currentUserId) {
        BigDecimal refundAmount = ctx.refundPayment.getAmount().abs();

        transactionTemplate.execute(status -> {
            Payment payment = paymentRepository.findById(ctx.refundPayment.getPaymentId()).orElse(null);
            if (payment != null) {
                payment.setPaymentStatus(TransactionStatus.SUCCESS);
                paymentRepository.save(payment);
            }

            Booking booking = bookingRepository.findById(bookingId).orElse(null);
            if (booking != null) {
                booking.setBookingStatus(BookingStatus.CANCELLED);
                booking.setCancelReason(reason);
                booking.setPaymentStatus(PaymentStatus.REFUNDED);
                booking.setExpiredAt(null);

                TimeSlot slot = booking.getSlot();
                if (slot != null && slot.getSlotStatus() == SlotStatus.BOOKED) {
                    slot.setSlotStatus(SlotStatus.AVAILABLE);
                    timeSlotRepository.save(slot);
                }
                bookingRepository.save(booking);

                // Credit ví Customer — thay cho gọi gateway VNPay, bất kể đơn gốc trả bằng
                // CASH/VNPAY/WALLET đều hoàn về đây. Hủy <12h trước giờ chơi tiering về 0đ
                // (docs/qa_findings_refactor_plan.md mục 1.3) thì không có gì để credit.
                if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
                    walletService.recordCustomerTransaction(
                            booking.getUser().getUserId(),
                            refundAmount,
                            booking.getBookingId(),
                            WalletTransactionType.CUSTOMER_REFUND_CREDIT,
                            "Hoàn tiền huỷ đơn đặt sân #" + booking.getBookingId()
                    );

                    // Khấu trừ ví Owner số tiền thực hoàn cho khách (đã khấu trừ phí dịch vụ)
                    Owner resolvedOwner = booking.getStadium() != null ? booking.getStadium().resolveOwner() : null;
                    if (resolvedOwner != null) {
                        walletService.recordOwnerTransaction(
                                resolvedOwner.getOwnerId(),
                                refundAmount.negate(),
                                booking.getBookingId(),
                                WalletTransactionType.REFUND_DEBIT,
                                "Khách huỷ đặt sân tự động đơn #" + booking.getBookingId()
                        );
                    }
                }

                sendCancellationEmailAndNotification(booking, reason, currentUserId);
            }
            return null;
        });
    }

    @RequiredArgsConstructor
    private static class CancelProcessContext {
        final Booking booking;
        final Payment originalPayment;
        final Payment refundPayment;
    }

    private void sendCancellationEmailAndNotification(Booking booking, String reason, Integer currentUserId) {
        String cancelledBy = booking.getUser().getUserId().equals(currentUserId) ? "Khách hàng" : "Chủ sân";
        try {
            customerNotificationService.notifyBookingCancelled(booking.getUser().getUserId(), booking, reason);
        } catch (Exception e) {
            log.error("Failed to publish cancellation notification for booking {}", booking.getBookingId(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeSlotResponse> getSlotsByDate(Integer stadiumId, LocalDate date) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy sân với ID " + stadiumId));

        List<TimeSlot> slots = timeSlotRepository.findByStadiumStadiumIdAndSlotStatus(
                stadium.getStadiumId(), SlotStatus.AVAILABLE);

        Set<Integer> bookedSlotIds = Set.copyOf(
                bookingRepository.findBookedSlotIds(stadiumId, date, ACTIVE_STATUSES));

        List<TimeSlotException> exceptions = timeSlotExceptionRepository.findByStadiumAndDateRange(stadiumId, date, date);
        Map<Integer, TimeSlotException> exceptionMap = exceptions.stream()
                .collect(Collectors.toMap(e -> e.getSlot().getSlotId(), e -> e));

        LocalDateTime now = LocalDateTime.now();

        java.time.LocalTime openT = stadium.getOpenTime() != null ? stadium.getOpenTime() : java.time.LocalTime.MIN;
        java.time.LocalTime closeT = stadium.getCloseTime() != null ? stadium.getCloseTime() : java.time.LocalTime.MAX;

        return slots.stream()
                .filter(slot -> {
                    TimeSlotException exception = exceptionMap.get(slot.getSlotId());
                    // Resolve effective times — apply override before comparing with open/close window.
                    java.time.LocalTime effectiveStart = (exception != null && exception.getStartTimeOverride() != null)
                            ? exception.getStartTimeOverride() : slot.getStartTime();
                    java.time.LocalTime effectiveEnd = (exception != null && exception.getEndTimeOverride() != null)
                            ? exception.getEndTimeOverride() : slot.getEndTime();
                    if (effectiveStart.isBefore(openT) || effectiveEnd.isAfter(closeT)) {
                        return false;
                    }
                    return exception == null || !Boolean.TRUE.equals(exception.getHidden());
                })
                .map(slot -> {
                    TimeSlotException exception = exceptionMap.get(slot.getSlotId());
                    java.time.LocalTime startT = (exception != null && exception.getStartTimeOverride() != null)
                            ? exception.getStartTimeOverride() : slot.getStartTime();
                    java.time.LocalTime endT = (exception != null && exception.getEndTimeOverride() != null)
                            ? exception.getEndTimeOverride() : slot.getEndTime();
                    BigDecimal price = (exception != null && exception.getPriceOverride() != null)
                            ? exception.getPriceOverride()
                            : slot.getPricePerSlot();
                    boolean isClosed = exception != null && Boolean.TRUE.equals(exception.getClosed());
                    boolean isFuture = LocalDateTime.of(date, startT).isAfter(now);
                    boolean isBooked = bookedSlotIds.contains(slot.getSlotId());

                    return TimeSlotResponse.builder()
                            .slotId(slot.getSlotId())
                            .stadiumId(slot.getStadium().getStadiumId())
                            .startTime(startT)
                            .endTime(endT)
                            .pricePerSlot(price)
                            .slotStatus(isClosed ? "OWNER_CLOSED" : (slot.getSlotStatus() != null ? slot.getSlotStatus().name() : null))
                            .available(!isBooked && isFuture && !isClosed)
                            .build();
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WeeklySlotResponse getWeeklySlots(Integer stadiumId, LocalDate weekStart) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy sân với ID " + stadiumId));

        // Snap về thứ 2 gần nhất — cho phép FE truyền bất kỳ ngày nào trong tuần.
        LocalDate monday = snapToMonday(weekStart);
        LocalDate sunday = monday.plusDays(6);

        List<TimeSlot> slots = timeSlotRepository.findByStadiumStadiumIdAndSlotStatus(
                stadium.getStadiumId(), SlotStatus.AVAILABLE);

        List<Booking> weeklyBookings = bookingRepository.findWeeklyBookings(
                stadiumId, monday, sunday, ACTIVE_STATUSES);

        List<TimeSlotException> exceptions = timeSlotExceptionRepository.findByStadiumAndDateRange(stadiumId, monday, sunday);

        // Map (date → slotId → booking). PENDING_PAYMENT is a temporary hold;
        // PENDING/CONFIRMED are displayed as booked.
        Map<LocalDate, Map<Integer, Booking>> bookingsByDate = weeklyBookings.stream()
                .collect(Collectors.groupingBy(
                        Booking::getReservationDate,
                        Collectors.toMap(
                                b -> b.getSlot().getSlotId(),
                                b -> b,
                                (left, right) -> left.getBookingStatus() == BookingStatus.PENDING_PAYMENT
                                        ? right : left)));

        // Map (date → Map<slotId → exception>)
        Map<LocalDate, Map<Integer, TimeSlotException>> exceptionsByDate = exceptions.stream()
                .collect(Collectors.groupingBy(
                        TimeSlotException::getExceptionDate,
                        Collectors.toMap(
                                e -> e.getSlot().getSlotId(),
                                e -> e
                        )
                ));

        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter hhmm = DateTimeFormatter.ofPattern("HH:mm");

        // Batch — 1 query cho cả tuần thay vì gọi isSlotUnderMaintenance (1-2 query/lần) mỗi slot mỗi ngày.
        Map<LocalDate, MaintenanceScheduleService.DayMaintenance> dayMaintenanceByDate =
                maintenanceScheduleService.getDayMaintenanceForDateRange(stadium, monday, sunday);

        List<WeeklySlotDayDto> days = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            LocalDate date = monday.plusDays(i);
            Map<Integer, Booking> dayBookings = bookingsByDate.getOrDefault(date, Map.of());
            Map<Integer, TimeSlotException> dayExceptions = exceptionsByDate.getOrDefault(date, Map.of());
            MaintenanceScheduleService.DayMaintenance dayMaintenance =
                    dayMaintenanceByDate.getOrDefault(date, MaintenanceScheduleService.DayMaintenance.NONE);
            days.add(buildWeeklySlotDay(date, stadium, slots, dayBookings, dayExceptions, dayMaintenance, now, hhmm));
        }

        log.info("📅 UC-CUS-01: Stadium {} weekly slots — {}..{} ({} bookings)",
                stadiumId, monday, sunday, weeklyBookings.size());

        return WeeklySlotResponse.builder()
                .weekStart(monday.toString())
                .weekEnd(sunday.toString())
                .days(days)
                .build();
    }

    private WeeklySlotDayDto buildWeeklySlotDay(
            LocalDate date,
            Stadium stadium,
            List<TimeSlot> slots,
            Map<Integer, Booking> dayBookings,
            Map<Integer, TimeSlotException> dayExceptions,
            MaintenanceScheduleService.DayMaintenance dayMaintenance,
            LocalDateTime now,
            DateTimeFormatter hhmm) {
        java.time.LocalTime openT = stadium.getOpenTime() != null ? stadium.getOpenTime() : java.time.LocalTime.MIN;
        java.time.LocalTime closeT = stadium.getCloseTime() != null ? stadium.getCloseTime() : java.time.LocalTime.MAX;

        List<WeeklySlotItemDto> daySlots = slots.stream()
                .sorted(Comparator.comparing(TimeSlot::getStartTime))
                .filter(slot -> {
                    TimeSlotException exception = dayExceptions.get(slot.getSlotId());
                    java.time.LocalTime effectiveStart = (exception != null && exception.getStartTimeOverride() != null)
                            ? exception.getStartTimeOverride() : slot.getStartTime();
                    java.time.LocalTime effectiveEnd = (exception != null && exception.getEndTimeOverride() != null)
                            ? exception.getEndTimeOverride() : slot.getEndTime();
                    if (effectiveStart.isBefore(openT) || effectiveEnd.isAfter(closeT)) {
                        return false;
                    }
                    return exception == null || !Boolean.TRUE.equals(exception.getHidden());
                })
                .map(slot -> {
                    TimeSlotException exception = dayExceptions.get(slot.getSlotId());
                    java.time.LocalTime startT = (exception != null && exception.getStartTimeOverride() != null)
                            ? exception.getStartTimeOverride() : slot.getStartTime();
                    java.time.LocalTime endT = (exception != null && exception.getEndTimeOverride() != null)
                            ? exception.getEndTimeOverride() : slot.getEndTime();
                    LocalDateTime slotStart = LocalDateTime.of(date, startT);
                    boolean isClosed = exception != null && Boolean.TRUE.equals(exception.getClosed());
                    BigDecimal price = (exception != null && exception.getPriceOverride() != null)
                            ? exception.getPriceOverride()
                            : slot.getPricePerSlot();

                    Booking activeBooking = dayBookings.get(slot.getSlotId());
                    String status;
                    if (isClosed) {
                        status = "OWNER_CLOSED";
                    } else if (activeBooking != null
                            && activeBooking.getBookingStatus() == BookingStatus.PENDING_PAYMENT) {
                        status = "HELD";
                    } else if (activeBooking != null) {
                        status = "BOOKED";
                    } else if (!slotStart.isAfter(now)) {
                        status = "PAST";
                    } else if (dayMaintenance.overlaps(startT, endT, date)) {
                        status = "MAINTENANCE";
                    } else {
                        status = "AVAILABLE";
                    }
                    
                    return WeeklySlotItemDto.builder()
                            .slotId(slot.getSlotId())
                            .startTime(startT != null ? startT.format(hhmm) : null)
                            .endTime(endT != null ? endT.format(hhmm) : null)
                            .price(price)
                            .status(status)
                            .heldUntil("HELD".equals(status) && activeBooking.getExpiredAt() != null
                                    ? activeBooking.getExpiredAt().toString() : null)
                            .build();
                })
                .toList();

        return WeeklySlotDayDto.builder()
                .date(date.toString())
                .dayName(vietnameseDayName(date))
                .slots(daySlots)
                .build();
    }

    /** Snap {@code date} về thứ 2 của tuần đó (DayOfWeek.MONDAY = 1). */
    private LocalDate snapToMonday(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1L);
    }

    /** Tên thứ tiếng Việt — dùng cho UI weekly grid. */
    private String vietnameseDayName(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case MONDAY -> "Thứ 2";
            case TUESDAY -> "Thứ 3";
            case WEDNESDAY -> "Thứ 4";
            case THURSDAY -> "Thứ 5";
            case FRIDAY -> "Thứ 6";
            case SATURDAY -> "Thứ 7";
            case SUNDAY -> "Chủ nhật";
        };
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<BookingHistoryItemDto> getMyBookings(
            UserPrincipal principal,
            int page,
            int size,
            String statusFilter) {

        Integer userId = principal.getUser().getUserId();
        // Clamp page/size để không throw IllegalArgumentException từ PageRequest.
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size));

        Page<Booking> bookings;
        if (statusFilter == null || statusFilter.isBlank()
                || "all".equalsIgnoreCase(statusFilter)) {
            bookings = bookingRepository
                    .findByUserUserIdOrderByReservationDateDesc(userId, pageable);
        } else {
            List<BookingStatus> statuses = mapStatusFilter(statusFilter);
            bookings = bookingRepository
                    .findByUserUserIdAndBookingStatusInOrderByReservationDateDesc(
                            userId, statuses, pageable);
        }

        log.info("📜 UC-CUS-01: Customer {} xem lịch sử đặt sân — page={}, size={}, status={}, total={}",
                principal.getUser().getEmail(), page, size, statusFilter, bookings.getTotalElements());

        return PageResponse.of(bookings.map(this::toHistoryItemDto));
    }

    /**
     * Map filter status từ FE sang danh sách {@link BookingStatus} tương ứng.
     * Không throw — filter lạ sẽ fallback về "tất cả trạng thái" để tránh
     * crash trang lịch sử.
     */
    private List<BookingStatus> mapStatusFilter(String statusFilter) {
        return switch (statusFilter.toLowerCase()) {
            case "upcoming"  -> List.of(BookingStatus.PENDING_PAYMENT, BookingStatus.PENDING, BookingStatus.CONFIRMED);
            case "completed" -> List.of(BookingStatus.COMPLETED);
            case "cancelled" -> List.of(BookingStatus.CANCELLED);
            case "pending"   -> List.of(BookingStatus.PENDING);
            case "confirmed" -> List.of(BookingStatus.CONFIRMED);
            default -> List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED,
                    BookingStatus.COMPLETED, BookingStatus.CANCELLED);
        };
    }

    /**
     * Map {@link Booking} entity sang {@link BookingHistoryItemDto} — shape
     * phải khớp với {@code BookingHistoryItem} ở Frontend
     * ({@code frontend/src/lib/bookings-api.ts}).
     */
    private BookingHistoryItemDto toHistoryItemDto(Booking booking) {
        Stadium stadium = booking.getStadium();
        TimeSlot slot = booking.getSlot();
        SportType sportType = stadium != null ? stadium.getSportType() : null;

        String imageUrl = null;
        if (stadium != null && stadium.getImages() != null && !stadium.getImages().isEmpty()) {
            StadiumImage firstImage = stadium.getImages().iterator().next();
            imageUrl = firstImage.getImageUrl();
        }

        String dateStr = booking.getReservationDate() != null
                ? booking.getReservationDate().toString()
                : null;

        String timeStr = null;
        if (slot != null) {
            Optional<TimeSlotException> exceptionOpt = timeSlotExceptionRepository.findBySlotSlotIdAndExceptionDate(
                    slot.getSlotId(), booking.getReservationDate());
            LocalTime start = exceptionOpt
                    .filter(e -> e.getStartTimeOverride() != null)
                    .map(TimeSlotException::getStartTimeOverride)
                    .orElse(slot.getStartTime());
            LocalTime end = exceptionOpt
                    .filter(e -> e.getEndTimeOverride() != null)
                    .map(TimeSlotException::getEndTimeOverride)
                    .orElse(slot.getEndTime());

            if (start != null && end != null) {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
                timeStr = start.format(fmt) + " - " + end.format(fmt);
            }
        }

        return BookingHistoryItemDto.builder()
                .id(String.valueOf(booking.getBookingId()))
                .displayId("BK" + String.format("%06d", booking.getBookingId()))
                .venue(stadium != null ? stadium.getStadiumName() : "Sân chưa biết")
                .complexName(stadium != null ? StadiumUtils.resolveComplexName(stadium) : null)
                .sportType(sportType != null ? sportType.getSportName() : "Khác")
                .imageUrl(imageUrl != null ? imageUrl : "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?q=80&w=300&auto=format&fit=crop")
                .date(dateStr)
                .time(timeStr)
                .location(StadiumUtils.resolveAddress(stadium))
                .price(booking.getTotalPrice())
                .status(booking.getBookingStatus() != null
                        ? booking.getBookingStatus().name().toLowerCase()
                        : null)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UC-CUS-04: Chi tiết đơn đặt sân
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingDetailResponse getBookingDetail(UserPrincipal principal, Integer bookingId) {
        Booking booking = bookingRepository.findDetailById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy đơn đặt sân với ID " + bookingId));

        Integer currentUserId = principal.getUser().getUserId();
        if (!booking.getUser().getUserId().equals(currentUserId)) {
            throw new AccessDeniedException("Bạn không có quyền xem đơn đặt sân này");
        }

        log.info("🔍 UC-CUS-04: Customer {} xem chi tiết booking #{}", principal.getUser().getEmail(), bookingId);
        return toBookingDetailResponse(booking, booking.getStadium(), booking.getSlot());
    }



    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private BookingDetailResponse toBookingDetailResponse(Booking booking, Stadium stadium, TimeSlot slot) {
        String imageUrl = null;
        if (stadium.getImages() != null && !stadium.getImages().isEmpty()) {
            imageUrl = stadium.getImages().iterator().next().getImageUrl();
        }
        String sportType = null;
        if (stadium.getSportType() != null) {
            sportType = stadium.getSportType().getSportName();
        }

        BigDecimal paidAmount = calculatePaidAmount(booking.getBookingId());

        BigDecimal refundedAmount = null;
        Integer refundPercent = null;
        if (booking.getPaymentStatus() == PaymentStatus.REFUNDED) {
            refundedAmount = calculateRefundedAmount(booking.getBookingId());
            if (paidAmount != null && paidAmount.compareTo(BigDecimal.ZERO) > 0) {
                refundPercent = refundedAmount
                        .multiply(BigDecimal.valueOf(100))
                        .divide(paidAmount, 0, RoundingMode.HALF_UP)
                        .intValue();
            }
        }

        LocalTime[] effectiveTime = getEffectiveSlotTime(slot, booking.getReservationDate());
        List<com.sportvenue.dto.booking.BookingDetailResponse.AccessoryInfo> accessoriesDto = mapBookingAccessories(booking.getBookingId());

        return BookingDetailResponse.builder()
                .bookingId(booking.getBookingId())
                .displayId("BK" + String.format("%06d", booking.getBookingId()))
                .reservationDate(booking.getReservationDate())
                .slot(BookingDetailResponse.SlotInfo.builder()
                        .slotId(slot.getSlotId())
                        .startTime(effectiveTime[0])
                        .endTime(effectiveTime[1])
                        .build())
                .stadium(BookingDetailResponse.StadiumInfo.builder()
                        .stadiumId(stadium.getStadiumId())
                        .ownerUserId(stadium.resolveOwner() != null && stadium.resolveOwner().getUser() != null
                                ? stadium.resolveOwner().getUser().getUserId() : null)
                        .stadiumName(stadium.getStadiumName())
                        .complexId(StadiumUtils.resolveComplexId(stadium))
                        .complexName(StadiumUtils.resolveComplexName(stadium))
                        .facilityId(StadiumUtils.resolveFacilityId(stadium))
                        .facilityName(StadiumUtils.resolveFacilityName(stadium))
                        .address(StadiumUtils.resolveAddress(stadium))
                        .sportType(sportType)
                        .imageUrl(imageUrl)
                        .build())
                .totalPrice(booking.getTotalPrice())
                .serviceFee(booking.getServiceFee())
                .accessories(accessoriesDto)
                .paidAmount(paidAmount)
                .status(booking.getBookingStatus().name().toLowerCase())
                .paymentStatus(booking.getPaymentStatus().name().toLowerCase())
                .refundedAmount(refundedAmount)
                .refundPercent(refundPercent)
                .note(booking.getNote())
                .cancelReason(booking.getCancelReason())
                .expiredAt(booking.getExpiredAt())
                .createdAt(booking.getBookingDate())
                .build();
    }

    private BigDecimal calculatePaidAmount(Integer bookingId) {
        List<Payment> successPayments = paymentRepository.findSuccessPaymentsByBookingId(bookingId);
        return successPayments.isEmpty()
                ? null
                : successPayments.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculateRefundedAmount(Integer bookingId) {
        return paymentRepository.findRefundPaymentByBookingId(bookingId).stream()
                .filter(p -> p.getPaymentStatus() == TransactionStatus.SUCCESS)
                .map(p -> p.getAmount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private LocalTime[] getEffectiveSlotTime(TimeSlot slot, LocalDate reservationDate) {
        Optional<TimeSlotException> exceptionOpt = timeSlotExceptionRepository
                .findBySlotSlotIdAndExceptionDate(slot.getSlotId(), reservationDate);
        LocalTime startTime = exceptionOpt
                .filter(e -> e.getStartTimeOverride() != null)
                .map(TimeSlotException::getStartTimeOverride)
                .orElse(slot.getStartTime());
        LocalTime endTime = exceptionOpt
                .filter(e -> e.getEndTimeOverride() != null)
                .map(TimeSlotException::getEndTimeOverride)
                .orElse(slot.getEndTime());
        return new LocalTime[]{startTime, endTime};
    }

    private List<com.sportvenue.dto.booking.BookingDetailResponse.AccessoryInfo> mapBookingAccessories(Integer bookingId) {
        List<com.sportvenue.dto.booking.BookingDetailResponse.AccessoryInfo> accessoriesDto = new java.util.ArrayList<>();
        try {
            List<BookingAccessory> bookingAccessories = bookingAccessoryRepository.findByBookingBookingId(bookingId);
            if (bookingAccessories != null && !bookingAccessories.isEmpty()) {
                List<Integer> accessoryIds = bookingAccessories.stream()
                        .map(BookingAccessory::getAccessoryId)
                        .distinct()
                        .collect(Collectors.toList());
                Map<Integer, String> accessoryNameMap = accessoryRepository.findAllById(accessoryIds).stream()
                        .collect(Collectors.toMap(Accessory::getAccessoryId, Accessory::getName, (a, b) -> a));

                accessoriesDto = bookingAccessories.stream()
                        .map(ba -> com.sportvenue.dto.booking.BookingDetailResponse.AccessoryInfo.builder()
                                .accessoryName(accessoryNameMap.getOrDefault(ba.getAccessoryId(), "Phụ kiện #" + ba.getAccessoryId()))
                                .quantity(ba.getQuantity())
                                .unitPrice(ba.getUnitPrice())
                                .build())
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.error("Failed to load accessories for booking {}", bookingId, e);
        }
        return accessoriesDto;
    }

    private TimeSlotResponse toTimeSlotResponse(TimeSlot slot, boolean bookedOnDate, boolean isFuture) {
        return TimeSlotResponse.builder()
                .slotId(slot.getSlotId())
                .stadiumId(slot.getStadium().getStadiumId())
                .startTime(slot.getStartTime())
                .endTime(slot.getEndTime())
                .pricePerSlot(slot.getPricePerSlot())
                .slotStatus(slot.getSlotStatus() != null ? slot.getSlotStatus().name() : null)
                .available(!bookedOnDate && isFuture)
                .build();
    }
}
