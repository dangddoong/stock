package com.example.inventory.product;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.inventory.common.error.BusinessException;
import com.example.inventory.common.error.ErrorCode;
import com.example.inventory.product.controller.ProductStockController;
import com.example.inventory.product.service.ProductStockService;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("잘못된 요청에 대한 에러 응답")
@WebMvcTest(ProductStockController.class)
class ProductStockControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean ProductStockService service;

  @DisplayName("입고 요청의 필수값·형식이 잘못되면 400으로 거절하고 서비스를 호출하지 않는다")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @MethodSource("invalidStockInBodies")
  void rejectsInvalidStockIn(String body) throws Exception {
    // given
    var request =
        post("/api/products/stock-in").contentType(MediaType.APPLICATION_JSON).content(body);

    // when
    var response = mvc.perform(request);

    // then
    response
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(content().string(not(containsString("Exception"))));
    verifyNoInteractions(service);
  }

  static Stream<String> invalidStockInBodies() {
    Stream<String> malformedOrIncompleteBodies =
        Stream.of(
            "{}",
            "null",
            "[]",
            "{",
            "",
            "{\"productCode\":",
            "{\"quantity\":secret}",
            "{\"productCode\":\"P1\",\"name\":\"상품\",\"quantity\":1} {}",
            "{\"productCode\":\"P1\",\"name\":\"상품\",\"quantity\":1} garbage",
            "{\"productCode\":\"P1\",\"name\":\"상품\"}",
            "{\"name\":\"상품\",\"quantity\":1}",
            "{\"productCode\":\"P1\",\"quantity\":1}");
    Stream<String> invalidProductCodeBodies =
        Stream.of("", " ", " P1", "P1 ", "P 1", "-P1", "_P1", "P.1", "상품", "X".repeat(51))
            .map(code -> "{\"productCode\":\"" + code + "\",\"name\":\"상품\",\"quantity\":1}");
    Stream<String> invalidNameBodies =
        Stream.of("", "  ", "X".repeat(201))
            .map(name -> "{\"productCode\":\"P1\",\"name\":\"" + name + "\",\"quantity\":1}");
    Stream<String> invalidQuantityBodies =
        invalidQuantities()
            .map(
                quantity ->
                    "{\"productCode\":\"P1\",\"name\":\"상품\",\"quantity\":" + quantity + "}");

    return Stream.of(
            malformedOrIncompleteBodies,
            invalidProductCodeBodies,
            invalidNameBodies,
            invalidQuantityBodies)
        .flatMap(bodies -> bodies);
  }

  static Stream<String> invalidQuantities() {
    return Stream.of(
        "null",
        "0",
        "-1",
        "1.5",
        "1.0",
        "1e1",
        "\"10\"",
        "\"abc\"",
        "\"\"",
        "9223372036854775808",
        "-9223372036854775809",
        "true",
        "{}",
        "[]");
  }

  @DisplayName("출고 수량이 양의 정수가 아니면 400으로 거절한다")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @MethodSource("invalidQuantities")
  void rejectsInvalidStockOutQuantities(String quantity) throws Exception {
    // given
    var request =
        post("/api/products/42/stock-out")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"quantity\":" + quantity + "}");

    // when
    var response = mvc.perform(request);

    // then
    response
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors").isArray());
    verifyNoInteractions(service);
  }

  @DisplayName("상품명에 null 문자가 포함되면 name 필드 오류로 거절한다")
  @Test
  void rejectsNullCharacterInNameBeforeCallingService() throws Exception {
    // given
    var request =
        post("/api/products/stock-in")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"productCode\":\"P1\",\"name\":\"A\\u0000B\",\"quantity\":1}");

    // when
    var response = mvc.perform(request);

    // then
    response
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors[0].field").value("name"));
    verifyNoInteractions(service);
  }

  @DisplayName("출고 수량이 누락되면 quantity 필드 오류를 반환한다")
  @Test
  void missingQuantityProducesFieldError() throws Exception {
    // given
    var request =
        post("/api/products/42/stock-out").contentType(MediaType.APPLICATION_JSON).content("{}");

    // when
    var response = mvc.perform(request);

    // then
    response
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("quantity"));
    verifyNoInteractions(service);
  }

  @DisplayName("상품 ID가 0 이하이면 조회와 출고를 모두 거절한다")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @ValueSource(longs = {0, -1, Long.MIN_VALUE})
  void rejectsNonPositiveIdsOnBothPathEndpoints(long id) throws Exception {
    // given
    var readRequest = get("/api/products/{id}/stock", id);
    var stockOutRequest =
        post("/api/products/{id}/stock-out", id)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"quantity\":1}");

    // when
    var readResponse = mvc.perform(readRequest);
    var stockOutResponse = mvc.perform(stockOutRequest);

    // then
    readResponse
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors[0].field").value("id"));
    stockOutResponse
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("id"));
    verifyNoInteractions(service);
  }

  @DisplayName("상품 ID가 long 형식이 아니면 조회와 출고를 모두 거절한다")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @ValueSource(
      strings = {"P001", "1.5", "1e1", "상품", "9223372036854775808", "-9223372036854775809"})
  void rejectsMalformedIdsOnBothPathEndpoints(String id) throws Exception {
    // given
    var readRequest = get("/api/products/{id}/stock", id);
    var stockOutRequest =
        post("/api/products/{id}/stock-out", id)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"quantity\":1}");

    // when
    var readResponse = mvc.perform(readRequest);
    var stockOutResponse = mvc.perform(stockOutRequest);

    // then
    readResponse
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    stockOutResponse
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    verifyNoInteractions(service);
  }

  @DisplayName("메서드·미디어 타입·경로·응답 형식 오류를 구분해 반환한다")
  @Test
  void preservesHttpStatusesAndDistinguishesUnknownPath() throws Exception {
    // given
    var unsupportedMethodRequest =
        put("/api/products/stock-in").contentType(MediaType.APPLICATION_JSON).content("{}");
    var unsupportedMediaTypeRequest =
        post("/api/products/stock-in").contentType(MediaType.TEXT_PLAIN).content("{}");
    var unknownPathRequest = get("/unknown-path");
    var unacceptableResponseRequest =
        get("/api/products/42/stock").accept(MediaType.APPLICATION_XML);

    // when
    var unsupportedMethodResponse = mvc.perform(unsupportedMethodRequest);
    var unsupportedMediaTypeResponse = mvc.perform(unsupportedMediaTypeRequest);
    var unknownPathResponse = mvc.perform(unknownPathRequest);
    var unacceptableResponseResponse = mvc.perform(unacceptableResponseRequest);

    // then
    unsupportedMethodResponse
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().exists("Allow"))
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    unsupportedMediaTypeResponse
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    unknownPathResponse
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    unacceptableResponseResponse
        .andExpect(status().isNotAcceptable())
        .andExpect(jsonPath("$.code").value("HTTP_ERROR"))
        .andExpect(jsonPath("$.errors").isArray());
    verifyNoInteractions(service);
  }

  @DisplayName("출고 업무 예외를 해당 HTTP 상태와 오류 코드로 반환한다")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @MethodSource("businessFailures")
  void mapsBusinessErrors(ErrorCode code, int httpStatus) throws Exception {
    // given
    given(service.stockOut(42L, 1L)).willThrow(new BusinessException(code));
    var request =
        post("/api/products/42/stock-out")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"quantity\":1}");

    // when
    var response = mvc.perform(request);

    // then
    response
        .andExpect(status().is(httpStatus))
        .andExpect(jsonPath("$.code").value(code.name()))
        .andExpect(jsonPath("$.errors").isEmpty());
  }

  static Stream<Arguments> businessFailures() {
    return Stream.of(
        Arguments.of(ErrorCode.PRODUCT_NOT_FOUND, 404),
        Arguments.of(ErrorCode.STOCK_UNAVAILABLE, 409));
  }
}
