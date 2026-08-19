package com.company.aiqa.replay;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayServiceTest {

    private final ReplayService service = new ReplayService(new ReplayProperties());

    /**
     * The endpoint answers any origin - it has to, since a report opened from a
     * file has none. The token is the only thing standing between that and an
     * open proxy any web page could drive.
     */
    @Test
    void onlyTheProcessesOwnTokenIsAccepted() {
        assertTrue(service.isAuthorized(service.token()));
        assertFalse(service.isAuthorized("guessed"));
        assertFalse(service.isAuthorized(null));
        assertFalse(service.isAuthorized(""));
    }

    @Test
    void tokenIsNotGuessableAndNotBlank() {
        assertNotNull(service.token());
        assertTrue(service.token().length() >= 32, service.token());
        assertFalse(service.token().equals(new ReplayService(new ReplayProperties()).token()));
    }

    /**
     * A refused connection is the answer to the reader's question, not an
     * internal failure - so it comes back as a result they can read rather than
     * as a thrown error the page would show as a broken proxy.
     */
    @Test
    void aCallThatNeverConnectsComesBackAsAResultNotAnException() {
        ReplayResult result = service.send(new ReplayRequest(
                "POST", "http://127.0.0.1:1/nope", Map.of("Content-Type", "application/json"), "{}"));

        assertEquals(0, result.status());
        assertNotNull(result.error());
    }

    @Test
    void aMalformedUriIsReportedTheSameWay() {
        ReplayResult result = service.send(new ReplayRequest("GET", "not a uri", Map.of(), null));

        assertEquals(0, result.status());
        assertNotNull(result.error());
    }
}
