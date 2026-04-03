package git.autoupdateservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import git.autoupdateservice.config.GitlabApiProperties;
import git.autoupdateservice.config.GitChangeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class GitlabChangesService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};

    private final GitlabApiProperties gitlabApiProperties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public FetchResult fetchFullChanges(String projectPath, String fromSha, String toSha) throws Exception {
        if (!StringUtils.hasText(projectPath)) {
            throw new IllegalArgumentException("projectPath is empty");
        }
        if (!StringUtils.hasText(toSha)) {
            throw new IllegalArgumentException("toSha is empty");
        }
        if (!StringUtils.hasText(gitlabApiProperties.baseUrl())) {
            throw new IllegalStateException("gitlab.api.base-url is not configured");
        }
        if (!StringUtils.hasText(gitlabApiProperties.token())) {
            throw new IllegalStateException("gitlab.api.token is not configured");
        }

        if (!StringUtils.hasText(fromSha) || isZeroSha(fromSha)) {
            List<ChangedFile> files = fetchCommitDiff(projectPath, toSha);
            return new FetchResult(files, 1, true, true, "commit-diff-only");
        }

        CompareData compareData = fetchCompare(projectPath, fromSha, toSha);
        LinkedHashMap<String, ChangedFile> merged = new LinkedHashMap<>();
        for (ChangedFile f : compareData.files()) {
            merged.put(keyOf(f), f);
        }

        boolean usedCommitFallback = false;
        if (compareData.compareTimedOut() || merged.isEmpty()) {
            usedCommitFallback = true;
            for (String commitSha : compareData.commitShas()) {
                for (ChangedFile f : fetchCommitDiff(projectPath, commitSha)) {
                    merged.putIfAbsent(keyOf(f), f);
                }
            }
        }

        return new FetchResult(
                new ArrayList<>(merged.values()),
                compareData.commitShas().size(),
                compareData.compareTimedOut(),
                usedCommitFallback,
                usedCommitFallback ? "compare+commit-diff" : "compare"
        );
    }

    private CompareData fetchCompare(String projectPath, String fromSha, String toSha) throws Exception {
        String encodedProject = url(projectPath);
        String url = normalizeBaseUrl(gitlabApiProperties.baseUrl())
                + "/api/v4/projects/" + encodedProject + "/repository/compare?from=" + url(fromSha)
                + "&to=" + url(toSha) + "&straight=true";

        Map<String, Object> body = getJsonMap(url);
        boolean compareTimedOut = asBoolean(body.get("compare_timeout"));

        List<ChangedFile> files = new ArrayList<>();
        Object diffsObj = body.get("diffs");
        if (diffsObj instanceof List<?> diffs) {
            for (Object it : diffs) {
                if (it instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> diff = (Map<String, Object>) raw;
                    ChangedFile cf = fromDiffMap(diff);
                    if (cf != null) files.add(cf);
                }
            }
        }

        List<String> commitShas = new ArrayList<>();
        Object commitsObj = body.get("commits");
        if (commitsObj instanceof List<?> commits) {
            for (Object it : commits) {
                if (it instanceof Map<?, ?> raw) {
                    Object id = raw.get("id");
                    if (id == null) id = raw.get("sha");
                    if (id != null) {
                        String sha = String.valueOf(id).trim();
                        if (!sha.isEmpty()) commitShas.add(sha);
                    }
                }
            }
        }

        return new CompareData(files, commitShas, compareTimedOut);
    }

    private List<ChangedFile> fetchCommitDiff(String projectPath, String sha) throws Exception {
        String encodedProject = url(projectPath);
        String url = normalizeBaseUrl(gitlabApiProperties.baseUrl())
                + "/api/v4/projects/" + encodedProject + "/repository/commits/" + url(sha) + "/diff";
        List<Map<String, Object>> body = getJsonList(url);
        List<ChangedFile> files = new ArrayList<>();
        for (Map<String, Object> diff : body) {
            ChangedFile cf = fromDiffMap(diff);
            if (cf != null) files.add(cf);
        }
        return files;
    }

    private Map<String, Object> getJsonMap(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("PRIVATE-TOKEN", gitlabApiProperties.token())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureSuccess(url, response);
        return objectMapper.readValue(response.body(), MAP_TYPE);
    }

    private List<Map<String, Object>> getJsonList(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("PRIVATE-TOKEN", gitlabApiProperties.token())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureSuccess(url, response);
        return objectMapper.readValue(response.body(), LIST_MAP_TYPE);
    }

    private void ensureSuccess(String url, HttpResponse<String> response) throws IOException {
        int code = response.statusCode();
        if (code >= 200 && code < 300) return;
        String body = response.body();
        if (body == null) body = "";
        body = body.replace("\r", " ").replace("\n", " ");
        if (body.length() > 500) body = body.substring(0, 500);
        throw new IOException("GitLab API request failed: status=" + code + ", url=" + url + ", body=" + body);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String v = baseUrl.trim();
        if (v.startsWith("http://http://")) {
            v = "http://" + v.substring("http://http://".length());
        } else if (v.startsWith("https://https://")) {
            v = "https://" + v.substring("https://https://".length());
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static boolean isZeroSha(String sha) {
        if (!StringUtils.hasText(sha)) return true;
        for (int i = 0; i < sha.length(); i++) {
            if (sha.charAt(i) != '0') return false;
        }
        return true;
    }

    private static String keyOf(ChangedFile f) {
        return f.changeType() + "|" + nvl(f.oldPath()) + "|" + nvl(f.newPath());
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static ChangedFile fromDiffMap(Map<String, Object> diff) {
        String oldPath = asString(diff.get("old_path"));
        String newPath = asString(diff.get("new_path"));

        GitChangeType type;
        if (asBoolean(diff.get("renamed_file"))) type = GitChangeType.RENAMED;
        else if (asBoolean(diff.get("new_file"))) type = GitChangeType.ADDED;
        else if (asBoolean(diff.get("deleted_file"))) type = GitChangeType.REMOVED;
        else type = GitChangeType.MODIFIED;

        if (!StringUtils.hasText(oldPath) && !StringUtils.hasText(newPath)) return null;
        return new ChangedFile(type, blankToNull(oldPath), blankToNull(newPath));
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }

    private record CompareData(List<ChangedFile> files, List<String> commitShas, boolean compareTimedOut) {}

    public record ChangedFile(GitChangeType changeType, String oldPath, String newPath) {}

    public record FetchResult(List<ChangedFile> files, int commitsCount, boolean compareTimedOut, boolean usedCommitFallback, String source) {}
}
