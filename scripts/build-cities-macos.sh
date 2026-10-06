#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_root=$(CDPATH= cd -- "$script_dir/.." && pwd)

if [ -n "${CITIES_SKYLINES_MANAGED:-}" ]; then
    managed_dir=$CITIES_SKYLINES_MANAGED
elif [ -d "$HOME/Library/Application Support/Steam/steamapps/common/Cities_Skylines/Cities.app/Contents/Resources/Data/Managed" ]; then
    managed_dir="$HOME/Library/Application Support/Steam/steamapps/common/Cities_Skylines/Cities.app/Contents/Resources/Data/Managed"
else
    managed_dir="/Applications/Cities.app/Contents/Resources/Data/Managed"
fi

if [ ! -f "$managed_dir/ICities.dll" ]; then
    echo "Cities managed assemblies not found at: $managed_dir" >&2
    echo "Set CITIES_SKYLINES_MANAGED to the folder containing ICities.dll." >&2
    exit 2
fi

dotnet_command=${DOTNET:-dotnet}
sdk_line=$("$dotnet_command" --list-sdks | tail -n 1)
if [ -z "$sdk_line" ]; then
    echo ".NET SDK not found. Install a .NET SDK for macOS, then retry." >&2
    exit 2
fi
sdk_base=$(printf '%s\n' "$sdk_line" | sed -E 's/^.*\[([^]]+)\]$/\1/')
compiler=$(find "$sdk_base" -path '*/Roslyn/bincore/csc.dll' -print | sort | tail -n 1)
if [ -z "$compiler" ]; then
    echo "Roslyn compiler not found under SDK: $sdk_base" >&2
    exit 2
fi

output_dir="$project_root/cities/bin/Release"
mkdir -p "$output_dir"
set -- -noconfig -nostdlib+ -langversion:3 -target:library -optimize+ -out:"$output_dir/CitiesCraft.dll"
for reference in mscorlib.dll System.dll System.Core.dll ICities.dll Assembly-CSharp.dll ColossalManaged.dll UnityEngine.dll UnityEngine.UI.dll; do
    if [ -f "$managed_dir/$reference" ]; then
        set -- "$@" "-reference:$managed_dir/$reference"
    fi
done
"$dotnet_command" "$compiler" "$@" "$project_root/cities/CitiesCraftMod.cs" "$project_root/cities/CitiesCraftLoadingExtension.cs" "$project_root/cities/CitiesFirstPersonCamera.cs" "$project_root/cities/CitiesNativeCompositor.cs" "$project_root/cities/BridgeClient.cs" "$project_root/cities/CitiesWorldSnapshotBuilder.cs" "$project_root/cities/CitiesFrameReceiver.cs" "$project_root/cities/CitiesCraftOverlay.cs"
echo "Built: $output_dir/CitiesCraft.dll"
