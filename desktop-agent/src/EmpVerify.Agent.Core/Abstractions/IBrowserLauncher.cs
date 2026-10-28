namespace EmpVerify.Agent.Core.Abstractions;

/// <summary>
/// Opens the interactive check page (capture or liveness) and hands back the
/// session for that window. Flows dispose the session once the server has a
/// verdict, so the employee never has to close the window themselves.
/// </summary>
public interface IBrowserLauncher
{
    IBrowserSession Open(string url);
}

/// <summary>
/// One open check window. <see cref="IDisposable.Dispose"/> closes it;
/// <see cref="Closed"/> completes if the employee closes it first, so a flow can
/// react at once instead of waiting out the whole response window. A head that
/// cannot observe its window (a shell-launched tab) returns a task that never
/// completes.
/// </summary>
public interface IBrowserSession : IDisposable
{
    Task Closed { get; }
}
