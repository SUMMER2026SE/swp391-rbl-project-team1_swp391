package com.sportvenue.entity.enums;

public enum NotificationType {
    BOOKING,
    PAYMENT,
    PROMOTION,
    SYSTEM,
    REVIEW,
    COMPLAINT,
    OWNER_APPROVAL,
    STADIUM_APPROVAL,
    ACCOUNT_LOCK,
    APPEAL,
    REPORT,
    AI_SUGGESTION,

    // CUSTOMER TYPES
    BOOKING_CONFIRMED,           // Đặt sân thành công
    BOOKING_CANCELLED,           // Đơn bị hủy
    PAYMENT_RECEIVED,            // Thanh toán thành công
    PAYMENT_FAILED,              // Thanh toán thất bại
    REFUND_PROCESSED,            // Hoàn tiền hoàn tất
    REFUND_EXCEPTION_DECISION,   // Phần quyết yêu cầu ngoại lệ hoàn tiền
    
    COMPLAINT_ACKNOWLEDGED,      // Khiếu nại được ghi nhận
    COMPLAINT_OWNER_REPLIED,     // Owner phản hồi khiếu nại
    COMPLAINT_RESOLVED,          // Khiếu nại được giải quyết
    COMPLAINT_ESCALATED,         // Khiếu nại được escalate lên Admin
    
    REVIEW_REMINDER,             // Nhắc đánh giá sân
    REVIEW_OWNER_RESPONDED,      // Owner phản hồi đánh giá
    
    MATCH_REQUEST_RECEIVED,      // Nhận yêu cầu tham gia kèo
    MATCH_REQUEST_APPROVED,      // Yêu cầu được chấp nhận
    MATCH_REQUEST_REJECTED,      // Yêu cầu bị từ chối
    MATCH_CANCELLED,             // Kèo ghép bị hủy
    
    UPGRADE_APPROVED,            // Yêu cầu nâng cấp Owner được phê duyệt
    UPGRADE_REJECTED,            // Yêu cầu nâng cấp bị từ chối
    
    ACCOUNT_LOCKED,              // Tài khoản bị khóa
    ACCOUNT_UNLOCKED             // Tài khoản được mở khóa
}
