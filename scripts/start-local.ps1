param(
    [int]$Port = 30002
)

$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $backendRoot '.env.local'
$jarFile = Join-Path $backendRoot 'target\xander-lab-backend-0.0.1-SNAPSHOT.jar'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing $envFile. Copy the local environment values into this ignored file first."
}

if (-not (Test-Path -LiteralPath $jarFile)) {
    throw "Missing $jarFile. Run: mvn -q -DskipTests package"
}

$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    throw "Port $Port is already in use by process $($listener[0].OwningProcess)."
}

Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith('#')) {
        return
    }

    $parts = $line -split '=', 2
    if ($parts.Count -ne 2) {
        return
    }

    [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1], 'Process')
}

$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    $javaHome = 'C:\Program Files\Microsoft\jdk-17.0.17.10-hotspot'
}
$java = Join-Path $javaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) {
    throw "Java 17 was not found at $java. Set JAVA_HOME before starting the backend."
}

$stdout = Join-Path $backendRoot 'backend.local.out.log'
$stderr = Join-Path $backendRoot 'backend.local.err.log'
$process = Start-Process `
    -FilePath $java `
    -ArgumentList '-jar', $jarFile, '--spring.profiles.active=local', "--server.port=$Port" `
    -WorkingDirectory $backendRoot `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr `
    -WindowStyle Hidden `
    -PassThru

Write-Output "Started local backend PID $($process.Id) on http://localhost:$Port"
