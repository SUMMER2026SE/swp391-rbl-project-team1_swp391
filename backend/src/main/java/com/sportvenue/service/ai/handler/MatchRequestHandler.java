package com.sportvenue.service.ai.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.dto.response.MatchResponse;
import com.sportvenue.entity.SportType;
import com.sportvenue.repository.SportTypeRepository;
import com.sportvenue.service.MatchRequestService;
import com.sportvenue.service.ai.AiConversationContextService;
import com.sportvenue.util.location.VietnamLocationResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Xử lý intent "find_match" — port từ CustomerAgentToolProvider.handleFindMatchRequests
 * (nhánh ai-chatting cũ).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchRequestHandler {

    private final MatchRequestService matchRequestService;
    private final SportTypeRepository sportTypeRepository;
    private final VietnamLocationResolver locationResolver;
    private final AiConversationContextService conversationContextService;

    /** Mặc định 5, chặn trần 10 — card UI trong khung chat không bị quá dài. */
    private static final int DEFAULT_SIZE = 5;
    private static final int MAX_SIZE = 10;

    public AiChatTurnResponse handle(JsonNode args, String llmMessage, Integer userId) {
        return handle(args, llmMessage, null, userId);
    }

    public AiChatTurnResponse handle(JsonNode args, String llmMessage, String conversationKey) {
        return handle(args, llmMessage, conversationKey, null);
    }

    public AiChatTurnResponse handle(JsonNode args, String llmMessage, String conversationKey, Integer userId) {
        if (args == null || args.isNull() || args.isMissingNode()) {
            return handleDefaultMatches(llmMessage, conversationKey, userId);
        }

        int page = args.hasNonNull("page") ? args.get("page").asInt() : 0;
        int size = args.hasNonNull("size") ? Math.min(args.get("size").asInt(), MAX_SIZE) : DEFAULT_SIZE;

        String location = resolveLocation(args, conversationKey);

        Integer sportTypeId = null;
        if (args.hasNonNull("sportName")) {
            String rawSportName = args.get("sportName").asText();
            sportTypeId = resolveSportTypeId(rawSportName);
            if (sportTypeId == null) {
                List<String> supportedSports = sportTypeRepository.findAll().stream()
                        .map(SportType::getSportName)
                        .toList();
                return AiChatTurnResponse.builder()
                        .message("Không tìm thấy môn thể thao \"" + rawSportName + "\" trong hệ thống. Các môn hiện có: "
                                + String.join(", ", supportedSports) + ".")
                        .intent("find_match")
                        .build();
            }
        }

        if (sportTypeId == null && conversationKey != null) {
            sportTypeId = conversationContextService.getContext(conversationKey)
                    .filter(ctx -> ctx.getCurrentSport() != null)
                    .map(ctx -> {
                        log.info("Merged sportName from context for match search: {}", ctx.getCurrentSport());
                        return resolveSportTypeId(ctx.getCurrentSport());
                    })
                    .orElse(null);
        }

        if (sportTypeId == null) {
            List<String> supportedSports = sportTypeRepository.findAll().stream()
                    .map(SportType::getSportName)
                    .toList();
            return AiChatTurnResponse.builder()
                    .intent("need_more_info")
                    .message("Bạn muốn tìm kèo cho môn thể thao nào vậy? Hệ thống đang có: "
                            + String.join(", ", supportedSports) + ". Cho mình biết để tìm kèo phù hợp nhé.")
                    .build();
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<MatchResponse> matches = matchRequestService.getActiveMatches(pageable, location, sportTypeId);

        if (matches.isEmpty()) {
            return handleNoMatchesFound(location, sportTypeId);
        }

        List<MatchResponse> matchList = enrichWithOwnerFlag(matches.getContent(), userId);

        if (conversationKey != null) {
            List<Integer> matchIds = matchList.stream()
                    .map(MatchResponse::getMatchId)
                    .toList();
            conversationContextService.saveLastShownMatches(conversationKey, matchIds);
        }

        return AiChatTurnResponse.builder()
                .message(llmMessage)
                .intent("find_match")
                .matches(matchList)
                .build();
    }

    private AiChatTurnResponse handleDefaultMatches(String llmMessage, String conversationKey, Integer userId) {
        Pageable pageable = PageRequest.of(0, DEFAULT_SIZE);
        Page<MatchResponse> matches = matchRequestService.getActiveMatches(pageable, null, null);
        List<MatchResponse> matchList = enrichWithOwnerFlag(matches.getContent(), userId);
        if (matchList.isEmpty()) {
            return AiChatTurnResponse.builder()
                    .message("Hiện tại chưa có kèo ghép nào đang mở.")
                    .intent("find_match")
                    .matches(List.of())
                    .build();
        }
        if (conversationKey != null) {
            conversationContextService.saveLastShownMatches(conversationKey, matchList.stream().map(MatchResponse::getMatchId).toList());
        }
        return AiChatTurnResponse.builder()
                .message(llmMessage != null && !llmMessage.isBlank() ? llmMessage : "Dưới đây là một số kèo ghép hiện đang mở:")
                .intent("find_match")
                .matches(matchList)
                .build();
    }

    private List<MatchResponse> enrichWithOwnerFlag(List<MatchResponse> matches, Integer userId) {
        if (userId == null) {
            return matches;
        }
        return matches.stream()
                .map(m -> MatchResponse.builder()
                        .matchId(m.getMatchId())
                        .hostName(m.getHostName())
                        .hostUserId(m.getHostUserId())
                        .stadiumName(m.getStadiumName())
                        .complexName(m.getComplexName())
                        .stadiumAddress(m.getStadiumAddress())
                        .sportName(m.getSportName())
                        .title(m.getTitle())
                        .description(m.getDescription())
                        .playDate(m.getPlayDate())
                        .startTime(m.getStartTime())
                        .endTime(m.getEndTime())
                        .maxPlayers(m.getMaxPlayers())
                        .currentPlayers(m.getCurrentPlayers())
                        .skillLevel(m.getSkillLevel())
                        .splitPrice(m.getSplitPrice())
                        .pricePerPlayer(m.getPricePerPlayer())
                        .matchStatus(m.getMatchStatus())
                        .matchingType(m.getMatchingType())
                        .cancelReason(m.getCancelReason())
                        .createdAt(m.getCreatedAt())
                        .isOwner(userId.equals(m.getHostUserId()))
                        .build())
                .toList();
    }

    private String resolveLocation(JsonNode args, String conversationKey) {
        if (args.hasNonNull("location")) {
            String rawLocation = args.get("location").asText();
            VietnamLocationResolver.LocationMatch match = locationResolver.deriveFromAddress(rawLocation);
            if (match.district() != null) {
                return match.district();
            } else if (match.province() != null) {
                return match.province();
            } else {
                return rawLocation;
            }
        } else if (conversationKey != null) {
            return conversationContextService.getContext(conversationKey)
                    .filter(ctx -> ctx.getCurrentProvince() != null)
                    .map(ctx -> {
                        log.info("Merged province from context for match search: {}", ctx.getCurrentProvince());
                        return ctx.getCurrentProvince();
                    })
                    .orElse(null);
        }
        return null;
    }

    private AiChatTurnResponse handleNoMatchesFound(String location, Integer sportTypeId) {
        StringBuilder noMatchMessage = new StringBuilder("Chưa tìm thấy kèo ghép nào");
        if (location != null) {
            noMatchMessage.append(" ở ").append(location);
        }
        if (sportTypeId != null) {
            String sportName = sportTypeRepository.findById(sportTypeId)
                    .map(SportType::getSportName)
                    .orElse("môn này");
            noMatchMessage.append(" cho ").append(sportName);
        }
        noMatchMessage.append(". Bạn có thể thử đổi khu vực hoặc môn thể thao.");
        return AiChatTurnResponse.builder()
                .message(noMatchMessage.toString())
                .intent("find_match")
                .matches(List.of())
                .build();
    }

    /** So khớp không phân biệt dấu tiếng Việt — dùng chung alias với StadiumSearchHandler. */
    private Integer resolveSportTypeId(String sportName) {
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
        if (searchKey.contains("bong da") || searchKey.contains("da bong") || searchKey.contains("da banh")
                || searchKey.contains("football") || searchKey.contains("soccer") || searchKey.contains("futsal")) {
            return findSportTypeByCode(sportTypes, "FOOTBALL");
        }
        if (searchKey.contains("cau long") || searchKey.contains("badminton")) {
            return findSportTypeByCode(sportTypes, "BADMINTON");
        }
        if (searchKey.contains("bong ro") || searchKey.contains("basketball")) {
            return findSportTypeByCode(sportTypes, "BASKETBALL");
        }
        if (searchKey.contains("bong chuyen") || searchKey.contains("volleyball")) {
            return findSportTypeByCode(sportTypes, "VOLLEYBALL");
        }
        if (searchKey.contains("tennis") || searchKey.contains("quan vot")) {
            return findSportTypeByCode(sportTypes, "TENNIS");
        }
        if (searchKey.contains("pickleball") || searchKey.contains("pickle ball")) {
            return findSportTypeByCode(sportTypes, "PICKLEBALL");
        }
        if (searchKey.contains("bong ban") || searchKey.contains("table tennis") || searchKey.contains("ping pong")) {
            return findSportTypeByCode(sportTypes, "TABLE_TENNIS");
        }
        return null;
    }

    private Integer findSportTypeByCode(List<SportType> list, String code) {
        return list.stream()
                .filter(st -> st.getSportCode().equalsIgnoreCase(code))
                .map(SportType::getSportTypeId)
                .findFirst()
                .orElse(null);
    }
}
