package com.company.aiqa.replay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets a generated report re-issue one of its captured requests.
 *
 * <p>Cross-origin from anywhere by necessity: the reports this serves are
 * opened from the filesystem, where the page's origin is the literal string
 * "null" and no allow-list can name it. The token check in
 * {@link ReplayService} is what keeps that from being an open proxy - it is
 * required on every call, and only this process's own reports carry it.
 *
 * <p>Switch the whole thing off with {@code aiqa.report.replay.enabled=false};
 * the button then falls back to a direct call from the browser, which works
 * whenever the service under test allows cross-origin requests.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "aiqa.report.replay", name = "enabled", havingValue = "true", matchIfMissing = true)
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = RequestMethod.POST)
public class ReplayController {

    private final ReplayService replayService;

    public ReplayController(ReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping("/replay")
    public ResponseEntity<ReplayResult> replay(
            @RequestHeader(value = "X-Aiqa-Replay-Token", required = false) String token,
            @RequestBody ReplayRequest request) {

        if (!replayService.isAuthorized(token)) {
            // No detail in the body: a page guessing tokens learns nothing.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request.uri() == null || request.uri().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(replayService.send(request));
    }
}
