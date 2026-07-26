package com.sportvenue.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_preference")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPreference {
    @Id
    @Column(name = "user_id")
    private Integer userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "favorite_sport", length = 50)
    private String favoriteSport;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sport_type_id")
    private SportType sportType;

    @Column(name = "preferred_district", length = 100)
    private String preferredDistrict;

    @Column(name = "preferred_province", length = 100)
    private String preferredProvince;

    @Column(name = "preferred_time_start")
    private LocalTime preferredTimeStart;

    @Column(name = "preferred_time_end")
    private LocalTime preferredTimeEnd;

    @Column(name = "preferred_weekday")
    private Short preferredWeekday;

    @Column(name = "avg_price_per_booking", precision = 10, scale = 2)
    private BigDecimal avgPricePerBooking;

    @Column(name = "max_price_per_booking", precision = 10, scale = 2)
    private BigDecimal maxPricePerBooking;

    @Column(name = "total_bookings")
    private Integer totalBookings;

    @Column(name = "total_completed_minutes")
    private Integer totalCompletedMinutes;

    @Column(name = "last_booking_date")
    private LocalDate lastBookingDate;

    @Column(name = "last_active_date")
    private LocalDate lastActiveDate;

    @Column(name = "computed_at")
    private LocalDateTime computedAt;

    @Column(name = "confidence_score", precision = 3, scale = 2)
    private BigDecimal confidenceScore;
}
