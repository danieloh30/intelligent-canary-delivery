package dev.danieloh.canary.agentic;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class AgentFailureTest {
    @Test void unwrapsTimeoutWithoutLeakingExceptionMessages() {
        var failure=AgentFailure.describe(new ExecutionException(new TimeoutException("secret-provider-body")),1,true);
        assertEquals("TIMEOUT",failure.category());assertTrue(failure.retryScheduled());
        assertTrue(failure.causes().contains("TimeoutException"));assertFalse(failure.message().contains("secret-provider-body"));
    }
    @Test void unknownConfigurationAndBudgetFailuresAreNotAutomaticallyRetried() {
        var failure=AgentFailure.describe(new IllegalStateException("Tool budget exceeded"),1,true);
        assertFalse(failure.retryScheduled());assertEquals("WORKFLOW_EXECUTION_FAILED",failure.category());
    }
}
