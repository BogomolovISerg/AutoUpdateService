package git.autoupdateservice.service;

public class NoopStepLogAnalyzer implements StepLogAnalyzer {
    @Override
    public Analysis analyze(String stepCode, String stdoutText, String stderrText, String debugText) {
        return Analysis.ok();
    }
}
