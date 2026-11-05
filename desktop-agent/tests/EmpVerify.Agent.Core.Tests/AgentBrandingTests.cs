namespace EmpVerify.Agent.Core.Tests;

/// <summary>
/// The tray icon is the only part of the agent most employees ever see, and the
/// failure mode here is silent: the loader falls back to the generic Windows
/// square if it cannot read the resource, so a dropped asset or a dropped csproj
/// line costs the agent its identity without breaking a build or a run.
/// </summary>
public class AgentBrandingTests
{
    private static string RepoFile(string relative)
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            var candidate = Path.Combine(dir.FullName, relative.Replace('/', Path.DirectorySeparatorChar));
            if (File.Exists(candidate))
            {
                return candidate;
            }
            dir = dir.Parent;
        }
        throw new FileNotFoundException($"{relative} not found by walking up from {AppContext.BaseDirectory}");
    }

    private const string IconPath = "desktop-agent/src/EmpVerify.Agent.Windows/Assets/EmpVerify.ico";
    private const string ProjectPath =
        "desktop-agent/src/EmpVerify.Agent.Windows/EmpVerify.Agent.Windows.csproj";

    /// <summary>Sizes declared in the icon directory: 6-byte header, then 16 bytes per image.</summary>
    private static List<int> IconSizes(string path)
    {
        var bytes = File.ReadAllBytes(path);
        Assert.True(bytes.Length > 6, "the icon file is too short to hold a directory");
        Assert.Equal(1, BitConverter.ToUInt16(bytes, 2));   // type 1 = icon, not cursor
        int count = BitConverter.ToUInt16(bytes, 4);

        var sizes = new List<int>();
        for (int i = 0; i < count; i++)
        {
            // A width byte of 0 means 256 — the field is one byte wide.
            int width = bytes[6 + i * 16];
            sizes.Add(width == 0 ? 256 : width);
        }
        return sizes;
    }

    [Fact]
    public void TheTrayIconCarriesTheSizeTheTrayAsksFor()
    {
        // 16px is the tray at 100% scaling and 24px is it at 150%. Without its own
        // frame for each, Windows resamples a bigger one and "EV" turns to mush.
        var sizes = IconSizes(RepoFile(IconPath));
        Assert.Contains(16, sizes);
        Assert.Contains(24, sizes);
        Assert.Contains(32, sizes);
    }

    [Fact]
    public void TheIconIsBothTheExeIconAndALoadableResource()
    {
        // Two separate jobs from one file: ApplicationIcon is what Task Manager and
        // alt-tab show, and the Resource entry is what the tray loader reads by
        // pack URI to pick a frame by size. Dropping either is a silent regression.
        var project = File.ReadAllText(RepoFile(ProjectPath));
        Assert.Contains("<ApplicationIcon>Assets\\EmpVerify.ico</ApplicationIcon>", project);
        Assert.Contains("<Resource Include=\"Assets\\EmpVerify.ico\" />", project);
    }

    [Fact]
    public void TheTrayFallsBackRatherThanFailingToStart()
    {
        // An icon that cannot be read must cost the branding, never the agent: this
        // runs on every sign-in on every machine in the fleet.
        var app = File.ReadAllText(
            RepoFile("desktop-agent/src/EmpVerify.Agent.Windows/App.xaml.cs"));
        Assert.Contains("LoadTrayIcon() ?? System.Drawing.SystemIcons.Application", app);
    }
}
