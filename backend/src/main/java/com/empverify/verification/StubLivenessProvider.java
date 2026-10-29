package com.empverify.verification;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local-dev liveness provider: lets the full liveness flow run without AWS. The
 * session is a random id; the result reports a high confidence and a placeholder
 * frame (the stub verifier ignores the bytes), so the flow behaves like a real
 * scan that returned a face picture.
 */
@Component
@ConditionalOnProperty(name = "empverify.liveness", havingValue = "stub", matchIfMissing = true)
public class StubLivenessProvider implements LivenessProvider {

    private static final byte[] PLACEHOLDER_FRAME = "stub-liveness-frame".getBytes(StandardCharsets.UTF_8);

    @Override
    public Session createSession() {
        return new Session(UUID.randomUUID().toString(), Instant.now().plusSeconds(300));
    }

    @Override
    public Result getResult(String sessionId) {
        return new Result(95.0, PLACEHOLDER_FRAME.clone(), "SUCCEEDED");
    }
}
