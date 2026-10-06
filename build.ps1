param(
    [switch]$Cities
)

$ErrorActionPreference = 'Stop'

& "$PSScriptRoot\gradlew.bat" build
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

if ($Cities) {
    if (-not $env:CS1_INSTALL -and -not $env:CITIES_SKYLINES_MANAGED) {
        throw 'Set CS1_INSTALL or CITIES_SKYLINES_MANAGED to your Cities: Skylines 1 managed assemblies directory.'
    }
    & msbuild "$PSScriptRoot\cities\CitiesCraft.csproj" /p:Configuration=Release
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
