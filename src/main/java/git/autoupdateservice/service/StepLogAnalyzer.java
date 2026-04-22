package git.autoupdateservice.service;

public interface StepLogAnalyzer {

    record Analysis(boolean hasErrors, String summary) {
        public static Analysis ok() { return new Analysis(false, null); }
        public static Analysis error(String summary) { return new Analysis(true, summary); }
    }

    Analysis analyze(String stepCode, String stdoutText, String stderrText, String debugText);
}
