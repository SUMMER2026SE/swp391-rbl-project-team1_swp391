import { StadiumResponse } from "./stadium";
import { MatchResponse } from "./match";

export interface ChatMessage {
  role: "user" | "assistant";
  content: string;
}

export interface AiChatTurnRequest {
  message: string;
  history: ChatMessage[];
  /** Tọa độ GPS hiện tại — gửi kèm nếu user đã cấp quyền vị trí */
  userLat?: number | null;
  userLng?: number | null;
}

export interface TimeSlotResponse {
  slotId: number;
  stadiumId: number;
  startTime: string; // HH:mm:ss
  endTime: string; // HH:mm:ss
  pricePerSlot: number;
  slotStatus: string;
  available: boolean;
}

export interface DraftBookingResponse {
  stadiumId: number;
  stadiumName: string;
  date: string; // dd/MM/yyyy (display format — convert before using in ISO date params)
  startTime: string; // HH:mm
  price: number;
}

export interface DraftJoinMatchResponse {
  matchId: number;
  title: string;
  stadiumName: string;
  playDate: string;
  time: string;
  userMessage: string;
}

export interface DraftCreateMatchResponse {
  bookingId: number;
  defaultTitle: string;
  stadiumName: string;
  sportName: string;
  playDate: string;
  startTime: string;
  endTime: string;
  matchingType?: "INDIVIDUAL" | "TEAM_VS_TEAM" | null;
  maxPlayers?: number | null;
  skillLevel?: "BEGINNER" | "INTERMEDIATE" | "ADVANCED" | null;
  splitPrice?: boolean | null;
  pricePerPlayer?: number | null;
  missingFields?: string[];
}

export interface BookingSlotInfo {
  slotId: number;
  startTime: string; // ISO LocalDateTime (yyyy-MM-ddTHH:mm:ss)
  endTime: string;
}

export interface BookingStadiumInfo {
  stadiumId: number;
  stadiumName: string;
  complexName?: string | null;
  address?: string | null;
  sportType?: string | null;
}

export interface BookingAiResponse {
  bookingId: number;
  stadium?: BookingStadiumInfo | null;
  slot?: BookingSlotInfo | null;
  totalPrice?: number | null;
  bookingStatus?: string | null;
  paymentStatus?: string | null;
  note?: string | null;
  bookingDate?: string | null;
  reservationDate?: string | null; // populated via slot.startTime date portion
}

export interface AiChatTurnResponse {
  message: string;
  intent: string;
  stadiums: StadiumResponse[] | null;
  slots: TimeSlotResponse[] | null;
  matches: MatchResponse[] | null;
  /** Danh sách booking của user (intent: my_bookings, booking_status, cancel_booking) */
  bookings?: BookingAiResponse[] | null;
  policyText: string | null;
  /** ID của booking vừa tạo (intent: create_booking) - deprecated */
  bookingId?: number | null;
  /** Draft booking info for user confirmation (intent: confirm_booking) */
  draftBooking?: DraftBookingResponse | null;
  /** ID của kèo ghép vừa tham gia (intent: join_match) */
  matchId?: number | null;
  /** Thông tin kèo ghép nháp (intent: confirm_join_match) */
  draftJoinMatch?: DraftJoinMatchResponse | null;
  /** Dữ liệu để hiển thị form cấu hình trước khi tạo kèo */
  draftCreateMatch?: DraftCreateMatchResponse | null;
}
