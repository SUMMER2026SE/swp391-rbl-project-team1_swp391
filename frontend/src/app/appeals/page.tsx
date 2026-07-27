"use client";

import { useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useRouter } from "next/navigation";
import { AlertTriangle, CheckCircle2, Clock, Loader2, ShieldAlert, X, Plus, Home } from "lucide-react";
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
  const { data: session, status, update } = useSession();
  const router = useRouter();
  const [appeal, setAppeal] = useState<Appeal | null>(null);
  const [appealText, setAppealText] = useState("");
  const [evidenceList, setEvidenceList] = useState<string[]>([]);
  const [urlInput, setUrlInput] = useState("");
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const user = session?.user;
  const isBlocked = user?.accountStatus === "BLOCKED";
  const pendingAppeal = appeal?.status === "PENDING";

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
        evidenceUrls: evidenceList,
      });
      setAppeal(data.result);
      setAppealText("");
      setEvidenceList([]);
      setUrlInput("");
      setMessage("Kháng cáo mới của bạn đã được gửi thành công tới Admin.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không thể gửi kháng cáo.");
    } finally {
      setSubmitting(false);
    }
  };

  const handleImageFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (evidenceList.length >= 5) {
      setError("Chỉ được gửi tối đa 5 bằng chứng.");
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      setError("Dung lượng ảnh tối đa là 10MB");
      return;
    }

    const reader = new FileReader();
    reader.onload = (event) => {
      const img = new Image();
      img.onload = () => {
        const canvas = document.createElement("canvas");
        let width = img.width;
        let height = img.height;
        const MAX_DIM = 800;

        if (width > MAX_DIM || height > MAX_DIM) {
          if (width > height) {
            height = Math.round((height * MAX_DIM) / width);
            width = MAX_DIM;
          } else {
            width = Math.round((width * MAX_DIM) / height);
            height = MAX_DIM;
          }
        }
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext("2d");
        ctx?.drawImage(img, 0, 0, width, height);
        const dataUrl = canvas.toDataURL("image/jpeg", 0.7);
        setEvidenceList((prev) => [...prev, dataUrl]);
        setError(null);
      };
      img.src = event.target?.result as string;
    };
    reader.readAsDataURL(file);

    e.target.value = "";
  };

  const removeEvidence = (index: number) => {
    setEvidenceList((prev) => prev.filter((_, i) => i !== index));
  };

  const addUrlEvidence = () => {
    if (!urlInput.trim()) return;
    if (evidenceList.length >= 5) {
      setError("Chỉ được gửi tối đa 5 bằng chứng.");
      return;
    }
    setEvidenceList((prev) => [...prev, urlInput.trim()]);
    setUrlInput("");
    setError(null);
  };

  const handleGoHome = async () => {
    try {
      await update();
    } catch {}
    router.push("/");
    router.refresh();
  };

  if (status === "loading" || loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-50">
        <Loader2 className="h-8 w-8 animate-spin text-emerald-600" />
      </div>
    );
  }

  // TRƯỜNG HỢP 1: Tài khoản hiện tại ĐANG HOẠT ĐỘNG (Không bị khóa)
  if (!isBlocked) {
    return (
      <div className="min-h-screen bg-background flex flex-col">
        <Header />
        <main className="flex-1 bg-slate-50 px-4 py-10">
          <div className="mx-auto max-w-3xl space-y-6">
            <Card className="border-emerald-200 bg-white shadow-sm">
              <CardContent className="flex flex-col sm:flex-row items-start sm:items-center gap-4 p-6">
                <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-emerald-100">
                  <CheckCircle2 className="h-7 w-7 text-emerald-600" />
                </div>
                <div className="flex-1 space-y-1">
                  <h1 className="text-xl font-semibold text-slate-900">Tài khoản của bạn đang hoạt động bình thường</h1>
                  <p className="text-sm text-slate-600">
                    Tài khoản không ở trạng thái khóa. Bạn có thể sử dụng tất cả dịch vụ trên hệ thống.
                  </p>
                </div>
                <Button onClick={handleGoHome} className="bg-emerald-600 hover:bg-emerald-700 text-white w-full sm:w-auto">
                  <Home className="mr-2 h-4 w-4" />
                  Về trang chủ
                </Button>
              </CardContent>
            </Card>

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
          </div>
        </main>
        <Footer />
      </div>
    );
  }

  // TRƯỜNG HỢP 2: Tài khoản hiện tại ĐANG BỊ KHÓA (isBlocked === true)
  return (
    <div className="min-h-screen bg-background flex flex-col">
      <Header />
      <main className="flex-1 bg-slate-50 px-4 py-10">
        <div className="mx-auto max-w-4xl space-y-6">
          <section className="rounded-lg border border-rose-200 bg-white p-6 shadow-sm">
            <div className="flex items-start gap-4">
              <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-rose-100">
                <ShieldAlert className="h-6 w-6 text-rose-600" />
              </div>
              <div className="space-y-2">
                <h1 className="text-2xl font-semibold text-slate-950">Tài khoản của bạn đang bị khóa</h1>
                <p className="text-sm text-slate-600">
                  Lý do: {user?.lockReason || appeal?.relatedLockReason || "Admin chưa ghi chú lý do cụ thể."}
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
                {appeal.evidenceUrls && appeal.evidenceUrls.length > 0 && (
                  <div className="space-y-2 pt-2">
                    <span className="font-medium text-slate-700">Bằng chứng đã đính kèm:</span>
                    <div className="flex flex-wrap gap-2">
                      {appeal.evidenceUrls.map((url, idx) => (
                        <a key={idx} href={url} target="_blank" rel="noreferrer" className="block">
                          {url.startsWith("data:image") || url.match(/\.(jpeg|jpg|gif|png|webp)/i) || url.startsWith("http") ? (
                            <img src={url} alt={`Bằng chứng ${idx + 1}`} className="h-16 w-16 rounded-md object-cover border" />
                          ) : (
                            <span className="text-xs text-emerald-700 underline">{url}</span>
                          )}
                        </a>
                      ))}
                    </div>
                  </div>
                )}
              </CardContent>
            </Card>
          )}

          {/* Form gửi kháng cáo mới cho lượt khóa hiện tại */}
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
                    Bạn đã có một kháng cáo đang chờ xử lý cho lượt khóa này. Vui lòng chờ Admin xem xét.
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

                <div className="space-y-3">
                  <Label>Bằng chứng đính kèm (Tối đa 5 hình ảnh / URL)</Label>
                  
                  <div className="flex flex-wrap items-center gap-3">
                    <div className="relative">
                      <Input
                        type="file"
                        accept="image/*"
                        onChange={handleImageFileChange}
                        disabled={pendingAppeal || submitting || evidenceList.length >= 5}
                        className="cursor-pointer text-sm"
                      />
                    </div>
                    <div className="flex flex-1 items-center gap-2 min-w-[240px]">
                      <Input
                        placeholder="Hoặc dán URL ảnh bằng chứng..."
                        value={urlInput}
                        onChange={(e) => setUrlInput(e.target.value)}
                        disabled={pendingAppeal || submitting || evidenceList.length >= 5}
                        className="text-sm"
                      />
                      <Button
                        type="button"
                        variant="outline"
                        onClick={addUrlEvidence}
                        disabled={pendingAppeal || submitting || !urlInput.trim() || evidenceList.length >= 5}
                      >
                        <Plus className="mr-1 h-4 w-4" />
                        Thêm
                      </Button>
                    </div>
                  </div>

                  {/* Thumbnail Previews Grid */}
                  {evidenceList.length > 0 && (
                    <div className="flex flex-wrap gap-3 pt-2">
                      {evidenceList.map((item, idx) => (
                        <div key={idx} className="relative group rounded-lg border bg-white p-1.5 shadow-sm">
                          {item.startsWith("data:image") || item.match(/\.(jpeg|jpg|gif|png|webp)/i) || item.startsWith("http") ? (
                            <img
                              src={item}
                              alt={`Bằng chứng ${idx + 1}`}
                              className="h-20 w-20 rounded-md object-cover"
                            />
                          ) : (
                            <div className="flex h-20 w-20 items-center justify-center rounded-md bg-slate-100 p-2 text-xs text-slate-600 break-all overflow-hidden">
                              {item}
                            </div>
                          )}
                          <button
                            type="button"
                            onClick={() => removeEvidence(idx)}
                            disabled={pendingAppeal || submitting}
                            className="absolute -top-2 -right-2 rounded-full bg-rose-600 p-1 text-white shadow hover:bg-rose-700 transition-colors"
                          >
                            <X className="h-3.5 w-3.5" />
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
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
