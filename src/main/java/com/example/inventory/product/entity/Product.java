package com.example.inventory.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Builder;

@Entity
@Table(name = "product")
public class Product {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "product_code", nullable = false, unique = true, length = 50)
  private String productCode;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(nullable = false)
  private long quantity;

  protected Product() {}

  @Builder
  private Product(String productCode, String name, long quantity) {
    this.productCode = productCode;
    this.name = name;
    this.quantity = quantity;
  }

  public Long getId() {
    return id;
  }

  public String getProductCode() {
    return productCode;
  }

  public String getName() {
    return name;
  }

  public long getQuantity() {
    return quantity;
  }
}
