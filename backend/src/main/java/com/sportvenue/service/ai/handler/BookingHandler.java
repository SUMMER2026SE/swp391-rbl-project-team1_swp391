package com.sportvenue.service.ai.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.dto.response.DraftBookingResponse;
import com.sportvenue.dto.response.TimeSlotResponse;
import com.sportvenue.entity.Stadium;
import com.sportvenue.entity.enums.StadiumNodeType;
import com.sportvenue.entity.enums.StadiumStatus;
import com.sportvenue.repository.SportTypeRepository;
import com.sportvenue.repository.StadiumRepository;
import com.sportvenue.repository.UserRepository;
import com.sportvenue.service.BookingService;
import com.sportvenue.util.RelativeDateParser;
import com.sportvenue.service.MaintenanceScheduleService;
import com.sportvenue.service.ai.AiConversationContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Xử lý intent "create_booking" — đặt sân trực tiếp từ chat.
 * Yêu cầu: người dùng phải đăng nhập và đã xác định được sân + slot + ngày.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingHandler {

    private final BookingService bookingService;
    private final StadiumRepository stadiumRepository;
    private final UserRepository userRepository;
    private final com.sportvenue.repository.BookingRepository bookingRepository;
    private final MaintenanceScheduleService maintenanceScheduleService;
    private final AiConversationContextService conversationContextService;
    private final SportTypeRepository sportTypeRepository;
    private final RelativeDateParser relativeDateParser;

    private Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Xử lý yêu cầu đặt sân từ chat.
     *
     * @param args JSON params từ LLM với các trường:
     *             - stadiumId (Integer): ID sân, hoặc
     *             - targetIndex (Integer): chỉ số sân đã hiển thị gần nhất (0-based)
     *             - slotId (Integer): ID khung giờ, hoặc
     *             - slotIndex (Integer): chỉ số khung giờ đã hiển thị (0-based)
     *             - date (String): ngày đặt (YYYY-MM-DD), mặc định hôm nay
     *             - note (String, optional): ghi chú cho sân
     * @param llmMessage message từ LLM
     * @param conversationKey key của cuộc hội thoại
     * @param userId ID của user đang đăng nhập
     * @return AiChatTurnResponse chứa kết quả đặt sân
     */
    /**
     * Kết quả của fallback search khi resolveStadiumId() fail.
     * - foundStadiums: danh sách sân tìm được (rỗng nếu không có sân phù hợp)
     * - singleStadiumId: nếu đúng 1 sân, trả về ID để tiếp tục flow bình thường
     * - needsUserSelection: true nếu cần user chọn từ danh sách
     * - searchErrorMessage: message lỗi nếu search fail
     */
    private record SearchFallbackResult(
            List<Stadium> foundStadiums,
            Integer singleStadiumId,
            boolean needsUserSelection,
            String searchErrorMessage
    ) {
        static SearchFallbackResult multiple(List<Stadium> stadiums) {
            return new SearchFallbackResult(stadiums, null, true, null);
        }

        static SearchFallbackResult single(Stadium stadium) {
            return new SearchFallbackResult(List.of(stadium), stadium.getStadiumId(), false, null);
        }

        static SearchFallbackResult none(String message) {
            return new SearchFallbackResult(List.of(), null, false, message);
        }

        static SearchFallbackResult error(String message) {
            return new SearchFallbackResult(List.of(), null, false, message);
        }
    }

    public AiChatTurnResponse handle(JsonNode args, String llmMessage, String conversationKey, Integer userId) {
        return handleWithRawMessage(args, llmMessage, conversationKey, userId, llmMessage);
    }

    /**
     * Xử lý yêu cầu đặt sân với raw user message để parse relative date.
     */
    public AiChatTurnResponse handleWithRawMessage(JsonNode args, String llmMessage, String conversationKey, Integer userId, String rawUserMessage) {
        if (userId == null) {
            return errorResponse("Bạn cần đăng nhập để đặt sân qua chat. Vui lòng đăng nhập và thử lại.");
        }

        // --- MERGE PENDING ACTION ---
        mergePendingState(args, conversationKey);
        // -----------------------------

        if (args == null || args.isNull() || args.isMissingNode()) {
            return errorResponse("Chưa xác định được sân bạn muốn đặt. Bạn muốn đặt sân nào? (Vd: sân bóng đá Thủ Đức)");
        }

        Integer stadiumId = resolveStadiumId(args, conversationKey);

        // Nếu không resolve được stadiumId từ context, thử fallback search
        if (stadiumId == null) {
            StadiumIdResolution resolution = resolveStadiumIdFallback(args, conversationKey, llmMessage);
            if (resolution.earlyResponse() != null) {
                return resolution.earlyResponse();
            }
            stadiumId = resolution.stadiumId();
        }
        if (stadiumId <= 0) {
            return errorResponse("ID sân không hợp lệ. Hãy tìm sân trước để lấy đúng sân bạn muốn đặt.");
        }

        // Validate stadium exists and is bookable
        Optional<Stadium> stadiumOpt = stadiumRepository.findById(stadiumId);
        if (stadiumOpt.isEmpty()) {
            return errorResponse("Không tìm thấy sân với ID " + stadiumId + ". Hãy tìm sân trước.");
        }
        Stadium stadium = stadiumOpt.get();
        if (stadium.getNodeType() != StadiumNodeType.COURT) {
            return errorResponse("Sân này không phải là sân lẻ có thể đặt lịch. Hãy chọn một sân cụ thể.");
        }
        if (stadium.getStadiumStatus() != StadiumStatus.AVAILABLE) {
            return errorResponse("Sân này hiện không khả dụng để đặt (đang bảo trì hoặc tạm đóng). Bạn có thể tìm sân khác.");
        }

        LocalDate requestedDate = resolveDateWithValidation(args, rawUserMessage, conversationKey);

        conversationContextService.saveCurrentFilters(conversationKey, null, null, null, requestedDate.toString(), null);

        if (requestedDate.isBefore(LocalDate.now(clock))) {
            return errorResponse("Không thể đặt sân cho ngày trong quá khứ.");
        }

        // Nếu target date là hôm nay mà giờ target đã qua → không nên tạo draft cho khung giờ đã qua
        // (AI sẽ trigger sibling fallback hoặc confirm draft cho slot đã qua, gây trang /booking/new
        // hiển thị lỗi "slot vừa có người đặt" do `getSlotsByDate` đánh `available=false` với giờ đã qua).
        if (requestedDate.isEqual(LocalDate.now(clock))) {
            java.time.LocalTime nowVietnam = java.time.LocalTime.now(clock);
            java.util.Optional<java.time.LocalTime> targetStartFromArgs = java.util.Optional.empty();
            if (args.hasNonNull("startTime")) {
                try { targetStartFromArgs = java.util.Optional.of(java.time.LocalTime.parse(args.get("startTime").asText())); }
                catch (Exception ignored) {}
            }
            if (targetStartFromArgs.isPresent() && !targetStartFromArgs.get().isAfter(nowVietnam)) {
                return errorResponse(String.format(
                        "Khung giờ %s hôm nay đã qua (hiện tại %s). Vui lòng chọn khung giờ khác hoặc ngày khác.",
                        targetStartFromArgs.get(),
                        nowVietnam));
            }
        }

        // Check maintenance
        if (maintenanceScheduleService.isStadiumUnderMaintenance(stadium, requestedDate)) {
            return errorResponse("Sân này có lịch bảo trì vào ngày " + requestedDate + ", không thể đặt. Bạn có thể chọn ngày khác hoặc sân khác.");
        }

        // Resolve slot
        List<TimeSlotResponse> slots = bookingService.getSlotsByDate(stadiumId, requestedDate);
        TimeSlotResponse targetSlot = resolveSlot(args, conversationKey, slots);

        if (targetSlot == null) {
            return handleMissingSlot(args, conversationKey, stadiumId, requestedDate);
        }

        // checkSlotAvailability merged into sibling-fallback so full court → sibling
        AiChatTurnResponse slotResponse = checkSlotAvailableWithSiblingFallback(
                stadium, targetSlot, slots, requestedDate, conversationKey);
        if (slotResponse != null) {
            return slotResponse;
        }

        AiChatTurnResponse duplicateResponse = checkDuplicateBookings(userId, targetSlot.getSlotId(), requestedDate);
        if (duplicateResponse != null) {
            return duplicateResponse;
        }

        return createConfirmBookingResponse(stadium, requestedDate, targetSlot, conversationKey);
    }

    /** Kết quả resolve stadiumId từ targetIndex/fallback search — hoặc 1 response để trả ngay. */
    private record StadiumIdResolution(Integer stadiumId, AiChatTurnResponse earlyResponse) {
        static StadiumIdResolution of(int id) {
            return new StadiumIdResolution(id, null);
        }

        static StadiumIdResolution earlyReturn(AiChatTurnResponse response) {
            return new StadiumIdResolution(null, response);
        }
    }

    /**
     * Thử resolve stadiumId khi resolveStadiumId() không tìm được trực tiếp — ưu tiên targetIndex
     * từ context đã hiển thị, nếu không có thì fallback search theo params đã trích xuất.
     */
    private StadiumIdResolution resolveStadiumIdFallback(JsonNode args, String conversationKey, String llmMessage) {
        if (args.hasNonNull("targetIndex")) {
            List<Integer> lastShown = conversationContextService.getLastShownStadiumIds(conversationKey);
            int targetIndex = args.get("targetIndex").asInt();

            // Check if it's a venueId (number in list)
            if (lastShown != null && lastShown.contains(targetIndex)) {
                return StadiumIdResolution.of(targetIndex);
            }
            // Check if it's a valid position index
            if (lastShown != null && targetIndex >= 0 && targetIndex < lastShown.size()) {
                return StadiumIdResolution.of(lastShown.get(targetIndex));
            }
            // Ambiguous - user gave a number but it doesn't match anything meaningful
            String clarificationMsg = buildClarificationMessage(targetIndex, lastShown);
            return StadiumIdResolution.earlyReturn(AiChatTurnResponse.builder()
                    .message(clarificationMsg)
                    .intent("need_more_info")
                    .build());
        }

        // Thử fallback search nếu có đủ thông tin để tìm sân
        SearchFallbackResult fallback = searchStadiumsFallback(args, conversationKey);

        if (fallback.needsUserSelection()) {
            // Nhiều sân → hiển thị danh sách cho user chọn
            return StadiumIdResolution.earlyReturn(
                    buildStadiumSelectionResponse(fallback.foundStadiums(), conversationKey, llmMessage));
        }
        if (fallback.singleStadiumId() != null) {
            // Đúng 1 sân → tiếp tục với sân này
            return StadiumIdResolution.of(fallback.singleStadiumId());
        }
        if (fallback.searchErrorMessage() != null) {
            // Không tìm được sân nào → báo lỗi cụ thể, không dùng generic message
            return StadiumIdResolution.earlyReturn(AiChatTurnResponse.builder()
                    .message(fallback.searchErrorMessage())
                    .intent("search_stadiums")  // Trả intent search_stadiums để FE hiển thị kết quả search
                    .build());
        }
        // Không có đủ thông tin để search → hỏi user
        return StadiumIdResolution.earlyReturn(
                errorResponse("Chưa xác định được sân bạn muốn đặt. Bạn muốn đặt sân nào? (Vd: sân bóng đá Thủ Đức)"));
    }

    /** Không resolve được slot — báo lỗi bug context nếu slotIndex đã stale, lưu pending state rồi hỏi lại giờ. */
    private AiChatTurnResponse handleMissingSlot(JsonNode args, String conversationKey, Integer stadiumId, LocalDate requestedDate) {
        if (args.hasNonNull("slotIndex")
                && conversationContextService.resolveSlotIdByIndex(conversationKey, args.get("slotIndex").asInt()).isEmpty()) {
            return systemBugResponse("Không thể xác định được khung giờ từ lịch sử chat. Vui lòng hỏi lại giờ trống.");
        }

        // Lưu pending state
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("stadiumId", stadiumId);
        data.put("date", requestedDate.toString());
        conversationContextService.savePendingAction(conversationKey,
                new AiConversationContextService.PendingAction("create_booking", data, "slot"));

        return errorResponse("Chưa xác định được khung giờ bạn muốn đặt. Bạn muốn đặt lúc mấy giờ? (Vd: 2h chiều thứ 7)");
    }

    private void mergePendingState(JsonNode args, String conversationKey) {
        if (args != null && args.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode mutableArgs = (com.fasterxml.jackson.databind.node.ObjectNode) args;
            Optional<AiConversationContextService.PendingAction> pendingOpt = conversationContextService.getPendingAction(conversationKey);
            if (pendingOpt.isPresent()) {
                AiConversationContextService.PendingAction pending = pendingOpt.get();
                if ("create_booking".equals(pending.getIntent()) && pending.getData() != null) {
                    pending.getData().forEach((key, value) -> {
                        if (!mutableArgs.hasNonNull(key) && value != null) {
                            if (value instanceof Integer) {
                                mutableArgs.put(key, (Integer) value);
                            } else if (value instanceof String) {
                                mutableArgs.put(key, (String) value);
                            } else if (value instanceof Boolean) {
                                mutableArgs.put(key, (Boolean) value);
                            }
                        }
                    });
                }
            }
        }
    }

    private AiChatTurnResponse createConfirmBookingResponse(Stadium stadium, LocalDate requestedDate, TimeSlotResponse targetSlot, String conversationKey) {
        // Lấy tên facility (cha) để phân biệt khi có nhiều "Sân 1" trùng tên
        String facilityName = (stadium.getParentStadium() != null) ? stadium.getParentStadium().getStadiumName() : null;

        // Định dạng ngày dd/MM/yyyy cho user-friendly
        String formattedDate = requestedDate.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        // Tạo Draft Booking thay vì gọi createBooking
        DraftBookingResponse draft = DraftBookingResponse.builder()
                .stadiumId(stadium.getStadiumId())
                .stadiumName(stadium.getStadiumName())
                .facilityName(facilityName) // Thêm tên cơ sở để hiển thị đầy đủ
                .date(formattedDate) // Định dạng dd/MM/yyyy
                .startTime(targetSlot.getStartTime().toString())
                .price(targetSlot.getPricePerSlot())
                .build();

        // Hoàn thành booking -> clear pending action
        conversationContextService.clearPendingAction(conversationKey);

        // Lưu draft vào context
        java.util.Map<String, Object> draftMap = new java.util.HashMap<>();
        draftMap.put("stadiumId", draft.getStadiumId());
        draftMap.put("stadiumName", draft.getStadiumName());
        draftMap.put("facilityName", draft.getFacilityName());
        draftMap.put("date", draft.getDate());
        draftMap.put("startTime", draft.getStartTime());
        draftMap.put("price", draft.getPrice());
        conversationContextService.saveBookingDraft(conversationKey, draftMap);

        return AiChatTurnResponse.builder()
                .message("Thông tin đặt sân đã sẵn sàng. Vui lòng kiểm tra lại và bấm nút bên dưới để tiến hành thanh toán.")
                .intent("confirm_booking") // Intent mới để UI hiển thị thẻ xác nhận
                .draftBooking(draft)
                .build();
    }

    public AiChatTurnResponse handleConfirmation(String message, Integer userId, String conversationKey) {
        String msgLower = message != null ? message.toLowerCase().trim() : "";
        
        if (isConfirmation(msgLower)) {
            // Lấy lại draft từ Redis
            java.util.Map<String, Object> draftMap = conversationContextService.getContext(conversationKey)
                    .map(ctx -> ctx.getBookingDraft())
                    .orElse(null);
                    
            if (draftMap != null) {
                DraftBookingResponse draft = DraftBookingResponse.builder()
                        .stadiumId((Integer) draftMap.get("stadiumId"))
                        .stadiumName((String) draftMap.get("stadiumName"))
                        .facilityName((String) draftMap.get("facilityName"))
                        .date((String) draftMap.get("date"))
                        .startTime((String) draftMap.get("startTime"))
                        // Handle price parsing safely since it could be Integer or Double or BigDecimal from Redis
                        .price(draftMap.get("price") != null ? new java.math.BigDecimal(draftMap.get("price").toString()) : null)
                        .build();
                
                // Clear the state so they don't get stuck
                conversationContextService.clearBookingDraft(conversationKey);
                
                return AiChatTurnResponse.builder()
                        .message("Tuyệt vời! Vui lòng bấm vào nút 'Thanh toán' trên thẻ thông tin bên dưới để hoàn tất đặt sân nhé.")
                        .intent("confirm_booking")
                        .draftBooking(draft)
                        .build();
            }
        } else if (isCancellation(msgLower)) {
            conversationContextService.clearBookingDraft(conversationKey);
            return AiChatTurnResponse.builder()
                    .intent("need_more_info")
                    .message("Đã hủy thao tác đặt sân. Bạn muốn tìm sân khác hay cần hỗ trợ gì thêm không?")
                    .build();
        }

        conversationContextService.clearBookingDraft(conversationKey);
        return AiChatTurnResponse.builder()
                .intent("need_more_info")
                .message("Mình chưa hiểu rõ ý bạn. Thao tác đặt sân trước đó đã bị hủy. Bạn cần hỗ trợ gì khác không?")
                .build();
    }
    
    private boolean isConfirmation(String msg) {
        return msg.equals("có") || msg.equals("đồng ý") || msg.equals("ok")
                || msg.equals("ừ") || msg.equals("được") || msg.equals("đặt luôn")
                || msg.equals("confirm") || msg.equals("yes") || msg.equals("y")
                || msg.startsWith("có") || msg.startsWith("đồng ý") || msg.startsWith("đặt luôn")
                || msg.startsWith("xác nhận");
    }

    private boolean isCancellation(String msg) {
        return msg.equals("không") || msg.equals("thôi") || msg.equals("bỏ")
                || msg.equals("no") || msg.equals("n") || msg.equals("hủy thao tác") || msg.equals("cancel");
    }

    /**
     * Fallback search: khi không resolve được stadiumId từ context, tìm sân mới dựa trên
     * extracted params (sportName, keyword, date, startTime, endTime).
     *
     * <p>Progressive search:</p>
     * <ol>
     *   <li>Tìm theo sportType + keyword (nếu có)</li>
     *   <li>Nếu có date + time → lọc sân còn trống vào thời điểm đó</li>
     *   <li>Nếu không có time cụ thể → trả tất cả sân phù hợp để user chọn</li>
     * </ol>
     */
    private SearchFallbackResult searchStadiumsFallback(JsonNode args, String conversationKey) {
        // Kiểm tra xem có đủ thông tin để search không
        String sportName = args.hasNonNull("sportName") ? args.get("sportName").asText() : null;
        String keyword = args.hasNonNull("keyword") ? args.get("keyword").asText() : null;

        // Nếu không có sportName và keyword → không đủ thông tin để search
        if ((sportName == null || sportName.isBlank()) && (keyword == null || keyword.isBlank())) {
            log.debug("Fallback search: không có sportName hoặc keyword để tìm sân");
            return SearchFallbackResult.error(null); // null = không có đủ thông tin
        }

        // Resolve sportTypeId
        Integer sportTypeId = null;
        if (sportName != null && !sportName.isBlank()) {
            sportTypeId = resolveSportTypeId(sportName);
        }

        // Parse date và time
        ParsedSearchDateTime parsedDateTime = parseFallbackDateTime(args);
        LocalDate targetDate = parsedDateTime.date();
        LocalTime targetStartTime = parsedDateTime.startTime();
        LocalTime targetEndTime = parsedDateTime.endTime();

        // Tìm sân theo sportType (ưu tiên) hoặc keyword
        List<Stadium> foundStadiums;
        try {
            foundStadiums = findStadiumsForFallback(sportTypeId, keyword);
        } catch (Exception e) {
            log.error("Fallback search: error searching stadiums", e);
            return SearchFallbackResult.error("Đã xảy ra lỗi khi tìm sân. Vui lòng thử lại sau.");
        }

        // Nếu không tìm được sân nào
        if (foundStadiums.isEmpty()) {
            String sportInfo = sportName != null ? " " + sportName : "";
            return SearchFallbackResult.none(
                    "Không tìm thấy sân" + sportInfo + " nào phù hợp. Bạn thử đổi khu vực hoặc mô tả cụ thể hơn được không? (Vd: 'sân bóng đá ở Thủ Đức')");
        }

        // Nếu có date + time cụ thể → lọc sân còn trống vào thời điểm đó
        if (targetDate != null && (targetStartTime != null || targetEndTime != null)) {
            List<Stadium> availableStadiums = filterStadiumsWithAvailability(
                    foundStadiums, targetDate, targetStartTime, targetEndTime);

            if (availableStadiums.isEmpty()) {
                String dateStr = targetDate.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                String timeStr = formatTimeRange(targetStartTime, targetEndTime);
                return SearchFallbackResult.none(
                        "Rất tiếc, không có sân nào còn trống" +
                                (sportName != null ? " " + sportName : "") +
                                " vào ngày " + dateStr +
                                (timeStr != null ? " lúc " + timeStr : "") + ". " +
                                "Bạn có thể thử đổi ngày, giờ, hoặc khu vực khác nhé.");
            }

            if (availableStadiums.size() == 1) {
                return SearchFallbackResult.single(availableStadiums.get(0));
            }

            // Nhiều sân còn trống → cho user chọn
            return SearchFallbackResult.multiple(availableStadiums);
        }

        // Không có date/time cụ thể → trả danh sách để user chọn
        if (foundStadiums.size() == 1) {
            return SearchFallbackResult.single(foundStadiums.get(0));
        }

        // Nhiều hơn 1 sân → cho user chọn
        return SearchFallbackResult.multiple(foundStadiums);
    }

    private record ParsedSearchDateTime(LocalDate date, LocalTime startTime, LocalTime endTime) { }

    /** Parse date/startTime/endTime từ args cho fallback search — parse lỗi thì bỏ qua field đó, không fail cả request. */
    private ParsedSearchDateTime parseFallbackDateTime(JsonNode args) {
        LocalDate targetDate = null;
        LocalTime targetStartTime = null;
        LocalTime targetEndTime = null;

        if (args.hasNonNull("date")) {
            try {
                targetDate = LocalDate.parse(args.get("date").asText());
            } catch (Exception e) {
                log.warn("Fallback search: invalid date format {}", args.get("date").asText());
            }
        }

        if (args.hasNonNull("startTime")) {
            try {
                targetStartTime = LocalTime.parse(args.get("startTime").asText());
            } catch (Exception e) {
                log.warn("Fallback search: invalid startTime format {}", args.get("startTime").asText());
            }
        }

        if (args.hasNonNull("endTime")) {
            try {
                targetEndTime = LocalTime.parse(args.get("endTime").asText());
            } catch (Exception e) {
                log.warn("Fallback search: invalid endTime format {}", args.get("endTime").asText());
            }
        }

        return new ParsedSearchDateTime(targetDate, targetStartTime, targetEndTime);
    }

    /** Tìm sân theo sportType (ưu tiên) hoặc keyword — dùng cho fallback search. */
    private List<Stadium> findStadiumsForFallback(Integer sportTypeId, String keyword) {
        if (sportTypeId != null) {
            return stadiumRepository.findCourtsForAiToolByIds(
                    stadiumRepository.findBySportTypeSportTypeIdAndStadiumStatus(
                            sportTypeId, StadiumStatus.AVAILABLE, org.springframework.data.domain.Pageable.unpaged()).getContent()
                            .stream().map(Stadium::getStadiumId).toList()
                    );
        }
        if (keyword != null && !keyword.isBlank()) {
            // Tìm theo keyword (tên sân, tên facility)
            List<Stadium> found = stadiumRepository.findCourtsByParentFacilityNameKeyword(keyword, null);
            if (found.isEmpty()) {
                // Thử search theo tên court
                found = stadiumRepository.searchByKeyword(keyword, org.springframework.data.domain.Pageable.ofSize(10)).getContent();
            }
            return found;
        }
        return List.of();
    }

    /**
     * Lọc các sân còn trống vào thời điểm cụ thể.
     * Kiểm tra xem có slot nào trống trong khoảng time yêu cầu.
     */
    private List<Stadium> filterStadiumsWithAvailability(
            List<Stadium> stadiums, LocalDate date, LocalTime startTime, LocalTime endTime) {

        return stadiums.stream()
                .filter(stadium -> {
                    List<TimeSlotResponse> slots = bookingService.getSlotsByDate(stadium.getStadiumId(), date);

                    if (slots.isEmpty()) {
                        return false;
                    }

                    // Lọc slot trong tương lai nếu là hôm nay
                    LocalTime now = LocalTime.now(clock);
                    if (date.isEqual(LocalDate.now(clock))) {
                        slots = slots.stream()
                                .filter(s -> s.getStartTime() != null && s.getStartTime().isAfter(now))
                                .toList();
                    }

                    // Kiểm tra nếu có slot trống trong khoảng yêu cầu
                    return hasAvailableSlotInRange(slots, startTime, endTime);
                })
                .toList();
    }

    /**
     * Kiểm tra xem có slot nào trống trong khoảng thời gian yêu cầu.
     */
    private boolean hasAvailableSlotInRange(List<TimeSlotResponse> slots, LocalTime startTime, LocalTime endTime) {
        if (slots.isEmpty()) {
            return false;
        }

        // Nếu có endTime cụ thể → tìm slot bắt đầu trong khoảng
        if (endTime != null) {
            final LocalTime reqEndTime = endTime;
            return slots.stream()
                    .filter(s -> Boolean.TRUE.equals(s.getAvailable()))
                    .anyMatch(s -> {
                        LocalTime slotStart = s.getStartTime();
                        // Slot phải nằm trong khoảng yêu cầu
                        return slotStart != null &&
                                (startTime == null || !slotStart.isBefore(startTime)) &&
                                !slotStart.isAfter(reqEndTime);
                    });
        }

        // Nếu chỉ có startTime → tìm slot bắt đầu từ startTime hoặc muộn hơn một chút (±30 phút)
        if (startTime != null) {
            final LocalTime reqStartTime = startTime;
            return slots.stream()
                    .filter(s -> Boolean.TRUE.equals(s.getAvailable()))
                    .anyMatch(s -> {
                        LocalTime slotStart = s.getStartTime();
                        if (slotStart == null) {
                            return false;
                        }
                        // Cho phép ±30 phút
                        return !slotStart.isBefore(reqStartTime.minusMinutes(30)) &&
                                !slotStart.isAfter(reqStartTime.plusMinutes(30));
                    });
        }

        // Không có time cụ thể → chỉ cần có slot trống
        return slots.stream().anyMatch(s -> Boolean.TRUE.equals(s.getAvailable()));
    }

    /**
     * Format khoảng thời gian thành string để hiển thị.
     */
    private String formatTimeRange(LocalTime startTime, LocalTime endTime) {
        if (startTime != null && endTime != null) {
            return startTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) +
                    " - " + endTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        } else if (startTime != null) {
            return startTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        } else if (endTime != null) {
            return "trước " + endTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        }
        return null;
    }

    /**
     * Resolve sportTypeId từ sportName (tái sử dụng logic từ StadiumSearchHandler).
     */
    private Integer resolveSportTypeId(String sportName) {
        if (sportName == null || sportName.isBlank()) {
            return null;
        }

        // Thử normalize sport name trước
        String normalizedName = normalizeSportName(sportName);
        final String finalSportName = normalizedName != null ? normalizedName : sportName;

        // Tìm trong DB
        return sportTypeRepository.findBySportName(finalSportName)
                .map(st -> st.getSportTypeId())
                .orElseGet(() -> findSportTypeIdByKeyword(finalSportName));
    }

    /**
     * Normalize sport name (đá banh → Bóng đá, bóng rổ → Bóng rổ, etc).
     */
    private String normalizeSportName(String input) {
        if (input == null) {
            return null;
        }
        String lower = input.toLowerCase(java.util.Locale.ROOT).trim();

        if (lower.contains("đá banh") || lower.contains("bóng đá") || lower.contains("football") || lower.contains("futsal")) {
            return "Bóng đá";
        }
        if (lower.contains("cầu lông") || lower.contains("badminton")) {
            return "Cầu lông";
        }
        if (lower.contains("bóng rổ") || lower.contains("basketball")) {
            return "Bóng rổ";
        }
        if (lower.contains("bóng chuyền") || lower.contains("volleyball")) {
            return "Bóng chuyền";
        }
        if (lower.contains("tennis") || lower.contains("quần vợt")) {
            return "Tennis";
        }
        if (lower.contains("pickleball")) {
            return "Pickleball";
        }
        if (lower.contains("bơi") || lower.contains("swim")) {
            return "Bơi lội";
        }
        if (lower.contains("bóng bàn") || lower.contains("table tennis") || lower.contains("ping pong")) {
            return "Bóng bàn";
        }
        return null;
    }

    /**
     * Tìm sportTypeId bằng keyword fallback.
     */
    private Integer findSportTypeIdByKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String lower = keyword.toLowerCase(java.util.Locale.ROOT);

        if (lower.contains("bong da") || lower.contains("da bong") || lower.contains("football") || lower.contains("soccer") || lower.contains("futsal")) {
            return findByCode("FOOTBALL");
        }
        if (lower.contains("cau long") || lower.contains("badminton")) {
            return findByCode("BADMINTON");
        }
        if (lower.contains("bong ro") || lower.contains("basketball")) {
            return findByCode("BASKETBALL");
        }
        if (lower.contains("bong chuyen") || lower.contains("volleyball")) {
            return findByCode("VOLLEYBALL");
        }
        if (lower.contains("tennis") || lower.contains("quan vot")) {
            return findByCode("TENNIS");
        }
        if (lower.contains("pickleball")) {
            return findByCode("PICKLEBALL");
        }
        if (lower.contains("bong ban") || lower.contains("table tennis") || lower.contains("ping pong")) {
            return findByCode("TABLE_TENNIS");
        }
        return null;
    }

    private Integer findByCode(String code) {
        return sportTypeRepository.findAll().stream()
                .filter(st -> st.getSportCode().equalsIgnoreCase(code))
                .map(st -> st.getSportTypeId())
                .findFirst()
                .orElse(null);
    }

    /**
     * Xây dựng response khi tìm được nhiều sân - hiển thị danh sách để user chọn.
     */
    private AiChatTurnResponse buildStadiumSelectionResponse(
            List<Stadium> stadiums, String conversationKey, String llmMessage) {

        // Lưu vào context để targetIndex hoạt động ở lượt sau
        conversationContextService.saveLastShownStadiums(conversationKey,
                stadiums.stream().map(Stadium::getStadiumId).toList());

        // Lấy thông tin slots cho mỗi sân (chỉ lấy số lượng để hiển thị)
        List<StadiumWithSlotInfo> stadiumsWithInfo = stadiums.stream()
                .map(s -> {
                    List<TimeSlotResponse> slots = bookingService.getSlotsByDate(s.getStadiumId(), LocalDate.now(clock));
                    long availableCount = slots.stream().filter(ss -> Boolean.TRUE.equals(ss.getAvailable())).count();
                    return new StadiumWithSlotInfo(s, availableCount);
                })
                .toList();

        // Tạo message mô tả các sân tìm được
        String stadiumList = stadiumsWithInfo.stream()
                .map(info -> {
                    String name = formatStadiumName(info.stadium());
                    return name + " (" + info.availableCount() + " giờ trống)";
                })
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        String message = "Mình tìm được " + stadiumsWithInfo.size() + " sân phù hợp: " + stadiumList +
                ". Bạn muốn chọn sân nào?";

        // Trả về response với stadiums để FE hiển thị
        List<com.sportvenue.dto.response.StadiumResponse> stadiumResponses = stadiumsWithInfo.stream()
                .map(info -> {
                    com.sportvenue.dto.response.StadiumResponse r = new com.sportvenue.dto.response.StadiumResponse();
                    r.setStadiumId(info.stadium().getStadiumId());
                    r.setStadiumName(formatStadiumName(info.stadium()));
                    r.setSportName(info.stadium().getSportType() != null ? info.stadium().getSportType().getSportName() : null);
                    r.setSportTypeId(info.stadium().getSportType() != null ? info.stadium().getSportType().getSportTypeId() : null);
                    r.setPricePerHour(info.stadium().getPricePerHour());
                    r.setAddress(info.stadium().getParentStadium() != null ?
                            info.stadium().getParentStadium().getAddress() : info.stadium().getAddress());
                    r.setAverageRating(info.stadium().getAverageRating());
                    if (info.stadium().getParentStadium() != null) {
                        r.setComplexName(info.stadium().getParentStadium().getStadiumName());
                        r.setParentStadiumId(info.stadium().getParentStadium().getStadiumId());
                    }
                    return r;
                })
                .toList();

        return AiChatTurnResponse.builder()
                .message(message)
                .intent("search_stadiums")  // FE sẽ render danh sách sân + hỏi chọn sân
                .stadiums(stadiumResponses)
                .build();
    }

    private record StadiumWithSlotInfo(Stadium stadium, long availableCount) { }

    private String formatStadiumName(Stadium stadium) {
        if (stadium.getParentStadium() != null && stadium.getParentStadium().getStadiumName() != null) {
            return stadium.getParentStadium().getStadiumName() + " - " + stadium.getStadiumName();
        }
        return stadium.getStadiumName();
    }



    /**
     * Resolve date với validation bằng RelativeDateParser.
     * Priority: RelativeDateParser > context > LLM > today
     */
    private LocalDate resolveDateWithValidation(JsonNode args, String rawUserMessage, String conversationKey) {
        LocalDate parsedDate = relativeDateParser.parse(rawUserMessage);
        if (parsedDate != null) {
            return parsedDate;
        }

        if (conversationKey != null) {
            Optional<LocalDate> ctxDate = conversationContextService.getCurrentDate(conversationKey);
            if (ctxDate.isPresent()) {
                return ctxDate.get();
            }
        }

        if (args.hasNonNull("date")) {
            try {
                return LocalDate.parse(args.get("date").asText());
            } catch (Exception ignored) {
                // fall through to context/default date below
            }
        }

        return LocalDate.now(clock);
    }

    /**
     * Resolve stadiumId với priority: stadiumId > targetIndex > keyword > currentStadiumId
     */
    private Integer resolveStadiumId(JsonNode args, String conversationKey) {
        if (args.hasNonNull("stadiumId")) {
            return args.get("stadiumId").asInt();
        }

        List<Integer> lastShownStadiumIds = conversationContextService.getLastShownStadiumIds(conversationKey);

        if (args.hasNonNull("targetIndex")) {
            int targetIndex = args.get("targetIndex").asInt();

            if (lastShownStadiumIds != null && lastShownStadiumIds.contains(targetIndex)) {
                return targetIndex;
            }

            if (lastShownStadiumIds != null && targetIndex >= 0 && targetIndex < lastShownStadiumIds.size()) {
                return lastShownStadiumIds.get(targetIndex);
            }

            return null;
        }

        if (args.hasNonNull("keyword")) {
            String keyword = args.get("keyword").asText().toLowerCase();
            if (lastShownStadiumIds != null && !lastShownStadiumIds.isEmpty()) {
                List<Stadium> recentStadiums = stadiumRepository.findAllById(lastShownStadiumIds);
                return recentStadiums.stream()
                        .filter(s -> {
                            String name = s.getStadiumName().toLowerCase();
                            String parentName = (s.getParentStadium() != null && s.getParentStadium().getStadiumName() != null)
                                    ? s.getParentStadium().getStadiumName().toLowerCase() : "";
                            return name.contains(keyword) || parentName.contains(keyword);
                        })
                        .findFirst()
                        .map(Stadium::getStadiumId)
                        .orElse(null);
            }
        }

        if (!args.hasNonNull("targetIndex") && !args.hasNonNull("keyword")) {
            return conversationContextService.getCurrentStadiumId(conversationKey).orElse(null);
        }

        return null;
    }

    private TimeSlotResponse resolveSlot(JsonNode args, String conversationKey, List<TimeSlotResponse> slots) {
        if (args.hasNonNull("slotId")) {
            int sid = args.get("slotId").asInt();
            return slots.stream().filter(s -> s.getSlotId() != null && s.getSlotId() == sid).findFirst().orElse(null);
        } else if (args.hasNonNull("slotIndex")) {
            int slotIndex = args.get("slotIndex").asInt();
            Integer resolvedId = conversationContextService.resolveSlotIdByIndex(conversationKey, slotIndex).orElse(null);
            if (resolvedId != null) {
                return slots.stream().filter(s -> s.getSlotId() != null && s.getSlotId().equals(resolvedId)).findFirst().orElse(null);
            } else {
                log.warn("Resolve context failed for slotId. conversationKey={}, slotIndex={}", conversationKey, slotIndex);
                return null;
            }
        } else if (args.hasNonNull("startTime")) {
            try {
                java.time.LocalTime targetStartTime = java.time.LocalTime.parse(args.get("startTime").asText());
                return slots.stream()
                        .filter(s -> s.getStartTime() != null && s.getStartTime().equals(targetStartTime))
                        .findFirst().orElse(null);
            } catch (Exception e) {
                log.warn("Invalid startTime format: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * Unified slot availability check: if target slot unavailable, try sibling courts.
     * Returns null only if the target court/slot is genuinely available.
     */
    private AiChatTurnResponse checkSlotAvailableWithSiblingFallback(
            Stadium stadium, TimeSlotResponse targetSlot, List<TimeSlotResponse> slots,
            LocalDate requestedDate, String conversationKey) {

        if (Boolean.TRUE.equals(targetSlot.getAvailable())) {
            // Quick DB re-check before confirming
            AiChatTurnResponse taken = checkSlotTakenByOthersWithSiblingFallback(
                    stadium, targetSlot, requestedDate, conversationKey);
            if (taken != null) {
                return taken;
            }
            return null; // genuinely available
        }

        // Target slot unavailable — try sibling courts
        Stadium parent = stadium.getParentStadium();
        if (parent == null || stadium.getFootballFieldType() == null) {
            // No siblings possible — show available slots as fallback
            return buildUnavailableSlotsResponse(stadium, slots, requestedDate, conversationKey);
        }

        List<Stadium> siblings = stadiumRepository.findSiblingCourtsByFacilityAndFieldType(
                parent.getStadiumId(), stadium.getStadiumId(), stadium.getFootballFieldType());

        List<com.sportvenue.entity.enums.BookingStatus> blockingStatuses = List.of(
                com.sportvenue.entity.enums.BookingStatus.PENDING,
                com.sportvenue.entity.enums.BookingStatus.CONFIRMED
        );

        Stadium freeSibling = null;
        for (Stadium sibling : siblings) {
            boolean siblingTaken = bookingRepository.existsActiveBooking(
                    sibling.getStadiumId(), targetSlot.getSlotId(), requestedDate, blockingStatuses);
            if (!siblingTaken) {
                freeSibling = sibling;
                break;
            }
        }

        if (freeSibling != null) {
            log.info("Sibling fallback: '{}' unavailable at slot {}, booking sibling '{}'",
                    stadium.getStadiumName(), targetSlot.getSlotId(), freeSibling.getStadiumName());
            conversationContextService.saveCurrentStadiumId(conversationKey, freeSibling.getStadiumId());
            conversationContextService.saveLastShownStadiums(conversationKey, List.of(freeSibling.getStadiumId()));
            return createConfirmBookingResponse(freeSibling, requestedDate, targetSlot, conversationKey);
        }

        return buildUnavailableSlotsResponse(stadium, slots, requestedDate, conversationKey);
    }

    private AiChatTurnResponse buildUnavailableSlotsResponse(
            Stadium stadium, List<TimeSlotResponse> slots, LocalDate requestedDate, String conversationKey) {
        if (requestedDate.isEqual(LocalDate.now(clock))) {
            java.time.LocalTime nowVietnam = java.time.LocalTime.now(clock);
            slots = slots.stream()
                    .filter(s -> s.getStartTime() != null && s.getStartTime().isAfter(nowVietnam))
                    .toList();
        }
        conversationContextService.saveLastShownSlots(conversationKey,
                slots.stream().map(TimeSlotResponse::getSlotId).toList());
        conversationContextService.saveCurrentStadiumId(conversationKey, stadium.getStadiumId());
        return AiChatTurnResponse.builder()
                .message("Khung giờ bạn chọn hiện không có sẵn. Đây là các giờ còn trống trong ngày " + requestedDate + " để bạn chọn:")
                .intent("get_slots")
                .slots(slots)
                .build();
    }

    private AiChatTurnResponse checkDuplicateBookings(Integer userId, Integer slotId, LocalDate requestedDate) {
        List<com.sportvenue.entity.enums.BookingStatus> pendingStatuses = List.of(
                com.sportvenue.entity.enums.BookingStatus.PENDING,
                com.sportvenue.entity.enums.BookingStatus.PENDING_PAYMENT
        );
        List<com.sportvenue.entity.Booking> duplicateBookings = bookingRepository.findUserActiveBookingsForSlot(
                userId, slotId, requestedDate, pendingStatuses);

        if (!duplicateBookings.isEmpty()) {
            com.sportvenue.entity.Booking dup = duplicateBookings.get(0);
            return AiChatTurnResponse.builder()
                    .message(String.format(
                            "Bạn đã có một đơn đặt sân (Mã: BK%06d) đang chờ thanh toán cho khung giờ này rồi. Bạn có muốn xem lại và thanh toán không?",
                            dup.getBookingId()
                    ))
                    .intent("create_booking")
                    .build();
        }
        return null;
    }

    /**
     * Check slot taken + auto-fallback to sibling courts (same facility, same field type, same slot).
     * When a free sibling is found, creates the draft booking immediately so executeStep marks COMPLETED.
     */
    private AiChatTurnResponse checkSlotTakenByOthersWithSiblingFallback(
            Stadium stadium, TimeSlotResponse targetSlot, LocalDate requestedDate, String conversationKey) {
        List<com.sportvenue.entity.enums.BookingStatus> blockingStatuses = List.of(
                com.sportvenue.entity.enums.BookingStatus.PENDING,
                com.sportvenue.entity.enums.BookingStatus.CONFIRMED
        );
        boolean isTaken = bookingRepository.existsActiveBooking(
                stadium.getStadiumId(), targetSlot.getSlotId(), requestedDate, blockingStatuses);

        if (!isTaken) {
            return null;
        }

        // Slot taken — try sibling courts
        Stadium parent = stadium.getParentStadium();
        if (parent == null || stadium.getFootballFieldType() == null) {
            return AiChatTurnResponse.builder()
                    .message("Rất tiếc, sân này vừa được đặt bởi người khác cho khung giờ bạn chọn. Vui lòng chọn khung giờ khác hoặc liên hệ chúng tôi để được hỗ trợ.")
                    .intent("get_slots")
                    .build();
        }

        List<Stadium> siblings = stadiumRepository.findSiblingCourtsByFacilityAndFieldType(
                parent.getStadiumId(), stadium.getStadiumId(), stadium.getFootballFieldType());

        Stadium freeSibling = null;
        for (Stadium sibling : siblings) {
            boolean siblingTaken = bookingRepository.existsActiveBooking(
                    sibling.getStadiumId(), targetSlot.getSlotId(), requestedDate, blockingStatuses);
            if (!siblingTaken) {
                freeSibling = sibling;
                break;
            }
        }

        if (freeSibling != null) {
            log.info("Sibling fallback: '{}' full at slot {}, booking sibling '{}'",
                    stadium.getStadiumName(), targetSlot.getSlotId(), freeSibling.getStadiumName());
            conversationContextService.saveCurrentStadiumId(conversationKey, freeSibling.getStadiumId());
            conversationContextService.saveLastShownStadiums(conversationKey, List.of(freeSibling.getStadiumId()));

            // Build draft booking for the free sibling — this is what executeStep needs to mark COMPLETED
            return createConfirmBookingResponse(freeSibling, requestedDate, targetSlot, conversationKey);
        }

        return AiChatTurnResponse.builder()
                .message("Rất tiếc, tất cả sân cùng loại tại khu vực này đều đã được đặt vào khung giờ bạn chọn. Vui lòng chọn ngày hoặc giờ khác.")
                .intent("get_slots")
                .build();
    }

    private AiChatTurnResponse errorResponse(String message) {
        return AiChatTurnResponse.builder()
                .message(message)
                .intent("create_booking")
                .build();
    }

    private AiChatTurnResponse systemBugResponse(String message) {
        return AiChatTurnResponse.builder()
                .message(message)
                .intent("system_bug_context_resolve")
                .build();
    }

    /**
     * Build clarification message when user provides ambiguous stadium identifier.
     */
    private String buildClarificationMessage(int targetIndex, List<Integer> lastShownStadiumIds) {
        if (lastShownStadiumIds == null || lastShownStadiumIds.isEmpty()) {
            return "Mình không tìm thấy sân bạn muốn chọn trong danh sách vừa hiển thị. Bạn muốn đặt sân nào? (Vd: sân bóng đá Thủ Đức)";
        }

        // Try to resolve the number to a stadium name
        List<Stadium> stadiums = stadiumRepository.findAllById(lastShownStadiumIds);
        StringBuilder sb = new StringBuilder();
        sb.append("Mình không tìm thấy sân với số bạn cung cấp (").append(targetIndex).append(") trong danh sách. ");

        if (stadiums.size() <= 5) {
            sb.append("Danh sách các sân bạn có thể chọn: ");
            for (int i = 0; i < stadiums.size(); i++) {
                Stadium s = stadiums.get(i);
                String name = formatStadiumName(s);
                sb.append("\n").append(i).append(" - ").append(name).append(" (ID: ").append(s.getStadiumId()).append(")");
            }
            sb.append("\n\nBạn có thể nói 'sân thứ 1', 'sân thứ 2', hoặc 'sân ID X' để chọn chính xác.");
        } else {
            sb.append("Bạn có thể chọn sân theo số thứ tự (sân thứ 1, sân thứ 2...) hoặc cung cấp ID sân cụ thể.");
        }

        return sb.toString();
    }
}
