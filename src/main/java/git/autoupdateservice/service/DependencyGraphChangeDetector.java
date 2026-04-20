package git.autoupdateservice.service;

import git.autoupdateservice.domain.LogType;
import git.autoupdateservice.domain.RepoBinding;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.SourceKind;
import git.autoupdateservice.domain.Settings;
import git.autoupdateservice.domain.TargetType;
import git.autoupdateservice.repo.RepoBindingRepository;
import git.autoupdateservice.repo.SettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DependencyGraphChangeDetector {

    private final RepoBindingRepository repoBindingRepository;
    private final DependencyGraphStateService dependencyGraphStateService;
    private final OneCNameDecoder oneCNameDecoder;
    private final AuditLogService auditLogService;
    private final SettingsRepository settingsRepository;

    @Transactional
    public ChangeAnalysis analyzeChangesAndMarkStale(
            String projectPath,
            List<GitlabChangesService.ChangedFile> changedFiles,
            String clientIp
    ) {
        RepoBinding binding = repoBindingRepository.findByProjectPathAndActiveTrue(projectPath).orElse(null);
        if (binding == null) {
            return ChangeAnalysis.empty();
        }

        try {
            List<DependencyGraphStateService.DirtyModuleHit> hits = extractDirtyCommonModules(changedFiles);
            List<DirectObjectHit> directObjects = extractDirectObjects(changedFiles);

            SourceKind sourceKind = binding.getTargetType() == TargetType.EXTENSION ? SourceKind.EXTENSION : SourceKind.BASE;
            String sourceName = sourceKind == SourceKind.EXTENSION
                    ? firstNonBlank(binding.getExtensionName(), binding.getProjectPath())
                    : "Основная конфигурация";

            if (!hits.isEmpty()) {
                OffsetDateTime now = OffsetDateTime.now();
                dependencyGraphStateService.markGraphStale(
                        sourceKind,
                        sourceName,
                        hits,
                        resolveBusinessDate(now),
                        now,
                        "Изменены общие модули"
                );

                auditLogService.info(
                        LogType.WEBHOOK_RECEIVED,
                        "Dependency graph marked stale",
                        "{\"projectPath\":\"" + esc(projectPath) + "\",\"sourceKind\":\"" + sourceKind + "\",\"modules\":" + hits.size() + ",\"directObjects\":" + directObjects.size() + "}",
                        clientIp,
                        "gitlab",
                        null
                );
            }

            return new ChangeAnalysis(sourceKind, sourceName, hits, directObjects);
        } catch (Exception e) {
            auditLogService.warn(
                    LogType.WEBHOOK_RECEIVED,
                    "Dependency graph change analysis failed: " + safe(e.getMessage()),
                    "{\"projectPath\":\"" + esc(projectPath) + "\"}",
                    clientIp,
                    "gitlab",
                    null
            );
            return ChangeAnalysis.empty();
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
            String decodedPath = oneCNameDecoder.decodePath(path);
            String[] parts = decodedPath.split("/");
            int commonModulesIndex = -1;
            for (int i = 0; i < parts.length; i++) {
                if (isCommonModulesRoot(parts[i])) {
                    commonModulesIndex = i;
                    break;
                }
            }
            if (commonModulesIndex < 0 || commonModulesIndex + 1 >= parts.length) {
                continue;
            }
            String moduleName = parts[commonModulesIndex + 1];
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

    public List<DirectObjectHit> extractDirectObjects(List<GitlabChangesService.ChangedFile> files) {
        Set<String> dedup = new LinkedHashSet<>();
        java.util.ArrayList<DirectObjectHit> hits = new java.util.ArrayList<>();
        if (files == null) {
            return hits;
        }
        for (GitlabChangesService.ChangedFile file : files) {
            String path = normalize(firstNonBlank(file.newPath(), file.oldPath()));
            if (path == null || path.isBlank()) {
                continue;
            }
            String decodedPath = oneCNameDecoder.decodePath(path);
            String[] parts = decodedPath.split("/");
            for (int index = 0; index < parts.length - 1; index++) {
                DependencyCallerType objectType = parseObjectType(parts[index]);
                if (objectType == null || index + 1 >= parts.length) {
                    continue;
                }
                String objectName = parts[index + 1];
                if (objectName == null || objectName.isBlank()) {
                    continue;
                }
                String key = objectType.name() + "|" + objectName;
                if (!dedup.add(key)) {
                    break;
                }
                hits.add(new DirectObjectHit(objectType, objectName, decodedPath));
                break;
            }
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

    private DependencyCallerType parseObjectType(String rawRoot) {
        if (rawRoot == null || rawRoot.isBlank()) {
            return null;
        }
        String normalized = normalizeRootName(rawRoot);
        return switch (normalized) {
            case "catalogs", "catalog", "справочники", "справочник" -> DependencyCallerType.CATALOG;
            case "documents", "document", "документы", "документ" -> DependencyCallerType.DOCUMENT;
            case "reports", "report", "отчеты", "отчет" -> DependencyCallerType.REPORT;
            case "commonforms", "commonform", "общиеформы", "общаяформа" -> DependencyCallerType.COMMON_FORM;
            case "dataprocessors", "dataprocessor", "обработки", "обработка" -> DependencyCallerType.DATA_PROCESSOR;
            default -> null;
        };
    }

    private boolean isCommonModulesRoot(String rawRoot) {
        String normalized = normalizeRootName(rawRoot);
        return "commonmodules".equals(normalized)
                || "commonmodule".equals(normalized)
                || "общиемодули".equals(normalized)
                || "общиймодуль".equals(normalized);
    }

    private String normalizeRootName(String rawRoot) {
        if (rawRoot == null) {
            return "";
        }
        return rawRoot.trim()
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[\\s_\\-]+", "");
    }

    private LocalDate resolveBusinessDate(OffsetDateTime changeAt) {
        String timezone = settingsRepository.findById(1L)
                .map(Settings::getTimezone)
                .orElse(null);
        try {
            return changeAt.atZoneSameInstant(ZoneId.of(timezone)).toLocalDate();
        } catch (Exception e) {
            return changeAt.toLocalDate();
        }
    }

    public record DirectObjectHit(DependencyCallerType objectType, String objectName, String changedPath) {
    }

    public record ChangeAnalysis(
            SourceKind sourceKind,
            String sourceName,
            List<DependencyGraphStateService.DirtyModuleHit> dirtyModules,
            List<DirectObjectHit> directObjects
    ) {
        public static ChangeAnalysis empty() {
            return new ChangeAnalysis(null, null, List.of(), List.of());
        }

        public boolean hasDirtyModules() {
            return dirtyModules != null && !dirtyModules.isEmpty();
        }

        public boolean hasDirectObjects() {
            return directObjects != null && !directObjects.isEmpty();
        }
    }
}
