package com.sportvenue.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Dữ liệu để frontend hiển thị form cấu hình kèo ngay trong khung chat. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftCreateMatchResponse {
    private Integer bookingId;
    private String defaultTitle;
    private String stadiumName;
    private String sportName;
    private LocalDate playDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String matchingType;
    private Integer maxPlayers;
    private String skillLevel;
    private Boolean splitPrice;
    private BigDecimal pricePerPlayer;
    private List<String> missingFields;
}
