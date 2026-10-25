using EmpVerify.Agent.Core.Abstractions;

namespace EmpVerify.Agent.Windows.Services;

/// <summary>
/// Visible identity-check prompt. Phase 1 placeholder: shows a WPF prompt window.
/// TODO: replace with a native toast notification (CommunityToolkit.WinUI.Notifications)
/// once the MSIX identity exists, so prompts appear even when the window is hidden.
/// </summary>
public sealed class ToastNotificationService : INotificationService
{
    public Task<bool> PromptForCheckAsync(TimeSpan responseWindow, CancellationToken cancellationToken)
    {
        // TODO Phase 1: show the check-prompt window on the UI dispatcher and await
        // the employee's response within responseWindow.
        return Task.FromResult(false);
    }
}
