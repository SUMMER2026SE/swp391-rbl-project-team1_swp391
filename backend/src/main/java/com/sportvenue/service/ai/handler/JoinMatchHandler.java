package com.sportvenue.service.ai.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.entity.MatchRequest;
import com.sportvenue.entity.User;
import com.sportvenue.entity.enums.MatchStatus;
import com.sportvenue.entity.enums.MatchingType;
import com.sportvenue.entity.enums.SkillLevel;
import com.sportvenue.repository.MatchRequestRepository;
import com.sportvenue.repository.UserRepository;
import com.sportvenue.service.MatchRequestService;
import com.sportvenue.service.ai.AiConversationContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Xử lý intent "join_match" — tham gia kèo ghép trực tiếp từ chat.
 * Yêu cầu: người dùng phải đăng nhập và chỉ định kèo muốn tham gia.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JoinMatchHandler {

    private final MatchRequestService matchRequestService;
    private final MatchRequestRepository matchRequestRepository;
    private final AiConversationContextService conversationContextService;
    private final UserRepository userRepository;

    /**
     * Xử lý yêu cầu tham gia kèo từ chat.
     *
     * @param args JSON params từ LLM với các trường:
     *             - matchId (Integer): ID kèo ghép muốn tham gia, hoặc
     *             - matchIndex (Integer): chỉ số kèo đã hiển thị gần nhất (0-based)
     *             - message (String, optional): lời nhắn gửi kèm khi xin tham gia
     * @param llmMessage message từ LLM
     * @param conversationKey key của cuộc hội thoại
     * @param userId ID của user đang đăng nhập
     * @return AiChatTurnResponse chứa kết quả tham gia kèo
     */
    public AiChatTurnResponse handle(JsonNode args, String llmMessage, String conversationKey, Integer userId) {
        if (userId == null) {
            return errorResponse("Bạn cần đăng nhập để tham gia kèo qua chat. Vui lòng đăng nhập và thử lại.");
        }

        if (args == null || args.isNull() || args.isMissingNode()) {
            return errorResponse("Chưa xác định được kèo bạn muốn tham gia. Hãy tìm kèo trước (vd hỏi \"tìm kèo bóng đá ở Thủ Đức\") rồi mới tham gia.");
        }

        // Resolve matchId
        Integer matchId = resolveMatchId(args, conversationKey);
        if (matchId == null) {
            return errorResponse("Chưa xác định được kèo bạn muốn tham gia. Hãy tìm kèo trước (vd hỏi \"tìm kèo bóng đá ở Thủ Đức\") rồi mới tham gia.");
        }
        if (matchId <= 0) {
            return errorResponse("ID kèo không hợp lệ. Hãy tìm kèo trước để chọn đúng kèo bạn muốn tham gia.");
        }

        // Validate match exists
        Optional<MatchRequest> matchOpt = matchRequestRepository.findById(matchId);
        if (matchOpt.isEmpty()) {
            return errorResponse("Không tìm thấy kèo với ID " + matchId + ". Kèo có thể đã bị xóa hoặc hết hạn.");
        }
        MatchRequest match = matchOpt.get();

        // Validate match is still open
        if (match.getMatchStatus() != MatchStatus.OPEN) {
            String statusMsg = match.getMatchStatus() == MatchStatus.FULL
                    ? "Kèo này đã đầy người."
                    : "Kèo này đã bị hủy hoặc không còn nhận người tham gia.";
            return errorResponse(statusMsg + " Bạn có thể tìm kèo khác.");
        }

        // Validate match is not in the past
        LocalDate nowDate = LocalDate.now();
        LocalTime nowTime = LocalTime.now();
        if (match.getPlayDate().isBefore(nowDate) ||
            (match.getPlayDate().isEqual(nowDate) && match.getStartTime().isBefore(nowTime))) {
            return errorResponse("Kèo này đã diễn ra rồi, không thể tham gia.");
        }

        // Check if user is trying to join their own match
        if (match.getUser().getUserId().equals(userId)) {
            return errorResponse("Bạn không thể tham gia kèo của chính mình.");
        }

        // Get optional message
        String message = null;
        if (args.hasNonNull("message")) {
            message = args.get("message").asText();
        }

        // Auto-generate team name + message for TEAM_VS_TEAM if not provided
        if (match.getMatchingType() == MatchingType.TEAM_VS_TEAM
                && (message == null || message.trim().isEmpty())) {
            String userName = "Đội thách đấu";
            try {
                User user = userRepository.findById(userId).orElse(null);
                if (user != null && !user.getFullName().isBlank()) {
                    userName = "Đội " + user.getFullName();
                }
            } catch (Exception e) {
                log.warn("Could not fetch user name for auto team name, using default", e);
            }
            String skillVi = mapSkillLevelToVietnamese(match.getSkillLevel());
            message = userName + " trình độ " + skillVi + " muốn nhận kèo";
            log.info("Auto-generated TEAM_VS_TEAM join message for user {}: {}", userId, message);
        }

        // Create draft instead of executing join immediately
        try {
            com.sportvenue.dto.response.DraftJoinMatchResponse draft = com.sportvenue.dto.response.DraftJoinMatchResponse.builder()
                    .matchId(match.getMatchId())
                    .title(match.getTitle())
                    .stadiumName(getStadiumName(match))
                    .playDate(match.getPlayDate().toString())
                    .time(match.getStartTime() + " - " + match.getEndTime())
                    .userMessage(message != null ? message : "")
                    .build();

            return AiChatTurnResponse.builder()
                    .message("Vui lòng kiểm tra lại thông tin và xác nhận tham gia kèo bên dưới:")
                    .intent("confirm_join_match")
                    .draftJoinMatch(draft)
                    .build();

        } catch (Exception e) {
            log.error("Unexpected error during join match draft creation", e);
            return errorResponse("Đã xảy ra lỗi khi xử lý yêu cầu tham gia kèo. Vui lòng thử lại sau.");
        }
    }

    private Integer resolveMatchId(JsonNode args, String conversationKey) {
        if (args.hasNonNull("matchId")) {
            return args.get("matchId").asInt();
        }
        if (args.hasNonNull("matchIndex")) {
            int matchIndex = args.get("matchIndex").asInt();
            return conversationContextService.resolveMatchIdByIndex(conversationKey, matchIndex).orElse(null);
        }
        return null;
    }

    private AiChatTurnResponse errorResponse(String message) {
        return AiChatTurnResponse.builder()
                .message(message)
                .intent("join_match")
                .build();
    }

    private String getStadiumName(MatchRequest match) {
        if (match.getStadium() != null) {
            return match.getStadium().getStadiumName();
        }
        if (match.getPreferredCourt() != null) {
            return match.getPreferredCourt().getStadiumName();
        }
        if (match.getPreferredFacility() != null) {
            return match.getPreferredFacility().getStadiumName();
        }
        if (match.getComplex() != null) {
            return match.getComplex().getName();
        }
        return "Chưa xác định";
    }

    private String mapSkillLevelToVietnamese(SkillLevel level) {
        if (level == null) return "trung bình";
        return switch (level) {
            case BEGINNER -> "cơ bản";
            case INTERMEDIATE -> "trung bình";
            case ADVANCED -> "nâng cao";
        };
    }
}
