"use client";

import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { ShieldCheck, Clock, AlertTriangle, FileText, CheckCircle2, CloudRain } from "lucide-react";
import { Badge } from "@/components/ui/badge";

interface TermsAndCancellationModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAccept?: () => void;
}

export function TermsAndCancellationModal({
  open,
  onOpenChange,
  onAccept,
}: TermsAndCancellationModalProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="rounded-2xl sm:max-w-2xl max-h-[90vh] overflow-y-auto p-6">
        <DialogHeader className="space-y-2 border-b pb-4">
          <div className="flex items-center gap-2 text-emerald-600">
            <ShieldCheck className="h-6 w-6" />
            <span className="text-xs font-bold uppercase tracking-wider">Chính sách minh bạch</span>
          </div>
          <DialogTitle className="text-xl font-bold text-slate-900">
            Quy định Hủy sân & Chính sách Hoàn tiền SportVenue
          </DialogTitle>
          <p className="text-xs text-slate-500">
            Nhằm đảm bảo quyền lợi cho cả Người chơi và Chủ sân, SportVenue áp dụng khung quy định hoàn tiền theo mốc thời gian minh bạch dưới đây.
          </p>
        </DialogHeader>

        <div className="space-y-6 py-4 text-sm text-slate-700">
          {/* Mốc thời gian hoàn tiền */}
          <div className="space-y-3">
            <h3 className="font-semibold text-slate-900 flex items-center gap-2">
              <Clock className="h-4 w-4 text-indigo-600" />
              1. Tỉ lệ hoàn tiền theo thời điểm hủy đơn
            </h3>

            <div className="grid gap-3 sm:grid-cols-3">
              {/* Mốc 1: Trên 24h */}
              <div className="rounded-xl border border-emerald-200 bg-emerald-50/50 p-4 space-y-2">
                <div className="flex items-center justify-between">
                  <Badge className="bg-emerald-600 hover:bg-emerald-700">Hoàn 100%</Badge>
                  <span className="text-xs font-semibold text-emerald-700">Hủy sớm</span>
                </div>
                <div className="text-xs font-medium text-slate-800">
                  Hủy trước giờ nhận sân <strong className="text-emerald-700">≥ 24 giờ</strong>
                </div>
                <p className="text-[11px] text-slate-600 leading-relaxed">
                  Hoàn trả 100% số tiền cọc/thanh toán vào Ví nội bộ hoặc tài khoản thanh toán ban đầu.
                </p>
              </div>

              {/* Mốc 2: 12h - 24h */}
              <div className="rounded-xl border border-amber-200 bg-amber-50/50 p-4 space-y-2">
                <div className="flex items-center justify-between">
                  <Badge className="bg-amber-600 hover:bg-amber-700">Hoàn 50%</Badge>
                  <span className="text-xs font-semibold text-amber-700">Hủy sát giờ</span>
                </div>
                <div className="text-xs font-medium text-slate-800">
                  Hủy trước giờ nhận sân từ <strong className="text-amber-700">12h - 24h</strong>
                </div>
                <p className="text-[11px] text-slate-600 leading-relaxed">
                  Hoàn 50% số tiền. 50% còn lại được bồi thường cho Chủ sân để bù đắp chi phí giữ lịch.
                </p>
              </div>

              {/* Mốc 3: Dưới 12h */}
              <div className="rounded-xl border border-rose-200 bg-rose-50/50 p-4 space-y-2">
                <div className="flex items-center justify-between">
                  <Badge variant="destructive">Hoàn 0%</Badge>
                  <span className="text-xs font-semibold text-rose-700">Hủy gấp</span>
                </div>
                <div className="text-xs font-medium text-slate-800">
                  Hủy trước giờ nhận sân <strong className="text-rose-700">&lt; 12 giờ</strong>
                </div>
                <p className="text-[11px] text-slate-600 leading-relaxed">
                  Không áp dụng hoàn tiền tự động do Chủ sân khó lấp trống lịch thi đấu sát giờ.
                </p>
              </div>
            </div>
          </div>

          {/* Trường hợp bão lũ / sự cố bất khả kháng */}
          <div className="rounded-xl border bg-slate-50 p-4 space-y-2">
            <h3 className="font-semibold text-slate-900 flex items-center gap-2">
              <CloudRain className="h-4 w-4 text-sky-600" />
              2. Trường hợp thời tiết bão lũ hoặc sự cố tại sân
            </h3>
            <p className="text-xs text-slate-600 leading-relaxed">
              Nếu không thể thi đấu do <strong>mưa bão, ngập lụt, hoặc sân hỏng hóc đột xuất</strong>, người chơi có thể gửi <em>Yêu cầu hoàn tiền ngoại lệ (Refund Exception)</em> kèm hình ảnh/video bằng chứng. Admin và Chủ sân sẽ xem xét hoàn 100% tiền đơn đặt.
            </p>
          </div>

          {/* Quy trình nhận tiền hoàn */}
          <div className="space-y-2">
            <h3 className="font-semibold text-slate-900 flex items-center gap-2">
              <CheckCircle2 className="h-4 w-4 text-emerald-600" />
              3. Phương thức và thời gian nhận lại tiền
            </h3>
            <ul className="list-disc pl-5 text-xs text-slate-600 space-y-1">
              <li><strong>Ví SportVenue:</strong> Tiền hoàn được cộng ngay lập tức vào Ví nội bộ để sử dụng cho lần đặt sân tiếp theo.</li>
              <li><strong>VNPay / Ngân hàng:</strong> Tiền được tự động hoàn lại tài khoản ngân hàng từ 1 - 3 ngày làm việc theo quy định đối soát của cổng thanh toán.</li>
            </ul>
          </div>

          {/* Trách nhiệm thi đấu */}
          <div className="rounded-lg bg-amber-50/80 p-3 border border-amber-200 text-xs text-amber-900 flex items-start gap-2">
            <AlertTriangle className="h-4 w-4 text-amber-600 shrink-0 mt-0.5" />
            <span>
              Người chơi vui lòng có mặt đúng giờ đã đặt. Trường hợp đến muộn hoặc không đến mà không báo trước, đơn hàng sẽ bị tính là hoàn tất và không được hoàn lại tiền.
            </span>
          </div>
        </div>

        <DialogFooter className="border-t pt-4 flex flex-col sm:flex-row gap-2">
          <Button
            onClick={() => {
              if (onAccept) onAccept();
              onOpenChange(false);
            }}
            className="bg-emerald-600 hover:bg-emerald-700 text-white font-semibold rounded-xl w-full"
          >
            Tôi đã đọc & đồng ý quy định
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
