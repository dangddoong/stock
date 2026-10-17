package com.example.inventory.product.service;

import com.example.inventory.common.error.BusinessException;
import com.example.inventory.common.error.ErrorCode;
import com.example.inventory.product.dto.ProductStockSnapshot;
import com.example.inventory.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductStockService {
  private final ProductRepository repository;

  @Transactional
  public ProductStockSnapshot stockIn(
      String productCode, String initialName, long incomingQuantity) {
    if (incomingQuantity <= 0) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }

    return repository.upsertAndIncreaseStock(productCode, initialName, incomingQuantity);
  }

  @Transactional
  public ProductStockSnapshot stockOut(long id, long outgoingQuantity) {
    if (outgoingQuantity <= 0) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }

    return repository
        .decreaseStockIfSufficient(id, outgoingQuantity)
        .orElseThrow(
            () -> {
              ErrorCode errorCode =
                  repository.existsById(id)
                      ? ErrorCode.STOCK_UNAVAILABLE
                      : ErrorCode.PRODUCT_NOT_FOUND;
              return new BusinessException(errorCode);
            });
  }

  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  public ProductStockSnapshot getStock(long id) {
    return repository
        .findStockById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
  }
}
