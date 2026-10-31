/**
 * What the employee is told after a scan.
 *
 * The server's status is an internal code: showing it raw ("Result: CHECK") next
 * to a high live-person score reads like a pass, when CHECK actually means the
 * face did not match the photo on file well enough to confirm. Only a MATCH is
 * phrased as confirmed; everything else says plainly that the scan was recorded
 * and goes to the administrator, so nobody can mistake a failed identity check
 * for a successful one. None of these are accusations — the administrator
 * decides, not this page.
 */
export interface Outcome {
  headline: string;
  detail: string;
  /** Drives the colour: only a confirmed match is "ok". */
  tone: "ok" | "review" | "bad";
}

export function describeOutcome(status: string): Outcome {
  switch (status) {
    case "MATCH":
      return {
        headline: "Identity confirmed",
        detail:
          "Your face matched the photo on file. This check is complete and the result has been " +
          "sent to your administrator.",
        tone: "ok",
      };
    case "CHECK":
      return {
        headline: "Not confirmed — sent for review",
        detail:
          "The scan showed a real person in front of the camera, but the face did not match the " +
          "photo on file closely enough to confirm it automatically. Your administrator has " +
          "received this scan and will review it.",
        tone: "review",
      };
    case "NO_FACE":
      return {
        headline: "No face detected",
        detail:
          "The scan did not capture a usable face, so nothing could be compared with the photo on " +
          "file. It has been recorded and sent to your administrator; you may be asked to scan again.",
        tone: "review",
      };
    case "MULTIPLE_FACES":
      return {
        headline: "More than one face — sent for review",
        detail:
          "More than one face was visible during the scan, so it could not be confirmed " +
          "automatically. Your administrator has received this scan and will review it.",
        tone: "review",
      };
    case "VERIFICATION_ERROR":
      return {
        headline: "Check could not be completed",
        detail:
          "Something went wrong while checking the scan, so it was not confirmed. It has been " +
          "recorded and your administrator will follow up.",
        tone: "bad",
      };
    case "EXPIRED":
      return {
        headline: "Check not completed",
        detail:
          "This check ended before a scan was finished, so it is recorded as missed. The EmpVerify " +
          "app offers it again.",
        tone: "bad",
      };
    default:
      return {
        headline: "Scan recorded",
        detail:
          "Your scan has been recorded and sent to your administrator. It was not confirmed " +
          "automatically on this screen.",
        tone: "review",
      };
  }
}

/**
 * The live-person score, stated as its own thing. It says a real face was in
 * front of the camera — never who that face belongs to — so it is shown as a
 * separate line and never as the result.
 */
export function livenessNote(confidence: number | null | undefined): string | null {
  if (confidence == null) return null;
  return (
    `Live-person score: ${Number(confidence).toFixed(1)} of 100. This only shows that a real ` +
    `person was in front of the camera. Who it is is decided by comparing the scan with the ` +
    `photo your administrator uploaded.`
  );
}
