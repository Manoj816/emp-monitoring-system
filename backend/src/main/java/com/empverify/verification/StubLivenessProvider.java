package com.empverify.verification;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local-dev liveness provider: lets the full liveness flow run without AWS. The
 * session is a random id; the result reports a high confidence and no captured
 * frame (so the backend falls back to comparing against the enrolled reference
 * via the stub verifier).
 */
@Component
@ConditionalOnProperty(name = "empverify.liveness", havingValue = "stub", matchIfMissing = true)
public class StubLivenessProvider implements LivenessProvider {

    @Override
    public Session createSession() {
        return new Session(UUID.randomUUID().toString(), Instant.now().plusSeconds(300));
    }

    @Override
    public Result getResult(String sessionId) {
        return new Result(95.0, null, "SUCCEEDED");
    }
}
