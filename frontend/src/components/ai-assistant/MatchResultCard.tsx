"use client";

import { useRouter } from "next/navigation";
import { useSession } from "next-auth/react";
import { signIn } from "next-auth/react";
import { Card, CardContent, CardFooter } from "../ui/card";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Calendar, Clock, Users, User } from "lucide-react";
import { MatchResponse } from "@/types/match";

export function MatchResultCard({ match }: { match: MatchResponse }) {
  const router = useRouter();
  const { data: session } = useSession();
  const {
    hostName,
    stadiumName,
    stadiumAddress,
    sportName,
    title,
    playDate,
    startTime,
    endTime,
    maxPlayers,
    currentPlayers,
    pricePerPlayer,
    splitPrice,
    isOwner,
  } = match;

  const formatTime = (timeStr: string) => {
    if (!timeStr) return "";
    const parts = timeStr.split(":");
    return parts.length >= 2 ? `${parts[0]}:${parts[1]}` : timeStr;
  };

  const formatDate = (dateStr: string) => {
    if (!dateStr) return "";
    try {
      const date = new Date(dateStr);
      return date.toLocaleDateString("vi-VN", {
        weekday: "short",
        year: "numeric",
        month: "numeric",
        day: "numeric",
      });
    } catch {
      return dateStr;
    }
  };

  const handleJoinClick = (e: React.MouseEvent) => {
    e.preventDefault();
    if (!session?.user) {
      // Redirect to login, come back to this match after login
      signIn(undefined, { callbackUrl: `/community?matchId=${match.matchId}` });
      return;
    }
    router.push(`/community?matchId=${match.matchId}`);
  };

  return (
    <div className="block group w-full max-w-sm">
      <Card className="overflow-hidden border-border/80 transition-all duration-300 hover:-translate-y-1 hover:border-primary/25 hover:shadow-xl hover:shadow-primary/10 h-full flex flex-col justify-between">
        <CardContent className="p-4">
          <div className="flex items-center justify-between mb-2.5">
            <Badge className="border-0 bg-primary/95 text-[10px] px-2 py-0.5">
              {sportName}
            </Badge>
            {isOwner ? (
              <Badge variant="outline" className="border-green-500 text-green-600 dark:text-green-400 text-[10px] px-2 py-0.5 gap-1">
                Kèo của bạn
              </Badge>
            ) : (
              <div className="flex items-center gap-1 text-[11px] text-muted-foreground font-medium">
                <User className="h-3.5 w-3.5" />
                <span>Chủ kèo: {hostName}</span>
              </div>
            )}
          </div>

          <h4 className="text-sm font-semibold leading-snug line-clamp-1 group-hover:text-primary transition-colors mb-2">
            {title}
          </h4>

          <p className="text-xs text-muted-foreground mb-3 line-clamp-1">
            📍 {stadiumName} - {stadiumAddress}
          </p>

          <div className="space-y-1.5 border-t border-border pt-3">
            <div className="flex items-center text-xs text-muted-foreground gap-2">
              <Calendar className="h-3.5 w-3.5 text-primary" />
              <span>{formatDate(playDate)}</span>
            </div>
            <div className="flex items-center text-xs text-muted-foreground gap-2">
              <Clock className="h-3.5 w-3.5 text-primary" />
              <span>
                {formatTime(startTime)} - {formatTime(endTime)}
              </span>
            </div>
            <div className="flex items-center text-xs text-muted-foreground gap-2">
              <Users className="h-3.5 w-3.5 text-primary" />
              <span>
                Sĩ số: {currentPlayers} / {maxPlayers} người chơi
              </span>
            </div>
          </div>

          <div className="mt-4 flex items-center justify-between">
            <span className="text-[10px] text-muted-foreground">
              Chi phí/người
            </span>
            <span className="font-bold text-sm text-primary">
              {splitPrice && pricePerPlayer > 0
                ? `${pricePerPlayer.toLocaleString("vi-VN")}đ`
                : "Miễn phí / Chia đều"}
            </span>
          </div>
        </CardContent>

        <CardFooter className="p-4 pt-0">
          <Button
            className="w-full rounded-lg font-semibold text-xs py-1.5 h-8"
            type="button"
            onClick={isOwner ? undefined : handleJoinClick}
            disabled={isOwner}
            variant={isOwner ? "secondary" : "default"}
          >
            {isOwner ? "Đây là kèo của bạn" : "Tham gia ghép kèo"}
          </Button>
        </CardFooter>
      </Card>
    </div>
  );
}
