#requires -Version 5.1
<#
.SYNOPSIS
  Bezpieczny szablon skryptu PowerShell dla Windows 11.
.DESCRIPTION
  Sprawdza środowisko i zapisuje raport bez zmian w systemie.
  To przykład — nie jest instalatorem NTLite.
#>

param(
    [string]$LogPath = (Join-Path -Path $env:TEMP -ChildPath 'Raport_Nowy-Skrypt.log')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ================= KONFIGURACJA =================
$PokazKomunikaty = $true
# =================================================

function Write-Raport {
    param([Parameter(Mandatory = $true)][string]$Tekst)
    $data = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    $linia = "[$data] $Tekst"
    Add-Content -LiteralPath $LogPath -Value $linia -Encoding UTF8
    if ($PokazKomunikaty) { Write-Output $linia }
}

try {
    $folder = Split-Path -Path $LogPath -Parent
    if ($folder -and -not (Test-Path -LiteralPath $folder)) {
        New-Item -ItemType Directory -Path $folder -Force | Out-Null
    }

    Write-Raport 'START SKRYPTU'
    Write-Raport ('System: ' + [System.Environment]::OSVersion.VersionString)
    Write-Raport ('Uzytkownik: ' + [System.Environment]::UserName)
    Write-Raport 'ZAKONCZONO POMYSLNIE'
}
catch {
    $opis = $_ | Out-String
    try {
        Add-Content -LiteralPath $LogPath -Value ("BLAD: " + $opis) -Encoding UTF8
    } catch {
        Write-Error ("Nie udalo sie zapisac raportu: " + $_.Exception.Message)
    }
    throw
}
