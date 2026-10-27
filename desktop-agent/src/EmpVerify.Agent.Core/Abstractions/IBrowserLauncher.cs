namespace EmpVerify.Agent.Core.Abstractions;

/// <summary>
/// Opens a URL in the user's default browser for the interactive Face Liveness
/// challenge (JavaFX WebView / WPF WebView lack the WebRTC the component needs).
/// The agent must not hold the camera when this runs so the browser can use it.
/// </summary>
public interface IBrowserLauncher
{
    void Open(string url);
}
