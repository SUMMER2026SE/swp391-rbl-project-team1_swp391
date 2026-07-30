package com.sportvenue.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO trả về thông tin đặt sân cho Owner.
 * Bao gồm thông tin khách hàng, sân, khung giờ và trạng thái.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingResponse {

    private Integer bookingId;

    /** Thông tin khách hàng đặt sân. */
    private CustomerInfo customer;

    /** Thông tin sân được đặt. */
    private StadiumInfo stadium;

    /** Thông tin khung giờ. */
    private SlotInfo slot;

    private BigDecimal totalPrice;
    private String bookingStatus;
    private String paymentStatus;
    private String note;
    private LocalDateTime bookingDate;

    /**
     * UC-CUS-01: Mã chuỗi đặt sân định kỳ — NULL nếu là đơn đơn lẻ.
     * Owner dashboard dùng để gom nhóm các đơn thuộc cùng chuỗi.
     */
    private String recurringGroupId;

    /** Đánh dấu đơn đặt tại quầy. */
    private Boolean isWalkIn;

    /** Thông tin khách hàng (nested DTO). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CustomerInfo {
        private Integer userId;
        private String fullName;
        private String email;
        private String phoneNumber;
        private String avatarUrl;
    }

    /** Thông tin sân (nested DTO). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StadiumInfo {
        private Integer stadiumId;
        private String stadiumName;
        private Integer complexId;
        private String complexName;
        private Integer facilityId;
        private String facilityName;
        private String address;
        private String sportType;
    }

    /** Thông tin khung giờ (nested DTO). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SlotInfo {
        private Integer slotId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }
}
