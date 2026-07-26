package com.sportvenue.service.ai.suggestion;

import com.sportvenue.entity.Booking;
import com.sportvenue.entity.MatchRequest;
import com.sportvenue.entity.SportType;
import com.sportvenue.entity.Stadium;
import com.sportvenue.entity.TimeSlot;
import com.sportvenue.entity.User;
import com.sportvenue.entity.UserPreference;
import com.sportvenue.entity.enums.MatchStatus;
import com.sportvenue.repository.BookingRepository;
import com.sportvenue.repository.MatchRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleBasedSuggestionServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private MatchRequestRepository matchRequestRepository;

    @InjectMocks
    private RuleBasedSuggestionService service;

    @Test
    void testUpcomingBookingReminder() {
        UserPreference pref = UserPreference.builder().userId(1).build();

        Booking upcomingBooking = new Booking();
        upcomingBooking.setReservationDate(LocalDate.now().plusDays(1)); // Tomorrow
        Stadium stadium = new Stadium();
        stadium.setStadiumName("Sân Chảo Lửa");
        upcomingBooking.setStadium(stadium);
        TimeSlot slot = new TimeSlot();
        slot.setStartTime(LocalTime.of(18, 0));
        upcomingBooking.setSlot(slot);

        when(bookingRepository.findUpcomingByUserId(eq(1), any(LocalDateTime.class), any()))
                .thenReturn(List.of(upcomingBooking));

        Optional<AiSuggestion> suggestion = service.generateSuggestion(pref);
        assertTrue(suggestion.isPresent());
        assertEquals("BOOKING_REMINDER", suggestion.get().getSuggestionType());
        assertEquals(1.0, suggestion.get().getConfidenceScore());
        assertTrue(suggestion.get().getMessage().contains("Sân Chảo Lửa"));
        assertTrue(suggestion.get().getMessage().contains("18:00"));
    }

    @Test
    void testMatchAvailabilityChecker() {
        SportType football = new SportType();
        football.setSportTypeId(1);
        football.setSportName("Bóng đá");

        UserPreference pref = UserPreference.builder()
                .userId(2)
                .sportType(football)
                .build();

        when(bookingRepository.findUpcomingByUserId(eq(2), any(LocalDateTime.class), any()))
                .thenReturn(Collections.emptyList());

        MatchRequest match = new MatchRequest();
        match.setSportType(football);
        User owner = new User();
        owner.setUserId(99); // different user
        match.setUser(owner);
        match.setMaxPlayers(10);
        match.setCurrentPlayers(8);
        match.setPlayDate(LocalDate.now().plusDays(2));
        match.setStartTime(LocalTime.of(19, 0));

        when(matchRequestRepository.findAllByPlayDateGreaterThanEqualAndMatchStatus(any(LocalDate.class), eq(MatchStatus.OPEN)))
                .thenReturn(List.of(match));

        Optional<AiSuggestion> suggestion = service.generateSuggestion(pref);
        assertTrue(suggestion.isPresent());
        assertEquals("MATCH_INVITE", suggestion.get().getSuggestionType());
        assertEquals(0.8, suggestion.get().getConfidenceScore());
        assertTrue(suggestion.get().getMessage().contains("Bóng đá"));
    }

    @Test
    void testBookingPatternAnalyzer() {
        UserPreference pref = UserPreference.builder()
                .userId(3)
                .preferredDistrict("Thủ Đức")
                .favoriteSport("Bóng đá")
                .preferredWeekday((short) 1) // Sunday
                .preferredTimeStart(LocalTime.of(17, 0))
                .build();

        when(bookingRepository.findUpcomingByUserId(eq(3), any(LocalDateTime.class), any()))
                .thenReturn(Collections.emptyList());

        Optional<AiSuggestion> suggestion = service.generateSuggestion(pref);
        assertTrue(suggestion.isPresent());
        assertEquals("PATTERN_MATCH", suggestion.get().getSuggestionType());
        assertEquals(0.7, suggestion.get().getConfidenceScore());
        assertTrue(suggestion.get().getMessage().contains("Thủ Đức"));
        assertTrue(suggestion.get().getMessage().contains("Thứ Chủ nhật"));
    }
}
