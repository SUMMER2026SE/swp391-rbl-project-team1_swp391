package com.sportvenue.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class CreateAppealRequest {

    @NotBlank(message = "Nội dung kháng cáo không được để trống")
    @Size(max = 2000, message = "Nội dung kháng cáo không được vượt quá 2000 ký tự")
    private String appealText;

    @Size(max = 5, message = "Chỉ được gửi tối đa 5 đường dẫn hoặc hình ảnh bằng chứng")
    private List<@Size(max = 2000000, message = "Dữ liệu bằng chứng không được vượt quá 2MB") String>
            evidenceUrls = new ArrayList<>();
}
