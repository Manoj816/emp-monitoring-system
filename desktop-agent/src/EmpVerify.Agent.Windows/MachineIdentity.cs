using System.Security.Cryptography;
using System.Text;
using Microsoft.Win32;

namespace EmpVerify.Agent.Windows;

/// <summary>
/// A stable identifier of this Windows installation, sent with every registration
/// and sign-in so the server re-uses the same device row after a reinstall, a
/// wiped profile or a credential reset instead of listing the machine twice.
/// Primary source: the Windows MachineGuid (set when Windows was installed,
/// readable by every user). Fallback: a hash of the machine name.
/// </summary>
public static class MachineIdentity
{
    private static readonly Lazy<string> Cached = new(Compute);

    public static string Id => Cached.Value;

    private static string Compute()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Cryptography");
            if (key?.GetValue("MachineGuid") is string guid && !string.IsNullOrWhiteSpace(guid))
            {
                return "win:" + guid.Trim().ToLowerInvariant();
            }
        }
        catch
        {
            // registry unavailable: fall through to the hostname-based fallback
        }
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(Environment.MachineName.ToUpperInvariant()));
        return "host:" + Convert.ToHexString(bytes)[..32].ToLowerInvariant();
    }
}
