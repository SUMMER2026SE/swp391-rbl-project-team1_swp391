package com.sportvenue.repository;

import com.sportvenue.entity.Stadium;
import com.sportvenue.entity.enums.StadiumStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository cho Stadium entity.
 * Stub — các thành viên (đặc biệt Huy, An, Hào) mở rộng thêm query khi cần.
 */
@Repository
public interface StadiumRepository extends JpaRepository<Stadium, Integer>, JpaSpecificationExecutor<Stadium> {

    /**
     * Dùng cho trang quản lý Owner: tải cả cả FACILITY lẫn COURT của owner,
     * kèm eager-fetch parentStadium và childCourts để tránh N+1 query
     * khi filterPendingStadiums / filterSuspendedStadiums duyệt hierarchy.
     * Các field còn lại (sportType, images) đi kèm qua EntityGraph riêng.
     */
    @Query("""
            SELECT DISTINCT s FROM Stadium s
            LEFT JOIN FETCH s.parentStadium p
            LEFT JOIN FETCH s.childCourts
            WHERE s.owner.ownerId = :ownerId
            AND s.stadiumStatus != com.sportvenue.entity.enums.StadiumStatus.CLOSED
            """)
    List<Stadium> findAllByOwnerForTree(@Param("ownerId") Integer ownerId);

    /** Lấy danh sách sân theo chủ sân — dùng cho trang quản lý của Owner. */
    @EntityGraph(attributePaths = {"sportType", "images"})
    @Query("""
            SELECT s FROM Stadium s
            LEFT JOIN s.owner o
            LEFT JOIN s.parentStadium p
            LEFT JOIN p.complex c
            LEFT JOIN c.owner co
            WHERE s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT
            AND (o.ownerId = :ownerId OR co.ownerId = :ownerId)
            AND s.stadiumStatus != :status
            """)
    List<Stadium> findByOwnerOwnerIdAndStadiumStatusNot(@Param("ownerId") Integer ownerId, @Param("status") StadiumStatus status);

    @Query("""
            SELECT s FROM Stadium s
            LEFT JOIN s.owner o
            LEFT JOIN s.parentStadium p
            LEFT JOIN p.complex c
            LEFT JOIN c.owner co
            WHERE s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT
            AND (o.ownerId = :ownerId OR co.ownerId = :ownerId)
            """)
    List<Stadium> findByOwnerOwnerId(@Param("ownerId") Integer ownerId);

    /** Lấy tất cả sân đang hoạt động — dùng cho trang chủ Guest. */
    @EntityGraph(attributePaths = {"sportType", "images", "owner"})
    @Query("SELECT s FROM Stadium s WHERE s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT AND s.stadiumStatus = :status")
    Page<Stadium> findByStadiumStatus(@Param("status") StadiumStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"sportType", "images", "owner"})
    @Query("SELECT s FROM Stadium s WHERE s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT AND s.approvedStatus = :status")
    List<Stadium> findByApprovedStatus(@Param("status") com.sportvenue.entity.enums.ApprovedStatus status);

    @EntityGraph(attributePaths = {"sportType", "images", "owner"})
    @Query("SELECT s FROM Stadium s WHERE s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT")
    List<Stadium> findAllWithDetails();

    @Override
    @EntityGraph(attributePaths = {"sportType", "images", "amenities"})
    List<Stadium> findAllById(Iterable<Integer> ids);

    /** Tìm kiếm sân theo tên hoặc địa chỉ — dùng cho Search Venue. */
    @Query("""
            SELECT s FROM Stadium s
            LEFT JOIN s.parentStadium p
            LEFT JOIN p.complex c
            WHERE s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT
            AND s.stadiumStatus = 'AVAILABLE'
            AND (LOWER(s.stadiumName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                 OR LOWER(c.address) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<Stadium> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /** Lấy sân theo sport type — dùng cho Filter Venue. */
    @Query("""
            SELECT s FROM Stadium s
            LEFT JOIN s.parentStadium p
            LEFT JOIN p.sportType pst
            LEFT JOIN s.sportType st
            WHERE s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT
            AND (pst.sportTypeId = :sportTypeId OR st.sportTypeId = :sportTypeId)
            AND s.stadiumStatus = :status
            """)
    Page<Stadium> findBySportTypeSportTypeIdAndStadiumStatus(
            @Param("sportTypeId") Integer sportTypeId, 
            @Param("status") StadiumStatus status, 
            Pageable pageable);

    /** Đếm sân theo owner — dùng cho Dashboard. */
    @Query("""
            SELECT COUNT(s) FROM Stadium s
            LEFT JOIN s.owner o
            LEFT JOIN s.parentStadium p
            LEFT JOIN p.complex c
            LEFT JOIN c.owner co
            WHERE s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT
            AND (o.ownerId = :ownerId OR co.ownerId = :ownerId)
            AND s.stadiumStatus = :status
            """)
    long countByOwnerOwnerIdAndStadiumStatus(@Param("ownerId") Integer ownerId, @Param("status") StadiumStatus status);

    @EntityGraph(attributePaths = {"complex", "parentStadium", "parentStadium.complex", "sportType", "images", "owner", "owner.user", "amenities", "accessories", "timeSlots"})
    Optional<Stadium> findWithDetailsByStadiumId(Integer stadiumId);

    @EntityGraph(attributePaths = {"sportType", "images"})
    @Query("""
            SELECT s FROM Stadium s
            WHERE s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT
            AND s.stadiumStatus = com.sportvenue.entity.enums.StadiumStatus.AVAILABLE
            AND (:#{#excludeIds.size()} = 0 OR s.stadiumId NOT IN :excludeIds)
            ORDER BY s.averageRating DESC, s.stadiumName ASC
            """)
    List<Stadium> findRecommendedExcluding(
            @Param("excludeIds") List<Integer> excludeIds,
            Pageable pageable);

    @Query("""
            SELECT COUNT(s) FROM Stadium s
            LEFT JOIN s.parentStadium p
            LEFT JOIN p.complex c
            LEFT JOIN c.owner o
            LEFT JOIN o.user u
            WHERE s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT
            AND u.email = :ownerEmail
            """)
    long countStadiumsByOwnerEmail(@Param("ownerEmail") String ownerEmail);

    @Query("""
            SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM Stadium s
            LEFT JOIN s.parentStadium p
            LEFT JOIN p.sportType pst
            LEFT JOIN s.sportType st
            WHERE s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT
            AND (pst.sportTypeId = :sportTypeId OR st.sportTypeId = :sportTypeId)
            """)
    boolean existsBySportTypeSportTypeId(@Param("sportTypeId") Integer sportTypeId);

    @EntityGraph(attributePaths = {"parentStadium", "complex"})
    @Query("SELECT s FROM Stadium s WHERE s.parentStadium.stadiumId = :facilityId AND s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT")
    List<Stadium> findCourtsByFacilityId(@Param("facilityId") Integer facilityId);

    @EntityGraph(attributePaths = {"parentStadium", "complex"})
    @Query("SELECT s FROM Stadium s WHERE s.complex.complexId = :complexId AND s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT")
    List<Stadium> findCourtsByComplexId(@Param("complexId") Integer complexId);

    @EntityGraph(attributePaths = {"parentStadium", "complex"})
    @Query("SELECT s FROM Stadium s WHERE s.stadiumId IN :ids AND s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT")
    List<Stadium> findCourtsByIds(@Param("ids") List<Integer> ids);

    @EntityGraph(attributePaths = {"sportType", "images"})
    @Query("""
            SELECT s FROM Stadium s
            WHERE s.complex.complexId = :complexId
            AND s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.FACILITY
            """)
    List<Stadium> findFacilitiesByComplexId(@Param("complexId") Integer complexId);

    @EntityGraph(attributePaths = {"complex", "complex.owner", "complex.owner.user"})
    @Query("SELECT s FROM Stadium s WHERE s.stadiumId = :stadiumId")
    Optional<Stadium> findFacilityWithComplexDetails(@Param("stadiumId") Integer stadiumId);

    @EntityGraph(attributePaths = {"complex", "parentStadium", "sportType"})
    @Query("SELECT s FROM Stadium s WHERE s.stadiumId = :stadiumId")
    Optional<Stadium> findByIdWithComplexAndParent(@Param("stadiumId") Integer stadiumId);

    @Query("""
        SELECT s.complex.complexId, MIN(s.pricePerHour), MAX(s.pricePerHour)
        FROM Stadium s
        WHERE s.complex.complexId IN :complexIds
        AND s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT
        GROUP BY s.complex.complexId
        """)
    List<Object[]> findMinMaxPriceByComplexIds(@Param("complexIds") List<Integer> complexIds);

    /**
     * Fallback cho AI chat tool: {@link #searchByKeyword} chỉ so khớp tên Court/địa chỉ Complex,
     * không so khớp tên Facility cha (vd "Sân vận động Cẩm Lệ") — trong khi Court con thường được
     * đặt tên chung chung ("Sân 1", "Sân 2"). Người dùng chat thường gọi bằng tên Facility.
     */
    @EntityGraph(attributePaths = {"sportType", "images", "amenities", "parentStadium", "complex"})
    @Query("""
            SELECT s FROM Stadium s
            LEFT JOIN s.parentStadium p
            WHERE s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT
            AND s.stadiumStatus = com.sportvenue.entity.enums.StadiumStatus.AVAILABLE
            AND p IS NOT NULL
            AND LOWER(p.stadiumName) LIKE LOWER(CONCAT('%', :keyword, '%'))
            """)
    List<Stadium> findCourtsByParentFacilityNameKeyword(@Param("keyword") String keyword);

    /**
     * Projection nhẹ dùng để so khớp không phân biệt dấu tiếng Việt ở tầng Java (xem
     * StadiumSearchHandler) — khi model tự đánh sai dấu (vd "Sân vân đơng" thay vì
     * "Sân vận động") khiến LIKE match ở {@link #findCourtsByParentFacilityNameKeyword} thất bại.
     */
    interface CourtFacilityNameProjection {
        Integer getStadiumId();

        String getFacilityName();
    }

    @Query("""
            SELECT s.stadiumId AS stadiumId, p.stadiumName AS facilityName
            FROM Stadium s
            LEFT JOIN s.parentStadium p
            WHERE s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT
            AND s.stadiumStatus = com.sportvenue.entity.enums.StadiumStatus.AVAILABLE
            AND p IS NOT NULL
            """)
    List<CourtFacilityNameProjection> findAllCourtFacilityNames();

    /**
     * IDs đầu vào hiện đã được lọc AVAILABLE ở {@link #findAllCourtFacilityNames}, nhưng vẫn
     * filter lại ở đây (defense-in-depth) — tránh AI gợi ý sân bảo trì nếu status đổi giữa
     * 2 query hoặc method này được tái sử dụng từ path khác trong tương lai.
     */
    @EntityGraph(attributePaths = {"sportType", "images", "amenities", "parentStadium", "complex"})
    @Query("""
            SELECT s FROM Stadium s
            WHERE s.stadiumId IN :ids
            AND s.nodeType = com.sportvenue.entity.enums.StadiumNodeType.COURT
            AND s.stadiumStatus = com.sportvenue.entity.enums.StadiumStatus.AVAILABLE
            """)
    List<Stadium> findCourtsForAiToolByIds(@Param("ids") List<Integer> ids);
}
