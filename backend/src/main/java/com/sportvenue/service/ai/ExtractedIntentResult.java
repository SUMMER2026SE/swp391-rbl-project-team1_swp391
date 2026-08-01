package com.sportvenue.service.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JSON 1 lượt chat mà Groq trả về — parse trực tiếp từ nội dung {@code choices[0].message.content}
 * (JSON mode). Groq JSON Mode chỉ đảm bảo output là JSON hợp lệ, KHÔNG đảm bảo đúng schema
 * (field bị thiếu/sai kiểu vẫn có thể xảy ra) — nên field ở đây có default rõ ràng, không được để
 * null lan xuống tầng dispatch (xem docs/ai_chatbot_rebuild_plan.md mục 6.1).
 *
 * {@code intent} hợp lệ: "search_stadiums" | "get_slots" | "find_match" | "create_match" | "get_policy" |
 * "need_more_info" | "out_of_scope" | "unknown" (fallback khi parse lỗi hoàn toàn).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtractedIntentResult {

    private String intent = "unknown";
    private Double confidence = 1.0;
    private String message = "";
    private JsonNode params;

    /**
     * Field initializer "unknown" chỉ áp dụng khi Jackson KHÔNG set field (key vắng mặt trong
     * JSON) — nếu Groq trả tường minh {@code "intent": null}, Jackson vẫn gọi setIntent(null) đè
     * default. Override getter để chặn null lan xuống dispatch trong mọi trường hợp.
     */
    public String getIntent() {
        return (intent == null || intent.isBlank()) ? "unknown" : intent;
    }

    public String getMessage() {
        return message == null ? "" : message;
    }

    /** Không bao giờ trả null — chỗ gọi luôn dùng được .hasNonNull()/.get() an toàn dù thiếu params. */
    public JsonNode getParams() {
        return params != null ? params : MissingNode.getInstance();
    }

    public static ExtractedIntentResult unknown() {
        ExtractedIntentResult result = new ExtractedIntentResult();
        result.setIntent("unknown");
        result.setMessage("Xin lỗi, tôi chưa hiểu ý bạn, bạn có thể nói rõ hơn được không?");
        return result;
    }
}
