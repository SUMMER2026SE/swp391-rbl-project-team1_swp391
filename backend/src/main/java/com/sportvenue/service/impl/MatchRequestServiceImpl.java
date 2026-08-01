package com.sportvenue.service.impl;

import com.sportvenue.dto.request.CreateMatchRequest;
import com.sportvenue.dto.response.MatchResponse;
import com.sportvenue.entity.MatchRequest;
import com.sportvenue.entity.SportType;
import com.sportvenue.entity.Stadium;
import com.sportvenue.entity.User;
import com.sportvenue.entity.enums.AccountStatus;
import com.sportvenue.entity.enums.MatchStatus;
import com.sportvenue.entity.enums.BookingStatus;
import com.sportvenue.exception.BadRequestException;
import com.sportvenue.exception.ResourceNotFoundException;
import com.sportvenue.repository.BookingRepository;
import com.sportvenue.repository.JoinRequestRepository;
import com.sportvenue.repository.MatchRequestRepository;
import com.sportvenue.repository.specification.MatchRequestSpecification;
import com.sportvenue.repository.SportTypeRepository;
import com.sportvenue.repository.StadiumRepository;
import com.sportvenue.repository.StadiumComplexRepository;
import com.sportvenue.repository.TimeSlotRepository;
import com.sportvenue.repository.UserRepository;
import com.sportvenue.repository.TimeSlotExceptionRepository;
import com.sportvenue.entity.TimeSlotException;
import com.sportvenue.entity.JoinRequest;
import com.sportvenue.entity.enums.JoinRequestStatus;
import java.util.Optional;
import com.sportvenue.dto.response.JoinRequestResponse;
import com.sportvenue.service.MaintenanceScheduleService;
import com.sportvenue.service.MatchRequestService;
import com.sportvenue.service.ChatService;
import com.sportvenue.service.CustomerNotificationService;
import com.sportvenue.util.StadiumUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service Implementation thực tế cho việc quản lý kèo ghép thể thao.
 * Thực hiện đầy đủ các ràng buộc nghiệp vụ của UC-CUS-10.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchRequestServiceImpl implements MatchRequestService {

    private final MatchRequestRepository matchRequestRepository;
    private final UserRepository userRepository;
    private final StadiumRepository stadiumRepository;
    private final SportTypeRepository sportTypeRepository;
    private final BookingRepository bookingRepository;
    private final JoinRequestRepository joinRequestRepository;
    private final ChatService chatService;
    private final StadiumComplexRepository stadiumComplexRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final TimeSlotExceptionRepository timeSlotExceptionRepository;
    private final MaintenanceScheduleService maintenanceScheduleService;
    private final CustomerNotificationService customerNotificationService;

    @Override
    @Transactional
    public MatchResponse createMatch(CreateMatchRequest request, Integer userId) {
        log.info("Creating match request: '{}' for user ID: {}", request.getTitle(), userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + userId));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BadRequestException("User account is not active");
        }

        com.sportvenue.entity.Booking booking = bookingRepository.findById(request.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with ID: " + request.getBookingId()));

        validateBookingForMatchCreation(booking, userId);

        Stadium stadium = booking.getStadium();
        SportType sportType = stadium.getSportType();
        if (sportType == null && stadium.getParentStadium() != null) {
            sportType = stadium.getParentStadium().getSportType();
        }
        LocalDate playDate = booking.getReservationDate();
        Optional<TimeSlotException> exceptionOpt = timeSlotExceptionRepository.findBySlotSlotIdAndExceptionDate(
                booking.getSlot().getSlotId(), playDate);
        LocalTime startTime = exceptionOpt
                .filter(e -> e.getStartTimeOverride() != null)
                .map(TimeSlotException::getStartTimeOverride)
                .orElse(booking.getSlot().getStartTime());
        LocalTime endTime = exceptionOpt
                .filter(e -> e.getEndTimeOverride() != null)
                .map(TimeSlotException::getEndTimeOverride)
                .orElse(booking.getSlot().getEndTime());

        checkOverlappingSchedules(userId, playDate, startTime, endTime);

        MatchRequest matchRequest = MatchRequest.builder()
                .user(user)
                .booking(booking)
                .stadium(stadium)
                .sportType(sportType)
                .title(request.getTitle())
                .description(request.getDescription())
                .playDate(playDate)
                .startTime(startTime)
                .endTime(endTime)
                .maxPlayers(request.getMaxPlayers())
                .currentPlayers(1)
                .skillLevel(request.getSkillLevel())
                .splitPrice(request.getSplitPrice())
                .pricePerPlayer(request.getPricePerPlayer() != null ? request.getPricePerPlayer() : BigDecimal.ZERO)
                .matchingType(request.getMatchingType())
                .matchStatus(MatchStatus.OPEN)
                .createdAt(LocalDateTime.now())
                .build();

        MatchRequest savedMatch = matchRequestRepository.save(matchRequest);
        log.info("Successfully created match request with ID: {}", savedMatch.getMatchId());

        chatService.createOrUpdateMatchGroupChat(savedMatch, user.getUserId());
        return mapToResponse(savedMatch);
    }

    private void validateBookingForMatchCreation(com.sportvenue.entity.Booking booking, Integer userId) {
        if (!booking.getUser().getUserId().equals(userId)) {
            throw new BadRequestException("Booking này không thuộc về bạn");
        }
        if (booking.getBookingStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("Chỉ có thể tạo kèo ghép cho lịch đặt sân đã được xác nhận");
        }
        LocalDateTime slotStart = LocalDateTime.of(booking.getReservationDate(), booking.getSlot().getStartTime());
        if (!slotStart.isAfter(LocalDateTime.now())) {
            throw new BadRequestException("Không thể tạo kèo ghép cho lịch đã diễn ra");
        }
        boolean hasActiveMatch = matchRequestRepository.existsByBookingBookingIdAndMatchStatusIn(
                booking.getBookingId(), Arrays.asList(MatchStatus.OPEN, MatchStatus.FULL));
        if (hasActiveMatch) {
            throw new BadRequestException("Booking này đã có kèo ghép đang mở, hãy huỷ kèo cũ trước khi tạo mới");
        }
    }

    private void checkOverlappingSchedules(Integer userId, LocalDate playDate, LocalTime startTime, LocalTime endTime) {
        boolean hasOverlappingMatch = matchRequestRepository.existsOverlappingMatchRequest(
                userId, playDate, startTime, endTime);
        if (hasOverlappingMatch) {
            throw new BadRequestException("Bạn đã có một kèo ghép đang mở trùng với khoảng thời gian này");
        }
        boolean hasApprovedOverlap = joinRequestRepository.existsApprovedOverlappingJoinRequest(
                userId, playDate, startTime, endTime);
        if (hasApprovedOverlap) {
            throw new BadRequestException("Bạn đã được chấp nhận tham gia một kèo ghép khác trùng với khoảng thời gian này");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public MatchResponse getMatch(Integer matchId) {
        log.info("Retrieving match detail for ID: {}", matchId);
        MatchRequest match = matchRequestRepository.findById(matchId)
                .orElseThrow(() -> new ResourceNotFoundException("Match request not found with ID: " + matchId));
        return mapToResponse(match);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MatchResponse> getActiveMatches(Pageable pageable) {
        log.info("Retrieving active match requests with pagination and future dates: {}", pageable);
        LocalDate nowDate = LocalDate.now();
        LocalTime nowTime = LocalTime.now();
        Page<MatchRequest> matches = matchRequestRepository.findActiveMatchesSorted(
                Arrays.asList(MatchStatus.OPEN, MatchStatus.FULL),
                nowDate,
                nowTime,
                pageable
        );
        return matches.map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MatchResponse> getActiveMatches(Pageable pageable, String location) {
        return getActiveMatches(pageable, location, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MatchResponse> getActiveMatches(Pageable pageable, String location, Integer sportTypeId) {
        log.info("Retrieving active match requests with pagination, location={}, sportTypeId={} and future dates: {}",
                location, sportTypeId, pageable);
        LocalDate nowDate = LocalDate.now();
        LocalTime nowTime = LocalTime.now();
        Page<MatchRequest> matches = matchRequestRepository.findAll(
                MatchRequestSpecification.withDynamicFilter(
                        Arrays.asList(MatchStatus.OPEN, MatchStatus.FULL),
                        nowDate,
                        nowTime,
                        location,
                        sportTypeId),
                pageable
        );
        return matches.map(this::mapToResponse);
    }

    @Override
    @Transactional
    public void joinMatch(Integer matchId, Integer userId, String message) {
        log.info("User ID {} requesting to join Match ID: {} with message: '{}'", userId, matchId, message);

        MatchRequest match = matchRequestRepository.findById(matchId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kèo ghép."));

        if (match.getMatchStatus() != MatchStatus.OPEN) {
            throw new BadRequestException("Kèo ghép này hiện không còn nhận thêm người tham gia.");
        }

        LocalDate nowDate = LocalDate.now();
        LocalTime nowTime = LocalTime.now();
        if (match.getPlayDate().isBefore(nowDate) || 
            (match.getPlayDate().isEqual(nowDate) && match.getStartTime().isBefore(nowTime))) {
            throw new BadRequestException("Kèo ghép này đã hết hạn đăng ký.");
        }

        if (match.getUser().getUserId().equals(userId)) {
            throw new BadRequestException("Bạn không thể tham gia kèo do chính mình tạo.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BadRequestException("Tài khoản của bạn hiện không hoạt động.");
        }

        boolean alreadyRequested = joinRequestRepository.existsByMatchRequestMatchIdAndUserUserIdAndRequestStatusIn(
                matchId,
                userId,
                Arrays.asList(JoinRequestStatus.PENDING, JoinRequestStatus.APPROVED)
        );
        if (alreadyRequested) {
            throw new BadRequestException("Bạn đã gửi yêu cầu tham gia kèo này rồi, vui lòng chờ xét duyệt.");
        }

        validateNoScheduleConflicts(userId, match);

        if (match.getMatchingType() == com.sportvenue.entity.enums.MatchingType.TEAM_VS_TEAM) {
            if (message == null || message.trim().isEmpty()) {
                throw new BadRequestException("Vui lòng nhập tên đội để đăng ký kèo đối đầu.");
            }
        }

        JoinRequest joinRequest = JoinRequest.builder()
                .matchRequest(match)
                .user(user)
                .requestStatus(JoinRequestStatus.PENDING)
                .message(message)
                .createdAt(LocalDateTime.now())
                .build();

        joinRequestRepository.save(joinRequest);
        log.info("Successfully created join request for User ID: {} on Match ID: {}", userId, matchId);

        try {
            customerNotificationService.notifyMatchRequestReceived(match.getUser().getUserId(), joinRequest);
        } catch (Exception ex) {
            log.warn("Failed to emit match request received notification for match {}", matchId, ex);
        }
    }

    private void validateNoScheduleConflicts(Integer userId, MatchRequest match) {
        boolean hasOverlappingMatch = matchRequestRepository.existsOverlappingMatchRequest(
                userId, match.getPlayDate(), match.getStartTime(), match.getEndTime());
        if (hasOverlappingMatch) {
            throw new BadRequestException("Bạn đã có kèo ghép khác trùng khung giờ này, không thể đăng ký thêm.");
        }

        boolean hasApprovedOverlap = joinRequestRepository.existsApprovedOverlappingJoinRequest(
                userId, match.getPlayDate(), match.getStartTime(), match.getEndTime());
        if (hasApprovedOverlap) {
            throw new BadRequestException("Bạn đã được chấp nhận tham gia kèo khác trong khung giờ này.");
        }

        boolean hasOverlappingBooking = bookingRepository.existsOverlappingBooking(
                userId, match.getPlayDate(), match.getStartTime(), match.getEndTime());
        if (hasOverlappingBooking) {
            throw new BadRequestException("Bạn đã có lịch đặt sân trùng với khung giờ của kèo này.");
        }
    }



    @Override
    @Transactional(readOnly = true)
    public List<JoinRequestResponse> getJoinRequestsForMatch(Integer matchId, Integer hostUserId) {
        log.info("Host User ID {} retrieving join requests for Match ID: {}", hostUserId, matchId);

        MatchRequest match = matchRequestRepository.findById(matchId)
                .orElseThrow(() -> new ResourceNotFoundException("Match request not found with ID: " + matchId));

        if (!match.getUser().getUserId().equals(hostUserId)) {
            throw new BadRequestException("Only the host of this match can view join requests");
        }

        List<JoinRequest> requests = joinRequestRepository.findAllByMatchRequestMatchId(matchId);
        return requests.stream()
                .map(req -> JoinRequestResponse.builder()
                        .joinId(req.getJoinId())
                        .matchId(req.getMatchRequest().getMatchId())
                        .userId(req.getUser().getUserId())
                        .fullName(req.getUser().getFullName())
                        .email(req.getUser().getEmail())
                        .requestStatus(req.getRequestStatus())
                        .message(req.getMessage())
                        .createdAt(req.getCreatedAt())
                        .matchTitle(req.getMatchRequest().getTitle())
                        .stadiumName(req.getMatchRequest().getStadium() != null ? req.getMatchRequest().getStadium().getStadiumName() :
                                (req.getMatchRequest().getPreferredCourt() != null ? req.getMatchRequest().getPreferredCourt().getStadiumName() :
                                        (req.getMatchRequest().getPreferredFacility() != null ? req.getMatchRequest().getPreferredFacility().getStadiumName() :
                                                (req.getMatchRequest().getComplex() != null ? req.getMatchRequest().getComplex().getName() : null))))
                        .complexName(req.getMatchRequest().getStadium() != null ? StadiumUtils.resolveComplexName(req.getMatchRequest().getStadium()) :
                                (req.getMatchRequest().getPreferredCourt() != null ? StadiumUtils.resolveComplexName(req.getMatchRequest().getPreferredCourt()) :
                                        (req.getMatchRequest().getPreferredFacility() != null ? StadiumUtils.resolveComplexName(req.getMatchRequest().getPreferredFacility()) :
                                                (req.getMatchRequest().getComplex() != null ? req.getMatchRequest().getComplex().getName() : null))))
                        .sportName(req.getMatchRequest().getSportType().getSportName())
                        .playDate(req.getMatchRequest().getPlayDate())
                        .startTime(req.getMatchRequest().getStartTime())
                        .endTime(req.getMatchRequest().getEndTime())
                        .hostName(req.getMatchRequest().getUser().getFullName())
                        .hostEmail(req.getMatchRequest().getUser().getEmail())
                        .hostUserId(req.getMatchRequest().getUser().getUserId())
                        .matchStatus(req.getMatchRequest().getMatchStatus())
                        .matchingType(req.getMatchRequest().getMatchingType())
                        .cancelReason(req.getMatchRequest().getCancelReason())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void approveJoinRequest(Integer matchId, Integer joinId, Integer hostUserId) {
        log.info("Host User ID {} approving Join ID: {} for Match ID: {}", hostUserId, joinId, matchId);

        MatchRequest match = matchRequestRepository.findById(matchId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kèo ghép."));

        if (!match.getUser().getUserId().equals(hostUserId)) {
            throw new BadRequestException("Chỉ chủ kèo mới có thể duyệt yêu cầu tham gia.");
        }

        if (match.getMatchStatus() != MatchStatus.OPEN) {
            throw new BadRequestException("Kèo ghép này không còn nhận thêm người.");
        }

        JoinRequest joinRequest = joinRequestRepository.findById(joinId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu tham gia."));

        if (!joinRequest.getMatchRequest().getMatchId().equals(matchId)) {
            throw new BadRequestException("Yêu cầu tham gia không thuộc kèo này.");
        }

        if (joinRequest.getRequestStatus() != JoinRequestStatus.PENDING) {
            throw new BadRequestException("Yêu cầu này đã được xử lý rồi.");
        }

        if (match.getCurrentPlayers() >= match.getMaxPlayers()) {
            throw new BadRequestException("Kèo đã đủ người, không thể chấp nhận thêm.");
        }

        joinRequest.setRequestStatus(JoinRequestStatus.APPROVED);
        joinRequestRepository.saveAndFlush(joinRequest);

        try {
            customerNotificationService.notifyMatchRequestApproved(joinRequest.getUser().getUserId(), joinRequest);
        } catch (Exception ex) {
            log.warn("Failed to emit match request approved notification for join request {}", joinRequest.getJoinId(), ex);
        }

        // Atomic increment để tránh race condition khi approve đồng thời
        int updated = matchRequestRepository.incrementCurrentPlayers(matchId);
        if (updated == 0) {
            throw new BadRequestException("Kèo đã đủ người, không thể chấp nhận thêm.");
        }

        // Reload để lấy currentPlayers mới nhất sau atomic update
        MatchRequest updatedMatch = matchRequestRepository.findById(matchId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kèo ghép."));

        if (updatedMatch.getCurrentPlayers().equals(updatedMatch.getMaxPlayers())) {
            matchRequestRepository.updateStatusAndReason(matchId, MatchStatus.FULL, updatedMatch.getCancelReason());
            log.info("Match ID: {} is now FULL. Auto-rejecting remaining pending requests.", matchId);
            joinRequestRepository.bulkUpdateStatus(
                    matchId,
                    JoinRequestStatus.REJECTED,
                    Arrays.asList(JoinRequestStatus.PENDING)
            );
        }

        // Create or update group chat
        chatService.createOrUpdateMatchGroupChat(updatedMatch, joinRequest.getUser().getUserId());
    }

    @Override
    @Transactional
    public void rejectJoinRequest(Integer matchId, Integer joinId, Integer hostUserId) {
        log.info("Host User ID {} rejecting Join ID: {} for Match ID: {}", hostUserId, joinId, matchId);

        MatchRequest match = matchRequestRepository.findById(matchId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kèo ghép."));

        if (!match.getUser().getUserId().equals(hostUserId)) {
            throw new BadRequestException("Chỉ chủ kèo mới có thể từ chối yêu cầu tham gia.");
        }

        if (match.getMatchStatus() != MatchStatus.OPEN) {
            throw new BadRequestException("Kèo ghép này không còn nhận thêm người.");
        }

        JoinRequest joinRequest = joinRequestRepository.findById(joinId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu tham gia."));

        if (!joinRequest.getMatchRequest().getMatchId().equals(matchId)) {
            throw new BadRequestException("Yêu cầu tham gia không thuộc kèo này.");
        }

        if (joinRequest.getRequestStatus() != JoinRequestStatus.PENDING) {
            throw new BadRequestException("Yêu cầu này đã được xử lý rồi.");
        }

        joinRequest.setRequestStatus(JoinRequestStatus.REJECTED);
        joinRequestRepository.save(joinRequest);

        try {
            customerNotificationService.notifyMatchRequestRejected(joinRequest.getUser().getUserId(), joinRequest);
        } catch (Exception ex) {
            log.warn("Failed to emit match request rejected notification for join request {}", joinRequest.getJoinId(), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MatchResponse> getMyCreatedMatches(Integer userId, Pageable pageable) {
        log.info("Retrieving matches created by User ID: {}", userId);
        return matchRequestRepository.findAllByUserUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<JoinRequestResponse> getMyJoinedRequests(String email, Pageable pageable) {
        log.info("Retrieving join requests sent by user email: {}", email);
        return joinRequestRepository.findAllByUserEmailOrderByCreatedAtDesc(email, pageable)
                .map(req -> JoinRequestResponse.builder()
                        .joinId(req.getJoinId())
                        .matchId(req.getMatchRequest().getMatchId())
                        .userId(req.getUser().getUserId())
                        .fullName(req.getUser().getFullName())
                        .email(req.getUser().getEmail())
                        .requestStatus(req.getRequestStatus())
                        .message(req.getMessage())
                        .createdAt(req.getCreatedAt())
                        .matchTitle(req.getMatchRequest().getTitle())
                        .stadiumName(req.getMatchRequest().getStadium() != null ? req.getMatchRequest().getStadium().getStadiumName() :
                                (req.getMatchRequest().getPreferredCourt() != null ? req.getMatchRequest().getPreferredCourt().getStadiumName() :
                                        (req.getMatchRequest().getPreferredFacility() != null ? req.getMatchRequest().getPreferredFacility().getStadiumName() :
                                                (req.getMatchRequest().getComplex() != null ? req.getMatchRequest().getComplex().getName() : null))))
                        .complexName(req.getMatchRequest().getStadium() != null ? StadiumUtils.resolveComplexName(req.getMatchRequest().getStadium()) :
                                (req.getMatchRequest().getPreferredCourt() != null ? StadiumUtils.resolveComplexName(req.getMatchRequest().getPreferredCourt()) :
                                        (req.getMatchRequest().getPreferredFacility() != null ? StadiumUtils.resolveComplexName(req.getMatchRequest().getPreferredFacility()) :
                                                (req.getMatchRequest().getComplex() != null ? req.getMatchRequest().getComplex().getName() : null))))
                        .sportName(req.getMatchRequest().getSportType().getSportName())
                        .playDate(req.getMatchRequest().getPlayDate())
                        .startTime(req.getMatchRequest().getStartTime())
                        .endTime(req.getMatchRequest().getEndTime())
                        .hostName(req.getMatchRequest().getUser().getFullName())
                        .hostEmail(req.getMatchRequest().getUser().getEmail())
                        .hostUserId(req.getMatchRequest().getUser().getUserId())
                        .matchStatus(req.getMatchRequest().getMatchStatus())
                        .matchingType(req.getMatchRequest().getMatchingType())
                        .cancelReason(req.getMatchRequest().getCancelReason())
                        .build());
    }

    @Override
    @Transactional
    public void cancelMatch(Integer matchId, Integer userId, String reason) {
        log.info("User ID {} requesting to cancel Match ID: {} with reason: {}", userId, matchId, reason);

        MatchRequest match = matchRequestRepository.findById(matchId)
                .orElseThrow(() -> new ResourceNotFoundException("Match request not found with ID: " + matchId));

        // 1. Kiểm tra quyền sở hữu (Phải là Host)
        if (!match.getUser().getUserId().equals(userId)) {
            throw new BadRequestException("Only the host of this match can cancel it");
        }

        // 2. Kiểm tra trạng thái hợp lệ để hủy
        if (match.getMatchStatus() != MatchStatus.OPEN && match.getMatchStatus() != MatchStatus.FULL) {
            throw new BadRequestException("Match request cannot be cancelled in its current status: " + match.getMatchStatus());
        }

        // 3. Kiểm tra thời gian (Kèo chưa diễn ra)
        LocalDate nowDate = LocalDate.now();
        LocalTime nowTime = LocalTime.now();
        if (match.getPlayDate().isBefore(nowDate) || 
            (match.getPlayDate().isEqual(nowDate) && match.getStartTime().isBefore(nowTime))) {
            throw new BadRequestException("Cannot cancel a match that has already started or finished");
        }

        // 4. Cập nhật trạng thái kèo sang CANCELLED và lý do hủy
        String finalReason = (reason == null || reason.trim().isEmpty()) ? "Chủ nhà không cung cấp lý do cụ thể" : reason.trim();
        matchRequestRepository.updateStatusAndReason(matchId, MatchStatus.CANCELLED, finalReason);

        // 5. Bulk update các JoinRequest liên quan sang CANCELLED
        int affectedRows = joinRequestRepository.bulkUpdateStatus(
                matchId,
                JoinRequestStatus.CANCELLED,
                Arrays.asList(JoinRequestStatus.PENDING, JoinRequestStatus.APPROVED)
        );

        // 6. Gửi thông báo hủy kèo cho TẤT CẢ người tham gia (APPROVED/PENDING) — KHÔNG gửi cho host
        // (host là người vừa thực hiện hành động hủy, không cần nhận thông báo về hành động của
        // chính mình). Những người tham gia mới là đối tượng bị ảnh hưởng và cần biết lịch của
        // họ vừa mất.
        try {
            List<JoinRequest> joinRequests = joinRequestRepository.findAllByMatchRequestMatchId(matchId);
            for (JoinRequest jr : joinRequests) {
                if (jr.getRequestStatus() == JoinRequestStatus.APPROVED
                        || jr.getRequestStatus() == JoinRequestStatus.PENDING) {
                    customerNotificationService.notifyMatchCancelled(jr.getUser().getUserId(), match);
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to emit match cancelled notification for match {}", matchId, ex);
        }

        log.info("Successfully cancelled Match ID: {}. Affected {} join requests.", matchId, affectedRows);
    }

    private MatchResponse mapToResponse(MatchRequest match) {
        return MatchResponse.builder()
                .matchId(match.getMatchId())
                .hostName(match.getUser().getFullName())
                .hostUserId(match.getUser().getUserId())
                .stadiumName(match.getStadium() != null ? match.getStadium().getStadiumName() :
                        (match.getPreferredCourt() != null ? match.getPreferredCourt().getStadiumName() :
                                (match.getPreferredFacility() != null ? match.getPreferredFacility().getStadiumName() :
                                        (match.getComplex() != null ? match.getComplex().getName() : null))))
                .complexName(match.getStadium() != null ? StadiumUtils.resolveComplexName(match.getStadium()) :
                        (match.getPreferredCourt() != null ? StadiumUtils.resolveComplexName(match.getPreferredCourt()) :
                                (match.getPreferredFacility() != null ? StadiumUtils.resolveComplexName(match.getPreferredFacility()) :
                                        (match.getComplex() != null ? match.getComplex().getName() : null))))
                .stadiumAddress(match.getStadium() != null && match.getStadium().getAddress() != null ? match.getStadium().getAddress() :
                        (match.getComplex() != null ? match.getComplex().getAddress() : null))
                .sportName(match.getSportType().getSportName())
                .title(match.getTitle())
                .description(match.getDescription())
                .playDate(match.getPlayDate())
                .startTime(match.getStartTime())
                .endTime(match.getEndTime())
                .maxPlayers(match.getMaxPlayers())
                .currentPlayers(match.getCurrentPlayers())
                .skillLevel(match.getSkillLevel())
                .splitPrice(match.getSplitPrice())
                .pricePerPlayer(match.getPricePerPlayer())
                .matchStatus(match.getMatchStatus())
                .matchingType(match.getMatchingType())
                .cancelReason(match.getCancelReason())
                .createdAt(match.getCreatedAt())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.sportvenue.dto.response.MatchEligibleBookingResponse> getEligibleBookingsForMatchCreation(Integer userId) {
        log.info("Retrieving eligible bookings for match creation for User ID: {}", userId);
        LocalDate today = LocalDate.now();
        LocalTime nowTime = LocalTime.now();
        List<com.sportvenue.entity.Booking> eligibleBookings = bookingRepository.findEligibleForMatchCreation(userId, today, nowTime);
        
        return eligibleBookings.stream().map(b -> {
            Optional<TimeSlotException> exceptionOpt = timeSlotExceptionRepository.findBySlotSlotIdAndExceptionDate(
                    b.getSlot().getSlotId(), b.getReservationDate());
            LocalTime start = exceptionOpt
                    .filter(e -> e.getStartTimeOverride() != null)
                    .map(TimeSlotException::getStartTimeOverride)
                    .orElse(b.getSlot().getStartTime());
            LocalTime end = exceptionOpt
                    .filter(e -> e.getEndTimeOverride() != null)
                    .map(TimeSlotException::getEndTimeOverride)
                    .orElse(b.getSlot().getEndTime());

            return com.sportvenue.dto.response.MatchEligibleBookingResponse.builder()
                    .bookingId(b.getBookingId())
                    .stadiumName(b.getStadium().getStadiumName())
                    .complexName(StadiumUtils.resolveComplexName(b.getStadium()))
                    .address(b.getStadium().getAddress())
                    .sportName(StadiumUtils.resolveSportName(b.getStadium()))
                    .playDate(b.getReservationDate())
                    .startTime(start)
                    .endTime(end)
                    .build();
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void cancelMatchByBookingCancellation(Integer bookingId) {
        // Tìm kèo đang active (OPEN/FULL) gắn với booking này
        Optional<MatchRequest> matchOpt = matchRequestRepository.findActiveByBookingId(bookingId);
        if (matchOpt.isEmpty()) {
            log.debug("No active match found for booking #{} — skip match auto-cancel.", bookingId);
            return;
        }

        MatchRequest match = matchOpt.get();
        Integer matchId = match.getMatchId();
        String reason = "Chủ kèo đã hủy đặt sân, kèo đấu bị hủy tự động.";

        log.info("[AUTO-CANCEL] Booking #{} cancelled → auto-cancelling Match #{} (status={})",
                bookingId, matchId, match.getMatchStatus());

        // 1. Cập nhật trạng thái kèo sang CANCELLED
        matchRequestRepository.updateStatusAndReason(matchId, MatchStatus.CANCELLED, reason);

        // 2. Bulk update các JoinRequest liên quan sang CANCELLED
        int affectedRows = joinRequestRepository.bulkUpdateStatus(
                matchId,
                JoinRequestStatus.CANCELLED,
                Arrays.asList(JoinRequestStatus.PENDING, JoinRequestStatus.APPROVED)
        );

        // 3. Reload match để có cancelReason mới nhất cho notification
        match.setCancelReason(reason);

        // 4. Gửi thông báo đến tất cả người tham gia (APPROVED/PENDING) — KHÔNG gửi cho host
        try {
            List<JoinRequest> joinRequests = joinRequestRepository.findAllByMatchRequestMatchId(matchId);
            for (JoinRequest jr : joinRequests) {
                if (jr.getRequestStatus() == JoinRequestStatus.APPROVED
                        || jr.getRequestStatus() == JoinRequestStatus.PENDING) {
                    customerNotificationService.notifyMatchCancelled(jr.getUser().getUserId(), match);
                }
            }
        } catch (Exception ex) {
            log.warn("[AUTO-CANCEL] Failed to send match cancelled notifications for match #{}", matchId, ex);
        }

        log.info("[AUTO-CANCEL] Match #{} auto-cancelled due to booking #{} cancellation. Affected {} join requests.",
                matchId, bookingId, affectedRows);
    }
}
