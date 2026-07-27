"use client";

import { useEffect, useMemo, useState } from "react";
import { useSession } from "next-auth/react";
import { useRouter } from "next/navigation";
import { AlertTriangle, CheckCircle2, Clock, Loader2, ShieldAlert } from "lucide-react";
import api from "@/lib/api";
import type { ApiResponse } from "@/types/common";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Header } from "@/components/layout/Header";
import { Footer } from "@/components/landing/Footer";

type AppealStatus = "PENDING" | "APPROVED" | "REJECTED";

type Appeal = {
  appealId: number;
  relatedLockReason?: string;
  appealText: string;
  evidenceUrls: string[];
  status: AppealStatus;
  adminNote?: string;
  createdAt: string;
  reviewedAt?: string;
};

function statusBadge(status: AppealStatus) {
  if (status === "APPROVED") return <Badge className="bg-emerald-600">Đã chấp nhận</Badge>;
  if (status === "REJECTED") return <Badge variant="destructive">Đã từ chối</Badge>;
  return <Badge variant="secondary">Đang chờ xử lý</Badge>;
}

export default function AppealPage() {
  const { data: session, status } = useSession();
  const router = useRouter();
  const [appeal, setAppeal] = useState<Appeal | null>(null);
  const [appealText, setAppealText] = useState("");
  const [evidenceText, setEvidenceText] = useState("");
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const user = session?.user;
  const isBlocked = user?.accountStatus === "BLOCKED";
  const pendingAppeal = appeal?.status === "PENDING";
  const evidenceUrls = useMemo(
    () => evidenceText.split(/\r?\n/).map((url) => url.trim()).filter(Boolean).slice(0, 5),
    [evidenceText]
  );

  useEffect(() => {
    if (status === "loading") return;
    if (status === "unauthenticated") {
      router.replace("/login?redirect=/appeals");
      return;
    }

    api.get<ApiResponse<Appeal | null>>("/appeals/me")
      .then((res) => setAppeal(res.data.result ?? null))
      .catch(() => setAppeal(null))
      .finally(() => setLoading(false));
  }, [status, router]);

  const submitAppeal = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    setMessage(null);
    if (!appealText.trim()) {
      setError("Vui lòng nhập nội dung kháng cáo.");
      return;
    }

    setSubmitting(true);
    try {
      const { data } = await api.post<ApiResponse<Appeal>>("/appeals", {
        appealText: appealText.trim(),
        evidenceUrls,
      });
      setAppeal(data.result);
      setAppealText("");
      setEvidenceText("");
      setMessage("Kháng cáo đã được gửi tới Admin.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể gửi kháng cáo.");
    } finally {
      setSubmitting(false);
    }
  };

  const handleImageFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) {
      setError("Dung lượng ảnh tối đa là 5MB");
      return;
    }
    const reader = new FileReader();
    reader.onloadend = () => {
      if (typeof reader.result === "string") {
        setEvidenceText((prev) => (prev ? `${prev}\n${reader.result}` : (reader.result as string)));
      }
    };
    reader.readAsDataURL(file);
  };

  if (status === "loading" || loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-50">
        <Loader2 className="h-8 w-8 animate-spin text-emerald-600" />
      </div>
    );
  }

  if (!isBlocked) {
    return (
      <div className="min-h-screen bg-background flex flex-col">
        <Header />
        <main className="flex-1 bg-slate-50 px-4 py-10">
          <div className="mx-auto max-w-3xl">
            <Card>
              <CardContent className="flex items-center gap-4 p-6">
                <CheckCircle2 className="h-9 w-9 text-emerald-600" />
                <div className="flex-1">
                  <h1 className="text-xl font-semibold text-slate-900">Tài khoản đang hoạt động</h1>
                  <p className="text-sm text-slate-600">Bạn không cần gửi kháng cáo mở khóa.</p>
                </div>
                <Button onClick={() => router.push("/")}>Về trang chủ</Button>
              </CardContent>
            </Card>
          </div>
        </main>
        <Footer />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-background flex flex-col">
      <Header />
      <main className="flex-1 bg-slate-50 px-4 py-10">
        <div className="mx-auto max-w-4xl space-y-6">
          <section className="rounded-lg border border-rose-200 bg-white p-6">
            <div className="flex items-start gap-4">
              <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-rose-100">
                <ShieldAlert className="h-6 w-6 text-rose-600" />
              </div>
              <div className="space-y-2">
                <h1 className="text-2xl font-semibold text-slate-950">Tài khoản của bạn đang bị khóa</h1>
                <p className="text-sm text-slate-600">
                  Lý do: {user?.lockReason || "Admin chưa ghi chú lý do cụ thể."}
                </p>
              </div>
            </div>
          </section>

          {appeal && (
            <Card>
              <CardHeader className="flex flex-row items-center justify-between gap-4">
                <CardTitle className="flex items-center gap-2 text-base">
                  <Clock className="h-5 w-5 text-slate-500" />
                  Kháng cáo gần nhất
                </CardTitle>
                {statusBadge(appeal.status)}
              </CardHeader>
              <CardContent className="space-y-3 text-sm">
                <p className="text-slate-700">{appeal.appealText}</p>
                {appeal.adminNote && (
                  <div className="rounded-md bg-slate-100 p-3 text-slate-700">
                    Ghi chú Admin: {appeal.adminNote}
                  </div>
                )}
              </CardContent>
            </Card>
          )}

          <Card>
            <CardHeader>
              <CardTitle>Gửi kháng cáo</CardTitle>
            </CardHeader>
            <CardContent>
              <form onSubmit={submitAppeal} className="space-y-5">
                {message && <div className="rounded-md bg-emerald-50 p-3 text-sm text-emerald-700">{message}</div>}
                {error && <div className="rounded-md bg-rose-50 p-3 text-sm text-rose-700">{error}</div>}
                {pendingAppeal && (
                  <div className="flex items-start gap-2 rounded-md bg-amber-50 p-3 text-sm text-amber-800">
                    <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
                    Bạn đã có một kháng cáo đang chờ xử lý.
                  </div>
                )}

                <div className="space-y-2">
                  <Label htmlFor="appealText">Nội dung kháng cáo</Label>
                  <Textarea
                    id="appealText"
                    value={appealText}
                    onChange={(event) => setAppealText(event.target.value)}
                    disabled={pendingAppeal || submitting}
                    rows={5}
                    maxLength={2000}
                    placeholder="Mô tả chi tiết lý do bạn cho rằng tài khoản bị khóa do nhầm lẫn..."
                  />
                </div>

                <div className="space-y-2">
                  <Label htmlFor="evidenceUrls">Bằng chứng (Upload Ảnh / URL)</Label>
                  <div className="flex flex-col gap-3">
                    <div className="flex items-center gap-3">
                      <Input
                        type="file"
                        accept="image/*"
                        onChange={handleImageFileChange}
                        disabled={pendingAppeal || submitting}
                        className="cursor-pointer text-sm"
                      />
                    </div>
                    <Textarea
                      id="evidenceUrls"
                      value={evidenceText}
                      onChange={(event) => setEvidenceText(event.target.value)}
                      disabled={pendingAppeal || submitting}
                      rows={3}
                      placeholder="Mỗi dòng một URL ảnh hoặc chuỗi dữ liệu ảnh bằng chứng..."
                    />
                  </div>
                </div>

                <Button type="submit" disabled={pendingAppeal || submitting}>
                  {submitting && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                  Gửi kháng cáo
                </Button>
              </form>
            </CardContent>
          </Card>
        </div>
      </main>
      <Footer />
    </div>
  );
}
