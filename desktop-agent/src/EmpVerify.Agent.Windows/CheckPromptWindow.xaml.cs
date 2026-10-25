using System.Windows;
using System.Windows.Threading;

namespace EmpVerify.Agent.Windows;

/// <summary>
/// Visible check prompt with a response countdown. No response within the window
/// closes the prompt as "not accepted" — recorded as a missed check, never as a
/// mismatch.
/// </summary>
public partial class CheckPromptWindow : Window
{
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private int _secondsLeft;

    public CheckPromptWindow(TimeSpan responseWindow)
    {
        InitializeComponent();
        _secondsLeft = (int)responseWindow.TotalSeconds;
        UpdateCountdown();
        _timer.Tick += (_, _) =>
        {
            _secondsLeft--;
            if (_secondsLeft <= 0)
            {
                DialogResult = false;
                Close();
                return;
            }
            UpdateCountdown();
        };
        _timer.Start();
        Closed += (_, _) => _timer.Stop();
    }

    private void UpdateCountdown() =>
        CountdownText.Text = $"Responds in {_secondsLeft}s, otherwise the check is recorded as missed.";

    private void Start_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = true;
        Close();
    }

    private void NotNow_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }
}
