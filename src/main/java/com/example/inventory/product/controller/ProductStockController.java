package com.example.inventory.product.controller;

import com.example.inventory.product.dto.ProductStockResponse;
import com.example.inventory.product.dto.StockInRequest;
import com.example.inventory.product.dto.StockOutRequest;
import com.example.inventory.product.service.ProductStockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/products", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ProductStockController {
  private final ProductStockService service;

  @PostMapping("/stock-in")
  public ProductStockResponse stockIn(@Valid @RequestBody StockInRequest request) {
    return ProductStockResponse.from(
        service.stockIn(request.productCode(), request.name(), request.quantity()));
  }

  @PostMapping("/{id}/stock-out")
  public ProductStockResponse stockOut(
      @PathVariable @Positive(message = "상품 ID는 1 이상이어야 합니다.") long id,
      @Valid @RequestBody StockOutRequest request) {
    return ProductStockResponse.from(service.stockOut(id, request.quantity()));
  }

  @GetMapping("/{id}/stock")
  public ProductStockResponse getStock(
      @PathVariable @Positive(message = "상품 ID는 1 이상이어야 합니다.") long id) {
    return ProductStockResponse.from(service.getStock(id));
  }
}
