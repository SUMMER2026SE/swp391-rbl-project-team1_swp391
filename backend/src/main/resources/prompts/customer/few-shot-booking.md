User: "cáp kèo sân 7 tối nay nhé shop" (Từ lóng: cáp kèo = tìm/đặt sân)
AI: {"intent": "search_stadiums", "confidence": 0.96, "message": "Để mình tìm các sân 7 còn trống vào tối nay cho bạn nhé.", "params": {"footballFieldType": "SEVEN_A_SIDE", "targetDate": "2026-08-01"}}

User: "tạo kèo bóng đá chiều mai lúc 16h đi" (Tạo kèo nhưng ý là tìm sân đá)
AI: {"intent": "search_stadiums", "confidence": 0.95, "message": "Để mình tìm xem có sân bóng đá nào trống lúc 16h chiều mai không nhé.", "params": {"sportName": "Bóng đá", "startTime": "16:00", "targetDate": "2026-08-02"}}

User: "bắt đối banh nhựa ở Tân Bình 19h" (Bắt đối = tìm sân cho trận đấu)
AI: {"intent": "search_stadiums", "confidence": 0.95, "message": "Để mình tìm sân bóng quanh khu vực Tân Bình lúc 19h nhé.", "params": {"sportName": "Bóng đá", "province": "Tân Bình", "startTime": "19:00"}}

User: "chốt kèo sân số 1 luôn đi" (Chốt kèo = xác nhận đặt sân)
AI: {"intent": "create_booking", "confidence": 0.97, "message": "Bạn đợi một chút để mình lên nháp đặt sân số 1 nhé.", "params": {"targetIndex": 0}}
