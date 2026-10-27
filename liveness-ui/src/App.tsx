import { useState } from "react";

/**
 * Face Liveness page, opened by the desktop agent in the default browser:
 *   /?checkId=<id>&token=<nonce>&session=<rekognition session id>
 *
 * Production (Rekognition): render <FaceLivenessDetector sessionId={session} ...>
 * from @aws-amplify/ui-react-liveness (requires the Cognito Identity Pool with
 * unauthenticated access to rekognition:StartFaceLivenessSession). On
 * analysisComplete, POST to the complete endpoint below; the agent polls the
 * backend for the outcome. This page never receives the verdict.
 *
 * Dev (stub): there is no real challenge, so a button finalizes the check so the
 * whole flow can be exercised without AWS.
 */
export default function App() {
  const params = new URLSearchParams(window.location.search);
  const checkId = params.get("checkId");
  const token = params.get("token");
  const session = params.get("session");
  const [state, setState] = useState<"idle" | "working" | "done" | "error">("idle");
  const [message, setMessage] = useState("");

  async function complete() {
    if (!checkId || !token) return;
    setState("working");
    try {
      const res = await fetch(
        `/api/v1/liveness/${checkId}/complete?token=${encodeURIComponent(token)}`,
        { method: "POST" },
      );
      if (!res.ok) throw new Error(`${res.status}`);
      const body = await res.json();
      setMessage(`Result: ${body.status}. You can return to the EmpVerify app.`);
      setState("done");
    } catch (e) {
      setMessage(`Could not complete: ${(e as Error).message}`);
      setState("error");
    }
  }

  const ready = Boolean(checkId && token);

  return (
    <main style={{ fontFamily: "system-ui", padding: "2rem", maxWidth: 560, margin: "0 auto" }}>
      <h1>Identity verification</h1>
      {!ready ? (
        <p>This page must be opened from the EmpVerify desktop application.</p>
      ) : state === "done" || state === "error" ? (
        <p>{message}</p>
      ) : (
        <>
          <p>
            Development stub: in production this screen runs the AWS Face Liveness
            challenge (session <code>{session}</code>). Here, confirm to finalize the check.
          </p>
          <button onClick={complete} disabled={state === "working"}
                  style={{ padding: "8px 16px", fontSize: 15 }}>
            {state === "working" ? "Verifying..." : "Complete liveness check"}
          </button>
        </>
      )}
    </main>
  );
}
