namespace EmpVerify.Agent.Core.Abstractions;

/// <summary>
/// Platform camera access. Windows: MediaCapture. macOS (future): AVFoundation.
/// Implementations must fully release the camera when not capturing, so the
/// browser-based liveness flow can acquire it.
/// </summary>
public interface ICameraService
{
    /// <summary>Returns null when no camera is available or access is denied.</summary>
    Task<CapturedFrame?> CaptureFrameAsync(CancellationToken cancellationToken);

    /// <summary>True when another application currently holds the camera (check is deferred, not failed).</summary>
    Task<bool> IsCameraBusyAsync(CancellationToken cancellationToken);

    /// <summary>Enumerated camera device names; used server-side to flag virtual webcams.</summary>
    Task<IReadOnlyList<string>> ListCameraDevicesAsync(CancellationToken cancellationToken);
}
