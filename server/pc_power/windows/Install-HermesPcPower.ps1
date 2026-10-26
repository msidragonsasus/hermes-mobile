param(
    [Parameter(Mandatory = $true)][string]$ListenAddress,
    [Parameter(Mandatory = $true)][string]$AllowedClient,
    [ValidateRange(1, 65535)][int]$Port = 9120,
    [string]$SshKey = '',
    [string]$TokenDestination = '',
    [string]$ScpPath = "$env:SystemRoot\System32\OpenSSH\scp.exe"
)

$ErrorActionPreference = 'Stop'

function Get-HermesPcPowerTrustedSids {
    return @('S-1-5-18', 'S-1-5-32-544')
}

function Get-HermesPcPowerEntrySecurity([IO.FileSystemInfo]$Entry) {
    $sections = [Security.AccessControl.AccessControlSections]::Access -bor
        [Security.AccessControl.AccessControlSections]::Owner
    if ($PSVersionTable.PSEdition -eq 'Core') {
        return [IO.FileSystemAclExtensions]::GetAccessControl($Entry, $sections)
    }
    return $Entry.GetAccessControl($sections)
}

function Assert-HermesPcPowerParentPath([string]$Path) {
    $trustedOwners = @(Get-HermesPcPowerTrustedSids) + @(
        'S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464'
    )
    $unsafeRights = [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [Security.AccessControl.FileSystemRights]::Delete -bor
        [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership
    $directory = Get-Item -LiteralPath $Path -Force
    while ($null -ne $directory) {
        # DirectoryInfo.Parent has no PSIsContainer in Windows PowerShell, so check the type.
        if (-not ($directory -is [IO.DirectoryInfo]) -or ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'The installation parent must be a regular, trusted directory.'
        }
        $security = Get-HermesPcPowerEntrySecurity -Entry $directory
        $descriptor = [Security.AccessControl.RawSecurityDescriptor]::new($security.GetSecurityDescriptorBinaryForm(), 0)
        if ($null -eq $descriptor.DiscretionaryAcl) {
            throw 'The installation parent must not have a null DACL.'
        }
        if ($security.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin $trustedOwners) {
            throw 'The installation parent has an untrusted owner.'
        }
        foreach ($rule in $security.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
            if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
                -not ($rule.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly) -and
                $rule.IdentityReference.Value -notin $trustedOwners -and
                ($rule.FileSystemRights -band $unsafeRights)) {
                throw 'The installation parent permits untrusted replacement or permission changes.'
            }
        }
        $directory = $directory.Parent
    }
}

function Assert-HermesPcPowerEntry([string]$Path, [bool]$RequireProtectedDirectory = $false) {
    $entry = Get-Item -LiteralPath $Path -Force
    if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Reparse points are not allowed in the installation.'
    }
    $security = Get-HermesPcPowerEntrySecurity -Entry $entry
    $trustedSids = @(Get-HermesPcPowerTrustedSids)
    if ($security.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin $trustedSids) {
        throw 'An installation entry has an untrusted owner; recover it manually before reinstalling.'
    }
    if ($RequireProtectedDirectory -and (-not $entry.PSIsContainer -or -not $security.AreAccessRulesProtected)) {
        throw 'The installation directory must have protected permissions.'
    }
    $fullControlSids = @()
    foreach ($rule in $security.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) { continue }
        if ($rule.IdentityReference.Value -notin $trustedSids) {
            throw 'An installation entry permits untrusted access; recover it manually before reinstalling.'
        }
        if (($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq
            [Security.AccessControl.FileSystemRights]::FullControl -and
            -not ($rule.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly) -and
            (-not $entry.PSIsContainer -or
                ($rule.InheritanceFlags -band [Security.AccessControl.InheritanceFlags]::ContainerInherit -and
                 $rule.InheritanceFlags -band [Security.AccessControl.InheritanceFlags]::ObjectInherit))) {
            $fullControlSids += $rule.IdentityReference.Value
        }
    }
    foreach ($trustedSid in $trustedSids) {
        if ($trustedSid -notin $fullControlSids) {
            throw 'An installation entry lacks required administrative permissions.'
        }
    }
    if ($entry.PSIsContainer) {
        foreach ($childPath in [IO.Directory]::EnumerateFileSystemEntries($entry.FullName)) {
            Assert-HermesPcPowerEntry -Path $childPath
        }
    }
}

function Initialize-HermesPcPowerDirectory([string]$Path) {
    $security = [Security.AccessControl.DirectorySecurity]::new()
    $security.SetAccessRuleProtection($true, $false)
    $security.SetOwner([Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))
    foreach ($trustedSid in Get-HermesPcPowerTrustedSids) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($trustedSid),
            [Security.AccessControl.FileSystemRights]::FullControl,
            ([Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
                [Security.AccessControl.InheritanceFlags]::ObjectInherit),
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow
        )
        $security.AddAccessRule($rule)
    }
    if ($PSVersionTable.PSEdition -eq 'Core') {
        [IO.FileSystemAclExtensions]::Create([IO.DirectoryInfo]::new($Path), $security)
    } else {
        [IO.Directory]::CreateDirectory($Path, $security) | Out-Null
    }
    Assert-HermesPcPowerEntry -Path $Path -RequireProtectedDirectory $true
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this installer from an elevated PowerShell window.'
}
if (($SshKey.Length -eq 0) -ne ($TokenDestination.Length -eq 0)) {
    throw 'SshKey and TokenDestination must be supplied together.'
}

$programDataDirectory = [Environment]::GetFolderPath([Environment+SpecialFolder]::CommonApplicationData)
Assert-HermesPcPowerParentPath -Path $programDataDirectory
$installDirectory = Join-Path $programDataDirectory 'HermesPcPower'
$serviceScript = Join-Path $installDirectory 'HermesPcPower.ps1'
$tokenFile = Join-Path $installDirectory 'sleep-token'
$operationFile = Join-Path $installDirectory 'completed-operations.txt'
Initialize-HermesPcPowerDirectory -Path $installDirectory
if (Test-Path -LiteralPath $serviceScript) {
    Copy-Item -LiteralPath $serviceScript -Destination "$serviceScript.backup-$(Get-Date -Format yyyyMMddHHmmss)"
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'HermesPcPower.ps1') -Destination $serviceScript -Force

if (-not (Test-Path -LiteralPath $tokenFile)) {
    $bytes = New-Object byte[] 48
    $randomNumberGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $randomNumberGenerator.GetBytes($bytes)
    } finally {
        $randomNumberGenerator.Dispose()
    }
    $token = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    [System.IO.File]::WriteAllText($tokenFile, $token, [System.Text.UTF8Encoding]::new($false))
}
$taskName = 'Hermes PC Power Receiver'
$arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$serviceScript`" -ListenAddress `"$ListenAddress`" -AllowedClient `"$AllowedClient`" -TokenFile `"$tokenFile`" -OperationFile `"$operationFile`" -Port $Port"
$action = New-ScheduledTaskAction -Execute "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -Argument $arguments
$trigger = @(
    (New-ScheduledTaskTrigger -AtStartup)
    (New-ScheduledTaskTrigger -AtLogOn)
    (New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(5) -RepetitionInterval (New-TimeSpan -Minutes 5))
)
$settings = New-ScheduledTaskSettingsSet -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew
Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 500
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -User 'SYSTEM' -RunLevel Highest -Force | Out-Null

$firewallName = 'Hermes PC Power Receiver'
Get-NetFirewallRule -DisplayName $firewallName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $firewallName -Direction Inbound -Action Allow -Protocol TCP -LocalAddress $ListenAddress -LocalPort $Port -RemoteAddress $AllowedClient | Out-Null
Start-ScheduledTask -TaskName $taskName

$listening = $false
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    Start-Sleep -Seconds 1
    $task = Get-ScheduledTask -TaskName $taskName
    $listening = $null -ne (Get-NetTCPConnection -LocalAddress $ListenAddress -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    if ($task.State -eq 'Running' -and $listening) { break }
}
if ($task.State -ne 'Running' -or -not $listening) {
    throw 'Receiver did not start listening. Inspect the scheduled task result before using the sleep button.'
}

if ($SshKey.Length -gt 0) {
    if (-not (Test-Path -LiteralPath $SshKey) -or -not (Test-Path -LiteralPath $ScpPath)) {
        throw 'The SSH key or scp executable does not exist.'
    }
    if ($TokenDestination -notmatch '^[A-Za-z0-9._-]+@[0-9.]+:/tmp/[A-Za-z0-9._-]+/sleep-token$') {
        throw 'TokenDestination is outside the allowed server staging path.'
    }
    & $ScpPath -i $SshKey -o BatchMode=yes -o StrictHostKeyChecking=yes -- $tokenFile $TokenDestination
    if ($LASTEXITCODE -ne 0) { throw 'Secure token transfer failed.' }
}

Write-Output "Installed $taskName on $ListenAddress`:$Port."
Write-Output "The receiver token remains at $tokenFile; do not paste it into chat or commit it."
