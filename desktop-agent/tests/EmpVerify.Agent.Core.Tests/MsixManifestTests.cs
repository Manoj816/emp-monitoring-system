using System.Xml.Linq;

namespace EmpVerify.Agent.Core.Tests;

/// <summary>
/// Guards the MSIX manifest's load-bearing bits so a bad edit fails CI rather
/// than producing a package that won't install or can't use the camera.
/// </summary>
public class MsixManifestTests
{
    private static readonly XNamespace Foundation =
        "http://schemas.microsoft.com/appx/manifest/foundation/windows10";
    private static readonly XNamespace Rescap =
        "http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities";
    private static readonly XNamespace Uap5 =
        "http://schemas.microsoft.com/appx/manifest/uap/windows10/5";

    private static XDocument LoadManifest()
    {
        // Walk up from the test output dir to the repo, then into packaging/.
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            var candidate = Path.Combine(dir.FullName, "desktop-agent", "packaging", "AppxManifest.xml");
            if (File.Exists(candidate))
            {
                return XDocument.Load(candidate);
            }
            dir = dir.Parent;
        }
        throw new FileNotFoundException("AppxManifest.xml not found by walking up from " + AppContext.BaseDirectory);
    }

    [Fact]
    public void ManifestIsWellFormedWithIdentity()
    {
        var identity = LoadManifest().Root!.Element(Foundation + "Identity");
        Assert.NotNull(identity);
        Assert.False(string.IsNullOrWhiteSpace((string?)identity!.Attribute("Name")));
        Assert.False(string.IsNullOrWhiteSpace((string?)identity.Attribute("Publisher")));
        // Package version must be a 4-part version.
        var version = (string?)identity.Attribute("Version");
        Assert.Matches(@"^\d+\.\d+\.\d+\.\d+$", version!);
    }

    [Fact]
    public void DeclaresWebcamCapability()
    {
        var caps = LoadManifest().Root!.Element(Foundation + "Capabilities")!;
        var webcam = caps.Elements(Foundation + "DeviceCapability")
            .Any(e => (string?)e.Attribute("Name") == "webcam");
        Assert.True(webcam, "webcam DeviceCapability is required for camera access and the privacy indicator");
    }

    [Fact]
    public void DeclaresRunFullTrustForWpf()
    {
        var caps = LoadManifest().Root!.Element(Foundation + "Capabilities")!;
        var fullTrust = caps.Elements(Rescap + "Capability")
            .Any(e => (string?)e.Attribute("Name") == "runFullTrust");
        Assert.True(fullTrust, "a packaged WPF desktop app requires runFullTrust");
    }

    [Fact]
    public void ApplicationEntryPointMatchesPublishedExecutable()
    {
        var app = LoadManifest().Root!
            .Element(Foundation + "Applications")!
            .Element(Foundation + "Application")!;
        Assert.Equal("EmpVerify.Agent.Windows.exe", (string?)app.Attribute("Executable"));
        Assert.Equal("Windows.FullTrustApplication", (string?)app.Attribute("EntryPoint"));
    }

    [Fact]
    public void DeclaresStartupTaskForLoginAutostart()
    {
        var startupTask = LoadManifest().Descendants(Uap5 + "StartupTask").FirstOrDefault();
        Assert.NotNull(startupTask);
        Assert.Equal("true", (string?)startupTask!.Attribute("Enabled"));
    }
}
