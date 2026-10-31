import { useEffect, useRef, useState } from "react";
import { describeOutcome, type Outcome } from "./outcome";

/**
 * Interactive capture page, opened by the desktop agent in the default browser:
 *   /capture?checkId=<id>&token=<nonce>
 *
 * Shows the webcam preview, a 2-minute countdown, and a Capture button. On
 * capture it uploads the photo to the backend, which compares it to the enrolled
 * reference and records the verdict. The server is the sole authority — this page
 * only shows back what the server decided.
 */
const RESPONSE_SECONDS = 120;

type State = "starting" | "ready" | "working" | "done" | "error" | "expired";

export default function CapturePage() {
  const params = new URLSearchParams(window.location.search);
  const checkId = params.get("checkId");
  const token = params.get("token");

  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const [state, setState] = useState<State>("starting");
  const [message, setMessage] = useState("");
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [remaining, setRemaining] = useState(RESPONSE_SECONDS);

  const ready = Boolean(checkId && token);

  function stopCamera() {
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
  }

  // Start the camera (localhost is a secure context, so no HTTPS needed).
  useEffect(() => {
    if (!ready) {
      setState("error");
      setMessage("This page must be opened from the EmpVerify desktop app.");
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: { width: 640, height: 480 },
        });
        if (cancelled) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }
        streamRef.current = stream;
        if (videoRef.current) videoRef.current.srcObject = stream;
        setState("ready");
      } catch (e) {
        setState("error");
        setMessage(`Camera unavailable: ${(e as Error).message}`);
      }
    })();
    return () => {
      cancelled = true;
      stopCamera();
    };
  }, [ready]);

  // Countdown; expires the check if the employee does not capture in time.
  useEffect(() => {
    if (state !== "ready") return;
    if (remaining <= 0) {
      setState("expired");
      setMessage("Time expired. Return to the EmpVerify app.");
      stopCamera();
      return;
    }
    const id = setTimeout(() => setRemaining((r) => r - 1), 1000);
    return () => clearTimeout(id);
  }, [state, remaining]);

  async function capture() {
    const video = videoRef.current;
    if (!video || !checkId || !token) return;
    setState("working");
    try {
      const canvas = document.createElement("canvas");
      canvas.width = video.videoWidth || 640;
      canvas.height = video.videoHeight || 480;
      canvas.getContext("2d")!.drawImage(video, 0, 0, canvas.width, canvas.height);
      const blob: Blob = await new Promise((resolve, reject) =>
        canvas.toBlob(
          (b) => (b ? resolve(b) : reject(new Error("capture failed"))),
          "image/jpeg",
          0.9,
        ),
      );

      const form = new FormData();
      form.append("image", blob, "capture.jpg");
      const res = await fetch(
        `/api/v1/capture/${checkId}/submit?token=${encodeURIComponent(token)}`,
        { method: "POST", body: form },
      );
      if (!res.ok) throw new Error(`server returned ${res.status}`);
      const body = await res.json();
      // The raw status and the face-match score stay off this screen: the status
      // reads like a pass when it is not, and the score would tell anyone trying
      // a photo of someone else how close they got.
      setOutcome(describeOutcome(String(body.status)));
      setState("done");
      stopCamera();
    } catch (e) {
      setMessage(`Could not submit: ${(e as Error).message}`);
      setState("error");
    }
  }

  const mm = String(Math.floor(remaining / 60)).padStart(2, "0");
  const ss = String(remaining % 60).padStart(2, "0");

  return (
    <main
      style={{
        fontFamily: "system-ui",
        padding: "2rem",
        maxWidth: 680,
        margin: "0 auto",
        textAlign: "center",
      }}
    >
      <h1>Identity check</h1>
      {state === "done" && outcome ? (
        <>
          <div className={`outcome ${outcome.tone}`}>
            <h2>{outcome.headline}</h2>
            <p className="outcome-detail">{outcome.detail}</p>
          </div>
          <p className="closing">You can return to the EmpVerify app.</p>
          {/* The agent honours window.close() once the check has ended. */}
          <button className="close-button" onClick={() => window.close()}>Close</button>
        </>
      ) : state === "done" || state === "error" || state === "expired" ? (
        <>
          <p style={{ fontSize: 18 }}>{message}</p>
          <button className="close-button" onClick={() => window.close()}>Close</button>
        </>
      ) : (
        <>
          <p>
            Look at the camera and click <strong>Capture</strong>. Time remaining:{" "}
            <strong>
              {mm}:{ss}
            </strong>
          </p>
          <video
            ref={videoRef}
            autoPlay
            playsInline
            muted
            style={{
              width: 480,
              maxWidth: "100%",
              borderRadius: 12,
              background: "#000",
              transform: "scaleX(-1)",
            }}
          />
          <div style={{ marginTop: 16 }}>
            <button
              onClick={capture}
              disabled={state !== "ready"}
              style={{ padding: "12px 28px", fontSize: 17, borderRadius: 8, cursor: "pointer" }}
            >
              {state === "working" ? "Submitting..." : "Capture"}
            </button>
          </div>
        </>
      )}
    </main>
  );
}
