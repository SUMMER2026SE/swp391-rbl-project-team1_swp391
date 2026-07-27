import api from "../api";

export interface CreateMatchRequestDto {
  bookingId: number;
  title: string;
  description?: string;
  maxPlayers: number;
  skillLevel: "BEGINNER" | "INTERMEDIATE" | "ADVANCED";
  splitPrice: boolean;
  pricePerPlayer?: number;
  matchingType?: "INDIVIDUAL" | "TEAM_VS_TEAM";
}

export interface MatchResponse {
  matchId: number;
  hostName: string;
  hostUserId: number;
  stadiumName: string;
  complexName?: string | null;
  stadiumAddress: string;
  sportName: string;
  title: string;
  description: string;
  playDate: string;
  startTime: string;
  endTime: string;
  maxPlayers: number;
  currentPlayers: number;
  skillLevel: "BEGINNER" | "INTERMEDIATE" | "ADVANCED";
  splitPrice: boolean;
  pricePerPlayer?: number;
  matchStatus: "OPEN" | "FULL" | "CANCELLED" | "COMPLETED";
  matchingType?: "INDIVIDUAL" | "TEAM_VS_TEAM";
  cancelReason?: string;
  createdAt: string;
  /** true nếu user hiện tại là host của kèo này */
  isOwner?: boolean;
}

export interface PageResponse<T> {
  content: T[];
  pageNumber: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export async function createMatchRequest(
  data: CreateMatchRequestDto,
): Promise<MatchResponse> {
  const res = await api.post<MatchResponse>("/matchmaking", data);
  return res.data;
}

export async function getActiveMatches(
  page = 0,
  size = 10,
): Promise<PageResponse<MatchResponse>> {
  const res = await api.get<PageResponse<MatchResponse>>("/matchmaking", {
    params: { page, size },
  });
  return res.data;
}

export async function getMatchDetail(matchId: number): Promise<MatchResponse> {
  const res = await api.get<MatchResponse>(`/matchmaking/${matchId}`);
  return res.data;
}

export async function joinMatchRequest(
  matchId: number,
  message = "",
): Promise<{ message: string }> {
  const res = await api.post<{ message: string }>(
    `/matchmaking/${matchId}/join`,
    null,
    {
      params: { message },
    },
  );
  return res.data;
}

export interface JoinRequestResponse {
  joinId: number;
  matchId: number;
  userId: number;
  fullName: string;
  email: string;
  requestStatus: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";
  message: string;
  createdAt: string;
  matchTitle?: string;
  stadiumName?: string;
  complexName?: string | null;
  sportName?: string;
  playDate?: string;
  startTime?: string;
  endTime?: string;
  hostName?: string;
  hostEmail?: string;
  hostUserId?: number;
  matchStatus?: "OPEN" | "FULL" | "CANCELLED" | "COMPLETED";
  matchingType?: "INDIVIDUAL" | "TEAM_VS_TEAM";
  cancelReason?: string;
}

export async function getJoinRequests(
  matchId: number,
): Promise<JoinRequestResponse[]> {
  const res = await api.get<JoinRequestResponse[]>(`/matchmaking/${matchId}/participants`);
  return res.data;
}

export async function approveJoinRequest(
  matchId: number,
  participantId: number,
): Promise<{ message: string }> {
  const res = await api.put<{ message: string }>(
    `/matchmaking/${matchId}/participants/${participantId}/approve`
  );
  return res.data;
}

export async function rejectJoinRequest(
  matchId: number,
  participantId: number,
): Promise<{ message: string }> {
  const res = await api.put<{ message: string }>(
    `/matchmaking/${matchId}/participants/${participantId}/reject`
  );
  return res.data;
}

export async function getMyCreatedMatches(
  page = 0,
  size = 20
): Promise<PageResponse<MatchResponse>> {
  const res = await api.get<PageResponse<MatchResponse>>("/matchmaking/my-created", {
    params: { page, size },
  });
  return res.data;
}

export async function getMyJoinedRequests(
  page = 0,
  size = 20
): Promise<PageResponse<JoinRequestResponse>> {
  const res = await api.get<PageResponse<JoinRequestResponse>>("/matchmaking/my-joined", {
    params: { page, size },
  });
  return res.data;
}


export async function cancelMatchRequest(
  matchId: number,
  reason?: string
): Promise<{ message: string }> {
  const url = reason 
    ? `/matchmaking/${matchId}/cancel?reason=${encodeURIComponent(reason)}`
    : `/matchmaking/${matchId}/cancel`;
  const res = await api.put<{ message: string }>(url);
  return res.data;
}

export interface MatchEligibleBookingResponse {
  bookingId: number;
  stadiumName: string;
  complexName: string;
  address: string;
  sportName: string;
  playDate: string;
  startTime: string;
  endTime: string;
}

export async function getEligibleBookingsForMatch(): Promise<MatchEligibleBookingResponse[]> {
  const res = await api.get<MatchEligibleBookingResponse[]>("/matchmaking/eligible-bookings");
  return res.data;
}
