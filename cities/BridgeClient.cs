using System;
using System.Globalization;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace CitiesCraft
{
    internal sealed class BridgeClient : IDisposable
    {
        private readonly object _lock = new object();
        private Thread _thread;
        private volatile bool _stopping;
        private volatile bool _connected;
        private volatile TcpClient _activeClient;
        private CameraState _camera;
        private PlayerState _player;

        public bool Connected { get { return _connected; } }
        public PlayerState LatestPlayer { get { lock (_lock) return _player; } }

        public void Start()
        {
            _thread = new Thread(Run);
            _thread.IsBackground = true;
            _thread.Name = "CitiesCraft Bridge link";
            _thread.Start();
        }

        public void PublishCamera(CameraState camera)
        {
            lock (_lock) _camera = camera;
        }

        private void Run()
        {
            long sequence = 0;
            while (!_stopping)
            {
                TcpClient client = new TcpClient();
                try
                {
                    client.Connect("127.0.0.1", 25598);
                    client.NoDelay = true;
                    NetworkStream stream = client.GetStream();
                    StreamReader reader = new StreamReader(stream, new UTF8Encoding(false, true));
                    StreamWriter writer = new StreamWriter(stream, new UTF8Encoding(false), 1024);
                    writer.AutoFlush = true;
                    writer.WriteLine("HELLO\t1\tcities");
                    string welcome = reader.ReadLine();
                    if (welcome == null || !welcome.StartsWith("WELCOME\t1\t", StringComparison.Ordinal))
                        throw new IOException("Bridge rejected Cities client");

                    _activeClient = client;
                    _connected = true;
                    Thread readerThread = new Thread(delegate() { ReadLoop(client, reader); });
                    readerThread.IsBackground = true;
                    readerThread.Start();
                    while (!_stopping && _connected)
                    {
                        CameraState camera;
                        lock (_lock) camera = _camera;
                        if (camera != null)
                        {
                            writer.WriteLine(String.Format(CultureInfo.InvariantCulture,
                                "CAMERA\t{0}\t{1:R}\t{2:R}\t{3:R}\t{4:R}\t{5:R}\t{6:R}",
                                sequence++, camera.X, camera.Y, camera.Z, camera.Yaw, camera.Pitch, camera.VerticalFov));
                        }
                        Thread.Sleep(100);
                    }
                }
                catch (Exception)
                {
                    _connected = false;
                }
                finally
                {
                    _connected = false;
                    _activeClient = null;
                    try { client.Close(); } catch { }
                }
                if (!_stopping) Thread.Sleep(1000);
            }
        }

        private void ReadLoop(TcpClient client, StreamReader reader)
        {
            try
            {
                string line;
                while (!_stopping && (line = reader.ReadLine()) != null)
                {
                    string[] fields = line.Split('\t');
                    if (fields.Length != 7 || fields[0] != "PLAYER") continue;
                    double x, y, z, yaw, pitch;
                    if (!Parse(fields[2], out x) || !Parse(fields[3], out y) || !Parse(fields[4], out z)
                        || !Parse(fields[5], out yaw) || !Parse(fields[6], out pitch)) continue;
                    lock (_lock) _player = new PlayerState(x, y, z, yaw, pitch);
                }
            }
            catch (IOException) { }
            catch (ObjectDisposedException) { }
            finally
            {
                _connected = false;
                try { client.Close(); } catch { }
            }
        }

        private static bool Parse(string text, out double value)
        {
            return Double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out value)
                && !Double.IsNaN(value) && !Double.IsInfinity(value);
        }

        public void Dispose()
        {
            _stopping = true;
            TcpClient client = _activeClient;
            if (client != null) try { client.Close(); } catch { }
        }
    }

    internal sealed class CameraState
    {
        public readonly double X, Y, Z, Yaw, Pitch, VerticalFov;
        public CameraState(double x, double y, double z, double yaw, double pitch, double verticalFov)
        { X = x; Y = y; Z = z; Yaw = yaw; Pitch = pitch; VerticalFov = verticalFov; }
    }

    internal sealed class PlayerState
    {
        public readonly double X, Y, Z, Yaw, Pitch;
        public PlayerState(double x, double y, double z, double yaw, double pitch)
        { X = x; Y = y; Z = z; Yaw = yaw; Pitch = pitch; }
    }
}
