package com.sportvenue.dto.response;

import com.sportvenue.entity.enums.MatchStatus;
import com.sportvenue.entity.enums.MatchingType;
import com.sportvenue.entity.enums.SkillLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Response DTO đại diện cho thông tin một kèo ghép trả về qua API.
 * Tuân thủ quy tắc không expose Entity trực tiếp ra ngoài Controller.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchResponse {

    private Integer matchId;
    private String hostName;
    private Integer hostUserId;
    private String stadiumName;
    private String complexName;
    private String stadiumAddress;
    private String sportName;
    private String title;
    private String description;
    private LocalDate playDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer maxPlayers;
    private Integer currentPlayers;
    private SkillLevel skillLevel;
    private Boolean splitPrice;
    private BigDecimal pricePerPlayer;
    private MatchStatus matchStatus;
    private MatchingType matchingType;
    private String cancelReason;
    private LocalDateTime createdAt;

    /** true nếu current user là host của kèo này — FE dùng hiển thị badge "Kèo của bạn" thay vì nút Tham gia. */
    private Boolean isOwner;
}
