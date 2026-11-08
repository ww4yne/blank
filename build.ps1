param(
    [switch]$UiOnly,
    [string]$DeveloperTokenPath = '',
    [string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug')
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if ($root -match '(?i)OneDrive') { throw 'Build workspaces must not be in OneDrive.' }
$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Android\openjdk\jdk-21.0.8' }
$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:GRADLE_USER_HOME = Join-Path $root '.tools\gradle-cache'
foreach ($path in @($env:JAVA_HOME, $env:ANDROID_HOME, $env:GRADLE_USER_HOME)) {
    if ($path -match '(?i)OneDrive') { throw 'Toolchains and caches must not be in OneDrive.' }
}
$sdkArchive = Join-Path (Split-Path $root) 'AndroidMusicKitSDK1.1.2.zip'
$libs = Join-Path $root 'app\libs'
New-Item -ItemType Directory -Force $libs | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($sdkArchive)
try {
    foreach ($name in @('mediaplayback-release-1.1.1.aar', 'musickitauth-release-1.1.2.aar')) {
        $entry = $archive.GetEntry("Android-MusicKit-SDK-1.1.2/$name")
        if (-not $entry) { throw "Official SDK archive does not contain $name." }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $libs $name), $true)
    }
} finally { $archive.Dispose() }
$tools = Join-Path $root '.tools'
$gradle = Join-Path $tools 'gradle-8.11.1\bin\gradle.bat'
if (-not (Test-Path $gradle)) {
    New-Item -ItemType Directory -Force $tools | Out-Null
    $distribution = Join-Path $tools 'gradle-8.11.1-bin.zip'
    $url = 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip'
    if (-not (Test-Path $distribution)) { Invoke-WebRequest $url -OutFile $distribution }
    $checksum = (Invoke-WebRequest "$url.sha256").Content
    $expected = if ($checksum -is [byte[]]) { [Text.Encoding]::UTF8.GetString($checksum).Trim() } else { "$checksum".Trim() }
    if ((Get-FileHash $distribution -Algorithm SHA256).Hash -ine $expected) {
        throw 'Gradle distribution SHA-256 does not match the official checksum.'
    }
    Expand-Archive -LiteralPath $distribution -DestinationPath $tools
    Remove-Item -LiteralPath $distribution
}
Push-Location $root
$previousTokenPath = $env:MOTING_DEVELOPER_TOKEN_FILE
try {
    if ($DeveloperTokenPath) {
        $resolvedToken = (Resolve-Path -LiteralPath $DeveloperTokenPath).Path
        if ($resolvedToken -match '(?i)OneDrive') { throw 'Do not read developer credentials from OneDrive.' }
        if (-not (Test-Path -LiteralPath $resolvedToken -PathType Leaf)) { throw 'Expected a developer-token file.' }
        $env:MOTING_DEVELOPER_TOKEN_FILE = $resolvedToken
    } else {
        $env:MOTING_DEVELOPER_TOKEN_FILE = $null
    }
    $arguments = @('--no-daemon', '--console=plain') + $Tasks
    if ($UiOnly) { $arguments += '-PuiOnly=true' }
    & $gradle @arguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)." }
    $buildsApk = @($Tasks | Where-Object {
        $_ -match '(^|:)(assembleDebug|assemble|build|connectedDebugAndroidTest|connectedCheck)$'
    }).Count -gt 0
    if ($buildsApk -and -not $UiOnly) {
        $apkDirectory = Join-Path $root 'app\build\outputs\apk\debug'
        $metadata = Get-Content -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
        $elements = @($metadata.elements)
        if ($metadata.applicationId -ne 'io.github.ww4yne.blank' -or $elements.Count -ne 1) {
            throw 'Expected the normal, single-APK player.'
        }
        $version = $elements[0].versionName
        $filename = $elements[0].outputFile
        if ($version -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$' -or
            [IO.Path]::GetFileName($filename) -ne $filename -or
            [IO.Path]::GetExtension($filename) -ne '.apk') {
            throw 'Unexpected APK output metadata.'
        }
        $apk = Join-Path $apkDirectory $filename
        if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'The final APK is missing.' }
        $outputDirectory = Join-Path $root 'dist'
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
        $publishedName = if ($DeveloperTokenPath) { "BLANK-$version-personal.apk" } else { "BLANK-$version.apk" }
        $published = Join-Path $outputDirectory $publishedName
        Copy-Item -LiteralPath $apk -Destination $published -Force
        if ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ne
            (Get-FileHash -LiteralPath $published -Algorithm SHA256).Hash) {
            throw 'The final APK checksum does not match the build output.'
        }
        Write-Output "Final APK (checksum verified): $published"
    }
} finally {
    $env:MOTING_DEVELOPER_TOKEN_FILE = $previousTokenPath
    Pop-Location
}
