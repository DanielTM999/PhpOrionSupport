param(
    [Parameter(Mandatory = $true)][string]$SourceJar,
    [Parameter(Mandatory = $true)][string]$DestinationJar,
    [Parameter(Mandatory = $true)][string]$LogFile
)

$ErrorActionPreference = 'Stop'
$destinationDirectory = Split-Path -Parent $DestinationJar
$stagingJar = "$DestinationJar.installing"
$backupJar = "$DestinationJar.previous"

while ($true) {
    try {
        if (-not (Test-Path -LiteralPath $destinationDirectory)) {
            New-Item -ItemType Directory -Path $destinationDirectory | Out-Null
        }
        Copy-Item -LiteralPath $SourceJar -Destination $stagingJar -Force
        if (Test-Path -LiteralPath $DestinationJar) {
            Copy-Item -LiteralPath $DestinationJar -Destination $backupJar -Force
        }
        Move-Item -LiteralPath $stagingJar -Destination $DestinationJar -Force
        "Installed $(Get-Date -Format o): $DestinationJar" |
                Set-Content -LiteralPath $LogFile -Encoding UTF8
        break
    } catch {
        if (Test-Path -LiteralPath $stagingJar) {
            Remove-Item -LiteralPath $stagingJar -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Seconds 2
    }
}
