import { Header } from "@/components/layout/Header";
import { Footer } from "@/components/landing/Footer";

export default function AboutPage() {
  return (
    <div className="min-h-screen flex flex-col bg-slate-50">
      <Header />
      <main className="flex-grow container mx-auto px-4 py-12">
        <div className="bg-white rounded-xl shadow-sm border p-8 md:p-12 max-w-4xl mx-auto">
          <h1 className="text-3xl font-bold mb-6 text-slate-900">Giới thiệu về SportsBook</h1>
          <div className="prose prose-slate max-w-none space-y-6">
            <p className="text-slate-600 leading-relaxed text-lg">
              Chào mừng bạn đến với <strong>SportsBook</strong> - nền tảng đặt sân thể thao trực tuyến hàng đầu Việt Nam.
              Chúng tôi ra đời với sứ mệnh kết nối những người đam mê thể thao với các cơ sở vật chất chất lượng, giúp việc tìm kiếm, đặt sân và quản lý trở nên dễ dàng, nhanh chóng và minh bạch hơn bao giờ hết.
            </p>
            
            <div className="grid grid-cols-1 md:grid-cols-2 gap-8 my-8">
              <div className="bg-slate-50 p-6 rounded-xl border border-slate-100">
                <h2 className="text-xl font-semibold text-slate-800 mt-0 mb-3 text-primary">Tầm nhìn</h2>
                <p className="text-slate-600 leading-relaxed mb-0">
                  Trở thành siêu ứng dụng thể thao số 1 tại Đông Nam Á, nơi mọi người không chỉ đặt sân mà còn tìm kiếm đối tác, tham gia giải đấu và xây dựng một cộng đồng thể thao lành mạnh, gắn kết và phát triển mạnh mẽ mỗi ngày.
                </p>
              </div>
              <div className="bg-slate-50 p-6 rounded-xl border border-slate-100">
                <h2 className="text-xl font-semibold text-slate-800 mt-0 mb-3 text-primary">Sứ mệnh</h2>
                <p className="text-slate-600 leading-relaxed mb-0">
                  Cung cấp giải pháp công nghệ toàn diện giúp phá bỏ rào cản trong việc tiếp cận thể thao. Đối với người chơi, đó là sự tiện lợi tối đa. Đối với chủ sân, đó là công cụ quản lý thông minh giúp tối ưu hóa doanh thu và vận hành.
                </p>
              </div>
            </div>

            <h2 className="text-2xl font-bold text-slate-800 mt-8 mb-4 border-b pb-2">Giá trị cốt lõi</h2>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-6">
              <div>
                <h3 className="font-semibold text-slate-800">Minh bạch</h3>
                <p className="text-slate-600 text-sm mt-2">Mọi thông tin về giá cả, tình trạng sân và đánh giá đều được hiển thị công khai, rõ ràng.</p>
              </div>
              <div>
                <h3 className="font-semibold text-slate-800">Tiện ích</h3>
                <p className="text-slate-600 text-sm mt-2">Trải nghiệm người dùng đặt lên hàng đầu với giao diện tối giản, dễ thao tác trên mọi thiết bị.</p>
              </div>
              <div>
                <h3 className="font-semibold text-slate-800">Đồng hành</h3>
                <p className="text-slate-600 text-sm mt-2">Luôn lắng nghe phản hồi để cải tiến sản phẩm, đồng hành cùng sự phát triển của phong trào thể thao.</p>
              </div>
            </div>

            <h2 className="text-2xl font-bold text-slate-800 mt-10 mb-4 border-b pb-2">Dịch vụ nổi bật</h2>
            <ul className="list-disc pl-5 text-slate-600 space-y-3">
              <li><strong>Hệ thống tìm kiếm thông minh:</strong> Lọc sân theo vị trí địa lý, loại hình môn thể thao (Bóng đá, cầu lông, tennis, bóng rổ...), mức giá và tiện ích đi kèm (như có bãi đỗ xe, có căn tin, cho thuê giày...).</li>
              <li><strong>Cập nhật thời gian thực (Real-time):</strong> Theo dõi tình trạng trống/kín của sân ngay lập tức, loại bỏ hoàn toàn việc gọi điện thoại hỏi sân truyền thống.</li>
              <li><strong>Thanh toán đa dạng & Bảo mật:</strong> Hỗ trợ thanh toán trực tuyến qua Ví điện tử (MoMo, VNPay), thẻ ngân hàng hoặc thanh toán tại sân với độ bảo mật cao nhất.</li>
              <li><strong>Cộng đồng & Đánh giá:</strong> Đọc và viết đánh giá thực tế về chất lượng sân, từ đó giúp cộng đồng có những lựa chọn tốt nhất.</li>
              <li><strong>Hệ sinh thái dành cho Chủ Sân:</strong> Bảng điều khiển (Dashboard) trực quan giúp chủ sân quản lý doanh thu, lịch đặt, báo cáo tài chính và phản hồi khách hàng một cách tự động và chuyên nghiệp.</li>
            </ul>
          </div>
        </div>
      </main>
      <Footer />
    </div>
  );
}
