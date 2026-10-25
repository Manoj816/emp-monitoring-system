namespace EmpVerify.Agent.Core.Abstractions;

/// <summary>
/// Secure storage for the per-device credentials issued at registration.
/// Windows: Credential Manager / DPAPI. macOS (future): Keychain.
/// </summary>
public interface ICredentialStore
{
    Task SaveAsync(DeviceCredentials credentials, CancellationToken cancellationToken);
    Task<DeviceCredentials?> LoadAsync(CancellationToken cancellationToken);
    Task DeleteAsync(CancellationToken cancellationToken);
}
