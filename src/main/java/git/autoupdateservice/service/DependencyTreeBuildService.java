package git.autoupdateservice.service;

import git.autoupdateservice.domain.*;
import git.autoupdateservice.repo.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
//import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final DependencyTreeSearchService dependencyTreeSearchService;
    private final DependencyScanLogRepository dependencyScanLogRepository;
    private static final int IMPACT_BATCH_SIZE = 1000;
    private static final int EDGE_BATCH_SIZE = 1000;
    private static final long MAX_BSL_FILE_SIZE_BYTES = 5L * 1024L * 1024L;

    //@Transactional
    public DependencySnapshot fullRebuild() {
        CodeSourceRoot sourceRoot = codeSourceRootRepository
                .findFirstBySourceKindAndEnabledIsTrue(SourceKind.BASE)
                .orElseThrow(() -> new IllegalStateException("Не настроен активный источник основной конфигурации"));
        return fullRebuild(sourceRoot.getId());
    }

   // @Transactional
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

      //  log.info("[DEP-SCAN:START] Start dependency rebuild. sourceRootId={}, sourceName={}, rootPath={}",
        //        sourceRoot.getId(), sourceRoot.getSourceName(), sourceRoot.getRootPath());

        try {
            //dependencyEdgeRepository.deleteBySnapshot(snapshot);
            //commonModuleImpactRepository.deleteBySnapshot(snapshot);

            BuildArtifacts artifacts = scanSource(sourceRoot, snapshot);

            snapshot.setFilesScanned(artifacts.filesScanned());
            snapshot.setStatus(DependencySnapshotStatus.READY);
            snapshot.setFinishedAt(OffsetDateTime.now());
            snapshot.setNotes(buildFinishNotes(artifacts));
            snapshot = dependencySnapshotRepository.save(snapshot);
            saveScanLog(snapshot, "INFO", "START", sourceRoot.getRootPath(),
                    "Запущено полное сканирование", null);
            dependencyGraphStateService.markSnapshotReady(snapshot);
            saveScanLog(snapshot, "INFO", "FINISH", sourceRoot.getRootPath(),
                    "Полное сканирование завершено. Обработано=" + artifacts.filesScanned()
                            + ", пропущено=" + artifacts.skippedFiles()
                            + ", edges=" + artifacts.edgesSaved()
                            + ", impacts=" + artifacts.impactsSaved(),
                    null);
            return snapshot;
        } catch (Exception e) {
            snapshot.setStatus(DependencySnapshotStatus.FAILED);
            snapshot.setFinishedAt(OffsetDateTime.now());
            snapshot.setNotes(errorMessage(e));
            snapshot = dependencySnapshotRepository.save(snapshot);

           // log.error("[DEP-SCAN:REBUILD_FAILED] Dependency rebuild failed: {} | path={}",
                //    errorMessage(e), sourceRoot.getRootPath(), e);
            dependencyEdgeRepository.deleteBySnapshot(snapshot);
            commonModuleImpactRepository.deleteBySnapshot(snapshot);
            return snapshot;
        }
    }

    private BuildArtifacts scanSource(CodeSourceRoot sourceRoot, DependencySnapshot snapshot) throws IOException {
        Path root = Path.of(sourceRoot.getRootPath()).toAbsolutePath().normalize();
      //  log.info("[DEP-SCAN:ROOT_CHECK] Checking source root | path={}", root);

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

        List<Path> commonModuleFiles = bslFiles.stream()
                .filter(f -> isRoot(root, f, "CommonModules"))
                .toList();

        List<Path> objectFiles = bslFiles.stream()
                .filter(f -> !isRoot(root, f, "CommonModules"))
                .toList();

      //  log.info("[DEP-SCAN:DISCOVER] Found BSL files: {} | commonModules={} | objectFiles={} | path={}",
        //        bslFiles.size(), commonModuleFiles.size(), objectFiles.size(), root);

        Set<String> knownCommonModules = collectKnownCommonModules(root, commonModuleFiles);
        //log.info("[DEP-SCAN:COMMON_MODULES] Known common modules: {} | path={}", knownCommonModules.size(), root);

        Set<String> excludedCalls = dependencyCallExclusionRepository.findAllByEnabledIsTrueOrderByCallNameAsc().stream()
                .map(DependencyCallExclusion::getCallName)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        //log.info("[DEP-SCAN:EXCLUSIONS] Excluded calls loaded: {} | path={}", excludedCalls.size(), root);

        List<String> skippedFileMessages = new ArrayList<>();

        Map<String, MemberRef> membersByFullName = new LinkedHashMap<>();
        Map<String, Set<String>> forwardEdges = new LinkedHashMap<>();
        Map<String, Set<String>> exportMembersByModule = new LinkedHashMap<>();

        int filesProcessed = 0;
        int skippedFiles = 0;
        int edgesSaved = 0;
        int impactsSaved = 0;

        List<DependencyEdge> edgeBatch = new ArrayList<>(EDGE_BATCH_SIZE);
        List<CommonModuleImpact> impactBatch = new ArrayList<>(IMPACT_BATCH_SIZE);

        for (Path file : commonModuleFiles) {
            String rel = null;
            String decodedRel = null;

            try {
                rel = normalizeRel(root.relativize(file));
                decodedRel = decodeRelativePath(rel);

                String text = readText(file);
                BslDependencyParser.ParsedCommonModule parsed = bslDependencyParser.parseCommonModule(
                        file, text, root, knownCommonModules, excludedCalls);

                logParsedCommonModule(parsed);

                if (parsed == null) {
                   // log.info("[DEP-SCAN:COMMON_MODULE_SKIPPED_EMPTY] path={}", file);
                    continue;
                }

                List<BslDependencyParser.MemberDefinition> members =
                        parsed.getMembers() == null ? List.of() : parsed.getMembers();

                for (BslDependencyParser.MemberDefinition member : members) {
                    MemberRef ref = new MemberRef(member.getModuleName(), member.getMemberName(), member.isExported());
                    membersByFullName.put(ref.fullName(), ref);

                    if (member.isExported()) {
                        exportMembersByModule
                                .computeIfAbsent(member.getModuleName().toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>())
                                .add(member.getMemberName().toLowerCase(Locale.ROOT));
                    }
                }

                List<BslDependencyParser.ModuleCall> calls =
                        parsed.getCalls() == null ? List.of() : parsed.getCalls();

                for (BslDependencyParser.ModuleCall call : calls) {
                    if (isBlank(call.getCallerModule())
                            || isBlank(call.getCallerMember())
                            || isBlank(call.getCalleeModule())
                            || isBlank(call.getCalleeMember())) {
                        continue;
                    }

                    String callerFull = call.getCallerModule() + "." + call.getCallerMember();
                    String calleeFull = call.getCalleeModule() + "." + call.getCalleeMember();
                    forwardEdges.computeIfAbsent(callerFull, k -> new LinkedHashSet<>()).add(calleeFull);

                    DependencyEdge edge = new DependencyEdge();
                    edge.setSnapshot(snapshot);
                    edge.setCallerType(DependencyCallerType.COMMON_MODULE);
                    edge.setCallerName(call.getCallerModule());
                    edge.setCallerMember(call.getCallerMember());
                    edge.setCalleeModule(call.getCalleeModule());
                    edge.setCalleeMember(call.getCalleeMember());
                    edge.setSourcePath(call.getSourcePath());
                    edge.setCreatedAt(OffsetDateTime.now());

                    edgeBatch.add(edge);

                    if (edgeBatch.size() >= EDGE_BATCH_SIZE) {
                        dependencyEdgeRepository.saveAll(edgeBatch);
                        edgesSaved += edgeBatch.size();
                        edgeBatch.clear();
                    }
                }

                filesProcessed++;
                snapshot.setFilesScanned(filesProcessed);
            } catch (Exception e) {
                skippedFiles++;
                String msg = "Пропущен общий модуль: " + file
                        + " | rel=" + nvl(rel)
                        + " | decodedRel=" + nvl(decodedRel)
                        + " | error=" + errorMessage(e);

                skippedFileMessages.add(msg);
                saveScanLog(snapshot, "WARN", "COMMON_MODULE_SKIPPED", decodedRel, msg, e);
                logSkippedFile(file, rel, decodedRel, e, "COMMON_MODULE_SKIPPED");
            }
        }
        if (!edgeBatch.isEmpty()) {
            dependencyEdgeRepository.saveAll(edgeBatch);
            edgesSaved += edgeBatch.size();
            edgeBatch.clear();
        }

        Map<String, Set<String>> exportToReachableMembers =
                buildExportReachability(membersByFullName, exportMembersByModule, forwardEdges);

      //  log.info("[DEP-SCAN:LOG_VERSION] export-index-debug-v1");
        logExportIndex(exportMembersByModule);
       // log.info("[DEP-SCAN:OBJECT_FILES] count={}", objectFiles.size());

        //Set<ImpactKey> impactDedup = new LinkedHashSet<>();
        Set<ImpactKey> impactDedup = new LinkedHashSet<>();

        for (Path file : objectFiles) {

            String rel = null;
            String decodedRel = null;

            try {
                rel = normalizeRel(root.relativize(file));
                decodedRel = decodeRelativePath(rel);

                String text = readText(file);
                BslDependencyParser.ParsedObjectUsages parsed =
                        bslDependencyParser.parseObjectExportUsages(file, text, root, exportMembersByModule);

                logParsedObjectUsages(parsed);

                if (parsed == null || parsed.getUsages() == null || parsed.getUsages().isEmpty()) {
                    filesProcessed++;
                    snapshot.setFilesScanned(filesProcessed);
                    continue;
                }

                for (BslDependencyParser.ObjectExportUsage usage : parsed.getUsages()) {
                    if (isBlank(usage.getExportModule()) || isBlank(usage.getExportMember())) {
                        continue;
                    }

                    String exportFull = usage.getExportModule() + "." + usage.getExportMember();
                    Set<String> affectedMembers = exportToReachableMembers.getOrDefault(exportFull, Set.of(exportFull));

                    for (String affectedFull : affectedMembers) {
                        MemberRef affected = membersByFullName.get(affectedFull);
                        if (affected == null) {
                            continue;
                        }

                        ImpactKey impactKey = new ImpactKey(
                                affected.moduleName(),
                                parsed.getObjectType(),
                                parsed.getObjectName()
                        );

                        if (!impactDedup.add(impactKey)) {
                            continue;
                        }

                        CommonModuleImpact impact = new CommonModuleImpact();
                        impact.setSnapshot(snapshot);
                        impact.setCommonModuleName(affected.moduleName());
                        impact.setCommonModuleMemberName(null);
                        impact.setObjectType(parsed.getObjectType());
                        impact.setObjectName(parsed.getObjectName());
                        impact.setSourcePath(null);
                        impact.setViaModule(null);
                        impact.setViaMember(null);
                        impact.setCreatedAt(OffsetDateTime.now());
                        //impacts.add(impact);
                        impactBatch.add(impact);

                        if (impactBatch.size() >= IMPACT_BATCH_SIZE) {
                            commonModuleImpactRepository.saveAll(impactBatch);
                            impactsSaved += impactBatch.size();
                            impactBatch.clear();
                        }
                    }
                }

                filesProcessed++;
                snapshot.setFilesScanned(filesProcessed);
            } catch (Exception e) {
                skippedFiles++;
                String msg = "Пропущен объектный модуль: " + file
                        + " | rel=" + nvl(rel)
                        + " | decodedRel=" + nvl(decodedRel)
                        + " | error=" + errorMessage(e);

                skippedFileMessages.add(msg);
                saveScanLog(snapshot, "WARN", "OBJECT_FILE_SKIPPED", decodedRel, msg, e);
              //  log.warn("[DEP-SCAN:OBJECT_FILE_SKIPPED] {}", msg);
            }
        }
        if (!impactBatch.isEmpty()) {
            commonModuleImpactRepository.saveAll(impactBatch);
            impactsSaved += impactBatch.size();
            impactBatch.clear();
        }

      //  log.info("[DEP-SCAN:BUILD_COMPLETE] processed={} | skipped={} | edges={} | impacts={} | path={}",
        //        filesProcessed, skippedFiles, edges.size(), impactsSaved, root);

        int previewLimit = Math.min(skippedFileMessages.size(), 50);
        for (int i = 0; i < previewLimit; i++) {
          //  log.warn("[DEP-SCAN:SKIPPED_FILE] {}", skippedFileMessages.get(i));
        }
        if (skippedFileMessages.size() > previewLimit) {
            //log.warn("[DEP-SCAN:SKIPPED_FILE] Всего пропущено файлов: {}. В лог выведены первые {}.",
              //      skippedFileMessages.size(), previewLimit);
        }

        return new BuildArtifacts(filesProcessed, skippedFiles, skippedFileMessages, edgesSaved, impactsSaved);
    }

    private Map<String, Set<String>> buildExportReachability(
            Map<String, MemberRef> membersByFullName,
            Map<String, Set<String>> exportMembersByModule,
            Map<String, Set<String>> forwardEdges
    ) {
        Map<String, Set<String>> exportToReachable = new LinkedHashMap<>();

        for (Map.Entry<String, Set<String>> entry : exportMembersByModule.entrySet()) {
            String moduleLower = entry.getKey();

            for (String memberLower : entry.getValue()) {
                MemberRef exportRef = findMember(membersByFullName, moduleLower, memberLower);
                if (exportRef == null) {
                    //log.warn("[DEP-SCAN:EXPORT_REACHABILITY_MISS] moduleLower={} | memberLower={}",
                     //       moduleLower, memberLower);
                    continue;
                }

                Set<String> visited = new LinkedHashSet<>();
                Deque<String> stack = new ArrayDeque<>();
                stack.push(exportRef.fullName());

                while (!stack.isEmpty()) {
                    String current = stack.pop();
                    if (!visited.add(current)) {
                        continue;
                    }

                    for (String next : forwardEdges.getOrDefault(current, Set.of())) {
                        stack.push(next);
                    }
                }

                exportToReachable.put(exportRef.fullName(), visited);

             //   log.info("[DEP-SCAN:EXPORT_REACHABILITY_ITEM] export={} | reachable={}",
               //         exportRef.fullName(),
                 //       visited);
            }
        }

    //    log.info("[DEP-SCAN:EXPORT_REACHABILITY] exportsIndexed={}", exportToReachable.size());
        return exportToReachable;
    }

    private MemberRef findMember(Map<String, MemberRef> membersByFullName, String moduleLower, String memberLower) {
        for (MemberRef ref : membersByFullName.values()) {
            if (ref.moduleName().toLowerCase(Locale.ROOT).equals(moduleLower)
                    && ref.memberName().toLowerCase(Locale.ROOT).equals(memberLower)) {
                return ref;
            }
        }
        return null;
    }

    private String readText(Path file) throws IOException {
        long size = Files.size(file);

        if (size > MAX_BSL_FILE_SIZE_BYTES) {
            throw new IOException("Файл слишком большой для разбора: " + file + ", size=" + size + " bytes");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private Set<String> collectKnownCommonModules(Path sourceRoot, List<Path> commonModuleFiles) {
        Set<String> modules = new LinkedHashSet<>();
        for (Path file : commonModuleFiles) {
            Path rel = sourceRoot.relativize(file);
            if (rel.getNameCount() < 2) {
                continue;
            }
            modules.add(oneCNameDecoder.decodePathSegment(rel.getName(1).toString()));
        }
        return modules;
    }

    private boolean belongsToAllowedRoot(Path sourceRoot, Path file) {
        Path rel = sourceRoot.relativize(file);
        if (rel.getNameCount() == 0) {
            return false;
        }
        String first = oneCNameDecoder.decodePathSegment(rel.getName(0).toString());
        return ALLOWED_ROOTS.contains(first);
    }

    private boolean isRoot(Path sourceRoot, Path file, String expectedRoot) {
        Path rel = sourceRoot.relativize(file);
        if (rel.getNameCount() == 0) {
            return false;
        }
        String first = oneCNameDecoder.decodePathSegment(rel.getName(0).toString());
        return expectedRoot.equalsIgnoreCase(first);
    }

    private String normalizeRel(Path relPath) {
        return relPath.toString().replace('\\', '/');
    }

    private String decodeRelativePath(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return relPath;
        }

        return Stream.of(relPath.split("/"))
                .map(oneCNameDecoder::decodePathSegment)
                .collect(Collectors.joining("/"));
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
                .limit(50)
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record BuildArtifacts(
            int filesScanned,
            int skippedFiles,
            List<String> skippedFileMessages,
            int edgesSaved,
            int impactsSaved
    ) {
    }

    private record MemberRef(String moduleName, String memberName, boolean exported) {
        String fullName() {
            return moduleName + "." + memberName;
        }
    }

    private void logParsedCommonModule(BslDependencyParser.ParsedCommonModule parsed) {
        if (parsed == null) {
            return;
        }

        List<String> members = parsed.getMembers() == null
                ? List.of()
                : parsed.getMembers().stream()
                .map(m -> m.getMemberName() + (m.isExported() ? " [export]" : ""))
                .sorted()
                .toList();

        List<String> exports = parsed.getMembers() == null
                ? List.of()
                : parsed.getMembers().stream()
                .filter(BslDependencyParser.MemberDefinition::isExported)
                .map(BslDependencyParser.MemberDefinition::getMemberName)
                .sorted()
                .toList();

        List<String> calls = parsed.getCalls() == null
                ? List.of()
                : parsed.getCalls().stream()
                .map(c -> c.getCallerModule() + "." + nvl(c.getCallerMember())
                        + " -> "
                        + c.getCalleeModule() + "." + nvl(c.getCalleeMember()))
                .sorted()
                .toList();

      /*  log.info("[DEP-SCAN:COMMON_MODULE_PARSED] module={} | path={} | membersCount={} | exportsCount={} | callsCount={}",
                parsed.getModuleName(),
                parsed.getSourcePath(),
                members.size(),
                exports.size(),
                calls.size());

        log.info("[DEP-SCAN:COMMON_MODULE_MEMBERS] module={} | members={}",
                parsed.getModuleName(),
                members);

        log.info("[DEP-SCAN:COMMON_MODULE_EXPORTS] module={} | exports={}",
                parsed.getModuleName(),
                exports);*/

        int callsLimit = Math.min(calls.size(), 100);
        if (!calls.isEmpty()) {
           /* log.info("[DEP-SCAN:COMMON_MODULE_CALLS] module={} | showingFirst={} | calls={}",
                    parsed.getModuleName(),
                    callsLimit,
                    calls.subList(0, callsLimit));

            if (calls.size() > callsLimit) {
                log.info("[DEP-SCAN:COMMON_MODULE_CALLS] module={} | totalCalls={} | truncated=true",
                        parsed.getModuleName(),
                        calls.size());
            }*/
        }
    }

    private void logParsedObjectUsages(BslDependencyParser.ParsedObjectUsages parsed) {
        if (parsed == null) {
            return;
        }

        List<String> usages = parsed.getUsages() == null
                ? List.of()
                : parsed.getUsages().stream()
                .map(u -> u.getExportModule() + "." + u.getExportMember())
                .sorted()
                .toList();

     /*   log.info("[DEP-SCAN:OBJECT_USAGE] type={} | object={} | path={} | usagesCount={}",
                parsed.getObjectType(),
                parsed.getObjectName(),
                parsed.getSourcePath(),
                usages.size());*/

        int limit = Math.min(usages.size(), 20);
        if (limit > 0) {
           /* log.info("[DEP-SCAN:OBJECT_USAGE_DETAILS] type={} | object={} | showingFirst={} | usages={}",
                    parsed.getObjectType(),
                    parsed.getObjectName(),
                    limit,
                    usages.subList(0, limit));*/
        }

        if (usages.size() > limit) {
          /*  log.info("[DEP-SCAN:OBJECT_USAGE_DETAILS] type={} | object={} | totalUsages={} | truncated=true",
                    parsed.getObjectType(),
                    parsed.getObjectName(),
                    usages.size());*/
        }
    }

    private void logSkippedFile(Path file, String rel, String decodedRel, Exception e, String phase) {
      /*  log.warn("[DEP-SCAN:{}] file={} | rel={} | decodedRel={} | error={}",
                phase,
                file,
                nvl(rel),
                nvl(decodedRel),
                errorMessage(e));*/
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private void logExportIndex(Map<String, Set<String>> exportMembersByModule) {
     /*   log.info("[DEP-SCAN:EXPORT_INDEX] modulesWithExports={}", exportMembersByModule.size());

        exportMembersByModule.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> log.info("[DEP-SCAN:EXPORT_INDEX_MODULE] module={} | exports={}",
                        e.getKey(),
                        e.getValue().stream().sorted().toList()));*/
    }
    private record ImpactKey(
            String commonModuleName,
            DependencyCallerType objectType,
            String objectName
    ) { }

    @Transactional(readOnly = true)
    public List<?> findRows(String mode, String q, DependencyCallerType objectType) {
        if (isBlank(q)) {
            return List.of();
        }

        String moduleName = q.trim();

        // Основной сценарий: ищем по общему модулю -> получаем объекты
        List<DependencyTreeSearchService.AffectedObject> affectedObjects = dependencyTreeSearchService.findAffectedObjectsByCommonModule(moduleName);

        if (objectType != null) {
            affectedObjects = affectedObjects.stream()
                    .filter(x -> x.getObjectType() == objectType)
                    .toList();
        }

        // Для остальных режимов возвращаем просто список объектов
        return affectedObjects;
    }
    private void saveScanLog(
            DependencySnapshot snapshot,
            String level,
            String phase,
            String sourcePath,
            String message,
            Throwable error
    ) {
        if (snapshot == null) {
            return;
        }

        DependencyScanLog row = new DependencyScanLog();
        row.setSnapshot(snapshot);
        row.setLevel(level);
        row.setPhase(phase);
        row.setSourcePath(sourcePath);
        row.setMessage(message);
        row.setStacktrace(error == null ? null : errorMessage(error));
        row.setCreatedAt(OffsetDateTime.now());

        dependencyScanLogRepository.save(row);
    }

}