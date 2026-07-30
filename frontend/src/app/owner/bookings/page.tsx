'use client'

import { useState, useEffect, useCallback } from "react";
import { useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Checkbox } from "@/components/ui/checkbox";
import {
  CheckCircle,
  XCircle,
  ChevronDown,
  ChevronUp,
  RotateCcw,
  Clock,
  DollarSign,
  Info,
  User,
  Calendar,
  AlertCircle,
  AlertTriangle,
  HelpCircle,
  ChevronLeft,
  ChevronRight,
} from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { toast } from "sonner";
import { get, post, put } from "@/lib/api";
import { cancelBooking } from "@/lib/bookings-api";
import type { PageResponse } from "@/types/common";
import { ReportUserDialog } from "@/components/reports/ReportUserDialog";
import { fetchWalletTransactionsByBooking, type WalletTransaction } from "@/lib/wallet-api";

interface BookingItem {
  id: number;
  displayId: string;
  customer: {
    userId?: number;
    name: string;
    phone: string;
    email: string;
  };
  stadiumId?: number;
  venue: string;
  /** Tên khu phức hợp chứa sân — null nếu sân độc lập. Cùng 1 Owner có thể có nhiều complex dùng trùng tên sân con (vd "Sân 1"). */
  complexName?: string | null;
  date: string;
  time: string;
  amount: number;
  refundAmount: number;
  serviceFee?: number;
  /** Số tiền thực tế đã thu qua cổng tính đến hiện tại — full nếu PAID, một phần nếu DEPOSITED. */
  paidAmount?: number;
  paymentStatus: string;
  status: string;
  notes: string;
  playTimeRaw: string;
  isWalkIn?: boolean;
}

interface StadiumOption {
  stadiumId: number;
  stadiumName: string;
}

interface RefundResponse {
  bookingId: number;
  stadiumName: string;
  customerName: string;
  playTime: string;
  originalPrice: number;
  serviceFee?: number;
  refundAmount: number;
  refundPercentage: number;
  bookingStatus: string;
  paymentStatus: string;
  processedAt: string;
  reason?: string;
  cancellationPolicyDescription?: string;
}

interface OwnerBookingsSummary {
  grossAmount: number;
  refundedAmount: number;
  serviceFee: number;
  netAmount: number;
}

interface RefundExceptionItem {
  id: number;
  bookingId: number;
  exceptionType: string;
  requestedAmount: number;
  approvedAmount?: number;
  status: string;
  reason?: string;
  createdAt?: string;
  evidenceUrl?: string;
  ownerNote?: string;
  refundPercent?: number;
  adminNote?: string;
}

function BookingManagementPage() {
  const [expandedRow, setExpandedRow] = useState<number | null>(null);
  const [bookingList, setBookingList] = useState<BookingItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  // States phục vụ Hoàn Tiền (UC-OWN-12)
  const [selectedBooking, setSelectedBooking] = useState<BookingItem | null>(null);
  const [isCancelModalOpen, setIsCancelModalOpen] = useState(false);
  const [cancelReason, setCancelReason] = useState("");
  const [reasonType, setReasonType] = useState("CUSTOMER_REQUEST");
  const [proofUrl, setProofUrl] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Xem trước tiền hoàn từ Backend (Tránh clock skew của client)
  const [previewData, setPreviewData] = useState<RefundResponse | null>(null);
  const [isPreviewLoading, setIsPreviewLoading] = useState(false);

  // States phục vụ hiển thị kết quả thành công Premium
  const [successData, setSuccessData] = useState<RefundResponse | null>(null);
  const [isSuccessModalOpen, setIsSuccessModalOpen] = useState(false);

  // Hủy đơn chưa thu tiền (vd: đang chờ thu tiền mặt) — không có gì để hoàn nên
  // dùng thẳng luồng cancelBooking() thay vì modal "Hủy & Hoàn Tiền" (yêu cầu
  // có giao dịch PAID/DEPOSITED để tính % hoàn).
  const [cancelOnlyBooking, setCancelOnlyBooking] = useState<BookingItem | null>(null);
  const [cancelOnlyReason, setCancelOnlyReason] = useState("");
  const [isCancelOnlySubmitting, setIsCancelOnlySubmitting] = useState(false);

  // Xác nhận đã thu tiền mặt tại sân (AWAITING_CASH_PAYMENT → PAID).
  const [confirmCashBooking, setConfirmCashBooking] = useState<BookingItem | null>(null);
  const [isConfirmCashSubmitting, setIsConfirmCashSubmitting] = useState(false);
  const [receiptBooking, setReceiptBooking] = useState<BookingItem | null>(null);

  // Xác nhận đã thu nốt phần còn lại của đơn đặt cọc (DEPOSITED → PAID).
  const [confirmRemainingBooking, setConfirmRemainingBooking] = useState<BookingItem | null>(null);
  const [isConfirmRemainingSubmitting, setIsConfirmRemainingSubmitting] = useState(false);

  // Báo cáo hành vi khách hàng (Owner → Customer).
  const [reportBooking, setReportBooking] = useState<BookingItem | null>(null);

  const [activeTab, setActiveTab] = useState("all");
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  const [filterStartDate, setFilterStartDate] = useState("");
  const [filterEndDate, setFilterEndDate] = useState("");
  const [filterStadiumId, setFilterStadiumId] = useState("all");
  const [ownerStadiums, setOwnerStadiums] = useState<StadiumOption[]>([]);

  useEffect(() => {
    async function fetchStadiums() {
      try {
        const data = await get<StadiumOption[]>('/stadiums/my');
        setOwnerStadiums(data || []);
      } catch (err) {
        console.error("Error fetching stadiums:", err);
      }
    }
    fetchStadiums();
  }, []);

  // Tổng Gross/Refund/Fee/Net trên TOÀN BỘ booking (không phụ thuộc phân trang) — lấy từ backend
  // thay vì tự cộng dồn bookingList (chỉ có 1 trang), tránh bug tổng bị lệch khi tạo đơn mới.
  const [summary, setSummary] = useState<{
    grossAmount: number;
    refundedAmount: number;
    serviceFee: number;
    netAmount: number;
  } | null>(null);

  const fetchSummary = useCallback(async () => {
    try {
      const query = new URLSearchParams();
      if (activeTab !== "all") {
        query.set("status", activeTab.toUpperCase());
      }
      if (filterStartDate) query.set("startDate", filterStartDate);
      if (filterEndDate) query.set("endDate", filterEndDate);
      if (filterStadiumId !== "all") query.set("stadiumId", filterStadiumId);
      const data = await get<OwnerBookingsSummary>(`/owner/bookings/summary?${query.toString()}`);
      setSummary({
        grossAmount: Number(data?.grossAmount) || 0,
        refundedAmount: Number(data?.refundedAmount) || 0,
        serviceFee: Number(data?.serviceFee) || 0,
        netAmount: Number(data?.netAmount) || 0,
      });
    } catch (error: unknown) {
      console.error("Error fetching bookings summary:", error);
    }
  }, [activeTab, filterStartDate, filterEndDate, filterStadiumId]);

  useEffect(() => {
    fetchSummary();
  }, [fetchSummary]);

  // Fetch danh sách đặt sân thực tế từ Backend
  const fetchBookings = useCallback(async () => {
    try {
      setIsLoading(true);
      const query = new URLSearchParams({
        page: String(page),
        size: "20",
      });
      if (activeTab !== "all") {
        query.set("status", activeTab.toUpperCase());
      }
      if (filterStartDate) query.set("startDate", filterStartDate);
      if (filterEndDate) query.set("endDate", filterEndDate);
      if (filterStadiumId !== "all") query.set("stadiumId", filterStadiumId);
      const data = await get<PageResponse<BookingItem> | BookingItem[]>(
        `/owner/bookings?${query.toString()}`
      );

      // Defensive: hỗ trợ cả mảng phẳng (API cũ) và PageResponse (API mới),
      // đồng thời không đưa dữ liệu sai kiểu vào state.
      const list: BookingItem[] = Array.isArray(data)
        ? data
        : Array.isArray(data?.content)
          ? data.content
          : [];
      setBookingList(list);
      setTotalPages(!Array.isArray(data) && data?.totalPages ? data.totalPages : 0);
      setTotalElements(!Array.isArray(data) && data?.totalElements ? data.totalElements : list.length);
    } catch (error: unknown) {
      const errMessage = error instanceof Error ? error.message : "Đã xảy ra lỗi không xác định";
      console.error("Error fetching bookings:", error);
      toast.error("Không thể tải danh sách đặt sân từ máy chủ: " + errMessage);
    } finally {
      setIsLoading(false);
    }
  }, [activeTab, page, filterStartDate, filterEndDate, filterStadiumId]);

  useEffect(() => {
    fetchBookings();
  }, [fetchBookings]);

  const searchParams = useSearchParams();

  // Auto-expand target booking row and scroll when coming from Context Card
  useEffect(() => {
    const bookingIdParam = searchParams.get('bookingId');
    if (bookingIdParam && bookingList.length > 0) {
      const bId = Number(bookingIdParam);
      setExpandedRow(bId);
      setTimeout(() => {
        const el = document.getElementById(`booking-row-${bId}`);
        if (el) {
          el.scrollIntoView({ behavior: 'smooth', block: 'center' });
          el.classList.add('ring-2', 'ring-emerald-500');
          setTimeout(() => el.classList.remove('ring-2', 'ring-emerald-500'), 3000);
        }
      }, 400);
    }
  }, [searchParams, bookingList]);

  const handleTabChange = (value: string) => {
    setActiveTab(value);
    setPage(0);
    setExpandedRow(null);
  };

  // Hàm tính toán dự phóng hoàn tiền ở Frontend (Hiển thị dự báo nhanh)
  const calculateExpectedRefund = (playTimeStr: string, price: number) => {
    const playTime = new Date(playTimeStr);
    const now = new Date();
    const diffMs = playTime.getTime() - now.getTime();
    const diffHours = diffMs / (1000 * 60 * 60);

    if (diffHours >= 24) {
      return { 
        percentage: 100, 
        amount: price, 
        label: "Hoàn 100%", 
        desc: "Hủy trước giờ chơi >= 24 giờ. Khách hàng nhận lại toàn bộ tiền sân.",
        badgeClass: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950/30 dark:text-emerald-400"
      };
    } else if (diffHours >= 12) {
      return { 
        percentage: 50, 
        amount: price * 0.5, 
        label: "Hoàn 50%", 
        desc: "Hủy trước giờ chơi từ 12 giờ đến dưới 24 giờ. Khách hàng nhận lại 50% tiền sân.",
        badgeClass: "bg-amber-100 text-amber-800 dark:bg-amber-950/30 dark:text-amber-400"
      };
    } else {
      return { 
        percentage: 0, 
        amount: 0, 
        label: "Hoàn 0%", 
        desc: "Hủy quá sát giờ chơi (< 12 giờ hoặc đã diễn ra). Khách hàng không được hoàn tiền theo điều khoản.",
        badgeClass: "bg-rose-100 text-rose-800 dark:bg-rose-950/30 dark:text-rose-400"
      };
    }
  };

  const getStatusBadge = (status: string) => {
    const config = {
      pending: { label: "Chờ xác nhận", className: "bg-yellow-100 text-yellow-700 dark:bg-yellow-950/30 dark:text-yellow-400" },
      confirmed: { label: "Đã xác nhận", className: "bg-blue-100 text-blue-700 dark:bg-blue-950/30 dark:text-blue-400" },
      rejected: { label: "Đã từ chối", className: "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-400" },
      completed: { label: "Hoàn thành", className: "bg-green-100 text-green-700 dark:bg-green-950/30 dark:text-green-400" },
      cancelled: { label: "Đã hủy", className: "bg-rose-100 text-rose-700 dark:bg-rose-950/30 dark:text-rose-400" },
    };
    const item = config[status.toLowerCase() as keyof typeof config] || { label: status, className: "bg-gray-100 text-gray-700" };
    return <Badge className={`${item.className} border-none shadow-none font-medium px-2.5 py-0.5`}>{item.label}</Badge>;
  };

  const getPaymentBadge = (status: string) => {
    const config = {
      unpaid: { label: "Chưa thanh toán", className: "bg-orange-100 text-orange-700 dark:bg-orange-950/30 dark:text-orange-400" },
      paid: { label: "Đã thanh toán", className: "bg-emerald-100 text-emerald-700 dark:bg-emerald-950/30 dark:text-emerald-400" },
      refunded: { label: "Đã hoàn tiền", className: "bg-purple-100 text-purple-700 dark:bg-purple-950/30 dark:text-purple-400" },
      awaiting_cash_payment: { label: "Chờ thu tiền mặt", className: "bg-amber-100 text-amber-700 dark:bg-amber-950/30 dark:text-amber-400" },
      deposited: { label: "Đã đặt cọc", className: "bg-indigo-100 text-indigo-700 dark:bg-indigo-950/30 dark:text-indigo-400" },
    };
    const item = config[status.toLowerCase() as keyof typeof config] || { label: status, className: "bg-gray-100 text-gray-700" };
    return <Badge className={`${item.className} border-none shadow-none font-medium px-2.5 py-0.5`}>{item.label}</Badge>;
  };

  const filterBookings = (status?: string) => {
    // Defensive: không bao giờ gọi .filter trên null/undefined/object.
    const list = Array.isArray(bookingList) ? bookingList : [];
    if (!status) return list;
    return list.filter((b) => b.status && b.status.toLowerCase() === status.toLowerCase());
  };

  // Load preview data mỗi khi mở modal hoặc thay đổi loại nguyên nhân hủy
  const fetchRefundPreview = useCallback(async (bookingId: number, type: string) => {
    setIsPreviewLoading(true);
    try {
      const data = await get<RefundResponse>(`/owner/bookings/${bookingId}/refund/preview?reasonType=${type}`);
      setPreviewData(data);
    } catch (error: unknown) {
      console.error("Error fetching refund preview:", error);
      toast.error("Không tải được thông tin xem trước hoàn tiền.");
      setPreviewData(null);
    } finally {
      setIsPreviewLoading(false);
    }
  }, []);

  const handleOpenRefundModal = (booking: BookingItem) => {
    setSelectedBooking(booking);
    setCancelReason("");
    setReasonType("CUSTOMER_REQUEST");
    setProofUrl("");
    setIsCancelModalOpen(true);
    setPreviewData(null);
    fetchRefundPreview(booking.id, "CUSTOMER_REQUEST");
  };

  // Lắng nghe thay đổi của reasonType để fetch lại preview
  useEffect(() => {
    if (selectedBooking && isCancelModalOpen) {
      fetchRefundPreview(selectedBooking.id, reasonType);
    }
  }, [reasonType, selectedBooking, isCancelModalOpen, fetchRefundPreview]);

  // Hàm gửi Request thực tế lên Backend để thực hiện hoàn tiền
  const handleConfirmRefund = async () => {
    if (!selectedBooking) return;

    if (reasonType === "CUSTOMER_REQUEST" && !cancelReason.trim()) {
      toast.error("Vui lòng nhập lý do hủy đặt sân!");
      return;
    }

    if (reasonType === "OWNER_FAULT" && !proofUrl.trim()) {
      toast.error("Vui lòng cung cấp bằng chứng (link ảnh/mô tả) cho sự cố từ phía sân!");
      return;
    }

    try {
      setIsSubmitting(true);
      toast.loading("Đang kết nối backend xử lý hoàn tiền...", { id: "refund-process" });

      // Gọi API thật
      const response = await post<RefundResponse>(`/owner/bookings/${selectedBooking.id}/refund`, {
        reason: reasonType === "OWNER_FAULT" ? proofUrl.trim() : cancelReason.trim(),
        reasonType,
        proofUrl: reasonType === "OWNER_FAULT" ? proofUrl.trim() : null
      });

      // Thành công: Cập nhật state ở local
      setBookingList(prev => prev.map(b => {
        if (b.id === selectedBooking.id) {
          return {
            ...b,
            status: "cancelled",
            paymentStatus: "refunded"
          };
        }
        return b;
      }));

      // Đưa dữ liệu kết quả từ server trả về vào State thành công để show Popup Premium
      setSuccessData(response);
      setIsCancelModalOpen(false);
      setIsSuccessModalOpen(true);
      toast.success("Đã hoàn tiền và giải phóng sân thành công!", { id: "refund-process" });
      fetchSummary();
    } catch (error: unknown) {
      const errMessage = error instanceof Error ? error.message : "Không thể thực hiện hoàn tiền. Vui lòng kiểm tra lại quyền sở hữu hoặc trạng thái đơn hàng!";
      console.error(error);
      toast.error(errMessage, { id: "refund-process" });
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleConfirmCancelOnly = async () => {
    if (!cancelOnlyBooking) return;
    try {
      setIsCancelOnlySubmitting(true);
      await cancelBooking(cancelOnlyBooking.id, cancelOnlyReason.trim() || undefined);
      setBookingList(prev => prev.map(b =>
        b.id === cancelOnlyBooking.id ? { ...b, status: "cancelled" } : b
      ));
      toast.success("Đã hủy đơn thành công");
      fetchSummary();
      setCancelOnlyBooking(null);
      setCancelOnlyReason("");
    } catch (error: unknown) {
      const errMessage = error instanceof Error ? error.message : "Không thể hủy đơn, vui lòng thử lại";
      toast.error(errMessage);
    } finally {
      setIsCancelOnlySubmitting(false);
    }
  };

  const handleConfirmCashReceived = async () => {
    if (!confirmCashBooking) return;
    try {
      setIsConfirmCashSubmitting(true);
      await put(`/owner/bookings/${confirmCashBooking.id}/confirm-cash-payment`, {});
      setBookingList(prev => prev.map(b =>
        b.id === confirmCashBooking.id ? { ...b, paymentStatus: "paid" } : b
      ));
      toast.success("Đã xác nhận thu tiền mặt thành công");
      fetchSummary();
      setReceiptBooking({ ...confirmCashBooking, paymentStatus: "paid" });
      setConfirmCashBooking(null);
    } catch (error: unknown) {
      const errMessage = error instanceof Error ? error.message : "Không thể xác nhận thu tiền, vui lòng thử lại";
      toast.error(errMessage);
    } finally {
      setIsConfirmCashSubmitting(false);
    }
  };

  const handleConfirmRemainingReceived = async () => {
    if (!confirmRemainingBooking) return;
    try {
      setIsConfirmRemainingSubmitting(true);
      await put(`/owner/bookings/${confirmRemainingBooking.id}/confirm-remaining-payment`, {});
      setBookingList(prev => prev.map(b =>
        b.id === confirmRemainingBooking.id ? { ...b, paymentStatus: "paid" } : b
      ));
      toast.success("Đã xác nhận thu nốt phần còn lại thành công");
      fetchSummary();
      const remaining = confirmRemainingBooking.amount - (confirmRemainingBooking.paidAmount || 0);
      setReceiptBooking({ ...confirmRemainingBooking, paymentStatus: "paid", amount: remaining });
      setConfirmRemainingBooking(null);
    } catch (error: unknown) {
      const errMessage = error instanceof Error ? error.message : "Không thể xác nhận thu tiền, vui lòng thử lại";
      toast.error(errMessage);
    } finally {
      setIsConfirmRemainingSubmitting(false);
    }
  };

  const BookingRow = ({ booking }: { booking: BookingItem }) => {
    const isExpanded = expandedRow === booking.id;
    // Backend (RefundServiceImpl.validateOwnershipAndStatus) đã cho phép hoàn tiền cho cả PAID
    // lẫn DEPOSITED từ trước — mở thêm điều kiện ở đây để Owner có nút hủy/hoàn tiền cho đơn cọc
    // (vd lỗi do sân → hoàn 100% gồm phí dịch vụ).
    const canRefund = !booking.isWalkIn && booking.status.toLowerCase() === "confirmed"
      && (booking.paymentStatus.toLowerCase() === "paid" || booking.paymentStatus.toLowerCase() === "deposited");
    // KHÔNG gate theo status CONFIRMED/COMPLETED cụ thể — BookingCompletionScheduler có thể chuyển
    // CONFIRMED→COMPLETED chỉ dựa vào giờ chơi, không xét paymentStatus, nên 1 đơn cash có thể đã
    // COMPLETED mà vẫn chưa thu tiền. Nhưng PHẢI loại trừ CANCELLED — đơn hủy không còn gì để thu,
    // backend cũng chặn (BadRequestException) nên nút hiện ra chỉ để báo lỗi vô nghĩa.
    const canConfirmCash = booking.paymentStatus.toLowerCase() === "awaiting_cash_payment"
      && booking.status.toLowerCase() !== "cancelled";
    // Đơn đặt cọc — khách trả nốt phần còn lại tại sân, tương tự canConfirmCash.
    const canConfirmRemaining = booking.paymentStatus.toLowerCase() === "deposited"
      && booking.status.toLowerCase() !== "cancelled";
    const canCancelUnpaid = !booking.isWalkIn && booking.status.toLowerCase() === "confirmed"
      && booking.paymentStatus.toLowerCase() === "awaiting_cash_payment";

    return (
      <>
        <tr id={`booking-row-${booking.id}`} className="border-b hover:bg-muted/40 transition-all duration-300">
          <td className="p-4 align-middle">
            <Checkbox />
          </td>
          <td className="p-4 align-middle font-mono text-sm font-semibold text-primary">{booking.displayId}</td>
          <td className="p-4 align-middle font-medium">{booking.customer.name}</td>
          <td className="p-4 align-middle text-muted-foreground">
            <div>{booking.venue}</div>
            {booking.complexName && (
              <div className="text-xs text-muted-foreground/70">{booking.complexName}</div>
            )}
          </td>
          <td className="p-4 align-middle text-sm">{booking.date}</td>
          <td className="p-4 align-middle text-sm font-medium">{booking.time}</td>
          <td className="p-4 align-middle text-right font-semibold text-slate-900 dark:text-slate-100">
            {booking.amount.toLocaleString('vi-VN')}đ
          </td>
          <td className="p-4 align-middle text-right text-rose-600 dark:text-rose-400 font-medium">
            {booking.refundAmount > 0 ? `-${booking.refundAmount.toLocaleString('vi-VN')}đ` : "0đ"}
          </td>
          <td className="p-4 align-middle text-right text-emerald-600 dark:text-emerald-400 font-bold">
            {(() => {
              const status = booking.paymentStatus.toLowerCase();
              const isPaidType = status === "paid" || status === "refunded";
              // Đơn cọc: phí dịch vụ đã bị trừ ngay lúc đặt cọc (không đợi tới lúc thu nốt) —
              // hiện paidAmount - serviceFee để khớp với Ví, tránh hiện "0đ" gây hiểu lầm.
              const netVal = isPaidType
                ? (booking.amount - (booking.serviceFee || 0) - booking.refundAmount)
                : (status === "deposited" ? ((booking.paidAmount || 0) - (booking.serviceFee || 0)) : 0);
              return `${netVal.toLocaleString('vi-VN')}đ`;
            })()}
          </td>
          <td className="p-4 align-middle">
            <div className="flex flex-col gap-1 items-start">
              {getStatusBadge(booking.status)}
              {getPaymentBadge(booking.paymentStatus)}
            </div>
          </td>
          <td className="p-4 align-middle">
            <div className="flex flex-wrap gap-2 items-center justify-end max-w-[260px] ml-auto">
              {canRefund && (
                <Button
                  size="sm"
                  variant="destructive"
                  className="bg-rose-600 hover:bg-rose-700 text-white font-medium shadow-sm transition-all duration-200"
                  onClick={() => handleOpenRefundModal(booking)}
                >
                  <RotateCcw className="h-3.5 w-3.5 mr-1.5 animate-spin-reverse" />
                  Hủy & Hoàn Tiền
                </Button>
              )}
              {canConfirmCash && (
                <Button
                  size="sm"
                  className="bg-emerald-600 hover:bg-emerald-700 text-white font-medium shadow-sm transition-all duration-200"
                  onClick={() => setConfirmCashBooking(booking)}
                >
                  <CheckCircle className="h-3.5 w-3.5 mr-1.5" />
                  Xác nhận đã thu tiền
                </Button>
              )}
              {canConfirmRemaining && (
                <Button
                  size="sm"
                  className="bg-indigo-600 hover:bg-indigo-700 text-white font-medium shadow-sm transition-all duration-200"
                  onClick={() => setConfirmRemainingBooking(booking)}
                >
                  <CheckCircle className="h-3.5 w-3.5 mr-1.5" />
                  Xác nhận thu nốt
                </Button>
              )}
              {canCancelUnpaid && (
                <Button
                  size="sm"
                  variant="outline"
                  className="border-amber-300 text-amber-700 hover:bg-amber-50 dark:border-amber-800 dark:text-amber-400"
                  onClick={() => setCancelOnlyBooking(booking)}
                >
                  <XCircle className="h-3.5 w-3.5 mr-1.5" />
                  Hủy đơn
                </Button>
              )}
              <Button
                size="sm"
                variant="outline"
                className="border-amber-300 text-amber-700 hover:bg-amber-50 dark:border-amber-800 dark:text-amber-400"
                disabled={!booking.customer.userId}
                onClick={() => setReportBooking(booking)}
              >
                <AlertTriangle className="h-3.5 w-3.5 mr-1.5" />
                Báo cáo
              </Button>
              <Button
                size="sm"
                variant="ghost"
                onClick={() =>
                  setExpandedRow(isExpanded ? null : booking.id)
                }
                className="hover:bg-muted"
              >
                {isExpanded ? (
                  <ChevronUp className="h-4 w-4" />
                ) : (
                  <ChevronDown className="h-4 w-4" />
                )}
              </Button>
            </div>
          </td>
        </tr>

        {isExpanded && (
          <tr className="bg-muted/30">
            <td colSpan={11} className="p-6">
              <div className="grid grid-cols-1 md:grid-cols-3 gap-6 rounded-lg border bg-card p-4 shadow-sm animate-in fade-in slide-in-from-top-1 duration-200">
                <div className="space-y-3">
                  <h4 className="font-semibold text-sm flex items-center gap-1.5 text-primary border-b pb-1.5">
                    <User className="h-4 w-4 text-blue-500" />
                    Thông tin khách hàng
                  </h4>
                  <div className="space-y-1.5 text-sm">
                    <div className="flex justify-between">
                      <span className="text-muted-foreground">Họ tên:</span>
                      <span className="font-medium">{booking.customer.name}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-muted-foreground">Số điện thoại:</span>
                      <span className="font-mono">{booking.customer.phone}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-muted-foreground">Email:</span>
                      <span className="text-blue-600 dark:text-blue-400 font-mono text-xs">{booking.customer.email}</span>
                    </div>
                  </div>
                </div>

                <div className="space-y-3">
                  <h4 className="font-semibold text-sm flex items-center gap-1.5 text-primary border-b pb-1.5">
                    <Calendar className="h-4 w-4 text-emerald-500" />
                    Chi tiết sân đặt
                  </h4>
                  <div className="space-y-1.5 text-sm">
                    <div className="flex justify-between">
                      <span className="text-muted-foreground">Sân chơi:</span>
                      <span className="font-medium">{booking.venue}</span>
                    </div>
                    {booking.complexName && (
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">Khu phức hợp:</span>
                        <span className="font-medium">{booking.complexName}</span>
                      </div>
                    )}
                    <div className="flex justify-between">
                      <span className="text-muted-foreground">Khung giờ:</span>
                      <span className="font-medium">{booking.time}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-muted-foreground">Thời gian gốc:</span>
                      <span className="font-mono text-xs">{new Date(booking.playTimeRaw).toLocaleString('vi-VN')}</span>
                    </div>
                  </div>
                </div>

                <div className="space-y-3">
                  <h4 className="font-semibold text-sm flex items-center gap-1.5 text-primary border-b pb-1.5">
                    <Info className="h-4 w-4 text-violet-500" />
                    Chính sách hoàn tiền (Ước tính)
                  </h4>
                  {booking.status.toLowerCase() === "cancelled" ? (
                    <div className="text-sm text-slate-500 italic bg-slate-100 dark:bg-slate-800 p-2.5 rounded border">
                      Đơn hàng đã được xử lý hủy. Khung giờ chơi đã được giải phóng trở lại trạng thái Sẵn Sàng (AVAILABLE).
                    </div>
                  ) : (
                    <div className="space-y-2">
                      {(() => {
                        const estimate = calculateExpectedRefund(booking.playTimeRaw, booking.amount);
                        return (
                          <>
                            <div className="flex justify-between items-center">
                              <span className="text-xs text-muted-foreground">Khả năng hoàn:</span>
                              <Badge className={`${estimate.badgeClass} border-none`}>{estimate.label}</Badge>
                            </div>
                            <div className="flex justify-between items-center">
                              <span className="text-xs text-muted-foreground">Số tiền hoàn trả:</span>
                              <span className="font-bold text-slate-900 dark:text-slate-100">{estimate.amount.toLocaleString('vi-VN')}đ</span>
                            </div>
                            <p className="text-[11px] text-muted-foreground leading-relaxed bg-slate-50 dark:bg-slate-900 p-1.5 rounded border border-slate-100 dark:border-slate-800">{estimate.desc}</p>
                            <p className="text-[10px] text-amber-600 dark:text-amber-400 italic mt-1 leading-normal">* Số tiền hoàn ước tính theo giờ máy khách, có thể thay đổi tùy thuộc vào giờ máy chủ khi xử lý giao dịch thực tế.</p>
                          </>
                        );
                      })()}
                    </div>
                  )}
                </div>

                <BookingExceptionHistory bookingId={booking.id} />

                <BookingWalletHistory bookingId={booking.id} />

                {booking.notes && (
                  <div className="col-span-full bg-slate-50 dark:bg-slate-900/60 p-2.5 rounded border text-xs text-slate-600 dark:text-slate-300">
                    <span className="font-semibold text-primary block mb-0.5">Ghi chú:</span>
                    <p className="italic">{booking.notes}</p>
                  </div>
                )}
              </div>
            </td>
          </tr>
        )}
      </>
    );
  };

  // Tổng lấy từ backend (/owner/bookings/summary) — SUM trên toàn bộ booking của Owner, không
  // phụ thuộc bookingList (chỉ đang giữ 1 trang) để tránh bug tổng giảm dần khi tạo đơn mới.
  const totalGrossAmount = summary?.grossAmount ?? 0;
  const totalRefundedAmount = summary?.refundedAmount ?? 0;
  const totalServiceFee = summary?.serviceFee ?? 0;
  const totalNetAmount = summary?.netAmount ?? 0;

  return (
    <>
      <div className="container mx-auto px-4 py-8 max-w-7xl">
        <div className="flex flex-col md:flex-row md:items-center md:justify-between mb-8 gap-4">
          <div>
            <h1 className="text-3xl font-extrabold tracking-tight text-slate-900 dark:text-white">Quản lý Đơn đặt sân</h1>
            <p className="text-muted-foreground mt-1 text-sm">Xem danh sách, xác nhận đặt sân hoặc hủy hoàn tiền nhanh chóng.</p>
          </div>
          
          {/* Hủy nhanh đã loại bỏ trong Production */}
        </div>

        {/* Filters */}
        <Card className="mb-6 shadow-sm border border-slate-200/80 dark:border-slate-800">
          <CardContent className="p-5">
            <div className="flex gap-4 flex-wrap">
              <div className="flex-1 min-w-[200px]">
                <label className="block text-xs font-semibold text-muted-foreground mb-1.5 uppercase tracking-wide">Từ ngày</label>
                <input
                  type="date"
                  className="w-full border rounded-lg px-3 py-2 bg-background focus:ring-1 focus:ring-primary focus:outline-none"
                  value={filterStartDate}
                  onChange={(e) => setFilterStartDate(e.target.value)}
                />
              </div>
              <div className="flex-1 min-w-[200px]">
                <label className="block text-xs font-semibold text-muted-foreground mb-1.5 uppercase tracking-wide">Đến ngày</label>
                <input
                  type="date"
                  className="w-full border rounded-lg px-3 py-2 bg-background focus:ring-1 focus:ring-primary focus:outline-none"
                  value={filterEndDate}
                  onChange={(e) => setFilterEndDate(e.target.value)}
                />
              </div>
              <div className="w-full md:w-56">
                <label className="block text-xs font-semibold text-muted-foreground mb-1.5 uppercase tracking-wide">Sân thể thao</label>
                <Select value={filterStadiumId} onValueChange={setFilterStadiumId}>
                  <SelectTrigger className="w-full h-[42px]">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="all">Tất cả các sân</SelectItem>
                    {ownerStadiums.map((stadium) => (
                      <SelectItem key={stadium.stadiumId} value={stadium.stadiumId.toString()}>
                        {stadium.stadiumName}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex items-end gap-2">
                <Button 
                  variant="outline" 
                  className="h-[42px] px-6 font-semibold shadow-sm hover:bg-muted"
                  onClick={() => {
                    fetchBookings();
                    fetchSummary();
                  }}
                >
                  Lọc kết quả
                </Button>
                {(filterStartDate || filterEndDate || filterStadiumId !== "all") && (
                  <Button 
                    variant="ghost" 
                    className="h-[42px] px-4 text-rose-500 hover:text-rose-600"
                    onClick={() => {
                      setFilterStartDate("");
                      setFilterEndDate("");
                      setFilterStadiumId("all");
                    }}
                  >
                    Xóa lọc
                  </Button>
                )}
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Doanh thu Summary Widget */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-6">
          <Card className="bg-slate-50/50 dark:bg-slate-900/40 border border-slate-200/80 dark:border-slate-800 shadow-sm">
            <CardContent className="p-5 flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">Doanh thu gộp (Gross)</p>
                <h3 className="text-2xl font-bold text-slate-900 dark:text-white mt-1">
                  {totalGrossAmount.toLocaleString('vi-VN')}đ
                </h3>
                <p className="text-[11px] text-muted-foreground mt-1">Tổng tiền của các đơn hàng trong danh sách</p>
              </div>
              <div className="bg-blue-100 text-blue-600 p-3 rounded-lg dark:bg-blue-950/40 dark:text-blue-400">
                <DollarSign className="h-6 w-6" />
              </div>
            </CardContent>
          </Card>

          <Card className="bg-slate-50/50 dark:bg-slate-900/40 border border-slate-200/80 dark:border-slate-800 shadow-sm">
            <CardContent className="p-5 flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">Tiền đã hoàn trả (Refunded)</p>
                <h3 className="text-2xl font-bold text-rose-600 dark:text-rose-400 mt-1">
                  {totalRefundedAmount.toLocaleString('vi-VN')}đ
                </h3>
                <p className="text-[11px] text-muted-foreground mt-1">Số tiền đã trả lại cho các đơn hủy hoàn tiền</p>
              </div>
              <div className="bg-rose-100 text-rose-600 p-3 rounded-lg dark:bg-rose-950/40 dark:text-rose-400">
                <RotateCcw className="h-6 w-6" />
              </div>
            </CardContent>
          </Card>

          <Card className="bg-emerald-50/30 dark:bg-emerald-950/10 border border-emerald-100/80 dark:border-emerald-950/40 shadow-sm">
            <CardContent className="p-5 flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold text-emerald-800 dark:text-emerald-400 uppercase tracking-wider">Thực thu sau cùng (Net)</p>
                <h3 className="text-2xl font-bold text-emerald-600 dark:text-emerald-400 mt-1">
                  {totalNetAmount.toLocaleString('vi-VN')}đ
                </h3>
                <p className="text-[11px] text-emerald-800 dark:text-emerald-400 mt-1">Số tiền thực tế chủ sân thu về sau khi trừ hoàn tiền và phí dịch vụ</p>
                <p className="text-[10px] text-slate-500 dark:text-slate-400 mt-1 font-mono">
                  {totalGrossAmount.toLocaleString('vi-VN')}đ (Gross) - {totalRefundedAmount.toLocaleString('vi-VN')}đ (Refund) - {totalServiceFee.toLocaleString('vi-VN')}đ (Fee)
                </p>
              </div>
              <div className="bg-emerald-100 text-emerald-600 p-3 rounded-lg dark:bg-emerald-950/30 dark:text-emerald-400">
                <CheckCircle className="h-6 w-6" />
              </div>
            </CardContent>
          </Card>
        </div>

        {/* Tabs */}
        <Tabs value={activeTab} onValueChange={handleTabChange} className="space-y-6">
          <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4 border-b pb-2">
            <TabsList className="bg-muted/70 p-1 rounded-lg w-full lg:w-auto overflow-x-auto flex-nowrap whitespace-nowrap justify-start">
              <TabsTrigger value="all" className="font-semibold text-sm">
                Tất cả
              </TabsTrigger>
              <TabsTrigger value="pending" className="font-semibold text-sm">
                Chờ duyệt
              </TabsTrigger>
              <TabsTrigger value="confirmed" className="font-semibold text-sm">
                Đã xác nhận
              </TabsTrigger>
              <TabsTrigger value="completed" className="font-semibold text-sm">
                Hoàn thành
              </TabsTrigger>
              <TabsTrigger value="cancelled" className="font-semibold text-sm">
                Đã hủy
              </TabsTrigger>
            </TabsList>

            <div className="flex gap-2 self-end">
              <Button variant="outline" size="sm" className="font-medium shadow-sm">
                Xuất danh sách Excel
              </Button>
            </div>
          </div>

          <Card className="shadow-md border border-slate-200/80 dark:border-slate-800 overflow-hidden">
            <CardContent className="p-0">
              <div className="overflow-x-auto">
                <table className="w-full border-collapse">
                  <thead>
                    <tr className="bg-muted/50 border-b text-muted-foreground text-xs uppercase font-bold tracking-wider">
                      <th className="p-4 text-left w-12">
                        <Checkbox />
                      </th>
                      <th className="p-4 text-left">Mã đơn</th>
                      <th className="p-4 text-left">Khách hàng</th>
                      <th className="p-4 text-left">Sân chơi</th>
                      <th className="p-4 text-left">Ngày</th>
                      <th className="p-4 text-left">Khung giờ</th>
                      <th className="p-4 text-right">Tổng tiền</th>
                      <th className="p-4 text-right">Tiền đã hoàn</th>
                      <th className="p-4 text-right">Thực thu</th>
                      <th className="p-4 text-left">Trạng thái</th>
                      <th className="p-4 text-right">Hành động</th>
                    </tr>
                  </thead>
                  <tbody>
                    <TabsContent value="all" className="m-0" asChild>
                      <>
                        {isLoading ? (
                          <tr>
                            <td colSpan={11} className="text-center p-12 text-muted-foreground">
                              <span className="animate-spin inline-block rounded-full h-6 w-6 border-2 border-primary border-t-transparent mr-2 align-middle"></span> 
                              Đang tải danh sách đặt sân từ máy chủ...
                            </td>
                          </tr>
                        ) : bookingList.length === 0 ? (
                          <tr><td colSpan={11} className="text-center p-8 text-muted-foreground">Không có đơn đặt sân nào.</td></tr>
                        ) : (
                          bookingList.map((booking) => (
                            <BookingRow key={booking.id} booking={booking} />
                          ))
                        )}
                      </>
                    </TabsContent>
                    <TabsContent value="pending" className="m-0" asChild>
                      <>
                        {isLoading ? (
                          <tr>
                            <td colSpan={11} className="text-center p-12 text-muted-foreground">
                              <span className="animate-spin inline-block rounded-full h-6 w-6 border-2 border-primary border-t-transparent mr-2 align-middle"></span> 
                              Đang tải...
                            </td>
                          </tr>
                        ) : filterBookings("pending").length === 0 ? (
                          <tr><td colSpan={11} className="text-center p-8 text-muted-foreground">Không có đơn đặt sân chờ duyệt.</td></tr>
                        ) : (
                          filterBookings("pending").map((booking) => (
                            <BookingRow key={booking.id} booking={booking} />
                          ))
                        )}
                      </>
                    </TabsContent>
                    <TabsContent value="confirmed" className="m-0" asChild>
                      <>
                        {isLoading ? (
                          <tr>
                            <td colSpan={11} className="text-center p-12 text-muted-foreground">
                              <span className="animate-spin inline-block rounded-full h-6 w-6 border-2 border-primary border-t-transparent mr-2 align-middle"></span> 
                              Đang tải...
                            </td>
                          </tr>
                        ) : filterBookings("confirmed").length === 0 ? (
                          <tr><td colSpan={11} className="text-center p-8 text-muted-foreground">Không có đơn đã xác nhận.</td></tr>
                        ) : (
                          filterBookings("confirmed").map((booking) => (
                            <BookingRow key={booking.id} booking={booking} />
                          ))
                        )}
                      </>
                    </TabsContent>
                    <TabsContent value="completed" className="m-0" asChild>
                      <>
                        {isLoading ? (
                          <tr>
                            <td colSpan={11} className="text-center p-12 text-muted-foreground">
                              <span className="animate-spin inline-block rounded-full h-6 w-6 border-2 border-primary border-t-transparent mr-2 align-middle"></span> 
                              Đang tải...
                            </td>
                          </tr>
                        ) : filterBookings("completed").length === 0 ? (
                          <tr><td colSpan={11} className="text-center p-8 text-muted-foreground">Không có đơn hoàn thành.</td></tr>
                        ) : (
                          filterBookings("completed").map((booking) => (
                            <BookingRow key={booking.id} booking={booking} />
                          ))
                        )}
                      </>
                    </TabsContent>
                    <TabsContent value="cancelled" className="m-0" asChild>
                      <>
                        {isLoading ? (
                          <tr>
                            <td colSpan={11} className="text-center p-12 text-muted-foreground">
                              <span className="animate-spin inline-block rounded-full h-6 w-6 border-2 border-primary border-t-transparent mr-2 align-middle"></span> 
                              Đang tải...
                            </td>
                          </tr>
                        ) : filterBookings("cancelled").length === 0 ? (
                          <tr><td colSpan={11} className="text-center p-8 text-muted-foreground">Không có đơn đã hủy.</td></tr>
                        ) : (
                          filterBookings("cancelled").map((booking) => (
                            <BookingRow key={booking.id} booking={booking} />
                          ))
                        )}
                      </>
                    </TabsContent>
                  </tbody>
                </table>
              </div>
            </CardContent>
          </Card>
          {totalPages > 1 && (
            <div className="flex items-center justify-center gap-3">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setPage(current => Math.max(0, current - 1))}
                disabled={page === 0 || isLoading}
              >
                <ChevronLeft className="mr-1 h-4 w-4" /> Trang trước
              </Button>
              <span className="text-sm font-medium text-muted-foreground">
                Trang {page + 1} / {totalPages} · {totalElements} đơn
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setPage(current => Math.min(totalPages - 1, current + 1))}
                disabled={page >= totalPages - 1 || isLoading}
              >
                Trang sau <ChevronRight className="ml-1 h-4 w-4" />
              </Button>
            </div>
          )}
        </Tabs>
      </div>

      {/* ──────────────────────────────────────────────────────────────────────────
          POPUP 1: ĐỐI THOẠI XÁC NHẬN HỦY VÀ ƯỚC TÍNH HOÀN TIỀN (CONFIRM REFUND DIALOG)
          ────────────────────────────────────────────────────────────────────────── */}
      <Dialog open={isCancelModalOpen} onOpenChange={setIsCancelModalOpen}>
        <DialogContent className="max-w-md max-h-[90vh] overflow-y-auto border dark:border-slate-800 shadow-2xl rounded-xl">
          <DialogHeader>
            <DialogTitle className="text-xl font-bold flex items-center gap-2 text-rose-600">
              <RotateCcw className="h-5 w-5" />
              Yêu Cầu Hủy & Hoàn Tiền
            </DialogTitle>
            <DialogDescription className="text-slate-500 dark:text-slate-400 text-xs">
              Vui lòng xem xét các điều khoản hoàn tiền và nhập lý do trước khi thực hiện. Hành động này không thể hoàn tác.
            </DialogDescription>
          </DialogHeader>

          {selectedBooking && (
            <div className="space-y-4 py-3">
              {/* Thông tin đơn */}
              <div className="bg-slate-50 dark:bg-slate-900/60 p-4 rounded-xl border space-y-2 text-sm">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Mã đơn:</span>
                  <span className="font-mono font-bold text-primary">{selectedBooking.displayId}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Khách hàng:</span>
                  <span className="font-medium text-slate-800 dark:text-slate-200">{selectedBooking.customer.name}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Thời gian chơi:</span>
                  <span className="font-medium text-slate-800 dark:text-slate-200">{new Date(selectedBooking.playTimeRaw).toLocaleString('vi-VN')}</span>
                </div>
                <div className="flex justify-between border-t pt-2 mt-2">
                  <span className="text-muted-foreground font-semibold">Tiền thanh toán:</span>
                  <span className="font-bold text-primary">{selectedBooking.amount.toLocaleString('vi-VN')}đ</span>
                </div>
              </div>

              {/* Tính toán hoàn tiền thực tế (Truy vấn động từ Máy Chủ) */}
              <div className="border border-violet-100 dark:border-violet-950/50 bg-violet-50/20 dark:bg-violet-950/10 p-4 rounded-xl space-y-2.5">
                <h5 className="font-semibold text-xs text-violet-800 dark:text-violet-400 uppercase tracking-wider flex items-center gap-1.5">
                  <Clock className="h-3.5 w-3.5" />
                  Chính sách áp dụng tự động (Giờ Máy Chủ)
                </h5>
                
                {isPreviewLoading ? (
                  <div className="text-center py-4 text-xs text-muted-foreground flex items-center justify-center gap-2">
                    <span className="animate-spin rounded-full h-4 w-4 border-2 border-indigo-600 border-t-transparent mr-2"></span>
                    Đang tính toán tiền hoàn chính xác từ máy chủ...
                  </div>
                ) : previewData ? (
                  <>
                    <div className="flex justify-between items-center text-sm">
                      <span className="text-slate-600 dark:text-slate-400">Tỷ lệ hoàn trả:</span>
                      <Badge className={`${
                        previewData.refundPercentage === 100 
                          ? "bg-emerald-100 text-emerald-800 dark:bg-emerald-950/30 dark:text-emerald-400" 
                          : previewData.refundPercentage === 50 
                          ? "bg-amber-100 text-amber-800 dark:bg-amber-950/30 dark:text-amber-400" 
                          : "bg-rose-100 text-rose-800 dark:bg-rose-950/30 dark:text-rose-400"
                      } border-none font-bold px-2 py-0.5`}>
                        Hoàn {previewData.refundPercentage}%
                      </Badge>
                    </div>
                    {(previewData.serviceFee ?? 0) > 0 && reasonType === "CUSTOMER_REQUEST" && (
                      <div className="flex justify-between items-center text-xs text-rose-600 font-medium">
                        <span>Phí dịch vụ (Không hoàn lại):</span>
                        <span>-{(previewData.serviceFee ?? 0).toLocaleString('vi-VN')}đ</span>
                      </div>
                    )}
                    <div className="flex justify-between items-center text-sm border-t pt-1.5 mt-1.5">
                      <span className="text-slate-600 dark:text-slate-400 font-semibold">Tiền trả khách:</span>
                      <span className="font-extrabold text-lg text-emerald-600 dark:text-emerald-400">{previewData.refundAmount.toLocaleString('vi-VN')}đ</span>
                    </div>
                    <p className="text-[11px] leading-relaxed text-muted-foreground border-t pt-2 mt-1.5 italic">
                      {previewData.cancellationPolicyDescription || (
                        previewData.refundPercentage === 100 
                          ? "Hủy trước giờ chơi >= 24 giờ. Khách hàng nhận lại toàn bộ tiền sân." 
                          : previewData.refundPercentage === 50 
                          ? "Hủy trước giờ chơi từ 12 giờ đến dưới 24 giờ. Khách hàng nhận lại 50% tiền sân." 
                          : "Hủy quá sát giờ chơi (< 12 giờ). Khách hàng không được hoàn tiền theo điều khoản."
                      )}
                    </p>
                  </>
                ) : (
                  <div className="text-center py-4 text-xs text-rose-600 flex items-center justify-center gap-1.5">
                    <AlertCircle className="h-4 w-4" />
                    Không thể kết nối máy chủ để xem trước hoàn tiền.
                  </div>
                )}
              </div>

              {/* Chọn người chịu trách nhiệm */}
              <div className="space-y-1.5 mt-2">
                <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300">Nguyên nhân hủy <span className="text-rose-500">*</span></label>
                <Select value={reasonType} onValueChange={setReasonType}>
                  <SelectTrigger className="w-full">
                    <SelectValue placeholder="Chọn nguyên nhân" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="CUSTOMER_REQUEST">Khách hàng yêu cầu hủy</SelectItem>
                    <SelectItem value="OWNER_FAULT">Sự cố từ phía sân (Mưa ngập, hỏng hóc...)</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              {reasonType === "OWNER_FAULT" && (
                <div className="space-y-1.5 mt-2 p-3 bg-rose-50 dark:bg-rose-950/20 border border-rose-200 dark:border-rose-900 rounded-md">
                  <label className="block text-xs font-semibold text-rose-700 dark:text-rose-400">Bằng chứng sự cố <span className="text-rose-500">*</span></label>
                  <p className="text-[11px] text-rose-600 mb-2">Bằng chứng này sẽ được lưu lại để Admin đối soát. Nếu khai báo sai, bạn có thể bị phạt.</p>
                  <Textarea 
                    value={proofUrl}
                    onChange={(e) => setProofUrl(e.target.value)}
                    placeholder="Mô tả chi tiết sự cố hoặc dán link ảnh bằng chứng vào đây..."
                    className="min-h-[60px] focus:ring-1 focus:ring-rose-500 focus:outline-none"
                  />
                </div>
              )}

              {/* Lý do hủy — chỉ hiện khi khách yêu cầu hủy để tránh trùng lặp với bằng chứng sự cố */}
              {reasonType === "CUSTOMER_REQUEST" && (
                <div className="space-y-1.5 mt-2">
                  <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300">Ghi chú hủy sân</label>
                  <Textarea 
                    value={cancelReason}
                    onChange={(e) => setCancelReason(e.target.value)}
                    placeholder="Ghi chú thêm (không bắt buộc)..."
                    className="min-h-[60px] focus:ring-1 focus:ring-primary focus:outline-none"
                  />
                </div>
              )}
            </div>
          )}

          <DialogFooter className="gap-2">
            <Button 
              variant="outline" 
              onClick={() => setIsCancelModalOpen(false)}
              disabled={isSubmitting}
            >
              Hủy bỏ
            </Button>
            <Button 
              className="bg-rose-600 hover:bg-rose-700 text-white font-medium shadow-sm transition-all duration-200"
              onClick={handleConfirmRefund}
              disabled={isSubmitting || isPreviewLoading || !previewData}
            >
              {isSubmitting ? "Đang xử lý..." : "Xác nhận hoàn tiền"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ──────────────────────────────────────────────────────────────────────────
          POPUP 2: THÔNG BÁO HOÀN TIỀN THÀNH CÔNG RỰC RỠ (SUCCESS DETAIL DIALOG)
          ────────────────────────────────────────────────────────────────────────── */}
      <Dialog open={isSuccessModalOpen} onOpenChange={setIsSuccessModalOpen}>
        <DialogContent className="max-w-md border border-emerald-100 dark:border-emerald-950/40 bg-card shadow-2xl rounded-xl overflow-hidden p-0 animate-in zoom-in-95 duration-200">
          
          {/* Header rực rỡ tích xanh */}
          <div className="bg-gradient-to-r from-emerald-500 to-teal-600 p-6 text-center text-white space-y-2">
            <div className="mx-auto w-16 h-16 bg-white/20 rounded-full flex items-center justify-center border border-white/30 animate-pulse">
              <CheckCircle className="h-10 w-10 text-white stroke-[2.5]" />
            </div>
            <h3 className="text-xl font-bold tracking-tight">Hoàn Tiền Thành Công!</h3>
            <p className="text-xs text-white/85">Giao dịch đã được hệ thống ghi nhận và đồng bộ thời gian chơi.</p>
          </div>

          {successData && (
            <div className="p-6 space-y-5">
              {/* Summary Info */}
              <div className="text-center space-y-1">
                <span className="text-xs text-muted-foreground uppercase font-bold tracking-wider">Số tiền đã hoàn trả</span>
                <h4 className="text-3xl font-black text-emerald-600 tracking-tight">
                  {successData.refundAmount.toLocaleString('vi-VN')}đ
                </h4>
                <div className="inline-flex items-center gap-1.5 mt-1.5">
                  <Badge className="bg-emerald-100 text-emerald-800 border-none font-semibold px-2 py-0.5">
                    Đã hoàn {successData.refundPercentage}%
                  </Badge>
                </div>
              </div>

              {/* Phân rã dữ liệu từ API */}
              <div className="space-y-3">
                <h5 className="font-semibold text-xs text-muted-foreground uppercase tracking-wide border-b pb-1">Chi tiết giao dịch hoàn trả</h5>
                <div className="space-y-2 text-sm">
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Mã đơn hàng:</span>
                    <span className="font-mono font-bold text-slate-800 dark:text-slate-200">BK{String(successData.bookingId).padStart(6, '0')}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Khách hàng:</span>
                    <span className="font-medium text-slate-800 dark:text-slate-200">{successData.customerName}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Sân chơi:</span>
                    <span className="font-medium text-slate-800 dark:text-slate-200">{successData.stadiumName}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Tiền thanh toán gốc:</span>
                    <span className="font-medium text-slate-800 dark:text-slate-200">{successData.originalPrice.toLocaleString('vi-VN')}đ</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Trạng thái mới đơn:</span>
                    <Badge className="bg-rose-100 text-rose-800 border-none font-semibold">{successData.bookingStatus}</Badge>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Trạng thái dòng tiền:</span>
                    <Badge className="bg-purple-100 text-purple-800 border-none font-semibold">{successData.paymentStatus}</Badge>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Thời gian xử lý:</span>
                    <span className="font-mono text-xs text-slate-800 dark:text-slate-200">{new Date(successData.processedAt).toLocaleString('vi-VN')}</span>
                  </div>
                </div>
              </div>

              {/* Thông báo giải phóng sân */}
              <div className="bg-slate-50 dark:bg-slate-900/60 border rounded-lg p-3 flex gap-2 items-start text-xs text-slate-600 dark:text-slate-300">
                <AlertCircle className="h-4.5 w-4.5 text-blue-500 shrink-0 mt-0.5" />
                <div>
                  <span className="font-semibold block text-slate-800 dark:text-slate-100 mb-0.5">Khung giờ đặt sân đã trống!</span>
                  Hệ thống đã giải phóng khung giờ chơi <span className="font-semibold text-primary">{new Date(successData.playTime).toLocaleString('vi-VN')}</span> sang trạng thái **Sẵn sàng (AVAILABLE)** để đón nhận các lượt đặt sân mới.
                </div>
              </div>
            </div>
          )}

          <div className="bg-slate-50 dark:bg-slate-900/60 p-4 border-t flex justify-end">
            <Button 
              className="bg-emerald-600 hover:bg-emerald-700 text-white font-medium"
              onClick={() => setIsSuccessModalOpen(false)}
            >
              Đóng và tiếp tục
            </Button>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog
        open={cancelOnlyBooking !== null}
        onOpenChange={(open) => {
          if (!open) {
            setCancelOnlyBooking(null);
            setCancelOnlyReason("");
          }
        }}
      >
        <DialogContent className="max-w-md border dark:border-slate-800 shadow-2xl rounded-xl">
          <DialogHeader>
            <DialogTitle className="text-xl font-bold flex items-center gap-2 text-amber-600">
              <XCircle className="h-5 w-5" /> Hủy đơn đặt sân
            </DialogTitle>
            <DialogDescription className="text-slate-500 dark:text-slate-400 text-xs">
              Đơn <span className="font-mono font-semibold">{cancelOnlyBooking?.displayId}</span> đang chờ thu tiền mặt tại sân —
              chưa thu tiền nên hủy sẽ không phát sinh hoàn tiền. Giờ chơi sẽ được giải phóng ngay.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-2 py-2">
            <label className="text-sm font-medium text-slate-700 dark:text-slate-300">
              Lý do hủy (tùy chọn)
            </label>
            <Textarea
              value={cancelOnlyReason}
              onChange={(e) => setCancelOnlyReason(e.target.value)}
              placeholder="Ví dụ: khách báo không tới, đặt trùng lịch..."
              rows={3}
              maxLength={255}
              disabled={isCancelOnlySubmitting}
            />
          </div>

          <DialogFooter className="gap-2">
            <Button
              type="button"
              variant="outline"
              className="rounded-xl"
              onClick={() => setCancelOnlyBooking(null)}
              disabled={isCancelOnlySubmitting}
            >
              Quay lại
            </Button>
            <Button
              type="button"
              variant="destructive"
              className="rounded-xl"
              onClick={handleConfirmCancelOnly}
              disabled={isCancelOnlySubmitting}
            >
              {isCancelOnlySubmitting ? "Đang xử lý..." : "Xác nhận hủy"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={confirmCashBooking !== null}
        onOpenChange={(open) => {
          if (!open) setConfirmCashBooking(null);
        }}
      >
        <DialogContent className="max-w-md border dark:border-slate-800 shadow-2xl rounded-xl">
          <DialogHeader>
            <DialogTitle className="text-xl font-bold flex items-center gap-2 text-emerald-600">
              <CheckCircle className="h-5 w-5" /> Xác nhận đã thu tiền mặt
            </DialogTitle>
            <DialogDescription className="text-slate-500 dark:text-slate-400 text-xs">
              Vui lòng kiểm tra lại thông tin trước khi xác nhận. Hành động này sẽ đánh dấu đơn đã thanh toán và không thể hoàn tác qua nút này.
            </DialogDescription>
          </DialogHeader>

          {confirmCashBooking && (
            <div className="bg-slate-50 dark:bg-slate-900/60 p-4 rounded-xl border space-y-2 text-sm">
              <div className="flex justify-between">
                <span className="text-muted-foreground">Mã đơn:</span>
                <span className="font-mono font-bold text-primary">{confirmCashBooking.displayId}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">Khách hàng:</span>
                <span className="font-medium text-slate-800 dark:text-slate-200">{confirmCashBooking.customer.name}</span>
              </div>
              <div className="flex justify-between border-t pt-2 mt-2">
                <span className="text-muted-foreground font-semibold">Số tiền thu tại sân:</span>
                <span className="font-bold text-emerald-600">{confirmCashBooking.amount.toLocaleString('vi-VN')}đ</span>
              </div>
            </div>
          )}

          <DialogFooter className="gap-2">
            <Button
              type="button"
              variant="outline"
              className="rounded-xl"
              onClick={() => setConfirmCashBooking(null)}
              disabled={isConfirmCashSubmitting}
            >
              Quay lại
            </Button>
            <Button
              type="button"
              className="rounded-xl bg-emerald-600 hover:bg-emerald-700 text-white"
              onClick={handleConfirmCashReceived}
              disabled={isConfirmCashSubmitting}
            >
              {isConfirmCashSubmitting ? "Đang xử lý..." : "Xác nhận đã thu tiền"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={confirmRemainingBooking !== null}
        onOpenChange={(open) => {
          if (!open) setConfirmRemainingBooking(null);
        }}
      >
        <DialogContent className="max-w-md border dark:border-slate-800 shadow-2xl rounded-xl">
          <DialogHeader>
            <DialogTitle className="text-xl font-bold flex items-center gap-2 text-indigo-600">
              <CheckCircle className="h-5 w-5" /> Xác nhận thu nốt phần còn lại
            </DialogTitle>
            <DialogDescription className="text-slate-500 dark:text-slate-400 text-xs">
              Khách đã đặt cọc trước — vui lòng kiểm tra lại thông tin trước khi xác nhận đã thu đủ phần còn lại tại sân. Hành động này sẽ đánh dấu đơn đã thanh toán đủ và không thể hoàn tác qua nút này.
            </DialogDescription>
          </DialogHeader>

          {confirmRemainingBooking && (
            <div className="bg-slate-50 dark:bg-slate-900/60 p-4 rounded-xl border space-y-2 text-sm">
              <div className="flex justify-between">
                <span className="text-muted-foreground">Mã đơn:</span>
                <span className="font-mono font-bold text-primary">{confirmRemainingBooking.displayId}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">Khách hàng:</span>
                <span className="font-medium text-slate-800 dark:text-slate-200">{confirmRemainingBooking.customer.name}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">Đã đặt cọc:</span>
                <span className="font-medium text-slate-800 dark:text-slate-200">
                  {(confirmRemainingBooking.paidAmount || 0).toLocaleString('vi-VN')}đ
                </span>
              </div>
              <div className="flex justify-between border-t pt-2 mt-2">
                <span className="text-muted-foreground font-semibold">Số tiền thu nốt tại sân:</span>
                <span className="font-bold text-indigo-600">
                  {(confirmRemainingBooking.amount - (confirmRemainingBooking.paidAmount || 0)).toLocaleString('vi-VN')}đ
                </span>
              </div>
            </div>
          )}

          <DialogFooter className="gap-2">
            <Button
              type="button"
              variant="outline"
              className="rounded-xl"
              onClick={() => setConfirmRemainingBooking(null)}
              disabled={isConfirmRemainingSubmitting}
            >
              Quay lại
            </Button>
            <Button
              type="button"
              className="rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white"
              onClick={handleConfirmRemainingReceived}
              disabled={isConfirmRemainingSubmitting}
            >
              {isConfirmRemainingSubmitting ? "Đang xử lý..." : "Xác nhận đã thu đủ"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={receiptBooking !== null}
        onOpenChange={(open) => {
          if (!open) setReceiptBooking(null);
        }}
      >
        <DialogContent className="max-w-md border dark:border-slate-800 shadow-2xl rounded-2xl p-0 overflow-hidden bg-white">
          <div className="bg-emerald-600 p-6 text-white text-center relative">
            <CheckCircle className="h-12 w-12 mx-auto mb-2 text-emerald-100 animate-bounce" />
            <h3 className="text-xl font-bold">Biên nhận thanh toán</h3>
            <p className="text-xs text-emerald-100/90 mt-1">Đơn đặt sân đã được xác nhận thanh toán tiền mặt</p>
          </div>
          <div className="p-6 space-y-4">
            <div className="text-center pb-4 border-b border-dashed border-slate-200">
              <span className="text-xs text-slate-400 uppercase tracking-wider font-semibold">Mã đơn đặt sân</span>
              <div className="text-2xl font-mono font-bold text-slate-800 mt-0.5">{receiptBooking?.displayId}</div>
            </div>
            
            <div className="space-y-2.5 text-sm">
              <div className="flex justify-between">
                <span className="text-slate-500">Khách hàng:</span>
                <span className="font-semibold text-slate-800">{receiptBooking?.customer.name}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">Số điện thoại:</span>
                <span className="font-semibold text-slate-800">{receiptBooking?.customer.phone}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">Sân chơi:</span>
                <span className="font-semibold text-slate-800 text-right">
                  {receiptBooking?.venue}
                  {receiptBooking?.complexName && (
                    <span className="block text-xs font-normal text-slate-400">{receiptBooking.complexName}</span>
                  )}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">Thời gian:</span>
                <span className="font-semibold text-slate-800">{receiptBooking?.time} ({receiptBooking?.date})</span>
              </div>
              
              <div className="w-full border-t border-slate-100 my-3" />
              
              <div className="flex justify-between items-center bg-slate-50 p-3 rounded-xl">
                <span className="text-slate-600 font-medium">Số tiền thu mặt:</span>
                <span className="text-lg font-bold text-emerald-600">
                  {receiptBooking?.amount.toLocaleString('vi-VN')}đ
                </span>
              </div>
              {receiptBooking?.serviceFee !== undefined && receiptBooking.serviceFee > 0 && (
                <div className="flex justify-between text-xs text-amber-600 bg-amber-50 px-3 py-2.5 rounded-xl mt-1 border border-amber-100/50">
                  <span>Phí dịch vụ khấu trừ ví:</span>
                  <span className="font-semibold">-{receiptBooking.serviceFee.toLocaleString('vi-VN')}đ</span>
                </div>
              )}
            </div>
          </div>
          <DialogFooter className="p-6 bg-slate-50/50 border-t border-slate-100 gap-2">
            <Button
              type="button"
              className="w-full rounded-xl bg-emerald-600 hover:bg-emerald-700 text-white font-bold h-11 shadow-md shadow-emerald-600/10"
              onClick={() => setReceiptBooking(null)}
            >
              Đóng biên nhận
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ReportUserDialog
        open={reportBooking !== null}
        onOpenChange={(open) => {
          if (!open) setReportBooking(null);
        }}
        reporteeId={reportBooking?.customer.userId}
        bookingId={reportBooking?.id}
        stadiumId={reportBooking?.stadiumId}
        contextLabel={
          reportBooking
            ? `Báo cáo hành vi khách hàng ${reportBooking.customer.name} liên quan đến đơn đặt sân ${reportBooking.displayId}.`
            : undefined
        }
      />
    </>
  );
}

export default BookingManagementPage;

function BookingExceptionHistory({ bookingId }: { bookingId: number }) {
  const [exception, setException] = useState<RefundExceptionItem | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    let active = true;
    const load = async () => {
      setLoading(true);
      try {
        const data = await get<RefundExceptionItem>(`/refund-exceptions/booking/${bookingId}/latest`);
        if (active) setException(data || null);
      } catch {
        if (active) setException(null);
      } finally {
        if (active) setLoading(false);
      }
    };
    load();
    return () => { active = false; };
  }, [bookingId]);

  if (loading) {
    return (
      <div className="col-span-full flex items-center gap-2 text-xs text-slate-500 py-2">
        <span className="animate-spin rounded-full h-3 w-3 border-2 border-indigo-600 border-t-transparent"></span>
        Đang tải lịch sử ngoại lệ...
      </div>
    );
  }

  if (!exception) return null;

  return (
    <div className="col-span-full border border-slate-200 dark:border-slate-800 rounded-2xl bg-slate-50/50 dark:bg-slate-900/40 p-4 space-y-3">
      <div className="flex justify-between items-center border-b border-slate-200/60 pb-2">
        <span className="font-bold text-xs text-slate-700 dark:text-slate-300 uppercase tracking-wider">
          Chi tiết yêu cầu bất khả kháng
        </span>
        <Badge variant="outline" className="font-bold text-[9px] uppercase bg-white dark:bg-slate-800 border-slate-300">
          {exception.status === "PENDING_OWNER" ? "Chờ bạn duyệt" :
           exception.status === "APPROVED_OWNER" ? "Đã duyệt (GĐ 1)" :
           exception.status === "PENDING_ADMIN" ? "Khách khiếu nại lên Admin (GĐ 2)" :
           exception.status === "APPROVED_ADMIN" ? "Admin đã duyệt" :
           exception.status === "REJECTED_OWNER" ? "Đã từ chối" :
           exception.status === "REJECTED_ADMIN" ? "Admin từ chối" : exception.status}
        </Badge>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
        <div className="space-y-1">
          <span className="text-slate-400 font-semibold block uppercase tracking-wider text-[9px]">Lý do từ khách</span>
          <p className="text-slate-700 dark:text-slate-300 font-medium italic">"{exception.reason}"</p>
          {exception.evidenceUrl && (
            <a href={exception.evidenceUrl} target="_blank" rel="noreferrer" className="text-indigo-600 hover:text-indigo-700 hover:underline font-semibold block mt-1.5 flex items-center gap-1">
              <span>Xem bằng chứng đính kèm ↗</span>
            </a>
          )}
        </div>

        <div className="space-y-2.5 border-l pl-4 border-slate-200 dark:border-slate-800">
          {/* Stage 1: Owner Review */}
          <div className="space-y-0.5">
            <span className="text-slate-400 font-semibold block uppercase tracking-wider text-[9px]">Giai đoạn 1: Chủ sân phản hồi</span>
            {exception.ownerNote ? (
              <p className="text-slate-700 dark:text-slate-300">
                Tỷ lệ duyệt hoàn: <span className="font-bold text-emerald-600 dark:text-emerald-400">{exception.refundPercent}%</span>
                <br />
                Ý kiến: <span className="italic">"{exception.ownerNote}"</span>
              </p>
            ) : (
              <p className="text-slate-400 italic">Chưa phản hồi (đang chờ xử lý)</p>
            )}
          </div>

          {/* Stage 2: Admin Decision */}
          <div className="space-y-0.5 pt-1.5 border-t border-slate-200/60 dark:border-slate-800">
            <span className="text-slate-400 font-semibold block uppercase tracking-wider text-[9px]">Giai đoạn 2: Quyết định của Admin</span>
            {exception.adminNote ? (
              <p className="text-slate-700 dark:text-slate-300">
                Trạng thái: <span className="font-bold text-emerald-600 dark:text-emerald-400">Đã giải quyết ({exception.refundPercent}%)</span>
                <br />
                Phản hồi: <span className="italic">"{exception.adminNote}"</span>
              </p>
            ) : exception.status === "PENDING_ADMIN" ? (
              <p className="text-amber-600 dark:text-amber-400 font-semibold italic">Khách hàng không hài lòng. Đang chờ Admin phán quyết cuối cùng...</p>
            ) : (
              <p className="text-slate-400 italic">Chưa chuyển tiếp lên Admin</p>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

const WALLET_TX_LABELS: Record<string, { label: string }> = {
  BOOKING_CREDIT: { label: "Ghi nhận doanh thu vào ví chủ sân" },
  SERVICE_FEE_CREDIT: { label: "Phí dịch vụ ghi nhận vào ví nền tảng" },
  REFUND_DEBIT: { label: "Hoàn tiền — trừ ví chủ sân" },
  REFUND_FEE_DEBIT: { label: "Hoàn phí dịch vụ — trừ ví nền tảng" },
  SERVICE_FEE_DEBIT: { label: "Khấu trừ phí dịch vụ (thu tiền mặt)" },
};

function BookingWalletHistory({ bookingId }: { bookingId: number }) {
  const [transactions, setTransactions] = useState<WalletTransaction[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    let active = true;
    const load = async () => {
      setLoading(true);
      try {
        const data = await fetchWalletTransactionsByBooking(bookingId);
        if (active) setTransactions(data);
      } catch {
        if (active) setTransactions([]);
      } finally {
        if (active) setLoading(false);
      }
    };
    load();
    return () => { active = false; };
  }, [bookingId]);

  if (loading) {
    return (
      <div className="col-span-full flex items-center gap-2 text-xs text-slate-500 py-2">
        <span className="animate-spin rounded-full h-3 w-3 border-2 border-indigo-600 border-t-transparent"></span>
        Đang tải lịch sử giao dịch ví...
      </div>
    );
  }

  if (transactions.length === 0) return null;

  return (
    <div className="col-span-full border border-slate-200 dark:border-slate-800 rounded-2xl bg-slate-50/50 dark:bg-slate-900/40 p-4 space-y-3">
      <div className="flex justify-between items-center border-b border-slate-200/60 pb-2">
        <span className="font-bold text-xs text-slate-700 dark:text-slate-300 uppercase tracking-wider">
          Lịch sử giao dịch Ví (đối soát dòng tiền)
        </span>
      </div>

      <ul className="space-y-2 text-xs">
        {transactions.map((tx) => {
          const meta = WALLET_TX_LABELS[tx.transactionType] || { label: tx.transactionType };
          const isPositive = tx.amount >= 0;
          return (
            <li
              key={tx.transactionId}
              className="flex items-center justify-between gap-3 border-b border-dashed border-slate-200/60 dark:border-slate-800 pb-2 last:border-0 last:pb-0"
            >
              <div className="space-y-0.5">
                <span className="font-medium text-slate-700 dark:text-slate-300 block">{meta.label}</span>
                <span className="text-[10px] text-slate-400">{new Date(tx.createdAt).toLocaleString('vi-VN')}</span>
                {tx.note && <span className="text-[10px] text-slate-400 italic block">{tx.note}</span>}
              </div>
              <span className={`font-bold whitespace-nowrap ${isPositive ? "text-emerald-600 dark:text-emerald-400" : "text-rose-600 dark:text-rose-400"}`}>
                {isPositive ? "+" : ""}{tx.amount.toLocaleString('vi-VN')}đ
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
