package git.autoupdateservice.service;

import git.autoupdateservice.domain.CodeSourceRoot;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.SourceKind;
import git.autoupdateservice.repo.CodeSourceRootRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class DependencySourceRootService {

    private static final Set<String> ALLOWED_ROOTS = Set.of(
            "CommonModules",
            "Catalogs",
            "Documents",
            "Reports",
            "CommonForms",
            "DataProcessors"
    );

    private final CodeSourceRootRepository codeSourceRootRepository;
    private final OneCNameDecoder oneCNameDecoder;

    public List<ScanRoot> collectScanRoots(CodeSourceRoot baseSourceRoot) {
        List<ScanRoot> result = new ArrayList<>();
        result.add(toScanRoot(baseSourceRoot));

        List<CodeSourceRoot> extensionRoots = codeSourceRootRepository
                .findAllBySourceKindAndEnabledIsTrueOrderByPriorityAscSourceNameAsc(SourceKind.EXTENSION);

        for (CodeSourceRoot extensionRoot : extensionRoots) {
            if (extensionRoot.getId() != null && extensionRoot.getId().equals(baseSourceRoot.getId())) {
                continue;
            }
            result.add(toScanRoot(extensionRoot));
        }

        result.sort(Comparator
                .comparing((ScanRoot root) -> root.sourceKind() != SourceKind.BASE)
                .thenComparing(ScanRoot::priority)
                .thenComparing(ScanRoot::sourceName, String.CASE_INSENSITIVE_ORDER));

        return result;
    }

    public DiscoveredFiles discoverBslFiles(List<ScanRoot> scanRoots) throws IOException {
        Map<ScanRoot, List<Path>> commonModuleFilesByRoot = new LinkedHashMap<>();
        Map<ScanRoot, List<Path>> objectFilesByRoot = new LinkedHashMap<>();
        int discoveredFiles = 0;

        for (ScanRoot sourceRoot : scanRoots) {
            if (!Files.isDirectory(sourceRoot.path())) {
                throw new IllegalStateException("Каталог исходников не найден: " + sourceRoot.path());
            }

            List<Path> bslFiles;
            try (Stream<Path> stream = Files.walk(sourceRoot.path())) {
                bslFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bsl"))
                        .filter(path -> belongsToAllowedRoot(sourceRoot.path(), path))
                        .sorted()
                        .toList();
            }

            discoveredFiles += bslFiles.size();
            commonModuleFilesByRoot.put(sourceRoot, bslFiles.stream()
                    .filter(file -> isRoot(sourceRoot.path(), file, "CommonModules"))
                    .toList());
            objectFilesByRoot.put(sourceRoot, bslFiles.stream()
                    .filter(file -> !isRoot(sourceRoot.path(), file, "CommonModules"))
                    .toList());
        }

        return new DiscoveredFiles(commonModuleFilesByRoot, objectFilesByRoot, discoveredFiles);
    }

    public String normalizeRelativePath(Path sourceRoot, Path file) {
        return sourceRoot.relativize(file).toString().replace('\\', '/');
    }

    public String decodeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return relativePath;
        }
        return Stream.of(relativePath.split("/"))
                .map(oneCNameDecoder::decodePathSegment)
                .collect(Collectors.joining("/"));
    }

    public ObjectRef determineObjectRef(Path sourceRoot, Path file) {
        Path relativePath = sourceRoot.relativize(file);
        if (relativePath.getNameCount() < 2) {
            return null;
        }

        String root = oneCNameDecoder.decodePathSegment(relativePath.getName(0).toString());
        String objectName = oneCNameDecoder.decodePathSegment(relativePath.getName(1).toString());

        DependencyCallerType objectType = switch (root.toLowerCase(Locale.ROOT)) {
            case "catalogs" -> DependencyCallerType.CATALOG;
            case "documents" -> DependencyCallerType.DOCUMENT;
            case "reports" -> DependencyCallerType.REPORT;
            case "commonforms" -> DependencyCallerType.COMMON_FORM;
            case "dataprocessors" -> DependencyCallerType.DATA_PROCESSOR;
            default -> null;
        };

        if (objectType == null || objectName == null || objectName.isBlank()) {
            return null;
        }

        return new ObjectRef(objectType, objectName);
    }

    private ScanRoot toScanRoot(CodeSourceRoot root) {
        if (root == null) {
            throw new IllegalArgumentException("Источник кода не задан");
        }

        String sourceName = root.getSourceName();
        if (sourceName == null || sourceName.isBlank()) {
            sourceName = root.getSourceKind() == SourceKind.EXTENSION
                    ? "Расширение"
                    : "Основная конфигурация";
        }

        Path path = Path.of(root.getRootPath()).toAbsolutePath().normalize();
        return new ScanRoot(root, root.getSourceKind(), sourceName.trim(), path, root.getPriority() == null ? 0 : root.getPriority());
    }

    private boolean belongsToAllowedRoot(Path sourceRoot, Path file) {
        Path relativePath = sourceRoot.relativize(file);
        if (relativePath.getNameCount() == 0) {
            return false;
        }
        String first = oneCNameDecoder.decodePathSegment(relativePath.getName(0).toString());
        return ALLOWED_ROOTS.contains(first);
    }

    private boolean isRoot(Path sourceRoot, Path file, String expectedRoot) {
        Path relativePath = sourceRoot.relativize(file);
        if (relativePath.getNameCount() == 0) {
            return false;
        }
        String first = oneCNameDecoder.decodePathSegment(relativePath.getName(0).toString());
        return expectedRoot.equalsIgnoreCase(first);
    }

    public record ScanRoot(
            CodeSourceRoot sourceRoot,
            SourceKind sourceKind,
            String sourceName,
            Path path,
            int priority
    ) {
    }

    public record ObjectRef(DependencyCallerType objectType, String objectName) {
    }

    public record DiscoveredFiles(
            Map<ScanRoot, List<Path>> commonModuleFilesByRoot,
            Map<ScanRoot, List<Path>> objectFilesByRoot,
            int discoveredFiles
    ) {
    }
}
