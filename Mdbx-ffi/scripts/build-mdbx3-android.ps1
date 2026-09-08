[CmdletBinding()]
param(
    [Parameter()]
    [string] $RepoRoot,

    [Parameter()]
    [string] $NdkPath,

    [Parameter()]
    [string] $ExpectedNdkVersion = '28.2.13676358',

    [Parameter()]
    [string] $ExpectedRustVersion = '1.86.0',

    [Parameter()]
    [string] $ExpectedCargoNdkVersion = '4.1.2',

    [Parameter()]
    [ValidateRange(21, 35)]
    [int] $Platform = 21,

    [Parameter()]
    [string] $OutputRoot,

    [Parameter()]
    [string] $BaselineReport,

    [Parameter()]
    [string] $AbiSplitOutputRoot,

    [Parameter()]
    [switch] $SkipAbiSplitPackaging
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

if (-not $RepoRoot) {
    $scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
    $RepoRoot = Join-Path $scriptDirectory '..'
}
$RepoRoot = Resolve-ExistingPath -Path $RepoRoot
$NdkPath = Resolve-NdkPath -RequestedPath $NdkPath -ExpectedVersion $ExpectedNdkVersion
if ([IO.Path]::GetFileName($NdkPath.TrimEnd('\')) -ne $ExpectedNdkVersion) {
    throw "Expected Android NDK $ExpectedNdkVersion, got $NdkPath"
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
$readelfTool = Resolve-LlvmTool -NdkRoot $NdkPath -ToolName 'llvm-readelf'
$linkerTool = Resolve-LlvmTool -NdkRoot $NdkPath -ToolName 'ld.lld'
$abiVerifierPath = Join-Path $RepoRoot 'scripts\verify-mdbx3-ffi-abi.py'
$artifactReportScript = Join-Path $RepoRoot 'scripts\report-mdbx3-runtime.py'
$releaseGateScript = Join-Path $RepoRoot 'scripts\verify-mdbx3-release.py'
$abiSplitPackageScript = Join-Path $RepoRoot 'scripts\package-mdbx3-android-abi-splits.ps1'

if (-not $OutputRoot) {
    $OutputRoot = Join-Path $RepoRoot 'target\mdbx3-android'
}
if (-not $AbiSplitOutputRoot) {
    $AbiSplitOutputRoot = Join-Path $RepoRoot 'target\mdbx3-android-abi-splits'
}
Assert-UnderRoot -Path $OutputRoot -Root $RepoRoot
Assert-UnderRoot -Path $AbiSplitOutputRoot -Root $RepoRoot

$jniRoot = Join-Path $OutputRoot 'android-jniLibs'
$reportRoot = Join-Path $OutputRoot 'reports'
$manifestPath = Join-Path $reportRoot 'mdbx3-build-manifest.json'
$reportPath = Join-Path $reportRoot 'mdbx3-android-artifacts.json'
$abiReportPath = Join-Path $reportRoot 'mdbx3-android-ffi-abi.json'
$gateReportPath = Join-Path $reportRoot 'mdbx3-release-gate.json'
foreach ($path in @($jniRoot, $reportRoot, $manifestPath, $reportPath, $abiReportPath, $gateReportPath)) {
    Assert-UnderRoot -Path $path -Root $RepoRoot
}
New-Item -ItemType Directory -Force -Path $jniRoot, $reportRoot | Out-Null

$previousNdkHome = $env:ANDROID_NDK_HOME
$previousRustFlags = $env:RUSTFLAGS
Push-Location $RepoRoot
try {
    $env:ANDROID_NDK_HOME = $NdkPath
    $buildIdFlag = '-C link-arg=-Wl,--build-id=sha1'
    $env:RUSTFLAGS = if ([string]::IsNullOrWhiteSpace($previousRustFlags)) {
        $buildIdFlag
    } else {
        "$previousRustFlags $buildIdFlag"
    }
    & cargo ndk `
        -t arm64-v8a `
        -t armeabi-v7a `
        -t x86_64 `
        -P $Platform `
        -o $jniRoot `
        build -p mdbx-ffi --profile mdbx3-release
    if ($LASTEXITCODE -ne 0) {
        throw "cargo ndk failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
    $env:ANDROID_NDK_HOME = $previousNdkHome
    $env:RUSTFLAGS = $previousRustFlags
}

foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86_64')) {
    $libraryPath = Join-Path $jniRoot "$abi\libmdbx_ffi.so"
    if (-not (Test-Path -LiteralPath $libraryPath -PathType Leaf)) {
        throw "Missing MDBX3 Android library: $libraryPath"
    }
    # Keep the GNU build-id note while removing symbols/debug sections.  The
    # release gate uses the build-id to tie a packaged library back to its
    # reproducible build; plain --strip-all removes that note on LLVM 19.
    & $stripTool --strip-all --keep-section=.note.gnu.build-id $libraryPath
    if ($LASTEXITCODE -ne 0) {
        throw "llvm-strip failed for ${abi}: exit code $LASTEXITCODE"
    }
}

$abiBaselinePath = Join-Path $RepoRoot 'crates\mdbx-ffi\abi\mdbx2-uniffi-bindings-v1.json'
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
    throw "MDBX3 Android ABI verification failed with exit code $LASTEXITCODE"
}

$manifestExitCode = 0
Push-Location $RepoRoot
try {
    $manifestJson = & cargo run -p mdbx-ffi --example mdbx3_build_manifest --profile mdbx3-release --quiet
    $manifestExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}
if ($manifestExitCode -ne 0) {
    throw "MDBX3 manifest generation failed with exit code $manifestExitCode"
}
$manifestText = ($manifestJson -join "`n") + "`n"
[IO.File]::WriteAllText(
    $manifestPath,
    $manifestText,
    [Text.UTF8Encoding]::new($false)
)

$sourceCommit = (& git -C $RepoRoot rev-parse HEAD).Trim()
$sourceChanges = @(& git -C $RepoRoot status --porcelain --untracked-files=no)
$sourceTreeState = if ($sourceChanges.Count -eq 0) { 'clean' } else { 'dirty' }
$stripVersion = (& $stripTool --version | Select-Object -First 1).Trim()
$nmVersion = (& $nmTool --version | Select-Object -First 1).Trim()
$readelfVersion = (& $readelfTool --version | Select-Object -First 1).Trim()
$linkerVersion = (& $linkerTool --version | Select-Object -First 1).Trim()
$reportArgs = @(
    $artifactReportScript,
    '--artifact', "arm64-v8a=$(Join-Path $jniRoot 'arm64-v8a\libmdbx_ffi.so')",
    '--artifact', "armeabi-v7a=$(Join-Path $jniRoot 'armeabi-v7a\libmdbx_ffi.so')",
    '--artifact', "x86_64=$(Join-Path $jniRoot 'x86_64\libmdbx_ffi.so')",
    '--manifest', $manifestPath,
    '--abi-report', $abiReportPath,
    '--output', $reportPath,
    '--source-commit', $sourceCommit,
    '--source-tree-state', $sourceTreeState,
    '--rustc-version', $rustcVersion,
    '--cargo-version', $cargoVersion,
    '--build-profile', 'mdbx3-release',
    '--target-platform', "android-api-$Platform-ndk-$([IO.Path]::GetFileName($NdkPath))",
    '--artifact-postprocess', 'llvm-strip --strip-all',
    '--toolchain-detail', "ndk=$([IO.Path]::GetFileName($NdkPath))",
    '--toolchain-detail', "cargo_ndk=$cargoNdkVersion",
    '--toolchain-detail', "linker=$linkerVersion",
    '--toolchain-detail', "strip=$stripVersion",
    '--toolchain-detail', "symbol_inspector=$nmVersion",
    '--toolchain-detail', "readelf=$readelfVersion",
    '--input-file', "cargo_lock=$(Join-Path $RepoRoot 'Cargo.lock')"
)
if ($BaselineReport) {
    $reportArgs += @('--baseline', (Resolve-ExistingPath -Path $BaselineReport))
} else {
    $defaultBaselineReport = Join-Path $RepoRoot 'target\mdbx2-android-baseline\reports\mdbx2-android-artifacts.json'
    if (-not (Test-Path -LiteralPath $defaultBaselineReport -PathType Leaf)) {
        throw "MDBX2 baseline report is required for the MDBX3 release gate; pass -BaselineReport explicitly"
    }
    $reportArgs += @('--baseline', (Resolve-ExistingPath -Path $defaultBaselineReport))
}
& python @reportArgs | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "MDBX3 artifact report failed with exit code $LASTEXITCODE"
}

& python $releaseGateScript `
    --root $OutputRoot `
    --abi-baseline (Join-Path $RepoRoot 'crates\mdbx-ffi\abi\mdbx2-uniffi-bindings-v1.json') `
    --readelf $readelfTool `
    --output $gateReportPath | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "MDBX3 release gate failed with exit code $LASTEXITCODE"
}

if (-not $SkipAbiSplitPackaging) {
    & $abiSplitPackageScript `
        -RepoRoot $RepoRoot `
        -SourceRoot $OutputRoot `
        -OutputRoot $AbiSplitOutputRoot `
        -AbiBaseline (Join-Path $RepoRoot 'crates\mdbx-ffi\abi\mdbx2-uniffi-bindings-v1.json') | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "MDBX3 ABI split packaging failed with exit code $LASTEXITCODE"
    }
}

Get-Content -Raw -LiteralPath $reportPath
