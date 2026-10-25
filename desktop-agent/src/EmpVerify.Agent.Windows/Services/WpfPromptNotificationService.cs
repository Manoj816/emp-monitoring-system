using System.Windows;
using EmpVerify.Agent.Core.Abstractions;

namespace EmpVerify.Agent.Windows.Services;

/// <summary>
/// Shows the visible check prompt as a topmost window with a countdown.
/// TODO: add a native toast notification alongside (needs MSIX identity) so the
/// prompt is noticed when the user is in another app full-screen.
/// </summary>
public sealed class WpfPromptNotificationService : INotificationService
{
    public async Task<bool> PromptForCheckAsync(TimeSpan responseWindow, CancellationToken cancellationToken)
    {
        return await Application.Current.Dispatcher.InvokeAsync(() =>
        {
            var prompt = new CheckPromptWindow(responseWindow);
            return prompt.ShowDialog() == true;
        });
    }
}
