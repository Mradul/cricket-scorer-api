package com.codimango.cricket;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

// Fallback to ensure any unhandled 400-ish failure returns JSON error contract
// Lower priority than specific mappers via @Provider ordering not guaranteed, but
// having this ensures framework-level binding failures that slip through still return JSON.
@Provider
public class GenericExceptionMapper implements ExceptionMapper<Exception> {
    @Override
    public Response toResponse(Exception exception) {
        // Don't override our own ApiException – it has its own mapper
        if (exception instanceof ApiException) {
            ApiException api = (ApiException) exception;
            return Response.status(api.status)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", api.getMessage()))
                    .build();
        }
        // For any exception that looks like client error, return 400 JSON
        // Check cause chain for Jackson processing
        Throwable t = exception;
        while (t != null) {
            if (t instanceof com.fasterxml.jackson.core.JsonProcessingException) {
                return Response.status(400)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(Map.of("error", "invalid request: " + t.getMessage()))
                        .build();
            }
            t = t.getCause();
        }
        // For other exceptions, return 400 JSON if message present, else 500 JSON
        // This keeps error contract for malformed int, etc.
        String msg = exception.getMessage();
        if (msg == null || msg.isBlank()) msg = "bad request";
        // Heuristic: if it's a WebApplicationException with 400-499, keep that status, else 400
        int status = 400;
        if (exception instanceof jakarta.ws.rs.WebApplicationException) {
            jakarta.ws.rs.WebApplicationException wae = (jakarta.ws.rs.WebApplicationException) exception;
            if (wae.getResponse() != null) {
                int s = wae.getResponse().getStatus();
                if (s >= 400 && s < 500) status = s;
            }
        }
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", msg))
                .build();
    }
}
