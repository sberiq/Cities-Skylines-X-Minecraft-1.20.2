using ColossalFramework;
using UnityEngine;

namespace CitiesCraft
{
    internal sealed class CitiesCraftOverlay : MonoBehaviour
    {
        private BridgeClient _bridge;
        private Camera _gameCamera;
        private float _lastCameraSearch;

        public void StartLink()
        {
            _bridge = new BridgeClient();
            _bridge.Start();
        }

        private void Update()
        {
            Camera camera = GetGameCamera();
            if (camera == null || _bridge == null) return;
            Vector3 pos = camera.transform.position;
            Vector3 angles = camera.transform.eulerAngles;
            _bridge.PublishCamera(new CameraState(pos.x, pos.y, pos.z, angles.y, angles.x, camera.fieldOfView));
        }

        private Camera GetGameCamera()
        {
            if (_gameCamera != null && Time.realtimeSinceStartup - _lastCameraSearch < 2f) return _gameCamera;
            CameraController controller = Object.FindObjectOfType<CameraController>();
            if (controller != null)
            {
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

        private void OnGUI()
        {
            string status = _bridge != null && _bridge.Connected ? "Bridge connected" : "Bridge disconnected";
            GUI.Box(new Rect(12, 12, 390, 74), status);
            PlayerState player = _bridge == null ? null : _bridge.LatestPlayer;
            if (player != null)
            {
                GUI.Label(new Rect(24, 40, 370, 24),
                    string.Format("Minecraft player: X {0:F2}  Y {1:F2}  Z {2:F2}", player.X, player.Y, player.Z));
            }
            else
            {
                GUI.Label(new Rect(24, 40, 370, 24), "Waiting for Minecraft player state");
            }
        }

        private void OnDestroy()
        {
            if (_bridge != null) _bridge.Dispose();
            _bridge = null;
        }
    }
}
