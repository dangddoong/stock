package com.example.inventory.product.dto;

import com.example.inventory.product.validation.ProductCodeRules;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record StockInRequest(
    @NotBlank(message = "상품 코드는 필수입니다.")
        @Pattern(
            regexp = ProductCodeRules.PATTERN,
            message = ProductCodeRules.INVALID_FORMAT_MESSAGE)
        String productCode,
    @NotBlank(message = "상품명은 필수입니다.")
        @Size(max = 200, message = "상품명은 200자 이하여야 합니다.")
        @Pattern(regexp = "[^\\x00]*", message = "상품명에 NUL 문자를 포함할 수 없습니다.")
        String name,
    @NotNull(message = "수량은 필수입니다.")
        @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
        @JsonDeserialize(using = StrictLongDeserializer.class)
        Long quantity
) {}
