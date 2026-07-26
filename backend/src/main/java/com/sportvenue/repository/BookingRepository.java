package com.sportvenue.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;

import com.sportvenue.entity.Booking;
import com.sportvenue.entity.enums.BookingStatus;

import jakarta.persistence.LockModeType;

/**
 * Repository cho Booking entity.
 * Stub — Hoàng và Lượng mở rộng thêm query khi cần.
 */
@Repository
public interface BookingRepository extends JpaRepository<Booking, Integer>, JpaSpecificationExecutor<Booking> {

    @Override
    @EntityGraph(attributePaths = {
        "user", "slot", "stadium",
        "stadium.owner", "stadium.owner.user",
        "stadium.parentStadium", "stadium.parentStadium.complex", "stadium.parentStadium.complex.owner", "stadium.parentStadium.complex.owner.user",
        "stadium.complex", "stadium.complex.owner", "stadium.complex.owner.user"
    })
    Page<Booking> findAll(Specification<Booking> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"stadium", "stadium.sportType", "stadium.images", "slot"})
    @Query("SELECT b FROM Booking b WHERE b.bookingId = :id")
    Optional<Booking> findDetailById(@Param("id") Integer id);

    /** Tìm kiếm đơn đặt sân kèm theo Pessimistic Write Lock để tránh Race Condition (Double Refund) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.bookingId = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Integer id);

    /**
     * Lấy lịch sử đặt sân của khách hàng — dùng cho trang "Lịch sử đặt sân".
     * Sort theo {@code bookingDate DESC} (mới tạo trước) rồi {@code reservationDate DESC}.
     * UC-CUS-01: Sau khi đặt sân xong, đơn mới phải xuất hiện ở đầu danh sách.
     */
    @EntityGraph(attributePaths = {"stadium", "stadium.sportType", "stadium.images", "slot"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.user.userId = :userId
            ORDER BY b.bookingDate DESC, b.reservationDate DESC
            """)
    Page<Booking> findByUserUserIdOrderByReservationDateDesc(Integer userId, Pageable pageable);

    @EntityGraph(attributePaths = {"stadium", "stadium.sportType", "stadium.images", "slot"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.user.userId = :userId
            AND b.bookingStatus IN :statuses
            ORDER BY b.bookingDate DESC, b.reservationDate DESC
            """)
    Page<Booking> findByUserUserIdAndBookingStatusInOrderByReservationDateDesc(
            Integer userId, List<BookingStatus> statuses, Pageable pageable);

    /**
     * Tra cứu 1 booking cụ thể theo bookingId, scoped theo userId + status — dùng cho lookup
     * tường minh (VD: user gõ "hủy đơn #648") nên không được giới hạn bởi page size như
     * {@link #findByUserUserIdAndBookingStatusInOrderByReservationDateDesc}, nếu không booking
     * nằm ngoài top N gần nhất sẽ báo "không tìm thấy" dù vẫn thuộc về user và hủy được.
     */
    @EntityGraph(attributePaths = {"stadium", "stadium.sportType", "stadium.images", "slot"})
    Optional<Booking> findByBookingIdAndUserUserIdAndBookingStatusIn(
            Integer bookingId, Integer userId, List<BookingStatus> statuses);

    /**
     * UC-CUS-01: Lịch sử đặt sân của customer hiện tại — hỗ trợ filter status tùy chọn.
     * Dùng cho {@code GET /api/v1/bookings/me}.
     *
     * <p>Mapping status FE → BE:</p>
     * <ul>
     *   <li>{@code upcoming} → PENDING, CONFIRMED</li>
     *   <li>{@code completed} → COMPLETED</li>
     *   <li>{@code cancelled} → CANCELLED</li>
     *   <li>Không truyền / "all" → tất cả trạng thái</li>
     * </ul>
     */
    @EntityGraph(attributePaths = {"stadium", "stadium.sportType", "stadium.images", "slot"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.user.userId = :userId
            AND (:status IS NULL OR b.bookingStatus = :status)
            ORDER BY b.bookingDate DESC, b.reservationDate DESC
            """)
    Page<Booking> findMyBookings(
            @Param("userId") Integer userId,
            @Param("status") BookingStatus status,
            Pageable pageable);

    /** Lịch sắp tới — slot chưa kết thúc, đơn Pending hoặc Confirmed. */
    @EntityGraph(attributePaths = {"stadium", "stadium.sportType", "stadium.images", "slot"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.user.userId = :userId
            AND b.bookingStatus IN (com.sportvenue.entity.enums.BookingStatus.PENDING,
                                    com.sportvenue.entity.enums.BookingStatus.CONFIRMED)
            AND b.bookingDate >= :now
            ORDER BY b.bookingDate ASC
            """)
    List<Booking> findUpcomingByUserId(
            @Param("userId") Integer userId,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    long countByUserUserId(Integer userId);

    @Query("""
            SELECT COUNT(DISTINCT b.stadium.stadiumId) FROM Booking b
            WHERE b.user.userId = :userId
            AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED
            """)
    long countDistinctCompletedVenues(@Param("userId") Integer userId);

    @EntityGraph(attributePaths = {"stadium", "stadium.sportType", "slot"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.user.userId = :userId
            AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED
            """)
    List<Booking> findCompletedByUserId(@Param("userId") Integer userId);

    /** Tổng số phút chơi từ các booking hoàn thành — dùng cho PersonalStats. */
    @Query(value = """
            SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (s.end_time - s.start_time)) / 60), 0)
            FROM bookings b
            JOIN time_slots s ON b.slot_id = s.slot_id
            WHERE b.user_id = :userId
            AND b.booking_status = 'COMPLETED'
            """, nativeQuery = true)
    long sumCompletedPlayMinutes(@Param("userId") Integer userId);

    /** Môn thể thao chơi nhiều nhất — dùng cho PersonalStats. Returns [sportName, count]. */
    @Query("""
            SELECT b.stadium.sportType.sportName, COUNT(b)
            FROM Booking b
            WHERE b.user.userId = :userId
            AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED
            GROUP BY b.stadium.sportType.sportName
            ORDER BY COUNT(b) DESC
            """)
    List<Object[]> findTopSportByUserId(@Param("userId") Integer userId, Pageable pageable);

    /** Lấy danh sách đặt sân của một sân — dùng cho Owner quản lý. */
    @EntityGraph(attributePaths = {"user", "stadium", "stadium.sportType", "slot"})
    Page<Booking> findByStadiumStadiumIdOrderByBookingDateDesc(Integer stadiumId, Pageable pageable);

    /** Lấy đặt sân theo trạng thái — dùng cho Owner filter Pending. */
    @EntityGraph(attributePaths = {"user", "stadium", "stadium.sportType", "slot"})
    Page<Booking> findByStadiumStadiumIdAndBookingStatus(Integer stadiumId, BookingStatus status, Pageable pageable);

    /**
     * Tổng doanh thu theo NGÀY ĐẶT (bookingDate) — thời điểm khách tạo đơn.
     *
     * Phù hợp khi báo cáo theo "doanh thu ghi nhận trong ngày" (kế toán dồn tích).
     * Không phản ánh ngày khách thực sự ra sân chơi.
     *
     * Dùng cho: UC-OWN-10 (Revenue Report) nếu product owner muốn thống kê theo ngày đặt.
     */
    @Query("""
            SELECT COALESCE(SUM(b.totalPrice), 0) FROM Booking b
            WHERE b.stadium.stadiumId = :stadiumId
            AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED
            AND b.bookingDate BETWEEN :from AND :to
            """)
    BigDecimal sumRevenueByBookingDate(
            @Param("stadiumId") Integer stadiumId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Tổng doanh thu theo NGÀY CHƠI (reservationDate) — thời điểm khách thực sự ra sân.
     * Khác với {@link #sumRevenueByBookingDate}: tính theo ngày chơi, không phải ngày đặt.
     */
    @Query("""
            SELECT COALESCE(SUM(b.totalPrice), 0) FROM Booking b
            WHERE b.stadium.stadiumId = :stadiumId
            AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED
            AND b.reservationDate BETWEEN :from AND :to
            """)
    BigDecimal sumRevenueBySlotDate(
            @Param("stadiumId") Integer stadiumId,
            @Param("from") java.time.LocalDate from,
            @Param("to") java.time.LocalDate to);

    @Query("""
            SELECT b.reservationDate AS date,
                   COALESCE(SUM(
                       CASE WHEN p.amount > 0 AND p.paymentStatus = com.sportvenue.entity.enums.TransactionStatus.SUCCESS THEN p.amount ELSE 0 END
                     - CASE WHEN p.amount < 0 AND p.paymentStatus = com.sportvenue.entity.enums.TransactionStatus.SUCCESS THEN ABS(p.amount) ELSE 0 END
                   ), 0) - COALESCE((
                       SELECT SUM(ABS(wt.amount))
                       FROM WalletTransaction wt
                       JOIN wt.booking b2
                       JOIN b2.stadium s2
                       WHERE s2.owner.user.email = :ownerEmail
                       AND (:stadiumId IS NULL OR s2.stadiumId = :stadiumId)
                       AND (b2.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED OR (b2.isWalkIn = true AND b2.bookingStatus = com.sportvenue.entity.enums.BookingStatus.CONFIRMED))
                       AND b2.reservationDate = b.reservationDate
                       AND wt.transactionType = com.sportvenue.entity.enums.WalletTransactionType.SERVICE_FEE_DEBIT
                   ), 0) AS revenue
            FROM Booking b
            JOIN b.stadium s
            LEFT JOIN Payment p ON p.booking = b
            WHERE s.owner.user.email = :ownerEmail
            AND (:stadiumId IS NULL OR s.stadiumId = :stadiumId)
            AND (b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED OR (b.isWalkIn = true AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.CONFIRMED))
            AND b.reservationDate BETWEEN :startDate AND :endDate
            GROUP BY b.reservationDate
            ORDER BY b.reservationDate ASC
            """)
    List<com.sportvenue.repository.projection.DailyRevenueProjection> getOwnerDailyNetRevenue(
            @Param("ownerEmail") String ownerEmail,
            @Param("stadiumId") Integer stadiumId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("""
            SELECT s.stadiumId AS stadiumId,
                   s.stadiumName AS stadiumName,
                   COUNT(DISTINCT b.bookingId) AS totalBookings,
                   COALESCE(SUM(
                       CASE WHEN p.amount > 0 AND p.paymentStatus = com.sportvenue.entity.enums.TransactionStatus.SUCCESS THEN p.amount ELSE 0 END
                     - CASE WHEN p.amount < 0 AND p.paymentStatus = com.sportvenue.entity.enums.TransactionStatus.SUCCESS THEN ABS(p.amount) ELSE 0 END
                   ), 0) - COALESCE((
                       SELECT SUM(ABS(wt.amount))
                       FROM WalletTransaction wt
                       JOIN wt.booking b2
                       JOIN b2.stadium s2
                       WHERE s2.owner.user.email = :ownerEmail
                       AND (:stadiumId IS NULL OR s2.stadiumId = :stadiumId)
                       AND (b2.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED OR (b2.isWalkIn = true AND b2.bookingStatus = com.sportvenue.entity.enums.BookingStatus.CONFIRMED))
                       AND b2.reservationDate BETWEEN :startDate AND :endDate
                       AND s2 = s
                       AND wt.transactionType = com.sportvenue.entity.enums.WalletTransactionType.SERVICE_FEE_DEBIT
                   ), 0) AS totalRevenue
            FROM Booking b
            JOIN b.stadium s
            LEFT JOIN Payment p ON p.booking = b
            WHERE s.owner.user.email = :ownerEmail
            AND (:stadiumId IS NULL OR s.stadiumId = :stadiumId)
            AND (b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED OR (b.isWalkIn = true AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.CONFIRMED))
            AND b.reservationDate BETWEEN :startDate AND :endDate
            GROUP BY s.stadiumId, s.stadiumName
            ORDER BY totalRevenue DESC
            """)
    List<com.sportvenue.repository.projection.VenueRevenueProjection> getOwnerVenueNetRevenueBreakdown(
            @Param("ownerEmail") String ownerEmail,
            @Param("stadiumId") Integer stadiumId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("""
            SELECT COALESCE(SUM(
                CASE WHEN p.amount > 0 AND p.paymentStatus = com.sportvenue.entity.enums.TransactionStatus.SUCCESS THEN p.amount ELSE 0 END
              - CASE WHEN p.amount < 0 AND p.paymentStatus = com.sportvenue.entity.enums.TransactionStatus.SUCCESS THEN ABS(p.amount) ELSE 0 END
            ), 0) - COALESCE((
                SELECT SUM(ABS(wt.amount))
                FROM WalletTransaction wt
                JOIN wt.booking b2
                JOIN b2.stadium s2
                WHERE s2.owner.user.email = :ownerEmail
                AND (b2.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED OR (b2.isWalkIn = true AND b2.bookingStatus = com.sportvenue.entity.enums.BookingStatus.CONFIRMED))
                AND b2.reservationDate BETWEEN :startDate AND :endDate
                AND wt.transactionType = com.sportvenue.entity.enums.WalletTransactionType.SERVICE_FEE_DEBIT
            ), 0)
            FROM Booking b
            JOIN b.stadium s
            LEFT JOIN Payment p ON p.booking = b
            WHERE s.owner.user.email = :ownerEmail
            AND (b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED OR (b.isWalkIn = true AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.CONFIRMED))
            AND b.reservationDate BETWEEN :startDate AND :endDate
            """)
    BigDecimal sumOwnerCurrentMonthNetRevenue(
            @Param("ownerEmail") String ownerEmail,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** Đếm số lượng đặt sân theo trạng thái — dùng cho Dashboard. */
    long countByStadiumStadiumIdAndBookingStatus(Integer stadiumId, BookingStatus status);

    /**
     * Lấy booking của nhiều sân cùng lúc — dùng cho Owner xem tất cả booking.
     * Hỗ trợ filter theo status (optional, null = tất cả).
     */
    @EntityGraph(attributePaths = {"user", "stadium", "stadium.sportType", "slot"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.stadium.stadiumId IN :stadiumIds
            AND (:status IS NULL OR b.bookingStatus = :status)
            ORDER BY b.bookingDate DESC
            """)
    Page<Booking> findByStadiumStadiumIdInOrderByBookingDateDesc(
            @Param("stadiumIds") List<Integer> stadiumIds,
            @Param("status") BookingStatus status,
            Pageable pageable);

    /** Lấy ID booking thuộc các sân của Owner có phân trang và filter. */
    @Query("""
            SELECT b.bookingId FROM Booking b
            WHERE b.stadium.owner.user.email = :email
            AND (:stadiumId IS NULL OR b.stadium.stadiumId = :stadiumId)
            AND (:status IS NULL OR b.bookingStatus = :status)
            AND b.reservationDate BETWEEN :startDate AND :endDate
            ORDER BY b.bookingDate DESC
            """)
    Page<Integer> findIdsByOwnerEmailAndFilters(
            @Param("email") String email,
            @Param("stadiumId") Integer stadiumId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("status") BookingStatus status,
            Pageable pageable);

    @EntityGraph(attributePaths = {"user", "stadium", "slot"})
    @Query("SELECT b FROM Booking b WHERE b.bookingId IN :ids")
    List<Booking> findAllByIdsWithDetails(@Param("ids") List<Integer> ids);

    default Page<Booking> findByOwnerEmailAndFilters(
            String email,
            Integer stadiumId,
            LocalDate startDate,
            LocalDate endDate,
            BookingStatus status,
            Pageable pageable) {
        Page<Integer> idPage = findIdsByOwnerEmailAndFilters(email, stadiumId, startDate, endDate, status, pageable);
        if (idPage.isEmpty()) {
            return new org.springframework.data.domain.PageImpl<>(java.util.Collections.emptyList(), pageable, 0);
        }
        List<Booking> details = findAllByIdsWithDetails(idPage.getContent());
        java.util.Map<Integer, Booking> map = details.stream()
                .collect(java.util.stream.Collectors.toMap(Booking::getBookingId, b -> b));
        List<Booking> ordered = idPage.getContent().stream()
                .map(map::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        return new org.springframework.data.domain.PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }

    /** Lấy booking thuộc các sân của Owner, có phân trang và filter trạng thái. */
    @EntityGraph(attributePaths = {"user", "stadium", "slot"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.stadium.owner.user.email = :email
            AND (:status IS NULL OR b.bookingStatus = :status)
            ORDER BY b.bookingDate DESC
            """)
    Page<Booking> findByOwnerEmailAndStatus(
            @Param("email") String email,
            @Param("status") BookingStatus status,
            Pageable pageable);

    @Query("""
            SELECT b FROM Booking b
            WHERE b.stadium.owner.user.email = :email
            AND (:status IS NULL OR b.bookingStatus = :status)
            """)
    List<Booking> findAllByOwnerEmailAndStatus(
            @Param("email") String email,
            @Param("status") BookingStatus status);

    /** Lấy danh sách đặt sân của tất cả các sân thuộc Owner có phân trang và filter status */
    @EntityGraph(attributePaths = {"user", "stadium", "stadium.sportType", "slot"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.stadium.owner.ownerId = :ownerId
            AND (:status IS NULL OR b.bookingStatus = :status)
            ORDER BY b.bookingDate DESC
            """)
    Page<Booking> findByOwnerIdAndStatus(
            @Param("ownerId") Integer ownerId,
            @Param("status") BookingStatus status,
            Pageable pageable);

    @Query("""
            SELECT COUNT(b) FROM Booking b
            JOIN b.stadium s
            JOIN s.owner o
            JOIN o.user u
            WHERE u.email = :ownerEmail
            AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.PENDING
            """)
    long countPendingBookingsByOwnerEmail(@Param("ownerEmail") String ownerEmail);

    @Query("""
            SELECT COUNT(b) FROM Booking b
            JOIN b.stadium s
            JOIN s.owner o
            JOIN o.user u
            WHERE u.email = :ownerEmail
            AND b.bookingDate BETWEEN :startOfToday AND :endOfToday
            AND b.bookingStatus IN (com.sportvenue.entity.enums.BookingStatus.CONFIRMED, com.sportvenue.entity.enums.BookingStatus.COMPLETED)
            """)
    long countTodayBookingsByOwnerEmail(
            @Param("ownerEmail") String ownerEmail,
            @Param("startOfToday") LocalDateTime startOfToday,
            @Param("endOfToday") LocalDateTime endOfToday);

    @Query("""
            SELECT COUNT(b) FROM Booking b
            JOIN b.stadium s
            JOIN s.owner o
            JOIN o.user u
            WHERE u.email = :ownerEmail
            AND b.bookingStatus IN (com.sportvenue.entity.enums.BookingStatus.CONFIRMED, com.sportvenue.entity.enums.BookingStatus.COMPLETED)
            AND b.bookingDate BETWEEN :startDate AND :endDate
            """)
    long countBookingsByOwnerAndDateRange(
            @Param("ownerEmail") String ownerEmail,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query("""
            SELECT b FROM Booking b
            WHERE b.stadium.stadiumId = :stadiumId
            AND b.bookingStatus IN (com.sportvenue.entity.enums.BookingStatus.PENDING,
                                    com.sportvenue.entity.enums.BookingStatus.CONFIRMED)
            AND (b.reservationDate > :today OR (b.reservationDate = :today AND b.slot.endTime >= :nowTime))
            """)
    List<Booking> findFutureBookingsByStadiumId(
            @Param("stadiumId") Integer stadiumId,
            @Param("today") java.time.LocalDate today,
            @Param("nowTime") java.time.LocalTime nowTime);

    @Query("""
            SELECT COUNT(b) > 0 FROM Booking b
            WHERE b.user.userId = :userId
            AND b.reservationDate = :reservationDate
            AND b.bookingStatus IN (com.sportvenue.entity.enums.BookingStatus.PENDING, com.sportvenue.entity.enums.BookingStatus.CONFIRMED)
            AND b.slot.startTime < :endTime
            AND b.slot.endTime > :startTime
            """)
    boolean existsOverlappingBooking(
            @Param("userId") Integer userId,
            @Param("reservationDate") LocalDate reservationDate,
            @Param("startTime") java.time.LocalTime startTime,
            @Param("endTime") java.time.LocalTime endTime);

    @Query("""
            SELECT b FROM Booking b
            WHERE b.user.userId = :userId
            AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.CONFIRMED
            AND (b.reservationDate > :today OR (b.reservationDate = :today AND b.slot.startTime > :nowTime))
            AND NOT EXISTS (
                SELECT 1 FROM MatchRequest m
                WHERE m.booking = b
                AND m.matchStatus IN (com.sportvenue.entity.enums.MatchStatus.OPEN, com.sportvenue.entity.enums.MatchStatus.FULL)
            )
            ORDER BY b.reservationDate ASC, b.slot.startTime ASC
            """)
    List<Booking> findEligibleForMatchCreation(
            @Param("userId") Integer userId,
            @Param("today") LocalDate today,
            @Param("nowTime") java.time.LocalTime nowTime);

    long countByBookingStatus(BookingStatus status);

    @EntityGraph(attributePaths = {"user", "stadium", "slot"})
    List<Booking> findTop5ByOrderByBookingDateDesc();

    /**
     * UC-CUS-01: True nếu đã có đơn active (bookingStatus IN :statuses)
     * cho cùng (stadiumId, slotId, reservationDate). CANCELLED / COMPLETED
     * KHÔNG chặn — slot coi như còn trống.
     */
    @Query("""
            SELECT COUNT(b) > 0 FROM Booking b
            WHERE b.stadium.stadiumId = :stadiumId
            AND b.slot.slotId = :slotId
            AND b.reservationDate = :reservationDate
            AND b.bookingStatus IN :statuses
            """)
    boolean existsActiveBooking(
            @Param("stadiumId") Integer stadiumId,
            @Param("slotId") Integer slotId,
            @Param("reservationDate") LocalDate reservationDate,
            @Param("statuses") List<BookingStatus> statuses);

    @Query("""
            SELECT b.stadium.stadiumId, b.slot.slotId FROM Booking b
            WHERE b.stadium.stadiumId IN :stadiumIds
            AND b.reservationDate = :reservationDate
            AND b.bookingStatus IN :statuses
            """)
    List<Object[]> findActiveBookingKeysByStadiumIds(
            @Param("stadiumIds") List<Integer> stadiumIds,
            @Param("reservationDate") LocalDate reservationDate,
            @Param("statuses") List<BookingStatus> statuses);

    @Query("""
            SELECT b FROM Booking b
            WHERE b.user.userId = :userId
            AND b.slot.slotId = :slotId
            AND b.reservationDate = :reservationDate
            AND b.bookingStatus IN :statuses
            """)
    List<Booking> findUserActiveBookingsForSlot(
            @Param("userId") Integer userId,
            @Param("slotId") Integer slotId,
            @Param("reservationDate") LocalDate reservationDate,
            @Param("statuses") List<BookingStatus> statuses);

    /**
     * UC-CUS-01: Trả về tập slotId đã có đơn active (PENDING/CONFIRMED) cho
     * sân vào một ngày cụ thể — dùng để render availability FE.
     */
    @Query("""
            SELECT DISTINCT b.slot.slotId FROM Booking b
            WHERE b.stadium.stadiumId = :stadiumId
            AND b.reservationDate = :reservationDate
            AND b.bookingStatus IN :statuses
            """)
    List<Integer> findBookedSlotIds(
            @Param("stadiumId") Integer stadiumId,
            @Param("reservationDate") LocalDate reservationDate,
            @Param("statuses") List<BookingStatus> statuses);

    /**
     * UC-CUS-01: Trả về toàn bộ booking active (PENDING/CONFIRMED) của một sân
     * trong khoảng ngày {@code [start, end]} — dùng cho weekly schedule để
     * service map (date → tập slotId đã đặt) khi render lịch tuần.
     *
     * <p>CANCELLED / COMPLETED KHÔNG được tính — slot coi như còn trống.</p>
     */
    @Query("""
            SELECT b FROM Booking b
            WHERE b.stadium.stadiumId = :stadiumId
            AND b.reservationDate BETWEEN :start AND :end
            AND b.bookingStatus IN :statuses
            """)
    @EntityGraph(attributePaths = {"user", "slot"})
    List<Booking> findWeeklyBookings(
            @Param("stadiumId") Integer stadiumId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("statuses") List<BookingStatus> statuses);

    /**
     * UC-CUS-01: Booking {@code PENDING_PAYMENT} đã quá hạn thanh toán.
     * Scheduler dùng để tự huỷ — giải phóng slot cho khách khác đặt.
     */
    @Query("""
            SELECT b FROM Booking b
            WHERE b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.PENDING_PAYMENT
            AND b.expiredAt IS NOT NULL
            AND b.expiredAt < :now
            """)
    List<Booking> findExpiredPendingPayments(@Param("now") LocalDateTime now);

    /**
     * UC-ADM-01: Đếm booking theo ngày — dùng cho biểu đồ trend trên Admin Dashboard.
     * Trả về mảng [reservationDate, count] cho n ngày gần nhất.
     */
    @Query("""
            SELECT b.reservationDate as date, COUNT(b) as count
            FROM Booking b
            WHERE b.reservationDate BETWEEN :startDate AND :endDate
            GROUP BY b.reservationDate
            ORDER BY b.reservationDate ASC
            """)
    List<Object[]> countBookingsByDateRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** UC-ADM-01: Đếm booking theo trạng thái trong khoảng ngày (reservationDate). */
    @Query("""
            SELECT COUNT(b) FROM Booking b
            WHERE b.bookingStatus = :status
            AND b.reservationDate BETWEEN :startDate AND :endDate
            """)
    long countByBookingStatusAndDateRange(
            @Param("status") BookingStatus status,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** UC-ADM-01: Tổng booking trong khoảng ngày — cho KPI card. */
    @Query("""
            SELECT COUNT(b) FROM Booking b
            WHERE b.reservationDate BETWEEN :startDate AND :endDate
            """)
    long countByDateRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Đếm booking theo toàn bộ Admin filter (khớp với Specification của getAdminBookings).
     * Dùng b.reservationDate để thống nhất cột ngày với tất cả aggregate queries.
     */
    @Query("""
            SELECT COUNT(b) FROM Booking b
            JOIN b.stadium s
            WHERE b.reservationDate >= :startDate
            AND b.reservationDate <= :endDate
            AND b.bookingStatus IN :bookingStatuses
            AND b.paymentStatus IN :paymentStatuses
            AND (:stadiumId IS NULL OR s.stadiumId = :stadiumId)
            AND (:ownerId IS NULL OR s.owner.ownerId = :ownerId)
            """)
    long countByFilters(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("bookingStatuses") List<BookingStatus> bookingStatuses,
            @Param("paymentStatuses") List<com.sportvenue.entity.enums.PaymentStatus> paymentStatuses,
            @Param("stadiumId") Integer stadiumId,
            @Param("ownerId") Integer ownerId);

    /** UC-ADM-01: Top 5 đặt sân gần nhất trong khoảng ngày. */
    @EntityGraph(attributePaths = {"user", "stadium", "slot"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.reservationDate BETWEEN :startDate AND :endDate
            ORDER BY b.bookingDate DESC
            """)
    List<Booking> findTop5ByDateRangeOrderByBookingDateDesc(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);

    @Query("""
            SELECT b.stadium.stadiumId FROM Booking b
            WHERE b.user.userId = :userId
            AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED
            GROUP BY b.stadium.stadiumId
            ORDER BY MAX(b.reservationDate) DESC, MAX(b.slot.startTime) DESC
            """)
    List<Integer> findDistinctBookedStadiumIds(@Param("userId") Integer userId, Pageable pageable);



    /**
     * Booking đang chiếm chỗ (theo {@code statuses}) của 1 hoặc nhiều sân trong khoảng
     * ngày {@code [rangeStart, rangeEnd]} — dùng để check conflict khi Owner tạo
     * MaintenanceSchedule mới (bao gồm cả court con khi bảo trì đặt ở FACILITY).
     * {@code @EntityGraph(slot)} — caller lọc tiếp theo giờ slot (so-trùng khung giờ bảo trì),
     * cần {@code slot} tránh N+1 khi truy cập {@code booking.getSlot().getStartTime()/getEndTime()}.
     */
    @EntityGraph(attributePaths = {"slot"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.stadium.stadiumId IN :stadiumIds
            AND b.reservationDate BETWEEN :rangeStart AND :rangeEnd
            AND b.bookingStatus IN :statuses
            """)
    List<Booking> findByStadiumIdsAndDateRangeAndStatuses(
            @Param("stadiumIds") List<Integer> stadiumIds,
            @Param("rangeStart") LocalDate rangeStart,
            @Param("rangeEnd") LocalDate rangeEnd,
            @Param("statuses") List<BookingStatus> statuses);

    /**
     * UC-CUS-07: Lấy booking COMPLETED của user tại sân cụ thể mà chưa được review.
     * FE dùng để xác định customer có đủ điều kiện viết đánh giá không.
     */
    @EntityGraph(attributePaths = {"stadium", "slot"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.user.userId = :userId
            AND b.stadium.stadiumId = :stadiumId
            AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED
            AND NOT EXISTS (
                SELECT r FROM com.sportvenue.entity.Review r
                WHERE r.booking.bookingId = b.bookingId
            )
            ORDER BY b.bookingDate DESC
            """)
    List<Booking> findCompletedUnreviewedBookings(
            @Param("userId") Integer userId,
            @Param("stadiumId") Integer stadiumId);

    /**
     * Lấy booking COMPLETED trong khoảng ngày chơi [startDate, endDate]
     * chưa được review và chưa gửi nhắc đánh giá.
     * Dùng bởi {@code ReviewReminderScheduler} để gửi REVIEW_REMINDER.
     */
    @EntityGraph(attributePaths = {"user", "stadium", "slot"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED
            AND b.reviewReminderSentAt IS NULL
            AND b.reservationDate BETWEEN :startDate AND :endDate
            AND NOT EXISTS (
                SELECT r FROM com.sportvenue.entity.Review r
                WHERE r.booking.bookingId = b.bookingId
            )
            """)
    List<Booking> findCompletedUnreviewedUnremindedBookings(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Lấy các booking CONFIRMED có reservation_date <= hôm nay.
     * Lọc thô theo ngày — scheduler sẽ lọc chính xác theo endTime ở Java.
     *
     * <p>Lý do không so sánh date+time trong JPQL: reservationDate là LocalDate,
     * slot.endTime là LocalTime — không thể combine thành LocalDateTime trong JPQL.
     *
     * <p>Dùng bởi {@code BookingCompletionScheduler}.
     */
    @EntityGraph(attributePaths = {"slot"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.CONFIRMED
            AND b.reservationDate >= :limitDate
            AND b.reservationDate <= :today
            """)
    List<Booking> findConfirmedPastPlayTime(
            @Param("limitDate") java.time.LocalDate limitDate,
            @Param("today") java.time.LocalDate today);

    /**
     * Lấy các booking CONFIRMED, chưa nhắc (reminderSentAt IS NULL),
     * có reservation_date trong khoảng [startDate, endDate].
     *
     * <p>Query lọc thô theo ngày. Scheduler sẽ lọc chính xác theo startTime
     * để đảm bảo chỉ nhắc booking trong đúng 24h tới.
     *
     * <p>endDate nên được tính là {@code now.plusHours(24).toLocalDate()} (không phải
     * {@code now.plusDays(1)}) để tránh bỏ sót booking ở biên ngày.
     *
     * <p>Dùng bởi {@code BookingReminderScheduler}.
     */
    @EntityGraph(attributePaths = {"user", "stadium", "slot"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.CONFIRMED
            AND b.reminderSentAt IS NULL
            AND b.reservationDate BETWEEN :startDate AND :endDate
            """)
    List<Booking> findUpcomingUnremindedBookings(
            @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate);
    // --- AI Proactive Notification Queries ---

    @Query("SELECT AVG(b.totalPrice) FROM Booking b WHERE b.user.userId = :userId AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED")
    BigDecimal avgPricePerBookingCompleted(@Param("userId") Integer userId);

    @Query("SELECT MAX(b.reservationDate) FROM Booking b WHERE b.user.userId = :userId AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED")
    LocalDate findLastBookingDateByUserId(@Param("userId") Integer userId);

    @Query("SELECT b.slot.startTime, COUNT(b) FROM Booking b WHERE b.user.userId = :userId AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED GROUP BY b.slot.startTime ORDER BY COUNT(b) DESC")
    List<Object[]> findPreferredTimeStartsByUserId(@Param("userId") Integer userId, Pageable pageable);

    @Query("SELECT b.slot.endTime, COUNT(b) FROM Booking b WHERE b.user.userId = :userId AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED GROUP BY b.slot.endTime ORDER BY COUNT(b) DESC")
    List<Object[]> findPreferredTimeEndsByUserId(@Param("userId") Integer userId, Pageable pageable);

    @Query(value = "SELECT EXTRACT(ISODOW FROM reservation_date) as dow, COUNT(*) as cnt FROM bookings WHERE user_id = :userId AND booking_status = 'COMPLETED' GROUP BY dow ORDER BY cnt DESC", nativeQuery = true)
    List<Object[]> findPreferredWeekdayByUserId(@Param("userId") Integer userId, Pageable pageable);

    @Query("SELECT b.stadium.sportType.sportTypeId, b.stadium.sportType.sportName, COUNT(b) FROM Booking b WHERE b.user.userId = :userId AND b.bookingStatus = com.sportvenue.entity.enums.BookingStatus.COMPLETED GROUP BY b.stadium.sportType.sportTypeId, b.stadium.sportType.sportName ORDER BY COUNT(b) DESC")
    List<Object[]> findTopSportWithIdByUserId(@Param("userId") Integer userId, Pageable pageable);
}
