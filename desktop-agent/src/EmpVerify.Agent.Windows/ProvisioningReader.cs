using System.IO;
using Microsoft.Win32;

namespace EmpVerify.Agent.Windows;

public enum ProvisioningKind
{
    /// <summary>Tenant-wide token: the device registers as unassigned; an admin links it to an employee.</summary>
    Provisioning,
    /// <summary>One-time, per-employee token: the device is bound to that employee at registration.</summary>
    Enrollment,
}

/// <summary>A deploy-time token (and optional backend URL) so the agent registers itself on first launch.</summary>
public sealed record Provisioning(ProvisioningKind Kind, string Token, string? BackendUrl);

/// <summary>
/// Reads a token the installer/MDM placed on the machine so the agent self-registers
/// without the employee typing anything. HKLM\Software\EmpVerify values:
/// ProvisioningToken (tenant, device-first) or EnrollmentToken (per-employee), plus
/// optional BackendUrl. File fallback: %ProgramData%\EmpVerify\provision.token or
/// enroll.token (token on line 1, optional backend URL on line 2).
/// </summary>
public static class ProvisioningReader
{
    private const string RegPath = @"SOFTWARE\EmpVerify";

    private static readonly string FileDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "EmpVerify");

    public static Provisioning? TryRead()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(RegPath);
            if (key is not null)
            {
                var url = key.GetValue("BackendUrl") as string;
                url = string.IsNullOrWhiteSpace(url) ? null : url!.Trim();
                if (key.GetValue("ProvisioningToken") is string tenant && !string.IsNullOrWhiteSpace(tenant))
                    return new Provisioning(ProvisioningKind.Provisioning, tenant.Trim(), url);
                if (key.GetValue("EnrollmentToken") is string enroll && !string.IsNullOrWhiteSpace(enroll))
                    return new Provisioning(ProvisioningKind.Enrollment, enroll.Trim(), url);
            }
        }
        catch { /* registry unavailable: fall through */ }

        return ReadFile("provision.token", ProvisioningKind.Provisioning)
            ?? ReadFile("enroll.token", ProvisioningKind.Enrollment);
    }

    private static Provisioning? ReadFile(string name, ProvisioningKind kind)
    {
        try
        {
            var path = Path.Combine(FileDir, name);
            if (!File.Exists(path)) return null;
            var lines = File.ReadAllLines(path);
            if (lines.Length == 0 || string.IsNullOrWhiteSpace(lines[0])) return null;
            var url = lines.Length > 1 && !string.IsNullOrWhiteSpace(lines[1]) ? lines[1].Trim() : null;
            return new Provisioning(kind, lines[0].Trim(), url);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>Best-effort cleanup of one-time enrollment material after registration (needs admin; ignored if denied).</summary>
    public static void Clear()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(RegPath, writable: true);
            key?.DeleteValue("EnrollmentToken", throwOnMissingValue: false);
        }
        catch { }
        try
        {
            var enroll = Path.Combine(FileDir, "enroll.token");
            if (File.Exists(enroll)) File.Delete(enroll);
        }
        catch { }
        // The tenant provisioning token is intentionally left in place: it is not
        // single-use, and a reset/re-image should be able to self-register again.
    }
}
