export interface MatchResponse {
  matchId: number;
  hostName: string;
  hostUserId: number;
  stadiumName: string;
  stadiumAddress: string;
  sportName: string;
  title: string;
  description: string;
  playDate: string; // YYYY-MM-DD
  startTime: string; // HH:mm:ss
  endTime: string; // HH:mm:ss
  maxPlayers: number;
  currentPlayers: number;
  skillLevel?: string;
  splitPrice?: boolean;
  pricePerPlayer: number;
  matchStatus: string;
  matchingType?: string;
  cancelReason?: string;
  createdAt?: string;
  /** true nếu user hiện tại là host của kèo này */
  isOwner?: boolean;
}
