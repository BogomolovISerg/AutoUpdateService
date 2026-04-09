package git.autoupdateservice.service.gitlab;

public record GitlabPushEvent(
        String eventHeader,
        String objectKind,
        String eventName,
        String projectPath,
        Long projectId,
        String projectName,
        String ref,
        String branch,
        String beforeSha,
        String commitSha,
        String checkoutSha,
        String pusherName,
        String pusherLogin,
        String userEmail,
        Integer totalCommitsCount,
        String authorName,
        String authorLogin,
        String comment,
        String sourceKey
) {
}
