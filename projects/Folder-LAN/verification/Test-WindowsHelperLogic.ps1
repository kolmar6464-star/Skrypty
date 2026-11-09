$ErrorActionPreference='Stop'
$t=$null;$e=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot '../windows/Parowanie-Folder-LAN.ps1'),[ref]$t,[ref]$e)
if($e.Count){throw 'Parser failed'}
foreach($name in @('Test-PrivateIPv4','Test-SameSubnet','Get-OriginalPasswordLiteral')) {
 $function=$ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -eq $name}
 . ([scriptblock]::Create($function.Extent.Text))
}
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('pairing-fixture-'+[guid]::NewGuid()+'.ps1')
$marker=$fixture+'.marker'
$passed=0
function Assert([bool]$State,[string]$Message){if(-not $State){throw $Message};$script:passed++;Write-Host ('PASS '+$Message)}
try {
 Assert (Test-PrivateIPv4 ([Net.IPAddress]::Parse('192.168.1.1'))) 'prywatny IPv4'
 Assert (-not (Test-PrivateIPv4 ([Net.IPAddress]::Parse('8.8.8.8')))) 'publiczny IPv4 odrzucony'
 Assert (Test-SameSubnet ([Net.IPAddress]::Parse('192.168.1.5')) ([Net.IPAddress]::Parse('192.168.1.10')) 24) 'ta sama podsieć'
 Assert (-not(Test-SameSubnet ([Net.IPAddress]::Parse('192.168.2.5')) ([Net.IPAddress]::Parse('192.168.1.10')) 24)) 'obca podsieć odrzucona'
 Set-Content -LiteralPath $fixture -Value ('$HasloSMB = ''synthetic-fixture-only'''+[Environment]::NewLine+'Set-Content -LiteralPath '''+$marker+''' -Value ''should-not-execute''')
 Assert ((Get-OriginalPasswordLiteral $fixture) -ceq 'synthetic-fixture-only') 'odczyt wyłącznie literału AST'
 Assert (-not(Test-Path -LiteralPath $marker)) 'kod źródłowego PS1 nie został wykonany'
 Set-Content -LiteralPath $fixture -Value '$HasloSMB = (''one'' + ''two'')'
 $failed=$false;try{[void](Get-OriginalPasswordLiteral $fixture)}catch{$failed=$true}
 Assert $failed 'wyrażenia hasła odrzucone'
 Set-Content -LiteralPath $fixture -Value ('$HasloSMB = ''first'''+[Environment]::NewLine+'$HasloSMB = ''second''')
 $failed=$false;try{[void](Get-OriginalPasswordLiteral $fixture)}catch{$failed=$true}
 Assert $failed 'niejednoznaczne przypisania odrzucone'
 Set-Content -LiteralPath $fixture -Value '$HasloSMB = ''unfinished'
 $failed=$false;try{[void](Get-OriginalPasswordLiteral $fixture)}catch{$failed=$true}
 Assert $failed 'błąd parsera odrzucony bez ujawnienia treści'
 Write-Host ('RESULT '+$passed+' passed; no Windows GUI/network execution')
}finally{Remove-Item -LiteralPath $fixture,$marker -Force -ErrorAction SilentlyContinue}
