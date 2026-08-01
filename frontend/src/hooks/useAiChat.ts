import { useState, useEffect, useRef, useCallback } from "react";
import { useSession } from "next-auth/react";
import { sendChatMessage } from "@/lib/ai-chat-api";
import api from "@/lib/api";
import {
  ChatMessage,
  TimeSlotResponse,
  BookingAiResponse,
  DraftCreateMatchResponse,
} from "@/types/aiChat";
import { StadiumResponse } from "@/types/stadium";
import { MatchResponse } from "@/types/match";

/** Tạo session ID cố định cho guest — tồn tại trong tab hiện tại (sessionStorage). */
function getDefaultMessages(): MessageItem[] {
  return [
    {
      id: "welcome",
      type: "assistant",
      content:
        "Xin chào! Tôi là trợ lý AI của SportHub. Tôi có thể giúp bạn:\n• Tìm sân theo môn, khu vực, giá\n• Xem giờ trống và đặt sân trực tiếp\n• Tìm kèo ghép và tham gia kèo\n\nBạn cần tôi giúp gì?",
      timestamp: new Date().toLocaleTimeString("vi-VN", {
        hour: "2-digit",
        minute: "2-digit",
      }),
    },
  ];
}

function getGuestSessionId(): string {
  if (typeof window === "undefined") return "";
  let sid = sessionStorage.getItem("ai_guest_session_id");
  if (!sid) {
    sid = crypto.randomUUID();
    sessionStorage.setItem("ai_guest_session_id", sid);
  }
  return sid;
}

/** Lấy storage key dựa trên trạng thái auth. */
function getStorageKey(userId: number | undefined): string {
  return userId != null
    ? `ai_chat_history_${userId}`
    : `ai_chat_guest_${getGuestSessionId()}`;
}

export interface MessageItem {
  id: string | number;
  type: string; // "user" | "assistant"
  content: string;
  intent?: string | null; // Intent type (e.g. "create_booking", "cancel_booking")
  timestamp: string;
  stadiums?: StadiumResponse[] | null;
  slots?: TimeSlotResponse[] | null;
  matches?: MatchResponse[] | null;
  /** Danh sách booking của user (intent: my_bookings, booking_status, cancel_booking) */
  bookings?: BookingAiResponse[] | null;
  policyText?: string | null;
  bookingId?: number | null; // ID booking vừa tạo (intent: create_booking) - deprecated
  draftBooking?: any | null; // Thông tin booking nháp để user confirm
  matchId?: number | null; // ID kèo vừa tham gia (intent: join_match)
  draftJoinMatch?: any | null; // Thông tin kèo nháp để user confirm
  draftCreateMatch?: DraftCreateMatchResponse | null; // Form cấu hình trước khi tạo kèo
  isHistory?: boolean; // Cờ đánh dấu tin nhắn load từ lịch sử cũ, không chạy lại typewriter
}

export function useAiChat() {
  const { data: session } = useSession();
  const [message, setMessage] = useState("");
  const [isSearching, setIsSearching] = useState(false);
  const [messages, setMessages] = useState<MessageItem[]>([]);

  const userId = session?.user?.userId as number | undefined;

  // Lưu tọa độ GPS vào ref để dùng ngay khi sendChatMessage mà không cần await
  const gpsRef = useRef<{ lat: number; lng: number } | null>(null);

  // Prefetch GPS ngay khi hook mount — trước khi user gửi bất kỳ tin nhắn nào
  useEffect(() => {
    if (typeof window === "undefined" || !navigator.geolocation) return;

    // Kiểm tra cache trước
    try {
      const cached = sessionStorage.getItem("ai_user_gps");
      if (cached) {
        const parsed = JSON.parse(cached) as { lat: number; lng: number; cachedAt: number };
        if (Date.now() - parsed.cachedAt < 5 * 60 * 1000) {
          gpsRef.current = { lat: parsed.lat, lng: parsed.lng };
          return;
        }
      }
    } catch { /* ignore */ }

    // Gọi Geolocation API sớm — khi user bấm Allow, kết quả sẽ có ngay trong gpsRef
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        const coords = { lat: pos.coords.latitude, lng: pos.coords.longitude };
        gpsRef.current = coords;
        // Đồng bộ vào cache để ai-chat-api.ts cũng thấy
        sessionStorage.setItem(
          "ai_user_gps",
          JSON.stringify({ ...coords, cachedAt: Date.now() })
        );
      },
      () => { /* user từ chối hoặc không hỗ trợ — giữ null */ },
      { timeout: 10000, maximumAge: 0 }
    );
  }, []);

  // Đọc lịch sử trò chuyện từ sessionStorage khi mount hoặc khi userId đổi
  useEffect(() => {
    if (typeof window === "undefined") {
      setMessages(getDefaultMessages());
      return;
    }

    const key = getStorageKey(userId);
    const stored = sessionStorage.getItem(key);
    if (stored) {
      try {
        const parsed = JSON.parse(stored) as MessageItem[];
        setMessages(parsed.map(m => ({ ...m, isHistory: true })));
        return;
      } catch {
        // corrupt → fall through to default
      }
    }
    setMessages(getDefaultMessages());
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId]);

  // Ghi lịch sử trò chuyện vào sessionStorage mỗi khi tin nhắn thay đổi
  useEffect(() => {
    if (typeof window !== "undefined" && messages.length > 0) {
      const key = getStorageKey(userId);
      sessionStorage.setItem(key, JSON.stringify(messages));
    }
  }, [messages, userId]);

  const handleSend = async (customMessage?: string) => {
    const q = (customMessage !== undefined ? customMessage : message).trim();
    if (!q || isSearching) return;

    const userTime = new Date().toLocaleTimeString("vi-VN", {
      hour: "2-digit",
      minute: "2-digit",
    });

    const userMessage: MessageItem = {
      id: "user-" + Date.now(),
      type: "user",
      content: q,
      timestamp: userTime,
    };

    const updatedMessages = [...messages, userMessage];
    setMessages(updatedMessages);
    setMessage("");
    setIsSearching(true);

    try {
      // Khi gửi tin mới, toàn bộ tin nhắn hiện hữu chuyển thành history
      const historyPayload: ChatMessage[] = updatedMessages.slice(0, -1).map((m) => ({
        role: m.type === "assistant" ? "assistant" : "user",
        content: m.content,
      }));

      const result = await sendChatMessage(q, historyPayload, gpsRef.current);

      const aiResponse: MessageItem = {
        id: "ai-" + Date.now(),
        type: "assistant",
        content: result.message || "",
        intent: result.intent,
        timestamp: new Date().toLocaleTimeString("vi-VN", {
          hour: "2-digit",
          minute: "2-digit",
        }),
        stadiums: result.stadiums,
        slots: result.slots,
        matches: result.matches,
        bookings: result.bookings,
        policyText: result.policyText,
        bookingId: result.bookingId,
        draftBooking: result.draftBooking,
        matchId: result.matchId,
        draftJoinMatch: result.draftJoinMatch,
        draftCreateMatch: result.draftCreateMatch,
        isHistory: false, // Tin nhắn mới, kích hoạt typewriter
      };

      setMessages((prev) => [...prev, aiResponse]);
    } catch (error) {
      const errorMsg: MessageItem = {
        id: "err-" + Date.now(),
        type: "assistant",
        content: "Không thể kết nối tới máy chủ. Vui lòng kiểm tra lại kết nối mạng của bạn.",
        timestamp: new Date().toLocaleTimeString("vi-VN", {
          hour: "2-digit",
          minute: "2-digit",
        }),
        isHistory: false,
      };
      setMessages((prev) => [...prev, errorMsg]);
    } finally {
      setIsSearching(false);
    }
  };

  const handleClearHistory = useCallback(async () => {
    if (typeof window !== "undefined") {
      // Xóa storage key hiện tại
      const key = getStorageKey(userId);
      sessionStorage.removeItem(key);
    }
    setMessages(getDefaultMessages());
    // Gọi BE để xóa Redis context — chỉ khi login
    if (userId != null) {
      try {
        await api.delete("/ai/chat/context");
      } catch (e) {
        // Không crash nếu BE call thất bại
        console.warn("[useAiChat] Failed to clear BE context:", e);
      }
    }
  }, [userId]);

  return {
    message,
    setMessage,
    isSearching,
    messages,
    setMessages,
    handleSend,
    handleClearHistory,
  };
}
