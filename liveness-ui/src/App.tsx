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
 * compares that frame to the enrolled references. This page never decides the verdict.
 *
 * The check is required: cancelling or a scan error restarts the scan with a
 * fresh session (a few times) instead of ending the check; the agent's response
 * window keeps running meanwhile.
 */
const region = (import.meta.env.VITE_AWS_REGION as string | undefined) ?? "us-east-1";
const identityPoolId = import.meta.env.VITE_COGNITO_IDENTITY_POOL_ID as string | undefined;

if (identityPoolId) {
  Amplify.configure({ Auth: { Cognito: { identityPoolId, allowGuestAccess: true } } });
}

const MAX_RESTARTS = 3;

type State = "scan" | "working" | "done" | "error";

export default function App() {
  const params = new URLSearchParams(window.location.search);
  const checkId = params.get("checkId");
  const token = params.get("token");
  const [session, setSession] = useState(params.get("session"));
  const [restarts, setRestarts] = useState(0);
  const [state, setState] = useState<State>("scan");
  const [message, setMessage] = useState("");
  const [notice, setNotice] = useState("");

  const ready = Boolean(checkId && token && session);

  /**
   * The scan ended without a result and will not be retried here. Tell the
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

  /** Cancel or error: the check is required, so start the scan again with a fresh session. */
  async function restart(reason: "cancelled" | "error", why: string) {
    if (!checkId || !token) return;
    if (restarts >= MAX_RESTARTS) {
      await abandon(reason, `${why} The scan could not be completed on this device. The EmpVerify app will offer it again.`);
      return;
    }
    setNotice(`${why} This check is required — restarting the scan…`);
    try {
      const res = await fetch(
        `/api/v1/liveness/${checkId}/restart?token=${encodeURIComponent(token)}`,
        { method: "POST" },
      );
      if (!res.ok) throw new Error(`server returned ${res.status}`);
      const body = (await res.json()) as { sessionId: string };
      setRestarts(restarts + 1);
      setSession(body.sessionId); // new key below: the detector remounts with the fresh session
    } catch (e) {
      await abandon(reason, `${why} The scan could not be restarted (${(e as Error).message}). The EmpVerify app will offer it again.`);
    }
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
      setMessage(`Result: ${body.status}${conf}. This window closes by itself.`);
      setState("done");
    } catch (e) {
      // The scan ran but the server could not record it: tell it so the check is
      // closed out as an error (not left pending), then let the employee close.
      await abandon("error", `Could not complete the check: ${(e as Error).message}. The EmpVerify app will offer it again.`);
    }
  }

  /** Done or failed: the page may now be closed (the agent honours window.close()). */
  function closeWindow() {
    window.close();
  }

  let body;
  if (!identityPoolId) {
    body = <p className="message">Liveness is not configured (VITE_COGNITO_IDENTITY_POOL_ID is missing).</p>;
  } else if (!ready) {
    body = <p className="message">This page must be opened from the EmpVerify desktop app.</p>;
  } else if (state === "done" || state === "error") {
    body = (
      <div className="ended">
        <p className="message">{message}</p>
        <button className="close-button" onClick={closeWindow}>Close</button>
      </div>
    );
  } else if (state === "working") {
    body = <p className="message">Verifying…</p>;
  } else {
    body = (
      <>
        <p className="lead">
          This identity check is required. Center your face in the oval and follow the prompts;
          the window closes by itself when the check is done.
        </p>
        <p className="warning">
          Photosensitivity warning: the screen flashes different colors during the scan.
        </p>
        {notice && <p className="notice">{notice}</p>}
        <div className="scan">
          {/* The camera opens straight away (no start screen to scroll to): the
              agent's window is the consent surface and the text above is the briefing. */}
          <FaceLivenessDetector
            key={session!}
            sessionId={session!}
            region={region}
            disableStartScreen
            onAnalysisComplete={complete}
            onError={(err) =>
              void restart("error", `The face scan hit a problem (${err.error?.message ?? String(err.state)}).`)
            }
            onUserCancel={() => void restart("cancelled", "The scan was cancelled.")}
          />
        </div>
      </>
    );
  }

  return (
    <main className="check-page">
      <h1>Identity verification</h1>
      {body}
    </main>
  );
}
