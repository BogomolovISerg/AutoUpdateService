package git.autoupdateservice.service;

import git.autoupdateservice.domain.*;
import git.autoupdateservice.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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

    private static final int IMPACT_BATCH_SIZE = 5000;
    private static final int EDGE_BATCH_SIZE = 5000;
    private static final long MAX_BSL_FILE_SIZE_BYTES = 12L * 1024L * 1024L;

    private final DependencyGraphStateService dependencyGraphStateService;
    private final CodeSourceRootRepository codeSourceRootRepository;
    private final DependencySnapshotRepository dependencySnapshotRepository;
    private final DependencyEdgeRepository dependencyEdgeRepository;
    private final CommonModuleImpactRepository commonModuleImpactRepository;
    private final DependencyCallExclusionRepository dependencyCallExclusionRepository;
    private final BslDependencyParser bslDependencyParser;
    private final DependencySourceRootService dependencySourceRootService;
    private final OneCNameDecoder oneCNameDecoder;
    private final DependencyTreeSearchService dependencyTreeSearchService;
    private final DependencyScanLogRepository dependencyScanLogRepository;
    private final ChangedObjectService changedObjectService;
    private final DependencySnapshotCleanupService dependencySnapshotCleanupService;

    public DependencySnapshot fullRebuild() {
        CodeSourceRoot sourceRoot = codeSourceRootRepository
                .findFirstBySourceKindAndEnabledIsTrue(SourceKind.BASE)
                .orElseThrow(() -> new IllegalStateException("Не настроен активный источник основной конфигурации"));
        return fullRebuild(sourceRoot.getId());
    }

    public DependencySnapshot fullRebuild(UUID sourceRootId) {
        CodeSourceRoot baseSourceRoot = codeSourceRootRepository.findById(sourceRootId)
                .orElseThrow(() -> new IllegalArgumentException("Источник кода не найден: " + sourceRootId));

        DependencySnapshot snapshot = new DependencySnapshot();
        snapshot.setSourceRoot(baseSourceRoot);
        snapshot.setStatus(DependencySnapshotStatus.BUILDING);
        snapshot.setBuildMode(DependencyBuildMode.FULL);
        snapshot.setStartedAt(OffsetDateTime.now());
        snapshot.setFilesScanned(0);
        snapshot.setNotes("Запущено полное сканирование");
        snapshot = dependencySnapshotRepository.save(snapshot);

        try {
            List<DependencySourceRootService.ScanRoot> scanRoots = dependencySourceRootService.collectScanRoots(baseSourceRoot);
            saveScanLog(snapshot, "INFO", "START", baseSourceRoot.getRootPath(),
                    "Запущено полное сканирование. Источников=" + scanRoots.size(), null);

            BuildArtifacts artifacts = scanSources(scanRoots, snapshot);

            snapshot.setFilesScanned(artifacts.filesScanned());
            snapshot.setStatus(DependencySnapshotStatus.READY);
            snapshot.setFinishedAt(OffsetDateTime.now());
            snapshot.setNotes(buildFinishNotes(artifacts));
            snapshot = dependencySnapshotRepository.save(snapshot);

            List<DependencyGraphDirtyItem> dirtyItems = dependencyGraphStateService.pendingDirtyItems();
            changedObjectService.registerObjectsFromDirtyModules(snapshot, dirtyItems, null);
            dependencyGraphStateService.markSnapshotReady(snapshot);
            dependencyGraphStateService.markDirtyItemsProcessed(dirtyItems);
            saveScanLog(snapshot, "INFO", "FINISH", baseSourceRoot.getRootPath(),
                    "Полное сканирование завершено. Обработано=" + artifacts.filesScanned()
                            + ", пропущено=" + artifacts.skippedFiles()
                            + ", edges=" + artifacts.edgesSaved()
                            + ", impacts=" + artifacts.impactsSaved(),
                    null);
            dependencySnapshotCleanupService.cleanupOldSnapshotsAsync(snapshot.getId());
            return snapshot;
        } catch (Exception e) {
            snapshot.setStatus(DependencySnapshotStatus.FAILED);
            snapshot.setFinishedAt(OffsetDateTime.now());
            snapshot.setNotes(errorMessage(e));
            snapshot = dependencySnapshotRepository.save(snapshot);

            dependencyEdgeRepository.deleteBySnapshot(snapshot);
            commonModuleImpactRepository.deleteBySnapshot(snapshot);
            saveScanLog(snapshot, "ERROR", "FAILED", baseSourceRoot.getRootPath(),
                    "Сканирование завершилось ошибкой: " + errorMessage(e), e);
            return snapshot;
        }
    }

    private BuildArtifacts scanSources(List<DependencySourceRootService.ScanRoot> scanRoots, DependencySnapshot snapshot) throws IOException {
        if (scanRoots == null || scanRoots.isEmpty()) {
            throw new IllegalStateException("Нет источников для сканирования");
        }

        DependencySourceRootService.DiscoveredFiles discoveredFiles = dependencySourceRootService.discoverBslFiles(scanRoots);
        Map<DependencySourceRootService.ScanRoot, List<Path>> commonModuleFilesByRoot = discoveredFiles.commonModuleFilesByRoot();
        Map<DependencySourceRootService.ScanRoot, List<Path>> objectFilesByRoot = discoveredFiles.objectFilesByRoot();
        List<String> skippedFileMessages = new ArrayList<>();

        for (DependencySourceRootService.ScanRoot sourceRoot : scanRoots) {
            saveScanLog(snapshot, "INFO", "DISCOVER", sourceRoot.path().toString(),
                    "Источник " + sourceRoot.sourceName() + ": найдено файлов="
                            + (commonModuleFilesByRoot.getOrDefault(sourceRoot, List.of()).size()
                            + objectFilesByRoot.getOrDefault(sourceRoot, List.of()).size()),
                    null);
        }

        Set<String> knownCommonModules = collectKnownCommonModules(commonModuleFilesByRoot);
        Set<String> excludedCalls = dependencyCallExclusionRepository.findAllByEnabledIsTrueOrderByCallNameAsc().stream()
                .map(DependencyCallExclusion::getCallName)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        GraphContext graph = new GraphContext();
        int filesProcessed = 0;
        int skippedFiles = 0;
        int edgesSaved = 0;
        int impactsSaved = 0;

        List<DependencyEdge> edgeBatch = new ArrayList<>(EDGE_BATCH_SIZE);
        List<CommonModuleImpact> impactBatch = new ArrayList<>(IMPACT_BATCH_SIZE);

        for (Map.Entry<DependencySourceRootService.ScanRoot, List<Path>> entry : commonModuleFilesByRoot.entrySet()) {
            DependencySourceRootService.ScanRoot sourceRoot = entry.getKey();
            for (Path file : entry.getValue()) {
                String rel = null;
                String decodedRel = null;
                try {
                    rel = dependencySourceRootService.normalizeRelativePath(sourceRoot.path(), file);
                    decodedRel = dependencySourceRootService.decodeRelativePath(rel);

                    String text = readText(file);
                    BslDependencyParser.ParsedCommonModule parsed = bslDependencyParser.parseCommonModule(
                            file,
                            text,
                            sourceRoot.path(),
                            knownCommonModules,
                            excludedCalls
                    );

                    if (parsed == null) {
                        continue;
                    }

                    for (BslDependencyParser.MemberDefinition member : safeList(parsed.getMembers())) {
                        MemberNode memberNode = MemberNode.from(sourceRoot, parsed.getModuleName(), member);
                        graph.addMember(memberNode);
                    }

                    OffsetDateTime processedAt = OffsetDateTime.now();
                    for (BslDependencyParser.ModuleCall call : safeList(parsed.getCalls())) {
                        if (isBlank(call.getCallerModule())
                                || isBlank(call.getCallerMember())
                                || isBlank(call.getCalleeModule())
                                || isBlank(call.getCalleeMember())) {
                            continue;
                        }

                        MemberNode caller = graph.findPhysicalMember(sourceRoot, call.getCallerModule(), call.getCallerMember());
                        if (caller == null) {
                            continue;
                        }

                        if (call.isLogicalCall()) {
                            graph.addLogicalEdge(caller.nodeKey(), logicalKey(call.getCalleeModule(), call.getCalleeMember()));
                        } else {
                            MemberNode callee = graph.findPhysicalMember(sourceRoot, call.getCalleeModule(), call.getCalleeMember());
                            if (callee != null) {
                                graph.addPhysicalEdge(caller.nodeKey(), callee.nodeKey());
                            }
                        }

                        DependencyEdge edge = new DependencyEdge();
                        edge.setSnapshot(snapshot);
                        edge.setCallerType(DependencyCallerType.COMMON_MODULE);
                        edge.setCallerName(call.getCallerModule());
                        edge.setCallerMember(call.getCallerMember());
                        edge.setCalleeModule(call.getCalleeModule());
                        edge.setCalleeMember(call.getCalleeMember());
                        edge.setSourcePath(call.getSourcePath());
                        edge.setCreatedAt(processedAt);
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
                    String msg = "Пропущен общий модуль [" + sourceRoot.sourceName() + "]: " + file
                            + " | rel=" + nvl(rel)
                            + " | decodedRel=" + nvl(decodedRel)
                            + " | error=" + errorMessage(e);
                    skippedFileMessages.add(msg);
                    saveScanLog(snapshot, "WARN", "COMMON_MODULE_SKIPPED", decodedRel, msg, e);
                }
            }
        }

        if (!edgeBatch.isEmpty()) {
            dependencyEdgeRepository.saveAll(edgeBatch);
            edgesSaved += edgeBatch.size();
            edgeBatch.clear();
        }
        graph.buildTreeEntries();
        Map<DependencySourceRootService.ObjectRef, List<ObjectFileRef>> objectFilesByOwner = groupObjectFiles(objectFilesByRoot);
        Map<String, Set<String>> exportMembersByModule = graph.exportMembersByModule();

        for (List<ObjectFileRef> ownerFiles : objectFilesByOwner.values()) {
            Set<ImpactKey> objectImpactDedup = new HashSet<>();

            for (ObjectFileRef ownerFile : ownerFiles) {
                DependencySourceRootService.ScanRoot sourceRoot = ownerFile.sourceRoot();
                Path file = ownerFile.file();
                String rel = null;
                String decodedRel = null;

                try {
                    rel = dependencySourceRootService.normalizeRelativePath(sourceRoot.path(), file);
                    decodedRel = dependencySourceRootService.decodeRelativePath(rel);

                    String text = readText(file);
                    BslDependencyParser.ParsedObjectUsages parsed = bslDependencyParser.parseObjectExportUsages(
                            file,
                            text,
                            sourceRoot.path(),
                            exportMembersByModule
                    );

                    if (parsed == null || parsed.getUsages() == null || parsed.getUsages().isEmpty()) {
                        filesProcessed++;
                        snapshot.setFilesScanned(filesProcessed);
                        continue;
                    }

                    OffsetDateTime processedAt = OffsetDateTime.now();
                    for (BslDependencyParser.ObjectExportUsage usage : parsed.getUsages()) {
                        if (isBlank(usage.getExportModule()) || isBlank(usage.getExportMember())) {
                            continue;
                        }

                        String logicalEntryKey = logicalKey(usage.getExportModule(), usage.getExportMember());
                        Set<String> affectedNodes = graph.resolveReachableMembers(logicalEntryKey);
                        if (affectedNodes.isEmpty()) {
                            continue;
                        }

                        for (String nodeKey : affectedNodes) {
                            MemberNode affected = graph.memberByNodeKey(nodeKey);
                            if (affected == null) {
                                continue;
                            }

                            Set<TreeEntry> treeEntries = graph.treeEntriesForNode(nodeKey);
                            if (treeEntries.isEmpty()) {
                                continue;
                            }

                            for (TreeEntry treeEntry : treeEntries) {
                                String entryMemberName = treeEntry.memberName();
                                if (isBlank(entryMemberName)) {
                                    continue;
                                }

                                ImpactKey impactKey = new ImpactKey(
                                        affected.sourceKind(),
                                        affected.sourceName(),
                                        affected.moduleName(),
                                        entryMemberName,
                                        parsed.getObjectType(),
                                        parsed.getObjectName()
                                );
                                if (!objectImpactDedup.add(impactKey)) {
                                    continue;
                                }

                                CommonModuleImpact impact = new CommonModuleImpact();
                                impact.setSnapshot(snapshot);
                                impact.setSourceKind(affected.sourceKind());
                                impact.setSourceName(affected.sourceName());
                                impact.setCommonModuleName(affected.moduleName());
                                impact.setCommonModuleMemberName(entryMemberName);
                                impact.setObjectType(parsed.getObjectType());
                                impact.setObjectName(parsed.getObjectName());
                                impact.setSourcePath(parsed.getSourcePath());
                                impact.setViaModule(usage.getExportModule());
                                impact.setViaMember(usage.getExportMember());
                                impact.setCreatedAt(processedAt);
                                impactBatch.add(impact);

                                if (impactBatch.size() >= IMPACT_BATCH_SIZE) {
                                    commonModuleImpactRepository.saveAll(impactBatch);
                                    impactsSaved += impactBatch.size();
                                    impactBatch.clear();
                                }
                            }

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
                    String msg = "Пропущен объектный модуль [" + sourceRoot.sourceName() + "]: " + file
                            + " | rel=" + nvl(rel)
                            + " | decodedRel=" + nvl(decodedRel)
                            + " | error=" + errorMessage(e);
                    skippedFileMessages.add(msg);
                    saveScanLog(snapshot, "WARN", "OBJECT_FILE_SKIPPED", decodedRel, msg, e);
                }
            }
        }

        if (!impactBatch.isEmpty()) {
            commonModuleImpactRepository.saveAll(impactBatch);
            impactsSaved += impactBatch.size();
            impactBatch.clear();
        }

        return new BuildArtifacts(
                filesProcessed,
                skippedFiles,
                skippedFileMessages,
                edgesSaved,
                impactsSaved,
                scanRoots.size(),
                discoveredFiles.discoveredFiles()
        );
    }

    private Map<DependencySourceRootService.ObjectRef, List<ObjectFileRef>> groupObjectFiles(
            Map<DependencySourceRootService.ScanRoot, List<Path>> objectFilesByRoot
    ) {
        Map<DependencySourceRootService.ObjectRef, List<ObjectFileRef>> grouped = new LinkedHashMap<>();
        for (Map.Entry<DependencySourceRootService.ScanRoot, List<Path>> entry : objectFilesByRoot.entrySet()) {
            DependencySourceRootService.ScanRoot sourceRoot = entry.getKey();
            for (Path file : entry.getValue()) {
                DependencySourceRootService.ObjectRef owner = dependencySourceRootService.determineObjectRef(sourceRoot.path(), file);
                if (owner == null) {
                    continue;
                }
                grouped.computeIfAbsent(owner, k -> new ArrayList<>()).add(new ObjectFileRef(sourceRoot, file));
            }
        }
        return grouped;
    }

    private Set<String> collectKnownCommonModules(Map<DependencySourceRootService.ScanRoot, List<Path>> commonModuleFilesByRoot) {
        Set<String> modules = new LinkedHashSet<>();
        for (Map.Entry<DependencySourceRootService.ScanRoot, List<Path>> entry : commonModuleFilesByRoot.entrySet()) {
            DependencySourceRootService.ScanRoot sourceRoot = entry.getKey();
            for (Path file : entry.getValue()) {
                Path rel = sourceRoot.path().relativize(file);
                if (rel.getNameCount() < 2) {
                    continue;
                }
                modules.add(oneCNameDecoder.decodePathSegment(rel.getName(1).toString()));
            }
        }
        return modules;
    }

    private String readText(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_BSL_FILE_SIZE_BYTES) {
            throw new IOException("Файл слишком большой для разбора: " + file + ", size=" + size + " bytes");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private String buildFinishNotes(BuildArtifacts artifacts) {
        String base = "Полное сканирование выполнено. Источников: " + artifacts.sourcesScanned()
                + ", найдено файлов: " + artifacts.discoveredFiles()
                + ", обработано: " + artifacts.filesScanned()
                + ", пропущено: " + artifacts.skippedFiles();

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

    private <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static String logicalKey(String moduleName, String memberName) {
        return moduleName.toLowerCase(Locale.ROOT) + "." + memberName.toLowerCase(Locale.ROOT);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    @Transactional(readOnly = true)
    public List<?> findRows(String mode, String q, DependencyCallerType objectType) {
        if (isBlank(q)) {
            return List.of();
        }

        String moduleName = q.trim();
        List<DependencyTreeSearchService.AffectedObject> affectedObjects = dependencyTreeSearchService.findAffectedObjectsByCommonModule(moduleName);
        if (objectType != null) {
            affectedObjects = affectedObjects.stream()
                    .filter(x -> x.getObjectType() == objectType)
                    .toList();
        }
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

    private record ObjectFileRef(DependencySourceRootService.ScanRoot sourceRoot, Path file) {
    }

    private record BuildArtifacts(
            int filesScanned,
            int skippedFiles,
            List<String> skippedFileMessages,
            int edgesSaved,
            int impactsSaved,
            int sourcesScanned,
            int discoveredFiles
    ) {
    }

    private record ImpactKey(
            SourceKind sourceKind,
            String sourceName,
            String commonModuleName,
            String commonModuleMemberName,
            DependencyCallerType objectType,
            String objectName
    ) {
    }

    private record TreeEntry(
            SourceKind sourceKind,
            String sourceName,
            String moduleName,
            String memberName
    ) {
    }
    private record MemberNode(
            String nodeKey,
            SourceKind sourceKind,
            String sourceName,
            String moduleName,
            String actualMemberName,
            String effectiveMemberName,
            boolean exportedEntry,
            boolean extensionHook,
            BslDependencyParser.AnnotationMode annotationMode,
            boolean continueCall
    ) {
        static MemberNode from(DependencySourceRootService.ScanRoot sourceRoot, String moduleName, BslDependencyParser.MemberDefinition member) {
            String actualMemberName = member.getMemberName();
            String effectiveMemberName = member.getEffectiveMemberName() == null || member.getEffectiveMemberName().isBlank()
                    ? actualMemberName
                    : member.getEffectiveMemberName();
            String nodeKey = sourceRoot.sourceKind().name()
                    + "|" + sourceRoot.sourceName().toLowerCase(Locale.ROOT)
                    + "|" + moduleName.toLowerCase(Locale.ROOT)
                    + "|" + actualMemberName.toLowerCase(Locale.ROOT);
            boolean exportedEntry = member.isExported() && !member.isExtensionHook();
            return new MemberNode(
                    nodeKey,
                    sourceRoot.sourceKind(),
                    sourceRoot.sourceName(),
                    moduleName,
                    actualMemberName,
                    effectiveMemberName,
                    exportedEntry,
                    member.isExtensionHook(),
                    member.getAnnotationMode(),
                    member.isContinueCall()
            );
        }

        String logicalKey() {
            return DependencyTreeBuildService.logicalKey(moduleName, effectiveMemberName);
        }

        String impactMemberName() {
            return extensionHook ? effectiveMemberName : actualMemberName;
        }
    }

    private final class GraphContext {
        private final Map<String, MemberNode> membersByNodeKey = new HashMap<>();
        private final Map<String, String> physicalLookup = new HashMap<>();
        private final Map<String, Set<String>> physicalEdges = new HashMap<>();
        private final Map<String, Set<String>> logicalEdges = new HashMap<>();
        private final Map<String, List<String>> membersByLogicalKey = new HashMap<>();
        private final Map<String, Set<String>> exportMembersByModule = new HashMap<>();
        private final Map<String, Set<String>> activeEntryCache = new HashMap<>();
        private final Map<String, Set<String>> reachableCache = new HashMap<>();
        private final Map<String, Set<TreeEntry>> treeEntriesByNodeKey = new HashMap<>();

        void addMember(MemberNode member) {
            membersByNodeKey.put(member.nodeKey(), member);
            physicalLookup.put(physicalLookupKey(member.sourceKind(), member.sourceName(), member.moduleName(), member.actualMemberName()), member.nodeKey());
            membersByLogicalKey.computeIfAbsent(member.logicalKey(), k -> new ArrayList<>()).add(member.nodeKey());
            if (member.exportedEntry()) {
                exportMembersByModule
                        .computeIfAbsent(member.moduleName().toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>())
                        .add(member.effectiveMemberName().toLowerCase(Locale.ROOT));
            }
        }

        MemberNode findPhysicalMember(DependencySourceRootService.ScanRoot sourceRoot, String moduleName, String actualMemberName) {
            String nodeKey = physicalLookup.get(physicalLookupKey(sourceRoot.sourceKind(), sourceRoot.sourceName(), moduleName, actualMemberName));
            return nodeKey == null ? null : membersByNodeKey.get(nodeKey);
        }

        void addPhysicalEdge(String fromNodeKey, String toNodeKey) {
            physicalEdges.computeIfAbsent(fromNodeKey, k -> new LinkedHashSet<>()).add(toNodeKey);
        }

        void addLogicalEdge(String fromNodeKey, String logicalEntryKey) {
            logicalEdges.computeIfAbsent(fromNodeKey, k -> new LinkedHashSet<>()).add(logicalEntryKey);
        }

        MemberNode memberByNodeKey(String nodeKey) {
            return membersByNodeKey.get(nodeKey);
        }

        Map<String, Set<String>> exportMembersByModule() {
            return exportMembersByModule;
        }

        Set<String> resolveReachableMembers(String logicalEntryKey) {
            return reachableCache.computeIfAbsent(logicalEntryKey, this::computeReachableMembers);
        }

        private Set<String> computeReachableMembers(String logicalEntryKey) {
            LinkedHashSet<String> visited = new LinkedHashSet<>();
            Deque<String> stack = new ArrayDeque<>(resolveActiveEntryMembers(logicalEntryKey));

            while (!stack.isEmpty()) {
                String current = stack.pop();
                if (!visited.add(current)) {
                    continue;
                }

                for (String next : physicalEdges.getOrDefault(current, Set.of())) {
                    stack.push(next);
                }
                for (String nextLogical : logicalEdges.getOrDefault(current, Set.of())) {
                    for (String nextPhysical : resolveActiveEntryMembers(nextLogical)) {
                        stack.push(nextPhysical);
                    }
                }
            }

            return visited;
        }

        private Set<String> resolveActiveEntryMembers(String logicalEntryKey) {
            return activeEntryCache.computeIfAbsent(logicalEntryKey, this::computeActiveEntryMembers);
        }

        private Set<String> computeActiveEntryMembers(String logicalEntryKey) {
            List<MemberNode> nodes = membersByLogicalKey.getOrDefault(logicalEntryKey, List.of()).stream()
                    .map(membersByNodeKey::get)
                    .filter(Objects::nonNull)
                    .toList();

            List<String> baseNodes = nodes.stream()
                    .filter(x -> x.sourceKind() == SourceKind.BASE)
                    .map(MemberNode::nodeKey)
                    .toList();

            List<MemberNode> extensionHookNodes = nodes.stream()
                    .filter(x -> x.sourceKind() == SourceKind.EXTENSION)
                    .filter(MemberNode::extensionHook)
                    .toList();

            List<String> extensionStandaloneNodes = nodes.stream()
                    .filter(x -> x.sourceKind() == SourceKind.EXTENSION)
                    .filter(x -> !x.extensionHook())
                    .map(MemberNode::nodeKey)
                    .toList();

            LinkedHashSet<String> active = new LinkedHashSet<>();
            if (!baseNodes.isEmpty() || !extensionHookNodes.isEmpty()) {
                extensionHookNodes.stream().map(MemberNode::nodeKey).forEach(active::add);

                boolean includeBase = extensionHookNodes.isEmpty()
                        || extensionHookNodes.stream().anyMatch(x -> x.annotationMode() != BslDependencyParser.AnnotationMode.INSTEAD || x.continueCall());
                if (includeBase) {
                    active.addAll(baseNodes);
                }
            } else {
                active.addAll(extensionStandaloneNodes);
            }

            if (active.isEmpty()) {
                active.addAll(extensionStandaloneNodes);
            }
            return active;
        }

        private String physicalLookupKey(SourceKind sourceKind, String sourceName, String moduleName, String actualMemberName) {
            return sourceKind.name()
                    + "|" + sourceName.toLowerCase(Locale.ROOT)
                    + "|" + moduleName.toLowerCase(Locale.ROOT)
                    + "|" + actualMemberName.toLowerCase(Locale.ROOT);
        }
        void buildTreeEntries() {
            for (MemberNode member : membersByNodeKey.values()) {
                if (!isTreeEntryCandidate(member)) {
                    continue;
                }

                TreeEntry entry = toTreeEntry(member);

                Deque<String> stack = new ArrayDeque<>();
                Set<String> visited = new HashSet<>();
                stack.push(member.nodeKey());

                while (!stack.isEmpty()) {
                    String currentNodeKey = stack.pop();
                    if (!visited.add(currentNodeKey)) {
                        continue;
                    }

                    MemberNode current = membersByNodeKey.get(currentNodeKey);
                    if (current == null) {
                        continue;
                    }

                    if (sameSourceAndModule(current, entry)) {
                        treeEntriesByNodeKey
                                .computeIfAbsent(currentNodeKey, k -> new LinkedHashSet<>())
                                .add(entry);
                    }

                    for (String next : physicalEdges.getOrDefault(currentNodeKey, Set.of())) {
                        stack.push(next);
                    }

                    for (String nextLogical : logicalEdges.getOrDefault(currentNodeKey, Set.of())) {
                        for (String nextPhysical : resolveActiveEntryMembers(nextLogical)) {
                            stack.push(nextPhysical);
                        }
                    }
                }
            }
        }

        Set<TreeEntry> treeEntriesForNode(String nodeKey) {
            return treeEntriesByNodeKey.getOrDefault(nodeKey, Set.of());
        }
        private boolean isTreeEntryCandidate(MemberNode member) {
            if (member == null) {
                return false;
            }
            return member.exportedEntry() || member.extensionHook();
        }

        private TreeEntry toTreeEntry(MemberNode member) {
            return new TreeEntry(
                    member.sourceKind(),
                    member.sourceName(),
                    member.moduleName(),
                    member.effectiveMemberName()
            );
        }

        private boolean sameSourceAndModule(MemberNode node, TreeEntry entry) {
            if (node == null || entry == null) {
                return false;
            }

            if (node.sourceKind() != entry.sourceKind()) {
                return false;
            }

            if (!Objects.equals(
                    normalizeName(node.sourceName()),
                    normalizeName(entry.sourceName()))) {
                return false;
            }

            return normalizeName(node.moduleName())
                    .equalsIgnoreCase(normalizeName(entry.moduleName()));
        }
    }
    private String normalizeName(String value) {
        return value == null ? "" : value.trim();
    }
}
