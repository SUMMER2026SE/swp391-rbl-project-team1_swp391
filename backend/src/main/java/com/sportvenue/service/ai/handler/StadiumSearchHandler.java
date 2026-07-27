package com.sportvenue.service.ai.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.sportvenue.dto.request.StadiumSearchRequest;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.dto.response.PageResponse;
import com.sportvenue.dto.response.StadiumResponse;
import com.sportvenue.entity.SportType;
import com.sportvenue.entity.Stadium;
import com.sportvenue.mapper.StadiumMapper;
import com.sportvenue.repository.SportTypeRepository;
import com.sportvenue.repository.StadiumRepository;
import com.sportvenue.service.MaintenanceScheduleService;
import com.sportvenue.service.PublicStadiumService;
import com.sportvenue.service.ai.AiConversationContextService;
import com.sportvenue.util.RelativeDateParser;
import com.sportvenue.util.location.VietnamLocationResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Xử lý intent "search_stadiums" — port nguyên logic nghiệp vụ từ CustomerAgentToolProvider
 * (nhánh ai-chatting cũ đã bỏ), chỉ đổi lớp vỏ ngoài: nhận JsonNode params (từ 1 lần gọi Groq
 * JSON mode) thay vì tool-call arguments, và tự quyết định message cuối cùng thay vì chờ LLM
 * rephrase ở lượt gọi thứ 2 (kiến trúc mới không còn lượt gọi thứ 2 — xem
 * docs/ai_chatbot_rebuild_plan.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StadiumSearchHandler {

    private final PublicStadiumService publicStadiumService;
    private final SportTypeRepository sportTypeRepository;
    private final StadiumRepository stadiumRepository;
    private final StadiumMapper stadiumMapper;
    private final VietnamLocationResolver locationResolver;
    private final MaintenanceScheduleService maintenanceScheduleService;
    private final AiConversationContextService conversationContextService;

    /** Giới hạn kết quả — card UI trong khung chat không bị quá dài. */
    static final int AI_SEARCH_RESULT_LIMIT = 5;

    /** Giờ Việt Nam — server Docker thường chạy UTC (lệch 7 tiếng). Không final để test override. */
    private Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));

    /** BUG B FIX: Fallback parser cho relative date expressions mà LLM không extract được. */
    private final RelativeDateParser relativeDateParser = new RelativeDateParser(clock);

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public AiChatTurnResponse handle(JsonNode args, String llmMessage, String conversationKey) {
        return handle(args, llmMessage, conversationKey, null, null);
    }

    /**
     * Overload nhận tọa độ GPS từ Frontend.
     * Nếu Groq trả về location = "CURRENT_LOCATION" và có coords → tìm theo bán kính GPS.
     * Nếu không có coords → hỏi user cấp quyền vị trí.
     */
    private static class SearchContextHolder {
        final String sportName;
        final String district;
        final String province;

        SearchContextHolder(String sportName, String district, String province) {
            this.sportName = sportName;
            this.district = district;
            this.province = province;
        }
    }

    public AiChatTurnResponse handle(JsonNode args, String llmMessage, String conversationKey,
                                     Double userLat, Double userLng) {
        if (args == null || args.isNull() || args.isMissingNode()) {
            return AiChatTurnResponse.builder()
                    .message("Bạn muốn tìm sân ở khu vực nào và chơi môn thể thao gì? Cho mình biết cụ thể hơn để tìm chính xác nhé.")
                    .intent("need_more_info")
                    .build();
        }

        if (isGpsSearch(args)) {
            return resolveGpsSearch(args, llmMessage, conversationKey, userLat, userLng);
        }

        // --- MERGE FROM CONTEXT ---
        SearchContextHolder context = mergeContextFilters(args, conversationKey);

        // Guardrail cứng bằng code (sau khi merge context)
        if (context.sportName == null && context.district == null && context.province == null && !args.hasNonNull("keyword")) {
            return AiChatTurnResponse.builder()
                    .message("Bạn muốn tìm sân ở khu vực nào và chơi môn thể thao gì? Cho mình biết cụ thể hơn để tìm chính xác nhé.")
                    .intent("need_more_info")
                    .build();
        }

        // Lưu context mới (bao gồm cả province - Bug #3)
        String targetDateStr = args.hasNonNull("targetDate") ? args.get("targetDate").asText() : null;
        String startTimeStr = args.hasNonNull("startTime") ? args.get("startTime").asText() : null;
        conversationContextService.saveCurrentFilters(conversationKey, context.sportName, context.district, context.province, targetDateStr, startTimeStr);

        StadiumSearchRequest.StadiumSearchRequestBuilder builder = StadiumSearchRequest.builder();
        String sportError = applyFilters(builder, args, context);
        if (sportError != null) {
            return AiChatTurnResponse.messageOnly(sportError, "search_stadiums");
        }

        StadiumSearchRequest searchRequest = builder.build();

        PageResponse<StadiumResponse> result = publicStadiumService.searchStadiums(searchRequest);

        List<StadiumResponse> stadiums = getStadiumsWithFallback(result.getContent(), searchRequest);

        // Vẫn rỗng dù đã thử theo tên (facility fallback/bỏ keyword nhiễu) — bộ lọc có thể đang
        // quá chặt (khu vực/giá/giờ) chứ không phải "không tồn tại". Nới lỏng dần thay vì báo
        // rỗng ngay (progressive relaxation — xem docs/ai_chatbot_rebuild_plan.md).
        String relaxationNote = null;
        if (stadiums.isEmpty()) {
            RelaxedSearchResult relaxed = searchWithRelaxation(searchRequest);
            if (!relaxed.stadiums().isEmpty()) {
                stadiums = relaxed.stadiums();
                relaxationNote = relaxed.relaxationNote();
            }
        }

        // BUG B FIX: Nếu không tìm thấy sân VÀ date chưa được extract từ LLM,
        // thử parse relative date từ raw message và tìm lại
        if (stadiums.isEmpty() && targetDateStr == null && llmMessage != null) {
            RetryWithDateResult retry = retryWithParsedDate(args, llmMessage, context, startTimeStr, conversationKey);
            if (!retry.stadiums().isEmpty()) {
                stadiums = retry.stadiums();
                relaxationNote = retry.relaxationNote();
            }
        }

        if (stadiums.isEmpty()) {
            log.debug("No stadiums found for searchRequest={}", searchRequest);
            return AiChatTurnResponse.builder()
                    .message("Chưa tìm thấy sân phù hợp với yêu cầu của bạn. Bạn có thể thử đổi khu vực, môn thể thao hoặc kiểm tra lại tên sân.")
                    .intent("search_stadiums")
                    .stadiums(List.of())
                    .build();
        }

        conversationContextService.saveLastShownStadiums(conversationKey,
                stadiums.stream().map(StadiumResponse::getStadiumId).toList());

        return AiChatTurnResponse.builder()
                .message(relaxationNote != null ? relaxationNote : llmMessage)
                .intent("search_stadiums")
                .stadiums(stadiums)
                .build();
    }

    private AiChatTurnResponse resolveGpsSearch(JsonNode args, String llmMessage, String conversationKey,
                                                 Double userLat, Double userLng) {
        if (userLat == null || userLng == null) {
            return AiChatTurnResponse.builder()
                    .intent("need_more_info")
                    .message("Để tìm sân gần bạn nhất, mình cần quyền truy cập vị trí của bạn. " +
                            "Vui lòng cho phép trình duyệt chia sẻ vị trí và thử lại nhé!")
                    .build();
        }
        return handleGpsSearch(args, llmMessage, conversationKey, userLat, userLng);
    }

    private record RetryWithDateResult(List<StadiumResponse> stadiums, String relaxationNote) { }

    /** BUG B FIX: Không tìm thấy sân + date chưa được LLM extract → thử parse relative date từ raw message và tìm lại. */
    private RetryWithDateResult retryWithParsedDate(JsonNode args, String llmMessage, SearchContextHolder context,
                                                      String startTimeStr, String conversationKey) {
        LocalDate parsedDate = relativeDateParser.parse(llmMessage);
        if (parsedDate == null) {
            return new RetryWithDateResult(List.of(), null);
        }

        // Rebuild search request with parsed date
        StadiumSearchRequest.StadiumSearchRequestBuilder retryBuilder = StadiumSearchRequest.builder();
        applyFilters(retryBuilder, args, context);
        retryBuilder.targetDate(parsedDate);
        retryBuilder.page(0);
        retryBuilder.size(AI_SEARCH_RESULT_LIMIT);

        StadiumSearchRequest retryRequest = retryBuilder.build();
        PageResponse<StadiumResponse> retryResult = publicStadiumService.searchStadiums(retryRequest);
        List<StadiumResponse> stadiums = getStadiumsWithFallback(retryResult.getContent(), retryRequest);

        if (stadiums.isEmpty()) {
            return new RetryWithDateResult(stadiums, null);
        }

        // Update context with parsed date
        String parsedDateStr = parsedDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        conversationContextService.saveCurrentFilters(conversationKey, context.sportName, context.district, context.province, parsedDateStr, startTimeStr);
        String note = "Đã tìm thấy sân cho ngày " + parsedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                + " (mình hiểu '" + extractDateHint(llmMessage) + "' là ngày này):";
        return new RetryWithDateResult(stadiums, note);
    }

    private boolean isGpsSearch(JsonNode args) {
        String rawLocation = args.hasNonNull("location") ? args.get("location").asText()
                : (args.hasNonNull("district") ? args.get("district").asText() : null);
        return rawLocation != null
                && rawLocation.toUpperCase().replace("_", "").contains("CURRENTLOCATION");
    }

    private SearchContextHolder mergeContextFilters(JsonNode args, String conversationKey) {
        // Accept multiple aliases from planner (sport_type/sport/sportName, location/district, etc.)
        String sportName = args.hasNonNull("sportName") ? args.get("sportName").asText()
                : (args.hasNonNull("sport_type") ? args.get("sport_type").asText()
                : (args.hasNonNull("sport") ? args.get("sport").asText() : null));
        String district = args.hasNonNull("district") ? args.get("district").asText()
                : (args.hasNonNull("location") ? args.get("location").asText() : null);
        String province = args.hasNonNull("province") ? args.get("province").asText() : null;

        java.util.Optional<AiConversationContextService.ConversationContext> ctxOpt = conversationContextService.getContext(conversationKey);
        if (ctxOpt.isPresent()) {
            AiConversationContextService.ConversationContext ctx = ctxOpt.get();
            if (sportName == null && ctx.getCurrentSport() != null) {
                sportName = ctx.getCurrentSport();
                log.info("Merged sportName from context: {}", sportName);
            }
            if (district == null && ctx.getCurrentDistrict() != null) {
                district = ctx.getCurrentDistrict();
                log.info("Merged district from context: {}", district);
            }
            if (province == null && ctx.getCurrentProvince() != null) {
                province = ctx.getCurrentProvince();
                log.info("Merged province from context: {}", province);
            }
        }
        return new SearchContextHolder(sportName, district, province);
    }

    private String applyFilters(StadiumSearchRequest.StadiumSearchRequestBuilder builder, JsonNode args, SearchContextHolder context) {
        if (args.hasNonNull("keyword")) {
            builder.keyword(args.get("keyword").asText());
        }

        String sportError = applySportNameFilter(builder, args);
        if (sportError != null) {
            return sportError;
        }

        applyDistrictFilter(builder, args, context.province);
        applyPriceFilters(builder, args);
        applyFootballFieldTypeFilter(builder, args);
        applyDateTimeFilters(builder, args);
        applySort(builder, args);

        builder.page(0);
        builder.size(AI_SEARCH_RESULT_LIMIT);
        return null;
    }

    /**
     * Tìm sân theo tọa độ GPS — bán kính 15km, sort gần nhất trước.
     * Kết hợp với sportTypeId nếu user đã chỉ định môn.
     */
    private AiChatTurnResponse handleGpsSearch(JsonNode args, String llmMessage, String conversationKey,
                                               double lat, double lng) {
        log.info("GPS search: lat={}, lng={}", lat, lng);

        StadiumSearchRequest.StadiumSearchRequestBuilder builder = StadiumSearchRequest.builder()
                .userLat(lat)
                .userLng(lng)
                .radiusInKm(15.0)
                .sortBy("distance")
                .sortDirection("ASC")
                .page(0)
                .size(AI_SEARCH_RESULT_LIMIT);

        // Áp dụng sport filter nếu có
        String sportError = applySportNameFilter(builder, args);
        if (sportError != null) {
            return AiChatTurnResponse.messageOnly(sportError, "search_stadiums");
        }

        // Áp dụng datetime filter nếu user chỉ định giờ
        applyDateTimeFilters(builder, args);
        applyPriceFilters(builder, args);
        applyFootballFieldTypeFilter(builder, args);

        StadiumSearchRequest req = builder.build();
        List<StadiumResponse> stadiums;
        try {
            stadiums = postProcessSearchResults(publicStadiumService.searchStadiums(req).getContent());
        } catch (Exception e) {
            log.warn("GPS search failed, falling back to no-radius search", e);
            stadiums = List.of();
        }

        // Fallback: nới bán kính lên 30km nếu không tìm thấy gì trong 15km
        if (stadiums.isEmpty()) {
            log.info("GPS search: không có sân trong 15km, thử bán kính 30km");
            req.setRadiusInKm(30.0);
            try {
                stadiums = postProcessSearchResults(publicStadiumService.searchStadiums(req).getContent());
            } catch (Exception e) {
                log.warn("GPS fallback search also failed", e);
            }
        }

        if (stadiums.isEmpty()) {
            return AiChatTurnResponse.builder()
                    .intent("search_stadiums")
                    .message("Không tìm thấy sân nào trong vòng 30km quanh vị trí của bạn. " +
                            "Bạn thử chỉ định khu vực cụ thể hơn (tên quận/thành phố) để mình tìm nhé.")
                    .stadiums(List.of())
                    .build();
        }

        // Lưu context
        String sportName = args.hasNonNull("sportName") ? args.get("sportName").asText() : null;
        conversationContextService.saveCurrentFilters(conversationKey, sportName, null, null, null, null);
        conversationContextService.saveLastShownStadiums(conversationKey,
                stadiums.stream().map(StadiumResponse::getStadiumId).toList());

        String msg = (llmMessage != null && !llmMessage.isBlank())
                ? llmMessage
                : "Đây là các sân gần vị trí của bạn nhất:";

        return AiChatTurnResponse.builder()
                .intent("search_stadiums")
                .message(msg)
                .stadiums(stadiums)
                .build();
    }

    private List<StadiumResponse> getStadiumsWithFallback(List<StadiumResponse> stadiums, StadiumSearchRequest searchRequest) {
        if (stadiums.isEmpty()) {
            List<StadiumResponse> fallback = findStadiumsByParentFacilityNameFallback(searchRequest.getKeyword(), searchRequest.getFootballFieldType());
            if (!fallback.isEmpty()) {
                return postProcessSearchResults(fallback);
            } else {
                List<StadiumResponse> retried = retrySearchWithoutNoisyKeyword(searchRequest);
                return postProcessSearchResults(retried);
            }
        }
        return postProcessSearchResults(stadiums);
    }

    private record RelaxedSearchResult(List<StadiumResponse> stadiums, String relaxationNote) {
        static RelaxedSearchResult empty() {
            return new RelaxedSearchResult(List.of(), null);
        }
    }

    /**
     * Nới lỏng dần theo thứ tự: bỏ khu vực -> bỏ khoảng giá -> bỏ ngày/giờ, dừng ngay khi có kết
     * quả. Luôn giữ sportTypeId (đổi môn là đổi hẳn ý định tìm kiếm, không nên tự relax).
     */
    private RelaxedSearchResult searchWithRelaxation(StadiumSearchRequest original) {
        boolean hasLocation = original.getProvince() != null || original.getDistrict() != null || original.getAddress() != null;
        boolean hasPrice = original.getMinPrice() != null || original.getMaxPrice() != null;
        boolean hasDateTime = original.getTargetDate() != null || original.getStartTime() != null || original.getEndTime() != null;

        if (hasLocation) {
            StadiumSearchRequest relaxed = relaxationBaseBuilder(original)
                    .minPrice(original.getMinPrice()).maxPrice(original.getMaxPrice())
                    .targetDate(original.getTargetDate()).startTime(original.getStartTime()).endTime(original.getEndTime())
                    .build();
            List<StadiumResponse> stadiums = postProcessSearchResults(publicStadiumService.searchStadiums(relaxed).getContent());
            if (!stadiums.isEmpty()) {
                return new RelaxedSearchResult(stadiums,
                        "Chưa tìm thấy sân đúng khu vực bạn muốn, đây là các sân khác phù hợp hơn:");
            }
        }

        if (hasPrice) {
            StadiumSearchRequest relaxed = relaxationBaseBuilder(original)
                    .targetDate(original.getTargetDate()).startTime(original.getStartTime()).endTime(original.getEndTime())
                    .build();
            List<StadiumResponse> stadiums = postProcessSearchResults(publicStadiumService.searchStadiums(relaxed).getContent());
            if (!stadiums.isEmpty()) {
                return new RelaxedSearchResult(stadiums,
                        "Chưa tìm thấy sân đúng khoảng giá bạn muốn, đây là các sân khác gần với nhu cầu của bạn:");
            }
        }

        if (hasDateTime) {
            StadiumSearchRequest relaxed = relaxationBaseBuilder(original).build();
            List<StadiumResponse> stadiums = postProcessSearchResults(publicStadiumService.searchStadiums(relaxed).getContent());
            if (!stadiums.isEmpty()) {
                return new RelaxedSearchResult(stadiums,
                        "Chưa tìm thấy sân trống đúng khung giờ bạn muốn, đây là các sân khác cùng môn thể thao:");
            }
        }

        return RelaxedSearchResult.empty();
    }

    private StadiumSearchRequest.StadiumSearchRequestBuilder relaxationBaseBuilder(StadiumSearchRequest original) {
        return StadiumSearchRequest.builder()
                .sportTypeId(original.getSportTypeId())
                .sortBy(original.getSortBy())
                .sortDirection(original.getSortDirection())
                .page(0)
                .size(AI_SEARCH_RESULT_LIMIT);
    }

    /**
     * Model hay nhét cả cụm người dùng gõ vào keyword (vd "sân bóng Thủ Đức") khiến LIKE theo
     * tên sân rỗng, dù sportName/district đã mang đủ ý định tìm kiếm. Khi search + fallback đều
     * rỗng mà vẫn còn filter khác, thử lại 1 lần bỏ keyword — deterministic, không phụ thuộc
     * model có nghe lời prompt hay không.
     */
    private List<StadiumResponse> retrySearchWithoutNoisyKeyword(StadiumSearchRequest request) {
        boolean hasOtherFilters = request.getSportTypeId() != null || request.getAddress() != null
                || request.getProvince() != null || request.getDistrict() != null
                || request.getMinPrice() != null || request.getMaxPrice() != null
                || request.getTargetDate() != null || request.getStartTime() != null;
        if (request.getKeyword() == null || request.getKeyword().isBlank() || !hasOtherFilters) {
            return List.of();
        }
        log.info("AI search rỗng với keyword '{}' — thử lại không keyword (giữ các filter còn lại)", request.getKeyword());
        request.setKeyword(null);
        return publicStadiumService.searchStadiums(request).getContent();
    }

    /** Mặc định sort giá thấp nhất trước; "rating" khi người dùng hỏi sân tốt nhất. */
    private void applySort(StadiumSearchRequest.StadiumSearchRequestBuilder builder, JsonNode args) {
        String sortBy = args.hasNonNull("sortBy") ? args.get("sortBy").asText() : "price";
        if ("rating".equalsIgnoreCase(sortBy)) {
            builder.sortBy("averageRating");
            builder.sortDirection("DESC");
        } else {
            builder.sortBy("pricePerHour");
            builder.sortDirection("ASC");
        }
    }

    /**
     * Post-process kết quả search: ghép tên "Facility - Court" (Court con thường chỉ tên
     * "Sân 1"/"Sân 2", card UI không phân biệt được) + loại sân có lịch bảo trì trùm hôm nay
     * (bảo trì theo khung ngày cố tình không đổi stadiumStatus nên filter AVAILABLE không bắt được).
     */
    private List<StadiumResponse> postProcessSearchResults(List<StadiumResponse> stadiums) {
        if (stadiums.isEmpty()) {
            return stadiums;
        }
        List<Integer> ids = stadiums.stream().map(StadiumResponse::getStadiumId).toList();
        Map<Integer, Stadium> courtById = new HashMap<>();
        for (Stadium court : stadiumRepository.findCourtsForAiToolByIds(ids)) {
            courtById.put(court.getStadiumId(), court);
        }

        LocalDate today = LocalDate.now(clock);
        List<StadiumResponse> processed = new ArrayList<>();
        for (StadiumResponse response : stadiums) {
            Stadium court = courtById.get(response.getStadiumId());
            if (court != null && maintenanceScheduleService.isStadiumUnderMaintenance(court, today)) {
                log.debug("AI search: bỏ sân {} khỏi kết quả — đang có lịch bảo trì hôm nay", response.getStadiumId());
                continue;
            }
            String facilityName = (court != null && court.getParentStadium() != null)
                    ? court.getParentStadium().getStadiumName()
                    : null;
            if (facilityName != null && response.getStadiumName() != null
                    && !response.getStadiumName().toLowerCase().contains(facilityName.toLowerCase())) {
                response.setStadiumName(facilityName + " - " + response.getStadiumName());
            }
            processed.add(response);
        }
        return processed;
    }

    private String applySportNameFilter(StadiumSearchRequest.StadiumSearchRequestBuilder builder, JsonNode args) {
        if (!args.hasNonNull("sportName")) {
            return null;
        }
        String rawSportName = args.get("sportName").asText();
        Integer sportTypeId = resolveSportTypeId(rawSportName);
        if (sportTypeId == null) {
            return buildUnknownSportMessage(rawSportName);
        }
        builder.sportTypeId(sportTypeId);
        return null;
    }

    /** Message cuối cùng cho user (không có lượt LLM thứ 2 để rephrase — phải tự viết ở đây). */
    private String buildUnknownSportMessage(String rawSportName) {
        List<String> supportedSports = sportTypeRepository.findAll().stream()
                .map(SportType::getSportName)
                .toList();
        return "Không tìm thấy môn thể thao \"" + rawSportName + "\" trong hệ thống. Các môn hiện có: "
                + String.join(", ", supportedSports) + ".";
    }

    /**
     * Dùng deriveFromAddress thay vì resolveDistrict đơn thuần vì model đôi khi truyền cả tên
     * thành phố lẫn quận trong cùng 1 chuỗi (vd "Quận 1, Hồ Chí Minh").
     * Bug #3: Ưu tiên province từ context (Đà Nẵng) thay vì mặc định TP.HCM.
     */
    private void applyDistrictFilter(StadiumSearchRequest.StadiumSearchRequestBuilder builder, JsonNode args, String provinceFromContext) {
        // Bug #3 + compound_task fix: Nếu không có district nhưng có province từ context, dùng province đó
        // Also accept "location" as alias for district (compound_task planner uses location)
        String rawDistrict = args.hasNonNull("district") ? args.get("district").asText()
                : (args.hasNonNull("location") ? args.get("location").asText() : null);
        String rawProvince = args.hasNonNull("province") ? args.get("province").asText() : provinceFromContext;

        if (rawDistrict == null && rawProvince == null) {
            return;
        }

        VietnamLocationResolver.LocationMatch match = locationResolver.deriveFromAddress(rawDistrict != null ? rawDistrict : rawProvince);
        if (match.province() != null) {
            builder.province(match.province());
        } else if (rawProvince != null) {
            // Dùng province từ context nếu derive không tìm được
            builder.province(rawProvince);
        }
        if (match.district() != null) {
            builder.district(match.district());
        }
        if (match.province() == null && match.district() == null && rawDistrict != null) {
            builder.address(rawDistrict);
        }
    }

    private void applyPriceFilters(StadiumSearchRequest.StadiumSearchRequestBuilder builder, JsonNode args) {
        if (args.hasNonNull("minPrice")) {
            builder.minPrice(BigDecimal.valueOf(args.get("minPrice").asDouble()));
        }
        if (args.hasNonNull("maxPrice")) {
            builder.maxPrice(BigDecimal.valueOf(args.get("maxPrice").asDouble()));
        }
    }

    /**
     * Trích xuất loại sân bóng đá từ input (Sân 5 người, Sân 7 người).
     * Accepts: footballFieldType, player_count, playerCount, sport_type (all "5 người" etc.)
     */
    private void applyFootballFieldTypeFilter(StadiumSearchRequest.StadiumSearchRequestBuilder builder, JsonNode args) {
        String raw = args.hasNonNull("footballFieldType") ? args.get("footballFieldType").asText()
                : (args.hasNonNull("player_count") ? args.get("player_count").asText()
                : (args.hasNonNull("sport_type") ? args.get("sport_type").asText()
                : (args.hasNonNull("playerCount") ? String.valueOf(args.get("playerCount").asInt()) : null)));
        if (raw == null) {
            return;
        }
        try {
            String normalized = raw.toUpperCase()
                    .replace(" ", "").replaceAll("NGƯỜI", "").replaceAll("NGƯỜI", "");
            String value;
            if (normalized.contains("5")) {
                value = "FIVE_A_SIDE";
            } else if (normalized.contains("7")) {
                value = "SEVEN_A_SIDE";
            } else if (normalized.contains("11")) {
                value = "ELEVEN_A_SIDE";
            } else if (normalized.equalsIgnoreCase("FUTSAL")) {
                value = "FUTSAL";
            } else {
                value = raw.toUpperCase().trim();
            }
            com.sportvenue.entity.enums.FootballFieldType fieldType =
                    com.sportvenue.entity.enums.FootballFieldType.valueOf(value);
            builder.footballFieldType(fieldType);
            log.info("applyFootballFieldTypeFilter: '{}' -> {}", raw, fieldType);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid footballFieldType value '{}': {}", raw, e.getMessage());
        }
    }

    private void applyDateTimeFilters(StadiumSearchRequest.StadiumSearchRequestBuilder builder, JsonNode args) {
        if (args.hasNonNull("targetDate")) {
            try {
                builder.targetDate(LocalDate.parse(args.get("targetDate").asText()));
            } catch (Exception e) {
                log.warn("Invalid targetDate format", e);
            }
        }
        if (args.hasNonNull("startTime")) {
            try {
                builder.startTime(LocalTime.parse(args.get("startTime").asText(), DateTimeFormatter.ofPattern("HH:mm")));
            } catch (Exception e) {
                log.warn("Invalid startTime format", e);
            }
        }
        if (args.hasNonNull("endTime")) {
            try {
                builder.endTime(LocalTime.parse(args.get("endTime").asText(), DateTimeFormatter.ofPattern("HH:mm")));
            } catch (Exception e) {
                log.warn("Invalid endTime format", e);
            }
        }
    }

    /**
     * Người dùng chat thường gọi tên Facility cha (vd "Sân vận động Cẩm Lệ"), trong khi Court con
     * bên dưới lại đặt tên chung chung ("Sân 1") nên search theo keyword chính có thể rỗng dù sân
     * đó tồn tại thật. Thử lại theo tên Facility cha, rồi thử lại lần 2 không phân biệt dấu tiếng
     * Việt (model đôi khi tự đánh sai dấu không ổn định giữa các lần gọi).
     */
    private List<StadiumResponse> findStadiumsByParentFacilityNameFallback(String rawKeyword,
            com.sportvenue.entity.enums.FootballFieldType fieldType) {
        if (rawKeyword == null || rawKeyword.isBlank()) {
            return List.of();
        }

        List<Stadium> fallbackCourts = stadiumRepository.findCourtsByParentFacilityNameKeyword(rawKeyword, fieldType);

        if (fallbackCourts.isEmpty()) {
            List<String> tokens = meaningfulKeywordTokens(rawKeyword);
            if (!tokens.isEmpty()) {
                List<Integer> matchedIds = stadiumRepository.findAllCourtFacilityNames().stream()
                        .filter(p -> p.getFacilityName() != null && containsAllTokens(p.getFacilityName(), tokens))
                        .map(StadiumRepository.CourtFacilityNameProjection::getStadiumId)
                        .distinct()
                        .limit(AI_SEARCH_RESULT_LIMIT)
                        .toList();
                if (!matchedIds.isEmpty()) {
                    fallbackCourts = stadiumRepository.findCourtsForAiToolByIds(matchedIds);
                }
            }
        }

        return fallbackCourts.stream()
                .limit(AI_SEARCH_RESULT_LIMIT)
                .map(stadiumMapper::toResponse)
                .toList();
    }

    /** Từ chung chung trong tên sân — bỏ đi để chỉ so phần tên riêng khi fallback theo token. */
    private static final java.util.Set<String> GENERIC_KEYWORD_TOKENS = java.util.Set.of(
            "san", "bong", "da", "banh", "cau", "long", "ro", "chuyen", "tennis", "futsal", "pickleball",
            "van", "dong", "nha", "thi", "dau", "the", "thao", "court", "stadium", "field", "arena");

    private List<String> meaningfulKeywordTokens(String rawKeyword) {
        return java.util.Arrays.stream(locationResolver.stripDiacritics(rawKeyword).toLowerCase().split("\\s+"))
                .filter(t -> !t.isBlank() && !GENERIC_KEYWORD_TOKENS.contains(t))
                .toList();
    }

    private boolean containsAllTokens(String facilityName, List<String> tokens) {
        String stripped = locationResolver.stripDiacritics(facilityName).toLowerCase();
        return tokens.stream().allMatch(stripped::contains);
    }

    /**
     * So khớp không phân biệt dấu tiếng Việt — model hay đánh sai dấu ("Bông đá") hoặc dùng
     * cách gọi dân dã ("đá banh") khiến so khớp chính xác dấu trước đây thất bại.
     */
    Integer resolveSportTypeId(String sportName) {
        if (sportName == null || sportName.isBlank()) {
            return null;
        }

        List<SportType> sportTypes = sportTypeRepository.findAll();
        String searchKey = locationResolver.stripDiacritics(sportName).toLowerCase().trim();

        for (SportType st : sportTypes) {
            if (locationResolver.stripDiacritics(st.getSportName()).toLowerCase().equals(searchKey) ||
                    st.getSportCode().toLowerCase().equals(searchKey) ||
                    (st.getNameEn() != null && st.getNameEn().toLowerCase().equals(searchKey))) {
                return st.getSportTypeId();
            }
        }

        if (containsAny(searchKey, "bong da", "da bong", "da banh", "football", "soccer", "futsal")) {
            return findSportTypeByCode(sportTypes, "FOOTBALL");
        }
        if (containsAny(searchKey, "cau long", "badminton")) {
            return findSportTypeByCode(sportTypes, "BADMINTON");
        }
        if (containsAny(searchKey, "bong ro", "basketball")) {
            return findSportTypeByCode(sportTypes, "BASKETBALL");
        }
        if (containsAny(searchKey, "bong chuyen", "volleyball")) {
            return findSportTypeByCode(sportTypes, "VOLLEYBALL");
        }
        if (containsAny(searchKey, "tennis", "quan vot")) {
            return findSportTypeByCode(sportTypes, "TENNIS");
        }
        if (containsAny(searchKey, "pickleball", "pickle ball")) {
            return findSportTypeByCode(sportTypes, "PICKLEBALL");
        }
        if (containsAny(searchKey, "bong ban", "table tennis", "ping pong")) {
            return findSportTypeByCode(sportTypes, "TABLE_TENNIS");
        }

        return null;
    }

    private boolean containsAny(String key, String... aliases) {
        for (String alias : aliases) {
            if (key.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    private Integer findSportTypeByCode(List<SportType> list, String code) {
        return list.stream()
                .filter(st -> st.getSportCode().equalsIgnoreCase(code))
                .map(SportType::getSportTypeId)
                .findFirst()
                .orElse(null);
    }

    /**
     * BUG B FIX: Extract date hint from raw message to include in fallback response.
     */
    private String extractDateHint(String message) {
        if (message == null) {
            return "";
        }
        // Try to extract "thứ X tuần này", "thứ X", etc.
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "thứ\\s*(\\d+\\s*)?(?:tuần\\s*)?(?:này|sau)?|ngày\\s*mai|hôm\\s*nay",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(message);
        if (m.find()) {
            return m.group().trim();
        }
        return "";
    }
}
