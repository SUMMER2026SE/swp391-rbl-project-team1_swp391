# fix(review): Paginate list endpoints, add owner guards, fix security and booking bugs

## Mô tả

PR này tổng hợp toàn bộ các cải tiến và bug fix sau vòng code review nội bộ trên nhánh `fix/post-review-bugs`. Các thay đổi bao gồm: bổ sung phân trang cho các endpoint trả về danh sách lớn, tăng cường bảo vệ owner routes, sửa lỗi bảo mật nghiêm trọng trong Redis config, và fix một loạt bug logic trong booking/refund/match flow.

---

## Phạm vi thay đổi
    
| Khu vực | Files thay đổi |
|---|---|
| Backend — Config | `CacheConfig`, `SecurityConfig` |
| Backend — Controller | `AdminSportTypeController`, `ComplaintController`, `MatchRequestController`, `OwnerBookingController`, `OwnerReviewController`, `RefundController`, `StadiumController` |
| Backend — DTO | `BookingDetailResponse`, `CreateMatchRequest`, `PageResponse` |
| Backend — Repository | `BookingRepository`, `ComplaintRepository`, `JoinRequestRepository`, `MatchRequestRepository`, `PaymentRepository` |
| Backend — Service | `ComplaintService/Impl`, `MatchRequestService/Impl`, `SportTypeService/Impl`, `AdminDashboardServiceImpl`, `AdminOwnerServiceImpl`, `BookingServiceImpl`, `OwnerRegistrationServiceImpl`, `PublicStadiumServiceImpl`, `RefundServiceImpl` |
| Backend — Test | `PublicStadiumServiceImplTest`, `ComplaintServiceImplTest` |
| Frontend — Pages | `login`, `auth/redirect` *(new)*, `admin/complaints`, `admin/dashboard`, `admin/owner-approvals`, `booking/[id]/review`, `community`, `complaints`, `owner/complaints` |
| Frontend — Components | `AddressPicker`, `ComplaintList` |
| Frontend — Lib | `matchmaking.ts`, `stadium.ts`, `venue.ts`, `bookings-api.ts` |
| Backend — DB Migration | `V6__seed_demo_data.sql` *(new)* |

---

## Chi tiết thay đổi

### 🔴 [SECURITY] CacheConfig — Thay LaissezFaireSubTypeValidator

**Vấn đề:** `LaissezFaireSubTypeValidator.instance` kết hợp `DefaultTyping.NON_FINAL` cho phép deserialize bất kỳ class JVM nào từ Redis, dẫn đến nguy cơ RCE nếu Redis bị tấn công.

**Fix:**
- Thay bằng `BasicPolymorphicTypeValidator` với allowlist chỉ gồm `com.sportvenue.dto`, `java.util`, `java.lang`, `java.math`, `java.time`.
- Inject Spring's primary `ObjectMapper` (thay vì tạo mới) để tránh config drift; gọi `.copy()` để không mutate bean gốc.

---

### 🔴 [BUG] SecurityConfig — Khôi phục `permitAll` cho upload giấy tờ đăng ký Owner

**Vấn đề:** `POST /api/v1/files/document` bị đổi thành `authenticated()`, chặn luồng đăng ký Owner (upload giấy tờ khi chưa có JWT).

**Fix:** Khôi phục về `permitAll()` — đây là endpoint thiết kế để hỗ trợ guest upload giấy tờ đăng ký, `FileController` đã xử lý `userPrincipal == null` an toàn.

---

### 🔴 [BUG] BookingServiceImpl — Cancel booking không xử lý đúng DEPOSITED và TimeSlot

**Vấn đề 1:** `wasReallyPaid` chỉ check `PaymentStatus.PAID`, bỏ qua `DEPOSITED` → booking đặt cọc bị huỷ mà không tạo refund record và không đổi status về `REFUNDED`.

**Fix:** Mở rộng điều kiện: `wasReallyPaid = PAID || DEPOSITED`.

**Vấn đề 2:** `cancelBooking` không restore `TimeSlot.slotStatus → AVAILABLE` khi huỷ booking đã được owner confirm (slot đang ở trạng thái `BOOKED`), khiến slot bị khoá vĩnh viễn.

**Fix:** Thêm restore slot trước khi lưu booking:
```java
if (slot != null && slot.getSlotStatus() == SlotStatus.BOOKED) {
    slot.setSlotStatus(SlotStatus.AVAILABLE);
    timeSlotRepository.save(slot);
}
```

---

### 🔴 [BUG] Frontend — `pageNo` vs `pageNumber` mismatch

**Vấn đề:** Backend `PageResponse` serialize field là `pageNumber`, nhưng 4 file frontend khai báo và đọc `pageNo` → pagination bị stuck tại page 0 (nhận `undefined`).

**Fix:**
- Đổi `pageNo: number` → `pageNumber: number` trong `PageResponse` interface tại `venue.ts`, `stadium.ts`, `matchmaking.ts`.
- Đổi `data.pageNo` → `data.pageNumber` trong `fetchMyBookings` (`bookings-api.ts:69`).

---

### 🔴 [BUG] login/page.tsx — Customer có thể redirect sang trang Owner/Admin

**Vấn đề:** Sau khi đăng nhập với role Customer, nếu URL có `?redirect=/owner/dashboard`, rawRedirect vượt qua regex check và user bị route nhầm sang trang không có quyền.

**Fix:** Thêm guard chặn redirect đến `/admin` và `/owner` nếu role không phải Admin/Owner:
```ts
!rawRedirect.startsWith('/admin') && !rawRedirect.startsWith('/owner')
```

---

### 🟡 [PERF] AdminOwnerServiceImpl — `saveAll` gọi trên toàn bộ list thay vì subset

**Vấn đề:** `stadiumRepository.saveAll(stadiums)` được gọi với toàn bộ list, kể cả các stadium không thay đổi trạng thái.

**Fix:** Collect chỉ các stadium thực sự bị thay đổi bằng `stream().filter().peek().toList()` rồi `saveAll(toUpdate)`.

---

### ✨ [FEAT] Phân trang (Pagination) cho các danh sách lớn

Các endpoint trả `List<>` đã được chuyển sang `Page<>` để tránh load toàn bộ data vào memory:

| Endpoint | Trước | Sau |
|---|---|---|
| `GET /owner/complaints` | `List<ComplaintResponse>` | `Page<ComplaintResponse>` |
| `GET /complaints` | `List<ComplaintResponse>` | `Page<ComplaintResponse>` |
| `GET /admin/complaints` | `List<ComplaintResponse>` | `Page<ComplaintResponse>` |
| `GET /matchmaking/my-created` | `List<MatchResponse>` | `Page<MatchResponse>` |
| `GET /matchmaking/my-joined` | `List<JoinRequestResponse>` | `Page<JoinRequestResponse>` |

Tất cả đều dùng `@PageableDefault(size = 20, sort = "createdAt", direction = DESC)`.

Frontend (`community/page.tsx`, các complaints pages) đã được cập nhật để đọc `data.content`.

---

### ✨ [FEAT] `@RequireApprovedOwner` guard trên owner controllers

Tất cả các endpoint của Owner (booking management, review management, refund, stadium CRUD) giờ đều được bảo vệ bởi `@RequireApprovedOwner`, ngăn owner chưa được duyệt thực hiện các thao tác.

---

### ✨ [FEAT] UC-ADM-08: Thêm endpoint cập nhật Sport Type

Thêm `PUT /api/v1/admin/sport-types/{id}` cho phép Admin chỉnh sửa loại môn thể thao. Có kiểm tra trùng tên và trùng mã trước khi lưu.

---

### ✨ [FEAT] MatchRequestServiceImpl — Ngăn race condition khi approve join request

**Vấn đề:** Khi nhiều approve request đến cùng lúc, in-memory increment có thể vượt quá `maxPlayers`.

**Fix:**
- Thêm `incrementCurrentPlayers(@Modifying)` với điều kiện `WHERE currentPlayers < maxPlayers` — chỉ update 1 row, trả về số row affected (0 hoặc 1).
- Nếu trả về 0 → throw `BadRequestException("Match is already full")`.
- Reload match sau atomic update để check FULL status chính xác.
- Thêm `existsApprovedOverlappingJoinRequest` để chặn user đã được approve vào kèo trùng giờ.
- Thêm validation thời gian quá khứ khi tạo kèo.

---

### ✨ [FEAT] RefundServiceImpl — Cải thiện tính đúng đắn

- Dùng `reservationDate` (thay vì `bookingDate.toLocalDate()`) để tính thời gian chơi.
- Chấp nhận `DEPOSITED` (ngoài `PAID`) trong `validateOwnershipAndStatus`.
- Extract `playTime(Booking)` helper — xoá 3 chỗ copy-paste `LocalDateTime.of(reservationDate, slot.getStartTime())`.
- Dùng `findSuccessPaymentsByBookingId` thay vì `findByBookingBookingId` để lấy đúng giao dịch gốc khi có nhiều payment retry.

---

### ✨ [FEAT] ComplaintServiceImpl — Cải thiện guard và duplicate check

- Đổi `existsByBookingBookingId` → `existsByBookingBookingIdAndStatusNot(RESOLVED)`: cho phép tạo khiếu nại mới sau khi khiếu nại cũ đã được giải quyết.
- Thêm guard chống double-resolve cho cả `resolveComplaint` (Owner) và `resolveComplaintByAdmin`.

---

### ✨ [FEAT] AdminOwnerServiceImpl — Lock/unlock owner chính xác hơn

- Lock: chỉ đổi `AVAILABLE → MAINTENANCE`, giữ nguyên `CLOSED`.
- Unlock: chỉ đổi `MAINTENANCE → AVAILABLE`, giữ nguyên `CLOSED`.
- Thêm guard chặn set `ApprovedStatus.PENDING` từ action approve/reject.
- Set `user.isVerified(true)` khi approve.

---

### ✨ [FEAT] BookingDetailResponse — Bổ sung thêm fields

Thêm `createdAt` (thời điểm tạo đơn), `stadium.sportType`, `stadium.imageUrl` vào response để frontend hiển thị đầy đủ thông tin chi tiết booking.

---

### ✨ [FEAT] ComplaintList — WebSocket real-time updates

`ComplaintList.tsx` giờ subscribe WebSocket qua `useComplaintWebSocket` để nhận tin nhắn mới realtime mà không cần polling.

---

### ✨ [FEAT] AddressPicker — Cache geocoding ổn định hơn

Round tọa độ về 8 chữ số thập phân khi build cache key, tránh trường hợp float precision khác nhau gây cache miss liên tục.

---

### 🛠 [STYLE] complaints pages — Bỏ `get<any>`

Đổi `get<any>` thành `get<Complaint[] | { content: Complaint[] }>` tại 3 trang complaints theo quy định `No any` trong CLAUDE.md.

---

### ✨ [FEAT] `/auth/redirect` — Trang xử lý callback sau Google OAuth

**File mới:** `frontend/src/app/auth/redirect/page.tsx`

Sau khi Google OAuth hoàn tất, NextAuth redirect về `/auth/redirect`. Trang này đọc `session.user.roleName` rồi điều hướng:
- `Admin` → `/admin/dashboard`
- `Owner` → `/owner/dashboard`
- Còn lại → `/`

Được reference bởi `handleGoogleLogin` trong `login/page.tsx` qua `callbackUrl: "/auth/redirect"`. Thiếu file này thì Google login sẽ về trang trắng/crash.

---

### ✨ [FEAT] V6 — Demo seed data

**File mới:** `backend/src/main/resources/db/migration/V6__seed_demo_data.sql`

Bổ sung dữ liệu demo đủ để chạy toàn bộ use case mà không cần tạo thủ công:

| Loại | Nội dung |
|---|---|
| Users | `customer4`, `customer5`, `owner3` (PENDING — demo UC-ADM-06 approve owner) |
| Stadiums | Kích hoạt lại Sân Tennis Phú Mỹ Hưng (`MAINTENANCE → AVAILABLE`) |
| Time slots | Slot 06:00–21:00 cho Sân Tennis |
| Bookings | Booking mẫu ở các trạng thái PENDING, CONFIRMED, COMPLETED, CANCELLED |
| Payments | Payment record tương ứng với bookings |
| Complaints | Complaint mẫu ở OPEN, IN_PROGRESS, RESOLVED |
| Reviews | Review mẫu với rating đa dạng |
| Match requests | Kèo ghép mẫu ở OPEN và FULL |

---

## Test plan

- [ ] `GET /complaints`, `GET /owner/complaints`, `GET /admin/complaints` trả về đúng `Page` object với `content`, `pageNumber`, `totalElements`, `totalPages`.
- [ ] `GET /matchmaking/my-created`, `GET /matchmaking/my-joined` trả về đúng Page.
- [ ] Owner chưa được duyệt bị từ chối `403` khi gọi `/api/v1/owner/**`.
- [ ] Guest upload giấy tờ tại `POST /api/v1/files/document` không cần JWT → trả về `200`.
- [ ] Huỷ booking đã confirmed → slot trở về `AVAILABLE`, trạng thái booking là `CANCELLED`.
- [ ] Huỷ booking có `paymentStatus=DEPOSITED` → trạng thái chuyển sang `REFUNDED`, refund record được tạo.
- [ ] Approve join request khi match đã full → trả về `400 Match is already full`.
- [ ] Approve 2 request đồng thời vào slot cuối → chỉ 1 thành công, 1 bị từ chối.
- [ ] Customer login với `?redirect=/owner/dashboard` → bị route về `/` thay vì `/owner/dashboard`.
- [ ] Admin/Owner login → route đúng về dashboard tương ứng bất kể `?redirect=`.
- [ ] Pagination trên trang danh sách khiếu nại hiển thị đúng page, không stuck tại page 0.
- [ ] `PUT /api/v1/admin/sport-types/{id}` cập nhật đúng tên/mã, báo lỗi nếu trùng.
- [ ] Run unit tests: `PublicStadiumServiceImplTest`, `ComplaintServiceImplTest` pass.
- [ ] Đăng nhập Google → được redirect đúng dashboard theo role (không về trang trắng).
- [ ] Chạy Flyway migration: `V6__seed_demo_data.sql` apply thành công, data demo xuất hiện.
- [ ] Các tài khoản seed: `customer4@sportvenue.com`, `customer5@sportvenue.com`, `owner3@sportvenue.com` login được (password: `password123`).

---

## Checklist trước khi merge

- [x] Branch name đúng format: `fix/post-review-bugs`
- [x] Commit message theo Conventional Commits
- [x] Không commit `.env`, credential, `target/`, `node_modules/`
- [x] Không push trực tiếp lên `main`
- [ ] Đã test manual các luồng chính
- [ ] Đã được review bởi ít nhất 1 thành viên (Lượng hoặc Huy)
