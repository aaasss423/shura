package app.shura.translator.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The loading / error / retry / cancel state machine.
 *
 * These are the transitions most likely to be wrong in a real app, so they are
 * pinned explicitly: a spinner that survives a failure, a Retry button offered for
 * a request that can never succeed, a cancel that leaves the screen stuck.
 */
public class RequestStateTest {

    @Test
    public void startsIdle() {
        RequestState<String> state = new RequestState<>();
        assertEquals(RequestState.Phase.IDLE, state.phase());
        assertFalse(state.isBusy());
        assertEquals(0, state.attempt());
    }

    @Test
    public void runningIsBusy() {
        RequestState<String> state = new RequestState<>();
        state.start();
        assertEquals(RequestState.Phase.RUNNING, state.phase());
        assertTrue(state.isBusy());
        assertEquals(1, state.attempt());
    }

    @Test
    public void successClearsErrorAndStoresResult() {
        RequestState<String> state = new RequestState<>();
        state.start();
        state.succeed("مرحبا", 120);
        assertEquals(RequestState.Phase.SUCCEEDED, state.phase());
        assertEquals("مرحبا", state.result());
        assertEquals(120, state.elapsedMs());
        assertEquals("", state.errorMessage());
        assertFalse(state.isBusy());
    }

    @Test
    public void failureKeepsARetryableFlag() {
        RequestState<String> state = new RequestState<>();
        state.start();
        state.fail("Cannot reach the server", true);
        assertEquals(RequestState.Phase.FAILED, state.phase());
        assertTrue(state.retryable());
        assertEquals("Cannot reach the server", state.errorMessage());
        }

    @Test
    public void aNonRetryableFailureOffersNoRetry() {
        RequestState<String> state = new RequestState<>();
        state.start();
        state.fail("text must be a non-empty string", false);
        assertEquals(RequestState.Phase.FAILED, state.phase());
        assertFalse(state.retryable());
    }

    @Test
    public void retryStartsANewAttemptAndCountsIt() {
        RequestState<String> state = new RequestState<>();
        state.start();
        state.fail("boom", true);
        state.retry();
        assertEquals(RequestState.Phase.RUNNING, state.phase());
        assertEquals(2, state.attempt());
        assertEquals("a retry must clear the previous error", "", state.errorMessage());
        assertFalse("Retry must not be offered while the request is running", state.retryable());
    }

    @Test
    public void startClearsAPreviousResultSoStaleTextIsNotShownAsFresh() {
        RequestState<String> state = new RequestState<>();
        state.start();
        state.succeed("first", 10);
        state.start();
        assertNull("a new attempt must not keep showing the old translation", state.result());
    }

    @Test
    public void cancelIsTerminalForThatRunAndRetryable() {
        RequestState<String> state = new RequestState<>();
        state.start();
        state.cancel();
        assertEquals(RequestState.Phase.CANCELLED, state.phase());
        assertFalse(state.isBusy());
        assertNull(state.result());
        assertTrue("cancelling is the user's choice, so retry must be offered", state.retryable());
        state.retry();
        assertEquals(RequestState.Phase.RUNNING, state.phase());
    }

    @Test
    public void resetReturnsEverythingToTheInitialState() {
        RequestState<String> state = new RequestState<>();
        state.start();
        state.succeed("x", 5);
        state.reset();
        assertEquals(RequestState.Phase.IDLE, state.phase());
        assertEquals(0, state.attempt());
        assertEquals(0, state.elapsedMs());
        assertNull(state.result());
    }

    @Test
    public void failureAfterSuccessStillReportsFailure() {
        RequestState<String> state = new RequestState<>();
        state.start();
        state.succeed("ok", 5);
        state.start();
        state.fail("later failure", true);
        assertEquals(RequestState.Phase.FAILED, state.phase());
        assertNull("a failed attempt must not leave the old result visible as current", state.result());
        assertTrue(state.retryable());
    }
}
