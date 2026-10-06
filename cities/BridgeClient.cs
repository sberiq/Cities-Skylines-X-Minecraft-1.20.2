using System;
using System.Collections.Generic;
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
        private ViewState _view;
        private int _viewReceivedAt;
        private string _latestSnapshot;
        private long _snapshotGeneration;
        private long _inputSequence;
        private readonly Queue<string> _inputQueue = new Queue<string>();
        private volatile float _collisionRadius = 96f;
        private volatile float _depthScale = 1f;

        public bool Connected { get { return _connected; } }
        public float CollisionRadius { get { return _collisionRadius; } }
        public float DepthScale { get { return _depthScale; } }
        public PlayerState LatestPlayer { get { lock (_lock) return _player; } }
        public ViewState LatestView { get { lock (_lock) return _view; } }
        public bool HasFreshView
        {
            get { return _viewReceivedAt != 0 && unchecked(Environment.TickCount - _viewReceivedAt) < 1500; }
        }

        public void PublishCitySnapshot(string snapshot)
        {
            lock (_lock)
            {
                _latestSnapshot = snapshot;
                _snapshotGeneration++;
            }
        }

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

        public void PublishInput(string type, params string[] values)
        {
            if (String.IsNullOrEmpty(type) || values == null) return;
            long sequence = Interlocked.Increment(ref _inputSequence) - 1;
            StringBuilder line = new StringBuilder("INPUT\t");
            line.Append(sequence.ToString(CultureInfo.InvariantCulture)).Append('\t').Append(type);
            for (int i = 0; i < values.Length; i++)
            {
                string value = values[i] ?? String.Empty;
                if (value.IndexOf('\t') >= 0 || value.IndexOf('\n') >= 0 || value.IndexOf('\r') >= 0) return;
                line.Append('\t').Append(value);
            }
            lock (_lock)
            {
                if (_inputQueue.Count >= 512) _inputQueue.Dequeue();
                _inputQueue.Enqueue(line.ToString());
            }
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
                    long sentSnapshotGeneration = -1;
                    while (!_stopping && _connected)
                    {
                        CameraState camera;
                        string snapshot;
                        long snapshotGeneration;
                        string[] inputs;
                        lock (_lock)
                        {
                            camera = _camera;
                            snapshot = _latestSnapshot;
                            snapshotGeneration = _snapshotGeneration;
                            inputs = _inputQueue.ToArray();
                            _inputQueue.Clear();
                        }
                        if (camera != null)
                        {
                            writer.WriteLine(String.Format(CultureInfo.InvariantCulture,
                                "CAMERA\t{0}\t{1:R}\t{2:R}\t{3:R}\t{4:R}\t{5:R}\t{6:R}",
                                sequence++, camera.X, camera.Y, camera.Z, camera.Yaw, camera.Pitch, camera.VerticalFov));
                        }
                        if (snapshot != null && snapshotGeneration != sentSnapshotGeneration)
                        {
                            writer.WriteLine(snapshot);
                            sentSnapshotGeneration = snapshotGeneration;
                        }
                        for (int i = 0; i < inputs.Length; i++) writer.WriteLine(inputs[i]);
                        Thread.Sleep(10);
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
                    if (fields.Length == 3 && fields[0] == "SETTINGS")
                    {
                        double radius;
                        double scale;
                        if (Parse(fields[1], out radius) && radius >= 16.0 && radius <= 96.0)
                            _collisionRadius = (float)radius;
                        if (Parse(fields[2], out scale) && scale > 0.0 && scale <= 64.0)
                            _depthScale = (float)scale;
                        continue;
                    }
                    if (fields.Length == 7 && fields[0] == "PLAYER")
                    {
                        double x, y, z, yaw, pitch;
                        if (!Parse(fields[2], out x) || !Parse(fields[3], out y) || !Parse(fields[4], out z)
                            || !Parse(fields[5], out yaw) || !Parse(fields[6], out pitch)) continue;
                        lock (_lock) _player = new PlayerState(x, y, z, yaw, pitch);
                        continue;
                    }
                    if (fields.Length == 9 && fields[0] == "VIEW")
                    {
                        double x, y, z, yaw, pitch, fov, aspect;
                        if (!Parse(fields[2], out x) || !Parse(fields[3], out y) || !Parse(fields[4], out z)
                            || !Parse(fields[5], out yaw) || !Parse(fields[6], out pitch)
                            || !Parse(fields[7], out fov) || !Parse(fields[8], out aspect)
                            || fov <= 0.0 || fov >= 180.0 || aspect < 0.25 || aspect > 5.0) continue;
                        lock (_lock)
                        {
                            _view = new ViewState(x, y, z, yaw, pitch, fov, aspect);
                            _viewReceivedAt = Environment.TickCount;
                        }
                    }
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

    internal sealed class ViewState
    {
        public readonly double X, Y, Z, Yaw, Pitch, VerticalFov, Aspect;
        public ViewState(double x, double y, double z, double yaw, double pitch, double verticalFov, double aspect)
        { X = x; Y = y; Z = z; Yaw = yaw; Pitch = pitch; VerticalFov = verticalFov; Aspect = aspect; }
    }
}
