using EmpVerify.Agent.Core.Abstractions;
using Windows.Devices.Enumeration;
using CapturedFrame = EmpVerify.Agent.Core.CapturedFrame;
using Windows.Media.Capture;
using Windows.Media.MediaProperties;
using Windows.Storage.Streams;

namespace EmpVerify.Agent.Windows.Services;

/// <summary>
/// Webcam capture via Windows MediaCapture. Using MediaCapture (rather than
/// DirectShow) means Windows shows its camera-in-use indicator automatically and
/// the app appears in Settings &gt; Privacy &gt; Camera — the transparency the
/// product requires. The MediaCapture instance is disposed after every capture so
/// the camera is fully released (required before the browser liveness flow runs).
/// </summary>
public sealed class WindowsCameraService : ICameraService
{
    public async Task<CapturedFrame?> CaptureFrameAsync(CancellationToken cancellationToken)
    {
        var device = await FindCameraAsync();
        if (device is null)
            return null;

        var capture = new MediaCapture();
        try
        {
            await capture.InitializeAsync(new MediaCaptureInitializationSettings
            {
                VideoDeviceId = device.Id,
                StreamingCaptureMode = StreamingCaptureMode.Video,
            });

            using var stream = new InMemoryRandomAccessStream();
            await capture.CapturePhotoToStreamAsync(ImageEncodingProperties.CreateJpeg(), stream);

            var bytes = new byte[stream.Size];
            stream.Seek(0);
            using var reader = new DataReader(stream.GetInputStreamAt(0));
            await reader.LoadAsync((uint)stream.Size);
            reader.ReadBytes(bytes);

            return new CapturedFrame(bytes, device.Name);
        }
        catch (UnauthorizedAccessException)
        {
            // Camera permission denied in Windows privacy settings: surfaces as
            // VERIFICATION_ERROR server-side, never as an identity mismatch.
            return null;
        }
        finally
        {
            capture.Dispose();
        }
    }

    public async Task<bool> IsCameraBusyAsync(CancellationToken cancellationToken)
    {
        var device = await FindCameraAsync();
        if (device is null)
            return false;

        var capture = new MediaCapture();
        try
        {
            await capture.InitializeAsync(new MediaCaptureInitializationSettings
            {
                VideoDeviceId = device.Id,
                StreamingCaptureMode = StreamingCaptureMode.Video,
            });
            return false;
        }
        catch
        {
            // Initialization failure while a device exists usually means another
            // app holds the camera; the check is deferred rather than failed.
            return true;
        }
        finally
        {
            capture.Dispose();
        }
    }

    public async Task<IReadOnlyList<string>> ListCameraDevicesAsync(CancellationToken cancellationToken)
    {
        var devices = await DeviceInformation.FindAllAsync(DeviceClass.VideoCapture);
        return devices.Select(d => d.Name).ToList();
    }

    private static async Task<DeviceInformation?> FindCameraAsync()
    {
        var devices = await DeviceInformation.FindAllAsync(DeviceClass.VideoCapture);
        return devices.FirstOrDefault();
    }
}
