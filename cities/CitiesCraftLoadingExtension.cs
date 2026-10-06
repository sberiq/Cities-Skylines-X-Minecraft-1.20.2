using ICities;
using UnityEngine;

namespace CitiesCraft
{
    public sealed class CitiesCraftLoadingExtension : LoadingExtensionBase
    {
        private GameObject _overlayObject;

        public override void OnLevelLoaded(LoadMode mode)
        {
            base.OnLevelLoaded(mode);
            if (mode != LoadMode.LoadGame && mode != LoadMode.NewGame && mode != LoadMode.NewGameFromScenario) return;

            _overlayObject = new GameObject("CitiesCraftTelemetryOverlay");
            CitiesCraftOverlay overlay = _overlayObject.AddComponent<CitiesCraftOverlay>();
            overlay.StartLink();
        }

        public override void OnLevelUnloading()
        {
            if (_overlayObject != null) Object.Destroy(_overlayObject);
            _overlayObject = null;
            base.OnLevelUnloading();
        }
    }
}
