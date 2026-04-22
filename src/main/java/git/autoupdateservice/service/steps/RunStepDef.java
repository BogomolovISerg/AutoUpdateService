package git.autoupdateservice.service.steps;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class RunStepDef {

    @JsonAlias({"num", "number", "step"})
    private int order;

    private String code;
    private String title;

    private boolean enabled = true;
    private boolean always = false;

    private String foreach;
    private String condition;
    @JsonAlias({"action", "builtin"})
    private String special;
    private String phase;

    private List<String> command;

    private RetryDef retry;

    @Data
    @NoArgsConstructor
    public static class RetryDef {
        private int maxAttempts = 0;
        private int sleepSeconds = 0;
        private List<String> checkCommand;
        private List<String> onFailCommand;
    }
}
