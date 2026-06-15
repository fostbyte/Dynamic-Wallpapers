# Windows Android Environment Setup Script
# Installs JDK 21 and Android SDK command-line tools locally without admin rights.

$ProgressPreference = 'SilentlyContinue' # Accelerates Invoke-WebRequest downloads

# Define target paths
$sdkRoot = "C:\Users\keyga\android-sdk"
$jdkRoot = "C:\Users\keyga\java-jdk"
$tempDir = "C:\Users\keyga\android-setup-temp"

Write-Host "Creating directories..."
if (-not (Test-Path $sdkRoot)) { New-Item -ItemType Directory -Path $sdkRoot | Out-Null }
if (-not (Test-Path $jdkRoot)) { New-Item -ItemType Directory -Path $jdkRoot | Out-Null }
if (-not (Test-Path $tempDir)) { New-Item -ItemType Directory -Path $tempDir | Out-Null }

# 1. Download OpenJDK 21
$jdkZip = "$tempDir\openjdk21.zip"
if (-not (Test-Path $jdkZip)) {
    Write-Host "Downloading OpenJDK 21..."
    Invoke-WebRequest -Uri "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/adoptium" -OutFile $jdkZip -UserAgent "Mozilla/5.0"
}

# 2. Download Android Command-Line Tools
$cmdToolsZip = "$tempDir\commandlinetools.zip"
if (-not (Test-Path $cmdToolsZip)) {
    Write-Host "Downloading Android SDK Command-Line Tools..."
    Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $cmdToolsZip
}

# 3. Extract OpenJDK 21
if (-not (Test-Path "$jdkRoot\bin\java.exe")) {
    Write-Host "Extracting OpenJDK 21..."
    Expand-Archive -Path $jdkZip -DestinationPath "$tempDir\jdk_extracted" -Force
    $extractedFolder = Get-ChildItem -Path "$tempDir\jdk_extracted" -Directory | Select-Object -First 1
    Copy-Item -Path "$($extractedFolder.FullName)\*" -Destination $jdkRoot -Recurse -Force
}

# 4. Extract Android Command-Line Tools
$latestCmdToolsPath = "$sdkRoot\cmdline-tools\latest"
if (-not (Test-Path "$latestCmdToolsPath\bin\sdkmanager.bat")) {
    Write-Host "Extracting Android Command-Line Tools..."
    Expand-Archive -Path $cmdToolsZip -DestinationPath "$tempDir\cmdtools_extracted" -Force
    
    # Structure must be: cmdline-tools/latest/bin
    if (-not (Test-Path "$sdkRoot\cmdline-tools")) { New-Item -ItemType Directory -Path "$sdkRoot\cmdline-tools" | Out-Null }
    if (-not (Test-Path $latestCmdToolsPath)) { New-Item -ItemType Directory -Path $latestCmdToolsPath | Out-Null }
    
    Copy-Item -Path "$tempDir\cmdtools_extracted\cmdline-tools\*" -Destination $latestCmdToolsPath -Recurse -Force
}

# 5. Set environment variables for this process
$env:JAVA_HOME = $jdkRoot
$env:ANDROID_HOME = $sdkRoot
$env:PATH = "$jdkRoot\bin;$sdkRoot\cmdline-tools\latest\bin;$sdkRoot\platform-tools;$env:PATH"

Write-Host "Verifying Java installation..."
java -version

# 6. Install Android SDK components using sdkmanager
Write-Host "Accepting licenses..."
# Create the license file directory and write license hashes to skip prompt, or pipe "y"
$licenseDir = "$sdkRoot\licenses"
if (-not (Test-Path $licenseDir)) { New-Item -ItemType Directory -Path $licenseDir | Out-Null }
# Standard licenses hashes
Set-Content -Path "$licenseDir\android-sdk-license" -Value "24333f8a63b6825ea9c5514f83c2829b004d6f43`n84831b9409646a913e0857d81540ee9987258477`nd975f251678a65362dc24f2b3d1b7f6a22a33c68"
Set-Content -Path "$licenseDir\android-sdk-preview-license" -Value "84831b9409646a913e0857d81540ee9987258477"

Write-Host "Installing Android SDK Platform 34, Build-Tools 34.0.0, and Platform-Tools..."
# We use sdkmanager to install platforms and build-tools
& "$latestCmdToolsPath\bin\sdkmanager.bat" --sdk_root=$sdkRoot "platform-tools" "platforms;android-34" "build-tools;34.0.0"

Write-Host "Android Environment Setup Complete!"
Write-Host "------------------------------------"
Write-Host "JAVA_HOME: $env:JAVA_HOME"
Write-Host "ANDROID_HOME: $env:ANDROID_HOME"
Write-Host "adb version:"
adb version
Write-Host "------------------------------------"

# Cleanup temp folder
Write-Host "Cleaning up temp folder..."
Remove-Item -Path $tempDir -Recurse -Force -ErrorAction SilentlyContinue
