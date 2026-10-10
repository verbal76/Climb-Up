<#
 UPWARDLY for Windows: builds the self-contained app-image (EXE + bundled Java runtime + assets) and the Steam depot layout. Run on Windows with JDK 17+ (jpackage) and Python 3 + Pillow.
   powershell -File tools/windows/package.ps1 [-BuildNumber 1] [-Channel experimental]
 Output (all under build/windows/):
   dist/UPWARDLY/            the app-image: UPWARDLY.exe, runtime/, app/upwardly.jar, app/assets/
   steam/content/            exactly what a Steam depot should contain (a copy of dist/UPWARDLY)
   steam/scripts/            app_build / depot_build templates (placeholders for the IDs the owner will create)
   UPWARDLY-<ver>-win64-<channel>.zip, SHA256SUMS.txt, BUILD_INFO.txt
 Nothing here talks to Steam or to GitHub Releases.
#>
param([int]$BuildNumber = 0, [string]$Channel = 'experimental')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $root
$out = Join-Path $root 'build\windows'
$jarDir = Join-Path $root 'desktop\build\windows\input'      # where :desktop:windowsJar writes upwardly.jar
New-Item -ItemType Directory -Force $out | Out-Null
$version = (Select-String -Path 'build.gradle' -Pattern "appVersionName = '([^']+)'").Matches[0].Groups[1].Value
$exeVersion = "$version.$BuildNumber"
Write-Host "UPWARDLY $version build $BuildNumber ($Channel)"

# 1. the jar (Windows natives only)
& "$root\gradlew.bat" :desktop:windowsJar "-PbuildNumber=$BuildNumber" --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'windowsJar failed' }

# 2. icon
python "$root\tools\windows\make_ico.py" "$root\docs\play_store_icon_512.png" "$out\upwardly.ico"
if ($LASTEXITCODE -ne 0) { throw 'icon failed' }

# 3. jpackage app-image with a jlink'd runtime
$dist = Join-Path $out 'dist'
if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
New-Item -ItemType Directory -Force $dist | Out-Null
$jp = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
if (-not (Test-Path $jp)) { $jp = 'jpackage' }
& $jp --type app-image --name UPWARDLY --app-version $exeVersion --vendor 'Hot Attic Games' `
  --description 'UPWARDLY - a tower that does not exist, a climb that does.' --copyright 'Hot Attic Games' `
  --icon "$out\upwardly.ico" --input $jarDir --main-jar upwardly.jar --main-class com.hotatticgames.climbup.desktop.DesktopLauncher `
  --add-modules 'java.base,java.desktop,java.logging,java.management,java.naming,java.xml,jdk.unsupported' `
  --java-options '-Dfile.encoding=UTF-8' --java-options '-Xms256m' --java-options '-Xmx1024m' --java-options '-XX:+UseG1GC' `
  --dest $dist
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }
$app = Join-Path $dist 'UPWARDLY'

# 4. assets beside the jar (the launcher finds app\assets); the OTA public key is not needed on a build that never touches the network
Copy-Item -Recurse "$root\assets" "$app\app\assets"
Remove-Item -Recurse -Force "$app\app\assets\ota" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$app\app\licenses" | Out-Null
Copy-Item "$root\ASSETS.md" "$app\app\licenses\ASSETS.md"

# 5. identification and checksums
$sha = (git rev-parse HEAD)
@"
UPWARDLY (Windows 64-bit)
version: $version
exe version: $exeVersion
channel: $Channel   (experimental builds are test artifacts, not releases)
source sha: $sha
built: $((Get-Date).ToUniversalTime().ToString('s'))Z
launch executable: UPWARDLY.exe
bundled runtime: $(Get-Content "$app\runtime\release" -Raw)
"@ | Set-Content -Encoding ASCII "$app\BUILD_INFO.txt"
$sums = Get-ChildItem -Recurse -File $app | Sort-Object FullName | ForEach-Object { $h = (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLower(); "$h  $($_.FullName.Substring($app.Length + 1).Replace('\','/'))" }
$sums | Set-Content -Encoding ASCII "$out\SHA256SUMS.txt"

# 6. steam layout (content only; the VDF files hold placeholders, the owner fills in the IDs when the Steamworks app exists)
$steam = Join-Path $out 'steam'
if (Test-Path $steam) { Remove-Item -Recurse -Force $steam }
New-Item -ItemType Directory -Force "$steam\content", "$steam\scripts", "$steam\output" | Out-Null
Copy-Item -Recurse "$app\*" "$steam\content"
Copy-Item "$root\tools\windows\steam\*.vdf" "$steam\scripts"

# 7. zip
$zip = Join-Path $out "UPWARDLY-$version-win64-$Channel.zip"
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path $app -DestinationPath $zip
(Get-FileHash -Algorithm SHA256 $zip).Hash.ToLower() + "  " + (Split-Path $zip -Leaf) | Add-Content -Encoding ASCII "$out\SHA256SUMS.txt"
Write-Host "app-image: $app"; Write-Host "zip: $zip"
