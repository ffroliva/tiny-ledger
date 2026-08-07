package com.ffroliva.tinyledger.platform;

import com.ffroliva.tinyledger.shared.error.ErrorCode;
import com.ffroliva.tinyledger.shared.error.TinyLedgerException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Spec §6.5: the error catalogue, once, for every module — {@code platform} is composition glue rather than
 * an application module (§3/§4.5, {@code spring.modulith.detection-strategy=explicitly-annotated}), which is
 * what lets it name exception types from both {@code ledger} and {@code shared} the way {@code config} names
 * services from both.
 *
 * <p>It outranks Boot's own {@code ProblemDetailsExceptionHandler} deliberately: that one is {@code @Order(0)}
 * and would otherwise answer the validation failures with {@code about:blank}, which carries no
 * machine-readable code (§7.1's one divergence from Starling).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ErrorHandlingAdvice {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandlingAdvice.class);

    /**
     * §6.5's one 400. {@code ConstraintViolationException} is the request-parameter case: the generated
     * interfaces are {@code @Validated}, so an implementing controller is method-validation proxied and its
     * {@code @Min}/{@code @Max} parameter bounds fail through AOP rather than through Spring MVC's own
     * {@code HandlerMethodValidationException} — both are listed so the answer does not depend on which.
     */
    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class,
        ConstraintViolationException.class
    })
    ResponseEntity<ProblemDetail> malformed() {
        return problem(HttpStatus.BAD_REQUEST, "/errors/invalid-amount", "Invalid amount");
    }

    /** §6.5: one catalogue, one translation. The code carries status, type and title. */
    @ExceptionHandler(TinyLedgerException.class)
    ResponseEntity<ProblemDetail> catalogued(TinyLedgerException exception) {
        ErrorCode code = exception.code();
        return problem(HttpStatus.valueOf(code.status()), code.type(), code.title());
    }

    /**
     * Everything else. An exception that already carries its own answer keeps it — the 422 refusals and the
     * 501 auditor pair the adapters raise as {@link org.springframework.web.ErrorResponseException}, and
     * Spring's own 404/405/415, which this handler would otherwise flatten into 500s. Anything left is a
     * genuine surprise, and nothing internal crosses the boundary with it: no message, no stack trace, no
     * identifiers (§6.5).
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception) {
        if (exception instanceof ErrorResponse declared) {
            // §6.5: the declared headers are part of the answer — 405's Allow, 503's Retry-After.
            return ResponseEntity.status(declared.getStatusCode())
                    .headers(declared.getHeaders())
                    .body(traced(declared.getBody()));
        }
        // The body says nothing, so the log has to: this is the only record a 500 ever leaves.
        log.error("unhandled exception at the API boundary", exception);
        return respond(
                HttpStatus.INTERNAL_SERVER_ERROR, traced(ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR)));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String title) {
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setType(URI.create(type));
        body.setTitle(title);
        return respond(status, traced(body));
    }

    private static ResponseEntity<ProblemDetail> respond(HttpStatusCode status, ProblemDetail body) {
        return ResponseEntity.status(status).body(body);
    }

    /**
     * §6.5/§6.6: the correlating id. {@link FapiInteractionIdFilter} is what puts it there.
     *
     * <p>Read from {@code interactionId} and published as {@code traceId}, and the asymmetry is
     * deliberate: since §14 step 9 part 2, Micrometer Tracing owns the MDC key {@code traceId} and would
     * overwrite this value, while the JSON property name is a published part of the error contract and is
     * left alone. §6.5 records that the name is now a misnomer.
     */
    private static ProblemDetail traced(ProblemDetail body) {
        String interactionId = MDC.get(FapiInteractionIdFilter.MDC_KEY);
        if (interactionId != null) body.setProperty("traceId", interactionId);
        return body;
    }
}
