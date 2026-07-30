package com.sportvenue.dto.booking;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * UC-CUS-01: Response trả về sau khi tạo booking đơn lẻ — chứa đầy đủ
 * thông tin FE cần để hiển thị ngay trong tab "Lịch sử đặt sân" (không cần
 * gọi thêm API chi tiết).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingDetailResponse {

    @Schema(description = "ID đơn đặt sân vừa tạo", example = "42")
    private Integer bookingId;

    @Schema(description = "Mã hiển thị (BK + 6 chữ số)", example = "BK000042")
    private String displayId;

    @Schema(description = "Ngày khách ra sân chơi")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate reservationDate;

    @Schema(description = "Thông tin khung giờ đã đặt")
    private SlotInfo slot;

    @Schema(description = "Thông tin sân đã đặt")
    private StadiumInfo stadium;

    @Schema(description = "Tổng tiền (đã gồm phí dịch vụ)", example = "500000.00")
    private BigDecimal totalPrice;

    @Schema(description = "Phí dịch vụ nền tảng đã gồm trong totalPrice", example = "20000.00")
    private BigDecimal serviceFee;

    @Schema(description = "Danh sách phụ kiện đặt kèm")
    private List<AccessoryInfo> accessories;

    @Schema(description = "Số tiền THỰC TẾ đã thanh toán qua cổng — bằng totalPrice nếu trả đủ, "
            + "chỉ bằng 30% totalPrice nếu đặt cọc. Null nếu chưa thanh toán.", example = "519750.00")
    private BigDecimal paidAmount;

    @Schema(description = "Trạng thái đơn — sau createBooking luôn là PENDING", example = "pending")
    private String status;

    @Schema(description = "Trạng thái thanh toán — sau createBooking luôn là UNPAID", example = "unpaid")
    private String paymentStatus;

    @Schema(description = "Số tiền thực tế đã hoàn — chỉ có giá trị khi paymentStatus=REFUNDED", example = "0.00")
    private BigDecimal refundedAmount;

    @Schema(description = "Tỷ lệ % hoàn tương ứng refundedAmount/originalPayment — null nếu chưa hoàn", example = "0")
    private Integer refundPercent;

    @Schema(description = "Ghi chú của khách (nếu có)")
    private String note;

    @Schema(description = "Lý do hủy đơn — NULL nếu đơn bị hệ thống tự hủy do hết hạn giữ chỗ "
            + "(BookingExpiryScheduler không set field này), có giá trị nếu khách/chủ sân chủ động hủy.",
            example = "Khách đổi lịch")
    private String cancelReason;

    @Schema(description = "Thời điểm tạo đơn đặt")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * UC-CUS-01: Thời điểm hết hạn giữ sân (ISO-8601) — FE dùng để chạy countdown.
     * NULL khi booking đã CONFIRMED / CANCELLED.
     */
    @Schema(description = "Thời điểm hết hạn giữ sân (ISO-8601). FE dùng để countdown.", example = "2026-06-25T10:35:00")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiredAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlotInfo {
        private Integer slotId;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
        private LocalTime startTime;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
        private LocalTime endTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StadiumInfo {
        private Integer stadiumId;
        private Integer ownerUserId;
        private String stadiumName;
        private Integer complexId;
        private String complexName;
        private Integer facilityId;
        private String facilityName;
        private String address;
        private String sportType;
        private String imageUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccessoryInfo {
        private String accessoryName;
        private Integer quantity;
        private BigDecimal unitPrice;
    }
}
