"use client";

import { useState } from "react";
import { useSession } from "next-auth/react";
import { signIn } from "next-auth/react";
import { Users, CheckCircle2, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import Link from "next/link";
import { joinMatchRequest } from "@/lib/api/matchmaking";
import { toast } from "sonner";

interface DraftJoinMatchCardProps {
  draftJoinMatch: {
    matchId: number;
    title: string;
    stadiumName: string;
    playDate: string;
    time: string;
    userMessage: string;
  };
}

export function DraftJoinMatchCard({
  draftJoinMatch,
}: DraftJoinMatchCardProps) {
  const { data: session } = useSession();
  const [isJoining, setIsJoining] = useState(false);
  const [isSuccess, setIsSuccess] = useState(false);

  const handleConfirmJoin = async () => {
    if (!session?.user) {
      signIn(undefined, {
        callbackUrl: `/community?matchId=${draftJoinMatch.matchId}`,
      });
      return;
    }
    setIsJoining(true);
    try {
      await joinMatchRequest(
        draftJoinMatch.matchId,
        draftJoinMatch.userMessage,
      );
      setIsSuccess(true);
      toast.success("Yêu cầu tham gia kèo của bạn đã được gửi thành công.");
    } catch (error: any) {
      // error.response?.data?.message = BadRequestException message từ BE
      // error.message = message đã được extract bởi axios interceptor (api.ts)
      // error.status = HTTP status code
      const serverMessage = error.response?.data?.message;
      const axiosMessage = error.message;
      const status = error.response?.status;

      console.error("[DraftJoinMatchCard] Confirm join failed:", {
        status,
        serverMessage,
        axiosMessage,
        rawError: error,
      });

      // Ưu tiên: message từ BE > message từ interceptor > fallback
      const displayMessage = serverMessage || axiosMessage || "Đã xảy ra lỗi khi xác nhận tham gia kèo. Vui lòng thử lại.";
      toast.error(displayMessage);
    } finally {
      setIsJoining(false);
    }
  };

  if (isSuccess) {
    return (
      <div className="w-full max-w-xs md:max-w-lg rounded-xl border border-blue-200 bg-blue-50 dark:bg-blue-950/20 dark:border-blue-800 p-4">
        <div className="flex items-center gap-2 mb-2">
          <CheckCircle2 className="h-5 w-5 text-blue-600 dark:text-blue-400" />
          <span className="font-semibold text-sm text-blue-800 dark:text-blue-300">
            Đã gửi yêu cầu tham gia kèo!
          </span>
        </div>
        <p className="text-xs text-blue-700 dark:text-blue-400 mb-3">
          Đang chờ chủ kèo xác nhận. Bạn sẽ được thông báo khi có kết quả.
        </p>
        <Link href={`/community`}>
          <Button
            size="sm"
            variant="outline"
            className="w-full border-blue-300 text-blue-700 hover:bg-blue-100"
          >
            Xem kèo của tôi
          </Button>
        </Link>
      </div>
    );
  }

  return (
    <div className="w-full max-w-xs md:max-w-lg rounded-xl border border-indigo-200 bg-indigo-50 dark:bg-indigo-950/20 dark:border-indigo-800 p-4 shadow-sm">
      <div className="flex items-center gap-2 mb-3">
        <Users className="h-5 w-5 text-indigo-600 dark:text-indigo-400" />
        <span className="font-semibold text-sm text-indigo-800 dark:text-indigo-300">
          Xác nhận tham gia kèo
        </span>
      </div>
      <div className="bg-white/60 dark:bg-black/20 rounded-md p-3 mb-3 border border-indigo-100 dark:border-indigo-900/50">
        <div className="flex flex-col gap-1.5 text-xs text-indigo-900 dark:text-indigo-200">
          <div className="flex justify-between">
            <span className="font-medium opacity-80">Kèo:</span>
            <span className="font-bold">{draftJoinMatch.title}</span>
          </div>
          <div className="flex justify-between">
            <span className="font-medium opacity-80">Sân:</span>
            <span className="font-bold text-right">
              {draftJoinMatch.stadiumName}
            </span>
          </div>
          <div className="flex justify-between">
            <span className="font-medium opacity-80">Thời gian:</span>
            <span className="font-bold">
              {draftJoinMatch.time}, {draftJoinMatch.playDate}
            </span>
          </div>
          {draftJoinMatch.userMessage && (
            <div className="flex flex-col mt-1 pt-1.5 border-t border-indigo-200/50 dark:border-indigo-800/50">
              <span className="font-medium opacity-80 mb-1">Lời nhắn:</span>
              <span className="italic text-indigo-700 dark:text-indigo-300">
                "{draftJoinMatch.userMessage}"
              </span>
            </div>
          )}
        </div>
      </div>
      <Button
        onClick={handleConfirmJoin}
        disabled={isJoining}
        size="sm"
        className="w-full bg-indigo-500 hover:bg-indigo-600 text-white font-medium shadow-sm transition-all hover:shadow-md"
      >
        {isJoining ? (
          <>
            <Loader2 className="mr-2 h-4 w-4 animate-spin" />
            Đang xử lý...
          </>
        ) : (
          "Xác nhận tham gia"
        )}
      </Button>
    </div>
  );
}
