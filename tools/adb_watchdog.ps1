[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._:-]+$')]
    [string]$Serial = '103.40.14.100:47866',

    [ValidateRange(5, 300)]
    [int]$IntervalSeconds = 15,

    [ValidateRange(2, 20)]
    [int]$RestartServerAfter = 4,

    [switch]$Once
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$adbExe = (Get-Command adb.exe -ErrorAction Stop).Source
$escapedSerial = [Regex]::Escape($Serial)
$failureCount = 0

function Write-WatchdogLog {
    param([string]$Message)
    $now = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    Write-Host "[$now] $Message"
}

function Invoke-AdbProcess {
    param(
        [string[]]$AdbArgs,
        [int]$TimeoutMs = 10000
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $adbExe
    $startInfo.Arguments = $AdbArgs -join ' '
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    [void]$process.Start()
    if (-not $process.WaitForExit($TimeoutMs)) {
        try { $process.Kill() } catch {}
        $process.Dispose()
        return [pscustomobject]@{ ExitCode = -1; Output = ''; Error = 'timeout' }
    }

    $stdout = $process.StandardOutput.ReadToEnd().Trim()
    $stderr = $process.StandardError.ReadToEnd().Trim()
    $exitCode = $process.ExitCode
    $process.Dispose()
    return [pscustomobject]@{ ExitCode = $exitCode; Output = $stdout; Error = $stderr }
}

function Get-AdbState {
    $result = Invoke-AdbProcess -AdbArgs @('devices')
    if ($result.ExitCode -ne 0) { return 'server-error' }
    $match = [Regex]::Match($result.Output, "(?m)^$escapedSerial\s+(\S+)")
    if (-not $match.Success) { return 'missing' }
    return $match.Groups[1].Value
}

function Test-AdbShell {
    $result = Invoke-AdbProcess -AdbArgs @('-s', $Serial, 'shell', 'true') -TimeoutMs 6000
    return $result.ExitCode -eq 0
}

function Connect-AdbTarget {
    if ($Serial.Contains(':')) {
        [void](Invoke-AdbProcess -AdbArgs @('connect', $Serial) -TimeoutMs 12000)
    }
}

Write-WatchdogLog "watching $Serial with $adbExe"
do {
    $state = Get-AdbState
    $healthy = $state -eq 'device' -and (Test-AdbShell)
    if ($healthy) {
        if ($failureCount -gt 0) { Write-WatchdogLog 'transport recovered' }
        $failureCount = 0
    } else {
        $failureCount++
        Write-WatchdogLog "transport state=$state failure=$failureCount"

        if ($state -eq 'offline') {
            [void](Invoke-AdbProcess -AdbArgs @('reconnect', 'offline') -TimeoutMs 10000)
            Connect-AdbTarget
        } elseif ($state -eq 'missing' -or $state -eq 'server-error') {
            Connect-AdbTarget
        } elseif ($state -eq 'device') {
            [void](Invoke-AdbProcess -AdbArgs @('-s', $Serial, 'reconnect') -TimeoutMs 10000)
        }

        $waitingForAuthorization = $state -eq 'authorizing' -or $state -eq 'unauthorized'
        if (-not $waitingForAuthorization -and ($failureCount % $RestartServerAfter) -eq 0) {
            Write-WatchdogLog 'restarting the host ADB server after repeated failures'
            [void](Invoke-AdbProcess -AdbArgs @('kill-server') -TimeoutMs 10000)
            [void](Invoke-AdbProcess -AdbArgs @('start-server') -TimeoutMs 12000)
            Connect-AdbTarget
        }
    }

    if (-not $Once) { Start-Sleep -Seconds $IntervalSeconds }
} while (-not $Once)
