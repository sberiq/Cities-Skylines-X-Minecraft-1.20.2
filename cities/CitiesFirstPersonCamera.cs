using UnityEngine;

namespace CitiesCraft
{
    /// <summary>
    /// Applies a mapped Minecraft pose to one Cities gameplay camera immediately
    /// before that camera culls and renders. This changes only the Unity camera
    /// transform and field of view; it does not enable CameraController override
    /// mode or alter Cities camera-controller state.
    /// </summary>
    public sealed class CitiesFirstPersonCamera : MonoBehaviour
    {
        private Camera _camera;
        private bool _poseActive;
        private bool _hasRestoreState;
        private Vector3 _restoreLocalPosition;
        private Quaternion _restoreLocalRotation;
        private float _restoreFieldOfView;
        private float _restoreAspect;
        private Vector3 _mappedPosition;
        private float _unityYaw;
        private float _unityPitch;
        private float _verticalFieldOfView;
        private float _aspect;

        /// <summary>
        /// Gets or adds the follower on the exact GameObject that owns camera.
        /// The returned component is inactive until SetPose is called.
        /// </summary>
        public static CitiesFirstPersonCamera Attach(Camera camera)
        {
            if (camera == null) return null;

            CitiesFirstPersonCamera follower = camera.GetComponent<CitiesFirstPersonCamera>();
            if (follower == null)
                follower = camera.gameObject.AddComponent<CitiesFirstPersonCamera>();

            return follower;
        }

        /// <summary>
        /// Activates or updates the follower for camera. Yaw and pitch use Unity
        /// degrees: yaw is around world Y, and positive pitch looks downward.
        /// The supplied FOV is vertical, like Camera.fieldOfView.
        /// </summary>
        public static CitiesFirstPersonCamera SetPose(
            Camera camera,
            Vector3 mappedPosition,
            float unityYaw,
            float unityPitch,
            float verticalFieldOfView)
        {
            return SetPose(camera, mappedPosition, unityYaw, unityPitch, verticalFieldOfView,
                camera == null ? 1f : camera.aspect);
        }

        public static CitiesFirstPersonCamera SetPose(
            Camera camera,
            Vector3 mappedPosition,
            float unityYaw,
            float unityPitch,
            float verticalFieldOfView,
            float aspect)
        {
            CitiesFirstPersonCamera follower = Attach(camera);
            if (follower == null) return null;

            follower.SetPose(mappedPosition, unityYaw, unityPitch, verticalFieldOfView, aspect);
            return follower;
        }

        /// <summary>
        /// Stops following and restores the camera pose captured when following
        /// began. Cities' own CameraController remains free to update it afterward.
        /// </summary>
        public static void Clear(Camera camera)
        {
            if (camera == null) return;

            CitiesFirstPersonCamera follower = camera.GetComponent<CitiesFirstPersonCamera>();
            if (follower != null) follower.ClearPose();
        }

        /// <summary>
        /// Activates or updates this follower with an already-mapped Unity pose.
        /// </summary>
        public void SetPose(Vector3 mappedPosition, float unityYaw, float unityPitch, float verticalFieldOfView)
        {
            SetPose(mappedPosition, unityYaw, unityPitch, verticalFieldOfView, _camera == null ? 1f : _camera.aspect);
        }

        public void SetPose(Vector3 mappedPosition, float unityYaw, float unityPitch, float verticalFieldOfView, float aspect)
        {
            EnsureCamera();
            if (_camera == null) return;

            if (!IsFinite(mappedPosition.x) || !IsFinite(mappedPosition.y) || !IsFinite(mappedPosition.z)
                || !IsFinite(unityYaw) || !IsFinite(unityPitch)
                || !IsFinite(verticalFieldOfView)
                || !IsFinite(aspect) || aspect < 0.25f || aspect > 5f
                || verticalFieldOfView <= 0f || verticalFieldOfView >= 180f)
            {
                ClearPose();
                throw new System.ArgumentOutOfRangeException("pose", "Camera pose values must be finite and vertical FOV must be between 0 and 180 degrees.");
            }

            if (!_poseActive)
                CaptureRestoreState();

            _mappedPosition = mappedPosition;
            _unityYaw = unityYaw;
            _unityPitch = unityPitch;
            _verticalFieldOfView = verticalFieldOfView;
            _aspect = aspect;
            _poseActive = true;

            if (!enabled) enabled = true;
        }

        /// <summary>
        /// Stops following and restores the saved local transform and FOV.
        /// </summary>
        public void ClearPose()
        {
            _poseActive = false;
            RestoreCamera();
        }

        private void Awake()
        {
            EnsureCamera();
        }

        private void OnPreCull()
        {
            if (!_poseActive) return;

            EnsureCamera();
            if (_camera == null || Camera.current != _camera) return;

            transform.position = _mappedPosition;
            transform.rotation = Quaternion.Euler(_unityPitch, _unityYaw, 0f);
            _camera.fieldOfView = _verticalFieldOfView;
            _camera.aspect = _aspect;
        }

        private void OnDisable()
        {
            ClearPose();
        }

        private void OnDestroy()
        {
            ClearPose();
        }

        private void EnsureCamera()
        {
            if (_camera == null) _camera = GetComponent<Camera>();
        }

        private void CaptureRestoreState()
        {
            if (_camera == null || _hasRestoreState) return;

            _restoreLocalPosition = transform.localPosition;
            _restoreLocalRotation = transform.localRotation;
            _restoreFieldOfView = _camera.fieldOfView;
            _restoreAspect = _camera.aspect;
            _hasRestoreState = true;
        }

        private void RestoreCamera()
        {
            if (!_hasRestoreState) return;

            EnsureCamera();
            if (_camera != null)
            {
                transform.localPosition = _restoreLocalPosition;
                transform.localRotation = _restoreLocalRotation;
                _camera.fieldOfView = _restoreFieldOfView;
                _camera.aspect = _restoreAspect;
            }

            _hasRestoreState = false;
        }

        private static bool IsFinite(float value)
        {
            return !float.IsNaN(value) && !float.IsInfinity(value);
        }
    }
}
