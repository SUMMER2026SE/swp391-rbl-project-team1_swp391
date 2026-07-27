package com.sportvenue.service;

import com.sportvenue.entity.User;
import com.sportvenue.entity.enums.AccountStatus;
import com.sportvenue.entity.enums.NotificationType;
import com.sportvenue.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminAccountLockService {

    private final AccountStatusService accountStatusService;
    private final AccountStatusHistoryService accountStatusHistoryService;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public AccountStatus applyLockState(User user, boolean enabled, Integer currentAdminId, String reason) {
        AccountStatus previousStatus = user.getAccountStatus();
        AccountStatus newStatus = accountStatusService.applyAdminLockState(user, enabled);
        user.setLockReason(enabled ? null : reason);
        accountStatusHistoryService.recordStatusChange(user, currentAdminId, previousStatus, newStatus, reason);
        userRepository.save(user);

        // Send WebSocket notification of account status change
        messagingTemplate.convertAndSend(
                "/topic/user/" + user.getUserId() + "/account-status",
                Map.of("status", newStatus.name())
        );

        if (newStatus == AccountStatus.BLOCKED) {
            notificationService.createNotification(
                    user.getUserId(),
                    "Tài khoản đã bị khóa",
                    "Tài khoản của bạn đã bị Admin khóa. Lý do: "
                            + (reason == null || reason.isBlank() ? "Không có ghi chú." : reason)
                            + " Bạn có thể gửi kháng cáo trong hệ thống.",
                    NotificationType.ACCOUNT_LOCK,
                    "USER-" + user.getUserId());
        }

        // Tự động gửi Email thông báo khóa / mở khóa tài khoản cho người dùng
        try {
            emailService.sendUserAccountLockStatusEmail(user.getEmail(), user.getFullName(), enabled, reason);
        } catch (Exception e) {
            // Log lỗi nếu email không gửi được nhưng không làm rollback giao dịch khóa tài khoản trong DB
        }

        return newStatus;
    }
}
