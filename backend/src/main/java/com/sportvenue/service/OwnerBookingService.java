package com.sportvenue.service;

import com.sportvenue.dto.request.BookingActionRequest;
import com.sportvenue.dto.response.BookingResponse;
import com.sportvenue.entity.Booking;
import com.sportvenue.entity.Owner;
import com.sportvenue.entity.Stadium;
import com.sportvenue.entity.TimeSlot;
import com.sportvenue.entity.User;
import com.sportvenue.entity.enums.BookingStatus;
import com.sportvenue.entity.enums.SlotStatus;
import com.sportvenue.exception.BadRequestException;
import com.sportvenue.exception.ResourceNotFoundException;
import com.sportvenue.entity.Payment;
import com.sportvenue.entity.enums.PaymentMethod;
import com.sportvenue.entity.enums.PaymentStatus;
import com.sportvenue.entity.enums.TransactionStatus;
import com.sportvenue.dto.request.CreateWalkInBookingRequest;
import com.sportvenue.repository.BookingRepository;
import com.sportvenue.repository.PaymentRepository;
import com.sportvenue.repository.OwnerRepository;
import com.sportvenue.repository.StadiumRepository;
import com.sportvenue.repository.TimeSlotRepository;
import com.sportvenue.repository.AccessoryRepository;
import com.sportvenue.repository.BookingAccessoryRepository;
import com.sportvenue.entity.Accessory;
import com.sportvenue.entity.BookingAccessory;
import com.sportvenue.dto.request.AccessoryItem;
import com.sportvenue.util.StadiumUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.math.BigDecimal;
import com.sportvenue.repository.TimeSlotExceptionRepository;
import com.sportvenue.entity.TimeSlotException;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.sportvenue.dto.response.WeeklySlotResponse;


/**
 * Service xử lý nghiệp vụ quản lý đặt sân cho Owner.
 * Bao gồm: xem danh sách, xác nhận, từ chối booking.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OwnerBookingService {

    private final BookingRepository bookingRepository;
    private final OwnerRepository ownerRepository;
    private final StadiumRepository stadiumRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final TimeSlotExceptionRepository timeSlotExceptionRepository;
    private final BookingService bookingService;
    private final PaymentRepository paymentRepository;
    private final AccessoryRepository accessoryRepository;
    private final BookingAccessoryRepository bookingAccessoryRepository;

    @Transactional(readOnly = true)
    public WeeklySlotResponse getOwnerWeeklySlots(Integer userId, Integer stadiumId, LocalDate weekStart) {
        Owner owner = findOwnerByUserId(userId);
        validateStadiumOwnership(stadiumId, owner.getOwnerId());
        WeeklySlotResponse response = bookingService.getWeeklySlots(stadiumId, weekStart);
        LocalDate monday = LocalDate.parse(response.getWeekStart());
        List<Booking> bookings = bookingRepository.findWeeklyBookings(stadiumId, monday, monday.plusDays(6),
                List.of(BookingStatus.PENDING_PAYMENT, BookingStatus.PENDING, BookingStatus.CONFIRMED));
        Map<String, Booking> byDateAndSlot = bookings.stream().collect(Collectors.toMap(
                booking -> booking.getReservationDate() + ":" + booking.getSlot().getSlotId(),
                Function.identity(), (first, ignored) -> first));
        response.getDays().forEach(day -> day.getSlots().forEach(slot -> {
            Booking booking = byDateAndSlot.get(day.getDate() + ":" + slot.getSlotId());
            if (booking != null) {
                slot.setBookingId(booking.getBookingId());
                slot.setIsWalkIn(booking.getIsWalkIn());
                // Walk-in bookings have user = null
                if (booking.getUser() != null) {
                    slot.setCustomerId(booking.getUser().getUserId());
                    slot.setCustomerDisplayName(abbreviateCustomerName(booking.getUser()));
                } else {
                    slot.setCustomerDisplayName("Khách vãng lai");
                }
            }
        }));
        return response;
    }

    private String abbreviateCustomerName(User user) {
        if (user == null) {
            return "Khách hàng";
        }
        String firstName = user.getFirstName();
        String lastName = user.getLastName();
        if (firstName == null || firstName.isBlank()) {
            return "Khách hàng";
        }
        if (lastName == null || lastName.isBlank()) {
            return firstName.trim();
        }
        String familyName = lastName.trim().split("\\s+")[0];
        return firstName.trim() + " " + Character.toUpperCase(familyName.charAt(0)) + ".";
    }

    /**
     * UC-OWN-06: Lấy danh sách booking của tất cả sân thuộc Owner.
     *
     * @param userId ID của user đăng nhập (phải có role Owner)
     * @param stadiumId (optional) filter theo sân cụ thể
     * @param status (optional) filter theo trạng thái booking
     * @param pageable phân trang
     * @return trang booking responses
     */
    @Transactional(readOnly = true)
    public Page<BookingResponse> getOwnerBookings(
            Integer userId, Integer stadiumId,
            BookingStatus status, Pageable pageable) {

        Owner owner = findOwnerByUserId(userId);

        // Nếu filter theo sân cụ thể — kiểm tra quyền sở hữu
        if (stadiumId != null) {
            validateStadiumOwnership(stadiumId, owner.getOwnerId());

            if (status != null) {
                return bookingRepository
                        .findByStadiumStadiumIdAndBookingStatus(stadiumId, status, pageable)
                        .map(this::toBookingResponse);
            }
            return bookingRepository
                    .findByStadiumStadiumIdOrderByBookingDateDesc(stadiumId, pageable)
                    .map(this::toBookingResponse);
        }

        // Lấy tất cả sân của owner rồi query booking (đã fix N+1)
        return bookingRepository
                .findByOwnerIdAndStatus(owner.getOwnerId(), status, pageable)
                .map(this::toBookingResponse);
    }

    /**
     * UC-OWN-07: Xác nhận hoặc từ chối đơn đặt sân.
     *
     * @param userId ID của Owner
     * @param bookingId ID của booking cần xử lý
     * @param request action (CONFIRM/REJECT) và lý do
     * @return booking response sau khi cập nhật
     */
    @Transactional
    public BookingResponse processBooking(
            Integer userId, Integer bookingId,
            BookingActionRequest request) {

        Owner owner = findOwnerByUserId(userId);
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy đơn đặt sân: " + bookingId));

        // Kiểm tra quyền sở hữu sân
        validateStadiumOwnership(
                booking.getStadium().getStadiumId(),
                owner.getOwnerId());

        // Chỉ xử lý booking đang Pending
        if (booking.getBookingStatus() != BookingStatus.PENDING) {
            throw new BadRequestException(
                    "Chỉ có thể xác nhận/từ chối đơn đang ở trạng thái Chờ xác nhận. "
                    + "Trạng thái hiện tại: " + booking.getBookingStatus());
        }

        if (request.getAction() == BookingActionRequest.Action.CONFIRM) {
            return confirmBooking(booking);
        } else {
            return rejectBooking(booking, request.getReason());
        }
    }

    private BookingResponse confirmBooking(Booking booking) {
        TimeSlot slot = booking.getSlot();
        if (slot.getSlotStatus() != SlotStatus.AVAILABLE) {
            throw new BadRequestException("Khung giờ này đã được đặt bởi một khách hàng khác.");
        }

        booking.setBookingStatus(BookingStatus.CONFIRMED);

        // Cập nhật slot thành Booked (Tự động flush nhờ Dirty Checking của JPA)
        slot.setSlotStatus(SlotStatus.BOOKED);

        log.info("✅ Booking #{} đã được xác nhận", booking.getBookingId());
        return toBookingResponse(booking);
    }

    private BookingResponse rejectBooking(Booking booking, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(
                    "Vui lòng nhập lý do khi từ chối đơn đặt sân.");
        }

        // Design decision: Sử dụng CANCELLED thay vì tạo status riêng (REJECTED) cho Owner từ chối.
        // PO quyết định gộp chung để đơn giản hóa flow, lý do từ chối sẽ được ghi vào note.
        booking.setBookingStatus(BookingStatus.CANCELLED);
        booking.setNote("Lý do từ chối: " + reason);
        booking.setCancelReason(reason);

        // Chỉ giải phóng slot cho người khác đặt khi không có đơn CONFIRMED khác đang chiếm giữ slot này
        TimeSlot slot = booking.getSlot();
        if (slot.getSlotStatus() != SlotStatus.BOOKED) {
            slot.setSlotStatus(SlotStatus.AVAILABLE);
        }

        log.info("❌ Booking #{} đã bị từ chối. Lý do: {}",
                booking.getBookingId(), reason);
        return toBookingResponse(booking);
    }

    /**
     * Tạo đơn đặt sân trực tiếp tại quầy.
     */
    @Transactional
    public BookingResponse createWalkInBooking(Integer ownerUserId, CreateWalkInBookingRequest request) {
        Owner owner = findOwnerByUserId(ownerUserId);
        
        Stadium stadium = stadiumRepository.findById(request.getStadiumId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sân: " + request.getStadiumId()));
        
        validateStadiumOwnership(stadium.getStadiumId(), owner.getOwnerId());
        
        TimeSlot slot = timeSlotRepository.findByIdForUpdate(request.getSlotId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy khung giờ: " + request.getSlotId()));
        
        if (slot.getSlotStatus() != SlotStatus.AVAILABLE) {
            throw new BadRequestException("Khung giờ này không khả dụng.");
        }
        
        // Check conflict
        boolean hasConflict = bookingRepository.existsActiveBooking(
                stadium.getStadiumId(), slot.getSlotId(), request.getReservationDate(), 
                List.of(BookingStatus.PENDING_PAYMENT, BookingStatus.PENDING, BookingStatus.CONFIRMED));
        
        if (hasConflict) {
            throw new BadRequestException("Khung giờ này đã được đặt.");
        }

        List<BookingAccessory> bookingAccessories = new ArrayList<>();
        BigDecimal extraPrice = processWalkInAccessories(request.getAccessories(), bookingAccessories);
        BigDecimal totalPrice = slot.getPricePerSlot().add(extraPrice);

        Booking booking = Booking.builder()
                .user(null) // Khách vãng lai
                .stadium(stadium)
                .slot(slot)
                .totalPrice(totalPrice)
                .serviceFee(BigDecimal.ZERO)
                .bookingStatus(BookingStatus.CONFIRMED)
                .paymentStatus(PaymentStatus.PAID)
                .reservationDate(request.getReservationDate())
                .isWalkIn(true)
                .build();
        
        Booking saved = bookingRepository.save(booking);
        
        if (!bookingAccessories.isEmpty()) {
            for (BookingAccessory ba : bookingAccessories) {
                ba.setBooking(saved);
            }
            bookingAccessoryRepository.saveAll(bookingAccessories);
        }
        
        createWalkInPayment(saved, totalPrice);
        
        log.info("🛒 Đã tạo Walk-in Booking #{} cho sân {} kèm {} phụ kiện", saved.getBookingId(), stadium.getStadiumId(), bookingAccessories.size());
        
        return toBookingResponse(saved);
    }

    private BigDecimal processWalkInAccessories(List<AccessoryItem> items, List<BookingAccessory> bookingAccessories) {
        BigDecimal extraPrice = BigDecimal.ZERO;
        if (items != null && !items.isEmpty()) {
            for (AccessoryItem item : items) {
                Accessory acc = accessoryRepository.findById(item.getAccessoryId())
                        .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phụ kiện với ID " + item.getAccessoryId()));
                
                if (!Boolean.TRUE.equals(acc.getIsAvailable())) {
                    throw new BadRequestException("Phụ kiện #" + acc.getAccessoryId() + " hiện không khả dụng");
                }
                
                BigDecimal lineTotal = acc.getPricePerUnit().multiply(BigDecimal.valueOf(item.getQuantity()));
                extraPrice = extraPrice.add(lineTotal);
                
                bookingAccessories.add(BookingAccessory.builder()
                        .accessoryId(acc.getAccessoryId())
                        .quantity(item.getQuantity())
                        .unitPrice(acc.getPricePerUnit())
                        .build());
            }
        }
        return extraPrice;
    }

    private void createWalkInPayment(Booking saved, BigDecimal totalPrice) {
        Payment payment = Payment.builder()
                .booking(saved)
                .paymentMethod(PaymentMethod.CASH)
                .amount(totalPrice)
                .transactionCode("WALK_IN_" + saved.getBookingId())
                .paymentStatus(TransactionStatus.SUCCESS)
                .paidAt(LocalDateTime.now())
                .build();
        paymentRepository.save(payment);
    }
    
    // ── Helper methods ────────────────────────────────────────────

    private Owner findOwnerByUserId(Integer userId) {
        return ownerRepository.findByUserUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy hồ sơ chủ sân cho user: " + userId));
    }

    private void validateStadiumOwnership(
            Integer stadiumId, Integer ownerId) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy sân: " + stadiumId));
        Owner resolvedOwner = stadium.resolveOwner();
        if (resolvedOwner == null || !resolvedOwner.getOwnerId().equals(ownerId)) {
            throw new BadRequestException(
                    "Bạn không có quyền quản lý sân này.");
        }
    }

    private BookingResponse toBookingResponse(Booking booking) {
        User customer = booking.getUser();
        Stadium stadium = booking.getStadium();
        TimeSlot slot = booking.getSlot();

        BookingResponse.CustomerInfo customerInfo = null;
        if (customer != null) {
            customerInfo = BookingResponse.CustomerInfo.builder()
                    .userId(customer.getUserId())
                    .fullName(customer.getLastName() + " " + customer.getFirstName())
                    .email(maskEmail(customer.getEmail()))
                    .phoneNumber(maskPhone(customer.getPhoneNumber()))
                    .avatarUrl(customer.getAvatarUrl())
                    .build();
        } else {
            customerInfo = BookingResponse.CustomerInfo.builder()
                    .fullName("Khách vãng lai")
                    .build();
        }

        return BookingResponse.builder()
                .bookingId(booking.getBookingId())
                .customer(customerInfo)
                .stadium(BookingResponse.StadiumInfo.builder()
                        .stadiumId(stadium.getStadiumId())
                        .stadiumName(stadium.getStadiumName())
                        .complexId(StadiumUtils.resolveComplexId(stadium))
                        .complexName(StadiumUtils.resolveComplexName(stadium))
                        .facilityId(StadiumUtils.resolveFacilityId(stadium))
                        .facilityName(StadiumUtils.resolveFacilityName(stadium))
                        .address(StadiumUtils.resolveAddress(stadium))
                        .sportType(StadiumUtils.resolveSportName(stadium))
                        .build())
                .slot(BookingResponse.SlotInfo.builder()
                        .slotId(slot.getSlotId())
                        .startTime(LocalDateTime.of(booking.getReservationDate(),
                                timeSlotExceptionRepository.findBySlotSlotIdAndExceptionDate(slot.getSlotId(), booking.getReservationDate())
                                        .filter(e -> e.getStartTimeOverride() != null)
                                        .map(TimeSlotException::getStartTimeOverride)
                                        .orElse(slot.getStartTime())))
                        .endTime(LocalDateTime.of(booking.getReservationDate(),
                                timeSlotExceptionRepository.findBySlotSlotIdAndExceptionDate(slot.getSlotId(), booking.getReservationDate())
                                        .filter(e -> e.getEndTimeOverride() != null)
                                        .map(TimeSlotException::getEndTimeOverride)
                                        .orElse(slot.getEndTime())))
                        .build())
                .totalPrice(booking.getTotalPrice())
                .bookingStatus(booking.getBookingStatus().name())
                .paymentStatus(booking.getPaymentStatus().name())
                .note(booking.getNote())
                .bookingDate(booking.getBookingDate())
                .recurringGroupId(booking.getRecurringGroupId())
                .isWalkIn(booking.getIsWalkIn())
                .build();
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        String[] parts = email.split("@");
        if (parts.length < 2) {
            return email;
        }
        String name = parts[0];
        if (name.length() > 3) {
            name = name.substring(0, 3) + "***";
        } else {
            name = "***";
        }
        return name + "@" + parts[1];
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, phone.length() - 4) + "****";
    }

    /**
     * Void (hủy) một đơn walk-in tạo nhầm.
     * Chỉ áp dụng cho walk-in booking đang CONFIRMED — giải phóng slot về AVAILABLE.
     * Owner phải sở hữu sân mới được thao tác.
     */
    @Transactional
    public BookingResponse voidWalkInBooking(Integer ownerUserId, Integer bookingId) {
        Owner owner = findOwnerByUserId(ownerUserId);
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn walk-in: " + bookingId));

        if (!Boolean.TRUE.equals(booking.getIsWalkIn())) {
            throw new BadRequestException("Chỉ có thể hủy đơn vãng lai (walk-in).");
        }

        if (booking.getBookingStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("Chỉ có thể hủy đơn walk-in đang ở trạng thái CONFIRMED.");
        }

        validateStadiumOwnership(booking.getStadium().getStadiumId(), owner.getOwnerId());

        booking.setBookingStatus(BookingStatus.CANCELLED);
        booking.setNote("Đơn vãng lai bị hủy bởi chủ sân (nhập nhầm).");

        // Giải phóng khung giờ để có thể đặt lại
        TimeSlot slot = booking.getSlot();
        if (slot != null) {
            slot.setSlotStatus(SlotStatus.AVAILABLE);
        }

        log.info("🗑️ Walk-in Booking #{} bị void bởi owner {}", bookingId, ownerUserId);
        return toBookingResponse(booking);
    }
}
