package git.autoupdateservice.web.model;

import git.autoupdateservice.domain.DependencyCallerType;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class DependencySearchRow {
    String commonModuleName;
    String commonModuleMemberName;
    DependencyCallerType objectType;
    String objectName;
    String viaModule;
    String viaMember;
    String sourcePath;
}
