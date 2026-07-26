import { Header } from "@/components/layout/Header";
import { Footer } from "@/components/landing/Footer";

export default function TermsPage() {
  return (
    <div className="min-h-screen flex flex-col bg-slate-50">
      <Header />
      <main className="flex-grow container mx-auto px-4 py-12">
        <div className="bg-white rounded-xl shadow-sm border p-8 md:p-12 max-w-4xl mx-auto">
          <h1 className="text-3xl font-bold mb-6 text-slate-900">Điều khoản sử dụng</h1>
          <div className="prose prose-slate max-w-none space-y-6 text-slate-600">
            <p className="text-sm text-slate-500 mb-8 border-b pb-4">Cập nhật lần cuối: 22/07/2026</p>
            
            <p className="text-lg">
              Vui lòng đọc kỹ các Điều khoản Sử dụng này trước khi đăng ký tài khoản hoặc sử dụng dịch vụ trên nền tảng SportsBook. Bằng việc tiếp tục truy cập và sử dụng ứng dụng của chúng tôi, bạn được xem là đã hiểu, đồng ý và tự nguyện ràng buộc bởi toàn bộ các quy định dưới đây.
            </p>

            <h2 className="text-2xl font-bold text-slate-800 mt-8 mb-4 border-b pb-2">1. Định nghĩa</h2>
            <ul className="list-disc pl-5 space-y-2">
              <li><strong>SportsBook:</strong> Là nền tảng công nghệ cung cấp dịch vụ trung gian kết nối giữa Chủ sân và Người chơi.</li>
              <li><strong>Người dùng (Khách hàng):</strong> Cá nhân hoặc tổ chức sử dụng SportsBook để tìm kiếm, xem thông tin và đặt lịch thuê sân thể thao.</li>
              <li><strong>Chủ sân (Đối tác):</strong> Các cá nhân, doanh nghiệp sở hữu hoặc có quyền khai thác cơ sở vật chất thể thao, đăng tải thông tin lên SportsBook để cho thuê.</li>
            </ul>

            <h2 className="text-2xl font-bold text-slate-800 mt-8 mb-4 border-b pb-2">2. Điều kiện đăng ký tài khoản</h2>
            <p>Để sử dụng đầy đủ các tính năng của nền tảng, bạn cần tạo tài khoản với các điều kiện sau:</p>
            <ul className="list-disc pl-5 space-y-2">
              <li>Bạn phải từ đủ 15 tuổi trở lên. Nếu dưới 15 tuổi, bạn cần có sự giám hộ của phụ huynh khi đặt sân và thanh toán.</li>
              <li>Thông tin đăng ký (Họ tên, SĐT, Email) phải chính xác, hợp lệ và thuộc quyền sở hữu của bạn.</li>
              <li>Bạn chịu trách nhiệm bảo mật thông tin đăng nhập. SportsBook không chịu trách nhiệm cho mọi tổn thất phát sinh do bạn để lộ mật khẩu.</li>
            </ul>

            <h2 className="text-2xl font-bold text-slate-800 mt-8 mb-4 border-b pb-2">3. Quy định về Đặt sân và Thanh toán</h2>
            <ul className="list-disc pl-5 space-y-2">
              <li><strong>Xác nhận đặt sân:</strong> Một giao dịch đặt sân chỉ được coi là thành công khi bạn nhận được thông báo xác nhận từ hệ thống qua Email/Ứng dụng, kèm theo mã đặt sân (Booking ID).</li>
              <li><strong>Thanh toán:</strong> Tùy thuộc vào chính sách của từng sân, bạn có thể phải thanh toán 100%, đặt cọc một phần hoặc thanh toán trực tiếp tại sân. Nếu chọn thanh toán online, bạn cần thực hiện chuyển khoản/quẹt thẻ trong thời gian quy định (thường là 15 phút) trước khi hệ thống tự động hủy đơn.</li>
              <li><strong>Tính chính xác:</strong> Giá thuê hiển thị trên ứng dụng là giá đã bao gồm các loại thuế, phí bắt buộc (trừ khi có ghi chú khác). Phí dịch vụ (nếu có) sẽ được thông báo rõ ràng trước khi bạn nhấn thanh toán.</li>
            </ul>

            <h2 className="text-2xl font-bold text-slate-800 mt-8 mb-4 border-b pb-2">4. Chính sách Hủy lịch và Hoàn tiền</h2>
            <p>Việc hủy lịch phụ thuộc vào quy định cụ thể của Chủ sân (được hiển thị rõ ở bước thanh toán). Nhìn chung, chính sách chung áp dụng như sau:</p>
            <ul className="list-disc pl-5 space-y-2">
              <li><strong>Hủy sớm (Thường trước 12h-24h):</strong> Miễn phí hủy, hoàn trả 100% tiền cọc/tiền sân vào Ví SportsBook hoặc tài khoản gốc.</li>
              <li><strong>Hủy trễ hoặc Không đến:</strong> Có thể mất một phần hoặc toàn bộ số tiền đã cọc tùy theo quy định của sân.</li>
              <li><strong>Trường hợp bất khả kháng:</strong> (Thiên tai, bão lụt, sự cố kỹ thuật từ phía sân) Người chơi được hoàn tiền 100% mà không chịu phí phạt.</li>
            </ul>

            <h2 className="text-2xl font-bold text-slate-800 mt-8 mb-4 border-b pb-2">5. Trách nhiệm và Miễn trừ</h2>
            <ul className="list-disc pl-5 space-y-2">
              <li><strong>Quy định tại sân:</strong> Người chơi phải tuân thủ nội quy riêng của từng sân (trang phục, giữ vệ sinh, không mang vũ khí/chất cấm). Sân có quyền từ chối phục vụ mà không hoàn tiền nếu khách hàng vi phạm nghiêm trọng.</li>
              <li><strong>Chấn thương/Mất mát tài sản:</strong> SportsBook là nền tảng đặt lịch, không chịu trách nhiệm bồi thường cho các chấn thương thể thao hoặc mất mát tài sản cá nhân xảy ra tại địa điểm thi đấu.</li>
              <li><strong>Tính liên tục của dịch vụ:</strong> Chúng tôi luôn nỗ lực duy trì hệ thống ổn định 24/7 nhưng không cam kết ứng dụng sẽ không bao giờ bị gián đoạn do bảo trì hoặc sự cố mạng diện rộng.</li>
            </ul>
          </div>
        </div>
      </main>
      <Footer />
    </div>
  );
}
