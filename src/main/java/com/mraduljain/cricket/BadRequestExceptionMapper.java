package com.mraduljain.cricket;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class BadRequestExceptionMapper implements ExceptionMapper<BadRequestException> {
    @Override
    public Response toResponse(BadRequestException exception) {
        // For framework-level 400s (e.g., failed param conversion) return JSON error contract
        String msg = exception.getMessage();
        if (msg == null || msg.isBlank()) msg = "bad request";
        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", msg))
                .build();
    }
}
