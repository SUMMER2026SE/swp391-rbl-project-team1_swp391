# Pull Request: Nâng Cấp Thẻ Ngữ Cảnh 3 Lớp & Điều Hướng Thông Minh Theo Vai Trò

## 📌 Thông Tin Chung
- **Branch:** `feature/chat/context-card-upgrade` ➔ `main`
- **Người thực hiện:** Antigravity AI Pair Programmer
- **Loại thay đổi:** `feat` (Tính năng mới + Cải tiến UX)
- **Tác vụ liên quan:** Nâng cấp thẻ ngữ cảnh nhắn tin (Context Card) và điều hướng thông minh cho Khách hàng (Customer) & Chủ sân (Owner).

---

## 🎯 Mục Tiêu & Mô Tả Thay Đổi

### 1. Hiển Thị Đầy Đủ Cấu Trúc 3 Lớp Của Sân (Hierarchy Clarity)
- **Trước đây:** Thẻ ngữ cảnh nhắn tin khi hỏi về sân con chỉ hiển thị *"Sân 1"* hoặc *"Sân 3"*, gây nhầm lẫn vì không biết sân thuộc Khu sân nào (Khu 5 người / 7 người) hay Tổ hợp sân nào.
- **Sau khi nâng cấp:** 
  - Thẻ hiển thị chuẩn 3 lớp:
    - **Dòng 1:** 🏢 **[Tổ hợp sân]** *(Ví dụ: Sân bóng rổ Quân Khu 5)*
    - **Dòng 2:** 📍 **[Khu sân] › [Sân con]** *(Ví dụ: Khu Sân Bóng Rổ › Sân 1)*
  - **Tự động giải mã (Auto-resolve):** Frontend trang Chat có thêm cơ chế `venueCache` tự động gọi API lấy thông tin phân cấp cho cả các **tin nhắn cũ đã lưu từ trước trong Database** mà không bị thiếu thông tin.

### 2. Thẻ Booking Chi Tiết
- Hiển thị rõ:
  - **Mã Booking:** `Booking #[ID]`
  - **Tổ hợp & Khu sân:** 🏢 `[Tổ hợp sân]` | 📍 `[Khu sân] › [Sân con]`
  - **Khung thời gian:** 📅 `[Ngày thi đấu] · [Giờ thi đấu]`

### 3. Điều Hướng Thông Minh Khi Click Vào Thẻ (Smart Role-Based Navigation)
- **Khách hàng (Customer):**
  - Click Thẻ Sân ➔ Chuyển hướng đến đúng trang thông tin chi tiết Sân (`/venues/[id]`) hoặc Tổ hợp (`/complexes/[id]`).
  - Click Thẻ Booking ➔ Chuyển hướng đến trang chi tiết Đơn hàng (`/booking/[id]`).
- **Chủ sân (Owner):**
  - Click Thẻ Sân (Tổ hợp / Khu / Sân con) ➔ Chuyển hướng đến `/owner/venues?complexId=X&facilityId=Y&stadiumId=Z`, trang tự động **mở bung (expand) cả 3 lớp**, cuộn mượt và phát hiệu ứng **highlight khung viền ring xanh lá** vào đúng phần tử mục tiêu.
  - Click Thẻ Booking ➔ Chuyển hướng đến `/owner/bookings?bookingId=X`, trang tự động **sổ chi tiết đơn hàng xuống** và cuộn tới đơn hàng.

---

## 📂 Danh Sách File Sửa Đổi (Changed Files)

### Backend (Java / Spring Boot)
1. `backend/.../util/StadiumUtils.java`: Thêm helper `resolveComplexId()`, `resolveFacilityId()`, `resolveFacilityName()`.
2. `backend/.../repository/StadiumRepository.java`: Bổ sung `"complex"`, `"parentStadium"`, `"parentStadium.complex"` vào `@EntityGraph` của `findWithDetailsByStadiumId` để eager fetch đủ 3 lớp.
3. `backend/.../dto/response/StadiumDetailResponse.java` & `StadiumResponse.java`: Bổ sung các trường `complexId`, `complexName`, `parentStadiumId`, `facilityName`, `nodeType`.
4. `backend/.../dto/response/BookingResponse.java` & `BookingDetailResponse.java`: Bổ sung các trường phân cấp trong `StadiumInfo`.
5. `backend/.../service/impl/PublicStadiumServiceImpl.java`, `BookingServiceImpl.java`, `OwnerBookingService.java`: Populate thông tin 3 lớp vào DTO response.
6. `backend/.../service/ai/handler/`: Cập nhật `MyBookingsHandler`, `CancelBookingHandler`, `BookingStatusHandler`.

### Frontend (TypeScript / Next.js)
1. `frontend/src/lib/contextual-chat.ts`: Cập nhật type `ChatContext` hỗ trợ `complexId`, `complexName`, `facilityId`, `facilityName`, `nodeType`.
2. `frontend/src/lib/api/venue.ts` & `bookings-api.ts`: Cập nhật interface `VenueDetail` & `BookingDetailItem`.
3. `frontend/src/app/chat/page.tsx`: 
   - Render lại giao diện Thẻ Ngữ Cảnh 3 lớp.
   - Thêm `handleContextCardClick` điều hướng theo role.
   - Thêm `venueCache` auto-fetch thông tin phân cấp cho tin nhắn cũ.
4. `frontend/src/components/venues/VenueDetail.tsx`, `ComplexDetail.tsx`, `TimeSlotManager.tsx`, `app/booking/[id]/page.tsx`: Truyền thông tin phân cấp khi tạo cuộc trò chuyện context.
5. `frontend/src/app/owner/venues/components/OwnerVenuesClient.tsx`: Xử lý `searchParams` để auto-expand 3 lớp và cuộn/highlight phần tử `complex-X`, `facility-Y`, `court-Z`.
6. `frontend/src/app/owner/bookings/page.tsx`: Xử lý `searchParams` `bookingId` để auto-expand dòng đơn hàng.

---

## 🧪 Hướng Dẫn Kiểm Thử (Testing Steps)

1. **Khách hàng gửi ngữ cảnh:**
   - Vào trang sân con bất kỳ ➔ Bấm *"Nói chuyện với chủ sân"*.
   - Kiểm tra trong Chat hiển thị thẻ ngữ cảnh đầy đủ 3 cấp **🏢 [Tổ hợp sân]** và **📍 [Khu sân] › [Sân con]**.
2. **Khách hàng Click thẻ:**
   - Bấm vào thẻ ➔ Chuyển hướng đúng về `/venues/[id]`.
3. **Chủ sân Click thẻ:**
   - Đăng nhập tài khoản Owner, bấm vào thẻ ngữ cảnh Sân ➔ Chuyển hướng tới `/owner/venues`, kiểm tra các cấp cây được bung ra và dòng sân được khoanh vùng highlight.
   - Bấm vào thẻ Booking ➔ Chuyển hướng tới `/owner/bookings`, kiểm tra đơn hàng tự động mở chi tiết.

---

## 🛡️ Kiểm Tra Ảnh Hưởng Hệ Thống (System Impact)
- ✅ **Backward Compatibility:** Không phá vỡ dữ liệu tin nhắn cũ trong DB.
- ✅ **Backend Compile:** `./mvnw compile -DskipTests` ➔ `BUILD SUCCESS`.
- ✅ **Frontend Type Check:** `npx tsc --noEmit` ➔ `0 errors`.
