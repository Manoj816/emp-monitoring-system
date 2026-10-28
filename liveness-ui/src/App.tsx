import { useState } from "react";
import { Amplify } from "aws-amplify";
import { FaceLivenessDetector } from "@aws-amplify/ui-react-liveness";
import "@aws-amplify/ui-react/styles.css";

/**
 * Face Liveness page, opened by the desktop agent (in its own window):
 *   /?checkId=<id>&token=<nonce>&session=<rekognition session id>
 *
 * The AWS component runs the guided full-face scan and streams it to Rekognition
 * using guest credentials from the Cognito identity pool (scoped to
 * StartFaceLivenessSession only). When the scan completes, this page tells the
 * backend, which fetches the live-person confidence plus a reference frame and
 * compares that frame to the enrolled face. This page never decides the verdict.
 */
const region = (import.meta.env.VITE_AWS_REGION as string | undefined) ?? "us-east-1";
const identityPoolId = import.meta.env.VITE_COGNITO_IDENTITY_POOL_ID as string | undefined;

if (identityPoolId) {
  Amplify.configure({ Auth: { Cognito: { identityPoolId, allowGuestAccess: true } } });
}

type State = "scan" | "working" | "done" | "error";

export default function App() {
  const params = new URLSearchParams(window.location.search);
  const checkId = params.get("checkId");
  const token = params.get("token");
  const session = params.get("session");
  const [state, setState] = useState<State>("scan");
  const [message, setMessage] = useState("");

  const ready = Boolean(checkId && token && session);

  /**
   * The scan ended without a result (cancelled, or it could not run). Tell the
   * backend right away so the check is recorded as missed with the reason and the
   * agent can offer the scan again, instead of waiting out the response window.
   */
  async function abandon(reason: "cancelled" | "error", text: string) {
    if (checkId && token) {
      try {
        await fetch(
          `/api/v1/liveness/${checkId}/cancel?token=${encodeURIComponent(token)}&reason=${reason}`,
          { method: "POST" },
        );
      } catch {
        // Best effort: the agent and the server's own expiry handle it.
      }
    }
    setMessage(text);
    setState("error");
  }

  async function complete() {
    if (!checkId || !token) return;
    setState("working");
    try {
      const res = await fetch(
        `/api/v1/liveness/${checkId}/complete?token=${encodeURIComponent(token)}`,
        { method: "POST" },
      );
      if (!res.ok) throw new Error(`server returned ${res.status}`);
      const body = await res.json();
      const conf =
        body.livenessConfidence != null
          ? ` (liveness ${Number(body.livenessConfidence).toFixed(1)})`
          : "";
      setMessage(`Result: ${body.status}${conf}. You can return to the EmpVerify app.`);
      setState("done");
    } catch (e) {
      setMessage(`Could not complete: ${(e as Error).message}`);
      setState("error");
    }
  }

  let body;
  if (!identityPoolId) {
    body = <p>Liveness is not configured (VITE_COGNITO_IDENTITY_POOL_ID is missing).</p>;
  } else if (!ready) {
    body = <p>This page must be opened from the EmpVerify desktop app.</p>;
  } else if (state === "done" || state === "error") {
    body = <p style={{ fontSize: 18 }}>{message}</p>;
  } else if (state === "working") {
    body = <p style={{ fontSize: 18 }}>Verifying…</p>;
  } else {
    body = (
      <>
        <p>Follow the on-screen prompts to scan your face.</p>
        <FaceLivenessDetector
          sessionId={session!}
          region={region}
          onAnalysisComplete={complete}
          onError={(err) =>
            void abandon(
              "error",
              `Face scan failed: ${err.error?.message ?? String(err.state)}. ` +
                "The EmpVerify app will offer the scan again shortly.",
            )
          }
          onUserCancel={() =>
            void abandon(
              "cancelled",
              "Scan cancelled. The EmpVerify app will offer it again shortly — " +
                "or click 'Liveness check' in the app to do it now.",
            )
          }
        />
      </>
    );
  }

  return (
    <main style={{ fontFamily: "system-ui", padding: "1.5rem", maxWidth: 720, margin: "0 auto" }}>
      <h1>Identity verification</h1>
      {body}
    </main>
  );
}
