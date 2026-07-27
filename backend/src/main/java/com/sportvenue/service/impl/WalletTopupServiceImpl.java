package com.sportvenue.service.impl;

import com.sportvenue.config.VNPayConfig;
import com.sportvenue.dto.request.WalletTopupRequest;
import com.sportvenue.dto.response.VnpayIpnResponse;
import com.sportvenue.dto.response.WalletTopupResponse;
import com.sportvenue.entity.WalletTopup;
import com.sportvenue.entity.enums.TransactionStatus;
import com.sportvenue.entity.enums.WalletTransactionType;
import com.sportvenue.exception.BadRequestException;
import com.sportvenue.repository.UserRepository;
import com.sportvenue.repository.WalletTopupRepository;
import com.sportvenue.security.UserPrincipal;
import com.sportvenue.service.WalletService;
import com.sportvenue.service.WalletTopupService;
import com.sportvenue.util.VNPayUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * Triển khai luồng nạp tiền vào ví Customer qua VNPay — tách riêng khỏi {@link PaymentServiceImpl}
 * vì không gắn với Booking/Slot nào, nhưng tái dùng cùng cấu hình/secret VNPay và cùng cặp
 * endpoint {@code /vnpay-return}, {@code /vnpay-ipn} ở {@code PaymentReturnController}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletTopupServiceImpl implements WalletTopupService {

    private static final int EXPIRE_MINUTES = 15;
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final WalletTopupRepository walletTopupRepository;
    private final UserRepository userRepository;
    private final VNPayConfig vnPayConfig;
    private final WalletService walletService;
    private final com.sportvenue.service.EmailService emailService;

    @Override
    public boolean isTopupCallback(HttpServletRequest request) {
        String txnRef = request.getParameter("vnp_TxnRef");
        return txnRef != null && txnRef.startsWith(TXN_REF_PREFIX);
    }

    @Override
    @Transactional
    public WalletTopupResponse initiateTopup(UserPrincipal principal, WalletTopupRequest request, HttpServletRequest httpRequest) {
        if (principal == null || principal.getUserId() == null) {
            throw new AccessDeniedException("Không xác định được người dùng");
        }
        Integer userId = principal.getUserId();
        BigDecimal amount = request.getAmount();

        String txnRef = TXN_REF_PREFIX + userId + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        WalletTopup topup = WalletTopup.builder()
                .user(userRepository.getReferenceById(userId))
                .amount(amount)
                .transactionCode(txnRef)
                .status(TransactionStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        walletTopupRepository.save(topup);

        long amountVnp = amount.multiply(BigDecimal.valueOf(100)).longValueExact();

        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss");
        fmt.setTimeZone(TimeZone.getTimeZone(VN_ZONE));
        Date now = new Date();
        Date expire = new Date(now.getTime() + EXPIRE_MINUTES * 60_000L);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Version", vnPayConfig.getVersion());
        params.put("vnp_Command", vnPayConfig.getCommand());
        params.put("vnp_TmnCode", vnPayConfig.getTmnCode());
        params.put("vnp_Amount", String.valueOf(amountVnp));
        params.put("vnp_CurrCode", vnPayConfig.getCurrencyCode());
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_OrderInfo", "Nap tien vao vi #" + userId);
        params.put("vnp_OrderType", vnPayConfig.getOrderType());
        params.put("vnp_Locale", vnPayConfig.getLocale());
        params.put("vnp_ReturnUrl", vnPayConfig.getReturnUrl());
        params.put("vnp_IpAddr", VNPayUtil.resolveClientIp(httpRequest));
        params.put("vnp_CreateDate", fmt.format(now));
        params.put("vnp_ExpireDate", fmt.format(expire));

        String signed = VNPayUtil.buildSignedQuery(params, vnPayConfig.getHashSecret());
        String paymentUrl = vnPayConfig.getUrl() + signed;

        log.info("Đã tạo paymentUrl VNPay nạp ví cho user {} — txnRef={}, amount={}", userId, txnRef, amount);

        return WalletTopupResponse.builder().paymentUrl(paymentUrl).build();
    }

    @Override
    @Transactional
    public TopupReturnResult handleTopupReturn(HttpServletRequest request) {
        TopupCallbackOutcome outcome = processTopupCallback(request);
        return switch (outcome.status()) {
            case SUCCESS, ALREADY_CONFIRMED -> new TopupReturnResult(true, outcome.amount(), null);
            case PAYMENT_FAILED -> new TopupReturnResult(false, outcome.amount(), outcome.detail());
            case INVALID_SIGNATURE -> new TopupReturnResult(false, null, "invalid_hash");
            case INVALID_AMOUNT -> new TopupReturnResult(false, outcome.amount(), "invalid_amount");
            case ORDER_NOT_FOUND -> throw new BadRequestException(
                    "Không tìm thấy yêu cầu nạp ví với txnRef: " + outcome.detail());
            case UNKNOWN_ERROR -> throw new BadRequestException(outcome.detail());
        };
    }

    @Override
    @Transactional
    public VnpayIpnResponse handleTopupIpn(HttpServletRequest request) {
        TopupCallbackOutcome outcome = processTopupCallback(request);
        return switch (outcome.status()) {
            case SUCCESS, PAYMENT_FAILED ->
                    VnpayIpnResponse.builder().rspCode("00").message("Confirm Success").build();
            case ALREADY_CONFIRMED ->
                    VnpayIpnResponse.builder().rspCode("02").message("Order already confirmed").build();
            case ORDER_NOT_FOUND ->
                    VnpayIpnResponse.builder().rspCode("01").message("Order not found").build();
            case INVALID_AMOUNT ->
                    VnpayIpnResponse.builder().rspCode("04").message("Invalid amount").build();
            case INVALID_SIGNATURE ->
                    VnpayIpnResponse.builder().rspCode("97").message("Invalid signature").build();
            case UNKNOWN_ERROR ->
                    VnpayIpnResponse.builder().rspCode("99").message("Unknown error").build();
        };
    }

    /**
     * Core xử lý dùng chung cho return + IPN — mirror đúng 3 bước bắt buộc của
     * {@link PaymentServiceImpl#processVnpayCallback}: lock row → idempotent guard → verify amount,
     * trước khi credit ví. Không ném exception cho lỗi nghiệp vụ dự kiến.
     */
    private TopupCallbackOutcome processTopupCallback(HttpServletRequest request) {
        Map<String, String> vnpParams = VNPayUtil.extractVnpParams(request.getParameterMap());

        if (vnpParams.isEmpty()) {
            return new TopupCallbackOutcome(TopupCallbackStatus.UNKNOWN_ERROR, null, "Không nhận được tham số VNPay");
        }

        if (!VNPayUtil.verifyChecksum(vnpParams, vnPayConfig.getHashSecret())) {
            log.warn("VNPay callback nạp ví có checksum không hợp lệ — txnRef={}", vnpParams.get("vnp_TxnRef"));
            return new TopupCallbackOutcome(TopupCallbackStatus.INVALID_SIGNATURE, null, "invalid_hash");
        }

        String txnRef = vnpParams.get("vnp_TxnRef");
        if (txnRef == null || txnRef.isBlank()) {
            return new TopupCallbackOutcome(TopupCallbackStatus.UNKNOWN_ERROR, null, "Thiếu vnp_TxnRef trong callback");
        }

        // Lock row — tránh race giữa return-redirect và IPN cùng ghi 1 row.
        WalletTopup topup = walletTopupRepository.findByTransactionCodeForUpdate(txnRef).orElse(null);
        if (topup == null) {
            return new TopupCallbackOutcome(TopupCallbackStatus.ORDER_NOT_FOUND, null, txnRef);
        }

        // Idempotent guard — chặn cộng ví 2 lần khi return + IPN cùng tới hoặc khách reload trang.
        if (topup.getStatus() == TransactionStatus.SUCCESS) {
            return new TopupCallbackOutcome(TopupCallbackStatus.ALREADY_CONFIRMED, topup.getAmount(), null);
        }

        long expectedAmount;
        try {
            expectedAmount = topup.getAmount().multiply(BigDecimal.valueOf(100)).longValueExact();
        } catch (ArithmeticException ex) {
            expectedAmount = -1L;
        }
        long vnpAmount;
        try {
            vnpAmount = Long.parseLong(vnpParams.get("vnp_Amount"));
        } catch (NumberFormatException ex) {
            vnpAmount = -1L;
        }
        if (vnpAmount != expectedAmount) {
            log.warn("VNPay callback nạp ví amount không khớp — txnRef={}, expected={}, got={}",
                    txnRef, expectedAmount, vnpAmount);
            return new TopupCallbackOutcome(TopupCallbackStatus.INVALID_AMOUNT, topup.getAmount(), "amount_mismatch");
        }

        String responseCode = vnpParams.get("vnp_ResponseCode");
        boolean success = "00".equals(responseCode);

        if (success) {
            topup.setStatus(TransactionStatus.SUCCESS);
            topup.setPaidAt(LocalDateTime.now());
            walletTopupRepository.save(topup);
            walletService.recordCustomerTransaction(
                    topup.getUser().getUserId(), topup.getAmount(), null,
                    WalletTransactionType.CUSTOMER_TOPUP_CREDIT, "Nạp tiền vào ví");
            try {
                emailService.sendWalletTopupSuccessEmail(
                        topup.getUser().getEmail(),
                        topup.getUser().getFullName(),
                        topup.getAmount(),
                        topup.getTransactionCode(),
                        null);
            } catch (Exception e) {
                log.error("Lỗi khi gửi email nạp ví thành công", e);
            }
            log.info("VNPay nạp ví THÀNH CÔNG — txnRef={}, userId={}, amount={}",
                    txnRef, topup.getUser().getUserId(), topup.getAmount());
        } else {
            topup.setStatus(TransactionStatus.FAILED);
            walletTopupRepository.save(topup);
            log.warn("VNPay nạp ví THẤT BẠI — txnRef={}, responseCode={}", txnRef, responseCode);
        }

        return new TopupCallbackOutcome(
                success ? TopupCallbackStatus.SUCCESS : TopupCallbackStatus.PAYMENT_FAILED,
                topup.getAmount(),
                success ? null : ("response_code=" + responseCode));
    }

    private enum TopupCallbackStatus {
        SUCCESS, ALREADY_CONFIRMED, ORDER_NOT_FOUND, INVALID_AMOUNT, INVALID_SIGNATURE, PAYMENT_FAILED, UNKNOWN_ERROR
    }

    private record TopupCallbackOutcome(TopupCallbackStatus status, BigDecimal amount, String detail) {
    }
}
