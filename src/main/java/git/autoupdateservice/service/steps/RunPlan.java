package git.autoupdateservice.service.steps;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class RunPlan {

    @JsonAlias({"params", "parameters", "properties"})
    private Map<String, String> settings = new LinkedHashMap<>();

    @JsonAlias({"resultFile", "testResultPath"})
    private String testResultFile;

    @JsonAlias({"xunitConfigFile", "smokeConfigFile"})
    private String xunitConfigFile;

    @JsonAlias({"extensionPlanMask", "extensionPlanPattern", "extPlanFilePattern", "extensionsPlanFilePattern"})
    private String extensionPlanFilePattern;

    @JsonAlias({"plan"})
    private List<RunStepDef> steps = new ArrayList<>();

    @JsonIgnore
    private String loadedFrom;
}
