import { postLongTimeout } from "./api";
import { AiChatTurnRequest, AiChatTurnResponse, ChatMessage } from "@/types/aiChat";
import { ApiResponse } from "@/types/common";

const SESSION_KEY = "ai_session_id";
const GPS_CACHE_KEY = "ai_user_gps";
const GPS_CACHE_TTL_MS = 5 * 60 * 1000; // 5 phút — tránh gọi Geolocation API liên tục

interface GpsCache {
  lat: number;
  lng: number;
  cachedAt: number;
}

function getOrCreateSessionId(): string {
  if (typeof window === "undefined") return "";
  let sessionId = sessionStorage.getItem(SESSION_KEY);
  if (!sessionId) {
    sessionId = crypto.randomUUID();
    sessionStorage.setItem(SESSION_KEY, sessionId);
  }
  return sessionId;
}

/**
 * Lấy tọa độ GPS từ cache sessionStorage.
 * Trả về null nếu chưa có hoặc đã quá TTL — caller sẽ gọi Geolocation API.
 */
function getCachedGps(): { lat: number; lng: number } | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = sessionStorage.getItem(GPS_CACHE_KEY);
    if (!raw) return null;
    const cache: GpsCache = JSON.parse(raw);
    if (Date.now() - cache.cachedAt > GPS_CACHE_TTL_MS) return null;
    return { lat: cache.lat, lng: cache.lng };
  } catch {
    return null;
  }
}

function setCachedGps(lat: number, lng: number) {
  if (typeof window === "undefined") return;
  const cache: GpsCache = { lat, lng, cachedAt: Date.now() };
  sessionStorage.setItem(GPS_CACHE_KEY, JSON.stringify(cache));
}

/**
 * Lấy tọa độ GPS của user — ưu tiên cache, fallback Geolocation API.
 * Không throw: nếu user từ chối hoặc browser không hỗ trợ, trả về null
 * (backend sẽ nhắc user cấp quyền khi cần).
 */
async function getUserGps(): Promise<{ lat: number; lng: number } | null> {
  const cached = getCachedGps();
  if (cached) return cached;

  if (typeof window === "undefined" || !navigator.geolocation) return null;

  return new Promise((resolve) => {
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        const coords = { lat: pos.coords.latitude, lng: pos.coords.longitude };
        setCachedGps(coords.lat, coords.lng);
        resolve(coords);
      },
      () => resolve(null), // Từ chối quyền hoặc timeout → resolve null, không reject
      { timeout: 10000, maximumAge: GPS_CACHE_TTL_MS }
    );
  });
}

export async function sendChatMessage(
  message: string,
  history: ChatMessage[],
  /** Tọa độ GPS do hook truyền vào — ưu tiên hơn cache nội bộ */
  gpsOverride?: { lat: number; lng: number } | null
): Promise<AiChatTurnResponse> {
  const sessionId = getOrCreateSessionId();

  // Ưu tiên gpsOverride từ hook (đã prefetch), fallback về cache/Geolocation API
  const gps = gpsOverride ?? (await getUserGps());

  const payload: AiChatTurnRequest = {
    message,
    history,
    userLat: gps?.lat ?? null,
    userLng: gps?.lng ?? null,
  };

  const response = await postLongTimeout<ApiResponse<AiChatTurnResponse>>("/ai/chat", payload, {
    headers: {
      "X-Session-ID": sessionId,
    },
  });

  return response.result;
}
