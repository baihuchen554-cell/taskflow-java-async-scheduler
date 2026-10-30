param([switch]$Background, [int]$Port = 8080, [string]$MySqlHome = 'C:/Program Files/MySQL/MySQL Server 8.4')
. "$PSScriptRoot/java-env.ps1"
Set-Location $projectDir
$localDir = Join-Path $projectDir '.local'
$dataDir = Join-Path $localDir 'mysql'
$configPath = Join-Path $localDir 'database.json'
$mysqlExe = Join-Path $MySqlHome 'bin/mysqld.exe'
if (-not (Test-Path $mysqlExe)) { throw 'MySQL 8.4 not found. Set -MySqlHome, use Docker Compose, or run scripts/start-demo.ps1.' }
New-Item -ItemType Directory -Force $localDir | Out-Null
# Windows MySQL cannot reliably parse Chinese paths. An ASCII junction still stores
# all project data in .local, without changing any installed MySQL service.
$runtimeDir = $localDir
if ($localDir -match '[^\x00-\x7F]') {
    $hash = [System.Security.Cryptography.SHA256]::Create()
    $key = ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($projectDir)))).Replace('-', '').Substring(0, 16)
    $hash.Dispose()
    $runtimeDir = Join-Path $env:LOCALAPPDATA "TaskFlow/$key"
    if ($runtimeDir -match '[^\x00-\x7F]') { throw 'Please move this project to an ASCII path, or use Docker Compose.' }
    if (-not (Test-Path $runtimeDir)) {
        New-Item -ItemType Directory -Force (Split-Path $runtimeDir) | Out-Null
        New-Item -ItemType Junction -Path $runtimeDir -Target $localDir | Out-Null
    } elseif ((Get-Item $runtimeDir).Target -ne $localDir) { throw 'Runtime junction points to another directory.' }
}
$dataDir = Join-Path $runtimeDir 'mysql'
if (-not (Test-Path $configPath)) {
    @{ port = 3308; username = 'taskflow'; password = [guid]::NewGuid().ToString('N'); rootPassword = [guid]::NewGuid().ToString('N') } | ConvertTo-Json | Set-Content $configPath -Encoding UTF8
}
$db = Get-Content $configPath -Raw | ConvertFrom-Json
$db | Add-Member -NotePropertyName mysqlHome -NotePropertyValue $MySqlHome -Force
$db | ConvertTo-Json | Set-Content $configPath -Encoding UTF8
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw "Port $Port is already in use. Stop the app before rebuilding and restarting."
}
$listener = Get-NetTCPConnection -State Listen -LocalPort $db.port -ErrorAction SilentlyContinue
if ($listener) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener[0].OwningProcess)"
    if (-not $process.CommandLine -or -not $process.CommandLine.Contains($dataDir)) {
        throw "Port $($db.port) is used by another process. Stop it or change port in .local/database.json."
    }
} else {
    if (-not (Test-Path (Join-Path $dataDir 'mysql'))) {
        New-Item -ItemType Directory -Force $dataDir | Out-Null
        & $mysqlExe --no-defaults --initialize-insecure "--datadir=$dataDir" --console
        if ($LASTEXITCODE -ne 0) { throw 'MySQL initialization failed.' }
    }
    # This instance owns only .local/mysql; installed MySQL services are untouched.
    $initPath = Join-Path $runtimeDir 'bootstrap.sql'
    @"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$($db.rootPassword)';
CREATE DATABASE IF NOT EXISTS taskflow CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'taskflow'@'localhost' IDENTIFIED BY '$($db.password)';
GRANT ALL PRIVILEGES ON taskflow.* TO 'taskflow'@'localhost';
"@ | Set-Content $initPath -Encoding ASCII
    $argsList = @('--no-defaults', "--datadir=`"$dataDir`"", "--port=$($db.port)", '--bind-address=127.0.0.1', '--mysqlx=OFF', '--default-time-zone=+00:00', "--init-file=`"$initPath`"", "--log-error=`"$runtimeDir/mysql.log`"")
    $mysqlProcess = Start-Process $mysqlExe -ArgumentList $argsList -PassThru -WindowStyle Hidden
    $mysqlProcess.Id | Set-Content (Join-Path $localDir 'mysql.pid')
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        if ($mysqlProcess.HasExited) { throw "MySQL stopped. Check $localDir/mysql.log" }
        if (Get-NetTCPConnection -State Listen -LocalPort $db.port -ErrorAction SilentlyContinue) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Timed out waiting for local MySQL.' }
    Remove-Item -LiteralPath $initPath
}
$env:DB_URL = "jdbc:mysql://127.0.0.1:$($db.port)/taskflow?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
$env:DB_USERNAME = $db.username
$env:DB_PASSWORD = $db.password
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = "$Port"
$env:SPRING_PROFILES_ACTIVE = 'default'
& "$projectDir/mvnw.cmd" -B package -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
Write-Host "TaskFlow (MySQL): http://localhost:$Port"
if ($Background) {
    if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw "Port $Port is already in use." }
    # 从运行副本启动，避免 Windows 文件锁妨碍后续 Maven 打包。
    Copy-Item "$projectDir/target/taskflow-1.0.0.jar" "$localDir/app.jar" -Force
    $appProcess = Start-Process "$env:JAVA_HOME/bin/java.exe" -ArgumentList @('-jar', "`"$localDir/app.jar`"") -PassThru -WindowStyle Hidden -RedirectStandardOutput "$localDir/app.log" -RedirectStandardError "$localDir/app-error.log"
    $appProcess.Id | Set-Content "$localDir/app.pid"
    for ($i = 0; $i -lt 60; $i++) {
        if ($appProcess.HasExited) { throw "App stopped. Check $localDir/app.log and app-error.log." }
        try {
            $null = Invoke-RestMethod "http://localhost:$Port/api/system"
            Write-Host 'Ready. Run scripts/stop-local.ps1 to stop this project.'
            exit 0
        } catch { Start-Sleep -Seconds 1 }
    }
    throw 'App readiness timed out; inspect .local/app.log.'
}
Copy-Item "$projectDir/target/taskflow-1.0.0.jar" "$localDir/app.jar" -Force
& "$env:JAVA_HOME/bin/java.exe" -jar "$localDir/app.jar"
exit $LASTEXITCODE
