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
