package com.codimango.cricket;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class WebAppExceptionMapper implements ExceptionMapper<WebApplicationException> {
    @Override
    public Response toResponse(WebApplicationException exception) {
        int status = exception.getResponse() != null ? exception.getResponse().getStatus() : 500;
        // Only handle 400 here; let 404/405 etc fall through with default if not ApiException
        if (status == 400) {
            String msg = exception.getMessage();
            if (msg == null || msg.isBlank()) msg = "bad request";
            return Response.status(400)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", msg))
                    .build();
        }
        // For other statuses, return JSON error as well to keep contract for 404/409 if framework generates them
        if (status == 404 || status == 409) {
            String msg = exception.getMessage();
            if (msg == null || msg.isBlank()) msg = status == 404 ? "not found" : "conflict";
            return Response.status(status)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", msg))
                    .build();
        }
        return exception.getResponse();
    }
}
