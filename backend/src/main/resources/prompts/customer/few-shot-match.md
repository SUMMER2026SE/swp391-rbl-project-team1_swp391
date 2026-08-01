User: "có kèo nào ở tân bình ko"
AI: {"intent": "find_match", "confidence": 0.96, "message": "Để mình tìm các kèo ghép ở Tân Bình nhé.", "params": {"location": "Tân Bình"}}

User: "có kèo bóng đá nào gần đại học FPT không" (CÓ tên môn + địa điểm)
AI: {"intent": "find_match", "confidence": 0.96, "message": "Để mình tìm kèo bóng đá gần Đại học FPT cho bạn nhé.", "params": {"sportName": "Bóng đá", "location": "FPT"}}

User: "cho mình tham gia kèo 2 với" (Tham gia vào kèo thứ 2 đã liệt kê)
AI: {"intent": "join_match", "confidence": 0.99, "message": "Để mình gửi yêu cầu tham gia kèo thứ 2 cho bạn nhé.", "params": {"matchIndex": 1}}

User: "xin slot kèo đầu nha bro" (Cách nói ngắn ngọn tham gia kèo)
AI: {"intent": "join_match", "confidence": 0.97, "message": "Để mình gửi yêu cầu tham gia kèo đầu tiên nhé.", "params": {"matchIndex": 0, "message": "xin slot nha bro"}}

User: "bây giờ tạo cho tôi kèo về sân bóng đá lúc 14h00 ngày 02/08/2026 đi"
AI: {"intent": "create_match", "confidence": 0.99, "message": "Mình sẽ kiểm tra booking và các thông tin cần thiết trước khi tạo kèo.", "params": {"sportName": "Bóng đá", "date": "2026-08-02", "startTime": "14:00"}}

User: "Ghép lẻ tối đa 10 người, trình độ trung bình, không chia tiền" (dữ liệu gửi từ form sau khi AI đã chọn booking)
AI: {"intent": "create_match", "confidence": 0.99, "message": "Mình đã ghi nhận thông tin kèo của bạn.", "params": {"matchingType": "INDIVIDUAL", "maxPlayers": 10, "skillLevel": "INTERMEDIATE", "splitPrice": false}}

User: "Cáp kèo đội vs đội, trình độ nâng cao, chia tiền 200000 đồng mỗi đội"
AI: {"intent": "create_match", "confidence": 0.99, "message": "Mình đã ghi nhận thông tin kèo của bạn.", "params": {"matchingType": "TEAM_VS_TEAM", "skillLevel": "ADVANCED", "splitPrice": true, "pricePerPlayer": 200000}}

User: "tôi muốn tạo kèo bóng đá lúc 14h ngày mai ở Thủ Đức" (Tạo kèo trực tiếp từ tự nhiên)
AI: {"intent": "create_match", "confidence": 0.98, "message": "Mình sẽ lên kèo bóng đá ở Thủ Đức lúc 14h ngày mai cho bạn nhé.", "params": {"sportName": "Bóng đá", "location": "Thủ Đức", "startTime": "14:00", "date": "2026-08-02"}}

User: "tạo kèo cầu lông tối nay 19h nha bot"
AI: {"intent": "create_match", "confidence": 0.98, "message": "Để mình tạo kèo cầu lông lúc 19h tối nay cho bạn.", "params": {"sportName": "Cầu lông", "startTime": "19:00", "date": "2026-08-01"}}

User: "cáp kèo banh nhựa sân 5 người thứ 7 tuần này"
AI: {"intent": "create_match", "confidence": 0.96, "message": "Mình sẽ tạo kèo bóng đá sân 5 người vào thứ 7 tuần này nhé.", "params": {"sportName": "Bóng đá", "footballFieldType": "FIVE_A_SIDE", "targetDate": "2026-08-08"}}

User: "bắt đối tennis sáng mai 8h"
AI: {"intent": "create_match", "confidence": 0.95, "message": "Mình sẽ lên kèo tennis vào 8h sáng mai cho bạn.", "params": {"sportName": "Tennis", "startTime": "08:00", "date": "2026-08-02"}}

User: "tạo cho tôi kèo sân bóng đá lúc 14h00 ngày mai đi" (Khách chỉ đưa thông tin thời gian, môn học)
AI: {"intent": "create_match", "confidence": 0.99, "message": "Mình sẽ tìm booking phù hợp và hiển thị form để bạn chọn thông tin kèo.", "params": {"sportName": "Bóng đá", "date": "2026-08-02", "startTime": "14:00"}}

User: "lên kèo cho booking #686"
AI: {"intent": "create_match", "confidence": 0.99, "message": "Mình sẽ tìm booking #686 và hiển thị form để bạn chọn thông tin kèo.", "params": {"bookingId": 686}}

User: "tạo kèo bóng rổ tại Sân 1 — Nhà thi đấu Quân khu 7"
AI: {"intent": "create_match", "confidence": 0.99, "message": "Mình sẽ kiểm tra booking tại Sân 1 — Nhà thi đấu Quân khu 7 và hiển thị form để bạn chọn thông tin kèo.", "params": {"sportName": "Bóng rổ", "stadiumName": "Sân 1 — Nhà thi đấu Quân khu 7"}}
