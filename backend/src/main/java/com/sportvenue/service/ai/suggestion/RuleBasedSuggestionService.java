package com.sportvenue.service.ai.suggestion;

import com.sportvenue.entity.Booking;
import com.sportvenue.entity.MatchRequest;
import com.sportvenue.entity.UserPreference;
import com.sportvenue.entity.enums.MatchStatus;
import com.sportvenue.repository.BookingRepository;
import com.sportvenue.repository.MatchRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleBasedSuggestionService {

    private final BookingRepository bookingRepository;
    private final MatchRequestRepository matchRequestRepository;

    /**
     * Tries to generate a rule-based suggestion.
     * Evaluates rules in order of confidence/priority.
     * @param pref UserPreference
     * @return Optional<AiSuggestion> if a rule triggers
     */
    public Optional<AiSuggestion> generateSuggestion(UserPreference pref) {
        if (pref == null || pref.getUserId() == null) {
            return Optional.empty();
        }

        // 1. UpcomingBookingReminder (Confidence: 1.0)
        Optional<AiSuggestion> reminder = checkUpcomingBookings(pref);
        if (reminder.isPresent()) {
            return reminder;
        }

        // 2. MatchAvailabilityChecker (Confidence: 0.8)
        Optional<AiSuggestion> matchSuggestion = checkMatchAvailability(pref);
        if (matchSuggestion.isPresent()) {
            return matchSuggestion;
        }

        // 3. BookingPatternAnalyzer (Confidence: 0.7)
        Optional<AiSuggestion> patternSuggestion = checkBookingPattern(pref);
        if (patternSuggestion.isPresent()) {
            return patternSuggestion;
        }

        return Optional.empty();
    }

    private Optional<AiSuggestion> checkUpcomingBookings(UserPreference pref) {
        // Find upcoming unreminded bookings for the user. We will just check if they have any booking tomorrow.
        List<Booking> upcoming = bookingRepository.findUpcomingByUserId(pref.getUserId(), LocalDateTime.now(), org.springframework.data.domain.PageRequest.of(0, 10));
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        
        for (Booking booking : upcoming) {
            if (booking.getReservationDate() != null && booking.getReservationDate().isEqual(tomorrow)) {
                String message = String.format("Nhắc nhở: Bạn có lịch đặt sân %s vào ngày mai lúc %s. Hãy chuẩn bị sẵn sàng nhé!",
                        booking.getStadium() != null ? booking.getStadium().getStadiumName() : "",
                        booking.getSlot() != null ? booking.getSlot().getStartTime().toString() : "");
                
                return Optional.of(AiSuggestion.builder()
                        .message(message)
                        .suggestionType("BOOKING_REMINDER")
                        .confidenceScore(1.0)
                        .matchedStadiumId(booking.getStadium() != null ? booking.getStadium().getStadiumId() : null)
                        .generatedByLlm(false)
                        .build());
            }
        }
        return Optional.empty();
    }

    private Optional<AiSuggestion> checkMatchAvailability(UserPreference pref) {
        if (pref.getSportType() == null) {
            return Optional.empty();
        }

        // Check if there is an active match today or later for their favorite sport
        List<MatchRequest> activeMatches = matchRequestRepository.findAllByPlayDateGreaterThanEqualAndMatchStatus(LocalDate.now(), MatchStatus.OPEN);
        
        for (MatchRequest match : activeMatches) {
            if (match.getSportType() != null && match.getSportType().getSportTypeId().equals(pref.getSportType().getSportTypeId())
                && !match.getUser().getUserId().equals(pref.getUserId())) {
                
                // Ensure it has space
                if (match.getCurrentPlayers() != null && match.getMaxPlayers() != null && match.getCurrentPlayers() < match.getMaxPlayers()) {
                    String sportName = match.getSportType().getSportName();
                    String message = String.format("Có một kèo %s đang thiếu người vào ngày %s lúc %s. Bạn có muốn tham gia không?",
                            sportName, match.getPlayDate(), match.getStartTime());
                    
                    return Optional.of(AiSuggestion.builder()
                            .message(message)
                            .suggestionType("MATCH_INVITE")
                            .confidenceScore(0.8)
                            .matchedMatchId(match.getMatchId())
                            .matchedStadiumId(match.getComplex() != null ? match.getComplex().getComplexId() : null) // Storing complex ID as stadium id for reference
                            .generatedByLlm(false)
                            .build());
                }
            }
        }
        return Optional.empty();
    }

    private Optional<AiSuggestion> checkBookingPattern(UserPreference pref) {
        if (pref.getPreferredDistrict() == null || pref.getFavoriteSport() == null) {
            return Optional.empty();
        }

        String dayHint = "tuần này";
        if (pref.getPreferredWeekday() != null) {
            dayHint = "Thứ " + (pref.getPreferredWeekday() == 1 ? "Chủ nhật" : pref.getPreferredWeekday());
        }

        String timeHint = "";
        if (pref.getPreferredTimeStart() != null) {
            timeHint = " vào khoảng " + pref.getPreferredTimeStart();
        }

        String message = String.format("Dựa trên thói quen, bạn thường chơi %s ở khu vực %s%s. %s bạn có muốn đặt sân không?",
                pref.getFavoriteSport(), pref.getPreferredDistrict(), timeHint, dayHint);

        return Optional.of(AiSuggestion.builder()
                .message(message)
                .suggestionType("PATTERN_MATCH")
                .confidenceScore(0.7)
                .generatedByLlm(false)
                .build());
    }
}
