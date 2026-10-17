package com.example.inventory.product.dto;

import lombok.Builder;

@Builder
public record ProductStockSnapshot(long id, String productCode, String name, long quantity) {}
