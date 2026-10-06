using ColossalFramework;
using UnityEngine;
using System;
using System.Globalization;
using System.Threading;
using Object = UnityEngine.Object;

namespace CitiesCraft
{
    internal sealed class CitiesCraftOverlay : MonoBehaviour
    {
        private BridgeClient _bridge;
        private CitiesFrameReceiver _frameReceiver;
        private Texture2D _minecraftFrame;
        private long _frameSequence = -1;
        private Camera _gameCamera;
        private CameraController _cameraController;
        private float _lastCameraSearch;
        private float _lastSnapshotRequest;
        private int _snapshotPending;
        private long _snapshotSequence;
        private volatile bool _stoppingSnapshots;
        private Vector3 _calibrationAnchor;
        private float _calibrationGroundHeight;
        private float _lastAnchorSample;

        public void StartLink()
        {
            _bridge = new BridgeClient();
            _bridge.Start();

            _frameReceiver = new CitiesFrameReceiver();
            _frameReceiver.Start();
        }

        private void Update()
        {
            UpdateMinecraftFrame();

            Camera camera = GetGameCamera();
            if (camera != null && _bridge != null)
            {
                Vector3 pos = camera.transform.position;
                Vector3 angles = camera.transform.eulerAngles;
                _bridge.PublishCamera(new CameraState(pos.x, pos.y, pos.z, angles.y, angles.x, camera.fieldOfView));
            }
            UpdateCalibrationAnchor();
            RequestWorldSnapshot();
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
            if (_gameCamera != null && Time.realtimeSinceStartup - _lastCameraSearch < 2f) return _gameCamera;
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
            return _gameCamera;
        }

        private void UpdateMinecraftFrame()
        {
            if (_frameReceiver == null) return;
            CitiesFrameReceiver.Frame frame = _frameReceiver.TakeLatestFrame();
            if (frame == null) return;

            if (_minecraftFrame == null || _minecraftFrame.width != frame.Width || _minecraftFrame.height != frame.Height)
            {
                if (_minecraftFrame != null) Object.Destroy(_minecraftFrame);
                _minecraftFrame = new Texture2D(frame.Width, frame.Height, TextureFormat.RGBA32, false);
                _minecraftFrame.name = "CitiesCraft Minecraft frame";
            }

            _minecraftFrame.LoadRawTextureData(frame.Pixels);
            _minecraftFrame.Apply(false, false);
            _frameSequence = frame.Sequence;
        }

        private void OnGUI()
        {
            string status = _bridge != null && _bridge.Connected ? "Bridge connected" : "Bridge disconnected";
            GUI.Box(new Rect(12, 12, 460, 116), status);
            PlayerState player = _bridge == null ? null : _bridge.LatestPlayer;
            if (player != null)
            {
                GUI.Label(new Rect(24, 38, 438, 22),
                    string.Format("Mapped player (Cities): X {0:F2}  Y {1:F2}  Z {2:F2}", player.X, player.Y, player.Z));
            }
            else
            {
                GUI.Label(new Rect(24, 38, 438, 22), "Waiting for Minecraft player state");
            }
            GUI.Label(new Rect(24, 60, 438, 22), string.Format(CultureInfo.InvariantCulture,
                "Cities anchor: X {0:F2}  ground Y {1:F2}  Z {2:F2}",
                _calibrationAnchor.x, _calibrationGroundHeight, _calibrationAnchor.z));
            GUI.Label(new Rect(24, 82, 438, 22), string.Format(CultureInfo.InvariantCulture,
                "Collision radius: {0:F0} m  | terrain grid: 8 m", _bridge == null ? 96f : _bridge.CollisionRadius));

            DrawMinecraftPictureInPicture();
        }

        private void DrawMinecraftPictureInPicture()
        {
            float maxWidth = Mathf.Min(480f, Screen.width * 0.42f);
            float maxHeight = Mathf.Min(320f, Screen.height * 0.42f);
            float aspect = _minecraftFrame == null
                ? 16f / 9f
                : (float)_minecraftFrame.width / _minecraftFrame.height;
            float imageWidth = maxWidth;
            float imageHeight = imageWidth / aspect;
            if (imageHeight > maxHeight)
            {
                imageHeight = maxHeight;
                imageWidth = imageHeight * aspect;
            }

            const float border = 2f;
            const float titleHeight = 22f;
            float outerWidth = imageWidth + border * 2f;
            float outerHeight = imageHeight + titleHeight + border * 2f;
            Rect outer = new Rect(Screen.width - outerWidth - 16f, Screen.height - outerHeight - 16f,
                outerWidth, outerHeight);
            GUI.Box(outer, GUIContent.none);

            bool connected = _frameReceiver != null && _frameReceiver.Connected;
            string relayStatus;
            if (_minecraftFrame == null)
                relayStatus = connected ? "Waiting for Minecraft frame" : "Frame relay disconnected";
            else
                relayStatus = connected
                    ? "Minecraft frame #" + _frameSequence
                    : "Last Minecraft frame #" + _frameSequence + " (relay disconnected)";
            GUI.Label(new Rect(outer.x + border, outer.y + border, imageWidth, titleHeight), relayStatus);
            Rect imageRect = new Rect(outer.x + border, outer.y + border + titleHeight, imageWidth, imageHeight);
            if (_minecraftFrame != null) GUI.DrawTexture(imageRect, _minecraftFrame, ScaleMode.StretchToFill, false);
            else GUI.Box(imageRect, "Waiting for frame data");
        }

        private void OnDestroy()
        {
            _stoppingSnapshots = true;
            if (_bridge != null) _bridge.Dispose();
            _bridge = null;
            if (_frameReceiver != null) _frameReceiver.Dispose();
            _frameReceiver = null;
            if (_minecraftFrame != null) Object.Destroy(_minecraftFrame);
            _minecraftFrame = null;
        }
    }
}
