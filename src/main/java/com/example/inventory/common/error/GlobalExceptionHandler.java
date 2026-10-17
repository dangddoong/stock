package com.example.inventory.common.error;

import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import org.hibernate.exception.JDBCConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ErrorResponse> handleBusiness(BusinessException exception) {
    return ResponseEntity.status(exception.getErrorCode().status())
        .body(ErrorResponse.from(exception.getErrorCode()));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
    var errors =
        exception.getConstraintViolations().stream()
            .map(
                violation ->
                    ErrorResponse.FieldError.builder()
                        .field(lastPathSegment(violation.getPropertyPath().toString()))
                        .message(violation.getMessage())
                        .build())
            .toList();
    return ResponseEntity.badRequest().body(ErrorResponse.from(ErrorCode.INVALID_REQUEST, errors));
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<ErrorResponse.FieldError> errors = List.of();
    if (exception instanceof MethodArgumentNotValidException validation) {
      errors =
          validation.getBindingResult().getFieldErrors().stream()
              .map(
                  error ->
                      ErrorResponse.FieldError.builder()
                          .field(error.getField())
                          .message(error.getDefaultMessage())
                          .build())
              .toList();
    } else if (exception instanceof HandlerMethodValidationException validation
        && !validation.isForReturnValue()) {
      errors =
          validation.getParameterValidationResults().stream()
              .flatMap(
                  result ->
                      result instanceof ParameterErrors fields
                          ? fields.getFieldErrors().stream()
                              .map(
                                  error ->
                                      ErrorResponse.FieldError.builder()
                                          .field(error.getField())
                                          .message(error.getDefaultMessage())
                                          .build())
                          : result.getResolvableErrors().stream()
                              .map(
                                  error ->
                                      ErrorResponse.FieldError.builder()
                                          .field(result.getMethodParameter().getParameterName())
                                          .message(error.getDefaultMessage())
                                          .build()))
              .toList();
    }
    if (status.is5xxServerError()) {
      log.error("HTTP request processing failed", exception);
    }
    HttpHeaders responseHeaders = new HttpHeaders();
    responseHeaders.putAll(headers);
    responseHeaders.setContentType(MediaType.APPLICATION_JSON);
    return super.handleExceptionInternal(
        exception, toFrameworkErrorResponse(status, errors), responseHeaders, status, request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
    ErrorCode errorCode =
        isTemporaryDatabaseFailure(exception)
            ? ErrorCode.SERVICE_UNAVAILABLE
            : ErrorCode.INTERNAL_SERVER_ERROR;
    log.error("Request processing failed: {}", errorCode, exception);
    return ResponseEntity.status(errorCode.status()).body(ErrorResponse.from(errorCode));
  }

  private ErrorResponse toFrameworkErrorResponse(
      HttpStatusCode httpStatus, List<ErrorResponse.FieldError> fieldErrors) {
    return switch (httpStatus.value()) {
      case 400 -> ErrorResponse.from(ErrorCode.INVALID_REQUEST, fieldErrors);
      case 404 -> ErrorResponse.from(ErrorCode.RESOURCE_NOT_FOUND, fieldErrors);
      case 405 -> ErrorResponse.from(ErrorCode.METHOD_NOT_ALLOWED, fieldErrors);
      case 415 -> ErrorResponse.from(ErrorCode.UNSUPPORTED_MEDIA_TYPE, fieldErrors);
      case 503 -> ErrorResponse.from(ErrorCode.SERVICE_UNAVAILABLE, fieldErrors);
      default ->
          httpStatus.is5xxServerError()
              ? ErrorResponse.from(ErrorCode.INTERNAL_SERVER_ERROR, fieldErrors)
              : ErrorResponse.httpError(fieldErrors);
    };
  }

  private boolean isTemporaryDatabaseFailure(Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof DataAccessResourceFailureException
          || cause instanceof CannotAcquireLockException
          || cause instanceof QueryTimeoutException
          || cause instanceof TransactionTimedOutException
          || cause instanceof JDBCConnectionException
          || cause instanceof SQLTransientConnectionException
          || cause instanceof SQLTimeoutException
          || cause instanceof jakarta.persistence.QueryTimeoutException
          || cause instanceof jakarta.persistence.LockTimeoutException) {
        return true;
      }
      if (cause instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (state != null
            && (state.startsWith("08")
                || state.equals("55P03")
                || state.equals("57014")
                || state.equals("40P01"))) {
          return true;
        }
      }
      if (cause == cause.getCause()) {
        break;
      }
    }
    return false;
  }

  private String lastPathSegment(String path) {
    return path.substring(path.lastIndexOf('.') + 1);
  }
}
