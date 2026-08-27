package com.mraduljain.cricket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

// Quarkus REST (quarkus-rest-jackson) uses @ServerExceptionMapper, not JAX-RS ExceptionMapper
// This ensures framework-level binding failures (invalid enum, malformed int) return JSON {"error":...}
@ApplicationScoped
public class RestExceptionMappers {

    @ServerExceptionMapper
    public RestResponse<Map<String, String>> mapJsonProcessing(JsonProcessingException ex) {
        return RestResponse.status(RestResponse.Status.BAD_REQUEST,
                Map.of("error", "invalid request: " + ex.getOriginalMessage()));
    }

    @ServerExceptionMapper
    public RestResponse<Map<String, String>> mapJsonMapping(JsonMappingException ex) {
        return RestResponse.status(RestResponse.Status.BAD_REQUEST,
                Map.of("error", "invalid request: " + ex.getOriginalMessage()));
    }

    @ServerExceptionMapper
    public RestResponse<Map<String, String>> mapBadRequest(BadRequestException ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) msg = "bad request";
        return RestResponse.status(RestResponse.Status.BAD_REQUEST,
                Map.of("error", msg));
    }

    @ServerExceptionMapper
    public RestResponse<Map<String, String>> mapWebApp(WebApplicationException ex) {
        int status = ex.getResponse() != null ? ex.getResponse().getStatus() : 500;
        if (status >= 400 && status < 500) {
            String msg = ex.getMessage();
            if (msg == null || msg.isBlank()) msg = status == 404 ? "not found" : status == 409 ? "conflict" : "bad request";
            return RestResponse.status(RestResponse.Status.fromStatusCode(status),
                    Map.of("error", msg));
        }
        return null; // let framework handle 500s
    }

    @ServerExceptionMapper
    public RestResponse<Map<String, String>> mapException(Exception ex) {
        // Let our own ApiException be handled by its dedicated JAX-RS mapper (preserves 404/409)
        if (ex instanceof ApiException) return null;
        // Ensure any unhandled client-side failure returns JSON error contract
        Throwable t = ex;
        while (t != null) {
            if (t instanceof JsonProcessingException) {
                return RestResponse.status(RestResponse.Status.BAD_REQUEST,
                        Map.of("error", "invalid request: " + t.getMessage()));
            }
            t = t.getCause();
        }
        // For any other exception that would otherwise result in empty 400 (e.g., NumberFormat, IllegalArgument)
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) msg = "bad request";
        int status = 400;
        if (ex instanceof WebApplicationException) {
            WebApplicationException wae = (WebApplicationException) ex;
            if (wae.getResponse() != null) {
                int s = wae.getResponse().getStatus();
                if (s >= 400 && s < 500) status = s;
                else status = 500;
            }
        }
        // Only map client errors to JSON here; let 500s use framework default unless Jackson-related
        if (status >= 400 && status < 500) {
            return RestResponse.status(RestResponse.Status.fromStatusCode(status),
                    Map.of("error", msg));
        }
        return null;
    }
}
