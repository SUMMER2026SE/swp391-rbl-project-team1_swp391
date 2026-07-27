"use client";

import { Suspense, useState, useEffect, useCallback } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  WalletCards,
  ArrowUpRight,
  ArrowDownLeft,
  ChevronLeft,
  ChevronRight,
  Loader2,
  Calendar,
  AlertCircle,
  Plus,
  CheckCircle2,
} from "lucide-react";
import { toast } from "sonner";
import {
  fetchCustomerWalletBalance,
  fetchCustomerWalletTransactions,
  initiateWalletTopup,
  type WalletTransaction,
} from "@/lib/wallet-api";
import { format } from "date-fns";
import { vi } from "date-fns/locale";
import { Header } from "@/components/layout/Header";
import { Footer } from "@/components/landing/Footer";

const QUICK_AMOUNTS = [100_000, 200_000, 500_000, 1_000_000];
const MIN_TOPUP = 10_000;
const MAX_TOPUP = 50_000_000;

function getTransactionBadge(type: string) {
  switch (type) {
    case "CUSTOMER_TOPUP_CREDIT":
      return <Badge className="bg-emerald-50 text-emerald-700 hover:bg-emerald-50 border-emerald-200">Nạp tiền vào ví</Badge>;
    case "CUSTOMER_REFUND_CREDIT":
      return <Badge className="bg-blue-50 text-blue-700 hover:bg-blue-50 border-blue-200">Hoàn tiền huỷ đơn</Badge>;
    case "CUSTOMER_PAYMENT_DEBIT":
      return <Badge className="bg-rose-50 text-rose-700 hover:bg-rose-50 border-rose-200">Thanh toán đơn đặt sân</Badge>;
    default:
      return <Badge variant="outline">{type}</Badge>;
  }
}

function CustomerWalletContent() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const [balance, setBalance] = useState<number | null>(null);
  const [transactions, setTransactions] = useState<WalletTransaction[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const size = 10;

  const [topupOpen, setTopupOpen] = useState(false);
  const [amountInput, setAmountInput] = useState("500000");
  const [submittingTopup, setSubmittingTopup] = useState(false);
  const [successModalData, setSuccessModalData] = useState<{ amount: number } | null>(null);

  const loadWalletData = useCallback(async () => {
    setLoading(true);
    try {
      const [balRes, txRes] = await Promise.all([
        fetchCustomerWalletBalance(),
        fetchCustomerWalletTransactions(page, size),
      ]);
      setBalance(balRes.balance);
      setTransactions(txRes.transactions);
      setTotalPages(txRes.totalPages);
      setTotalElements(txRes.totalElements);
    } catch (err: any) {
      console.error(err);
      toast.error("Không thể tải thông tin ví và giao dịch.");
    } finally {
      setLoading(false);
    }
  }, [page]);

  useEffect(() => {
    loadWalletData();
  }, [loadWalletData]);

  // Kết quả redirect về từ VNPay sau khi nạp tiền — xem PaymentReturnController.buildWalletRedirect.
  useEffect(() => {
    const topupSuccess = searchParams.get("topupSuccess");
    if (topupSuccess === null) return;

    const amount = searchParams.get("amount");
    if (topupSuccess === "true") {
      setSuccessModalData({ amount: amount ? Number(amount) : 0 });
    } else {
      const reason = searchParams.get("reason");
      toast.error(
        reason === "invalid_hash"
          ? "Chữ ký không hợp lệ — vui lòng thử lại hoặc liên hệ hỗ trợ."
          : "Nạp tiền thất bại — vui lòng thử lại."
      );
    }
    router.replace("/wallet");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleTopup = async () => {
    const amount = Number(amountInput);
    if (!amount || amount < MIN_TOPUP) {
      toast.error(`Số tiền nạp tối thiểu là ${MIN_TOPUP.toLocaleString("vi-VN")}đ`);
      return;
    }
    if (amount > MAX_TOPUP) {
      toast.error(`Số tiền nạp tối đa là ${MAX_TOPUP.toLocaleString("vi-VN")}đ`);
      return;
    }
    try {
      setSubmittingTopup(true);
      const { paymentUrl } = await initiateWalletTopup(amount);
      window.location.href = paymentUrl;
    } catch (err: any) {
      toast.error(err.message || "Không thể tạo liên kết nạp tiền");
      setSubmittingTopup(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <Header />
      <main className="flex-1">
        <div className="space-y-6 max-w-6xl mx-auto p-4 md:p-6">
          {/* Top Section: Balance Card */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            <Card className="md:col-span-2 bg-gradient-to-br from-indigo-600 to-blue-700 text-white shadow-xl border-none relative overflow-hidden group">
              <div className="absolute -right-10 -top-10 bg-white/10 w-40 h-40 rounded-full blur-2xl group-hover:bg-white/20 transition-all"></div>
              <CardContent className="p-6 md:p-8 flex flex-col justify-between h-full min-h-[180px]">
                <div>
                  <div className="flex items-center gap-2 text-indigo-100/90 text-sm font-semibold uppercase tracking-wider mb-2">
                    <WalletCards className="h-5 w-5" />
                    Ví của tôi
                  </div>
                  <h1 className="text-4xl md:text-5xl font-extrabold tracking-tight mt-2">
                    {balance !== null ? `${balance.toLocaleString("vi-VN")} đ` : "---"}
                  </h1>
                </div>
                <div className="mt-6 flex flex-wrap gap-3">
                  <Button
                    onClick={() => setTopupOpen(true)}
                    className="bg-white text-indigo-700 hover:bg-slate-100 font-semibold px-6 py-2.5 rounded-xl gap-1.5 transition-all"
                  >
                    <Plus className="h-4 w-4" />
                    Nạp tiền
                  </Button>
                </div>
              </CardContent>
            </Card>

            <Card className="border-slate-200 shadow-sm bg-white dark:bg-slate-900">
              <CardHeader className="pb-2">
                <CardTitle className="text-slate-600 text-xs font-semibold uppercase tracking-wider">Thông tin ví</CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="flex justify-between items-center py-1.5 border-b border-slate-100">
                  <span className="text-slate-500 text-sm">Loại ví</span>
                  <span className="font-semibold text-sm text-slate-800">Ví Customer</span>
                </div>
                <div className="flex justify-between items-center py-1.5 border-b border-slate-100">
                  <span className="text-slate-500 text-sm">Tổng giao dịch</span>
                  <span className="font-semibold text-sm text-slate-800">{totalElements}</span>
                </div>
                <div className="flex justify-between items-center py-1.5">
                  <span className="text-slate-500 text-sm">Đơn vị tiền tệ</span>
                  <span className="font-semibold text-sm text-slate-800">VND (đ)</span>
                </div>
              </CardContent>
            </Card>
          </div>

          {/* Transactions Section */}
          <Card className="border border-slate-200 shadow-sm bg-white">
            <CardHeader className="flex flex-row items-center justify-between border-b border-slate-100 pb-4">
              <CardTitle className="text-lg font-bold text-slate-800 flex items-center gap-2">
                Lịch sử giao dịch ví
              </CardTitle>
              <span className="text-xs text-muted-foreground bg-slate-100 px-2.5 py-1 rounded-full font-medium">
                Tổng cộng: {totalElements}
              </span>
            </CardHeader>
            <CardContent className="p-0">
              {loading ? (
                <div className="flex flex-col items-center justify-center py-20 gap-3 text-slate-500">
                  <Loader2 className="h-8 w-8 animate-spin text-indigo-600" />
                  <span className="text-sm">Đang tải lịch sử giao dịch...</span>
                </div>
              ) : transactions.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-20 text-slate-400 gap-2">
                  <AlertCircle className="h-10 w-10 text-slate-300" />
                  <span className="text-sm font-medium">Chưa có giao dịch nào được thực hiện.</span>
                </div>
              ) : (
                <div className="overflow-x-auto">
                  <Table>
                    <TableHeader className="bg-slate-50/50">
                      <TableRow>
                        <TableHead className="w-[120px] font-bold text-slate-700">Mã GD</TableHead>
                        <TableHead className="font-bold text-slate-700">Mã Booking</TableHead>
                        <TableHead className="font-bold text-slate-700">Loại giao dịch</TableHead>
                        <TableHead className="font-bold text-slate-700">Mô tả / Note</TableHead>
                        <TableHead className="font-bold text-slate-700">Ngày tạo</TableHead>
                        <TableHead className="text-right font-bold text-slate-700 pr-6">Số tiền</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {transactions.map((tx) => {
                        const isPositive = tx.amount > 0;
                        return (
                          <TableRow key={tx.transactionId} className="hover:bg-slate-50/50 transition-colors">
                            <TableCell className="font-medium text-slate-600">
                              #{tx.transactionId}
                            </TableCell>
                            <TableCell>
                              {tx.bookingId ? (
                                <span className="text-slate-600 font-medium">
                                  BK{String(tx.bookingId).padStart(6, "0")}
                                </span>
                              ) : (
                                <span className="text-slate-400">N/A</span>
                              )}
                            </TableCell>
                            <TableCell>
                              {getTransactionBadge(tx.transactionType)}
                            </TableCell>
                            <TableCell className="max-w-[280px] truncate text-slate-600 text-sm">
                              {tx.note || "---"}
                            </TableCell>
                            <TableCell className="text-slate-500 text-xs">
                              <div className="flex items-center gap-1.5">
                                <Calendar className="h-3.5 w-3.5" />
                                {format(new Date(tx.createdAt), "dd/MM/yyyy HH:mm", { locale: vi })}
                              </div>
                            </TableCell>
                            <TableCell className={`text-right font-bold pr-6 text-sm ${
                              isPositive ? "text-emerald-600" : "text-rose-600"
                            }`}>
                              <div className="flex items-center justify-end gap-1">
                                {isPositive ? (
                                  <ArrowUpRight className="h-4 w-4 text-emerald-500" />
                                ) : (
                                  <ArrowDownLeft className="h-4 w-4 text-rose-500" />
                                )}
                                {isPositive ? "+" : ""}
                                {tx.amount.toLocaleString("vi-VN")} đ
                              </div>
                            </TableCell>
                          </TableRow>
                        );
                      })}
                    </TableBody>
                  </Table>
                </div>
              )}
            </CardContent>

            {totalPages > 1 && (
              <div className="flex items-center justify-between border-t border-slate-100 px-6 py-4">
                <span className="text-xs text-slate-500 font-medium">
                  Trang {page + 1} / {totalPages}
                </span>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                    disabled={page === 0}
                    className="h-8 w-8 p-0"
                  >
                    <ChevronLeft className="h-4 w-4" />
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                    disabled={page === totalPages - 1}
                    className="h-8 w-8 p-0"
                  >
                    <ChevronRight className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            )}
          </Card>
        </div>
      </main>
      <Footer />

      <Dialog open={topupOpen} onOpenChange={setTopupOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Nạp tiền vào ví</DialogTitle>
            <DialogDescription>
              Tối thiểu {MIN_TOPUP.toLocaleString("vi-VN")}đ · Tối đa {MAX_TOPUP.toLocaleString("vi-VN")}đ.
              Bạn sẽ được chuyển sang VNPay để hoàn tất thanh toán.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-3 py-2">
            <Label htmlFor="topup-amount">Số tiền</Label>
            <Input
              id="topup-amount"
              type="number"
              min={MIN_TOPUP}
              max={MAX_TOPUP}
              value={amountInput}
              onChange={(e) => setAmountInput(e.target.value)}
            />
            <div className="flex flex-wrap gap-2">
              {QUICK_AMOUNTS.map((amt) => (
                <Button
                  key={amt}
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setAmountInput(String(amt))}
                  className="rounded-full"
                >
                  {amt >= 1_000_000 ? `${amt / 1_000_000}tr` : `${amt / 1000}k`}
                </Button>
              ))}
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setTopupOpen(false)} disabled={submittingTopup}>
              Huỷ
            </Button>
            <Button onClick={handleTopup} disabled={submittingTopup} className="gap-2">
              {submittingTopup && <Loader2 className="h-4 w-4 animate-spin" />}
              Tiếp tục
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Modal Popup Nạp Tiền Thành Công */}
      <Dialog open={!!successModalData} onOpenChange={() => setSuccessModalData(null)}>
        <DialogContent className="sm:max-w-md text-center p-6 bg-white rounded-2xl shadow-xl border-emerald-100">
          <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100 text-emerald-600 mb-4 animate-pulse">
            <CheckCircle2 className="h-10 w-10" />
          </div>
          <DialogTitle className="text-2xl font-bold text-slate-900">
            Nạp Tiền Thành Công!
          </DialogTitle>
          <DialogDescription className="text-sm text-slate-500 mt-1">
            Giao dịch nạp tiền vào ví của bạn đã được xác nhận qua VNPay.
          </DialogDescription>
          
          <div className="my-6 rounded-xl bg-slate-50 border border-slate-100 p-4 space-y-2.5 text-left">
            <div className="flex justify-between items-center text-sm">
              <span className="text-slate-500">Số tiền nạp</span>
              <span className="font-bold text-emerald-600 text-base">
                +{successModalData?.amount.toLocaleString("vi-VN")} đ
              </span>
            </div>
            <div className="flex justify-between items-center text-sm">
              <span className="text-slate-500">Phương thức</span>
              <span className="font-medium text-slate-700">VNPay Gateway</span>
            </div>
            <div className="flex justify-between items-center text-sm">
              <span className="text-slate-500">Trạng thái</span>
              <Badge className="bg-emerald-50 text-emerald-700 border-emerald-200">Thành công</Badge>
            </div>
          </div>

          <DialogFooter className="sm:justify-center">
            <Button
              className="w-full bg-emerald-600 hover:bg-emerald-700 text-white font-semibold py-2.5 rounded-xl transition-all shadow-md hover:shadow-lg"
              onClick={() => setSuccessModalData(null)}
            >
              Hoàn tất & Tiếp tục
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

export default function CustomerWalletPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen bg-slate-50 flex flex-col">
          <Header />
          <div className="flex-1 flex items-center justify-center">
            <Loader2 className="h-12 w-12 animate-spin text-primary" />
          </div>
          <Footer />
        </div>
      }
    >
      <CustomerWalletContent />
    </Suspense>
  );
}
