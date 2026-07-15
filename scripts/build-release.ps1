# Build a release APK of JCode on Windows (pwsh). Output: .\builds
#   -Yes                             : auto-accept install prompts
#   -Variant release|beta            : pick the build variant non-interactively (else you're prompted).
#                                      beta = side-by-side app (dev.jcode.beta / "JCode (beta)") that
#                                      installs ALONGSIDE the release build instead of replacing it.
#   -PreReleaseLabel <label>         : label appended to a beta versionName (default: beta -> 1.0.2-beta)
[CmdletBinding()]
param(
    [switch]$Yes,
    [ValidateSet('release', 'beta', 'prerelease')]
    [string]$Variant,
    [string]$PreReleaseLabel = 'beta'
)

$ErrorActionPreference = 'Stop'
$NdkVersion = '27.2.12479018'
$PlatformPkg = 'platforms;android-36'
$BuildToolsPkg = 'build-tools;34.0.0'
$CmakePkg = 'cmake;3.22.1'

function Say([string]$m)  { Write-Host "[jcode] $m" -ForegroundColor Cyan }
function Warn([string]$m) { Write-Host "[warn] $m" -ForegroundColor Yellow }
function Fail([string]$m) { Write-Host "[error] $m" -ForegroundColor Red; exit 1 }
function Have([string]$cmd) { $null -ne (Get-Command $cmd -ErrorAction SilentlyContinue) }

function Ask([string]$prompt) {
    if ($Yes) { return $true }
    return Ask-Interactive $prompt
}

# Never auto-accepted by -Yes (used for choices, not installs).
function Ask-Interactive([string]$prompt) {
    if ([Console]::IsInputRedirected) { return $false }
    $a = Read-Host "$prompt [y/N]"
    return $a -match '^(y|yes)$'
}

function Refresh-Path {
    $env:Path = $env:Path + ';' +
                [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
                [Environment]::GetEnvironmentVariable('Path', 'User')
}

function Require-Tool([string]$Tool, [string]$Hint, [string]$InstallCmd) {
    if (Have $Tool) { return }
    Warn "Missing required tool: $Tool"
    Say  "  install with: $Hint"
    if ($InstallCmd -and (Ask "Install $Tool now?")) {
        Invoke-Expression $InstallCmd
        Refresh-Path
        if (-not (Have $Tool)) { Fail "$Tool still not found after install (open a new shell and re-run?)" }
    } else {
        Fail "Please install $Tool and re-run."
    }
}

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot
Say "JCode release build (Windows) - repo: $RepoRoot"

# --- Build variant: Release or Pre-release (interactive unless -Variant was given) ---
$IsPre = $false
if ($Variant) {
    $IsPre = ($Variant -eq 'beta' -or $Variant -eq 'prerelease')
} elseif (-not [Console]::IsInputRedirected) {
    Write-Host ''
    Say 'Which build?'
    Write-Host '  [1] Release  - final build (dev.jcode / "JCode"), version straight from VERSION.txt' -ForegroundColor Gray
    Write-Host '  [2] Beta     - side-by-side testing build (dev.jcode.beta / "JCode (beta)"): installs' -ForegroundColor Gray
    Write-Host '                 ALONGSIDE the release app, own data, versionName gets a -label suffix' -ForegroundColor Gray
    $sel = Read-Host 'Select [1]'
    $IsPre = ($sel -match '^(2|p|b)')
    if ($IsPre) {
        $lbl = Read-Host "Pre-release label [$PreReleaseLabel]"
        if ($lbl) { $PreReleaseLabel = ($lbl.Trim() -replace '[^0-9A-Za-z.\-]', '') }
    }
}
# Beta = a separate app id so it doesn't overwrite the installed release. Fixed ".beta" (one beta
# slot) regardless of the version label, so successive betas replace each other, never the release.
$IdSuffix = if ($IsPre) { '.beta' } else { '' }
$AppLabel = if ($IsPre) { 'JCode (beta)' } else { 'JCode' }
$VariantTag = if ($IsPre) { 'beta' } else { 'release' }
Say "Variant: $VariantTag$(if ($IsPre) { " -> app id dev.jcode$IdSuffix, label '$AppLabel', version label: $PreReleaseLabel" })"

if ($RepoRoot.Length -gt 50) {
    Warn "Repo path is $($RepoRoot.Length) chars - the native (tree-sitter) build can hit the Win32 MAX_PATH limit."
    Say  "  if the native build fails with path errors, build from a short-path worktree, e.g.: git worktree add X:\jc"
}

Require-Tool git 'winget install Git.Git' 'winget install --accept-package-agreements --accept-source-agreements Git.Git'

if (-not (Have java) -and $env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}
Require-Tool java 'winget install EclipseAdoptium.Temurin.21.JDK   (or https://adoptium.net)' 'winget install --accept-package-agreements --accept-source-agreements EclipseAdoptium.Temurin.21.JDK'
$javaVer = (java -version 2>&1 | Select-Object -First 1) -replace '.*version "([0-9]+).*', '$1'
if ($javaVer -match '^\d+$') {
    if ([int]$javaVer -lt 17) {
        Warn "JDK $javaVer found, but 17+ is required (21 recommended)."
        Fail 'Install JDK 21 (winget install EclipseAdoptium.Temurin.21.JDK) and re-run.'
    }
    Say "JDK $javaVer OK"
} else {
    Warn 'Could not parse Java version; continuing (need JDK 17+, 21 recommended).'
}

$SdkRoot = $env:ANDROID_HOME
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $SdkRoot -and (Test-Path 'local.properties')) {
    $line = Select-String -Path 'local.properties' -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
    if ($line) { $SdkRoot = $line.Matches[0].Groups[1].Value -replace '\\\\', '\' -replace '\\:', ':' }
}
if (-not $SdkRoot -and (Test-Path "$env:LOCALAPPDATA\Android\Sdk")) { $SdkRoot = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not $SdkRoot -or -not (Test-Path $SdkRoot)) {
    Warn "Android SDK not found (checked ANDROID_HOME, ANDROID_SDK_ROOT, local.properties, $env:LOCALAPPDATA\Android\Sdk)."
    Say  'Install it, then re-run:'
    Say  '  - Android Studio (easiest): https://developer.android.com/studio'
    Say  '  - or command-line tools only: https://developer.android.com/studio#command-line-tools-only'
    Say  "    unzip to $env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest, then:"
    Say  "    sdkmanager `"platform-tools`" `"$PlatformPkg`" `"$BuildToolsPkg`" `"ndk;$NdkVersion`" `"$CmakePkg`""
    Fail 'Android SDK is required.'
}
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
Say "Android SDK: $SdkRoot"

$SdkManager = "$SdkRoot\cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $SdkManager)) {
    $SdkManager = Get-ChildItem "$SdkRoot\cmdline-tools\*\bin\sdkmanager.bat" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}

function Sdk-Component([string]$Path, [string]$Pkg, [string]$Label) {
    if (Test-Path $Path) { return }
    Warn "Missing SDK component: $Label"
    if ($SdkManager) {
        Say "  install with: sdkmanager `"$Pkg`""
        if (Ask "Install $Label now via sdkmanager?") {
            & $SdkManager $Pkg
            if ($LASTEXITCODE -ne 0) { Fail 'sdkmanager failed (try accepting licenses: sdkmanager --licenses)' }
            return
        }
    } else {
        Say "  install `"$Pkg`" via Android Studio's SDK Manager or sdkmanager (cmdline-tools not found in the SDK)."
    }
    Fail "Please install $Label and re-run."
}

Sdk-Component "$SdkRoot\ndk\$NdkVersion" "ndk;$NdkVersion" "NDK $NdkVersion"
Sdk-Component "$SdkRoot\platforms\android-36" $PlatformPkg 'Android platform 36'
if (-not (Test-Path "$SdkRoot\cmake") -or -not (Get-ChildItem "$SdkRoot\cmake" -ErrorAction SilentlyContinue)) {
    Sdk-Component "$SdkRoot\cmake\3.22.1" $CmakePkg 'SDK CMake'
}
if (-not (Test-Path "$SdkRoot\build-tools") -or -not (Get-ChildItem "$SdkRoot\build-tools" -ErrorAction SilentlyContinue)) {
    Sdk-Component "$SdkRoot\build-tools\34.0.0" $BuildToolsPkg 'Android build-tools'
}

$CargoTasks = @()
$rustReady = $true
if (-not (Have cargo) -and (Test-Path "$env:USERPROFILE\.cargo\bin\cargo.exe")) {
    $env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"
}
if (-not (Have cargo)) {
    $rustReady = $false
    Warn 'Rust (cargo) not found - the ripgrep/wasmtime native libs will be built as stubs.'
    Say  '  install with: winget install Rustlang.Rustup   (then: rustup default stable)'
    if (Ask 'Install Rust now?') {
        winget install --accept-package-agreements --accept-source-agreements Rustlang.Rustup
        Refresh-Path
        if (Have cargo) { $rustReady = $true }
    }
}
if ($rustReady -and -not (cargo ndk --version 2>$null)) {
    Warn 'cargo-ndk not found.'
    if (Ask 'Install cargo-ndk now (cargo install cargo-ndk)?') {
        cargo install cargo-ndk
        if ($LASTEXITCODE -ne 0) { Warn 'cargo-ndk install failed.'; $rustReady = $false }
    } else { $rustReady = $false }
}
if ($rustReady) {
    if (Have rustup) {
        $targets = rustup target list --installed 2>$null
        if ($targets -notcontains 'aarch64-linux-android') {
            Warn 'Rust target aarch64-linux-android not installed.'
            if (Ask 'Add it now (rustup target add aarch64-linux-android)?') {
                rustup target add aarch64-linux-android
                if ($LASTEXITCODE -ne 0) { Warn 'rustup target add failed.'; $rustReady = $false }
            } else { $rustReady = $false }
        }
    } else {
        Warn "cargo found but rustup isn't - can't verify the aarch64-linux-android target."
        Say  '  install Rust via rustup instead: winget install Rustlang.Rustup'
        $rustReady = $false
    }
}
if ($rustReady) {
    $CargoTasks = @(':native:ripgrep-ffi:cargoBuildReleaseJniLibs', ':native:wasmtime-ffi:cargoBuildReleaseJniLibs')
    Say 'Rust toolchain OK'
} else {
    Warn 'Continuing without Rust - search/wasm features in the APK will use stub libraries.'
}

$Version = if (Test-Path 'VERSION.txt') { (Get-Content 'VERSION.txt' -Raw).Trim() } else { '1.0.0' }
# Pre-release appends the label to versionName (1.0.2 -> 1.0.2-beta); versionCode ignores the suffix.
$VersionName = if ($IsPre) { "$Version-$PreReleaseLabel" } else { $Version }
# versionCode = MAJOR*10000 + MINOR*100 + PATCH (must match app/build.gradle.kts jcodeVersionCode).
$Code = if ($Version -match '^(\d+)\.(\d+)\.(\d+)') { [int]$Matches[1] * 10000 + [int]$Matches[2] * 100 + [int]$Matches[3] } else { 10000 }
Say "Building JCode v$VersionName ($Code) [$VariantTag] - this compiles native code and can take a while..."

# Cargo libs build in a separate invocation: assembleRelease's configuration then sees them
# and drops the CMake stub for the Rust modules (see root build.gradle.kts).
if ($CargoTasks.Count -gt 0) {
    & .\gradlew.bat @CargoTasks
    if ($LASTEXITCODE -ne 0) { Fail 'Cargo build failed.' }
}
# -PjcodeIdSuffix makes the Beta build a separate app id + label (see app/build.gradle.kts).
$GradleArgs = @(':app:assembleRelease', "-PjcodeVersionName=$VersionName")
if ($IdSuffix) { $GradleArgs += "-PjcodeIdSuffix=$IdSuffix" }
& .\gradlew.bat @GradleArgs
if ($LASTEXITCODE -ne 0) { Fail 'Gradle build failed.' }

$Apk = Get-ChildItem 'app\build\outputs\apk\release\*.apk' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $Apk) { Fail 'Build finished but no APK found in app\build\outputs\apk\release\' }

New-Item -ItemType Directory -Force 'builds' | Out-Null
$Out = "builds\jcode-v$VersionName-$Code-$VariantTag.apk"

$LatestBuildTools = Get-ChildItem "$SdkRoot\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '[^\d.].*$', '') } | Select-Object -Last 1
$ApkSigner = Join-Path $LatestBuildTools.FullName 'apksigner.bat'

function Get-Keytool {
    $j = (Get-Command java -ErrorAction SilentlyContinue).Source
    if (-not $j -and $env:JAVA_HOME) { $j = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    if (-not $j) { return $null }
    $kt = Join-Path (Split-Path $j) 'keytool.exe'
    if (Test-Path $kt) { return $kt } else { return $null }
}

function New-ReleaseKeystore($KsPath, $PassFile) {
    $keytool = Get-Keytool
    if (-not $keytool) { Warn 'keytool not found; cannot create a keystore.'; return $null }
    New-Item -ItemType Directory -Force (Split-Path $KsPath) | Out-Null
    $bytes = New-Object 'System.Byte[]' 18
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $pw = ((([Convert]::ToBase64String($bytes)) -replace '[+/=]', '') + 'aA1').Substring(0, 24)
    & $keytool -genkeypair -v -keystore $KsPath -alias jcode -keyalg RSA -keysize 4096 -validity 10000 `
        -storepass $pw -keypass $pw -dname 'CN=JCode, O=JCode, C=US'
    if ($LASTEXITCODE -ne 0) { Warn 'keytool failed to create the keystore.'; return $null }
    Set-Content -Path $PassFile -Value $pw -NoNewline
    Say  "Created release keystore: $KsPath"
    Say  "  password saved to: $PassFile"
    Warn 'KEEP this keystore + password - reuse it to sign every future release.'
    return @{ Keystore = $KsPath; Pass = $pw }
}

# Resolve the signing keystore: explicit env wins; else the default JCode keystore; else create one.
$KeystoreDefault = Join-Path $env:USERPROFILE '.jcode\jcode-release.jks'
$KeystorePassFile = Join-Path $env:USERPROFILE '.jcode\jcode-release.password.txt'
$Keystore = $env:JCODE_KEYSTORE
$KeystorePass = $env:JCODE_KEYSTORE_PASS
$KeyAlias = $env:JCODE_KEY_ALIAS
$KeyPass = $env:JCODE_KEY_PASS
if (-not $Keystore -and (Test-Path $KeystoreDefault)) {
    $Keystore = $KeystoreDefault
    if (-not $KeyAlias) { $KeyAlias = 'jcode' }
    if (-not $KeystorePass -and (Test-Path $KeystorePassFile)) { $KeystorePass = (Get-Content $KeystorePassFile -Raw).Trim() }
    if (-not $KeyPass) { $KeyPass = $KeystorePass }
    Say "Using release keystore $Keystore"
}
if (-not $Keystore -and (Ask "No release keystore found. Create one now at $KeystoreDefault and sign with it?")) {
    $created = New-ReleaseKeystore $KeystoreDefault $KeystorePassFile
    if ($created) { $Keystore = $created.Keystore; $KeystorePass = $created.Pass; $KeyAlias = 'jcode'; $KeyPass = $created.Pass }
}

$signState = 'unsigned'
if ($Keystore) {
    if (-not (Test-Path $Keystore)) { Fail "JCODE_KEYSTORE is set but not a file: $Keystore" }
    if (-not $KeystorePass) { Fail "No keystore password (set JCODE_KEYSTORE_PASS or provide $KeystorePassFile)." }
    Say "Signing with keystore $Keystore"
    $signArgs = @('sign', '--ks', $Keystore, '--ks-pass', "pass:$KeystorePass")
    if ($KeyAlias) { $signArgs += @('--ks-key-alias', $KeyAlias) }
    if ($KeyPass)  { $signArgs += @('--key-pass', "pass:$KeyPass") }
    $signArgs += @('--out', $Out, $Apk.FullName)
    & $ApkSigner @signArgs
    if ($LASTEXITCODE -ne 0) { Fail 'apksigner failed.' }
    $signState = 'release-signed'
} elseif ((Test-Path "$env:USERPROFILE\.android\debug.keystore") -and
          (Ask-Interactive 'No release keystore configured. Sign with the Android debug keystore so the APK is installable?')) {
    $Out = "builds\jcode-v$VersionName-$Code-$VariantTag-debugsigned.apk"
    & $ApkSigner sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --out $Out $Apk.FullName
    if ($LASTEXITCODE -ne 0) { Fail 'apksigner failed.' }
    $signState = 'debug-signed'
} else {
    $Out = "builds\jcode-v$VersionName-$Code-$VariantTag-unsigned.apk"
    Copy-Item $Apk.FullName $Out -Force
    Warn 'APK is UNSIGNED and cannot be installed as-is.'
    Say  "  sign later: apksigner sign --ks <keystore> --out <signed.apk> $Out"
}

$Sha = (Get-FileHash $Out -Algorithm SHA256).Hash.ToLower()
$SizeMb = [math]::Round((Get-Item $Out).Length / 1MB, 1)
Say "Done: $Out ($SizeMb MB, $signState)"
if ($IdSuffix) { Say "Installs as a SEPARATE app: dev.jcode$IdSuffix ('$AppLabel') - won't overwrite the release build." }
Say "sha256: $Sha"
