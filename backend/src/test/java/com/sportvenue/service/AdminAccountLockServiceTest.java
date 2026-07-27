package com.sportvenue.service;

import com.sportvenue.entity.User;
import com.sportvenue.entity.enums.AccountStatus;
import com.sportvenue.entity.enums.NotificationType;
import com.sportvenue.exception.BadRequestException;
import com.sportvenue.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminAccountLockServiceTest {

    @Spy
    private AccountStatusService accountStatusService;

    @Mock
    private AccountStatusHistoryService accountStatusHistoryService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private EmailService emailService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private AdminAccountLockService adminAccountLockService;

    @Test
    void applyLockState_Lock_RecordsHistoryAndNotifiesUser() {
        User user = User.builder()
                .userId(2)
                .email("customer@example.com")
                .firstName("Nguyen")
                .lastName("An")
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        AccountStatus newStatus = adminAccountLockService.applyLockState(user, false, 1, "Violation");

        assertEquals(AccountStatus.BLOCKED, newStatus);
        assertEquals(AccountStatus.BLOCKED, user.getAccountStatus());
        assertEquals("Violation", user.getLockReason());
        verify(accountStatusHistoryService).recordStatusChange(
                user, 1, AccountStatus.ACTIVE, AccountStatus.BLOCKED, "Violation");
        verify(userRepository).save(user);
        verify(notificationService).createNotification(
                eq(2),
                eq("Tài khoản đã bị khóa"),
                contains("Violation"),
                eq(NotificationType.ACCOUNT_LOCK),
                eq("USER-2"));
        verify(emailService).sendUserAccountLockStatusEmail(eq("customer@example.com"), eq("Nguyen An"), eq(false), eq("Violation"));
    }

    @Test
    void applyLockState_Unlock_ClearsReasonAndDoesNotNotify() {
        User user = User.builder()
                .userId(2)
                .email("customer@example.com")
                .firstName("Nguyen")
                .lastName("An")
                .accountStatus(AccountStatus.BLOCKED)
                .lockReason("Violation")
                .build();

        AccountStatus newStatus = adminAccountLockService.applyLockState(user, true, 1, null);

        assertEquals(AccountStatus.ACTIVE, newStatus);
        assertEquals(AccountStatus.ACTIVE, user.getAccountStatus());
        assertNull(user.getLockReason());
        verify(accountStatusHistoryService).recordStatusChange(
                user, 1, AccountStatus.BLOCKED, AccountStatus.ACTIVE, null);
        verify(userRepository).save(user);
        verify(notificationService, never()).createNotification(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(emailService).sendUserAccountLockStatusEmail(eq("customer@example.com"), eq("Nguyen An"), eq(true), eq(null));
    }

    @Test
    void applyLockState_PendingAccount_ThrowsAndDoesNotWrite() {
        User user = User.builder()
                .userId(2)
                .accountStatus(AccountStatus.PENDING)
                .build();

        assertThrows(BadRequestException.class, () ->
                adminAccountLockService.applyLockState(user, true, 1, null));

        assertEquals(AccountStatus.PENDING, user.getAccountStatus());
        verify(accountStatusHistoryService, never()).recordStatusChange(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
