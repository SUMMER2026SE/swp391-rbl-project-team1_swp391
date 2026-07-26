package com.sportvenue.service.ai;

import com.sportvenue.entity.UserPreference;
import com.sportvenue.service.UserPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PersonalizationPromptBuilder {
    private final UserPreferenceService userPreferenceService;

    public String buildSupplement(Integer userId) {
        if (userId == null) {
            return "";
        }

        Optional<UserPreference> prefOpt = userPreferenceService.getForUser(userId);
        if (prefOpt.isEmpty()) {
            return "";
        }

        UserPreference pref = prefOpt.get();
        int totalBookings = pref.getTotalBookings() != null ? pref.getTotalBookings() : 0;

        if (totalBookings == 0) {
            return ""; // 0 bookings: No personalization section
        }

        // Check if dormant (> 60 days since last booking)
        if (pref.getLastBookingDate() != null) {
            long daysSinceLastBooking = ChronoUnit.DAYS.between(pref.getLastBookingDate(), LocalDate.now());
            if (daysSinceLastBooking > 60) {
                return "Chào bạn, đã lâu chưa gặp bạn.\n";
            }
        }

        BigDecimal confidence = pref.getConfidenceScore() != null ? pref.getConfidenceScore() : BigDecimal.ZERO;
        StringBuilder sb = new StringBuilder();
        
        sb.append("<!-- PERSONALIZATION (computed_at=").append(pref.getComputedAt()).append(") -->\n");
        sb.append("Bạn có những thông tin cá nhân hóa sau, hãy dựa vào đây để gợi ý sân phù hợp:\n");

        if (totalBookings >= 1 && totalBookings <= 2 || confidence.compareTo(new BigDecimal("0.3")) < 0) {
            // Partial profile (favorite sport only) -> short supplement
            if (pref.getFavoriteSport() != null) {
                sb.append("- Môn bạn hay chơi nhất: ").append(pref.getFavoriteSport()).append("\n");
            }
        } else {
            // Full profile
            if (pref.getFavoriteSport() != null) {
                sb.append("- Môn bạn hay chơi nhất: ").append(pref.getFavoriteSport()).append("\n");
            }
            if (pref.getPreferredDistrict() != null && pref.getPreferredProvince() != null) {
                sb.append("- Khu vực bạn thường đặt: ").append(pref.getPreferredDistrict()).append(", ").append(pref.getPreferredProvince()).append("\n");
            }
            if (pref.getPreferredTimeStart() != null || pref.getPreferredTimeEnd() != null) {
                sb.append("- Giờ bạn hay chơi: ");
                if (pref.getPreferredTimeStart() != null) {
                    sb.append(pref.getPreferredTimeStart());
                }
                sb.append(" - ");
                if (pref.getPreferredTimeEnd() != null) {
                    sb.append(pref.getPreferredTimeEnd());
                }
                sb.append("\n");
            }
            if (pref.getAvgPricePerBooking() != null) {
                java.text.NumberFormat format = java.text.NumberFormat.getNumberInstance(java.util.Locale.of("vi", "VN"));
                sb.append("- Giá trung bình bạn hay đặt: ").append(format.format(pref.getAvgPricePerBooking())).append("đ\n");
            }
            if (pref.getTotalCompletedMinutes() != null && pref.getTotalCompletedMinutes() > 0) {
                sb.append("- Tổng thời gian chơi: ").append(pref.getTotalCompletedMinutes() / 60).append(" giờ\n");
            }
        }
        sb.append("<!-- END PERSONALIZATION -->");
        return sb.toString();
    }
}
