Describe 'Nowy-Skrypt.ps1' {
    It 'Tworzy raport i zapisuje zakonczenie' {
        $skrypt = Join-Path $PSScriptRoot '../scripts/Nowy-Skrypt.ps1'
        $raport = Join-Path ([System.IO.Path]::GetTempPath()) ("test-ps1-" + [guid]::NewGuid().ToString('N') + ".log")

        try {
            & $skrypt -LogPath $raport | Out-Null
            Test-Path -LiteralPath $raport | Should -BeTrue
            (Get-Content -LiteralPath $raport -Raw) | Should -Match 'ZAKONCZONO POMYSLNIE'
        }
        finally {
            Remove-Item -LiteralPath $raport -ErrorAction SilentlyContinue
        }
    }
}
