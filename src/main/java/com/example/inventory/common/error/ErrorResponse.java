package com.example.inventory.common.error;

import java.util.List;
import lombok.Builder;

@Builder
public record ErrorResponse(String code, String message, List<FieldError> errors) {
  public ErrorResponse {
    errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public static ErrorResponse from(ErrorCode errorCode) {
    return from(errorCode, List.of());
  }

  public static ErrorResponse from(ErrorCode errorCode, List<FieldError> fieldErrors) {
    return ErrorResponse.builder()
        .code(errorCode.name())
        .message(errorCode.message())
        .errors(fieldErrors)
        .build();
  }

  // The HTTP status for framework errors comes from the framework response itself.
  public static ErrorResponse httpError(List<FieldError> fieldErrors) {
    return ErrorResponse.builder()
        .code("HTTP_ERROR")
        .message("HTTP 요청을 처리할 수 없습니다.")
        .errors(fieldErrors)
        .build();
  }

  @Builder
  public record FieldError(String field, String message) {}
}
