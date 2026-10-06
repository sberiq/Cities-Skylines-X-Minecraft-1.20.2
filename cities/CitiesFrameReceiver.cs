using System;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace CitiesCraft
{
    /// <summary>
    /// Receives the low-rate, latest-frame stream from the local Minecraft frame relay.
    /// This class owns only socket and byte-array work; Unity objects are touched by the caller.
    /// </summary>
    internal sealed class CitiesFrameReceiver : IDisposable
    {
        private const int Port = 25599;
        private const int HeaderLength = 96;
        private const int MaxWidth = 640;
        private const int MaxHeight = 360;
        private const int MaxPlaneLength = MaxWidth * MaxHeight * 4;
        private const long MaxPayloadLength = (long)MaxPlaneLength * 4L;

        private static readonly byte[] Handshake = Encoding.ASCII.GetBytes("CCFRAME/3\tcities\n");
        private static readonly byte[] AcceptedHandshake = Encoding.ASCII.GetBytes("CCFRAME/3\tOK");
        private static readonly byte[] Magic = new byte[] { (byte)'C', (byte)'C', (byte)'F', (byte)'3' };

        private readonly object _frameLock = new object();
        private Thread _thread;
        private volatile bool _stopping;
        private volatile bool _connected;
        private volatile TcpClient _activeClient;
        private Frame _latestFrame;

        public bool Connected { get { return _connected; } }

        public void Start()
        {
            if (_thread != null) return;
            _thread = new Thread(Run);
            _thread.IsBackground = true;
            _thread.Name = "CitiesCraft Minecraft frame receiver";
            _thread.Start();
        }

        /// <summary>Transfers ownership of the single pending frame to the Unity main thread.</summary>
        public Frame TakeLatestFrame()
        {
            lock (_frameLock)
            {
                Frame frame = _latestFrame;
                _latestFrame = null;
                return frame;
            }
        }

        private void Run()
        {
            while (!_stopping)
            {
                TcpClient client = new TcpClient();
                try
                {
                    _activeClient = client;
                    if (_stopping) break;

                    client.Connect("127.0.0.1", Port);
                    client.NoDelay = true;
                    NetworkStream stream = client.GetStream();
                    stream.Write(Handshake, 0, Handshake.Length);
                    stream.Flush();
                    if (!ReadHandshakeLine(stream)) throw new IOException("Frame relay handshake was rejected");

                    _connected = true;
                    ReceiveFrames(stream);
                }
                catch (Exception)
                {
                    // The relay is optional and may start after Cities. Retry below.
                }
                finally
                {
                    _connected = false;
                    _activeClient = null;
                    try { client.Close(); } catch { }
                }

                if (!_stopping) Thread.Sleep(750);
            }
        }

        private static bool ReadHandshakeLine(NetworkStream stream)
        {
            byte[] line = new byte[AcceptedHandshake.Length];
            for (int i = 0; i < line.Length; i++)
            {
                int value = stream.ReadByte();
                if (value < 0 || value != AcceptedHandshake[i]) return false;
                line[i] = (byte)value;
            }
            return stream.ReadByte() == '\n';
        }

        private void ReceiveFrames(NetworkStream stream)
        {
            byte[] header = new byte[HeaderLength];
            long previousSequence = -1;

            while (!_stopping)
            {
                ReadExactly(stream, header, 0, header.Length);
                if (!HasMagic(header)) throw new IOException("Frame header magic did not match CCF3");

                int width = ReadInt32BigEndian(header, 4);
                int height = ReadInt32BigEndian(header, 8);
                long sequence = ReadInt64BigEndian(header, 12);
                long timestampNanos = ReadInt64BigEndian(header, 20);
                int worldLength = ReadInt32BigEndian(header, 28);
                int depthLength = ReadInt32BigEndian(header, 32);
                int handLength = ReadInt32BigEndian(header, 36);
                int guiLength = ReadInt32BigEndian(header, 40);
                int depthEncoding = ReadInt32BigEndian(header, 44);
                float nearPlane = ReadFloat32BigEndian(header, 48);
                float farPlane = ReadFloat32BigEndian(header, 52);
                double cameraX = ReadFloat64BigEndian(header, 56);
                double cameraY = ReadFloat64BigEndian(header, 64);
                double cameraZ = ReadFloat64BigEndian(header, 72);
                float cameraYaw = ReadFloat32BigEndian(header, 80);
                float cameraPitch = ReadFloat32BigEndian(header, 84);
                float verticalFov = ReadFloat32BigEndian(header, 88);
                float aspect = ReadFloat32BigEndian(header, 92);

                long expectedLength = (long)width * height * 4L;
                if (width <= 0 || width > MaxWidth || height <= 0 || height > MaxHeight
                    || expectedLength > MaxPlaneLength || worldLength != expectedLength
                    || depthLength != expectedLength || handLength != expectedLength || guiLength != expectedLength
                    || (long)worldLength + depthLength + handLength + guiLength > MaxPayloadLength
                    || depthEncoding != 1 || Single.IsNaN(nearPlane) || Single.IsInfinity(nearPlane)
                    || Single.IsNaN(farPlane) || Single.IsInfinity(farPlane)
                    || nearPlane <= 0f || farPlane <= nearPlane
                    || Double.IsNaN(cameraX) || Double.IsInfinity(cameraX)
                    || Double.IsNaN(cameraY) || Double.IsInfinity(cameraY)
                    || Double.IsNaN(cameraZ) || Double.IsInfinity(cameraZ)
                    || Single.IsNaN(cameraYaw) || Single.IsInfinity(cameraYaw)
                    || Single.IsNaN(cameraPitch) || Single.IsInfinity(cameraPitch)
                    || Single.IsNaN(verticalFov) || Single.IsInfinity(verticalFov)
                    || verticalFov <= 0f || verticalFov >= 180f
                    || Single.IsNaN(aspect) || Single.IsInfinity(aspect) || aspect < 0.25f || aspect > 5f)
                {
                    throw new IOException("Frame dimensions, camera metadata, or layer lengths are invalid");
                }
                if (sequence < 0 || (previousSequence >= 0 && sequence <= previousSequence))
                {
                    throw new IOException("Frame sequence must increase within a connection");
                }

                byte[] world = new byte[worldLength];
                byte[] depth = new byte[depthLength];
                byte[] hand = new byte[handLength];
                byte[] gui = new byte[guiLength];
                ReadExactly(stream, world, 0, world.Length);
                ReadExactly(stream, depth, 0, depth.Length);
                ReadExactly(stream, hand, 0, hand.Length);
                ReadExactly(stream, gui, 0, gui.Length);
                FlipRowsForUnity(world, width, height, 4);
                FlipRowsForUnity(depth, width, height, 4);
                FlipRowsForUnity(hand, width, height, 4);
                FlipRowsForUnity(gui, width, height, 4);
                ConvertDepthEndian(depth);

                previousSequence = sequence;
                lock (_frameLock)
                {
                    // A single pending slot keeps memory bounded; newer frames replace stale ones.
                    _latestFrame = new Frame(width, height, sequence, timestampNanos,
                        nearPlane, farPlane, cameraX, cameraY, cameraZ, cameraYaw, cameraPitch,
                        verticalFov, aspect, world, depth, hand, gui);
                }
            }
        }

        private static void ReadExactly(Stream stream, byte[] buffer, int offset, int count)
        {
            while (count > 0)
            {
                int read = stream.Read(buffer, offset, count);
                if (read <= 0) throw new EndOfStreamException("Frame relay disconnected mid-frame");
                offset += read;
                count -= read;
            }
        }

        private static bool HasMagic(byte[] header)
        {
            return header[0] == Magic[0] && header[1] == Magic[1]
                && header[2] == Magic[2] && header[3] == Magic[3];
        }

        private static int ReadInt32BigEndian(byte[] bytes, int offset)
        {
            uint value = ((uint)bytes[offset] << 24)
                | ((uint)bytes[offset + 1] << 16)
                | ((uint)bytes[offset + 2] << 8)
                | bytes[offset + 3];
            return unchecked((int)value);
        }

        private static long ReadInt64BigEndian(byte[] bytes, int offset)
        {
            ulong value = ((ulong)bytes[offset] << 56)
                | ((ulong)bytes[offset + 1] << 48)
                | ((ulong)bytes[offset + 2] << 40)
                | ((ulong)bytes[offset + 3] << 32)
                | ((ulong)bytes[offset + 4] << 24)
                | ((ulong)bytes[offset + 5] << 16)
                | ((ulong)bytes[offset + 6] << 8)
                | bytes[offset + 7];
            return unchecked((long)value);
        }

        private static void FlipRowsForUnity(byte[] pixels, int width, int height, int bytesPerPixel)
        {
            int rowLength = checked(width * bytesPerPixel);
            byte[] row = new byte[rowLength];
            for (int top = 0, bottom = height - 1; top < bottom; top++, bottom--)
            {
                int topOffset = top * rowLength;
                int bottomOffset = bottom * rowLength;
                Buffer.BlockCopy(pixels, topOffset, row, 0, rowLength);
                Buffer.BlockCopy(pixels, bottomOffset, pixels, topOffset, rowLength);
                Buffer.BlockCopy(row, 0, pixels, bottomOffset, rowLength);
            }
        }

        private static float ReadFloat32BigEndian(byte[] bytes, int offset)
        {
            byte[] value = new byte[4];
            value[0] = bytes[offset];
            value[1] = bytes[offset + 1];
            value[2] = bytes[offset + 2];
            value[3] = bytes[offset + 3];
            if (BitConverter.IsLittleEndian)
            {
                byte temporary = value[0]; value[0] = value[3]; value[3] = temporary;
                temporary = value[1]; value[1] = value[2]; value[2] = temporary;
            }
            return BitConverter.ToSingle(value, 0);
        }

        private static double ReadFloat64BigEndian(byte[] bytes, int offset)
        {
            byte[] value = new byte[8];
            Buffer.BlockCopy(bytes, offset, value, 0, value.Length);
            if (BitConverter.IsLittleEndian) Array.Reverse(value);
            return BitConverter.ToDouble(value, 0);
        }

        private static void ConvertDepthEndian(byte[] depth)
        {
            if (!BitConverter.IsLittleEndian) return;
            for (int offset = 0; offset < depth.Length; offset += 4)
            {
                byte temporary = depth[offset]; depth[offset] = depth[offset + 3]; depth[offset + 3] = temporary;
                temporary = depth[offset + 1]; depth[offset + 1] = depth[offset + 2]; depth[offset + 2] = temporary;
            }
        }

        public void Dispose()
        {
            _stopping = true;
            TcpClient client = _activeClient;
            if (client != null)
            {
                try { client.Close(); } catch { }
            }

            lock (_frameLock) _latestFrame = null;
        }

        internal sealed class Frame
        {
            public readonly int Width;
            public readonly int Height;
            public readonly long Sequence;
            public readonly long TimestampNanos;
            public readonly float NearPlane;
            public readonly float FarPlane;
            public readonly double CameraX;
            public readonly double CameraY;
            public readonly double CameraZ;
            public readonly float CameraYaw;
            public readonly float CameraPitch;
            public readonly float VerticalFov;
            public readonly float Aspect;
            public readonly byte[] WorldPixels;
            public readonly byte[] DepthPixels;
            public readonly byte[] HandPixels;
            public readonly byte[] GuiPixels;

            public Frame(int width, int height, long sequence, long timestampNanos,
                float nearPlane, float farPlane, double cameraX, double cameraY, double cameraZ,
                float cameraYaw, float cameraPitch, float verticalFov, float aspect,
                byte[] worldPixels, byte[] depthPixels, byte[] handPixels, byte[] guiPixels)
            {
                Width = width;
                Height = height;
                Sequence = sequence;
                TimestampNanos = timestampNanos;
                NearPlane = nearPlane;
                FarPlane = farPlane;
                CameraX = cameraX;
                CameraY = cameraY;
                CameraZ = cameraZ;
                CameraYaw = cameraYaw;
                CameraPitch = cameraPitch;
                VerticalFov = verticalFov;
                Aspect = aspect;
                WorldPixels = worldPixels;
                DepthPixels = depthPixels;
                HandPixels = handPixels;
                GuiPixels = guiPixels;
            }
        }
    }
}
