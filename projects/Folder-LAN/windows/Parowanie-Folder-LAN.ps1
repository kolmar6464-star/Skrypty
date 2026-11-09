#requires -Version 5.1
<#
.SYNOPSIS
  Pomocnik jednorazowego parowania aplikacji Folder LAN.
.DESCRIPTION
  Pokazuje lokalne dane komputera. Opcjonalnie przez kilka minut odpowiada na
  wykrywanie aplikacji na UDP 47292. Hasło można pokazać lokalnie po wyraźnej
  zgodzie; odczyt dotyczy wyłącznie literału AST z wybranego PS1, bez wykonania kodu.
  Nie przesyła i nie zapisuje hasła.
  Nie zmienia udziałów, kont, zadań, oryginalnego PS1 ani reguł zapory.
#>
[CmdletBinding()]
param(
    [switch]$EnableDiscovery,
    [string]$OriginalScriptPath = '',
    [ValidateRange(1,30)][int]$DiscoveryMinutes = 5
)

# ================= KONFIGURACJA =================
# Nazwa istniejącego udziału SMB skonfigurowanego przez oryginalny skrypt.
$ShareName = 'FolderUdostepnionyLAN'
# Nazwa istniejącego konta lokalnego. Hasło wpisujesz tylko w aplikacji Android.
$AccountName = 'FolderLAN_SVC'
# Oddzielny port nowego pomocnika. Nie używamy niezweryfikowanego protokołu UDP 47291.
$DiscoveryPort = 47292
# Log bez haseł i innych poświadczeń.
$LogPath = Join-Path ([IO.Path]::GetTempPath()) 'FolderLAN_Parowanie.log'
# =================================================

$ErrorActionPreference = 'Stop'
$script:UdpSockets = @()
$script:DiscoveryUntil = [DateTime]::MinValue
$script:Window = $null

function Write-PairingLog {
    param([string]$Message,[string]$Level = 'INFO')
    $line = '[{0}] [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'),$Level,$Message
    Write-Host $line
    try { Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8 -ErrorAction Stop } catch { }
}

function Test-PrivateIPv4 {
    param([Net.IPAddress]$Address)
    $bytes=$Address.GetAddressBytes()
    if($bytes.Length -ne 4){return $false}
    return $bytes[0] -eq 10 -or ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) -or ($bytes[0] -eq 192 -and $bytes[1] -eq 168) -or ($bytes[0] -eq 169 -and $bytes[1] -eq 254)
}

function Test-SameSubnet {
    param([Net.IPAddress]$First,[Net.IPAddress]$Second,[int]$PrefixLength)
    $a=$First.GetAddressBytes();$b=$Second.GetAddressBytes()
    if($a.Length -ne 4 -or $b.Length -ne 4 -or $PrefixLength -lt 1 -or $PrefixLength -gt 32){return $false}
    for($i=0;$i -lt 4;$i++){
        $bits=[Math]::Min(8,[Math]::Max(0,$PrefixLength-$i*8))
        $mask=if($bits -eq 0){0}else{(255 -shl (8-$bits)) -band 255}
        if(($a[$i] -band $mask) -ne ($b[$i] -band $mask)){return $false}
    }
    return $true
}

function Get-LocalLanAddresses {
    $result=@()
    foreach($adapter in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()){
        if($adapter.OperationalStatus -ne 'Up' -or $adapter.NetworkInterfaceType -notin @('Ethernet','Wireless80211')){continue}
        foreach($unicast in $adapter.GetIPProperties().UnicastAddresses){
            if(-not(Test-PrivateIPv4 $unicast.Address)){continue}
            $mask=$unicast.IPv4Mask.GetAddressBytes();$prefix=0
            foreach($byte in $mask){for($bit=7;$bit -ge 0;$bit--){if($byte -band (1 -shl $bit)){$prefix++}}}
            $result += [pscustomobject]@{Address=$unicast.Address;PrefixLength=$prefix}
        }
    }
    return $result
}

function Get-OriginalPasswordLiteral {
    param([Parameter(Mandatory=$true)][string]$Path)
    if(-not(Test-Path -LiteralPath $Path -PathType Leaf)){throw 'Nie znaleziono wskazanego oryginalnego PS1.'}
    $tokens=$null;$parseErrors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$tokens,[ref]$parseErrors)
    if($parseErrors.Count){throw 'Wybrany PS1 ma błędy parsera; nie odczytano hasła.'}
    $assignments=@($ast.EndBlock.Statements | Where-Object {
        $_ -is [Management.Automation.Language.AssignmentStatementAst] -and
        $_.Left.Extent.Text -ceq '$HasloSMB' -and
        $_.Right.Expression -is [Management.Automation.Language.StringConstantExpressionAst]
    })
    if($assignments.Count -ne 1 -or [string]::IsNullOrEmpty($assignments[0].Right.Expression.Value)){
        throw 'Nie znaleziono jednego prostego literału $HasloSMB. Pomocnik nie wykonuje wyrażeń ani skryptu.'
    }
    return [string]$assignments[0].Right.Expression.Value
}

function Show-OriginalPassword {
    $consent=[Windows.Forms.MessageBox]::Show(
        'Pomocnik odczyta wyłącznie dosłowny tekst $HasloSMB z wybranego lokalnego PS1. Nie uruchomi skryptu i nie zapisze hasła. Hasło zostanie pokazane w osobnym oknie dopiero po zaznaczeniu Pokaż hasło. Kontynuować?',
        'Jednorazowe parowanie — zgoda','YesNo','Warning')
    if($consent -ne [Windows.Forms.DialogResult]::Yes){return}
    $dialog=New-Object Windows.Forms.OpenFileDialog
    $dialog.Title='Wybierz oryginalny PS1 konfigurujący Folder LAN'
    $dialog.Filter='Skrypt PowerShell (*.ps1)|*.ps1'
    if($OriginalScriptPath -and (Test-Path -LiteralPath $OriginalScriptPath -PathType Leaf)){$dialog.FileName=$OriginalScriptPath}
    try {
        if($dialog.ShowDialog($script:Window) -ne [Windows.Forms.DialogResult]::OK){return}
        $secret=Get-OriginalPasswordLiteral -Path $dialog.FileName
        $window=New-Object Windows.Forms.Form
        $window.Text='Hasło SMB — tylko do przepisania na własny telefon'
        $window.ClientSize=New-Object Drawing.Size(600,190)
        $window.StartPosition='CenterParent';$window.AutoScaleMode='Dpi'
        $label=New-Object Windows.Forms.Label
        $label.SetBounds(16,14,565,48)
        $label.Text='Wpisz hasło poniżej w aplikacji Folder LAN. Nie wysyłaj tego okna ani oryginalnego PS1 innym osobom. Zamknij okno po parowaniu.'
        $window.Controls.Add($label)
        $box=New-Object Windows.Forms.TextBox
        $box.SetBounds(16,72,565,30);$box.ReadOnly=$true;$box.UseSystemPasswordChar=$true;$box.Text=$secret
        $window.Controls.Add($box)
        $reveal=New-Object Windows.Forms.CheckBox
        $reveal.SetBounds(16,122,360,30);$reveal.Text='Pokaż hasło tylko na tym komputerze'
        $reveal.Add_CheckedChanged({$box.UseSystemPasswordChar=-not $reveal.Checked})
        $window.Controls.Add($reveal)
        try {[void]$window.ShowDialog($script:Window)} finally {$box.Clear();$secret=$null;$window.Dispose()}
        Write-PairingLog 'Zamknięto okno jednorazowego parowania. Hasła nie zapisano ani nie wysłano.'
    } finally {$dialog.Dispose()}
}

function Stop-LanDiscovery {
    foreach($entry in $script:UdpSockets){try{$entry.Socket.Close();$entry.Socket.Dispose()}catch{}}
    $script:UdpSockets=@()
    $script:DiscoveryUntil=[DateTime]::MinValue
}

function Start-LanDiscovery {
    Stop-LanDiscovery
    foreach($address in @(Get-LocalLanAddresses)){
        try {
            $endpoint=New-Object Net.IPEndPoint($address.Address,$DiscoveryPort)
            $socket=New-Object Net.Sockets.UdpClient($endpoint)
            $script:UdpSockets += [pscustomobject]@{Socket=$socket;Address=$address.Address;PrefixLength=$address.PrefixLength}
        } catch {Write-PairingLog ('Nie uruchomiono wykrywania na jednym z interfejsów: '+$_.Exception.Message) 'WARN'}
    }
    if(-not $script:UdpSockets.Count){throw 'Nie można nasłuchiwać UDP na lokalnym interfejsie. Użyj ręcznego IP w aplikacji.'}
    $script:DiscoveryUntil=(Get-Date).AddMinutes($DiscoveryMinutes)
    Write-PairingLog ('Wykrywanie włączone na '+$DiscoveryMinutes+' min; port UDP '+$DiscoveryPort+'. Hasła nie są przesyłane.')
}

function Receive-LanDiscovery {
    if((Get-Date) -gt $script:DiscoveryUntil){Stop-LanDiscovery;return}
    foreach($entry in $script:UdpSockets){
        for($count=0;$count -lt 16 -and $entry.Socket.Available -gt 0;$count++){
            try {
                $remote=New-Object Net.IPEndPoint([Net.IPAddress]::Any,0)
                $bytes=$entry.Socket.Receive([ref]$remote)
                if($bytes.Length -gt 512 -or -not(Test-PrivateIPv4 $remote.Address) -or -not(Test-SameSubnet $remote.Address $entry.Address $entry.PrefixLength)){continue}
                $message=[Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json -ErrorAction Stop
                if($message.protocol -cne 'folderlan-android-v1' -or $message.type -cne 'discover'){continue}
                $reply=@{protocol='folderlan-android-v1';type='host';name=$env:COMPUTERNAME;host=$entry.Address.ToString();share=$ShareName} | ConvertTo-Json -Compress
                $replyBytes=[Text.Encoding]::UTF8.GetBytes($reply)
                [void]$entry.Socket.Send($replyBytes,$replyBytes.Length,$remote)
            } catch {Write-PairingLog 'Pominięto niepoprawne zapytanie lub zakończone połączenie UDP.' 'WARN'}
        }
    }
}

try {
    if($env:OS -ne 'Windows_NT'){throw 'Pomocnik wymaga systemu Windows.'}
    $isSystem=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value -eq 'S-1-5-18'
    if($isSystem -or [Diagnostics.Process]::GetCurrentProcess().SessionId -eq 0 -or -not [Environment]::UserInteractive){
        Write-PairingLog 'Nie uruchomiono GUI w SYSTEM / sesji 0. Parowanie wykonaj po zalogowaniu użytkownika.' 'WARN'
        exit 2
    }
    if([Threading.Thread]::CurrentThread.ApartmentState -ne 'STA'){
        $hostExe=Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $arguments=@('-NoProfile','-STA','-ExecutionPolicy','Bypass','-File',$PSCommandPath,'-DiscoveryMinutes',[string]$DiscoveryMinutes)
        if($EnableDiscovery){$arguments+='-EnableDiscovery'}
        if($OriginalScriptPath){$arguments+='-OriginalScriptPath';$arguments+=$OriginalScriptPath}
        & $hostExe @arguments
        exit $LASTEXITCODE
    }
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [Windows.Forms.Application]::EnableVisualStyles()
    $addresses=@(Get-LocalLanAddresses)
    $ips=($addresses | ForEach-Object{$_.Address.ToString()}) -join ', '
    if(-not $ips){$ips='Nie znaleziono prywatnego IPv4; połącz komputer z LAN.'}
    $shareStatus='Nie potwierdzono istnienia udziału; uruchom oryginalny skrypt konfigurujący LAN.'
    try {if(Get-SmbShare -Name $ShareName -ErrorAction Stop){$shareStatus='Udział istnieje. Uprawnienia i hasło należy sprawdzić przy pierwszym połączeniu.'}} catch { }
    $form=New-Object Windows.Forms.Form
    $script:Window=$form
    $form.Text='Folder LAN — jednorazowe parowanie'
    $form.Size=New-Object Drawing.Size(720,570)
    $form.MinimumSize=New-Object Drawing.Size(660,540)
    $form.StartPosition='CenterScreen';$form.AutoScaleMode='Dpi'
    $form.Font=New-Object Drawing.Font('Segoe UI',10)
    $text=New-Object Windows.Forms.TextBox
    $text.SetBounds(18,18,665,325);$text.Anchor='Top,Bottom,Left,Right'
    $text.Multiline=$true;$text.ReadOnly=$true;$text.ScrollBars='Vertical'
    $text.Text=@"
1. Połącz telefon i komputer z tą samą zaufaną siecią Wi-Fi/LAN.
2. Otwórz Folder LAN na Androidzie i wyszukaj komputer lub wpisz IP ręcznie.
3. Przy pierwszym połączeniu wpisz poniższe dane oraz znane Ci hasło konta SMB.
4. Zatwierdź zapis na telefonie — poświadczenia są chronione Android Keystore.

Komputer / domena lokalna: $env:COMPUTERNAME
Adresy IPv4: $ips
Udział: $ShareName
Użytkownik: $AccountName

$shareStatus

Przycisk „Hasło z oryginalnego PS1” pozwala lokalnie odczytać tylko literał
HasloSMB po Twojej zgodzie, bez wykonywania PS1 ani przenoszenia hasła. Nie zmieniaj
hasła konta, jeśli używają go już dwa sparowane komputery. Wykryta nazwa
komputera nie jest potwierdzeniem jego tożsamości — porównaj ją i IP z tym oknem.

Opcjonalne wykrywanie używa UDP $DiscoveryPort przez $DiscoveryMinutes min. Zapora
może je blokować; nie otwieramy portów automatycznie. Ręczne IP nadal działa.
Nie przekierowuj SMB/445 w routerze do Internetu.
"@
    $form.Controls.Add($text)
    $start=New-Object Windows.Forms.Button;$start.Text='Włącz wykrywanie na kilka minut'
    $start.SetBounds(18,355,325,38);$start.Anchor='Bottom,Left';$form.Controls.Add($start)
    $stop=New-Object Windows.Forms.Button;$stop.Text='Wyłącz wykrywanie'
    $stop.SetBounds(355,355,220,38);$stop.Anchor='Bottom,Left';$form.Controls.Add($stop)
    $password=New-Object Windows.Forms.Button;$password.Text='Hasło z oryginalnego PS1 (jednorazowo)'
    $password.SetBounds(18,405,440,38);$password.Anchor='Bottom,Left';$form.Controls.Add($password)
    $password.Add_Click({try{Show-OriginalPassword}catch{Write-PairingLog 'Nie udało się odczytać hasła z lokalnego PS1.' 'ERROR';[void][Windows.Forms.MessageBox]::Show($_.Exception.Message,'Folder LAN','OK','Error')}})
    $status=New-Object Windows.Forms.Label;$status.SetBounds(18,460,665,40);$status.Anchor='Bottom,Left,Right'
    $status.Text='Wykrywanie wyłączone. Możesz od razu wpisać IP w aplikacji.';$form.Controls.Add($status)
    $start.Add_Click({try{Start-LanDiscovery;$status.Text='Wykrywanie aktywne do '+$script:DiscoveryUntil.ToString('HH:mm:ss')}catch{Write-PairingLog $_.Exception.Message 'ERROR';$status.Text=$_.Exception.Message}})
    $stop.Add_Click({Stop-LanDiscovery;$status.Text='Wykrywanie wyłączone.'})
    $timer=New-Object Windows.Forms.Timer;$timer.Interval=250
    $timer.Add_Tick({try{Receive-LanDiscovery;if(-not $script:UdpSockets.Count){$status.Text='Wykrywanie wyłączone. Ręczne IP jest nadal dostępne.'}}catch{Write-PairingLog $_.Exception.Message 'ERROR';Stop-LanDiscovery;$status.Text='Wykrywanie zatrzymano po błędzie. Użyj ręcznego IP.'}})
    $form.Add_Shown({$timer.Start();if($EnableDiscovery){$start.PerformClick()}})
    $form.Add_FormClosed({$timer.Stop();Stop-LanDiscovery})
    Write-PairingLog 'Otwarto pomocnika. Nie zmieniono udziałów, kont, zadań ani zapory.'
    try {[void]$form.ShowDialog()} finally {$timer.Stop();$timer.Dispose();$form.Dispose()}
} catch {
    Write-PairingLog ('Błąd pomocnika: '+$_.Exception.Message) 'ERROR'
    if($env:OS -eq 'Windows_NT' -and [Environment]::UserInteractive -and -not $isSystem){
        try {[void][Windows.Forms.MessageBox]::Show($_.Exception.Message,'Folder LAN','OK','Error')}catch{}
    }
    exit 1
} finally {Stop-LanDiscovery}
