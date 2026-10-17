package com.example.inventory.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.inventory.PostgresTestSupport;
import com.example.inventory.common.error.BusinessException;
import com.example.inventory.common.error.ErrorCode;
import com.example.inventory.product.service.ProductStockService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import lombok.Builder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("동시 입출고 재고 정합성")
@Timeout(60)
class ProductStockConcurrencyTest extends PostgresTestSupport {
  @Autowired ProductStockService service;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute("TRUNCATE product RESTART IDENTITY");
  }

  @Test
  @DisplayName("신규 상품에 100건 동시 입고하면 한 상품에 재고 100개가 저장된다")
  void concurrentRegistrationAddsAllQuantitiesInOneRow() throws Exception {
    // given
    var operations = IntStream.range(0, 100).mapToObj(i -> stockInTask("NEW", "상품", 1)).toList();

    // when
    var results = executeConcurrently(operations);

    // then
    assertThat(results).allMatch(StockChangeResult::isSuccessful);
    assertStockAndTotalProductCount("NEW", 100, 1);
  }

  @Test
  @DisplayName("기존 상품에 동시 입고하면 증가분이 모두 반영되고 상품명은 유지된다")
  void concurrentStockInDoesNotLoseExistingStock() throws Exception {
    // given
    long id = service.stockIn("OLD", "기존", 10).id();
    var operations = IntStream.range(0, 100).mapToObj(i -> stockInTask("OLD", "다른 이름", 1)).toList();

    // when
    var results = executeConcurrently(operations);

    // then
    assertThat(results).allMatch(StockChangeResult::isSuccessful);
    assertStockAndTotalProductCount("OLD", 110, 1);
    assertThat(service.getStock(id).name()).isEqualTo("기존");
  }

  @Test
  @DisplayName("서로 다른 이름으로 동시 등록해도 상품은 하나이며 입고 수량은 모두 반영된다")
  void competingNewNamesRetainOneSuccessfulNameAndAllStock() throws Exception {
    // given
    var names = IntStream.range(0, 40).mapToObj(i -> "상품 " + i).toList();
    var operations = names.stream().map(name -> stockInTask("NEW", name, 2)).toList();

    // when
    var results = executeConcurrently(operations);

    // then
    assertThat(results).allMatch(StockChangeResult::isSuccessful);
    assertStockAndTotalProductCount("NEW", 80, 1);
    assertThat(service.getStock(productId("NEW")).name()).isIn(names);
  }

  @Test
  @DisplayName("재고 10개에서 7개씩 동시 출고하면 한 건만 성공하고 3개가 남는다")
  void twoCompetingShipmentsCannotOversell() throws Exception {
    // given
    long id = service.stockIn("P1", "상품", 10).id();
    var operations = List.of(stockOutTask(id, 7), stockOutTask(id, 7));

    // when
    var results = executeConcurrently(operations);

    // then
    assertThat(results.stream().filter(StockChangeResult::isSuccessful).count()).isEqualTo(1);
    assertThat(
            results.stream().filter(o -> o.rejectionCode() == ErrorCode.STOCK_UNAVAILABLE).count())
        .isEqualTo(1);
    assertStockAndTotalProductCount("P1", 3, 1);
  }

  @Test
  @DisplayName("재고 100개에서 1개씩 200건 동시 출고하면 100건만 성공한다")
  void twoHundredShipmentsConsumeExactlyOneHundredUnits() throws Exception {
    // given
    long id = service.stockIn("P1", "상품", 100).id();
    var operations = IntStream.range(0, 200).mapToObj(i -> stockOutTask(id, 1)).toList();

    // when
    var results = executeConcurrently(operations);

    // then
    assertThat(results.stream().filter(StockChangeResult::isSuccessful).count()).isEqualTo(100);
    assertThat(
            results.stream().filter(o -> o.rejectionCode() == ErrorCode.STOCK_UNAVAILABLE).count())
        .isEqualTo(100);
    assertStockAndTotalProductCount("P1", 0, 1);
  }

  @Test
  @DisplayName("입출고가 경합해도 최종 재고는 성공한 수량의 합과 일치한다")
  void mixedStockInAndOutSatisfiesSuccessfulQuantityInvariant() throws Exception {
    // given
    long id = service.stockIn("P1", "상품", 20).id();
    List<Callable<StockChangeResult>> operations = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      operations.add(stockOutTask(id, 3));
      operations.add(stockInTask("P1", "상품", 2));
    }

    // when
    var results = executeConcurrently(operations);

    // then
    assertThat(results)
        .allMatch(o -> o.isSuccessful() || o.rejectionCode() == ErrorCode.STOCK_UNAVAILABLE);
    long netStockChange =
        results.stream()
            .filter(StockChangeResult::isSuccessful)
            .mapToLong(StockChangeResult::stockDelta)
            .sum();
    assertStockAndTotalProductCount("P1", 20 + netStockChange, 1);
    assertThat(service.getStock(id).quantity()).isNotNegative();
  }

  @Test
  @DisplayName("서로 다른 상품의 동시 입고는 상품별 재고에 각각 반영된다")
  void differentCodesEachKeepTheirOwnTotal() throws Exception {
    // given
    var operations =
        IntStream.range(0, 100).mapToObj(i -> stockInTask("P" + i % 10, "상품", i % 10 + 1)).toList();

    // when
    var results = executeConcurrently(operations);

    // then
    assertThat(results).allMatch(StockChangeResult::isSuccessful);
    for (int i = 0; i < 10; i++) {
      assertStockAndTotalProductCount("P" + i, (i + 1) * 10L, 10);
    }
  }

  private Callable<StockChangeResult> stockInTask(
      String productCode, String initialName, long incomingQuantity) {
    return () -> {
      try {
        service.stockIn(productCode, initialName, incomingQuantity);
        return StockChangeResult.builder().stockDelta(incomingQuantity).build();
      } catch (BusinessException e) {
        return StockChangeResult.builder().rejectionCode(e.getErrorCode()).build();
      }
    };
  }

  private Callable<StockChangeResult> stockOutTask(long id, long outgoingQuantity) {
    return () -> {
      try {
        service.stockOut(id, outgoingQuantity);
        return StockChangeResult.builder().stockDelta(-outgoingQuantity).build();
      } catch (BusinessException e) {
        return StockChangeResult.builder().rejectionCode(e.getErrorCode()).build();
      }
    };
  }

  private List<StockChangeResult> executeConcurrently(List<Callable<StockChangeResult>> operations)
      throws Exception {
    int workers = Math.min(16, operations.size());
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<StockChangeResult>> futures = new ArrayList<>();
    try {
      for (var operation : operations) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("start gate timed out");
                  }
                  return operation
                      .call(); // Each service call returns after its own transaction commits.
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
      List<StockChangeResult> results = new ArrayList<>();
      List<Throwable> unexpected = new ArrayList<>();
      for (var future : futures) {
        try {
          results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        } catch (Exception e) {
          unexpected.add(e);
        }
      }
      assertThat(unexpected).as("all unexpected worker errors").isEmpty();
      assertThat(results).hasSize(operations.size());
      return results;
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void assertStockAndTotalProductCount(
      String productCode, long expectedStockQuantity, long expectedProductCount) {
    assertThat(service.getStock(productId(productCode)).quantity())
        .as("상품 %s의 최종 재고", productCode)
        .isEqualTo(expectedStockQuantity);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class))
        .as("전체 상품 행 수")
        .isEqualTo(expectedProductCount);
  }

  private long productId(String productCode) {
    return jdbc.queryForObject(
        "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
  }

  @Builder
  private record StockChangeResult(long stockDelta, ErrorCode rejectionCode) {
    boolean isSuccessful() {
      return rejectionCode == null;
    }
  }
}
