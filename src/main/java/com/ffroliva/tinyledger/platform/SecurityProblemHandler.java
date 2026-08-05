package com.ffroliva.tinyledger.platform;

import com.ffroliva.tinyledger.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §6.5: the two errors the security chain writes itself. Both are produced before {@code DispatcherServlet}
 * runs, so {@link ErrorHandlingAdvice} — a {@code @RestControllerAdvice} — never sees them and cannot
 * translate them. Without this, the plan's "one catalogue" goal would be contradicted by the two most
 * frequent responses in {@code full}: the measured default 401 is an empty body, and the default 403 is
 * {@code BasicErrorController}'s shape, which echoes the request {@code path} — an internal identifier
 * §6.5 forbids crossing the boundary. {@code docs/api/openapi.yaml} already promises clients
 * {@code application/problem+json} with a {@code type} for both.
 *
 * <p>Lives in {@code platform}, not {@code config}: {@code config} already imports the business modules, so
 * {@code config → platform} closes no loop, while {@code platform → config} would.
 */
@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    public SecurityProblemHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(response, ErrorCode.UNAUTHENTICATED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, ErrorCode.FORBIDDEN);
    }

    private void write(HttpServletResponse response, ErrorCode code) throws IOException {
        ProblemDetail body = ProblemDetail.forStatus(code.status());
        body.setType(URI.create(code.type()));
        body.setTitle(code.title());
        // §6.5/§6.6: the same correlating id ErrorHandlingAdvice attaches. Requires the interaction-id
        // filter to run BEFORE the security chain — see Task 7.
        String traceId = MDC.get("traceId");
        if (traceId != null) body.setProperty("traceId", traceId);
        response.setStatus(code.status());
        response.setContentType("application/problem+json");
        mapper.writeValue(response.getOutputStream(), body);
    }
}
