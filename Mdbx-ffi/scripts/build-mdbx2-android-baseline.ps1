[CmdletBinding()]
param(
    [Parameter()]
    [string] $RepoRoot = (Join-Path $PSScriptRoot '..'),

    [Parameter(Mandatory)]
    [string] $BaselineSourceRoot,

    [Parameter()]
    [string] $NdkPath,

    [Parameter()]
    [string] $ExpectedNdkVersion = '28.2.13676358',

    [Parameter()]
    [ValidateRange(21, 35)]
    [int] $Platform = 21,

    [Parameter()]
    [string] $OutputRoot,

    [Parameter()]
    [string] $BuildTargetRoot,

    [Parameter()]
    [string] $ExpectedRustVersion = '1.86.0',

    [Parameter()]
    [string] $ExpectedCargoNdkVersion = '4.1.2'
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
    if ($fullPath -ne $fullRoot -and
        -not $fullPath.StartsWith("$fullRoot\", [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside the repository: $fullPath"
    }
}

function Resolve-NdkPath {
    param(
        [string] $RequestedPath,
        [Parameter(Mandatory)][string] $ExpectedVersion
    )

    if ($RequestedPath) {
        return Resolve-ExistingPath -Path $RequestedPath
    }
    if ($env:ANDROID_NDK_HOME -and
        (Test-Path -LiteralPath $env:ANDROID_NDK_HOME -PathType Container) -and
        [IO.Path]::GetFileName($env:ANDROID_NDK_HOME.TrimEnd('\')) -eq $ExpectedVersion) {
        return Resolve-ExistingPath -Path $env:ANDROID_NDK_HOME
    }
    if ($env:ANDROID_HOME) {
        $expectedPath = Join-Path $env:ANDROID_HOME "ndk\$ExpectedVersion"
        if (Test-Path -LiteralPath $expectedPath -PathType Container) {
            return Resolve-ExistingPath -Path $expectedPath
        }
    }
    throw "Android NDK $ExpectedVersion was not found; pass -NdkPath explicitly"
}

function Resolve-LlvmTool {
    param(
        [Parameter(Mandatory)][string] $NdkRoot,
        [Parameter(Mandatory)][string] $ToolName
    )

    $prebuiltRoot = Join-Path $NdkRoot 'toolchains\llvm\prebuilt'
    $hostRoot = Get-ChildItem -LiteralPath $prebuiltRoot -Directory |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $hostRoot) {
        throw "No LLVM prebuilt toolchain found under $prebuiltRoot"
    }

    foreach ($candidate in @("$ToolName.exe", $ToolName)) {
        $toolPath = Join-Path $hostRoot "bin\$candidate"
        if (Test-Path -LiteralPath $toolPath -PathType Leaf) {
            return Resolve-ExistingPath -Path $toolPath
        }
    }
    throw "$ToolName was not found under $hostRoot\bin"
}

$RepoRoot = Resolve-ExistingPath -Path $RepoRoot
$BaselineSourceRoot = Resolve-ExistingPath -Path $BaselineSourceRoot
$NdkPath = Resolve-NdkPath -RequestedPath $NdkPath -ExpectedVersion $ExpectedNdkVersion
if ([IO.Path]::GetFileName($NdkPath.TrimEnd('\')) -ne $ExpectedNdkVersion) {
    throw "Expected Android NDK $ExpectedNdkVersion, got $NdkPath"
}
$abiVerifierPath = Join-Path $RepoRoot 'scripts\verify-mdbx3-ffi-abi.py'
$artifactReportScript = Join-Path $RepoRoot 'scripts\report-mdbx3-runtime.py'

if (-not $OutputRoot) {
    $OutputRoot = Join-Path $RepoRoot 'target\mdbx2-android-baseline'
}
if (-not $BuildTargetRoot) {
    $BuildTargetRoot = Join-Path ([IO.Path]::GetTempPath()) 'mdbx2-android-baseline-target'
}
Assert-UnderRoot -Path $OutputRoot -Root $RepoRoot
$BuildTargetRoot = [IO.Path]::GetFullPath($BuildTargetRoot)

$abiBaselinePath = Join-Path $RepoRoot 'crates\mdbx-ffi\abi\mdbx2-uniffi-bindings-v1.json'
$abiBaseline = Get-Content -Raw -LiteralPath $abiBaselinePath | ConvertFrom-Json
$expectedCommit = [string]$abiBaseline.source_commit
$sourceCommit = (& git -C $BaselineSourceRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect baseline source repository: $BaselineSourceRoot"
}
if ($sourceCommit -ne $expectedCommit) {
    throw "Baseline source must be commit $expectedCommit, got $sourceCommit"
}
$sourceChanges = @(& git -C $BaselineSourceRoot status --porcelain)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect baseline source status: $BaselineSourceRoot"
}
if ($sourceChanges.Count -gt 0) {
    throw "Baseline source worktree must be clean: $BaselineSourceRoot"
}

$rustcVersion = (& rustc --version).Trim()
$cargoVersion = (& cargo --version).Trim()
$cargoNdkVersion = (& cargo ndk --version).Trim()
if (-not $rustcVersion.StartsWith("rustc $ExpectedRustVersion ", [StringComparison]::Ordinal)) {
    throw "Expected Rust $ExpectedRustVersion, got $rustcVersion"
}
if ($cargoNdkVersion -ne "cargo-ndk $ExpectedCargoNdkVersion") {
    throw "Expected cargo-ndk $ExpectedCargoNdkVersion, got $cargoNdkVersion"
}

$stripTool = Resolve-LlvmTool -NdkRoot $NdkPath -ToolName 'llvm-strip'
$nmTool = Resolve-LlvmTool -NdkRoot $NdkPath -ToolName 'llvm-nm'
$linkerTool = Resolve-LlvmTool -NdkRoot $NdkPath -ToolName 'ld.lld'
$stripVersion = (& $stripTool --version | Select-Object -First 1).Trim()
$nmVersion = (& $nmTool --version | Select-Object -First 1).Trim()
$linkerVersion = (& $linkerTool --version | Select-Object -First 1).Trim()
$jniRoot = Join-Path $OutputRoot 'android-jniLibs'
$reportRoot = Join-Path $OutputRoot 'reports'
$manifestPath = Join-Path $reportRoot 'mdbx2-build-baseline.json'
$reportPath = Join-Path $reportRoot 'mdbx2-android-artifacts.json'
$abiReportPath = Join-Path $reportRoot 'mdbx2-android-ffi-abi.json'
foreach ($path in @($jniRoot, $reportRoot, $manifestPath, $reportPath, $abiReportPath)) {
    Assert-UnderRoot -Path $path -Root $RepoRoot
}
New-Item -ItemType Directory -Force -Path $jniRoot, $reportRoot, $BuildTargetRoot | Out-Null

$previousNdkHome = $env:ANDROID_NDK_HOME
$previousTargetDir = $env:CARGO_TARGET_DIR
Push-Location $BaselineSourceRoot
try {
    $env:ANDROID_NDK_HOME = $NdkPath
    $env:CARGO_TARGET_DIR = $BuildTargetRoot
    & cargo ndk `
        -t arm64-v8a `
        -t armeabi-v7a `
        -t x86_64 `
        -P $Platform `
        -o $jniRoot `
        build -p mdbx-ffi --release
    if ($LASTEXITCODE -ne 0) {
        throw "MDBX2 baseline cargo ndk failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
    $env:ANDROID_NDK_HOME = $previousNdkHome
    $env:CARGO_TARGET_DIR = $previousTargetDir
}

foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86_64')) {
    $libraryPath = Join-Path $jniRoot "$abi\libmdbx_ffi.so"
    if (-not (Test-Path -LiteralPath $libraryPath -PathType Leaf)) {
        throw "Missing MDBX2 baseline library: $libraryPath"
    }
    & $stripTool --strip-all $libraryPath
    if ($LASTEXITCODE -ne 0) {
        throw "llvm-strip failed for ${abi}: exit code $LASTEXITCODE"
    }
}

$abiArgs = @(
    $abiVerifierPath,
    'verify-exports',
    '--baseline', $abiBaselinePath,
    '--nm', $nmTool,
    '--library', "arm64-v8a=$(Join-Path $jniRoot 'arm64-v8a\libmdbx_ffi.so')",
    '--library', "armeabi-v7a=$(Join-Path $jniRoot 'armeabi-v7a\libmdbx_ffi.so')",
    '--library', "x86_64=$(Join-Path $jniRoot 'x86_64\libmdbx_ffi.so')",
    '--output', $abiReportPath
)
& python @abiArgs | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "MDBX2 Android ABI verification failed with exit code $LASTEXITCODE"
}

$manifest = [ordered]@{
    profile = 'mdbx2-size-baseline-input-v1'
    runtime = [ordered]@{
        runtime_name = 'MDBX2'
        source_commit = $sourceCommit
        storage_format = 'MDBX-2'
        ffi_abi_profile = $abiBaseline.profile
        ffi_namespace = $abiBaseline.component_name
        android_shared_object_name = $abiBaseline.source_library_name
    }
    build = [ordered]@{
        rustc = $rustcVersion
        cargo = $cargoVersion
        cargo_ndk = $cargoNdkVersion
        ndk = [IO.Path]::GetFileName($NdkPath)
        android_api = $Platform
        artifact_postprocess = 'llvm-strip --strip-all'
        strip_tool = $stripVersion
        symbol_inspector = $nmVersion
        linker = $linkerVersion
    }
}
$manifestText = ($manifest | ConvertTo-Json -Depth 8) + "`n"
[IO.File]::WriteAllText(
    $manifestPath,
    $manifestText,
    [Text.UTF8Encoding]::new($false)
)

$reportArgs = @(
    $artifactReportScript,
    '--artifact', "arm64-v8a=$(Join-Path $jniRoot 'arm64-v8a\libmdbx_ffi.so')",
    '--artifact', "armeabi-v7a=$(Join-Path $jniRoot 'armeabi-v7a\libmdbx_ffi.so')",
    '--artifact', "x86_64=$(Join-Path $jniRoot 'x86_64\libmdbx_ffi.so')",
    '--manifest', $manifestPath,
    '--abi-report', $abiReportPath,
    '--output', $reportPath,
    '--source-commit', $sourceCommit,
    '--source-tree-state', 'clean',
    '--rustc-version', $rustcVersion,
    '--cargo-version', $cargoVersion,
    '--build-profile', 'release+llvm-strip',
    '--target-platform', "android-api-$Platform-ndk-$([IO.Path]::GetFileName($NdkPath))",
    '--artifact-postprocess', 'llvm-strip --strip-all',
    '--toolchain-detail', "ndk=$([IO.Path]::GetFileName($NdkPath))",
    '--toolchain-detail', "cargo_ndk=$cargoNdkVersion",
    '--toolchain-detail', "linker=$linkerVersion",
    '--toolchain-detail', "strip=$stripVersion",
    '--toolchain-detail', "symbol_inspector=$nmVersion",
    '--input-file', "cargo_lock=$(Join-Path $BaselineSourceRoot 'Cargo.lock')"
)
& python @reportArgs | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "MDBX2 baseline artifact report failed with exit code $LASTEXITCODE"
}

Get-Content -Raw -LiteralPath $reportPath
