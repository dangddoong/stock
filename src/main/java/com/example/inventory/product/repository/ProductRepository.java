package com.example.inventory.product.repository;

import com.example.inventory.product.dto.ProductStockSnapshot;
import com.example.inventory.product.entity.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {
  @Query(
      value =
          """
            INSERT INTO product AS p (product_code, name, quantity)
            VALUES (:productCode, :initialName, :incomingQuantity)
            ON CONFLICT (product_code)
            DO UPDATE SET quantity = p.quantity + :incomingQuantity
            RETURNING id, product_code, name, quantity
            """,
      nativeQuery = true)
  ProductStockSnapshot upsertAndIncreaseStock(
      @Param("productCode") String productCode,
      @Param("initialName") String initialName,
      @Param("incomingQuantity") long incomingQuantity);

  @Query(
      value =
          """
            UPDATE product SET quantity = quantity - :outgoingQuantity
            WHERE id = :id AND quantity >= :outgoingQuantity
            RETURNING id, product_code, name, quantity
            """,
      nativeQuery = true)
  Optional<ProductStockSnapshot> decreaseStockIfSufficient(
      @Param("id") long id, @Param("outgoingQuantity") long outgoingQuantity);

  Optional<ProductStockSnapshot> findStockById(long id);
}
