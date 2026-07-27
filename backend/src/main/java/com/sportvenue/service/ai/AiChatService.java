package com.sportvenue.service.ai;

import com.sportvenue.dto.request.AiChatTurnRequest;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.security.UserPrincipal;

public interface AiChatService {

    /**
     * Xử lý 1 lượt chat — 1 lệnh gọi Groq blocking, không streaming (xem docs/ai_chatbot_rebuild_plan.md).
     *
     * @param conversationKey định danh hội thoại (theo user/session/ip — xem AiChatController) dùng để
     *                        tra/lưu lastShownResults; truyền null nếu không cần tính năng này.
     */
    AiChatTurnResponse handleChat(AiChatTurnRequest request, UserPrincipal userPrincipal, String conversationKey);

    /** Xóa Redis context của user — dùng khi logout để không bị truy cập context của user khác. */
    void clearContextForUser(Integer userId);
}
