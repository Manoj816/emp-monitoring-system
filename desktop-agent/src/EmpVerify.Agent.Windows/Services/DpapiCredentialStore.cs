using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using EmpVerify.Agent.Core;
using EmpVerify.Agent.Core.Abstractions;

namespace EmpVerify.Agent.Windows.Services;

/// <summary>
/// Device credentials encrypted at rest with DPAPI (current-user scope) under
/// %LOCALAPPDATA%\EmpVerify. The secret never leaves this machine unencrypted.
/// </summary>
public sealed class DpapiCredentialStore : ICredentialStore
{
    private static readonly string FilePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "EmpVerify", "device.credentials");

    /// <summary>Fast synchronous check used at startup to decide show-vs-background.</summary>
    public static bool CredentialsExist() => File.Exists(FilePath);

    public Task SaveAsync(DeviceCredentials credentials, CancellationToken cancellationToken)
    {
        var json = JsonSerializer.Serialize(credentials);
        var protectedBytes = ProtectedData.Protect(
            Encoding.UTF8.GetBytes(json), optionalEntropy: null, DataProtectionScope.CurrentUser);
        Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
        File.WriteAllBytes(FilePath, protectedBytes);
        return Task.CompletedTask;
    }

    public Task<DeviceCredentials?> LoadAsync(CancellationToken cancellationToken)
    {
        if (!File.Exists(FilePath))
            return Task.FromResult<DeviceCredentials?>(null);

        var protectedBytes = File.ReadAllBytes(FilePath);
        var json = Encoding.UTF8.GetString(ProtectedData.Unprotect(
            protectedBytes, optionalEntropy: null, DataProtectionScope.CurrentUser));
        return Task.FromResult(JsonSerializer.Deserialize<DeviceCredentials>(json));
    }

    public Task DeleteAsync(CancellationToken cancellationToken)
    {
        if (File.Exists(FilePath))
            File.Delete(FilePath);
        return Task.CompletedTask;
    }
}
