package git.autoupdateservice.web;

import git.autoupdateservice.service.gitlab.GitlabWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/gitlab")
public class GitlabWebhookController {

    private final GitlabWebhookService gitlabWebhookService;

    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String event,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String ip = IpUtil.clientIp(request);
        var result = gitlabWebhookService.handleWebhook(token, event, body, ip);
        return ResponseEntity.status(result.statusCode()).body(result.body());
    }
}
