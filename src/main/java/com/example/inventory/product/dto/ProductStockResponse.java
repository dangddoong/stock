package com.example.inventory.product.dto;

import lombok.Builder;

@Builder
public record ProductStockResponse(long id, String productCode, String name, long quantity) {
  public static ProductStockResponse from(ProductStockSnapshot stock) {
    return ProductStockResponse.builder()
        .id(stock.id())
        .productCode(stock.productCode())
        .name(stock.name())
        .quantity(stock.quantity())
        .build();
  }
}
