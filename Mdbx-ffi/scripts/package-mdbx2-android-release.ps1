[CmdletBinding()]
param(
    [Parameter()]
    [string] $RepoRoot = (Join-Path $PSScriptRoot '..'),

    [Parameter()]
    [string] $SourceRoot,

    [Parameter()]
    [string] $OutputRoot,

    [Parameter()]
    [string] $ReleaseNotes
)

$ErrorActionPreference = 'Stop'

function Resolve-ExistingPath {
    param([Parameter(Mandatory)][string] $Path)

    return (Resolve-Path -LiteralPath $Path).Path
}

function Assert-UnderRoot {
    param(
        [Parameter(Mandatory)][string] $Path,
        [Parameter(Mandatory)][string] $Root
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullRoot = ([IO.Path]::GetFullPath($Root)).TrimEnd('\')
    $rootPrefix = "$fullRoot\"
    if ($fullPath -ne $fullRoot -and -not $fullPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside the repository: $fullPath"
    }
}

$RepoRoot = Resolve-ExistingPath -Path $RepoRoot
if (-not $SourceRoot) {
    $SourceRoot = Join-Path $RepoRoot 'target\android-jniLibs'
}
if (-not $OutputRoot) {
    $OutputRoot = Join-Path $RepoRoot 'target\release-assets'
}
if (-not $ReleaseNotes) {
    $ReleaseNotes = Join-Path $RepoRoot 'target\release-notes-mdbx2.md'
}

$SourceRoot = Resolve-ExistingPath -Path $SourceRoot
Assert-UnderRoot -Path $SourceRoot -Root $RepoRoot
Assert-UnderRoot -Path $OutputRoot -Root $RepoRoot
Assert-UnderRoot -Path $ReleaseNotes -Root $RepoRoot

$artifacts = @(
    [pscustomobject]@{
        Abi       = 'arm64-v8a'
        Source    = (Join-Path $SourceRoot 'arm64-v8a\libmdbx_ffi.so')
        AssetName = 'libmdbx_ffi_arm64-v8a.so'
    },
    [pscustomobject]@{
        Abi       = 'armeabi-v7a'
        Source    = (Join-Path $SourceRoot 'armeabi-v7a\libmdbx_ffi.so')
        AssetName = 'libmdbx_ffi_armeabi-v7a.so'
    },
    [pscustomobject]@{
        # The Android ABI is x86_64; the public release asset keeps the
        # established x86_x64 spelling for compatibility with existing links.
        Abi       = 'x86_64'
        Source    = (Join-Path $SourceRoot 'x86_64\libmdbx_ffi.so')
        AssetName = 'libmdbx_ffi_x86_x64.so'
    }
)

# Validate every input before touching any output so a partial package is never published.
foreach ($artifact in $artifacts) {
    if (-not (Test-Path -LiteralPath $artifact.Source -PathType Leaf)) {
        throw "Missing canonical ABI library: $($artifact.Source)"
    }
    $sourceInfo = Get-Item -LiteralPath $artifact.Source
    if ($sourceInfo.Length -le 0) {
        throw "Empty ABI library: $($artifact.Source)"
    }
}
if (-not (Test-Path -LiteralPath $ReleaseNotes -PathType Leaf)) {
    throw "Missing release notes: $ReleaseNotes"
}

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

# Remove the old hyphenated aliases from the staging directory. The canonical
# files under target/android-jniLibs remain untouched for Gradle/Android use.
$legacyNames = @(
    'libmdbx_ffi-arm64-v8a.so',
    'libmdbx_ffi-armeabi-v7a.so',
    'libmdbx_ffi-x86_64.so',
    'mdbx2-android-jniLibs.zip'
)
foreach ($legacyName in $legacyNames) {
    $legacyPath = Join-Path $OutputRoot $legacyName
    Assert-UnderRoot -Path $legacyPath -Root $RepoRoot
    if (Test-Path -LiteralPath $legacyPath -PathType Leaf) {
        Remove-Item -LiteralPath $legacyPath -Force
    }
}

foreach ($artifact in $artifacts) {
    $destination = Join-Path $OutputRoot $artifact.AssetName
    Assert-UnderRoot -Path $destination -Root $RepoRoot
    Copy-Item -LiteralPath $artifact.Source -Destination $destination -Force
}

$targetRoot = Resolve-ExistingPath -Path (Join-Path $RepoRoot 'target')
$stagingRoot = Join-Path $targetRoot '.mdbx2-android-release-staging'
$zipPath = Join-Path $OutputRoot 'mdbx2-android-release.zip'
$rootZipPath = Join-Path $targetRoot 'mdbx2-android-release.zip'
foreach ($path in @($stagingRoot, $zipPath, $rootZipPath)) {
    Assert-UnderRoot -Path $path -Root $RepoRoot
}

if (Test-Path -LiteralPath $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $stagingRoot | Out-Null

try {
    foreach ($artifact in $artifacts) {
        $abiDirectory = Join-Path $stagingRoot "android-jniLibs\$($artifact.Abi)"
        New-Item -ItemType Directory -Force -Path $abiDirectory | Out-Null
        Copy-Item -LiteralPath $artifact.Source -Destination (Join-Path $abiDirectory 'libmdbx_ffi.so') -Force
    }
    Copy-Item -LiteralPath $ReleaseNotes -Destination (Join-Path $stagingRoot 'release-notes-mdbx2.md') -Force

    Compress-Archive -Path (Join-Path $stagingRoot '*') -DestinationPath $zipPath -CompressionLevel Optimal -Force
    Copy-Item -LiteralPath $zipPath -Destination $rootZipPath -Force
}
finally {
    if (Test-Path -LiteralPath $stagingRoot) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force
    }
}

Write-Output 'MDBX2 Android release assets (no compilation performed):'
foreach ($artifact in $artifacts) {
    $path = Join-Path $OutputRoot $artifact.AssetName
    $info = Get-Item -LiteralPath $path
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash
    Write-Output ("  {0}  {1} bytes  sha256:{2}" -f $info.Name, $info.Length, $hash)
}
$zipInfo = Get-Item -LiteralPath $zipPath
$zipHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipPath).Hash
Write-Output ("  {0}  {1} bytes  sha256:{2}" -f $zipInfo.Name, $zipInfo.Length, $zipHash)
