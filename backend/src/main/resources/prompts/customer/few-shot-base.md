### MỘT SỐ VÍ DỤ QUAN TRỌNG ĐỂ PHÂN LOẠI INTENT (FEW-SHOT EXAMPLES)

⚠️⚠️⚠️ QUY TẮC SẮT (IRONCLAD RULE) - ĐỌC KỸ:
**"Đặt" + [môn] + [khu vực] = search_stadiums** (CHƯA có sân cụ thể)
**"Đặt" + [tên sân cụ thể] = create_booking** (ĐÃ có sân cụ thể)
→ KHÔNG BAO GIỜ dùng create_booking khi user chỉ nói "tìm/đặt sân [môn] ở [địa điểm]"

User: "Tìm sân bóng Thủ Đức"
AI: {"intent": "search_stadiums", "confidence": 0.98, "message": "Để mình tìm các sân bóng đá ở Thủ Đức cho bạn nhé.", "params": {"sportName": "Bóng đá", "district": "Thủ Đức"}}

User: "mình rảnh chiều nay có kèo nào khu vực gần trường không, giá sinh viên thôi" (KHÔNG có tên môn)
AI: {"intent": "need_more_info", "confidence": 0.92, "message": "Bạn muốn tìm kèo cho môn thể thao nào vậy? Cho mình biết để tìm kèo phù hợp nhé.", "params": {}}

User: "đơn đặt sân của tôi duyệt chưa"
AI: {"intent": "booking_status", "confidence": 0.96, "message": "Để mình kiểm tra trạng thái đơn đặt sân của bạn nhé:", "params": {}}

---

### COMPOUND TASK (YÊU CẦU NHIỀU BƯỚC)

⚠️ Khi câu chứa từ 2 yêu cầu nối nhau bằng "rồi", "sau đó", "và", "tiếp theo" → dùng `compound_task`.

User: "tìm sân bóng đá ở Chi Lăng rồi đặt 1 giờ tối mai"
AI: {"intent": "compound_task", "confidence": 0.97, "message": "Tôi sẽ phân tích yêu cầu của bạn thành các bước để thực hiện tuần tự.", "params": {}}

User: "tìm kèo bóng đá gần tôi rồi tham gia kèo phù hợp nhất"
AI: {"intent": "compound_task", "confidence": 0.97, "message": "Tôi sẽ phân tích yêu cầu của bạn thành các bước để thực hiện tuần tự.", "params": {}}

User: "tìm sân futsal ở Quận 1 và đặt 2 tiếng"
AI: {"intent": "compound_task", "confidence": 0.96, "message": "Tôi sẽ phân tích yêu cầu của bạn thành các bước để thực hiện tuần tự.", "params": {}}
