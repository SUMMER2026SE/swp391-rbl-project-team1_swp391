package com.sportvenue.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class IntentValidator {

    private Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private static final List<String> VALID_INTENTS = Arrays.asList(
            "search_stadiums", "get_slots", "find_match", "create_match", "create_booking", "join_match",
            "get_policy", "need_more_info", "out_of_scope", "unknown",
            "my_bookings", "booking_status", "cancel_booking", "get_price", "recommend_time"
    );

    public record ValidationResult(boolean valid, ExtractedIntentResult overriddenResult, String validationStatus, String errorReason) { }

    public ValidationResult validate(ExtractedIntentResult intentResult) {
        String intent = intentResult.getIntent();

        if (!VALID_INTENTS.contains(intent)) {
            log.warn("Invalid intent detected: {}", intent);
            ExtractedIntentResult fallback = new ExtractedIntentResult();
            fallback.setIntent("unknown");
            fallback.setMessage("Xin lỗi, hệ thống không nhận dạng được yêu cầu của bạn.");
            fallback.setParams(intentResult.getParams());
            fallback.setConfidence(intentResult.getConfidence());
            return new ValidationResult(false, fallback, "FAIL_INVALID_INTENT", "Intent không nằm trong danh sách cho phép");
        }

        JsonNode params = intentResult.getParams();

        // Validate Date
        ValidationResult dateValidation = validateDate(params, intentResult);
        if (dateValidation != null) {
            return dateValidation;
        }
        
        // Validate targetDate (for search)
        ValidationResult targetDateValidation = validateTargetDate(params, intentResult);
        if (targetDateValidation != null) {
            return targetDateValidation;
        }

        // Validate Time
        ValidationResult timeValidation = validateTime(params, intentResult);
        if (timeValidation != null) {
            return timeValidation;
        }
        
        return new ValidationResult(true, intentResult, "PASS", null);
    }

    private ValidationResult validateDate(JsonNode params, ExtractedIntentResult intentResult) {
        if (params != null && params.hasNonNull("date")) {
            String dateStr = params.get("date").asText();
            try {
                LocalDate targetDate = LocalDate.parse(dateStr);
                LocalDate today = LocalDate.now(clock);
                if (targetDate.isBefore(today)) {
                    log.info("Detected past date: {}", dateStr);
                    ExtractedIntentResult fallback = new ExtractedIntentResult();
                    fallback.setIntent("need_more_info");
                    fallback.setMessage("Bạn không thể chọn ngày trong quá khứ. Vui lòng chọn lại ngày khác nhé.");
                    fallback.setParams(params);
                    fallback.setConfidence(intentResult.getConfidence());
                    return new ValidationResult(false, fallback, "FAIL_DATE_PAST", "Ngày truyền vào nằm trong quá khứ");
                }
            } catch (DateTimeParseException e) {
                log.warn("Invalid date format: {}", dateStr);
                ExtractedIntentResult fallback = new ExtractedIntentResult();
                fallback.setIntent("need_more_info");
                fallback.setMessage("Ngày không hợp lệ. Bạn vui lòng cung cấp lại ngày bạn muốn nhé.");
                fallback.setParams(params);
                fallback.setConfidence(intentResult.getConfidence());
                return new ValidationResult(false, fallback, "FAIL_INVALID_FORMAT", "Định dạng ngày không hợp lệ");
            }
        }
        return null;
    }

    private ValidationResult validateTargetDate(JsonNode params, ExtractedIntentResult intentResult) {
        if (params != null && params.hasNonNull("targetDate")) {
            String dateStr = params.get("targetDate").asText();
            try {
                LocalDate targetDate = LocalDate.parse(dateStr);
                LocalDate today = LocalDate.now(clock);
                if (targetDate.isBefore(today)) {
                    log.info("Detected past targetDate: {}", dateStr);
                    ExtractedIntentResult fallback = new ExtractedIntentResult();
                    fallback.setIntent("need_more_info");
                    fallback.setMessage("Bạn không thể tìm sân trong quá khứ. Vui lòng chọn ngày khác nhé.");
                    fallback.setParams(params);
                    fallback.setConfidence(intentResult.getConfidence());
                    return new ValidationResult(false, fallback, "FAIL_DATE_PAST", "Ngày truyền vào nằm trong quá khứ");
                }
            } catch (DateTimeParseException e) {
                log.warn("Invalid targetDate format: {}", dateStr);
                ExtractedIntentResult fallback = new ExtractedIntentResult();
                fallback.setIntent("need_more_info");
                fallback.setMessage("Ngày không hợp lệ. Bạn vui lòng cung cấp lại ngày bạn muốn tìm nhé.");
                fallback.setParams(params);
                fallback.setConfidence(intentResult.getConfidence());
                return new ValidationResult(false, fallback, "FAIL_INVALID_FORMAT", "Định dạng ngày không hợp lệ");
            }
        }
        return null;
    }

    private ValidationResult validateTime(JsonNode params, ExtractedIntentResult intentResult) {
        if (params != null && params.hasNonNull("startTime")) {
            String timeStr = params.get("startTime").asText();
            try {
                LocalTime.parse(timeStr);
            } catch (DateTimeParseException e) {
                log.warn("Invalid startTime format: {}", timeStr);
                ExtractedIntentResult fallback = new ExtractedIntentResult();
                fallback.setIntent("need_more_info");
                fallback.setMessage("Giờ không hợp lệ. Bạn vui lòng chọn lại thời gian cụ thể (ví dụ 14h chiều).");
                fallback.setParams(params);
                fallback.setConfidence(intentResult.getConfidence());
                return new ValidationResult(false, fallback, "FAIL_INVALID_FORMAT", "Định dạng giờ không hợp lệ");
            }
        }
        return null;
    }
}
