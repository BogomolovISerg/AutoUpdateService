package git.autoupdateservice.web;

import git.autoupdateservice.service.SmokeObjectListService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/test-objects")
public class SmokeTestObjectsController {

    private final SmokeObjectListService smokeObjectListService;

    @Value("${app.test-objects.api-token:}")
    private String apiToken;

    @GetMapping("/connection")
    public ResponseEntity<Map<String, Object>> connectionSettings(
            @RequestHeader(value = "X-AutoUpdate-Token", required = false) String headerToken,
            @RequestParam(value = "token", required = false) String queryToken
    ) {
        if (!authorized(headerToken, queryToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }

        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.putAll(smokeObjectListService.testConnectionSettings());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse(e));
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> storeObjectList(
            @RequestHeader(value = "X-AutoUpdate-Token", required = false) String headerToken,
            @RequestParam(value = "token", required = false) String queryToken,
            @RequestBody String payload,
            HttpServletRequest request
    ) {
        if (!authorized(headerToken, queryToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }

        try {
            SmokeObjectListService.StoreResult result = smokeObjectListService.storeLatestObjectList(
                    payload,
                    IpUtil.clientIp(request),
                    "external-test-objects"
            );

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("saved", result.saved());
            response.put("hasError", result.hasError());
            response.put("errorText", result.errorText());
            response.put("path", result.path().toString());
            response.put("objectCounts", result.objectCounts());
            response.put("usingPrevious", !result.saved());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse(e));
        }
    }

    private boolean authorized(String headerToken, String queryToken) {
        if (!StringUtils.hasText(apiToken)) {
            return true;
        }
        String provided = StringUtils.hasText(headerToken) ? headerToken : queryToken;
        return apiToken.equals(provided);
    }

    private Map<String, Object> errorResponse(Exception e) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("saved", false);
        response.put("error", "test_objects_api_failed");
        response.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return response;
    }
}
