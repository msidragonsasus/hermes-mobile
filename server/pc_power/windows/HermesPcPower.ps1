param(
    [Parameter(Mandatory = $true)][string]$ListenAddress,
    [Parameter(Mandatory = $true)][string]$AllowedClient,
    [Parameter(Mandatory = $true)][string]$TokenFile,
    [Parameter(Mandatory = $true)][string]$OperationFile,
    [ValidateRange(1, 65535)][int]$Port = 9120
)

$ErrorActionPreference = 'Stop'

if (-not [System.Net.IPAddress]::TryParse($ListenAddress, [ref]([System.Net.IPAddress]$null))) {
    throw 'ListenAddress must be an IP address.'
}
if (-not [System.Net.IPAddress]::TryParse($AllowedClient, [ref]([System.Net.IPAddress]$null))) {
    throw 'AllowedClient must be an IP address.'
}

$token = (Get-Content -LiteralPath $TokenFile -Raw -Encoding UTF8).Trim()
if ($token.Length -lt 32 -or $token.Length -gt 256 -or $token -match '\s') {
    throw 'Token file is invalid.'
}
$completedOperations = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
if (Test-Path -LiteralPath $OperationFile) {
    Get-Content -LiteralPath $OperationFile -Encoding UTF8 | Select-Object -Last 256 | ForEach-Object {
        if ($_ -match '^[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,40}$') { [void]$completedOperations.Add($_) }
    }
}

Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class HermesPowerState {
    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    private struct TokenPrivileges {
        public uint Count;
        public long Luid;
        public uint Attributes;
    }
    [DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool LookupPrivilegeValue(string system, string name, out long luid);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool AdjustTokenPrivileges(IntPtr token, bool disableAll, ref TokenPrivileges privileges,
        uint length, IntPtr previous, IntPtr required);
    public static int EnableShutdownPrivilege() {
        IntPtr token;
        if (!OpenProcessToken(GetCurrentProcess(), 0x20 | 0x8, out token)) return Marshal.GetLastWin32Error();
        try {
            long luid;
            if (!LookupPrivilegeValue(null, "SeShutdownPrivilege", out luid)) return Marshal.GetLastWin32Error();
            var privileges = new TokenPrivileges { Count = 1, Luid = luid, Attributes = 2 };
            if (!AdjustTokenPrivileges(token, false, ref privileges, 0, IntPtr.Zero, IntPtr.Zero))
                return Marshal.GetLastWin32Error();
            return Marshal.GetLastWin32Error(); // Includes ERROR_NOT_ALL_ASSIGNED despite a nonzero return.
        } finally { CloseHandle(token); }
    }
    [DllImport("powrprof.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.U1)]
    public static extern bool SetSuspendState([MarshalAs(UnmanagedType.U1)] bool hibernate,
        [MarshalAs(UnmanagedType.U1)] bool forceCritical, [MarshalAs(UnmanagedType.U1)] bool disableWakeEvent);
}
'@

$powerError = [HermesPowerState]::EnableShutdownPrivilege()
$statusFile = "$OperationFile.status.json"
$lastOperation = $null
if (Test-Path -LiteralPath $statusFile) {
    $lastOperation = Get-Content -LiteralPath $statusFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($lastOperation.state -eq 'pending') {
        $lastOperation.state = 'unknown' # A restart must never replay a previously accepted request.
    }
}

function Save-OperationStatus([string]$Id, [string]$State, [int]$NativeError = 0) {
    $script:lastOperation = @{ operation_id = $Id; state = $State; native_error = $NativeError }
    $json = $script:lastOperation | ConvertTo-Json -Compress
    [IO.File]::WriteAllText("$statusFile.tmp", $json, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$statusFile.tmp" -Destination $statusFile -Force
}

function Test-FixedTimeToken([string]$Actual, [string]$Expected) {
    $actualBytes = [System.Text.Encoding]::UTF8.GetBytes($Actual)
    $expectedBytes = [System.Text.Encoding]::UTF8.GetBytes($Expected)
    if ($actualBytes.Length -ne $expectedBytes.Length) { return $false }
    $difference = 0
    for ($index = 0; $index -lt $actualBytes.Length; $index++) {
        $difference = $difference -bor ($actualBytes[$index] -bxor $expectedBytes[$index])
    }
    return $difference -eq 0
}

function Write-HttpResponse($Stream, [int]$Status, [string]$Reason, [hashtable]$Payload) {
    $body = [System.Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Compress))
    $head = [System.Text.Encoding]::ASCII.GetBytes(
        "HTTP/1.1 $Status $Reason`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n"
    )
    $Stream.Write($head, 0, $head.Length)
    $Stream.Write($body, 0, $body.Length)
    $Stream.Flush()
}

# Tailscale may acquire its address after the startup task has already started.
for ($attempt = 0; ; $attempt++) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Parse($ListenAddress), $Port)
    try { $listener.Start(); break } catch {
        $listener.Stop()
        if ($attempt -ge 29) { throw }
        Start-Sleep -Seconds 2
    }
}
try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $client.ReceiveTimeout = 5000
            $client.SendTimeout = 5000
            $remoteAddress = ([System.Net.IPEndPoint]$client.Client.RemoteEndPoint).Address.ToString()
            $stream = $client.GetStream()
            if ($remoteAddress -ne $AllowedClient) {
                Write-HttpResponse $stream 403 'Forbidden' @{ error = 'forbidden' }
                continue
            }

            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 1024, $true)
            $requestLine = $reader.ReadLine()
            if ($null -eq $requestLine -or $requestLine.Length -gt 256) {
                Write-HttpResponse $stream 400 'Bad Request' @{ error = 'invalid_request' }
                continue
            }
            $headers = @{}
            $headerBytes = $requestLine.Length
            for ($lineCount = 0; $lineCount -lt 40; $lineCount++) {
                $line = $reader.ReadLine()
                if ($null -eq $line) { break }
                $headerBytes += $line.Length
                if ($headerBytes -gt 8192) { throw 'Headers too large.' }
                if ($line.Length -eq 0) { break }
                $separator = $line.IndexOf(':')
                if ($separator -gt 0) {
                    $headers[$line.Substring(0, $separator).Trim().ToLowerInvariant()] = $line.Substring($separator + 1).Trim()
                }
            }

            $parts = $requestLine.Split(' ')
            if ($parts.Length -ne 3 -or $parts[1] -notin @('/health', '/sleep')) {
                Write-HttpResponse $stream 404 'Not Found' @{ error = 'not_found' }
                continue
            }
            $authorization = [string]$headers['authorization']
            if (-not $authorization.StartsWith('Bearer ') -or -not (Test-FixedTimeToken $authorization.Substring(7) $token)) {
                Write-HttpResponse $stream 401 'Unauthorized' @{ error = 'unauthorized' }
                continue
            }
            if ($parts[0] -eq 'GET' -and $parts[1] -eq '/health') {
                Write-HttpResponse $stream 200 'OK' @{
                    ready = ($powerError -eq 0)
                    error = $(if ($powerError -ne 0) { 'shutdown_privilege_missing' } else { $null })
                    last_operation = $lastOperation
                }
                continue
            }
            if ($parts[0] -ne 'POST' -or $parts[1] -ne '/sleep') {
                Write-HttpResponse $stream 405 'Method Not Allowed' @{ error = 'method_not_allowed' }
                continue
            }
            $contentLength = 0
            if (-not [int]::TryParse([string]$headers['content-length'], [ref]$contentLength) -or $contentLength -lt 2 -or $contentLength -gt 1024) {
                Write-HttpResponse $stream 400 'Bad Request' @{ error = 'invalid_length' }
                continue
            }
            $characters = New-Object char[] $contentLength
            $read = 0
            while ($read -lt $contentLength) {
                $count = $reader.Read($characters, $read, $contentLength - $read)
                if ($count -le 0) { break }
                $read += $count
            }
            if ($read -ne $contentLength) {
                Write-HttpResponse $stream 400 'Bad Request' @{ error = 'incomplete_body' }
                continue
            }
            try { $payload = (-join $characters) | ConvertFrom-Json } catch { $payload = $null }
            $operationId = [string]$payload.operation_id
            if ($operationId -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,40}$') {
                Write-HttpResponse $stream 400 'Bad Request' @{ error = 'invalid_operation_id' }
                continue
            }

            if ($completedOperations.Contains($operationId)) {
                if ($lastOperation.operation_id -eq $operationId -and $lastOperation.state -eq 'failed') {
                    Write-HttpResponse $stream 409 'Conflict' @{ error = 'sleep_failed'; accepted = $false }
                    continue
                }
                Write-HttpResponse $stream 202 'Accepted' @{ accepted = $true; operation_id = $operationId; duplicate = $true }
                continue
            }
            if ($powerError -ne 0) {
                Write-HttpResponse $stream 503 'Service Unavailable' @{ error = 'shutdown_privilege_missing' }
                continue
            }
            Save-OperationStatus $operationId 'pending'
            Add-Content -LiteralPath $OperationFile -Value $operationId -Encoding UTF8
            [void]$completedOperations.Add($operationId)

            Write-HttpResponse $stream 202 'Accepted' @{ accepted = $true; operation_id = $operationId }
            $client.Close()
            Start-Sleep -Milliseconds 750
            try {
                if (-not [HermesPowerState]::SetSuspendState($false, $false, $false)) {
                    $nativeError = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
                    Save-OperationStatus $operationId 'failed' $nativeError
                } else {
                    Save-OperationStatus $operationId 'resumed'
                }
            } catch {
                Save-OperationStatus $operationId 'failed' -1
            }
        } catch {
            try { Write-HttpResponse $client.GetStream() 500 'Internal Server Error' @{ error = 'request_failed' } } catch {}
        } finally {
            $client.Dispose()
        }
    }
} finally {
    $listener.Stop()
}
