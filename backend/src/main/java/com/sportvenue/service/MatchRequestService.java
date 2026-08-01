package com.sportvenue.service;

import com.sportvenue.dto.request.CreateMatchRequest;
import com.sportvenue.dto.response.MatchResponse;
import com.sportvenue.dto.response.JoinRequestResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;

/**
 * Service Interface cho việc quản lý kèo ghép thể thao (Matchmaking).
 * Tuân thủ quy tắc lập trình hướng giao diện (Entity -> Repository -> Service Interface -> ServiceImpl -> Controller).
 */
public interface MatchRequestService {

    /**
     * Tạo một kèo ghép mới.
     *
     * @param request thông tin kèo ghép từ client
     * @param userId ID của người tạo kèo (Host)
     * @return MatchResponse thông tin kèo ghép vừa tạo
     */
    MatchResponse createMatch(CreateMatchRequest request, Integer userId);

    /**
     * Lấy thông tin chi tiết của một kèo ghép theo ID.
     *
     * @param matchId ID của kèo ghép
     * @return MatchResponse thông tin kèo ghép
     */
    MatchResponse getMatch(Integer matchId);

    /**
     * Lấy danh sách các kèo ghép đang mở (OPEN) có phân trang.
     *
     * @param pageable thông tin phân trang
     * @return Page<MatchResponse> danh sách kèo ghép phân trang
     */
    Page<MatchResponse> getActiveMatches(Pageable pageable);

    /**
     * Lấy danh sách các kèo ghép đang mở (OPEN), lọc theo khu vực/thành phố (khớp gần đúng
     * với địa chỉ sân hoặc tổ hợp sân). Truyền {@code null} để không lọc theo khu vực.
     *
     * @param pageable thông tin phân trang
     * @param location từ khoá khu vực/thành phố (vd "Đà Nẵng", "Quận 9"), có thể null
     * @return Page<MatchResponse> danh sách kèo ghép phân trang
     */
    Page<MatchResponse> getActiveMatches(Pageable pageable, String location);

    /**
     * Lấy danh sách các kèo ghép đang mở (OPEN), lọc theo khu vực và/hoặc môn thể thao.
     * Truyền {@code null} cho tham số nào không cần lọc.
     *
     * @param pageable thông tin phân trang
     * @param location từ khoá khu vực/thành phố (vd "Đà Nẵng", "Quận 9"), có thể null
     * @param sportTypeId ID môn thể thao muốn lọc, có thể null
     * @return Page<MatchResponse> danh sách kèo ghép phân trang
     */
    Page<MatchResponse> getActiveMatches(Pageable pageable, String location, Integer sportTypeId);

    /**
     * Gửi yêu cầu xin tham gia vào một kèo ghép.
     *
     * @param matchId ID của kèo ghép muốn tham gia
     * @param userId ID của người xin tham gia
     * @param message Lời nhắn đính kèm
     */
    void joinMatch(Integer matchId, Integer userId, String message);

    /**
     * Lấy danh sách các yêu cầu tham gia của một kèo (chỉ Host của kèo mới xem được).
     *
     * @param matchId ID của kèo ghép
     * @param hostUserId ID của người dùng gọi API (bắt buộc phải là Host của kèo)
     * @return danh sách yêu cầu tham gia
     */
    List<JoinRequestResponse> getJoinRequestsForMatch(Integer matchId, Integer hostUserId);

    /**
     * Phê duyệt yêu cầu tham gia của người chơi (chỉ Host mới thực hiện được).
     *
     * @param matchId ID của kèo ghép
     * @param joinId ID của yêu cầu tham gia cần duyệt
     * @param hostUserId ID của người dùng gọi API (Host)
     */
    void approveJoinRequest(Integer matchId, Integer joinId, Integer hostUserId);

    /**
     * Từ chối yêu cầu tham gia của người chơi (chỉ Host mới thực hiện được).
     *
     * @param matchId ID của kèo ghép
     * @param joinId ID của yêu cầu tham gia cần từ chối
     * @param hostUserId ID của người dùng gọi API (Host)
     */
    void rejectJoinRequest(Integer matchId, Integer joinId, Integer hostUserId);

    /**
     * Lấy danh sách các kèo ghép mà người dùng đã tạo (với tư cách Host).
     *
     * @param userId ID của người dùng
     * @return danh sách kèo ghép đã tạo
     */
    Page<MatchResponse> getMyCreatedMatches(Integer userId, Pageable pageable);

    /**
     * Lấy danh sách các đơn đăng ký tham gia kèo mà người dùng đã gửi (với tư cách Guest).
     *
     * @param email Email của người dùng
     * @return danh sách đơn đăng ký
     */
    Page<JoinRequestResponse> getMyJoinedRequests(String email, Pageable pageable);

    /**
     * Hủy kèo ghép (chỉ Host mới thực hiện được).
     *
     * @param matchId ID của kèo cần hủy
     * @param userId ID của người thực hiện hủy (phải là Host)
     * @param reason Lý do hủy kèo
     */
    void cancelMatch(Integer matchId, Integer userId, String reason);

    /**
     * Lấy danh sách booking đủ điều kiện tạo kèo ghép.
     *
     * @param userId ID của người dùng
     * @return danh sách MatchEligibleBookingResponse
     */
    List<com.sportvenue.dto.response.MatchEligibleBookingResponse> getEligibleBookingsForMatchCreation(Integer userId);

    /**
     * Tự động hủy kèo đang active gắn với booking khi booking đó bị hủy.
     * Khác với {@link #cancelMatch} — không cần kiểm tra quyền sở hữu vì
     * đây là hành động hệ thống triggered bởi luồng hủy booking.
     * Gửi thông báo "Chủ kèo đã hủy đặt sân" tới tất cả người đã tham gia.
     *
     * @param bookingId ID của booking vừa bị hủy
     */
    void cancelMatchByBookingCancellation(Integer bookingId);
}

