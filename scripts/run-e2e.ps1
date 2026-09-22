<#
.SYNOPSIS
  Runs the Gherkin end-to-end suite on a connected device/emulator without keeping Gradle alive.

.DESCRIPTION
  Installs the already built app + test APKs together with Android Test Orchestrator and runs the
  scenarios through it, so every scenario starts from a freshly wiped app. Build first with:
    .\gradlew.bat assembleStableDebug assembleStableDebugAndroidTest

.EXAMPLE
  .\scripts\run-e2e.ps1                          # everything
  .\scripts\run-e2e.ps1 -Tags "@panel"           # one area
  .\scripts\run-e2e.ps1 -Tags "@queue and @new"  # Cucumber tag expression
#>
param(
    [string]$Tags = "",
    [string]$Abi = "x86_64",
    [string]$OutFile = "",
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'E:\code\android' }
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'

$appApk = Join-Path $root "app\build\outputs\apk\stable\debug\app-stable-$Abi-debug.apk"
$testApk = Join-Path $root 'app\build\outputs\apk\androidTest\stable\debug\app-stable-debug-androidTest.apk'
$cache = Join-Path $gradleHome 'caches\modules-2\files-2.1'
$orchestratorApk = Get-ChildItem (Join-Path $cache 'androidx.test\orchestrator') -Recurse -Filter 'orchestrator-*.apk' | Sort-Object FullName | Select-Object -Last 1
$servicesApk = Get-ChildItem (Join-Path $cache 'androidx.test.services\test-services') -Recurse -Filter 'test-services-*.apk' | Sort-Object FullName | Select-Object -Last 1

foreach ($f in @($appApk, $testApk, $orchestratorApk.FullName, $servicesApk.FullName)) {
    if (-not (Test-Path $f)) { throw "Missing $f - build first (see .DESCRIPTION)" }
}

#Espresso needs animations off (Gradle's connected tests do the same); the setting doesn't survive a device reboot
foreach ($scale in 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale') {
    & $adb shell settings put global $scale 0
}
#The "Viewing full screen" hint Android shows on first fullscreen takes window focus and blocks Espresso
& $adb shell settings put secure immersive_mode_confirmations confirmed

if (-not $SkipInstall) {
    & $adb install -r -t -g $appApk | Out-Null
    & $adb install -r -t $testApk | Out-Null
    & $adb install -r --force-queryable $orchestratorApk.FullName | Out-Null
    & $adb install -r --force-queryable $servicesApk.FullName | Out-Null
}

$instrArgs = "-e targetInstrumentation com.futo.platformplayer.test/io.cucumber.android.runner.CucumberAndroidJUnitRunner " +
        "-e clearPackageData true -e optionsAnnotationPackage com.futo.platformplayer.e2e"
if ($Tags) { $instrArgs += " -e tags '$Tags'" }
$command = "CLASSPATH=`$(pm path androidx.test.services) app_process / androidx.test.services.shellexecutor.ShellMain " +
           "am instrument -r -w $instrArgs androidx.test.orchestrator/.AndroidTestOrchestrator"

if (-not $OutFile) { $OutFile = Join-Path $root 'app\build\e2e-raw.txt' }
& $adb shell $command | Tee-Object -FilePath $OutFile | Out-Null

# Summarise the raw instrumentation output: one line per scenario
$results = @()
$current = @{}
$key = $null
foreach ($line in Get-Content $OutFile) {
    if ($line -match '^INSTRUMENTATION_STATUS: (\w+)=(.*)$') {
        $key = $Matches[1]; $current[$key] = $Matches[2]
    } elseif ($line -match '^INSTRUMENTATION_STATUS_CODE: (-?\d+)$') {
        $code = [int]$Matches[1]
        if ($code -ne 1 -and $current['test']) {
            $status = switch ($code) { 0 { 'PASS' } -2 { 'FAIL' } -1 { 'ERROR' } -3 { 'IGNORED' } default { "CODE $code" } }
            $results += [pscustomobject]@{ Feature = $current['class']; Scenario = $current['test']; Result = $status; Stack = $current['stack'] }
        }
        $current = @{}; $key = $null
    } elseif ($key -and -not $line.StartsWith('INSTRUMENTATION_')) {
        $current[$key] += "`n" + $line
    }
}

foreach ($r in $results) {
    "{0,-6} {1} > {2}" -f $r.Result, $r.Feature, $r.Scenario
    if ($r.Result -ne 'PASS' -and $r.Stack) {
        ($r.Stack -split "`n" | Where-Object { $_ -match 'Error|Exception|Timed out|expected' } | Select-Object -First 2) |
            ForEach-Object { "         " + $_.Trim() }
    }
}
$failed = @($results | Where-Object Result -ne 'PASS').Count
"`n$($results.Count) scenarios, $failed not passing. Raw output: $OutFile"
