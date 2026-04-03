package git.autoupdateservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gitlab.webhook")
public record GitlabProperties(String secret) {}
