package com.kakao.kakao_test.controller;

import com.kakao.kakao_test.dto.IncidentDetailDto;
import com.kakao.kakao_test.service.IncidentService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class IncidentController {
    private final IncidentService incidentService;

    @GetMapping("/api/incidents/{id}")
    public ResponseEntity<IncidentDetailDto> getIncident(
            @PathVariable String id,
            @RequestHeader(value = "X-Incident-Edge", required = false) String edgeHeader,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String before,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        Long beforeId = null;
        if (before != null) {
            try {
                beforeId = Long.valueOf(before);
                if (beforeId < 1) return ResponseEntity.badRequest().build();
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        return ResponseEntity.of(incidentService.getDetail(id, edgeHeader, authorization, beforeId));
    }
}
