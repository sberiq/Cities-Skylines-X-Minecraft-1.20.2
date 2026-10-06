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
        private const int HeaderLength = 24;
        private const int MaxWidth = 3840;
        private const int MaxHeight = 2160;
        private const int MaxPayloadLength = MaxWidth * MaxHeight * 4;

        private static readonly byte[] Handshake = Encoding.ASCII.GetBytes("CCFRAME/1\tcities\n");
        private static readonly byte[] AcceptedHandshake = Encoding.ASCII.GetBytes("CCFRAME/1\tOK");
        private static readonly byte[] Magic = new byte[] { (byte)'C', (byte)'C', (byte)'F', (byte)'1' };

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
                if (!HasMagic(header)) throw new IOException("Frame header magic did not match CCF1");

                int width = ReadInt32BigEndian(header, 4);
                int height = ReadInt32BigEndian(header, 8);
                long sequence = ReadInt64BigEndian(header, 12);
                int payloadLength = ReadInt32BigEndian(header, 20);

                long expectedLength = (long)width * height * 4L;
                if (width <= 0 || width > MaxWidth || height <= 0 || height > MaxHeight
                    || expectedLength > MaxPayloadLength || payloadLength != expectedLength)
                {
                    throw new IOException("Frame dimensions or RGBA payload length are outside supported bounds");
                }
                if (sequence < 0 || (previousSequence >= 0 && sequence <= previousSequence))
                {
                    throw new IOException("Frame sequence must increase within a connection");
                }

                byte[] pixels = new byte[payloadLength];
                ReadExactly(stream, pixels, 0, pixels.Length);
                FlipRowsForUnity(pixels, width, height);

                previousSequence = sequence;
                lock (_frameLock)
                {
                    // A single pending slot keeps memory bounded; newer frames replace stale ones.
                    _latestFrame = new Frame(width, height, sequence, pixels);
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

        private static void FlipRowsForUnity(byte[] pixels, int width, int height)
        {
            int rowLength = checked(width * 4);
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
            public readonly byte[] Pixels;

            public Frame(int width, int height, long sequence, byte[] pixels)
            {
                Width = width;
                Height = height;
                Sequence = sequence;
                Pixels = pixels;
            }
        }
    }
}
