package com.sportvenue.repository;

import com.sportvenue.entity.MatchRequest;
import com.sportvenue.entity.enums.MatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MatchRequestRepository extends JpaRepository<MatchRequest, Integer>, JpaSpecificationExecutor<MatchRequest> {

    /** Tìm các kèo ghép có kèm thông tin sân, môn thể thao và người tạo kèm phân trang */
    @EntityGraph(attributePaths = {"user", "stadium", "sportType"})
    Page<MatchRequest> findAllByMatchStatus(MatchStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "stadium", "sportType"})
    @Query("""
            SELECT m FROM MatchRequest m
            WHERE m.matchStatus IN (:statuses)
            AND (m.playDate > :nowDate OR (m.playDate = :nowDate AND m.startTime > :nowTime))
            ORDER BY CASE WHEN m.matchStatus = 'OPEN' THEN 0 ELSE 1 END ASC,
                     m.playDate ASC,
                     m.startTime ASC
            """)
    Page<MatchRequest> findActiveMatchesSorted(
            @Param("statuses") List<MatchStatus> statuses,
            @Param("nowDate") LocalDate nowDate,
            @Param("nowTime") LocalTime nowTime,
            Pageable pageable);

    @EntityGraph(attributePaths = {"user", "stadium", "sportType"})
    List<MatchRequest> findAllByPlayDateGreaterThanEqualAndMatchStatus(LocalDate date, MatchStatus status);

    @EntityGraph(attributePaths = {"user", "stadium", "sportType"})
    List<MatchRequest> findAllByUserEmailOrderByCreatedAtDesc(String email);

    @EntityGraph(attributePaths = {"user", "stadium", "sportType"})
    Page<MatchRequest> findAllByUserUserIdOrderByCreatedAtDesc(Integer userId, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE MatchRequest m SET m.matchStatus = :status, m.cancelReason = :cancelReason WHERE m.matchId = :matchId")
    void updateStatusAndReason(@Param("matchId") Integer matchId, @Param("status") MatchStatus status, @Param("cancelReason") String cancelReason);

    /** Atomic increment currentPlayers — tránh race condition khi nhiều request approve cùng lúc. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE MatchRequest m SET m.currentPlayers = m.currentPlayers + 1 WHERE m.matchId = :matchId AND m.currentPlayers < m.maxPlayers")
    int incrementCurrentPlayers(@Param("matchId") Integer matchId);

    @EntityGraph(attributePaths = {"user", "stadium", "sportType"})
    Optional<MatchRequest> findByMatchId(Integer matchId);

    @Query("""
            SELECT COUNT(m) > 0 FROM MatchRequest m
            WHERE m.user.userId = :userId
            AND m.playDate = :playDate
            AND m.matchStatus IN (com.sportvenue.entity.enums.MatchStatus.OPEN, com.sportvenue.entity.enums.MatchStatus.FULL)
            AND m.startTime < :endTime
            AND m.endTime > :startTime
            """)
    boolean existsOverlappingMatchRequest(
            @Param("userId") Integer userId,
            @Param("playDate") LocalDate playDate,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime);

    boolean existsBySportTypeSportTypeId(Integer sportTypeId);

    boolean existsByBookingBookingIdAndMatchStatusIn(Integer bookingId, List<MatchStatus> statuses);

    /** Tìm kèo đang active (OPEN/FULL) gắn với một booking — dùng khi hủy booking để auto-cancel kèo. */
    @EntityGraph(attributePaths = {"user", "stadium", "sportType"})
    @Query("""
            SELECT m FROM MatchRequest m
            WHERE m.booking.bookingId = :bookingId
            AND m.matchStatus IN (
                com.sportvenue.entity.enums.MatchStatus.OPEN,
                com.sportvenue.entity.enums.MatchStatus.FULL
            )
            """)
    Optional<MatchRequest> findActiveByBookingId(@Param("bookingId") Integer bookingId);
}

