'use client';

import { useState } from "react";
import { Header } from "@/components/layout/Header";
import { Footer } from "@/components/landing/Footer";
import Link from "next/link";
import { Search, ChevronRight, HelpCircle, FileText, CreditCard, CalendarDays, User } from "lucide-react";

export default function HelpPage() {
  const [searchQuery, setSearchQuery] = useState("");

  return (
    <div className="min-h-screen flex flex-col bg-slate-50">
      <Header />
      <main className="flex-grow container mx-auto px-4 py-12">
        <div className="max-w-5xl mx-auto">
          {/* Header */}
          <div className="text-center mb-12">
            <h1 className="text-4xl font-bold text-slate-900 mb-4">Xin chào, chúng tôi có thể giúp gì cho bạn?</h1>
            <div className="max-w-2xl mx-auto relative">
              <Search className="absolute left-4 top-1/2 -translate-y-1/2 text-slate-400 h-5 w-5" />
              <input 
                type="text" 
                placeholder="Nhập từ khóa tìm kiếm (Ví dụ: Hủy đặt sân, Thanh toán...)"
                className="w-full pl-12 pr-4 py-4 rounded-full border shadow-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-slate-700"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
            </div>
          </div>

          {/* Categories */}
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 mb-12">
            {[
              { icon: <CalendarDays className="h-6 w-6 text-blue-500" />, title: "Hướng dẫn Đặt sân", desc: "Cách tìm kiếm, chọn giờ và đặt sân nhanh chóng." },
              { icon: <CreditCard className="h-6 w-6 text-green-500" />, title: "Thanh toán & Ví", desc: "Các phương thức thanh toán, nạp/rút tiền." },
              { icon: <FileText className="h-6 w-6 text-orange-500" />, title: "Chính sách Hủy & Hoàn tiền", desc: "Quy định hủy lịch và thời gian nhận lại tiền." },
              { icon: <User className="h-6 w-6 text-purple-500" />, title: "Quản lý Tài khoản", desc: "Đổi mật khẩu, cập nhật thông tin cá nhân." },
              { icon: <HelpCircle className="h-6 w-6 text-red-500" />, title: "Câu hỏi thường gặp (FAQ)", desc: "Tổng hợp các câu hỏi phổ biến nhất." },
            ].map((cat) => (
              <div key={cat.title} className="bg-white p-6 rounded-xl border shadow-sm hover:shadow-md transition-shadow cursor-pointer group">
                <div className="flex items-start gap-4">
                  <div className="p-3 bg-slate-50 rounded-lg group-hover:bg-primary/5 transition-colors">
                    {cat.icon}
                  </div>
                  <div>
                    <h3 className="font-semibold text-slate-800 mb-1">{cat.title}</h3>
                    <p className="text-sm text-slate-500">{cat.desc}</p>
                  </div>
                </div>
              </div>
            ))}
          </div>

          {/* Contact CTA */}
          <div className="bg-primary/5 border border-primary/10 rounded-xl p-8 text-center">
            <h2 className="text-xl font-bold text-slate-800 mb-2">Bạn vẫn chưa tìm thấy câu trả lời?</h2>
            <p className="text-slate-600 mb-6">Đội ngũ hỗ trợ của chúng tôi luôn sẵn sàng giải đáp mọi thắc mắc của bạn.</p>
            <div className="flex flex-col sm:flex-row justify-center gap-4">
              <Link href="/contact" className="px-6 py-3 bg-primary text-white font-medium rounded-lg hover:bg-primary/90 transition-colors">
                Gửi yêu cầu hỗ trợ
              </Link>
              <a href="tel:19001234" className="px-6 py-3 bg-white text-slate-700 font-medium rounded-lg border hover:bg-slate-50 transition-colors">
                Gọi tổng đài: 1900 1234
              </a>
            </div>
          </div>
        </div>
      </main>
      <Footer />
    </div>
  );
}
