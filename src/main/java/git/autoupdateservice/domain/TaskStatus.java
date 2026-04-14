package git.autoupdateservice.domain;

public enum TaskStatus {
    NEW,
    TEST_FAILED,
    TEST_OK,
    UPDATED,
    CANCELED
}