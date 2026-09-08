[CmdletBinding()]
param(
    [Parameter()]
    [string] $RepoRoot,

    [Parameter()]
    [string] $LibraryRoot,

    [Parameter()]
    [string] $DeviceSerial,

    [Parameter()]
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86_64')]
    [string] $Abi = 'x86_64',

    [Parameter()]
    [switch] $InstallAndRun,

    [Parameter()]
    [switch] $Offline
)

$ErrorActionPreference = 'Stop'
if (-not $RepoRoot) {
    $RepoRoot = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..'
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
if (-not $LibraryRoot) {
    $LibraryRoot = Join-Path $RepoRoot 'target\mdbx3-android-final'
}
$LibraryRoot = (Resolve-Path -LiteralPath $LibraryRoot).Path
$projectRoot = Join-Path $RepoRoot 'mdbx3-runtime-design\android-smoke'
$gradle = if ($env:GRADLE_HOME) {
    Join-Path $env:GRADLE_HOME 'bin\gradle.bat'
} else {
    'gradle.bat'
}

Push-Location $projectRoot
try {
    $gradleArguments = @("-PmdbxAbi=$Abi", "-PmdbxLibraryRoot=$LibraryRoot", 'assembleDebug')
    if ($Offline) {
        $gradleArguments += '--offline'
    }
    & $gradle @gradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Android loader smoke build failed with exit code $LASTEXITCODE"
    }
    $apk = Join-Path $projectRoot "app\build\outputs\apk\$Abi\debug\app-$Abi-debug.apk"
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
        $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    }
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
        throw "Android loader smoke APK was not produced"
    }
    Write-Output "APK: $apk"
    if ($InstallAndRun) {
        $adbPrefix = @()
        if ($DeviceSerial) {
            $adbPrefix = @('-s', $DeviceSerial)
        }
        & adb @adbPrefix install -r $apk
        if ($LASTEXITCODE -ne 0) {
            throw "adb install failed with exit code $LASTEXITCODE"
        }
        & adb @adbPrefix shell am force-stop com.monica.mdbx3.smoke
        & adb @adbPrefix shell monkey -p com.monica.mdbx3.smoke 1
        if ($LASTEXITCODE -ne 0) {
            throw "adb launch failed with exit code $LASTEXITCODE"
        }
        & adb @adbPrefix logcat -d -s AndroidRuntime:I mdbx3_smoke:I '*:S'
    }
}
finally {
    Pop-Location
}
