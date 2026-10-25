namespace EmpVerify.Agent.Core.Abstractions;

/// <summary>
/// Visible identity-check prompt. Windows: toast notification. macOS (future): user notification.
/// Checks are always disclosed — there is no covert capture path.
/// </summary>
public interface INotificationService
{
    /// <summary>
    /// Shows the check prompt and waits for the employee to accept within the
    /// response window. False means no response (recorded as a missed check).
    /// </summary>
    Task<bool> PromptForCheckAsync(TimeSpan responseWindow, CancellationToken cancellationToken);
}
