using System.IO;
using Microsoft.Win32;

namespace EmpVerify.Agent.Windows;

/// <summary>A deploy-time enrollment token (and optional backend URL) for silent registration.</summary>
public sealed record Provisioning(string EnrollmentToken, string? BackendUrl);

/// <summary>
/// Reads an enrollment token supplied at deploy time so the agent self-registers
/// on first launch — the employee never types anything. IT/MDM sets it in
/// HKLM\Software\EmpVerify (values EnrollmentToken, optional BackendUrl), or drops
/// a file at %ProgramData%\EmpVerify\enroll.token ("token" on line 1, optional
/// backend URL on line 2). Manual entry remains the fallback.
/// </summary>
public static class ProvisioningReader
{
    private const string RegPath = @"SOFTWARE\EmpVerify";

    private static readonly string FilePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
        "EmpVerify", "enroll.token");

    public static Provisioning? TryRead()
    {
        // 1. Registry (HKLM) — what an installer or Intune policy typically sets.
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(RegPath);
            if (key?.GetValue("EnrollmentToken") is string token && !string.IsNullOrWhiteSpace(token))
            {
                var url = key.GetValue("BackendUrl") as string;
                return new Provisioning(token.Trim(), string.IsNullOrWhiteSpace(url) ? null : url!.Trim());
            }
        }
        catch { /* registry unavailable: fall through */ }

        // 2. ProgramData file.
        try
        {
            if (File.Exists(FilePath))
            {
                var lines = File.ReadAllLines(FilePath);
                if (lines.Length > 0 && !string.IsNullOrWhiteSpace(lines[0]))
                {
                    var url = lines.Length > 1 && !string.IsNullOrWhiteSpace(lines[1]) ? lines[1].Trim() : null;
                    return new Provisioning(lines[0].Trim(), url);
                }
            }
        }
        catch { /* file unreadable: fall through */ }

        return null;
    }

    /// <summary>Best-effort cleanup after a successful registration (needs admin; ignored if denied).</summary>
    public static void Clear()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(RegPath, writable: true);
            key?.DeleteValue("EnrollmentToken", throwOnMissingValue: false);
        }
        catch { }
        try { if (File.Exists(FilePath)) File.Delete(FilePath); } catch { }
    }
}
