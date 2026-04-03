package git.autoupdateservice.service;

import git.autoupdateservice.domain.CodeSourceRoot;
import git.autoupdateservice.domain.CommonModuleImpact;
import git.autoupdateservice.domain.DependencyBuildMode;
import git.autoupdateservice.domain.DependencyCallExclusion;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.DependencyEdge;
import git.autoupdateservice.domain.DependencySnapshot;
import git.autoupdateservice.domain.DependencySnapshotStatus;
import git.autoupdateservice.domain.SourceKind;
import git.autoupdateservice.repo.CodeSourceRootRepository;
import git.autoupdateservice.repo.CommonModuleImpactRepository;
import git.autoupdateservice.repo.DependencyCallExclusionRepository;
import git.autoupdateservice.repo.DependencyEdgeRepository;
import git.autoupdateservice.repo.DependencySnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
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

    private final DependencyGraphStateService dependencyGraphStateService;
    private final CodeSourceRootRepository codeSourceRootRepository;
    private final DependencySnapshotRepository dependencySnapshotRepository;
    private final DependencyEdgeRepository dependencyEdgeRepository;
    private final CommonModuleImpactRepository commonModuleImpactRepository;
    private final DependencyCallExclusionRepository dependencyCallExclusionRepository;
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

        log.info("[DEP-SCAN:START] Start dependency rebuild. sourceRootId={}, sourceName={}, rootPath={}",
                sourceRoot.getId(), sourceRoot.getSourceName(), sourceRoot.getRootPath());

        try {
            BuildArtifacts artifacts = scanSource(sourceRoot, snapshot);

            if (!artifacts.edges().isEmpty()) {
                dependencyEdgeRepository.saveAll(artifacts.edges());
                log.info("[DEP-SCAN:SAVE_EDGES] Saved dependency edges: {} | path={}",
                        artifacts.edges().size(), sourceRoot.getRootPath());
            } else {
                log.info("[DEP-SCAN:SAVE_EDGES] No dependency edges to save | path={}", sourceRoot.getRootPath());
            }

            if (!artifacts.impacts().isEmpty()) {
                commonModuleImpactRepository.saveAll(artifacts.impacts());
                log.info("[DEP-SCAN:SAVE_IMPACTS] Saved common module impacts: {} | path={}",
                        artifacts.impacts().size(), sourceRoot.getRootPath());
            } else {
                log.info("[DEP-SCAN:SAVE_IMPACTS] No common module impacts to save | path={}", sourceRoot.getRootPath());
            }

            snapshot.setFilesScanned(artifacts.filesScanned());
            snapshot.setStatus(DependencySnapshotStatus.READY);
            snapshot.setFinishedAt(OffsetDateTime.now());
            snapshot.setNotes(buildFinishNotes(artifacts));
            snapshot = dependencySnapshotRepository.save(snapshot);

            dependencyGraphStateService.markSnapshotReady(snapshot);

            log.info("[DEP-SCAN:FINISH] Rebuild finished successfully. filesScanned={}, skipped={}, edges={}, impacts={} | path={}",
                    artifacts.filesScanned(),
                    artifacts.skippedFiles(),
                    artifacts.edges().size(),
                    artifacts.impacts().size(),
                    sourceRoot.getRootPath());

            return snapshot;
        } catch (Exception e) {
            snapshot.setStatus(DependencySnapshotStatus.FAILED);
            snapshot.setFinishedAt(OffsetDateTime.now());
            snapshot.setNotes(errorMessage(e));
            snapshot = dependencySnapshotRepository.save(snapshot);

            log.error("[DEP-SCAN:REBUILD_FAILED] Dependency rebuild failed: {} | path={}",
                    errorMessage(e), sourceRoot.getRootPath(), e);

            return snapshot;
        }
    }

    private BuildArtifacts scanSource(CodeSourceRoot sourceRoot, DependencySnapshot snapshot) throws IOException {
        Path root = Path.of(sourceRoot.getRootPath()).toAbsolutePath().normalize();

        log.info("[DEP-SCAN:ROOT_CHECK] Checking source root | path={}", root);

        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("Каталог исходников не найден: " + root);
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

        log.info("[DEP-SCAN:DISCOVER] Found BSL files: {} | path={}", bslFiles.size(), root);

        Set<String> knownCommonModules = collectKnownCommonModules(root, bslFiles);
        log.info("[DEP-SCAN:COMMON_MODULES] Known common modules: {} | path={}", knownCommonModules.size(), root);

        Set<String> excludedCalls = dependencyCallExclusionRepository.findAllByEnabledIsTrueOrderByCallNameAsc().stream()
                .map(DependencyCallExclusion::getCallName)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        log.info("[DEP-SCAN:EXCLUSIONS] Excluded calls loaded: {} | path={}", excludedCalls.size(), root);

        List<DependencyEdge> edges = new ArrayList<>();
        Map<ObjectKey, List<DependencyEdge>> objectDirectEdges = new LinkedHashMap<>();
        Map<MemberKey, List<DependencyEdge>> commonModuleEdges = new LinkedHashMap<>();
        List<String> skippedFileMessages = new ArrayList<>();

        int filesProcessed = 0;
        int skippedFiles = 0;

        for (Path file : bslFiles) {
            String rel = null;
            String decodedRel = null;

            try {
                log.info("[DEP-SCAN:FILE_START] Processing file | path={}", file);

                rel = normalizeRel(root.relativize(file));
                log.info("[DEP-SCAN:RELATIVIZE_OK] rel={} | path={}", rel, file);

                decodedRel = oneCNameDecoder.decodePath(rel);
                log.info("[DEP-SCAN:DECODE_PATH_OK] decodedRel={} | path={}", decodedRel, file);

                String text = Files.readString(file, StandardCharsets.UTF_8);
                log.info("[DEP-SCAN:READ_FILE_OK] textLength={} | path={}", text.length(), file);

                BslDependencyParser.ParsedFile parsed =
                        bslDependencyParser.parse(file, text, root, knownCommonModules, excludedCalls);

                int callCount = parsed.getCalls() == null ? 0 : parsed.getCalls().size();
                log.info("[DEP-SCAN:PARSE_FILE_OK] callerType={}, callerName={}, calls={} | path={}",
                        parsed.getCallerType(), parsed.getCallerName(), callCount, file);

                if (parsed.getCallerType() == null || parsed.getCallerName() == null || parsed.getCalls() == null) {
                    log.info("[DEP-SCAN:FILE_SKIPPED_EMPTY] Parsed file has no caller or calls | path={}", file);
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

                log.info("[DEP-SCAN:FILE_OK] Processed successfully. processed={}, edges={} | path={}",
                        filesProcessed, edges.size(), file);

            } catch (Exception e) {
                skippedFiles++;

                String msg = "Пропущен файл: " + file
                        + " | rel=" + nvl(rel)
                        + " | decodedRel=" + nvl(decodedRel)
                        + " | error=" + errorMessage(e);

                skippedFileMessages.add(msg);
                log.warn("[DEP-SCAN:FILE_SKIPPED_ERROR] {}", msg, e);

                continue;
            }
        }

        log.info("[DEP-SCAN:BUILD_IMPACTS_START] objectKeys={}, commonModuleKeys={} | path={}",
                objectDirectEdges.size(), commonModuleEdges.size(), root);

        List<CommonModuleImpact> impacts = buildImpacts(snapshot, objectDirectEdges, commonModuleEdges);

        log.info("[DEP-SCAN:BUILD_IMPACTS_OK] impacts={} | path={}", impacts.size(), root);
        log.info("[DEP-SCAN:FINISH_SCAN] processed={}, skipped={}, edges={}, impacts={} | path={}",
                filesProcessed, skippedFiles, edges.size(), impacts.size(), root);

        int previewLimit = Math.min(skippedFileMessages.size(), 50);
        for (int i = 0; i < previewLimit; i++) {
            log.warn("[DEP-SCAN:SKIPPED_FILE] {}", skippedFileMessages.get(i));
        }
        if (skippedFileMessages.size() > previewLimit) {
            log.warn("[DEP-SCAN:SKIPPED_FILE] Всего пропущено файлов: {}. В лог выведены первые {}.",
                    skippedFileMessages.size(), previewLimit);
        }

        return new BuildArtifacts(filesProcessed, skippedFiles, skippedFileMessages, edges, impacts);
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

    private String buildFinishNotes(BuildArtifacts artifacts) {
        String base = "Полное сканирование выполнено. Обработано: "
                + artifacts.filesScanned()
                + ", пропущено: "
                + artifacts.skippedFiles();

        if (artifacts.skippedFiles() == 0) {
            return base;
        }

        List<String> preview = artifacts.skippedFileMessages().stream()
                .limit(3)
                .toList();

        return base + ". Первые ошибки: " + String.join(" ; ", preview);
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

    private record BuildArtifacts(
            int filesScanned,
            int skippedFiles,
            List<String> skippedFileMessages,
            List<DependencyEdge> edges,
            List<CommonModuleImpact> impacts
    ) {}

    private record ObjectKey(DependencyCallerType type, String name, String sourcePath) {}
    private record MemberKey(String module, String member) {}
    private record Traversal(String module, String member, String viaModule, String viaMember) {}
}