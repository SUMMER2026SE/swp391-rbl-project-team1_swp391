'use client'

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { ArrowLeft, MapPin, Clock, Calendar, Star, MessageSquare, AlertCircle, AlertTriangle, Loader2, Info, HelpCircle, ExternalLink, WalletCards, Users } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter, DialogDescription } from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { post, get, put } from '@/lib/api';
import { fetchBookingDetail, type BookingDetailItem } from '@/lib/bookings-api';
import { initiateVnpayPayment } from '@/lib/payments-api';
import { payRemainingWithWallet } from '@/lib/wallet-api';
import { toast } from 'sonner';
import Image from "next/image";
import { useParams, useRouter } from 'next/navigation';
import { Header } from '@/components/layout/Header';
import { Footer } from '@/components/landing/Footer';
import { chatUrl, createContextualConversation } from '@/lib/contextual-chat';
import { RefundExceptionDialog } from '@/components/bookings/RefundExceptionDialog';
import { ReportUserDialog } from '@/components/reports/ReportUserDialog';

const STATUS_CONFIG = {
  confirmed: { label: "Đã xác nhận", className: "bg-green-50 text-green-700 border-green-200" },
  pending: { label: "Chờ xác nhận", className: "bg-amber-50 text-amber-700 border-amber-200" },
  pending_payment: { label: "Chờ thanh toán", className: "bg-orange-50 text-orange-700 border-orange-200" },
  completed: { label: "Hoàn thành", className: "bg-slate-50 text-slate-600 border-slate-200" },
  cancelled: { label: "Đã hủy", className: "bg-red-50 text-red-600 border-red-200" },
} as const;

const PAYMENT_STATUS_CONFIG = {
  unpaid: { label: "Chưa thanh toán", className: "bg-rose-50 text-rose-700 border-rose-200" },
  deposited: { label: "Đã đặt cọc", className: "bg-indigo-50 text-indigo-700 border-indigo-200" },
  paid: { label: "Đã thanh toán", className: "bg-emerald-50 text-emerald-700 border-emerald-200" },
  refunded: { label: "Đã hoàn tiền", className: "bg-blue-50 text-blue-700 border-blue-200" },
  awaiting_cash_payment: { label: "Chờ thu tiền mặt", className: "bg-amber-50 text-amber-700 border-amber-200" }
} as const;

const EXCEPTION_STATUS_CONFIG = {
  PENDING_OWNER: { label: "Chờ Owner duyệt", className: "bg-amber-50 text-amber-700 border-amber-200" },
  APPROVED_OWNER: { label: "Chấp nhận (Chờ hoàn)", className: "bg-green-50 text-green-700 border-green-200" },
  REJECTED_OWNER: { label: "Owner từ chối", className: "bg-red-50 text-red-700 border-red-200" },
  PENDING_ADMIN: { label: "Chờ Admin duyệt (Leo thang)", className: "bg-indigo-50 text-indigo-700 border-indigo-200" },
  APPROVED_ADMIN: { label: "Đã hoàn tiền (Admin)", className: "bg-emerald-50 text-emerald-700 border-emerald-200" },
  REJECTED_ADMIN: { label: "Từ chối cuối (Admin)", className: "bg-slate-50 text-slate-700 border-slate-200" },
  EXPIRED: { label: "Đã hết hạn", className: "bg-slate-50 text-slate-500 border-slate-200" },
} as const;

function getStatusBadge(status: BookingDetailItem["status"]) {
  const config = STATUS_CONFIG[status] || { label: status, className: "bg-slate-50 text-slate-600" };
  return (
    <Badge variant="outline" className={`${config.className} font-bold px-3 py-1 rounded-full text-[10px] uppercase tracking-wider`}>
      {config.label}
    </Badge>
  );
}

function getPaymentStatusBadge(status: string, refundedAmount?: number | null) {
  const normalized = status.toLowerCase();
  if (normalized === 'refunded' && refundedAmount === 0) {
    return (
      <Badge variant="outline" className="bg-rose-50 text-rose-700 border-rose-200 font-bold px-3 py-1 rounded-full text-[10px] uppercase tracking-wider">
        Không hoàn tiền
      </Badge>
    );
  }
  const config = PAYMENT_STATUS_CONFIG[normalized as keyof typeof PAYMENT_STATUS_CONFIG] || { label: status, className: "bg-slate-50 text-slate-600" };
  return (
    <Badge variant="outline" className={`${config.className} font-bold px-3 py-1 rounded-full text-[10px] uppercase tracking-wider`}>
      {config.label}
    </Badge>
  );
}

/** Giải thích chính sách hoàn tiền đã áp dụng — suy ra từ dữ liệu đã có, không gọi thêm API. */
function getCancellationPolicyText(booking: BookingDetailItem): string | null {
  if (booking.refundedAmount === null || booking.refundPercent === null) return null;
  const wasDeposit = booking.paidAmount !== null && booking.paidAmount < booking.totalPrice;
  if (wasDeposit && booking.refundPercent === 0) {
    return "Đơn đặt cọc (30%): khách tự hủy mất toàn bộ tiền cọc — đây là chi phí giữ chỗ theo chính sách đặt cọc, không phải lỗi hệ thống.";
  }
  if (booking.refundPercent === 100) {
    return "Hủy trước giờ chơi ≥ 24 giờ (hoặc do lỗi từ phía sân): hoàn lại toàn bộ giá trị sân.";
  }
  if (booking.refundPercent === 50) {
    return "Hủy trước giờ chơi từ 12 đến dưới 24 giờ: hoàn lại 50% giá trị sân, phí dịch vụ không hoàn.";
  }
  return "Hủy quá sát giờ chơi (< 12 giờ) nên không được hoàn tiền theo chính sách.";
}

export default function BookingDetailPage() {
  const params = useParams();
  const router = useRouter();
  const [booking, setBooking] = useState<BookingDetailItem | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  
  const [complaintOpen, setComplaintOpen] = useState(false);
  const [reportOpen, setReportOpen] = useState(false);
  const [complaintText, setComplaintText] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [paying, setPaying] = useState(false);
  const [payingRemaining, setPayingRemaining] = useState(false);
  const [chatStarting, setChatStarting] = useState(false);
  
  const [exceptionRequest, setExceptionRequest] = useState<any | null>(null);
  const [exceptionDialogOpen, setExceptionDialogOpen] = useState(false);
  const [escalating, setEscalating] = useState(false);
  const [payRemainingConfirmOpen, setPayRemainingConfirmOpen] = useState(false);

  const handleMessageOwner = async () => {
    if (!booking?.ownerUserId) return toast.error('Không tìm thấy tài khoản chủ sân');
    try {
      setChatStarting(true);
      const conversationId = await createContextualConversation(booking.ownerUserId, {
        action: 'booking_referral',
        bookingId: Number(booking.id),
        stadiumId: booking.stadiumId,
        stadiumName: booking.venueName,
        complexId: booking.complexId ?? undefined,
        complexName: booking.complexName ?? undefined,
        facilityId: booking.facilityId ?? undefined,
        facilityName: booking.facilityName ?? undefined,
        playDate: booking.playDate,
        time: `${booking.startTime} - ${booking.endTime}`,
      });
      router.push(chatUrl(conversationId));
    } catch { toast.error('Không thể bắt đầu cuộc trò chuyện'); }
    finally { setChatStarting(false); }
  };

  const loadExceptionData = async (bookingId: string) => {
    try {
      const data = await get<any>(`/refund-exceptions/booking/${bookingId}/latest`);
      setExceptionRequest(data || null);
    } catch {
      setExceptionRequest(null);
    }
  };

  useEffect(() => {
    const loadData = async () => {
      if (!params?.id) return;
      try {
        setLoading(true);
        const data = await fetchBookingDetail(params.id as string);
        setBooking(data);
        if (data.status === 'cancelled') {
          await loadExceptionData(params.id as string);
        }
      } catch (err: any) {
        setError(err.message || 'Không thể tải chi tiết đặt sân');
      } finally {
        setLoading(false);
      }
    };

    loadData();
  }, [params?.id]);

  const handleEscalate = async () => {
    if (!exceptionRequest) return;
    try {
      setEscalating(true);
      const res = await put<any>(`/refund-exceptions/${exceptionRequest.requestId}/escalate`);
      toast.success("Đã leo thang yêu cầu lên Admin thành công!");
      setExceptionRequest(res);
    } catch (err: any) {
      toast.error(err.message || "Có lỗi xảy ra khi leo thang.");
    } finally {
      setEscalating(false);
    }
  };

  const handlePayWithVnpay = async (option: string = "FULL") => {
    if (!booking) return;
    if (paying) return;
    try {
      setPaying(true);
      const { paymentUrl } = await initiateVnpayPayment(parseInt(booking.id, 10), option);
      // Rời app — phải dùng window.location (không dùng được router.push)
      window.location.href = paymentUrl;
    } catch (err: any) {
      toast.error(err.message || 'Không thể tạo liên kết thanh toán VNPay');
      setPaying(false);
    }
  };

  const handlePayRemainingWithWallet = async () => {
    if (!booking) return;
    if (payingRemaining) return;
    try {
      setPayingRemaining(true);
      await payRemainingWithWallet(parseInt(booking.id, 10));
      toast.success("Đã thanh toán nốt phần còn lại bằng Ví thành công!");
      const updated = await fetchBookingDetail(booking.id);
      setBooking(updated);
    } catch (err: any) {
      toast.error(err.message || "Không thể thanh toán bằng Ví");
    } finally {
      setPayingRemaining(false);
    }
  };

  const handleSubmitComplaint = async () => {
    if (!complaintText.trim() || !booking) return;
    try {
      setSubmitting(true);
      const response = await post<{ complaintId: number }>(`/complaints`, {
        bookingId: parseInt(booking.id),
        subject: "Khiếu nại từ đơn đặt sân #" + booking.displayId,
        description: complaintText.trim() 
      });
      toast.success("Đã gửi khiếu nại thành công! Chủ sân sẽ sớm phản hồi.");
      setComplaintOpen(false);
      setComplaintText("");
      router.push(`/complaints?complaintId=${response.complaintId}`);
    } catch (err: any) {
      toast.error(err.message || "Có lỗi xảy ra khi gửi khiếu nại.");
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <div className="min-h-screen bg-slate-50 flex flex-col">
        <Header />
        <div className="flex-1 flex items-center justify-center">
          <div className="text-center space-y-4">
            <Loader2 className="h-12 w-12 animate-spin text-primary mx-auto" />
            <p className="text-slate-500 font-medium">Đang tải chi tiết đơn hàng...</p>
          </div>
        </div>
        <Footer />
      </div>
    );
  }

  if (error || !booking) {
    return (
      <div className="min-h-screen bg-slate-50 flex flex-col">
        <Header />
        <div className="flex-1 flex flex-col items-center justify-center gap-6 p-4">
          <div className="bg-red-50 p-6 rounded-full">
            <AlertCircle className="h-16 w-16 text-red-500" />
          </div>
          <div className="text-center">
            <h2 className="text-2xl font-bold text-slate-900 mb-2">Lỗi tải dữ liệu</h2>
            <p className="text-slate-500 font-medium max-w-xs">{error || 'Không tìm thấy đơn đặt sân'}</p>
          </div>
          <Button onClick={() => router.push('/profile?tab=bookings')} variant="outline" className="rounded-xl px-8 border-slate-200">
            Quay lại danh sách
          </Button>
        </div>
        <Footer />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <Header />

      <main className="flex-1 container mx-auto px-4 py-8 max-w-3xl">
        {/* Breadcrumb & Navigation */}
        <div className="mb-6 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div className="flex items-center gap-2 text-sm text-slate-500 font-semibold">
            <Link href="/profile?tab=bookings" className="hover:text-primary transition-colors">Lịch sử đặt sân</Link>
            <span className="text-slate-300">/</span>
            <span className="text-slate-800">{booking.displayId}</span>
          </div>
          <div className="text-left sm:text-right">
            <p className="text-[10px] uppercase tracking-widest text-slate-400 font-bold">Mã đơn hàng</p>
            <p className="font-mono font-bold text-slate-900 text-lg">{booking.displayId}</p>
          </div>
        </div>

        <div className="grid grid-cols-1 gap-6">
          {/* Main Card: Venue & Status */}
          <Card className="overflow-hidden rounded-3xl border-none shadow-sm bg-white">
            <div className="relative h-48 md:h-64">
              <Image
                src={booking.imageUrl}
                alt={booking.venueName}
                fill
                className="object-cover"
                unoptimized
              />
              <div className="absolute inset-0 bg-gradient-to-t from-black/60 to-transparent" />
              <div className="absolute bottom-6 left-6 right-6">
                <div className="flex flex-wrap items-center justify-between gap-4">
                  <div>
                    <Badge className="bg-white/20 hover:bg-white/30 text-white border-white/40 backdrop-blur-md mb-2">
                      {booking.sportType}
                    </Badge>
                    <h1 className="text-2xl md:text-3xl font-bold text-white leading-tight">
                      {booking.venueName}
                    </h1>
                    {booking.complexName && (
                      <p className="text-sm text-white/80 mt-0.5">{booking.complexName}</p>
                    )}
                  </div>
                  <div className="flex items-center gap-2 bg-white/10 backdrop-blur-md p-1.5 rounded-2xl border border-white/20">
                    {getStatusBadge(booking.status)}
                    {getPaymentStatusBadge(booking.paymentStatus)}
                  </div>
                </div>
              </div>
            </div>
            
            <CardContent className="p-6">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                <div className="flex items-start gap-3">
                  <div className="bg-slate-50 p-2 rounded-xl shrink-0">
                    <MapPin className="h-5 w-5 text-primary" />
                  </div>
                  <div>
                    <p className="text-xs font-bold text-slate-400 uppercase tracking-wider mb-0.5">Địa chỉ</p>
                    <p className="text-slate-700 font-medium leading-relaxed">{booking.address}</p>
                  </div>
                </div>
                {booking.stadiumId && (
                  <Button asChild variant="outline" size="sm" className="rounded-xl border-slate-200 text-slate-700 hover:bg-slate-50 gap-1.5 shrink-0 self-start sm:self-center">
                    <Link href={`/venues/${booking.stadiumId}`}>
                      Xem chi tiết sân
                      <ExternalLink className="h-3.5 w-3.5" />
                    </Link>
                  </Button>
                )}
              </div>
            </CardContent>
          </Card>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            {/* Booking Details */}
            <Card className="rounded-3xl border-none shadow-sm bg-white p-6">
              <h3 className="text-lg font-bold text-slate-900 mb-6 flex items-center gap-2">
                <Calendar className="h-5 w-5 text-primary" />
                Thông tin đặt sân
              </h3>
              
              <div className="space-y-6">
                {/* Highlighted time and date display */}
                <div className="flex items-center rounded-2xl bg-slate-50 px-4 py-3 text-xs sm:text-sm border border-slate-100">
                  <div className="flex-1">
                    <div className="text-[10px] sm:text-xs font-semibold text-slate-400 tracking-[0.08em]">
                      NGÀY RA SÂN
                    </div>
                    <div className="mt-1 text-sm sm:text-base font-semibold text-slate-800">
                      {booking.playDate}
                    </div>
                  </div>

                  <div className="w-px mx-4 bg-slate-200 self-stretch" />

                  <div className="flex-1 text-right">
                    <div className="text-[10px] sm:text-xs font-semibold text-slate-400 tracking-[0.08em]">
                      KHUNG GIỜ CHƠI
                    </div>
                    <div className="mt-1 text-sm sm:text-base font-medium text-emerald-700 whitespace-nowrap">
                      {booking.startTime} - {booking.endTime}
                    </div>
                  </div>
                </div>

                <Separator className="bg-slate-50" />

                <div className="flex items-start gap-3">
                  <Info className="h-4 w-4 text-slate-400 mt-0.5" />
                  <div>
                    <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1">Ghi chú</p>
                    <p className="text-sm text-slate-600 italic">
                      {booking.note || "Không có ghi chú nào từ khách hàng."}
                    </p>
                  </div>
                </div>
              </div>
            </Card>

            {/* Payment Summary */}
            <Card className="rounded-3xl border-none shadow-sm bg-white p-6 flex flex-col justify-between">
              <div>
                <h3 className="text-lg font-bold text-slate-900 mb-6">Thanh toán</h3>
                
                <div className="space-y-4">
                  {(() => {
                    const accessoriesTotal = booking.accessories?.reduce((sum, a) => sum + (a.quantity * a.unitPrice), 0) || 0;
                    const courtPrice = Math.max(0, booking.totalPrice - booking.serviceFee - accessoriesTotal);
                    return (
                      <>
                        <div className="flex justify-between items-center text-sm">
                          <span className="text-slate-500 font-medium">Giá sân</span>
                          <span className="font-bold text-slate-700">
                            {courtPrice.toLocaleString('vi-VN')}đ
                          </span>
                        </div>
                        {booking.accessories && booking.accessories.length > 0 && (
                          <div className="space-y-2 border-t border-b border-dashed border-slate-100 py-2 my-1">
                            <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider">Phụ kiện đặt kèm</p>
                            {booking.accessories.map((a, idx) => (
                              <div key={idx} className="flex justify-between items-center text-sm">
                                <span className="text-slate-500 text-xs">
                                  {a.accessoryName} <span className="text-slate-400">({a.quantity} x {a.unitPrice.toLocaleString('vi-VN')}đ)</span>
                                </span>
                                <span className="font-semibold text-slate-600 text-xs">
                                  {(a.quantity * a.unitPrice).toLocaleString('vi-VN')}đ
                                </span>
                              </div>
                            ))}
                          </div>
                        )}
                        <div className="flex justify-between items-center text-sm">
                          <span className="text-slate-500 font-medium">Phí dịch vụ</span>
                          <span className="font-bold text-slate-700">{booking.serviceFee.toLocaleString('vi-VN')}đ</span>
                        </div>
                      </>
                    );
                  })()}
                  
                  <Separator className="bg-slate-100" />
                  
                  <div className="flex justify-between items-end">
                    <div>
                      <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1">Tổng cộng</p>
                      <p className="text-xl font-bold text-primary">
                        {booking.totalPrice.toLocaleString('vi-VN')}đ
                      </p>
                    </div>
                    {getPaymentStatusBadge(booking.paymentStatus, booking.refundedAmount)}
                  </div>

                  {/* Hiển thị thông tin cọc đã đóng (đối với cả đơn đang cọc lẫn đơn cọc đã bị hủy) */}
                  {(booking.paymentStatus === 'deposited' || (booking.paidAmount !== null && booking.paidAmount < booking.totalPrice)) && (
                    <>
                      <Separator className="bg-slate-100" />
                      <div className="flex justify-between items-center text-sm bg-indigo-50/60 -mx-2 px-2 py-2 rounded-xl">
                        <span className="text-indigo-700 font-semibold">Đã đặt cọc</span>
                        <span className="font-bold text-indigo-700">
                          {booking.paidAmount !== null
                            ? `${booking.paidAmount.toLocaleString('vi-VN')}đ (30%)`
                            : 'Đang xử lý...'}
                        </span>
                      </div>
                      {booking.paidAmount !== null && booking.status !== 'cancelled' && (
                        <div className="flex justify-between items-center text-xs px-2">
                          <span className="text-slate-500">Còn lại khi đến sân</span>
                          <span className="font-semibold text-slate-600">
                            {Math.max(0, booking.totalPrice - booking.paidAmount).toLocaleString('vi-VN')}đ
                          </span>
                        </div>
                      )}
                    </>
                  )}

                  {booking.paymentStatus === 'refunded' && (
                    <>
                      <Separator className="bg-slate-100" />
                      <div className="flex flex-col gap-2 bg-blue-50/60 -mx-2 px-3 py-2.5 rounded-xl">
                        <div className="flex justify-between items-center text-sm">
                          <span className="text-blue-700 font-semibold">
                            {booking.refundedAmount === 0 ? "Số tiền hoàn" : "Đã hoàn tiền"}
                          </span>
                          <span className="font-bold text-blue-700">
                            {booking.refundedAmount !== null
                              ? `${booking.refundedAmount.toLocaleString('vi-VN')}đ${booking.refundPercent !== null ? ` (${booking.refundPercent}%)` : ''}`
                              : 'Đang xử lý...'}
                          </span>
                        </div>
                        {getCancellationPolicyText(booking) && (
                          <p className="text-[11px] text-blue-600/80 italic border-t border-blue-100/50 pt-1.5">
                            {getCancellationPolicyText(booking)}
                          </p>
                        )}
                        {booking.refundedAmount !== null && booking.refundedAmount > 0 && (
                          <div className="mt-1 flex items-center gap-1.5 text-[11px] text-blue-600/80 border-t border-blue-100/50 pt-1.5">
                            <WalletCards className="h-3 w-3 shrink-0" />
                            Số tiền hoàn đã được cộng vào Ví của bạn.
                          </div>
                        )}
                      </div>
                    </>
                  )}
                </div>
              </div>

              {/* Thanh toán ngay button flow */}
              <div className="pt-6 space-y-2">
                {(booking.status === 'pending' || booking.status === 'pending_payment' || booking.status === 'confirmed') && booking.paymentStatus === 'unpaid' && (
                  <div className="flex flex-col gap-2">
                    <Button
                      className="w-full bg-emerald-600 hover:bg-emerald-700 text-white font-bold rounded-2xl h-11 disabled:opacity-60"
                      onClick={() => handlePayWithVnpay("FULL")}
                      disabled={paying}
                    >
                      {paying ? "Đang chuyển hướng..." : "Thanh toán toàn bộ"}
                    </Button>
                    <Button
                      variant="outline"
                      className="w-full text-emerald-700 border-emerald-200 hover:bg-emerald-50 font-bold rounded-2xl h-11 disabled:opacity-60"
                      onClick={() => handlePayWithVnpay("DEPOSIT")}
                      disabled={paying}
                    >
                      Thanh toán cọc 30%
                    </Button>
                  </div>
                )}
                {booking.paymentStatus === 'deposited' && booking.status !== 'cancelled' && (
                  <Button
                    className="w-full bg-emerald-600 hover:bg-emerald-700 text-white font-bold rounded-2xl h-11 gap-2 disabled:opacity-60"
                    onClick={() => setPayRemainingConfirmOpen(true)}
                    disabled={payingRemaining}
                  >
                    <WalletCards className="h-4 w-4" />
                    {payingRemaining ? "Đang xử lý..." : "Thanh toán nốt bằng Ví"}
                  </Button>
                )}
                <p className="text-[10px] text-slate-400 text-center">
                  Đặt lúc: {booking.createdAt}
                </p>
              </div>
            </Card>
          </div>

          {/*
            Chỉ cho xem xét ngoại lệ khi đơn CANCELLED + đã thực sự hoàn tiền (paymentStatus
            'refunded' — loại trừ booking hủy thẳng khi đang chờ thu tiền mặt tại sân, chưa có
            gì để hoàn) + refundPercent < 100 (đã hoàn đủ 100% thì không còn gì để xin thêm).
            Nếu đã có exceptionRequest đang tồn tại thì luôn hiện để khách theo dõi trạng thái,
            bất kể refundPercent hiện tại (vì có thể đã đổi sau khi ngoại lệ được duyệt).
          */}
          {booking.status === 'cancelled' && (exceptionRequest || (
            booking.paymentStatus === 'refunded' &&
            booking.paidAmount !== null &&
            booking.paidAmount === booking.totalPrice &&
            booking.refundPercent !== null &&
            booking.refundPercent < 100
          )) && (
            <Card className="rounded-3xl border border-slate-100 shadow-sm bg-white p-6">
              <h3 className="text-lg font-bold text-slate-900 mb-4 flex items-center gap-2">
                <HelpCircle className="h-5 w-5 text-emerald-500" />
                Xem xét ngoại lệ hoàn tiền
              </h3>
              
              {exceptionRequest ? (
                <div className="space-y-4">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium text-slate-500">Trạng thái yêu cầu:</span>
                    <Badge variant="outline" className={`${EXCEPTION_STATUS_CONFIG[exceptionRequest.status as keyof typeof EXCEPTION_STATUS_CONFIG]?.className || "bg-slate-50 text-slate-600"} font-bold px-3 py-1 rounded-full text-[10px] uppercase tracking-wider`}>
                      {EXCEPTION_STATUS_CONFIG[exceptionRequest.status as keyof typeof EXCEPTION_STATUS_CONFIG]?.label || exceptionRequest.status}
                    </Badge>
                  </div>
                  
                  <div className="rounded-2xl bg-slate-50 p-4 space-y-3 text-sm">
                    <div>
                      <span className="font-semibold text-slate-700">Lý do của bạn:</span>
                      <p className="text-slate-600 mt-1 italic">"{exceptionRequest.reason}"</p>
                    </div>

                    {exceptionRequest.evidenceUrl && (
                      <div className="space-y-1.5">
                        <span className="font-semibold text-slate-700">Bằng chứng đã gửi:</span>
                        <a
                          href={exceptionRequest.evidenceUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="block border border-slate-200 rounded-xl overflow-hidden bg-white hover:opacity-90 transition-opacity max-w-xs"
                        >
                          {/* eslint-disable-next-line @next/next/no-img-element */}
                          <img
                            src={exceptionRequest.evidenceUrl}
                            alt="Bằng chứng bất khả kháng"
                            className="w-full object-contain max-h-52 bg-white"
                            onError={(e) => {
                              (e.currentTarget as HTMLImageElement).style.display = "none";
                              e.currentTarget.nextElementSibling?.classList.remove("hidden");
                            }}
                          />
                          <span className="hidden block px-3 py-2 text-xs text-primary break-all">
                            {exceptionRequest.evidenceUrl}
                          </span>
                        </a>
                      </div>
                    )}
                    
                    {exceptionRequest.refundPercent !== null && (
                      <div>
                        <span className="font-semibold text-slate-700">Tỷ lệ hoàn tiền được duyệt:</span>
                        <span className="ml-2 font-bold text-emerald-600">{exceptionRequest.refundPercent}%</span>
                      </div>
                    )}

                    {exceptionRequest.ownerNote && (
                      <div>
                        <span className="font-semibold text-slate-700">Phản hồi từ Chủ sân:</span>
                        <p className="text-slate-600 mt-1">"{exceptionRequest.ownerNote}"</p>
                      </div>
                    )}

                    {exceptionRequest.adminNote && (
                      <div>
                        <span className="font-semibold text-slate-700">Quyết định từ Admin:</span>
                        <p className="text-slate-600 mt-1">"{exceptionRequest.adminNote}"</p>
                      </div>
                    )}
                  </div>

                  {exceptionRequest.canEscalate && (
                    <div className="space-y-2">
                      {exceptionRequest.status === 'APPROVED_OWNER' && (
                        <p className="text-xs text-slate-500 leading-relaxed">
                          Chưa hài lòng với mức {exceptionRequest.refundPercent}% Chủ sân đã duyệt? Bạn có thể yêu cầu Admin xem xét lại — nếu Admin duyệt mức cao hơn, bạn chỉ nhận thêm phần chênh lệch.
                        </p>
                      )}
                      <Button
                        onClick={handleEscalate}
                        disabled={escalating}
                        className="w-full bg-indigo-600 hover:bg-indigo-700 text-white font-bold rounded-2xl h-11 disabled:opacity-60"
                      >
                        {escalating ? "Đang xử lý..." : "Leo thang lên Ban quản trị (Admin)"}
                      </Button>
                    </div>
                  )}
                </div>
              ) : (
                <div className="space-y-4">
                  <p className="text-sm text-slate-600 leading-relaxed">
                    Đơn hủy sát giờ nhận hoàn tiền 0%. Nếu bạn gặp sự cố bất khả kháng (sức khỏe, tai nạn...), bạn có thể gửi yêu cầu kèm bằng chứng để Chủ sân và Admin xem xét hoàn lại 50% hoặc 100%.
                  </p>
                  <Button
                    onClick={() => setExceptionDialogOpen(true)}
                    className="w-full bg-slate-900 hover:bg-slate-800 text-white font-bold rounded-2xl h-11"
                  >
                    Nộp yêu cầu ngoại lệ
                  </Button>
                </div>
              )}
            </Card>
          )}

          {/* Action Buttons */}
          <Card className="rounded-3xl border-none shadow-sm bg-slate-900 p-6">
            <div className="flex flex-col sm:flex-row gap-4">
              {/* Liên hệ chủ sân — luôn hiện bất kể đơn đã hủy/hoàn tiền hay chưa, khách có thể
                  cần hỏi về tiến độ hoàn tiền hoặc trao đổi thêm sau khi hủy. */}
              <Button variant="secondary" className="rounded-2xl flex-1 font-bold h-12" onClick={handleMessageOwner} disabled={chatStarting}>
                <MessageSquare className="h-4 w-4 mr-2" />
                {chatStarting ? 'Đang mở...' : 'Liên hệ chủ sân'}
              </Button>
              {booking.status === 'confirmed' && (
                <Button asChild className="rounded-2xl flex-1 font-bold h-12 bg-indigo-500 hover:bg-indigo-600">
                  <Link href={`/community?action=create&bookingId=${booking.id}`}>
                    <Users className="h-4 w-4 mr-2" />
                    Tìm đối thủ (Tạo kèo)
                  </Link>
                </Button>
              )}
              {(booking.status === 'confirmed' || booking.status === 'pending') && (
                <Button asChild variant="destructive" className="rounded-2xl flex-1 font-bold h-12">
                  <Link href={`/booking/${booking.id}/cancel`}>Huỷ đơn đặt</Link>
                </Button>
              )}
              {booking.status === 'completed' && (
                <>
                  <Button asChild className="rounded-2xl flex-1 font-bold h-12 bg-emerald-500 hover:bg-emerald-600">
                    <Link href={`/booking/${booking.id}/review`}>
                      <Star className="h-4 w-4 mr-2" />
                      Viết đánh giá
                    </Link>
                  </Button>
                  <Button
                    variant="outline"
                    className="rounded-2xl flex-1 font-bold h-12 bg-transparent text-white border-white/20 hover:bg-white/10"
                    onClick={() => setComplaintOpen(true)}
                  >
                    <AlertCircle className="h-4 w-4 mr-2 text-red-400" />
                    Gửi khiếu nại
                  </Button>
                  <Button
                    variant="outline"
                    className="rounded-2xl flex-1 font-bold h-12 bg-transparent text-white border-white/20 hover:bg-white/10"
                    onClick={() => setReportOpen(true)}
                    disabled={!booking.ownerUserId}
                  >
                    <AlertTriangle className="h-4 w-4 mr-2 text-amber-400" />
                    Báo cáo hành vi
                  </Button>
                </>
              )}
              {booking.status === 'cancelled' && (
                <>
                  {/* Chỉ hiện khiếu nại/báo cáo khi đơn có cancelReason (huỷ thủ công) HOẶC đã từng
                      thực sự thu tiền rồi hoàn (paymentStatus=refunded) — tức có giao dịch thật.
                      cancelReason có thể NULL ngay cả khi huỷ thủ công nếu người hủy để trống lý do
                      (vd Owner hủy/hoàn tiền không bắt buộc nhập lý do), nên không thể chỉ dựa vào
                      cancelReason một mình. Chỉ khi CẢ HAI đều rỗng — auto-cancel do hết hạn giữ chỗ,
                      chưa từng thanh toán — thì mới thực sự chưa có gì để khiếu nại. */}
                  {(booking.cancelReason != null || booking.paymentStatus.toLowerCase() === 'refunded') && (
                    <>
                      <Button
                        variant="outline"
                        className="rounded-2xl flex-1 font-bold h-12 bg-transparent text-white border-white/20 hover:bg-white/10"
                        onClick={() => setComplaintOpen(true)}
                      >
                        <AlertCircle className="h-4 w-4 mr-2 text-red-400" />
                        Gửi khiếu nại
                      </Button>
                      <Button
                        variant="outline"
                        className="rounded-2xl flex-1 font-bold h-12 bg-transparent text-white border-white/20 hover:bg-white/10"
                        onClick={() => setReportOpen(true)}
                        disabled={!booking.ownerUserId}
                      >
                        <AlertTriangle className="h-4 w-4 mr-2 text-amber-400" />
                        Báo cáo hành vi
                      </Button>
                    </>
                  )}
                  <Button asChild variant="secondary" className="rounded-2xl flex-1 font-bold h-12">
                    <Link href="/search">Đặt sân khác</Link>
                  </Button>
                </>
              )}
            </div>
          </Card>
        </div>
      </main>

      <Footer />

      {/* Complaint Dialog */}
      <Dialog open={complaintOpen} onOpenChange={setComplaintOpen}>
        <DialogContent className="rounded-3xl sm:max-w-md p-6">
          <DialogHeader>
            <DialogTitle className="text-xl font-bold">Gửi khiếu nại</DialogTitle>
            <DialogDescription className="text-slate-500 font-medium">
              Vui lòng mô tả vấn đề bạn gặp phải. Chúng tôi và chủ sân sẽ hỗ trợ bạn sớm nhất.
            </DialogDescription>
          </DialogHeader>
          <div className="py-4">
            <Textarea
              placeholder="Ví dụ: Sân không đúng mô tả, chủ sân không cho vào, ..."
              value={complaintText}
              onChange={(e) => setComplaintText(e.target.value)}
              className="min-h-[120px] rounded-2xl border-slate-200 focus-visible:ring-primary"
            />
          </div>
          <DialogFooter className="flex-col sm:flex-row gap-2">
            <Button variant="ghost" onClick={() => setComplaintOpen(false)} className="rounded-xl flex-1">Hủy</Button>
            <Button 
              onClick={handleSubmitComplaint} 
              disabled={!complaintText.trim() || submitting} 
              className="rounded-xl flex-1 bg-red-600 hover:bg-red-700 font-bold"
            >
              {submitting ? "Đang gửi..." : "Gửi khiếu nại"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ReportUserDialog
        open={reportOpen}
        onOpenChange={setReportOpen}
        reporteeId={booking.ownerUserId}
        bookingId={parseInt(booking.id, 10)}
        stadiumId={booking.stadiumId}
        contextLabel={`Báo cáo hành vi liên quan đến đơn đặt sân ${booking.displayId}.`}
      />

      {/* Refund Exception Dialog */}
      <RefundExceptionDialog
        bookingId={exceptionDialogOpen && booking ? parseInt(booking.id, 10) : null}
        onOpenChange={setExceptionDialogOpen}
        onSubmitSuccess={() => {
          if (booking) {
            loadExceptionData(booking.id);
          }
        }}
      />

      {/* Pay Remaining Confirmation Dialog */}
      <Dialog open={payRemainingConfirmOpen} onOpenChange={setPayRemainingConfirmOpen}>
        <DialogContent className="rounded-3xl sm:max-w-md p-6">
          <DialogHeader>
            <DialogTitle className="text-xl font-bold">Xác nhận thanh toán</DialogTitle>
            <DialogDescription className="text-slate-500 font-medium">
              Bạn có chắc chắn muốn thanh toán nốt phần còn lại bằng Ví nội bộ của mình?
            </DialogDescription>
          </DialogHeader>
          <div className="py-4">
            <div className="flex justify-between items-center text-sm border-b border-slate-100 pb-3">
              <span className="text-slate-500">Số tiền thanh toán:</span>
              <span className="font-bold text-slate-800 text-base">
                {booking && (booking.totalPrice - (booking.paidAmount || 0)).toLocaleString('vi-VN')}đ
              </span>
            </div>
            {booking && (
              <div className="mt-3 text-xs text-amber-600 bg-amber-50 rounded-xl p-3 flex gap-2">
                <AlertCircle className="h-4 w-4 shrink-0" />
                <span>Tiền sẽ được trừ trực tiếp từ Ví của bạn và chuyển trạng thái đơn sang ĐÃ THANH TOÁN.</span>
              </div>
            )}
          </div>
          <DialogFooter className="flex-col sm:flex-row gap-2">
            <Button variant="ghost" onClick={() => setPayRemainingConfirmOpen(false)} className="rounded-xl flex-1">
              Quay lại
            </Button>
            <Button 
              onClick={async () => {
                await handlePayRemainingWithWallet();
                setPayRemainingConfirmOpen(false);
              }} 
              disabled={payingRemaining} 
              className="rounded-xl flex-1 bg-emerald-600 hover:bg-emerald-700 font-bold"
            >
              {payingRemaining ? "Đang xử lý..." : "Xác nhận"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
