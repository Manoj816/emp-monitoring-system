namespace EmpVerify.Agent.Core.Abstractions;

/// <summary>
/// Opens the interactive check page (capture or liveness) and hands back a
/// session that closes it. Flows dispose the session once the server has a
/// verdict, so the employee never has to close the window themselves. A head
/// that cannot close what it opened (e.g. a shell-launched tab) returns a no-op.
/// </summary>
public interface IBrowserLauncher
{
    IDisposable Open(string url);
}
