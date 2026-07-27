"use client";

import { useEffect, useState } from "react";
import { CheckCircle2, Loader2, ShieldAlert, XCircle } from "lucide-react";
import api from "@/lib/api";
import type { PageResponse } from "@/types/common";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Textarea } from "@/components/ui/textarea";
import { toast } from "sonner";

type AppealStatus = "PENDING" | "APPROVED" | "REJECTED";

type Appeal = {
  appealId: number;
  userId: number;
  userEmail: string;
  userFullName: string;
  relatedLockReason?: string;
  appealText: string;
  evidenceUrls: string[];
  status: AppealStatus;
  adminNote?: string;
  createdAt: string;
};

const statusOptions: AppealStatus[] = ["PENDING", "APPROVED", "REJECTED"];

function statusLabel(status: AppealStatus) {
  if (status === "APPROVED") return "Đã chấp nhận";
  if (status === "REJECTED") return "Đã từ chối";
  return "Đang chờ";
}

function statusBadge(status: AppealStatus) {
  if (status === "APPROVED") return <Badge className="bg-emerald-600">{statusLabel(status)}</Badge>;
  if (status === "REJECTED") return <Badge variant="destructive">{statusLabel(status)}</Badge>;
  return <Badge variant="secondary">{statusLabel(status)}</Badge>;
}

export default function AdminAppealsPage() {
  const [status, setStatus] = useState<AppealStatus>("PENDING");
  const [appeals, setAppeals] = useState<Appeal[]>([]);
  const [notes, setNotes] = useState<Record<number, string>>({});
  const [loading, setLoading] = useState(true);
  const [reviewingId, setReviewingId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadAppeals = async () => {
    setLoading(true);
    setError(null);
    try {
      const { data } = await api.get<PageResponse<Appeal>>("/admin/appeals", {
        params: { status, page: 0, size: 20, sort: "createdAt,desc" },
      });
      setAppeals(data.content ?? []);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể tải danh sách kháng cáo.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadAppeals();
  }, [status]);

  const reviewAppeal = async (appealId: number, nextStatus: "APPROVED" | "REJECTED") => {
    setReviewingId(appealId);
    setError(null);
    try {
      await api.patch(`/admin/appeals/${appealId}/review`, {
        status: nextStatus,
        adminNote: notes[appealId]?.trim() || undefined,
      });
      if (nextStatus === "APPROVED") {
        toast.success("Đã phê duyệt kháng cáo & TỰ ĐỘNG MỞ KHÓA tài khoản người dùng thành công!");
      } else {
        toast.info("Đã từ chối đơn kháng cáo.");
      }
      await loadAppeals();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể xử lý kháng cáo.");
    } finally {
      setReviewingId(null);
    }
  };

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="inline-flex rounded-md border border-slate-200 bg-white p-1">
          {statusOptions.map((option) => (
            <button
              key={option}
              type="button"
              onClick={() => setStatus(option)}
              className={`rounded px-3 py-1.5 text-sm font-medium transition-colors ${
                status === option ? "bg-emerald-600 text-white" : "text-slate-600 hover:bg-slate-100"
              }`}
            >
              {statusLabel(option)}
            </button>
          ))}
        </div>
      </div>

      {error && <div className="rounded-md bg-rose-50 p-3 text-sm text-rose-700">{error}</div>}

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 className="h-8 w-8 animate-spin text-emerald-600" />
        </div>
      ) : appeals.length === 0 ? (
        <div className="rounded-lg border border-slate-200 bg-white py-14 text-center text-slate-500">
          <ShieldAlert className="mx-auto mb-3 h-10 w-10 text-slate-300" />
          Không có kháng cáo nào
        </div>
      ) : (
        <div className="space-y-4">
          {appeals.map((appeal) => (
            <Card key={appeal.appealId}>
              <CardContent className="space-y-4 p-5">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <div className="font-semibold text-slate-950">{appeal.userFullName}</div>
                    <div className="text-sm text-slate-500">{appeal.userEmail}</div>
                  </div>
                  {statusBadge(appeal.status)}
                </div>

                <div className="grid gap-3 text-sm md:grid-cols-2">
                  <div className="rounded-md bg-slate-50 p-3">
                    <div className="font-medium text-slate-700">Lý do khóa</div>
                    <p className="mt-1 text-slate-600">{appeal.relatedLockReason || "Không có ghi chú."}</p>
                  </div>
                  <div className="rounded-md bg-slate-50 p-3">
                    <div className="font-medium text-slate-700">Nội dung kháng cáo</div>
                    <p className="mt-1 text-slate-600">{appeal.appealText}</p>
                  </div>
                </div>

                {appeal.evidenceUrls.length > 0 && (
                  <div className="space-y-1 text-sm">
                    <div className="font-medium text-slate-700">Bằng chứng</div>
                    {appeal.evidenceUrls.map((url) => (
                      <a
                        key={url}
                        href={url}
                        target="_blank"
                        rel="noreferrer"
                        className="block break-all text-emerald-700 hover:underline"
                      >
                        {url}
                      </a>
                    ))}
                  </div>
                )}

                {appeal.status === "PENDING" && (
                  <div className="space-y-3">
                    <Textarea
                      value={notes[appeal.appealId] ?? ""}
                      onChange={(event) =>
                        setNotes((current) => ({ ...current, [appeal.appealId]: event.target.value }))
                      }
                      placeholder="Ghi chú cho người dùng"
                      rows={3}
                    />
                    <div className="flex flex-wrap gap-2">
                      <Button
                        onClick={() => reviewAppeal(appeal.appealId, "APPROVED")}
                        disabled={reviewingId === appeal.appealId}
                        className="bg-emerald-600 hover:bg-emerald-700"
                      >
                        <CheckCircle2 className="mr-2 h-4 w-4" />
                        Chấp nhận
                      </Button>
                      <Button
                        variant="destructive"
                        onClick={() => reviewAppeal(appeal.appealId, "REJECTED")}
                        disabled={reviewingId === appeal.appealId}
                      >
                        <XCircle className="mr-2 h-4 w-4" />
                        Từ chối
                      </Button>
                    </div>
                  </div>
                )}
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
