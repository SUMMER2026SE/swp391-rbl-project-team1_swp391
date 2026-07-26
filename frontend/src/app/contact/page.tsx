import { Header } from "@/components/layout/Header";
import { Footer } from "@/components/landing/Footer";
import { MapPin, Phone, Mail, Clock } from "lucide-react";

export default function ContactPage() {
  return (
    <div className="min-h-screen flex flex-col bg-slate-50">
      <Header />
      <main className="flex-grow container mx-auto px-4 py-12">
        <div className="bg-white rounded-xl shadow-sm border p-8 md:p-12 max-w-4xl mx-auto">
          <h1 className="text-3xl font-bold mb-8 text-slate-900 text-center">Liên hệ với chúng tôi</h1>
          
          <div className="max-w-2xl mx-auto">
            <h2 className="text-xl font-semibold text-slate-800 mb-6 text-center">Thông tin liên hệ</h2>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-8">
              <div className="flex items-start gap-4">
                <div className="p-3 bg-primary/10 rounded-full text-primary shrink-0">
                  <MapPin className="h-6 w-6" />
                </div>
                <div>
                  <h3 className="font-medium text-slate-900">Địa chỉ</h3>
                  <p className="text-slate-600 mt-1">Khu Công Nghệ Cao FPT, Hòa Hải, Ngũ Hành Sơn, Đà Nẵng</p>
                </div>
              </div>
              
              <div className="flex items-start gap-4">
                <div className="p-3 bg-primary/10 rounded-full text-primary shrink-0">
                  <Phone className="h-6 w-6" />
                </div>
                <div>
                  <h3 className="font-medium text-slate-900">Điện thoại</h3>
                  <p className="text-slate-600 mt-1">1900 1234</p>
                </div>
              </div>

              <div className="flex items-start gap-4">
                <div className="p-3 bg-primary/10 rounded-full text-primary shrink-0">
                  <Mail className="h-6 w-6" />
                </div>
                <div>
                  <h3 className="font-medium text-slate-900">Email</h3>
                  <p className="text-slate-600 mt-1">support@sportsbook.vn</p>
                </div>
              </div>

              <div className="flex items-start gap-4">
                <div className="p-3 bg-primary/10 rounded-full text-primary shrink-0">
                  <Clock className="h-6 w-6" />
                </div>
                <div>
                  <h3 className="font-medium text-slate-900">Giờ làm việc</h3>
                  <p className="text-slate-600 mt-1">Thứ 2 - Thứ 6: 08:00 - 18:00<br/>Thứ 7 - Chủ Nhật: 08:00 - 12:00</p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </main>
      <Footer />
    </div>
  );
}
