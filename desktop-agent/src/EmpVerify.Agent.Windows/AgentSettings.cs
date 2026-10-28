using System.IO;
using System.Text.Json;

namespace EmpVerify.Agent.Windows;

/// <summary>Non-secret agent settings (the device secret lives in <c>DpapiCredentialStore</c>).</summary>
public sealed class AgentSettings
{
    private static readonly string FilePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "EmpVerify", "settings.json");

    public string BackendUrl { get; set; } = "http://localhost:8082";

    // Demo overrides (off by default). Edit settings.json to control checks for a demo:
    //   DemoIntervalMinutes > 0  -> run a check every N minutes (ignores the 1/day plan)
    //   DemoCheckAt = "HH:mm"    -> run one check today at this local time
    public int DemoIntervalMinutes { get; set; }
    public string? DemoCheckAt { get; set; }

    // Checks run the AWS Face Liveness scan (proves a live person, defeats held-up
    // photos). Set false to fall back to the plain photo capture + compare.
    public bool UseLivenessForChecks { get; set; } = true;

    // When a scan is not completed (window closed, cancelled, or timed out):
    //   the required session-start scan reopens every RetryMinutes until it is done;
    //   a scheduled daily check is offered again MissedCheckRetries times, then recorded as missed.
    public int RetryMinutes { get; set; } = 2;
    public int MissedCheckRetries { get; set; } = 1;

    public static AgentSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                return JsonSerializer.Deserialize<AgentSettings>(File.ReadAllText(FilePath)) ?? new AgentSettings();
            }
        }
        catch
        {
            // fall through to defaults on a corrupt settings file
        }
        return new AgentSettings();
    }

    public void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this));
    }
}
