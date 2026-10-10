package com.stocks.security;

import com.stocks.common.error.ErrorCode;
import com.stocks.common.error.Problem;
import com.stocks.common.web.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Spring Security's {@code StrictHttpFirewall} rejects some header values lazily, when a controller first reads them
 * (e.g. a User-Agent with control characters). That is the client's fault: 400 {@code VALIDATION_FAILED}, not the
 * catch-all 500 of {@code ProblemDetailsAdvice}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FirewallRejectionAdvice {

  @ExceptionHandler(RequestRejectedException.class)
  public ResponseEntity<Problem> rejected(RequestRejectedException e, HttpServletRequest request) {
    String requestId = RequestIds.of(request);
    Problem problem = Problem.of(ErrorCode.VALIDATION_FAILED, 400, "The request has a header value that is not "
        + "allowed (control characters).", null, List.of(), null, requestId);
    return ResponseEntity.status(400).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
  }
}
