using ColossalFramework;
using UnityEngine;
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Threading;
using Object = UnityEngine.Object;

namespace CitiesCraft
{
    internal sealed class CitiesCraftOverlay : MonoBehaviour
    {
        private BridgeClient _bridge;
        private CitiesFrameReceiver _frameReceiver;
        private CitiesNativeCompositor _compositor;
        private long _frameSequence = -1;
        private float _lastFrameAt;
        private CitiesFrameReceiver.Frame _cameraFrame;
        private Texture2D _worldColor;
        private Texture2D _linearDepth;
        private Texture2D _handLayer;
        private Texture2D _guiLayer;
        private int _textureWidth;
        private int _textureHeight;
        private float _frameNear = 0.05f;
        private float _frameFar = 256f;
        private Camera _gameCamera;
        private CameraController _cameraController;
        private float _lastCameraSearch;
        private float _lastSnapshotRequest;
        private int _snapshotPending;
        private long _snapshotSequence;
        private volatile bool _stoppingSnapshots;
        private bool _passthroughEnabled = true;
        private bool _inputActive;
        private Vector2 _lastCursor = new Vector2(-1f, -1f);
        private Vector3 _calibrationAnchor;
        private float _calibrationGroundHeight;
        private float _lastAnchorSample;
        private bool _compositorBackendSupported;
        private string _graphicsBackend = "unknown";

        public void StartLink()
        {
            _graphicsBackend = SystemInfo.graphicsDeviceVersion ?? "unknown";
            _compositorBackendSupported = _graphicsBackend.IndexOf("OpenGL", StringComparison.OrdinalIgnoreCase) >= 0
                && _graphicsBackend.IndexOf("Metal", StringComparison.OrdinalIgnoreCase) < 0;
            if (!_compositorBackendSupported)
            {
                _passthroughEnabled = false;
                Debug.LogWarning("CitiesCraft passthrough renderer needs OpenGL; detected " + _graphicsBackend
                    + ". The image effect is disabled to prevent a Cities render crash.");
            }

            _bridge = new BridgeClient();
            _bridge.Start();

            _frameReceiver = new CitiesFrameReceiver();
            _frameReceiver.Start();
        }

        private void Update()
        {
            UpdateMinecraftFrame();

            Camera camera = GetGameCamera();
            bool frameFresh = _cameraFrame != null && Time.realtimeSinceStartup - _lastFrameAt < 1.0f;
            bool stateReady = _bridge != null && _bridge.Connected;

            if (_compositorBackendSupported && Input.GetKeyDown(KeyCode.F8))
            {
                _passthroughEnabled = !_passthroughEnabled;
                if (!_passthroughEnabled && _inputActive) ReleaseRemoteInputs();
                if (!_passthroughEnabled) _inputActive = false;
            }

            if (_compositorBackendSupported && _passthroughEnabled && frameFresh && camera != null)
            {
                CitiesFirstPersonCamera.SetPose(camera,
                    new Vector3((float)_cameraFrame.CameraX, (float)_cameraFrame.CameraY, (float)_cameraFrame.CameraZ),
                    _cameraFrame.CameraYaw, _cameraFrame.CameraPitch,
                    _cameraFrame.VerticalFov, _cameraFrame.Aspect);
                if (stateReady)
                {
                    CaptureRemoteInput();
                    _inputActive = true;
                }
                else
                {
                    if (_inputActive) ReleaseRemoteInputs();
                    _inputActive = false;
                    _lastCursor = new Vector2(-1f, -1f);
                }
            }
            else
            {
                if (camera != null) CitiesFirstPersonCamera.Clear(camera);
                if (_inputActive) ReleaseRemoteInputs();
                _inputActive = false;
                _lastCursor = new Vector2(-1f, -1f);
            }

            if (_compositor != null)
            {
                _compositor.PassthroughActive = _compositorBackendSupported && _passthroughEnabled && frameFresh;
                _compositor.MinecraftDepthScale = _bridge == null ? 1f : _bridge.DepthScale;
            }

            UpdateCalibrationAnchor();
            RequestWorldSnapshot();
        }

        private void CaptureRemoteInput()
        {
            if (_bridge == null) return;

            InputKey[] keys = InputKeys;
            for (int i = 0; i < keys.Length; i++)
            {
                if (Input.GetKeyDown(keys[i].UnityKey))
                    SendKey(keys[i].GlfwKey, 1);
                if (Input.GetKeyUp(keys[i].UnityKey))
                    SendKey(keys[i].GlfwKey, 0);
            }

            float mouseX = Input.GetAxisRaw("Mouse X");
            float mouseY = Input.GetAxisRaw("Mouse Y");
            if (mouseX != 0f || mouseY != 0f)
                _bridge.PublishInput("LOOK", Number(mouseX), Number(-mouseY));

            Vector3 cursor = Input.mousePosition;
            Vector2 normalizedCursor = new Vector2(
                Screen.width <= 0 ? 0f : Mathf.Clamp01(cursor.x / Screen.width),
                Screen.height <= 0 ? 0f : Mathf.Clamp01(1f - cursor.y / Screen.height));
            if (Mathf.Abs(normalizedCursor.x - _lastCursor.x) > 0.0005f
                || Mathf.Abs(normalizedCursor.y - _lastCursor.y) > 0.0005f)
            {
                _bridge.PublishInput("CURSOR", Number(normalizedCursor.x), Number(normalizedCursor.y));
                _lastCursor = normalizedCursor;
            }

            for (int button = 0; button <= 7; button++)
            {
                if (button > 6) break;
                if (Input.GetMouseButtonDown(button)) SendButton(button, 1);
                if (Input.GetMouseButtonUp(button)) SendButton(button, 0);
            }

            float scroll = Input.GetAxisRaw("Mouse ScrollWheel");
            if (scroll != 0f) _bridge.PublishInput("SCROLL", "0", Number(scroll * 10f));
        }

        private void SendKey(int glfwKey, int action)
        {
            _bridge.PublishInput("KEY", glfwKey.ToString(CultureInfo.InvariantCulture), "0",
                action.ToString(CultureInfo.InvariantCulture), InputModifiers().ToString(CultureInfo.InvariantCulture));
        }

        private void SendButton(int button, int action)
        {
            _bridge.PublishInput("BUTTON", button.ToString(CultureInfo.InvariantCulture),
                action.ToString(CultureInfo.InvariantCulture), InputModifiers().ToString(CultureInfo.InvariantCulture));
        }

        private void ReleaseRemoteInputs()
        {
            InputKey[] keys = InputKeys;
            for (int i = 0; i < keys.Length; i++) SendKey(keys[i].GlfwKey, 0);
            for (int button = 0; button <= 6; button++) SendButton(button, 0);
        }

        private static int InputModifiers()
        {
            int modifiers = 0;
            if (Input.GetKey(ParseKey("LeftShift")) || Input.GetKey(ParseKey("RightShift"))) modifiers |= 1;
            if (Input.GetKey(ParseKey("LeftControl")) || Input.GetKey(ParseKey("RightControl"))) modifiers |= 2;
            if (Input.GetKey(ParseKey("LeftAlt")) || Input.GetKey(ParseKey("RightAlt"))) modifiers |= 4;
            return modifiers;
        }

        private static string Number(float value)
        {
            return value.ToString("R", CultureInfo.InvariantCulture);
        }

        private void OnGUI()
        {
            Event current = Event.current;
            if (_passthroughEnabled && current != null && current.type == EventType.KeyDown)
            {
                if (current.character != '\0' && !Char.IsControl(current.character) && _bridge != null)
                {
                    _bridge.PublishInput("TEXT", ((int)current.character).ToString(CultureInfo.InvariantCulture),
                        InputModifiers().ToString(CultureInfo.InvariantCulture));
                }
                current.Use();
            }

            bool integratedView = _compositorBackendSupported && _compositor != null && _compositor.IsAvailable
                && _passthroughEnabled && _bridge != null && _bridge.Connected
                && _cameraFrame != null && Time.realtimeSinceStartup - _lastFrameAt < 1.0f;
            if (integratedView) return;

            string bridgeStatus = _bridge != null && _bridge.Connected ? "connected" : "connecting";
            string viewStatus = _bridge != null && _bridge.HasFreshView ? "Minecraft view live" : "waiting for Minecraft view";
            string mode = !_compositorBackendSupported
                ? "passthrough unavailable: " + _graphicsBackend + " (Steam launch option: -force-glcore)"
                : (_passthroughEnabled ? "F8: passthrough on" : "F8: passthrough off");
            string frameStatus = _frameSequence < 0 ? "waiting for Minecraft frame" : "frame " + _frameSequence;
            GUI.Label(new Rect(12, 12, 900, 24), "CitiesCraft  |  Bridge " + bridgeStatus + "  |  " + viewStatus + "  |  " + frameStatus + "  |  " + mode);
            GUI.Label(new Rect(12, 34, 520, 22), string.Format(CultureInfo.InvariantCulture,
                "Cities anchor: X {0:F2}  ground Y {1:F2}  Z {2:F2}",
                _calibrationAnchor.x, _calibrationGroundHeight, _calibrationAnchor.z));
        }

        private void UpdateCalibrationAnchor()
        {
            if (_gameCamera == null || Time.realtimeSinceStartup - _lastAnchorSample < 1f) return;
            _calibrationAnchor = _cameraController != null
                ? _cameraController.targetPosition
                : _gameCamera.transform.position;
            _calibrationGroundHeight = TerrainManager.instance.SampleFinalHeightSmooth(
                _calibrationAnchor.x, _calibrationAnchor.z);
            _lastAnchorSample = Time.realtimeSinceStartup;
        }

        private void RequestWorldSnapshot()
        {
            if (_bridge == null || _stoppingSnapshots || Time.realtimeSinceStartup - _lastSnapshotRequest < 0.5f) return;
            PlayerState player = _bridge.LatestPlayer;
            if (player == null || Interlocked.CompareExchange(ref _snapshotPending, 1, 0) != 0) return;

            _lastSnapshotRequest = Time.realtimeSinceStartup;
            long sequence = Interlocked.Increment(ref _snapshotSequence);
            float centerX = (float)player.X;
            float centerZ = (float)player.Z;
            float radius = _bridge.CollisionRadius;
            BridgeClient bridge = _bridge;
            try
            {
                SimulationManager.instance.AddAction(delegate()
                {
                    try
                    {
                        if (!_stoppingSnapshots)
                            bridge.PublishCitySnapshot(CitiesWorldSnapshotBuilder.Build(sequence, centerX, centerZ, radius));
                    }
                    catch (Exception exception)
                    {
                        Debug.LogException(exception);
                    }
                    finally
                    {
                        Interlocked.Exchange(ref _snapshotPending, 0);
                    }
                });
            }
            catch (Exception exception)
            {
                Interlocked.Exchange(ref _snapshotPending, 0);
                Debug.LogException(exception);
            }
        }

        private Camera GetGameCamera()
        {
            if (_gameCamera == null || Time.realtimeSinceStartup - _lastCameraSearch >= 2f)
            {
                CameraController controller = Object.FindObjectOfType<CameraController>();
                if (controller != null)
                {
                    _cameraController = controller;
                    _gameCamera = controller.GetComponent<Camera>();
                    if (_gameCamera == null)
                    {
                        Camera[] children = controller.GetComponentsInChildren<Camera>(true);
                        if (children != null && children.Length > 0) _gameCamera = children[0];
                    }
                }
                if (_gameCamera == null) _gameCamera = Camera.main;
                _lastCameraSearch = Time.realtimeSinceStartup;
            }

            if (_gameCamera != null && _compositorBackendSupported
                && (_compositor == null || _compositor.gameObject != _gameCamera.gameObject))
            {
                _compositor = _gameCamera.GetComponent<CitiesNativeCompositor>();
                if (_compositor == null) _compositor = _gameCamera.gameObject.AddComponent<CitiesNativeCompositor>();
                if (_worldColor != null)
                    _compositor.SetMinecraftFrames(_worldColor, _linearDepth, _handLayer, _guiLayer,
                        _frameNear, _frameFar);
            }
            return _gameCamera;
        }

        private void UpdateMinecraftFrame()
        {
            if (_frameReceiver == null) return;
            CitiesFrameReceiver.Frame frame = _frameReceiver.TakeLatestFrame();
            if (frame == null) return;

            if (_worldColor == null || _textureWidth != frame.Width || _textureHeight != frame.Height)
            {
                DestroyMinecraftTextures();
                _textureWidth = frame.Width;
                _textureHeight = frame.Height;
                _worldColor = CreateColorTexture("CitiesCraft Minecraft world", frame.Width, frame.Height);
                _handLayer = CreateColorTexture("CitiesCraft Minecraft hand", frame.Width, frame.Height);
                _guiLayer = CreateColorTexture("CitiesCraft Minecraft GUI", frame.Width, frame.Height);
                _linearDepth = new Texture2D(frame.Width, frame.Height, TextureFormat.RFloat, false, true);
                _linearDepth.name = "CitiesCraft Minecraft linear depth";
                _linearDepth.filterMode = FilterMode.Point;
                _linearDepth.wrapMode = TextureWrapMode.Clamp;
            }

            _worldColor.LoadRawTextureData(frame.WorldPixels);
            _worldColor.Apply(false, false);
            _linearDepth.LoadRawTextureData(frame.DepthPixels);
            _linearDepth.Apply(false, false);
            _handLayer.LoadRawTextureData(frame.HandPixels);
            _handLayer.Apply(false, false);
            _guiLayer.LoadRawTextureData(frame.GuiPixels);
            _guiLayer.Apply(false, false);

            _frameSequence = frame.Sequence;
            _lastFrameAt = Time.realtimeSinceStartup;
            _cameraFrame = frame;
            _frameNear = frame.NearPlane;
            _frameFar = frame.FarPlane;
            if (_compositor != null)
            {
                _compositor.SetMinecraftFrames(_worldColor, _linearDepth, _handLayer, _guiLayer,
                    _frameNear, _frameFar);
                _compositor.MinecraftOverlayPremultipliedAlpha = false;
            }
        }

        private static Texture2D CreateColorTexture(string textureName, int width, int height)
        {
            Texture2D texture = new Texture2D(width, height, TextureFormat.RGBA32, false, true);
            texture.name = textureName;
            texture.filterMode = FilterMode.Bilinear;
            texture.wrapMode = TextureWrapMode.Clamp;
            return texture;
        }

        private void DestroyMinecraftTextures()
        {
            if (_worldColor != null) Object.Destroy(_worldColor);
            if (_linearDepth != null) Object.Destroy(_linearDepth);
            if (_handLayer != null) Object.Destroy(_handLayer);
            if (_guiLayer != null) Object.Destroy(_guiLayer);
            _worldColor = null;
            _linearDepth = null;
            _handLayer = null;
            _guiLayer = null;
            _textureWidth = _textureHeight = 0;
        }

        private void OnDestroy()
        {
            _stoppingSnapshots = true;
            CitiesFirstPersonCamera.Clear(_gameCamera);
            if (_compositor != null)
            {
                _compositor.PassthroughActive = false;
                _compositor.SetMinecraftFrames(null, null, null, null, 0.05f, 256f);
                Object.Destroy(_compositor);
                _compositor = null;
            }
            if (_bridge != null) _bridge.Dispose();
            _bridge = null;
            if (_frameReceiver != null) _frameReceiver.Dispose();
            _frameReceiver = null;
            DestroyMinecraftTextures();
        }

        private static readonly InputKey[] InputKeys = BuildInputKeys();

        private static InputKey[] BuildInputKeys()
        {
            List<InputKey> keys = new List<InputKey>();
            for (int i = 0; i < 26; i++) AddKey(keys, ((char)('A' + i)).ToString(), 65 + i);
            for (int i = 0; i < 10; i++) AddKey(keys, "Alpha" + i.ToString(CultureInfo.InvariantCulture), 48 + i);
            AddKey(keys, "Space", 32); AddKey(keys, "Apostrophe", 39); AddKey(keys, "Comma", 44);
            AddKey(keys, "Minus", 45); AddKey(keys, "Period", 46); AddKey(keys, "Slash", 47);
            AddKey(keys, "Semicolon", 59); AddKey(keys, "Equals", 61); AddKey(keys, "LeftBracket", 91);
            AddKey(keys, "Backslash", 92); AddKey(keys, "RightBracket", 93); AddKey(keys, "BackQuote", 96);
            AddKey(keys, "Escape", 256); AddKey(keys, "Return", 257); AddKey(keys, "Tab", 258);
            AddKey(keys, "Backspace", 259); AddKey(keys, "Insert", 260); AddKey(keys, "Delete", 261);
            AddKey(keys, "RightArrow", 262); AddKey(keys, "LeftArrow", 263); AddKey(keys, "DownArrow", 264);
            AddKey(keys, "UpArrow", 265); AddKey(keys, "PageUp", 266); AddKey(keys, "PageDown", 267);
            AddKey(keys, "Home", 268); AddKey(keys, "End", 269); AddKey(keys, "CapsLock", 280);
            for (int i = 1; i <= 12; i++) AddKey(keys, "F" + i.ToString(CultureInfo.InvariantCulture), 289 + i);
            AddKey(keys, "LeftShift", 340); AddKey(keys, "LeftControl", 341); AddKey(keys, "LeftAlt", 342);
            AddKey(keys, "RightShift", 344); AddKey(keys, "RightControl", 345); AddKey(keys, "RightAlt", 346);
            for (int i = 0; i <= 9; i++) AddKey(keys, "Keypad" + i.ToString(CultureInfo.InvariantCulture), 320 + i);
            AddKey(keys, "KeypadPeriod", 330); AddKey(keys, "KeypadDivide", 331); AddKey(keys, "KeypadMultiply", 332);
            AddKey(keys, "KeypadMinus", 333); AddKey(keys, "KeypadPlus", 334); AddKey(keys, "KeypadEnter", 335);
            return keys.ToArray();
        }

        private static void AddKey(List<InputKey> keys, string unityName, int glfwKey)
        {
            object parsed;
            try { parsed = Enum.Parse(typeof(KeyCode), unityName, true); }
            catch (ArgumentException) { return; }
            keys.Add(new InputKey((KeyCode)parsed, glfwKey));
        }

        private static KeyCode ParseKey(string unityName)
        {
            try { return (KeyCode)Enum.Parse(typeof(KeyCode), unityName, true); }
            catch (ArgumentException) { return KeyCode.None; }
        }

        private sealed class InputKey
        {
            public readonly KeyCode UnityKey;
            public readonly int GlfwKey;
            public InputKey(KeyCode unityKey, int glfwKey) { UnityKey = unityKey; GlfwKey = glfwKey; }
        }
    }
}
