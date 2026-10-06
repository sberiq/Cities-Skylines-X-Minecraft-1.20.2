#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
package_dir="$project_root/dist/citiescraft-dev-kit"
archive_path="$project_root/dist/citiescraft-dev-kit.zip"

if [ ! -f "$project_root/minecraft/build/libs/citiescraft-minecraft-0.1.0.jar" ]; then
    echo "Minecraft mod not built. Run ./gradlew :minecraft:build first." >&2
    exit 2
fi
if [ ! -f "$project_root/cities/bin/Release/CitiesCraft.dll" ]; then
    echo "Cities mod not built. Run ./scripts/build-cities-macos.sh first." >&2
    exit 2
fi
if [ ! -f "$project_root/bridge/build/distributions/bridge.zip" ]; then
    echo "Bridge distribution not built. Run ./gradlew :bridge:distZip first." >&2
    exit 2
fi

rm -rf "$package_dir" "$archive_path"
mkdir -p "$package_dir/bridge" "$package_dir/cities" "$package_dir/minecraft" "$package_dir/docs"
cp "$project_root/README.md" "$project_root/THIRD_PARTY_NOTICES.md" "$package_dir/"
cp "$project_root/minecraft/build/libs/citiescraft-minecraft-0.1.0.jar" "$package_dir/minecraft/"
cp "$project_root/cities/bin/Release/CitiesCraft.dll" "$package_dir/cities/"
cp "$project_root/bridge/build/distributions/bridge.zip" "$package_dir/bridge/"
cp "$project_root"/docs/*.md "$package_dir/docs/"

(
    cd "$package_dir"
    zip -X -qr "$archive_path" README.md THIRD_PARTY_NOTICES.md docs bridge cities minecraft
)

echo "Packaged: $archive_path"
