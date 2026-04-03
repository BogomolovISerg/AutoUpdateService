package git.autoupdateservice.service;

/**
 * Резервная заглушка анализатора: всегда "ошибок нет".
 *
 * В проде используется VanessaRunnerStepLogAnalyzer.
 */
public class NoopStepLogAnalyzer implements StepLogAnalyzer {
    @Override
    public Analysis analyze(String stepCode, String stdoutText, String stderrText, String debugText) {
        return Analysis.ok();
    }
}
