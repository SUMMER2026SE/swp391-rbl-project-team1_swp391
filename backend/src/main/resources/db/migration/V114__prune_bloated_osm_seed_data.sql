-- Xóa toàn bộ 161 complex OSM (V7.6.1/V7.7/V7.8/V8.1/V8.2) cùng dữ liệu phát sinh
-- (booking/payment/review/complaint/refund-exception) để thay bằng bộ dữ liệu demo
-- nhỏ gọn, chọn lọc (xem V114-V116). Giữ nguyên 6 complex/stadium legacy từ V2/V6
-- (description rỗng) và toàn bộ tài khoản user/owner/admin.
--
-- Complex OSM được nhận diện qua description bắt đầu bằng chuỗi cố định do
-- generate-seed-sql-from-osm.mjs sinh ra; dùng LIKE (không phải =) vì một số
-- complex có thêm hậu tố "Website: ..." phía sau (vd complex_id 164).

-- 1) Xóa mọi thứ tham chiếu tới bookings trước
DELETE FROM booking_accessories;
DELETE FROM booking_promotions;
DELETE FROM payments;
DELETE FROM complaints;
DELETE FROM refund_exception_requests;
DELETE FROM reports;
DELETE FROM reviews;
DELETE FROM venue_reviews;

-- 2) Xóa bookings và match_requests
DELETE FROM join_requests;
DELETE FROM match_requests;
DELETE FROM bookings;

-- 3) Xóa court (con) rồi facility (cha) thuộc complex OSM
DELETE FROM stadiums
WHERE complex_id IN (
    SELECT complex_id FROM stadium_complexes
    WHERE description LIKE 'Dữ liệu thực tế tổng hợp từ OpenStreetMap.%'
)
AND parent_stadium_id IS NOT NULL;

DELETE FROM stadiums
WHERE complex_id IN (
    SELECT complex_id FROM stadium_complexes
    WHERE description LIKE 'Dữ liệu thực tế tổng hợp từ OpenStreetMap.%'
)
AND parent_stadium_id IS NULL;

-- 4) Xóa complex OSM (cascade complex_amenities/complex_sport_types/
--    maintenance_schedules/stadium_complex_images tự động theo FK ON DELETE CASCADE)
DELETE FROM stadium_complexes
WHERE description LIKE 'Dữ liệu thực tế tổng hợp từ OpenStreetMap.%';
