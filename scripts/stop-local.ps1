$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$localDir = Join-Path $projectDir '.local'
# Verify the command line before stopping a saved PID (PIDs can be reused).
$appPidFile = Join-Path $localDir 'app.pid'
if (Test-Path $appPidFile) {
    $appProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $(Get-Content $appPidFile -Raw)" -ErrorAction SilentlyContinue
    if ($appProcess -and ($appProcess.CommandLine.Contains("$projectDir/target/taskflow-1.0.0.jar") -or $appProcess.CommandLine.Contains("$localDir/app.jar"))) {
        Stop-Process -Id $appProcess.ProcessId
        Write-Host 'TaskFlow stopped; in-flight work will be recovered on restart.'
    }
    Remove-Item -LiteralPath $appPidFile
}
$configPath = Join-Path $localDir 'database.json'
if (Test-Path $configPath) {
    $db = Get-Content $configPath -Raw | ConvertFrom-Json
    $mysqlHome = if ($db.mysqlHome) { $db.mysqlHome } else { 'C:/Program Files/MySQL/MySQL Server 8.4' }
    $mysqlAdmin = Join-Path $mysqlHome 'bin/mysqladmin.exe'
    if (Test-Path $mysqlAdmin) {
        $oldPassword = $env:MYSQL_PWD
        try {
            $env:MYSQL_PWD = $db.rootPassword
            & $mysqlAdmin --no-defaults --host=127.0.0.1 "--port=$($db.port)" --user=root shutdown
        } finally { $env:MYSQL_PWD = $oldPassword }
    }
}
