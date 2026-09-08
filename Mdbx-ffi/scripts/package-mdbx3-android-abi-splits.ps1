[CmdletBinding()]
param(
    [Parameter()]
    [string] $RepoRoot,

    [Parameter()]
    [string] $SourceRoot,

    [Parameter()]
    [string] $OutputRoot,

    [Parameter()]
    [string] $AbiBaseline
)

$ErrorActionPreference = 'Stop'

$SupportedAbis = @('arm64-v8a', 'armeabi-v7a', 'x86_64')
$LibraryName = 'libmdbx_ffi.so'
$ReportNames = @(
    'mdbx3-build-manifest.json',
    'mdbx3-android-ffi-abi.json',
    'mdbx3-android-artifacts.json',
    'mdbx3-release-gate.json'
)

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
    if ($fullPath -ne $fullRoot -and
        -not $fullPath.StartsWith("$fullRoot\", [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside the repository: $fullPath"
    }
}

function Get-ArtifactRecord {
    param(
        [Parameter(Mandatory)][string] $Abi,
        [Parameter(Mandatory)][string] $ZipPath,
        [Parameter(Mandatory)][string] $LibraryAssetPath
    )

    $zipInfo = Get-Item -LiteralPath $ZipPath
    $libraryInfo = Get-Item -LiteralPath $LibraryAssetPath
    [pscustomobject][ordered]@{
        abi            = $Abi
        package        = $zipInfo.Name
        package_bytes  = $zipInfo.Length
        package_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $ZipPath).Hash.ToLowerInvariant()
        library        = $libraryInfo.Name
        library_bytes  = $libraryInfo.Length
        library_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $LibraryAssetPath).Hash.ToLowerInvariant()
        install_path   = "android-jniLibs/$Abi/$LibraryName"
    }
}

if (-not $RepoRoot) {
    $scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
    $RepoRoot = Join-Path $scriptDirectory '..'
}
$RepoRoot = Resolve-ExistingPath -Path $RepoRoot
if (-not $SourceRoot) {
    $SourceRoot = Join-Path $RepoRoot 'target\mdbx3-android-final'
}
if (-not $OutputRoot) {
    $OutputRoot = Join-Path $RepoRoot 'target\mdbx3-android-abi-splits'
}
if (-not $AbiBaseline) {
    $AbiBaseline = Join-Path $RepoRoot 'crates\mdbx-ffi\abi\mdbx2-uniffi-bindings-v1.json'
}

$SourceRoot = Resolve-ExistingPath -Path $SourceRoot
$AbiBaseline = Resolve-ExistingPath -Path $AbiBaseline
Assert-UnderRoot -Path $SourceRoot -Root $RepoRoot
Assert-UnderRoot -Path $AbiBaseline -Root $RepoRoot
Assert-UnderRoot -Path $OutputRoot -Root $RepoRoot

$verifyGate = Join-Path $RepoRoot 'scripts\verify-mdbx3-release.py'
& python $verifyGate --root $SourceRoot --abi-baseline $AbiBaseline | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'The source root did not pass the MDBX3 full release gate'
}

$sourceReports = Join-Path $SourceRoot 'reports'
foreach ($reportName in $ReportNames) {
    $reportPath = Join-Path $sourceReports $reportName
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
        throw "Missing MDBX3 report: $reportPath"
    }
}

$sourceLibraries = @{}
foreach ($abi in $SupportedAbis) {
    $libraryPath = Join-Path $SourceRoot "android-jniLibs\$abi\$LibraryName"
    if (-not (Test-Path -LiteralPath $libraryPath -PathType Leaf)) {
        throw "Missing MDBX3 ABI library: $libraryPath"
    }
    $libraryInfo = Get-Item -LiteralPath $libraryPath
    if ($libraryInfo.Length -le 0) {
        throw "Empty MDBX3 ABI library: $libraryPath"
    }
    $sourceLibraries[$abi] = $libraryPath
}

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$targetRoot = Resolve-ExistingPath -Path (Join-Path $RepoRoot 'target')
$stagingRoot = Join-Path $targetRoot '.mdbx3-android-abi-split-staging'
Assert-UnderRoot -Path $stagingRoot -Root $RepoRoot

$generatedFiles = @('mdbx3-android-abi-index.json', 'mdbx3-android-jniLibs-universal.zip')
foreach ($abi in $SupportedAbis) {
    $generatedFiles += @(
        "mdbx3-android-$abi.zip",
        "libmdbx_ffi_$abi.so"
    )
}
foreach ($name in $generatedFiles) {
    $path = Join-Path $OutputRoot $name
    Assert-UnderRoot -Path $path -Root $RepoRoot
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        Remove-Item -LiteralPath $path -Force
    }
}

if (Test-Path -LiteralPath $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $stagingRoot | Out-Null

$thinArtifacts = @()
try {
    foreach ($abi in $SupportedAbis) {
        $stage = Join-Path $stagingRoot $abi
        $stageLibraryDirectory = Join-Path $stage "android-jniLibs\$abi"
        $stageReportDirectory = Join-Path $stage 'reports'
        New-Item -ItemType Directory -Force -Path $stageLibraryDirectory, $stageReportDirectory | Out-Null
        Copy-Item -LiteralPath $sourceLibraries[$abi] -Destination (Join-Path $stageLibraryDirectory $LibraryName)
        foreach ($reportName in $ReportNames) {
            Copy-Item -LiteralPath (Join-Path $sourceReports $reportName) -Destination (Join-Path $stageReportDirectory $reportName)
        }

        $zipPath = Join-Path $OutputRoot "mdbx3-android-$abi.zip"
        Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zipPath -CompressionLevel Optimal -Force
        $libraryAssetPath = Join-Path $OutputRoot "libmdbx_ffi_$abi.so"
        Copy-Item -LiteralPath $sourceLibraries[$abi] -Destination $libraryAssetPath
        $thinArtifacts += Get-ArtifactRecord -Abi $abi -ZipPath $zipPath -LibraryAssetPath $libraryAssetPath
    }

    $universalStage = Join-Path $stagingRoot 'universal'
    $universalLibraryRoot = Join-Path $universalStage 'android-jniLibs'
    $universalReportRoot = Join-Path $universalStage 'reports'
    New-Item -ItemType Directory -Force -Path $universalLibraryRoot, $universalReportRoot | Out-Null
    foreach ($abi in $SupportedAbis) {
        $destinationDirectory = Join-Path $universalLibraryRoot $abi
        New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null
        Copy-Item -LiteralPath $sourceLibraries[$abi] -Destination (Join-Path $destinationDirectory $LibraryName)
    }
    foreach ($reportName in $ReportNames) {
        Copy-Item -LiteralPath (Join-Path $sourceReports $reportName) -Destination (Join-Path $universalReportRoot $reportName)
    }
    $universalZipPath = Join-Path $OutputRoot 'mdbx3-android-jniLibs-universal.zip'
    Compress-Archive -Path (Join-Path $universalStage '*') -DestinationPath $universalZipPath -CompressionLevel Optimal -Force

    $artifactReport = Get-Content -LiteralPath (Join-Path $sourceReports 'mdbx3-android-artifacts.json') -Raw | ConvertFrom-Json
    $manifest = Get-Content -LiteralPath (Join-Path $sourceReports 'mdbx3-build-manifest.json') -Raw | ConvertFrom-Json
    $index = [ordered]@{
        profile             = 'mdbx3-android-abi-split-v1'
        runtime             = 'MDBX3'
        storage_format      = 'MDBX-2'
        library_name        = $LibraryName
        selection           = 'install exactly one ABI package per Android process'
        universal_package   = [ordered]@{
            asset  = 'mdbx3-android-jniLibs-universal.zip'
            bytes  = (Get-Item -LiteralPath $universalZipPath).Length
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $universalZipPath).Hash.ToLowerInvariant()
            use    = 'compatibility and local testing; it contains all ABIs and is not the size-optimized choice'
        }
        abis                = @($thinArtifacts)
        source              = [ordered]@{
            commit      = $artifactReport.source_commit
            tree_state  = $artifactReport.source_tree_state
            manifest_sha256 = $artifactReport.manifest_sha256
            runtime_version = $manifest.runtime.runtime_version
            abi_baseline = 'mdbx2-uniffi-bindings-v1'
        }
        constraints         = @(
            'Android ELF shared objects are architecture-specific; no single native ELF is cross-ABI executable.',
            'All packages keep the canonical android-jniLibs/<abi>/libmdbx_ffi.so path and the MDBX2-compatible ABI.',
            'The universal package must not be used as evidence of a smaller installed footprint.'
        )
    }
    $indexPath = Join-Path $OutputRoot 'mdbx3-android-abi-index.json'
    $indexJson = $index | ConvertTo-Json -Depth 10
    [IO.File]::WriteAllText($indexPath, "$indexJson`n", [Text.UTF8Encoding]::new($false))
}
finally {
    if (Test-Path -LiteralPath $stagingRoot) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force
    }
}

$verifySplits = Join-Path $RepoRoot 'scripts\verify-mdbx3-android-abi-splits.py'
& python $verifySplits --root $OutputRoot
if ($LASTEXITCODE -ne 0) {
    throw 'MDBX3 ABI split package verification failed'
}

Write-Output 'MDBX3 Android ABI split assets:'
foreach ($artifact in $thinArtifacts) {
    Write-Output ("  {0}  {1} bytes  sha256:{2}" -f $artifact.package, $artifact.package_bytes, $artifact.package_sha256)
}
$universalPath = Join-Path $OutputRoot 'mdbx3-android-jniLibs-universal.zip'
$universalInfo = Get-Item -LiteralPath $universalPath
$universalHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $universalPath).Hash
Write-Output ("  {0}  {1} bytes  sha256:{2}" -f $universalInfo.Name, $universalInfo.Length, $universalHash)
