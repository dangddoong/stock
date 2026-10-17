package com.example.inventory.product.validation;

public final class ProductCodeRules {
  public static final String PATTERN = "^[A-Za-z0-9][A-Za-z0-9_-]{0,49}$";
  public static final String INVALID_FORMAT_MESSAGE = "상품 코드 형식을 확인해 주세요.";

  private ProductCodeRules() {}
}
