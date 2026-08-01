package com.sportvenue.service.ai.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportvenue.dto.request.CreateMatchRequest;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.dto.response.DraftCreateMatchResponse;
import com.sportvenue.dto.response.MatchEligibleBookingResponse;
import com.sportvenue.dto.response.MatchResponse;
import com.sportvenue.entity.enums.MatchingType;
import com.sportvenue.entity.enums.SkillLevel;
import com.sportvenue.exception.BadRequestException;
import com.sportvenue.exception.ResourceNotFoundException;
import com.sportvenue.service.MatchRequestService;
import com.sportvenue.service.ai.AiConversationContextService;
import com.sportvenue.util.RelativeDateParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Thu thập đủ thông tin rồi tạo kèo từ một booking CONFIRMED trong tương lai. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateMatchHandler {

    private static final int MAX_CHOICES_IN_MESSAGE = 5;
    private static final String FORM_CONFIRMATION_PREFIX = "xac nhan tao keo:";
    private static final Pattern RAW_TIME_PATTERN = Pattern.compile(
            "(?<!\\d)([01]?\\d|2[0-3])\\s*(?:h|:|giờ)\\s*([0-5]?\\d)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern RAW_BOOKING_ID_PATTERN = Pattern.compile(
            "(?:booking|mã|#)\\s*#?(\\d+)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern RAW_MAX_PLAYERS_PATTERN = Pattern.compile(
            "(?:tối\\s*đa|số\\s*người|max)\\s*(?:là|:)?\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern RAW_PRICE_PATTERN = Pattern.compile(
            "(?:chia\\s*tiền|chia\\s*đều).*?(\\d[\\d.,]*)\\s*(?:đ|đồng|vnd)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Set<String> DRAFT_FIELDS = Set.of(
            "bookingId", "sportName", "stadiumName", "keyword", "date", "targetDate",
            "playDate", "startTime", "time", "description", "maxPlayers",
            "skillLevel", "splitPrice", "pricePerPlayer", "matchingType");

    private final MatchRequestService matchRequestService;
    private final AiConversationContextService conversationContextService;
    private final ObjectMapper objectMapper;
    private final RelativeDateParser relativeDateParser = new RelativeDateParser();

    public AiChatTurnResponse handle(JsonNode args, String llmMessage, Integer userId) {
        return handle(args, llmMessage, userId, null, null);
    }

    public AiChatTurnResponse handle(JsonNode args, String llmMessage, Integer userId, String rawUserMessage) {
        return handle(args, llmMessage, userId, rawUserMessage, null);
    }

    public AiChatTurnResponse handle(JsonNode args, String llmMessage, Integer userId,
                                     String rawUserMessage, String conversationKey) {
        if (userId == null) {
            return message("Bạn cần đăng nhập để tạo kèo từ lịch đặt sân đã xác nhận của mình.",
                    "need_more_info");
        }

        Optional<AiConversationContextService.PendingAction> pending = getPendingDraft(conversationKey);
        if (pending.isPresent() && isCancelDraftMessage(rawUserMessage)) {
            conversationContextService.clearPendingAction(conversationKey);
            return message("Đã hủy thao tác tạo kèo. Khi cần bạn cứ nhắn mình nhé.", "create_match");
        }

        Map<String, Object> draftData = pending
                .map(AiConversationContextService.PendingAction::getData)
                .map(HashMap::new)
                .orElseGet(HashMap::new);
        mergeArgs(draftData, args);
        mergeRawFields(draftData, rawUserMessage);

        List<MatchEligibleBookingResponse> eligible =
                matchRequestService.getEligibleBookingsForMatchCreation(userId);
        if (eligible.isEmpty()) {
            conversationContextService.clearPendingAction(conversationKey);
            return message("Bạn chưa có lịch đặt sân CONFIRMED nào trong tương lai để tạo kèo. "
                    + "Hãy đặt sân và hoàn tất xác nhận trước nhé.", "create_match");
        }

        JsonNode mergedArgs = objectMapper.valueToTree(draftData);
        SelectionResult selection = selectBooking(eligible, mergedArgs, rawUserMessage);
        if (selection.errorMessage() != null) {
            saveDraft(conversationKey, draftData, "bookingId");
            return message(selection.errorMessage(), "create_match");
        }
        if (selection.matches().size() != 1) {
            saveDraft(conversationKey, draftData, "bookingId");
            return message(buildChoiceMessage(selection.matches()), "create_match");
        }

        MatchEligibleBookingResponse booking = selection.matches().get(0);
        draftData.put("bookingId", booking.getBookingId());
        mergedArgs = objectMapper.valueToTree(draftData);

        List<String> missingFields = findMissingFields(mergedArgs);
        boolean confirmedFromForm = pending.isPresent() && isFormConfirmationMessage(rawUserMessage);
        if (!confirmedFromForm || !missingFields.isEmpty()) {
            saveDraft(conversationKey, draftData, String.join(",", missingFields));
            return informationResponse(booking, mergedArgs, missingFields);
        }

        CreateMatchRequest request;
        try {
            request = buildCreateRequest(mergedArgs, booking);
        } catch (IllegalArgumentException ex) {
            saveDraft(conversationKey, draftData, "invalidField");
            return message(ex.getMessage(), "need_more_info");
        }

        try {
            MatchResponse created = matchRequestService.createMatch(request, userId);
            conversationContextService.clearPendingAction(conversationKey);
            created.setIsOwner(true);
            return AiChatTurnResponse.builder()
                    .intent("create_match")
                    .message("Đã tạo kèo thành công cho " + booking.getStadiumName()
                            + " lúc " + formatTime(booking.getStartTime()) + " ngày "
                            + formatDate(booking.getPlayDate()) + ".")
                    .matches(List.of(created))
                    .build();
        } catch (BadRequestException | ResourceNotFoundException ex) {
            conversationContextService.clearPendingAction(conversationKey);
            log.info("Không thể tạo kèo tự động cho booking {}: {}", booking.getBookingId(), ex.getMessage());
            return message("Chưa thể tạo kèo: " + ex.getMessage(), "create_match");
        } catch (RuntimeException ex) {
            log.warn("Không thể tạo kèo tự động cho booking {}", booking.getBookingId(), ex);
            return message("Chưa thể tạo kèo lúc này. Bạn vui lòng thử lại.", "create_match");
        }
    }

    private SelectionResult selectBooking(List<MatchEligibleBookingResponse> eligible, JsonNode args,
                                          String rawUserMessage) {
        if (hasValue(args, "bookingId")) {
            int bookingId = args.get("bookingId").asInt(-1);
            List<MatchEligibleBookingResponse> exact = eligible.stream()
                    .filter(item -> item.getBookingId() == bookingId)
                    .toList();
            if (exact.isEmpty()) {
                return new SelectionResult(List.of(),
                        "Booking #" + bookingId + " không đủ điều kiện tạo kèo. "
                                + "Booking phải thuộc về bạn, đã CONFIRMED và chưa có kèo đang mở.");
            }
            return new SelectionResult(exact, null);
        }

        LocalDate requestedDate;
        LocalTime requestedTime;
        try {
            requestedDate = readDate(args, rawUserMessage);
            requestedTime = readTime(args, rawUserMessage);
        } catch (DateTimeParseException ex) {
            return new SelectionResult(List.of(),
                    "Ngày hoặc giờ chưa hợp lệ. Bạn hãy dùng giờ cụ thể, ví dụ 14:00 ngày mai.");
        }

        String requestedSport = defaultIfBlank(text(args, "sportName"), detectSport(rawUserMessage));
        String requestedStadium = firstText(args, "stadiumName", "keyword");

        List<MatchEligibleBookingResponse> matches = eligible.stream()
                .filter(item -> requestedDate == null || requestedDate.equals(item.getPlayDate()))
                .filter(item -> requestedTime == null || requestedTime.equals(item.getStartTime()))
                .filter(item -> requestedSport == null || sameSport(item.getSportName(), requestedSport))
                .filter(item -> requestedStadium == null
                        || containsNormalized(item.getStadiumName(), requestedStadium)
                        || containsNormalized(item.getComplexName(), requestedStadium))
                .toList();

        if (matches.isEmpty()) {
            return new SelectionResult(List.of(), "Không tìm thấy booking CONFIRMED phù hợp với ngày, giờ và môn "
                    + "bạn yêu cầu. Các lịch hiện có:\n" + formatChoices(eligible));
        }
        return new SelectionResult(matches, null);
    }

    private CreateMatchRequest buildCreateRequest(JsonNode args, MatchEligibleBookingResponse booking) {
        MatchingType matchingType = parseMatchingType(text(args, "matchingType"));
        int maxPlayers = matchingType == MatchingType.TEAM_VS_TEAM
                ? 2 : readMaxPlayers(args);
        SkillLevel skillLevel = parseSkillLevel(text(args, "skillLevel"));
        boolean splitPrice = args.get("splitPrice").asBoolean();
        BigDecimal pricePerPlayer = readPrice(args, splitPrice);

        String title = buildDefaultTitle(booking);
        String description = truncate(text(args, "description"), 1000);

        return CreateMatchRequest.builder()
                .bookingId(booking.getBookingId())
                .title(title)
                .description(description)
                .maxPlayers(maxPlayers)
                .skillLevel(skillLevel)
                .splitPrice(splitPrice)
                .pricePerPlayer(pricePerPlayer)
                .matchingType(matchingType)
                .build();
    }

    private int readMaxPlayers(JsonNode args) {
        int value = args.get("maxPlayers").asInt(-1);
        if (value < 2 || value > 50) {
            throw new IllegalArgumentException("Số người tối đa phải từ 2 đến 50.");
        }
        return value;
    }

    private BigDecimal readPrice(JsonNode args, boolean splitPrice) {
        if (!splitPrice) {
            return BigDecimal.ZERO;
        }
        if (!hasValue(args, "pricePerPlayer")) {
            throw new IllegalArgumentException(
                    "Bạn muốn chia tiền sân bao nhiêu cho mỗi người? Hãy cho mình số tiền cụ thể.");
        }
        BigDecimal value = args.get("pricePerPlayer").decimalValue();
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Tiền chia cho mỗi người phải lớn hơn 0.");
        }
        if (value.scale() > 2 || value.precision() - value.scale() > 10) {
            throw new IllegalArgumentException("Tiền chia mỗi người có tối đa 10 chữ số và 2 số lẻ.");
        }
        return value;
    }

    private MatchingType parseMatchingType(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Bạn chưa chọn hình thức ghép kèo.");
        }
        String value = normalize(raw);
        if (value.equals("team_vs_team") || value.contains("doi vs doi") || value.contains("cap keo")) {
            return MatchingType.TEAM_VS_TEAM;
        }
        return MatchingType.INDIVIDUAL;
    }

    private SkillLevel parseSkillLevel(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Bạn chưa chọn trình độ người chơi.");
        }
        String value = normalize(raw);
        if (value.equals("beginner") || value.contains("moi")) {
            return SkillLevel.BEGINNER;
        }
        if (value.equals("advanced") || value.contains("nang cao") || value.contains("chuyen nghiep")) {
            return SkillLevel.ADVANCED;
        }
        return SkillLevel.INTERMEDIATE;
    }

    private LocalDate readDate(JsonNode args, String rawUserMessage) {
        String raw = firstText(args, "date", "targetDate", "playDate");
        return raw == null ? relativeDateParser.parse(rawUserMessage) : LocalDate.parse(raw);
    }

    private LocalTime readTime(JsonNode args, String rawUserMessage) {
        String raw = firstText(args, "startTime", "time");
        if (raw != null) {
            return LocalTime.parse(raw);
        }
        if (rawUserMessage == null) {
            return null;
        }
        Matcher matcher = RAW_TIME_PATTERN.matcher(rawUserMessage);
        if (!matcher.find()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        String normalizedMessage = normalize(rawUserMessage);
        if (hour < 12 && (normalizedMessage.contains("chieu") || normalizedMessage.contains("toi"))) {
            hour += 12;
        }
        return LocalTime.of(hour, minute);
    }

    private String detectSport(String rawUserMessage) {
        String value = normalize(rawUserMessage);
        if (value.contains("bong da") || value.contains("da banh") || value.contains("football")) {
            return "Bóng đá";
        }
        if (value.contains("cau long") || value.contains("badminton")) {
            return "Cầu lông";
        }
        if (value.contains("bong ro") || value.contains("basketball")) {
            return "Bóng rổ";
        }
        if (value.contains("bong chuyen") || value.contains("volleyball")) {
            return "Bóng chuyền";
        }
        if (value.contains("pickleball")) {
            return "Pickleball";
        }
        if (value.contains("tennis") || value.contains("quan vot")) {
            return "Tennis";
        }
        return null;
    }

    private Optional<AiConversationContextService.PendingAction> getPendingDraft(String conversationKey) {
        Optional<AiConversationContextService.PendingAction> pending =
                conversationContextService.getPendingAction(conversationKey);
        if (pending == null) {
            return Optional.empty();
        }
        return pending.filter(action -> "create_match".equals(action.getIntent()));
    }

    private void mergeArgs(Map<String, Object> draftData, JsonNode args) {
        if (args == null || !args.isObject()) {
            return;
        }
        args.fields().forEachRemaining(entry -> {
            if (DRAFT_FIELDS.contains(entry.getKey()) && !entry.getValue().isNull()) {
                draftData.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class));
            }
        });
    }

    private void mergeRawFields(Map<String, Object> draftData, String rawUserMessage) {
        if (rawUserMessage == null || rawUserMessage.isBlank()) {
            return;
        }
        Matcher bookingMatcher = RAW_BOOKING_ID_PATTERN.matcher(rawUserMessage);
        if (bookingMatcher.find()) {
            draftData.put("bookingId", Integer.parseInt(bookingMatcher.group(1)));
        }

        String normalized = normalize(rawUserMessage);
        if (normalized.contains("ghep le") || normalized.contains("individual")) {
            draftData.put("matchingType", MatchingType.INDIVIDUAL.name());
        } else if (normalized.contains("doi vs doi") || normalized.contains("cap keo")
                || normalized.contains("team vs team")) {
            draftData.put("matchingType", MatchingType.TEAM_VS_TEAM.name());
        }

        if (normalized.contains("trinh do moi") || normalized.contains("nguoi moi")
                || normalized.contains("beginner")) {
            draftData.put("skillLevel", SkillLevel.BEGINNER.name());
        } else if (normalized.contains("trung binh") || normalized.contains("intermediate")) {
            draftData.put("skillLevel", SkillLevel.INTERMEDIATE.name());
        } else if (normalized.contains("nang cao") || normalized.contains("chuyen nghiep")
                || normalized.contains("advanced")) {
            draftData.put("skillLevel", SkillLevel.ADVANCED.name());
        }

        if (normalized.contains("khong chia tien") || normalized.contains("khong chia")) {
            draftData.put("splitPrice", false);
        } else if (normalized.contains("chia tien") || normalized.contains("chia deu")) {
            draftData.put("splitPrice", true);
        }

        Matcher maxPlayersMatcher = RAW_MAX_PLAYERS_PATTERN.matcher(rawUserMessage);
        if (maxPlayersMatcher.find()) {
            draftData.put("maxPlayers", Integer.parseInt(maxPlayersMatcher.group(1)));
        }
        Matcher priceMatcher = RAW_PRICE_PATTERN.matcher(rawUserMessage);
        if (priceMatcher.find()) {
            String rawPrice = priceMatcher.group(1).replace(".", "").replace(",", "");
            draftData.put("pricePerPlayer", new BigDecimal(rawPrice));
        }
    }

    private List<String> findMissingFields(JsonNode args) {
        List<String> missing = new ArrayList<>();
        if (!hasValue(args, "matchingType")) {
            missing.add("matchingType");
        }
        if (!hasValue(args, "skillLevel")) {
            missing.add("skillLevel");
        }
        if (!hasValue(args, "splitPrice")) {
            missing.add("splitPrice");
        }

        MatchingType type = hasValue(args, "matchingType")
                ? parseMatchingType(text(args, "matchingType")) : null;
        if (type == MatchingType.INDIVIDUAL && !hasValue(args, "maxPlayers")) {
            missing.add("maxPlayers");
        }
        if (hasValue(args, "splitPrice") && args.get("splitPrice").asBoolean(false)
                && !hasValue(args, "pricePerPlayer")) {
            missing.add("pricePerPlayer");
        }
        return missing;
    }

    private void saveDraft(String conversationKey, Map<String, Object> draftData, String missingField) {
        conversationContextService.savePendingAction(conversationKey,
                new AiConversationContextService.PendingAction("create_match", draftData, missingField));
    }

    private AiChatTurnResponse informationResponse(MatchEligibleBookingResponse booking, JsonNode args,
                                                   List<String> missingFields) {
        String defaultTitle = buildDefaultTitle(booking);
        DraftCreateMatchResponse draft = DraftCreateMatchResponse.builder()
                .bookingId(booking.getBookingId())
                .defaultTitle(defaultTitle)
                .stadiumName(booking.getStadiumName())
                .sportName(booking.getSportName())
                .playDate(booking.getPlayDate())
                .startTime(booking.getStartTime())
                .endTime(booking.getEndTime())
                .matchingType(text(args, "matchingType"))
                .maxPlayers(hasValue(args, "maxPlayers") ? args.get("maxPlayers").asInt() : null)
                .skillLevel(text(args, "skillLevel"))
                .splitPrice(hasValue(args, "splitPrice") ? args.get("splitPrice").asBoolean() : null)
                .pricePerPlayer(hasValue(args, "pricePerPlayer")
                        ? args.get("pricePerPlayer").decimalValue() : null)
                .missingFields(missingFields)
                .build();
        return AiChatTurnResponse.builder()
                .intent("create_match")
                .message("Đã chọn booking #" + booking.getBookingId() + " — " + booking.getStadiumName()
                        + ", " + formatTime(booking.getStartTime()) + " ngày "
                        + formatDate(booking.getPlayDate()) + ". Tiêu đề sẽ được tạo tự động là “"
                        + defaultTitle + "”. Vui lòng chọn thông tin trong form bên dưới.")
                .draftCreateMatch(draft)
                .build();
    }

    private boolean isCancelDraftMessage(String rawUserMessage) {
        String normalized = normalize(rawUserMessage);
        return normalized.equals("thoi") || normalized.equals("huy")
                || normalized.contains("huy tao keo") || normalized.contains("khong tao nua")
                || normalized.contains("bo qua");
    }

    private boolean isFormConfirmationMessage(String rawUserMessage) {
        return normalize(rawUserMessage).startsWith(FORM_CONFIRMATION_PREFIX);
    }

    private boolean sameSport(String actual, String requested) {
        return canonicalSport(actual).equals(canonicalSport(requested));
    }

    private String canonicalSport(String value) {
        String normalized = normalize(value);
        if (normalized.contains("cau long") || normalized.contains("badminton")) {
            return "BADMINTON";
        }
        if (normalized.contains("bong da") || normalized.contains("da banh")
                || normalized.contains("football") || normalized.contains("soccer")) {
            return "FOOTBALL";
        }
        if (normalized.contains("bong ro") || normalized.contains("basketball")) {
            return "BASKETBALL";
        }
        if (normalized.contains("bong chuyen") || normalized.contains("volleyball")) {
            return "VOLLEYBALL";
        }
        if (normalized.contains("pickleball")) {
            return "PICKLEBALL";
        }
        if (normalized.contains("tennis") || normalized.contains("quan vot")) {
            return "TENNIS";
        }
        return normalized;
    }

    private String buildChoiceMessage(List<MatchEligibleBookingResponse> choices) {
        return "Có nhiều booking phù hợp. Bạn hãy chọn mã booking muốn tạo kèo:\n"
                + formatChoices(choices)
                + "\nVí dụ: tạo kèo cho booking #123.";
    }

    private String formatChoices(List<MatchEligibleBookingResponse> choices) {
        return choices.stream()
                .limit(MAX_CHOICES_IN_MESSAGE)
                .map(item -> "• #" + item.getBookingId() + " — " + item.getStadiumName()
                        + ", " + formatTime(item.getStartTime()) + " ngày " + formatDate(item.getPlayDate()))
                .collect(Collectors.joining("\n"));
    }

    private AiChatTurnResponse message(String content, String intent) {
        return AiChatTurnResponse.messageOnly(content, intent);
    }

    private boolean hasValue(JsonNode args, String field) {
        return args != null && args.hasNonNull(field) && !args.get(field).asText().isBlank();
    }

    private String text(JsonNode args, String field) {
        return hasValue(args, field) ? args.get(field).asText().trim() : null;
    }

    private String firstText(JsonNode args, String... fields) {
        for (String field : fields) {
            String value = text(args, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean containsNormalized(String source, String expected) {
        return source != null && normalize(source).contains(normalize(expected));
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String buildDefaultTitle(MatchEligibleBookingResponse booking) {
        String stadiumName = defaultIfBlank(booking.getStadiumName(), "Sân thể thao").trim();
        String complexName = booking.getComplexName();
        String venueLabel = stadiumName;

        if (complexName != null && !complexName.isBlank()) {
            complexName = complexName.trim();
            String normalizedStadium = normalize(stadiumName);
            String normalizedComplex = normalize(complexName);
            if (!normalizedStadium.contains(normalizedComplex)) {
                venueLabel = normalizedComplex.contains(normalizedStadium)
                        ? complexName
                        : stadiumName + " — " + complexName;
            }
        }

        return truncate("Kèo " + booking.getSportName() + " tại " + venueLabel, 100);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String formatDate(LocalDate date) {
        return date == null ? "không rõ ngày" : String.format("%02d/%02d/%04d",
                date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    private String formatTime(LocalTime time) {
        return time == null ? "không rõ giờ" : String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    private record SelectionResult(List<MatchEligibleBookingResponse> matches, String errorMessage) { }
}
