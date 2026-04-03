package git.autoupdateservice.service;

import git.autoupdateservice.domain.LogType;
import git.autoupdateservice.domain.RepoBinding;
import git.autoupdateservice.domain.SourceKind;
import git.autoupdateservice.domain.TargetType;
import git.autoupdateservice.repo.RepoBindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DependencyGraphChangeDetector {

    private final RepoBindingRepository repoBindingRepository;
    private final GitlabChangesService gitlabChangesService;
    private final DependencyGraphStateService dependencyGraphStateService;
    private final OneCNameDecoder oneCNameDecoder;
    private final AuditLogService auditLogService;

    @Transactional
    public void analyzePushAndMarkStale(String projectPath, String beforeSha, String commitSha, String clientIp) {
        RepoBinding binding = repoBindingRepository.findByProjectPathAndActiveTrue(projectPath).orElse(null);
        if (binding == null) {
            return;
        }

        try {
            GitlabChangesService.FetchResult fetchResult = gitlabChangesService.fetchFullChanges(projectPath, beforeSha, commitSha);
            List<DependencyGraphStateService.DirtyModuleHit> hits = extractDirtyCommonModules(fetchResult.files());
            if (hits.isEmpty()) {
                return;
            }

            SourceKind sourceKind = binding.getTargetType() == TargetType.EXTENSION ? SourceKind.EXTENSION : SourceKind.BASE;
            String sourceName = sourceKind == SourceKind.EXTENSION
                    ? firstNonBlank(binding.getExtensionName(), binding.getProjectPath())
                    : "Основная конфигурация";

            dependencyGraphStateService.markGraphStale(
                    sourceKind,
                    sourceName,
                    hits,
                    OffsetDateTime.now(),
                    "Изменены общие модули"
            );

            auditLogService.info(
                    LogType.WEBHOOK_RECEIVED,
                    "Dependency graph marked stale",
                    "{\"projectPath\":\"" + esc(projectPath) + "\",\"sourceKind\":\"" + sourceKind + "\",\"modules\":" + hits.size() + "}",
                    clientIp,
                    "gitlab",
                    null
            );
        } catch (Exception e) {
            auditLogService.warn(
                    LogType.WEBHOOK_RECEIVED,
                    "Dependency graph change analysis failed: " + safe(e.getMessage()),
                    "{\"projectPath\":\"" + esc(projectPath) + "\",\"commitSha\":\"" + esc(commitSha) + "\"}",
                    clientIp,
                    "gitlab",
                    null
            );
        }
    }

    private List<DependencyGraphStateService.DirtyModuleHit> extractDirtyCommonModules(List<GitlabChangesService.ChangedFile> files) {
        Set<String> dedup = new LinkedHashSet<>();
        java.util.ArrayList<DependencyGraphStateService.DirtyModuleHit> hits = new java.util.ArrayList<>();
        if (files == null) {
            return hits;
        }
        for (GitlabChangesService.ChangedFile file : files) {
            String path = normalize(firstNonBlank(file.newPath(), file.oldPath()));
            if (path == null || path.isBlank()) {
                continue;
            }
            String decodedPath = oneCNameDecoder.decodePathSegment(path);
            String[] parts = decodedPath.split("/");
            if (parts.length < 2) {
                continue;
            }
            if (!"commonmodules".equals(parts[0].toLowerCase(Locale.ROOT))) {
                continue;
            }
            String moduleName = parts[1];
            if (moduleName == null || moduleName.isBlank()) {
                continue;
            }
            String key = moduleName + "|" + decodedPath;
            if (!dedup.add(key)) {
                continue;
            }
            hits.add(new DependencyGraphStateService.DirtyModuleHit(moduleName, decodedPath));
        }
        return hits;
    }

    private static String normalize(String path) {
        return path == null ? null : path.replace('\\', '/');
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safe(String s) {
        if (s == null) return "unknown";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
