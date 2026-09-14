#requires -Version 5.1
#requires -RunAsAdministrator
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repo = 'TTolsun/hal-camera'
$runnerRoot = 'C:\ProgramData\HALCamera\docgen-runner'
$accountName = 'hal-docgen'
$packageUrl = 'https://github.com/actions/runner/releases/download/v2.337.0/actions-runner-win-x64-2.337.0.zip'
$packageHash = '1150692afa94e71f872017e254ea55b6eece1eece3fe7e3a6d4c93d0a1b85cfc'
$gitExe = 'C:\Program Files\Git\cmd\git.exe'
if (Test-Path -LiteralPath $runnerRoot) { throw "Runner directory already exists: $runnerRoot. Inspect it before retrying." }
if (Get-LocalUser -Name $accountName -ErrorAction SilentlyContinue) { throw "Account already exists: $accountName. Inspect it before retrying." }
if (!(Test-Path -LiteralPath $gitExe)) { throw 'Git for Windows is required.' }
$ollama = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags'
if (!($ollama.models | Where-Object { $_.name -eq 'qwen3.5:4b' })) { throw 'Install qwen3.5:4b in Ollama first.' }
$env:GIT_TERMINAL_PROMPT = '0'
$env:GCM_INTERACTIVE = 'Never'
$credentialLines = "protocol=https`nhost=github.com`n`n" | & $gitExe credential fill
$githubToken = ($credentialLines | Where-Object { $_ -like 'password=*' }) -replace '^password=', ''
$credentialLines = $null
if (!$githubToken) { throw 'The current Windows user has no GitHub credential in Git Credential Manager.' }
$apiHeaders = @{ Authorization = "Bearer $githubToken"; Accept = 'application/vnd.github+json' }
$archive = Join-Path $env:TEMP ('hal-docgen-runner-' + [Guid]::NewGuid().ToString() + '.zip')
try {
    $runnerList = Invoke-RestMethod -Headers $apiHeaders -Uri "https://api.github.com/repos/$repo/actions/runners"
    if ($runnerList.runners | Where-Object { $_.name -eq 'halcamera-docgen-windows' }) { throw 'The named GitHub runner already exists.' }
    Invoke-WebRequest -UseBasicParsing -Uri $packageUrl -OutFile $archive
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $packageHash) { throw 'Runner archive SHA-256 mismatch.' }
    New-Item -ItemType Directory -Path $runnerRoot | Out-Null
    # Private directory: local Administrators and SYSTEM, then the new service user.
    $acl = Get-Acl -LiteralPath $runnerRoot
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @('S-1-5-32-544','S-1-5-18')) {
        $rule = New-Object Security.AccessControl.FileSystemAccessRule([Security.Principal.SecurityIdentifier]::new($sid), 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $runnerRoot -AclObject $acl
    Expand-Archive -LiteralPath $archive -DestinationPath $runnerRoot
    $randomBytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($randomBytes)
    $rng.Dispose()
    $servicePassword = 'Aa1!' + [Convert]::ToBase64String($randomBytes)
    $securePassword = ConvertTo-SecureString $servicePassword -AsPlainText -Force
    $serviceUser = New-LocalUser -Name $accountName -Password $securePassword -AccountNeverExpires -PasswordNeverExpires -UserMayNotChangePassword -Description 'HALCamera documentation runner service only'
    $rule = New-Object Security.AccessControl.FileSystemAccessRule($serviceUser.SID, 'Modify', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $runnerRoot -AclObject $acl
    # No repository secrets are placed on this account. The runner stores only
    # its own service registration credentials using the official installer.
    $registration = Invoke-RestMethod -Method Post -Headers $apiHeaders -Uri "https://api.github.com/repos/$repo/actions/runners/registration-token"
    $env:ACTIONS_RUNNER_INPUT_TOKEN = $registration.token
    $env:ACTIONS_RUNNER_INPUT_WINDOWSLOGONPASSWORD = $servicePassword
    Push-Location -LiteralPath $runnerRoot
    try {
        & .\config.cmd --unattended --url "https://github.com/$repo" --name 'halcamera-docgen-windows' --labels 'docgen-qwen' --work '_work' --runasservice --windowslogonaccount ".\$accountName"
        if ($LASTEXITCODE -ne 0) { throw 'Runner registration or service setup failed.' }
    } finally { Pop-Location }
    $serviceName = (Get-Content -LiteralPath (Join-Path $runnerRoot '.service') -Raw).Trim()
    Get-Service -Name $serviceName | Select-Object Name, Status, StartType
    Write-Output 'Runner installed. Keep DOCGEN_LOCAL_RUNNER_ENABLED unset until workflow checks and repository permissions are verified.'
} finally {
    $env:ACTIONS_RUNNER_INPUT_TOKEN = $null
    $env:ACTIONS_RUNNER_INPUT_WINDOWSLOGONPASSWORD = $null
    $servicePassword = $null
    $securePassword = $null
    $githubToken = $null
    $registration = $null
    $apiHeaders = $null
    if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive }
}
