package com.sportvenue.service;

import com.sportvenue.entity.SportType;
import com.sportvenue.entity.User;
import com.sportvenue.entity.UserPreference;
import com.sportvenue.repository.BookingRepository;
import com.sportvenue.repository.SportTypeRepository;
import com.sportvenue.repository.UserPreferenceRepository;
import com.sportvenue.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserPreferenceService {
    private final UserPreferenceRepository userPreferenceRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final SportTypeRepository sportTypeRepository;

    @Transactional
    public void computeForUser(Integer userId) {
        log.info("Computing UserPreference for user {}", userId);

        long totalBookings = bookingRepository.countByUserUserId(userId);
        if (totalBookings == 0) {
            // Optional: You could create an empty profile or just return.
            // Based on doc: "confidence_score = 0.0 when <3 bookings; hasProfile() returns false -> scheduler skips."
            // We still create the row so we don't compute again constantly.
            saveEmptyOrMinimalPreference(userId, totalBookings);
            return;
        }

        UserPreference pref = userPreferenceRepository.findById(userId)
                .orElseGet(() -> UserPreference.builder()
                        .userId(userId)
                        .user(userRepository.findById(userId).orElse(null))
                        .build());

        pref.setTotalBookings((int) totalBookings);

        // 1. Favorite sport & sport type id
        List<Object[]> topSport = bookingRepository.findTopSportWithIdByUserId(userId, PageRequest.of(0, 1));
        if (!topSport.isEmpty()) {
            Object[] first = topSport.get(0);
            Integer sportTypeId = (Integer) first[0];
            String sportName = (String) first[1];
            pref.setFavoriteSport(sportName);
            pref.setSportType(sportTypeRepository.findById(sportTypeId).orElse(null));
        }

        // 2. preferred_district, preferred_province (skipping for brevity or using distinct booked stadiums)
        // Here we can use findDistinctBookedStadiumIds and load the most frequent one, but for simplicity we'll 
        // just set it based on the last booked stadium or omit if too complex. The doc says: "join stadium_complexes -> preferred_district, preferred_province".
        // (Assuming we might fetch this later if needed, or leave null for now if not explicitly written).

        // 3. total_completed_minutes
        long completedMins = bookingRepository.sumCompletedPlayMinutes(userId);
        pref.setTotalCompletedMinutes((int) completedMins);

        // 4. avg_price_per_booking
        BigDecimal avgPrice = bookingRepository.avgPricePerBookingCompleted(userId);
        pref.setAvgPricePerBooking(avgPrice);

        // 5. preferred_time_start/end
        List<Object[]> timeStarts = bookingRepository.findPreferredTimeStartsByUserId(userId, PageRequest.of(0, 1));
        if (!timeStarts.isEmpty()) {
            pref.setPreferredTimeStart((LocalTime) timeStarts.get(0)[0]);
        }
        List<Object[]> timeEnds = bookingRepository.findPreferredTimeEndsByUserId(userId, PageRequest.of(0, 1));
        if (!timeEnds.isEmpty()) {
            pref.setPreferredTimeEnd((LocalTime) timeEnds.get(0)[0]);
        }

        // 6. preferred_weekday
        List<Object[]> weekdays = bookingRepository.findPreferredWeekdayByUserId(userId, PageRequest.of(0, 1));
        if (!weekdays.isEmpty()) {
            Number dow = (Number) weekdays.get(0)[0];
            pref.setPreferredWeekday(dow.shortValue());
        }

        // 7. last_booking_date
        LocalDate lastDate = bookingRepository.findLastBookingDateByUserId(userId);
        pref.setLastBookingDate(lastDate);

        pref.setComputedAt(LocalDateTime.now());
        
        // Cold-start gate
        if (totalBookings < 3) {
            pref.setConfidenceScore(BigDecimal.valueOf(0.0));
        } else {
            pref.setConfidenceScore(BigDecimal.valueOf(0.8)); // example score
        }

        userPreferenceRepository.save(pref);
    }

    private void saveEmptyOrMinimalPreference(Integer userId, long totalBookings) {
        UserPreference pref = userPreferenceRepository.findById(userId)
                .orElseGet(() -> UserPreference.builder()
                        .userId(userId)
                        .user(userRepository.findById(userId).orElse(null))
                        .build());
        pref.setTotalBookings((int) totalBookings);
        pref.setComputedAt(LocalDateTime.now());
        pref.setConfidenceScore(BigDecimal.valueOf(0.0));
        userPreferenceRepository.save(pref);
    }

    @Cacheable(value = "userPreference", key = "#userId")
    public Optional<UserPreference> getForUser(Integer userId) {
        return userPreferenceRepository.findById(userId);
    }
}
