<#
.SYNOPSIS
    Host-side Windows setup script to prepare WSL2 Ubuntu 22.04 prerequisites for Aria Vision GPU training.
.DESCRIPTION
    Must be executed in an Elevated PowerShell Prompt (Run as Administrator).
    Performs prerequisite checks:
    1. Checks CPU Virtualization (AMD-V / Intel VT-x) in BIOS/firmware.
    2. Enables 'Microsoft-Windows-Subsystem-Linux' optional feature via DISM.
    3. Enables 'VirtualMachinePlatform' optional feature via DISM.
    4. Handles DISM return codes (0 = Success, 3010 = ERROR_SUCCESS_REBOOT_REQUIRED).
    5. Halts safely before WSL operations if an OS reboot is pending.
    6. If reboot is not pending, installs Ubuntu-22.04 if absent.
    7. Strictly checks that Ubuntu-22.04 is WSL version 2. Does NOT alter existing distributions.
       If an existing Ubuntu-22.04 is version 1, reports a blocker without modifying it.
    8. Normalizes embedded NUL characters from UTF-16 wsl.exe output when parsing.
#>

[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

Write-Host "=== Aria Vision Model: Host WSL2 Setup Script ===" -ForegroundColor Cyan
Write-Host "Timestamp: $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssZ')"

# Helper function to get clean output from wsl.exe (normalizes UTF-16 NUL bytes)
function Get-CleanWslOutput {
    param([string[]]$ArgumentList)
    $output = (& wsl.exe @ArgumentList 2>&1 | Out-String)
    if ($null -eq $output) { return "" }
    return ($output -replace "`0", "").Trim()
}

# Check for Administrator elevation
$currentPrincipal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
$isAdmin = $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "[ERROR] This script requires Administrator elevation (DISM Error 740)." -ForegroundColor Red
    Write-Host "Please open an elevated PowerShell prompt (Run as Administrator) to execute this script." -ForegroundColor Yellow
    exit 740
}
Write-Host "[OK] Running with Administrator elevation." -ForegroundColor Green

# 1. Check CPU Virtualization in BIOS
Write-Host "`n[1/4] Checking CPU Virtualization in BIOS/firmware..."
$cpu = Get-CimInstance -ClassName Win32_Processor | Select-Object -First 1 Name, VirtualizationFirmwareEnabled
Write-Host "  CPU: $($cpu.Name)"
if ($cpu.VirtualizationFirmwareEnabled) {
    Write-Host "  [OK] Hardware Virtualization (AMD-V / VT-x) is ENABLED in firmware." -ForegroundColor Green
} else {
    Write-Host "  [ERROR] Hardware Virtualization is DISABLED in BIOS. Please enable AMD-V / SVM or Intel VT-x in UEFI setup." -ForegroundColor Red
    exit 1
}

# 2. Enable Windows Optional Features via DISM
Write-Host "`n[2/4] Enabling Windows Optional Features via DISM..."
$featuresToEnable = @(
    "Microsoft-Windows-Subsystem-Linux",
    "VirtualMachinePlatform"
)

$rebootPending = $false

foreach ($feature in $featuresToEnable) {
    Write-Host "  Processing feature: $feature"
    & dism.exe /online /enable-feature /featurename:$feature /all /norestart
    $dismCode = $LASTEXITCODE

    if ($dismCode -eq 0) {
        Write-Host "  [OK] Feature $feature enabled successfully." -ForegroundColor Green
    } elseif ($dismCode -eq 3010) {
        Write-Host "  [INFO] Feature $feature enabled; OS reboot is required to activate kernel drivers (code 3010)." -ForegroundColor Yellow
        $rebootPending = $true
    } else {
        Write-Host "  [ERROR] DISM failed for $feature with exit code: $dismCode" -ForegroundColor Red
        exit $dismCode
    }
}

# 3. Halt before WSL operations if reboot is pending
if ($rebootPending) {
    Write-Host "`n==========================================================================" -ForegroundColor Yellow
    Write-Host "  SYSTEM REBOOT REQUIRED BEFORE WSL OPERATIONS" -ForegroundColor Yellow
    Write-Host "  VirtualMachinePlatform and WSL kernel drivers have been staged but" -ForegroundColor Yellow
    Write-Host "  require a Windows restart to initialize the hypervisor." -ForegroundColor Yellow
    Write-Host "  Please restart Windows, then rerun this script to complete Ubuntu setup." -ForegroundColor Yellow
    Write-Host "==========================================================================" -ForegroundColor Yellow
    exit 3010
}

# 4. Update WSL kernel components (fail immediately on nonzero)
Write-Host "`n[3/4] Updating WSL kernel components..."
& wsl.exe --update
if ($LASTEXITCODE -ne 0) {
    Write-Host "  [ERROR] wsl.exe --update failed with exit code $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}

# 5. Inspect existing distributions and install Ubuntu-22.04 if absent
Write-Host "`n[4/4] Checking Ubuntu-22.04 distribution and WSL version..."
$cleanList = Get-CleanWslOutput -ArgumentList @("--list", "--verbose")
$lines = $cleanList -split "`r?`n"

$ubuntuInstalled = $false
$ubuntuVersion = $null

foreach ($line in $lines) {
    # Match lines like: "* Ubuntu-22.04    Running    2" or "  Ubuntu-22.04    Stopped    1"
    if ($line -match "Ubuntu-22\.04\s+\w+\s+(\d+)") {
        $ubuntuInstalled = $true
        $ubuntuVersion = [int]$matches[1]
        break
    }
}

if (-not $ubuntuInstalled) {
    Write-Host "  Ubuntu-22.04 not found. Installing Ubuntu-22.04 (no launch)..."
    & wsl.exe --install -d Ubuntu-22.04 --no-launch
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  [ERROR] wsl.exe --install -d Ubuntu-22.04 failed with exit code $LASTEXITCODE" -ForegroundColor Red
        exit $LASTEXITCODE
    }
} else {
    Write-Host "  [INFO] Ubuntu-22.04 is already installed with WSL version $ubuntuVersion."
    if ($ubuntuVersion -ne 2) {
        # Strict constraint: NEVER modify existing distributions
        Write-Host "  [ERROR] Existing Ubuntu-22.04 is version $ubuntuVersion, but version 2 is required for GPU passthrough." -ForegroundColor Red
        Write-Host "  [ERROR] Modifying existing user distributions is forbidden by safety policy." -ForegroundColor Red
        Write-Host "  Please manually verify or migrate your existing Ubuntu-22.04 distribution to version 2." -ForegroundColor Yellow
        exit 1
    }
}

# 6. Final verification: strictly verify Ubuntu-22.04 is present and at version 2
Write-Host "`nVerifying final WSL distribution list:"
$finalList = Get-CleanWslOutput -ArgumentList @("--list", "--verbose")
Write-Host $finalList

$finalVerified = $false
foreach ($line in ($finalList -split "`r?`n")) {
    if ($line -match "Ubuntu-22\.04\s+\w+\s+2") {
        $finalVerified = $true
        break
    }
}

if (-not $finalVerified) {
    Write-Host "[ERROR] Final verification failed: Ubuntu-22.04 was not found with WSL version 2." -ForegroundColor Red
    exit 1
}

Write-Host "`n=== WSL2 HOST SETUP COMPLETE ===" -ForegroundColor Green
Write-Host "Ubuntu-22.04 (WSL version 2) verified."
Write-Host "Next step: Launch Ubuntu-22.04 and execute tools/vision/training/environment/setup_wsl_guest.sh"
exit 0
