import { Header } from "@/components/layout/Header";
import { Footer } from "@/components/landing/Footer";

export default function PrivacyPage() {
  return (
    <div className="min-h-screen flex flex-col bg-slate-50">
      <Header />
      <main className="flex-grow container mx-auto px-4 py-12">
        <div className="bg-white rounded-xl shadow-sm border p-8 md:p-12 max-w-4xl mx-auto">
          <h1 className="text-3xl font-bold mb-6 text-slate-900">Chính sách bảo mật</h1>
          <div className="prose prose-slate max-w-none space-y-6 text-slate-600">
            <p className="text-sm text-slate-500 mb-8 border-b pb-4">Cập nhật lần cuối: 22/07/2026</p>
            
            <p className="text-lg">
              Chào mừng bạn đến với Chính sách bảo mật của SportsBook. Việc bạn tin tưởng và sử dụng nền tảng của chúng tôi đồng nghĩa với việc bạn giao phó những thông tin cá nhân của mình cho chúng tôi. Chúng tôi cam kết bảo vệ quyền riêng tư và bảo mật dữ liệu của bạn một cách tuyệt đối, tuân thủ theo các quy định pháp luật hiện hành tại Việt Nam.
            </p>

            <h2 className="text-2xl font-bold text-slate-800 mt-8 mb-4 border-b pb-2">1. Thông tin chúng tôi thu thập</h2>
            <p>Chúng tôi thu thập các thông tin cá nhân khi bạn tương tác với SportsBook thông qua trang web hoặc ứng dụng di động, bao gồm:</p>
            <ul className="list-disc pl-5 space-y-2">
              <li><strong>Thông tin nhận dạng cơ bản:</strong> Họ và tên, ngày sinh, giới tính.</li>
              <li><strong>Thông tin liên lạc:</strong> Địa chỉ email, số điện thoại, địa chỉ (để gửi hóa đơn hoặc đối soát nếu cần).</li>
              <li><strong>Dữ liệu giao dịch:</strong> Lịch sử đặt sân, thông tin thanh toán (chúng tôi KHÔNG lưu trữ trực tiếp số thẻ tín dụng/CVV mà chỉ lưu trữ token giao dịch trả về từ đối tác cổng thanh toán VNPay, MoMo).</li>
              <li><strong>Thông tin kỹ thuật:</strong> Địa chỉ IP, loại trình duyệt, hệ điều hành, thời gian truy cập, cookies và các dữ liệu phân tích hành vi người dùng trên trang nhằm mục đích tối ưu hóa trải nghiệm (UX).</li>
            </ul>

            <h2 className="text-2xl font-bold text-slate-800 mt-8 mb-4 border-b pb-2">2. Mục đích sử dụng thông tin</h2>
            <p>Thông tin của bạn được thu thập nhằm phục vụ các mục đích cụ thể và hợp pháp sau đây:</p>
            <ul className="list-disc pl-5 space-y-2">
              <li><strong>Cung cấp dịch vụ cốt lõi:</strong> Xác thực tài khoản, xử lý và xác nhận các đơn đặt sân, chuyển thông tin cơ bản của bạn (Tên, Số điện thoại) cho Chủ sân để họ chuẩn bị tiếp đón.</li>
              <li><strong>Hỗ trợ và chăm sóc khách hàng:</strong> Giải quyết khiếu nại, hỗ trợ hoàn tiền, trả lời các thắc mắc qua tổng đài hoặc email.</li>
              <li><strong>Cá nhân hóa trải nghiệm:</strong> Đề xuất các sân bóng gần bạn nhất, hoặc gửi thông báo về các môn thể thao mà bạn yêu thích.</li>
              <li><strong>Tiếp thị và truyền thông:</strong> Gửi mã giảm giá, chương trình khuyến mãi, bản tin định kỳ (bạn có quyền từ chối nhận email tiếp thị bất cứ lúc nào bằng cách nhấn nút Unsubscribe ở cuối email).</li>
              <li><strong>An ninh và phòng chống gian lận:</strong> Phát hiện, ngăn chặn các hành vi giả mạo, spam lịch đặt sân gây thiệt hại cho nền tảng và đối tác Chủ sân.</li>
            </ul>

            <h2 className="text-2xl font-bold text-slate-800 mt-8 mb-4 border-b pb-2">3. Chia sẻ thông tin với bên thứ ba</h2>
            <p>
              SportsBook tuyệt đối <strong>KHÔNG BÁN</strong> hoặc <strong>CHO THUÊ</strong> thông tin cá nhân của bạn cho bất kỳ bên thứ ba nào vì mục đích thương mại. Thông tin chỉ được chia sẻ trong các trường hợp sau:
            </p>
            <ul className="list-disc pl-5 space-y-2">
              <li><strong>Cho Chủ sân (Đối tác):</strong> Họ tên và Số điện thoại để xác nhận lịch đặt.</li>
              <li><strong>Cho Đối tác thanh toán:</strong> Các cổng thanh toán (VNPay, MoMo, Ngân hàng) để xử lý giao dịch tài chính.</li>
              <li><strong>Theo yêu cầu pháp lý:</strong> Khi có yêu cầu chính thức từ cơ quan nhà nước có thẩm quyền nhằm phục vụ công tác điều tra theo luật định.</li>
            </ul>

            <h2 className="text-2xl font-bold text-slate-800 mt-8 mb-4 border-b pb-2">4. Lưu trữ và Bảo mật</h2>
            <p>
              Dữ liệu của bạn được lưu trữ trên hệ thống máy chủ an toàn, được mã hóa theo tiêu chuẩn SSL/TLS 256-bit trong quá trình truyền tải. 
              Mật khẩu của bạn được băm (hash) bằng thuật toán mạnh (Bcrypt) không thể giải mã ngược, kể cả đội ngũ quản trị cũng không thể biết mật khẩu gốc của bạn.
            </p>

            <h2 className="text-2xl font-bold text-slate-800 mt-8 mb-4 border-b pb-2">5. Quyền kiểm soát dữ liệu của bạn</h2>
            <p>Là người dùng của SportsBook, bạn có toàn quyền đối với dữ liệu của mình:</p>
            <ul className="list-disc pl-5 space-y-2">
              <li><strong>Truy cập và Chỉnh sửa:</strong> Bạn có thể tự do cập nhật thông tin cá nhân trong mục &quot;Hồ sơ của tôi&quot;.</li>
              <li><strong>Quyền được lãng quên (Xóa dữ liệu):</strong> Bạn có thể yêu cầu xóa vĩnh viễn tài khoản và toàn bộ dữ liệu liên quan bằng cách gửi email tới <a href="mailto:privacy@sportsbook.vn" className="text-primary hover:underline">privacy@sportsbook.vn</a>. Chúng tôi sẽ xử lý yêu cầu trong vòng 7 ngày làm việc.</li>
            </ul>
          </div>
        </div>
      </main>
      <Footer />
    </div>
  );
}
