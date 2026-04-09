package git.autoupdateservice.service;

import git.autoupdateservice.domain.CommonModuleImpact;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.SourceKind;
import git.autoupdateservice.repo.CommonModuleImpactRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DependencySearchMapper {

    public String normalizeLike(String query) {
        if (isBlank(query)) {
            return null;
        }
        return "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
    }

    public String objectTypeName(DependencyCallerType objectType) {
        return objectType == null ? null : objectType.name();
    }

    public DependencyTreeSearchService.ModuleNode toModuleNode(CommonModuleImpactRepository.ModuleAggRow row) {
        SourceKind sourceKind = parseSourceKind(row.getSourceKind());
        return DependencyTreeSearchService.ModuleNode.builder()
                .sourceKind(sourceKind)
                .sourceName(defaultSourceName(sourceKind, row.getSourceName()))
                .commonModuleName(row.getCommonModuleName())
                .methodCount(row.getMethodCount())
                .objectCount(row.getObjectCount())
                .build();
    }

    public DependencyTreeSearchService.MethodNode toMethodNode(CommonModuleImpactRepository.MethodAggRow row) {
        return DependencyTreeSearchService.MethodNode.builder()
                .methodName(row.getCommonModuleMemberName())
                .objectCount(row.getObjectCount())
                .build();
    }

    public DependencyTreeSearchService.ObjectNode toObjectNode(CommonModuleImpactRepository.ObjectRow row) {
        DependencyCallerType objectType = parseObjectType(row.getObjectType());
        if (objectType == null || isBlank(row.getObjectName())) {
            return null;
        }

        return DependencyTreeSearchService.ObjectNode.builder()
                .objectType(objectType)
                .objectName(row.getObjectName())
                .build();
    }

    public DependencyTreeSearchService.AffectedObject toAffectedObject(CommonModuleImpact row) {
        if (row == null || row.getObjectType() == null || isBlank(row.getObjectName())) {
            return null;
        }
        return DependencyTreeSearchService.AffectedObject.builder()
                .objectType(row.getObjectType())
                .objectName(row.getObjectName())
                .build();
    }

    public Set<String> normalizeModuleNames(Collection<String> commonModuleNames) {
        if (commonModuleNames == null || commonModuleNames.isEmpty()) {
            return Set.of();
        }

        return commonModuleNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private DependencyCallerType parseObjectType(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return DependencyCallerType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private SourceKind parseSourceKind(String value) {
        if (isBlank(value)) {
            return SourceKind.BASE;
        }
        try {
            return SourceKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SourceKind.BASE;
        }
    }

    private String defaultSourceName(SourceKind sourceKind, String sourceName) {
        if (!isBlank(sourceName)) {
            return sourceName.trim();
        }
        return sourceKind == SourceKind.EXTENSION ? "Расширение" : "Основная конфигурация";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
