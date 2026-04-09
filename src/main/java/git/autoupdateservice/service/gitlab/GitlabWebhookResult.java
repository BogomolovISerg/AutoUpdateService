package git.autoupdateservice.service.gitlab;

import java.util.Map;

public record GitlabWebhookResult(int statusCode, Map<String, Object> body) {

    public static GitlabWebhookResult ok(Map<String, Object> body) {
        return new GitlabWebhookResult(200, body);
    }

    public static GitlabWebhookResult unauthorized(Map<String, Object> body) {
        return new GitlabWebhookResult(401, body);
    }
}
