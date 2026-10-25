/**
 * Face Liveness page, opened by the desktop agent in the default browser with a
 * one-time token: /?token=<opaque>
 *
 * Phase 2 flow (see docs/openapi/agent-api.yaml):
 *  1. Exchange the token with the backend for a Rekognition liveness sessionId.
 *  2. Render <FaceLivenessDetector sessionId=... region=...> from
 *     @aws-amplify/ui-react-liveness (requires the Cognito Identity Pool with
 *     unauthenticated access to rekognition:StartFaceLivenessSession).
 *  3. On analysisComplete, tell the employee to return to the agent; the agent
 *     polls the backend for the outcome. This page never receives the result.
 */
export default function App() {
  const token = new URLSearchParams(window.location.search).get("token");

  return (
    <main style={{ fontFamily: "system-ui", padding: "2rem", maxWidth: 640, margin: "0 auto" }}>
      <h1>Identity verification</h1>
      {token ? (
        <p>Phase 2 scaffold — the liveness challenge will start here.</p>
      ) : (
        <p>This page must be opened from the EmpVerify desktop application.</p>
      )}
    </main>
  );
}
