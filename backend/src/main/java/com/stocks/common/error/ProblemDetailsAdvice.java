package com.stocks.common.error;

import com.stocks.common.web.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Renders every exception that leaves a controller as an RFC 9457 {@code application/problem+json} body with a
 * stable {@link ErrorCode} (§6.5).
 *
 * <ul>
 *   <li>{@link ApiException} → its code, status, detail, hint, fields and {@code retryAfterS} (+ {@code Retry-After}).</li>
 *   <li>Every 401 carries {@code WWW-Authenticate: Bearer realm="stocks"} (§17.1, RFC 9110 §15.5.2), e.g.
 *       {@code BAD_CREDENTIALS} from login or {@code UNAUTHENTICATED} from a controller, like the 401s the security
 *       layer writes itself.</li>
 *   <li>Malformed JSON, wrong types, unknown enum values, bean-validation failures, bad parameters
 *       → 400 {@code VALIDATION_FAILED} with {@code fields}.</li>
 *   <li>Other Spring MVC exceptions ({@link ErrorResponse}) keep their HTTP status; the code is derived from it
 *       (404 → {@code NOT_FOUND}, 401 → {@code UNAUTHENTICATED}, 403 → {@code FORBIDDEN}, other 4xx →
 *       {@code VALIDATION_FAILED}).</li>
 *   <li>Spring Security exceptions are re-thrown so the security filter chain's entry point and access-denied
 *       handler answer them.</li>
 *   <li>Anything else → 500 {@code INTERNAL_ERROR}; the body carries only the {@code requestId}, never a message or a
 *       stack trace. The exception is logged with that id.</li>
 * </ul>
 *
 * Extension point: WS5's MCP error mapping ({@code McpErrors}) reuses {@link Problem#of(ApiException, String)} so
 * REST and MCP agree on codes.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProblemDetailsAdvice {

  private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

  /**
   * The {@code WWW-Authenticate} challenge of every 401 (§17.1; Phase 2 adds {@code resource_metadata}). Lives here
   * because {@code common} may not depend on {@code security} (§5.2 rule 8); {@code security.ProblemWriter} reuses it.
   */
  public static final String BEARER_CHALLENGE = "Bearer realm=\"stocks\"";

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Problem> apiException(ApiException e, HttpServletRequest request) {
    if (e.status() >= 500 && e.code() != ErrorCode.NOT_IMPLEMENTED) {
      log.warn("{} {} -> {}: {}", request.getMethod(), request.getRequestURI(), e.code(), e.getMessage());
    }
    return respond(Problem.of(e, RequestIds.of(request)));
  }

  @ExceptionHandler(BindException.class) // includes MethodArgumentNotValidException
  public ResponseEntity<Problem> bindException(BindException e, HttpServletRequest request) {
    List<ProblemField> fields = new ArrayList<>();
    for (FieldError fe : e.getBindingResult().getFieldErrors()) {
      fields.add(new ProblemField(fe.getField(), ErrorCode.VALIDATION_FAILED.name(), fe.getDefaultMessage()));
    }
    e.getBindingResult().getGlobalErrors().forEach(ge ->
        fields.add(new ProblemField(ge.getObjectName(), ErrorCode.VALIDATION_FAILED.name(), ge.getDefaultMessage())));
    return validation(request, describe(fields), fields);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<Problem> methodValidation(HandlerMethodValidationException e, HttpServletRequest request) {
    List<ProblemField> fields = new ArrayList<>();
    e.getParameterValidationResults().forEach(r -> {
      String name = r.getMethodParameter().getParameterName();
      r.getResolvableErrors().forEach(err ->
          fields.add(new ProblemField(name, ErrorCode.VALIDATION_FAILED.name(), err.getDefaultMessage())));
    });
    return validation(request, describe(fields), fields);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<Problem> constraintViolation(ConstraintViolationException e, HttpServletRequest request) {
    List<ProblemField> fields = e.getConstraintViolations().stream()
        .map(v -> new ProblemField(lastNode(v.getPropertyPath().toString()), ErrorCode.VALIDATION_FAILED.name(),
            v.getMessage()))
        .toList();
    return validation(request, describe(fields), fields);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Problem> notReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
    Throwable cause = e.getMostSpecificCause();
    if (cause instanceof DatabindException de && !de.getPath().isEmpty()) {
      String field = de.getPath().stream()
          .map(ref -> ref.getPropertyName() != null ? ref.getPropertyName() : "[" + ref.getIndex() + "]")
          .collect(Collectors.joining(".")).replace(".[", "[");
      String message = "Invalid value";
      if (cause instanceof InvalidFormatException ife && ife.getTargetType() != null
          && ife.getTargetType().isEnum()) {
        message = "Must be one of " + enumValues(ife.getTargetType());
      } else if (cause instanceof MismatchedInputException mie && mie.getTargetType() != null) {
        message = "Expected " + simpleTypeName(mie.getTargetType());
      }
      List<ProblemField> fields = List.of(new ProblemField(field, ErrorCode.VALIDATION_FAILED.name(), message));
      return validation(request, "Invalid value for '" + field + "': " + message, fields);
    }
    return validation(request, "Malformed JSON request body", List.of());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Problem> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
    String message = e.getRequiredType() != null && e.getRequiredType().isEnum()
        ? "Must be one of " + enumValues(e.getRequiredType())
        : "Invalid value";
    if (e.getMostSpecificCause() instanceof IllegalArgumentException iae && iae.getMessage() != null
        && e.getRequiredType() != null && e.getRequiredType().isEnum()) {
      message = iae.getMessage();
    }
    List<ProblemField> fields = List.of(new ProblemField(e.getName(), ErrorCode.VALIDATION_FAILED.name(), message));
    return validation(request, "Invalid value for '" + e.getName() + "': " + message, fields);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Problem> missingParameter(MissingServletRequestParameterException e,
                                                  HttpServletRequest request) {
    List<ProblemField> fields = List.of(
        new ProblemField(e.getParameterName(), ErrorCode.VALIDATION_FAILED.name(), "Required"));
    return validation(request, "Missing parameter '" + e.getParameterName() + "'", fields);
  }

  /**
   * The client went away while its response was still being written (an SSE stream closed by its client, §19.1, or
   * an aborted download): nobody is left to answer, so nothing is written and it is not an error (Spring's own
   * {@code ResponseEntityExceptionHandler} does the same). A {@code null} entity marks the request handled.
   */
  @ExceptionHandler(AsyncRequestNotUsableException.class)
  public ResponseEntity<Problem> clientGone(AsyncRequestNotUsableException e, HttpServletRequest request) {
    log.debug("Client gone [requestId={}] {} {}: {}", RequestIds.of(request), request.getMethod(),
        request.getRequestURI(), e.getMessage());
    return null;
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Problem> other(Exception e, HttpServletRequest request) throws Exception {
    if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
      throw e; // let Spring Security's ExceptionTranslationFilter answer (entry point or access-denied handler)
    }
    String requestId = RequestIds.of(request);
    if (e instanceof ErrorResponse er) {
      HttpStatusCode status = er.getStatusCode();
      if (status.is4xxClientError()) {
        ErrorCode code = switch (status.value()) {
          case 401 -> ErrorCode.UNAUTHENTICATED;
          case 403 -> ErrorCode.FORBIDDEN;
          case 404 -> ErrorCode.NOT_FOUND;
          default -> ErrorCode.VALIDATION_FAILED; // 400, 405, 406, 413, 415 …: keep the HTTP status
        };
        String detail = er.getBody().getDetail();
        return respond(Problem.of(code, status.value(), detail, null, List.of(), null, requestId),
            er.getHeaders()); // e.g. Allow on 405
      }
    }
    log.error("Unhandled exception [requestId={}] {} {}", requestId, request.getMethod(), request.getRequestURI(), e);
    return respond(Problem.of(ErrorCode.INTERNAL_ERROR, 500, null, null, List.of(), null, requestId));
  }

  private ResponseEntity<Problem> validation(HttpServletRequest request, String detail, List<ProblemField> fields) {
    return respond(Problem.of(ErrorCode.VALIDATION_FAILED, 400, detail, null, fields, null, RequestIds.of(request)));
  }

  private static ResponseEntity<Problem> respond(Problem problem) {
    return respond(problem, HttpHeaders.EMPTY);
  }

  private static ResponseEntity<Problem> respond(Problem problem, HttpHeaders extraHeaders) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(problem.status())
        .headers(h -> h.addAll(extraHeaders))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON);
    if (problem.retryAfterS() != null) {
      builder.header(HttpHeaders.RETRY_AFTER, Long.toString(problem.retryAfterS()));
    }
    if (problem.requestId() != null) {
      builder.header(RequestIds.HEADER, problem.requestId());
    }
    if (problem.status() == 401 && !extraHeaders.containsHeader(HttpHeaders.WWW_AUTHENTICATE)) {
      builder.header(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
    }
    return builder.body(problem);
  }

  private static String describe(List<ProblemField> fields) {
    if (fields.isEmpty()) {
      return "Validation failed";
    }
    return fields.stream()
        .map(f -> f.field() + ": " + f.message())
        .collect(Collectors.joining("; "));
  }

  private static String lastNode(String path) {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? path : path.substring(dot + 1);
  }

  private static String enumValues(Class<?> type) {
    Object[] constants = type.getEnumConstants();
    List<String> values = new ArrayList<>();
    for (Object c : constants) {
      values.add(c instanceof com.stocks.common.domain.WireEnum w ? w.wireValue() : ((Enum<?>) c).name());
    }
    return values.toString();
  }

  private static String simpleTypeName(Class<?> type) {
    if (type == java.math.BigDecimal.class) {
      return "a decimal string, e.g. \"227.40\"";
    }
    if (Number.class.isAssignableFrom(type) || type.isPrimitive() && type != boolean.class) {
      return "a number";
    }
    if (type == Boolean.class || type == boolean.class) {
      return "a boolean";
    }
    if (type == String.class) {
      return "a string";
    }
    if (java.time.temporal.Temporal.class.isAssignableFrom(type)) {
      return "an ISO-8601 " + (type == java.time.LocalDate.class ? "date (YYYY-MM-DD)" : "timestamp");
    }
    return "a value of type " + type.getSimpleName();
  }
}
