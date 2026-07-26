package com.sportvenue.service.ai;

import com.sportvenue.entity.UserPreference;
import com.sportvenue.service.UserPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalizationPromptBuilderTest {

    @Mock
    private UserPreferenceService userPreferenceService;

    @InjectMocks
    private PersonalizationPromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
    }

    @Test
    void test0Bookings() {
        UserPreference pref = UserPreference.builder()
                .userId(1)
                .totalBookings(0)
                .build();
        when(userPreferenceService.getForUser(1)).thenReturn(Optional.of(pref));

        String supplement = promptBuilder.buildSupplement(1);
        assertEquals("", supplement);
    }

    @Test
    void test1to2Bookings_ShortSupplement() {
        UserPreference pref = UserPreference.builder()
                .userId(2)
                .totalBookings(2)
                .confidenceScore(new BigDecimal("0.2"))
                .favoriteSport("Cầu lông")
                .computedAt(LocalDateTime.of(2026, 7, 27, 10, 0))
                .build();
        when(userPreferenceService.getForUser(2)).thenReturn(Optional.of(pref));

        String supplement = promptBuilder.buildSupplement(2);
        assertTrue(supplement.contains("<!-- PERSONALIZATION"));
        assertTrue(supplement.contains("- Môn bạn hay chơi nhất: Cầu lông"));
        assertFalse(supplement.contains("Tổng thời gian chơi"));
    }

    @Test
    void test3PlusBookings_FullSupplement() {
        UserPreference pref = UserPreference.builder()
                .userId(3)
                .totalBookings(5)
                .confidenceScore(new BigDecimal("0.8"))
                .favoriteSport("Bóng đá")
                .preferredDistrict("Quận 1")
                .preferredProvince("TP HCM")
                .preferredTimeStart(LocalTime.of(18, 0))
                .preferredTimeEnd(LocalTime.of(21, 0))
                .avgPricePerBooking(new BigDecimal("350000"))
                .totalCompletedMinutes(48 * 60)
                .lastBookingDate(LocalDate.now().minusDays(10))
                .computedAt(LocalDateTime.of(2026, 7, 27, 10, 0))
                .build();
        when(userPreferenceService.getForUser(3)).thenReturn(Optional.of(pref));

        String supplement = promptBuilder.buildSupplement(3);
        assertTrue(supplement.contains("<!-- PERSONALIZATION"));
        assertTrue(supplement.contains("- Môn bạn hay chơi nhất: Bóng đá"));
        assertTrue(supplement.contains("- Khu vực bạn thường đặt: Quận 1, TP HCM"));
        assertTrue(supplement.contains("- Giờ bạn hay chơi: 18:00 - 21:00"));
        assertTrue(supplement.contains("- Giá trung bình bạn hay đặt: 350.000đ"));
        assertTrue(supplement.contains("- Tổng thời gian chơi: 48 giờ"));
        assertTrue(supplement.contains("<!-- END PERSONALIZATION -->"));
    }

    @Test
    void testDormantUser() {
        UserPreference pref = UserPreference.builder()
                .userId(4)
                .totalBookings(10)
                .lastBookingDate(LocalDate.now().minusDays(65)) // > 60 days
                .build();
        when(userPreferenceService.getForUser(4)).thenReturn(Optional.of(pref));

        String supplement = promptBuilder.buildSupplement(4);
        assertEquals("Chào bạn, đã lâu chưa gặp bạn.\n", supplement);
    }
}
