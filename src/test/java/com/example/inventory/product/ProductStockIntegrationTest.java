package com.example.inventory.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.inventory.PostgresTestSupport;
import com.example.inventory.product.service.ProductStockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("실제 DB를 사용하는 재고 API 및 에러 응답 검증")
@AutoConfigureMockMvc
class ProductStockIntegrationTest extends PostgresTestSupport {
  @Autowired ProductStockService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  @BeforeEach
  void clean() {
    jdbc.execute("TRUNCATE product RESTART IDENTITY");
  }

  @Test
  @DisplayName("HTTP로 신규 입고 후 출고하고 조회하면 상품 정보와 최종 재고가 일치한다")
  void stockInThenStockOutAndReadThroughHttp() throws Exception {
    var stockInResponse =
        mvc.perform(
                post("/api/products/stock-in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                                {"productCode":"P001","name":"상품 A","quantity":15}
                                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.productCode").value("P001"))
            .andExpect(jsonPath("$.name").value("상품 A"))
            .andExpect(jsonPath("$.quantity").value(15))
            .andReturn();
    long id =
        objectMapper
            .readTree(stockInResponse.getResponse().getContentAsByteArray())
            .get("id")
            .longValue();
    assertThat(id).isPositive();

    mvc.perform(
            post("/api/products/{id}/stock-out", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":4}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.productCode").value("P001"))
        .andExpect(jsonPath("$.name").value("상품 A"))
        .andExpect(jsonPath("$.quantity").value(11));

    mvc.perform(get("/api/products/{id}/stock", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.productCode").value("P001"))
        .andExpect(jsonPath("$.name").value("상품 A"))
        .andExpect(jsonPath("$.quantity").value(11));
    assertThat(jdbc.queryForObject("SELECT quantity FROM product WHERE id = ?", Long.class, id))
        .isEqualTo(11);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("재고보다 많은 수량을 출고하면 409를 반환하고 재고를 유지한다")
  void insufficientStockReturns409WithoutChangingStock() throws Exception {
    // given
    long id = service.stockIn("P001", "상품 A", 15).id();
    var request =
        post("/api/products/{id}/stock-out", id)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"quantity\":16}");

    // when
    var response = mvc.perform(request);

    // then
    response
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STOCK_UNAVAILABLE"));
    assertThat(service.getStock(id).quantity()).isEqualTo(15);
  }

  @Test
  @DisplayName("null 문자가 포함된 상품명은 신규·기존 상품 모두 400으로 거절하며 재고를 변경하지 않는다")
  void nullCharacterInNameIsRejectedForNewAndExistingProducts() throws Exception {
    // given
    long id = service.stockIn("OLD", "상품", 10).id();
    var newProductRequest =
        post("/api/products/stock-in")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"productCode\":\"NEW\",\"name\":\"A\\u0000B\",\"quantity\":1}");
    var existingProductRequest =
        post("/api/products/stock-in")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"productCode\":\"OLD\",\"name\":\"A\\u0000B\",\"quantity\":1}");

    // when
    var newProductResponse = mvc.perform(newProductRequest);
    var existingProductResponse = mvc.perform(existingProductRequest);

    // then
    newProductResponse
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors[0].field").value("name"));
    existingProductResponse
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors[0].field").value("name"));
    assertThat(service.getStock(id).quantity()).isEqualTo(10);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("존재하지 않는 상품의 조회와 출고는 모두 404를 반환한다")
  void unknownProductsReturn404ForReadsAndStockOut() throws Exception {
    // given
    long missingId = Long.MAX_VALUE;
    var readRequest = get("/api/products/{id}/stock", missingId);
    var stockOutRequest =
        post("/api/products/{id}/stock-out", missingId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"quantity\":1}");

    // when
    var readResponse = mvc.perform(readRequest);
    var stockOutResponse = mvc.perform(stockOutRequest);

    // then
    readResponse
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    stockOutResponse
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
  }

  @Test
  @DisplayName("숫자 상품 코드를 상품 ID로 요청하면 404를 반환하고 실제 상품 재고는 유지한다")
  void numericProductCodesDoNotActAsIds() throws Exception {
    // given
    long numericCode = Long.MAX_VALUE;
    long id = service.stockIn(Long.toString(numericCode), "숫자 코드 상품", 10).id();
    var actualIdRequest = get("/api/products/{id}/stock", id);
    var codeAsIdRequest = get("/api/products/{id}/stock", numericCode);
    var stockOutRequest =
        post("/api/products/{id}/stock-out", numericCode)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"quantity\":1}");

    // when
    var actualIdResponse = mvc.perform(actualIdRequest);
    var codeAsIdResponse = mvc.perform(codeAsIdRequest);
    var stockOutResponse = mvc.perform(stockOutRequest);

    // then
    assertThat(id).isNotEqualTo(numericCode);
    actualIdResponse
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.productCode").value(Long.toString(numericCode)));
    codeAsIdResponse
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    stockOutResponse
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    assertThat(service.getStock(id).quantity()).isEqualTo(10);
  }
}
