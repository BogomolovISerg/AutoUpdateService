package git.autoupdateservice.service;

import git.autoupdateservice.domain.CodeSourceRoot;
import git.autoupdateservice.domain.CommonModuleImpact;
import git.autoupdateservice.domain.DependencyBuildMode;
import git.autoupdateservice.domain.DependencyCallExclusion;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.DependencyEdge;
import git.autoupdateservice.domain.DependencyScanLog;
import git.autoupdateservice.domain.DependencySnapshot;
import git.autoupdateservice.domain.DependencySnapshotStatus;
import git.autoupdateservice.domain.SourceKind;
import git.autoupdateservice.repo.CodeSourceRootRepository;
import git.autoupdateservice.repo.CommonModuleImpactRepository;
import git.autoupdateservice.repo.DependencyCallExclusionRepository;
import git.autoupdateservice.repo.DependencyEdgeRepository;
import git.autoupdateservice.repo.DependencyScanLogRepository;
import git.autoupdateservice.repo.DependencySnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class DependencyTreeBuildService {

    private static final Logger log = LoggerFactory.getLogger(DependencyTreeBuildService.class);

    private static final Set<String> ALLOWED_ROOTS = Set.of(
            "CommonModules",
            "Catalogs",
            "Documents",
            "Reports",
            "CommonForms",
            "DataProcessors"
    );

    private final CodeSourceRootRepository codeSourceRootRepository;
    private final DependencySnapshotRepository dependencySnapshotRepository;
    private final DependencyEdgeRepository dependencyEdgeRepository;
    private final CommonModuleImpactRepository commonModuleImpactRepository;
    private final DependencyCallExclusionRepository dependencyCallExclusionRepository;
    private final DependencyScanLogRepository dependencyScanLogRepository;
    private final BslDependencyParser bslDependencyParser;
    private final OneCNameDecoder oneCNameDecoder;

    @Transactional
    public DependencySnapshot fullRebuild() {
        CodeSourceRoot sourceRoot = codeSourceRootRepository
                .findFirstBySourceKindAndEnabledIsTrue(SourceKind.BASE)
                .orElseThrow(() -> new IllegalStateException("Не настроен активный источник основной конфигурации"));
        return fullRebuild(sourceRoot.getId());
    }

    @Transactional
    public DependencySnapshot fullRebuild(UUID sourceRootId) {
        CodeSourceRoot sourceRoot = codeSourceRootRepository.findById(sourceRootId)
                .orElseThrow(() -> new IllegalArgumentException("Источник кода не найден: " + sourceRootId));

        DependencySnapshot snapshot = new DependencySnapshot();
        snapshot.setSourceRoot(sourceRoot);
        snapshot.setStatus(DependencySnapshotStatus.BUILDING);
        snapshot.setBuildMode(DependencyBuildMode.FULL);
        snapshot.setStartedAt(OffsetDateTime.now());
        snapshot.setFilesScanned(0);
        snapshot.setNotes("Запущено полное сканирование");
        snapshot = dependencySnapshotRepository.save(snapshot);

        logInfo(snapshot, "START", sourceRoot.getRootPath(),
                "Start dependency rebuild. sourceRootId=" + sourceRoot.getId()
                        + ", sourceName=" + sourceRoot.getSourceName()
                        + ", rootPath=" + sourceRoot.getRootPath());

        try {
            BuildArtifacts artifacts = scanSource(sourceRoot, snapshot);

            if (!artifacts.edges().isEmpty()) {
                dependencyEdgeRepository.saveAll(artifacts.edges());
                logInfo(snapshot, "SAVE_EDGES", sourceRoot.getRootPath(),
                        "Saved dependency edges: " + artifacts.edges().size());
            } else {
                logInfo(snapshot, "SAVE_EDGES", sourceRoot.getRootPath(), "No dependency edges to save");
            }

            if (!artifacts.impacts().isEmpty()) {
                commonModuleImpactRepository.saveAll(artifacts.impacts());
                logInfo(snapshot, "SAVE_IMPACTS", sourceRoot.getRootPath(),
                        "Saved common module impacts: " + artifacts.impacts().size());
            } else {
                logInfo(snapshot, "SAVE_IMPACTS", sourceRoot.getRootPath(), "No common module impacts to save");
            }

            snapshot.setFilesScanned(artifacts.filesScanned());
            snapshot.setStatus(DependencySnapshotStatus.READY);
            snapshot.setFinishedAt(OffsetDateTime.now());
            snapshot.setNotes("Полное сканирование выполнено");
            snapshot = dependencySnapshotRepository.save(snapshot);

            logInfo(snapshot, "FINISH", sourceRoot.getRootPath(),
                    "Rebuild finished successfully. filesScanned=" + artifacts.filesScanned()
                            + ", edges=" + artifacts.edges().size()
                            + ", impacts=" + artifacts.impacts().size());

            return snapshot;
        } catch (Exception e) {
            snapshot.setStatus(DependencySnapshotStatus.FAILED);
            snapshot.setFinishedAt(OffsetDateTime.now());
            snapshot.setNotes(safeText(errorMessage(e), 4000));
            snapshot = dependencySnapshotRepository.save(snapshot);

            logError(snapshot, "REBUILD_FAILED", sourceRoot.getRootPath(),
                    "Dependency rebuild failed: " + errorMessage(e), e);

            return snapshot;
        }
    }

    private BuildArtifacts scanSource(CodeSourceRoot sourceRoot, DependencySnapshot snapshot) throws IOException {
        Path root = Path.of(sourceRoot.getRootPath()).toAbsolutePath().normalize();

        logInfo(snapshot, "ROOT_CHECK", root.toString(), "Checking source root");

        if (!Files.isDirectory(root)) {
            IllegalStateException e = new IllegalStateException("Каталог исходников не найден: " + root);
            logError(snapshot, "ROOT_INVALID", root.toString(), e.getMessage(), e);
            throw e;
        }

        List<Path> bslFiles;
        try (Stream<Path> stream = Files.walk(root)) {
            bslFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bsl"))
                    .filter(p -> belongsToAllowedRoot(root, p))
                    .sorted()
                    .toList();
        }

        logInfo(snapshot, "DISCOVER", root.toString(), "Found BSL files: " + bslFiles.size());

        Set<String> knownCommonModules = collectKnownCommonModules(root, bslFiles);
        logInfo(snapshot, "COMMON_MODULES", root.toString(),
                "Known common modules: " + knownCommonModules.size());

        Set<String> excludedCalls = dependencyCallExclusionRepository.findAllByEnabledIsTrueOrderByCallNameAsc().stream()
                .map(DependencyCallExclusion::getCallName)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        logInfo(snapshot, "EXCLUSIONS", root.toString(),
                "Excluded calls loaded: " + excludedCalls.size());

        List<DependencyEdge> edges = new ArrayList<>();
        Map<ObjectKey, List<DependencyEdge>> objectDirectEdges = new LinkedHashMap<>();
        Map<MemberKey, List<DependencyEdge>> commonModuleEdges = new LinkedHashMap<>();

        int filesProcessed = 0;

        for (Path file : bslFiles) {
            String rel = null;
            String decodedRel = null;
            String text = null;

            try {
                logInfo(snapshot, "FILE_START", file.toString(), "Processing file");

                logInfo(snapshot, "RELATIVIZE_START", file.toString(), "Start relativize");
                rel = normalizeRel(root.relativize(file));
                logInfo(snapshot, "RELATIVIZE_OK", file.toString(), "rel=" + rel);

                logInfo(snapshot, "DECODE_PATH_START", file.toString(), "decode input=" + rel);
                decodedRel = oneCNameDecoder.decodePath(rel);
                logInfo(snapshot, "DECODE_PATH_OK", file.toString(), "decodedRel=" + decodedRel);

                logInfo(snapshot, "READ_FILE_START", file.toString(), "Start read file");
                text = Files.readString(file, StandardCharsets.UTF_8);
                logInfo(snapshot, "READ_FILE_OK", file.toString(), "textLength=" + text.length());

                logInfo(snapshot, "PARSE_FILE_START", file.toString(), "Start parse file");
                BslDependencyParser.ParsedFile parsed =
                        bslDependencyParser.parse(file, text, root, knownCommonModules, excludedCalls);

                int callCount = parsed.getCalls() == null ? 0 : parsed.getCalls().size();
                logInfo(snapshot, "PARSE_FILE_OK", file.toString(),
                        "callerType=" + parsed.getCallerType()
                                + ", callerName=" + parsed.getCallerName()
                                + ", calls=" + callCount);

                if (parsed.getCallerType() == null || parsed.getCallerName() == null || parsed.getCalls() == null) {
                    logWarn(snapshot, "FILE_SKIPPED", file.toString(),
                            "Parsed file has no caller or calls. rel=" + rel + ", decodedRel=" + decodedRel);
                    filesProcessed++;
                    snapshot.setFilesScanned(filesProcessed);
                    continue;
                }

                for (BslDependencyParser.ParsedCall call : parsed.getCalls()) {
                    if (call.getCalleeModule() == null || call.getCalleeModule().isBlank()) {
                        continue;
                    }

                    DependencyEdge edge = new DependencyEdge();
                    edge.setSnapshot(snapshot);
                    edge.setCallerType(parsed.getCallerType());
                    edge.setCallerName(parsed.getCallerName());
                    edge.setCallerMember(call.getCallerMember());
                    edge.setCalleeModule(call.getCalleeModule());
                    edge.setCalleeMember(call.getCalleeMember());
                    edge.setSourcePath(call.getSourcePath());
                    edge.setCreatedAt(OffsetDateTime.now());
                    edges.add(edge);

                    if (parsed.getCallerType() == DependencyCallerType.COMMON_MODULE) {
                        MemberKey key = new MemberKey(parsed.getCallerName(), call.getCallerMember());
                        commonModuleEdges.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
                    } else {
                        ObjectKey key = new ObjectKey(parsed.getCallerType(), parsed.getCallerName(), call.getSourcePath());
                        objectDirectEdges.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
                    }
                }

                filesProcessed++;
                snapshot.setFilesScanned(filesProcessed);

                logInfo(snapshot, "FILE_OK", file.toString(),
                        "Processed successfully. totalProcessed=" + filesProcessed
                                + ", totalEdges=" + edges.size());
            } catch (Exception e) {
                snapshot.setFilesScanned(filesProcessed);
                logError(snapshot, "FILE_FAILED", file.toString(),
                        "rel=" + rel + ", decodedRel=" + decodedRel + ", error=" + errorMessage(e), e);
                throw e;
            }
        }

        logInfo(snapshot, "BUILD_IMPACTS_START", root.toString(),
                "Building impacts. objectKeys=" + objectDirectEdges.size()
                        + ", commonModuleKeys=" + commonModuleEdges.size());

        List<CommonModuleImpact> impacts = buildImpacts(snapshot, objectDirectEdges, commonModuleEdges);

        logInfo(snapshot, "BUILD_IMPACTS_OK", root.toString(), "Impacts built: " + impacts.size());

        return new BuildArtifacts(filesProcessed, edges, impacts);
    }

    private Set<String> collectKnownCommonModules(Path sourceRoot, List<Path> bslFiles) {
        Set<String> modules = new LinkedHashSet<>();
        for (Path file : bslFiles) {
            Path rel = sourceRoot.relativize(file);
            if (rel.getNameCount() < 2) {
                continue;
            }
            String first = oneCNameDecoder.decodePathSegment(rel.getName(0).toString());
            if (!"CommonModules".equals(first)) {
                continue;
            }
            modules.add(oneCNameDecoder.decodePathSegment(rel.getName(1).toString()));
        }
        return modules;
    }

    private List<CommonModuleImpact> buildImpacts(
            DependencySnapshot snapshot,
            Map<ObjectKey, List<DependencyEdge>> objectDirectEdges,
            Map<MemberKey, List<DependencyEdge>> commonModuleEdges
    ) {
        List<CommonModuleImpact> impacts = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();

        for (Map.Entry<ObjectKey, List<DependencyEdge>> e : objectDirectEdges.entrySet()) {
            ObjectKey objectKey = e.getKey();
            Deque<Traversal> queue = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();

            for (DependencyEdge edge : e.getValue()) {
                queue.addLast(new Traversal(edge.getCalleeModule(), edge.getCalleeMember(), null, edge.getCalleeMember()));
            }

            while (!queue.isEmpty()) {
                Traversal t = queue.removeFirst();
                String dedupKey = objectKey.type() + "|" + objectKey.name() + "|" + objectKey.sourcePath()
                        + "|" + nvl(t.module()) + "|" + nvl(t.member()) + "|" + nvl(t.viaModule()) + "|" + nvl(t.viaMember());
                if (!seen.add(dedupKey)) {
                    continue;
                }

                CommonModuleImpact row = new CommonModuleImpact();
                row.setSnapshot(snapshot);
                row.setCommonModuleName(t.module());
                row.setObjectType(objectKey.type());
                row.setObjectName(objectKey.name());
                row.setSourcePath(objectKey.sourcePath());
                row.setViaModule(t.viaModule());
                row.setViaMember(t.viaMember() == null ? t.member() : t.viaMember());
                row.setCreatedAt(now);
                impacts.add(row);

                MemberKey current = new MemberKey(t.module(), t.member());
                for (DependencyEdge next : commonModuleEdges.getOrDefault(current, List.of())) {
                    queue.addLast(new Traversal(
                            next.getCalleeModule(),
                            next.getCalleeMember(),
                            t.module(),
                            t.member()
                    ));
                }
            }
        }

        return impacts;
    }

    private boolean belongsToAllowedRoot(Path sourceRoot, Path file) {
        Path rel = sourceRoot.relativize(file);
        if (rel.getNameCount() == 0) {
            return false;
        }
        String first = oneCNameDecoder.decodePathSegment(rel.getName(0).toString());
        return ALLOWED_ROOTS.contains(first);
    }

    private String normalizeRel(Path relPath) {
        return relPath.toString().replace('\\', '/');
    }

    private String errorMessage(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getName();
        }
        return e.getClass().getName() + ": " + message;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private record BuildArtifacts(int filesScanned, List<DependencyEdge> edges, List<CommonModuleImpact> impacts) {}
    private record ObjectKey(DependencyCallerType type, String name, String sourcePath) {}
    private record MemberKey(String module, String member) {}
    private record Traversal(String module, String member, String viaModule, String viaMember) {}

    private void logInfo(DependencySnapshot snapshot, String phase, String sourcePath, String message) {
        logScan(snapshot, "INFO", phase, sourcePath, message, null);
    }

    private void logWarn(DependencySnapshot snapshot, String phase, String sourcePath, String message) {
        logScan(snapshot, "WARN", phase, sourcePath, message, null);
    }

    private void logError(DependencySnapshot snapshot, String phase, String sourcePath, String message, Throwable error) {
        logScan(snapshot, "ERROR", phase, sourcePath, message, error);
    }

    private void logScan(DependencySnapshot snapshot,
                         String level,
                         String phase,
                         String sourcePath,
                         String message,
                         Throwable error) {
        try {
            DependencyScanLog row = new DependencyScanLog();
            row.setSnapshot(snapshot);
            row.setLevel(level);
            row.setPhase(phase);
            row.setSourcePath(trimToNull(sourcePath));
            row.setMessage(safeText(message, 20000));
            row.setStacktrace(error == null ? null : safeText(stackTrace(error), 200000));
            row.setCreatedAt(OffsetDateTime.now());
            dependencyScanLogRepository.save(row);
        } catch (Exception ignored) {
            // не ломаем rebuild из-за проблем с логированием
        }

        if ("ERROR".equals(level)) {
            log.error("[DEP-SCAN:{}] {} | path={}", phase, message, sourcePath, error);
        } else if ("WARN".equals(level)) {
            log.warn("[DEP-SCAN:{}] {} | path={}", phase, message, sourcePath);
        } else {
            log.info("[DEP-SCAN:{}] {} | path={}", phase, message, sourcePath);
        }
    }

    private String stackTrace(Throwable error) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        error.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String x = s.trim();
        return x.isEmpty() ? null : x;
    }

    private String safeText(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}