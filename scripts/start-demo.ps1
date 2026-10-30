param([int]$Port = 8080)
. "$PSScriptRoot/java-env.ps1"
Set-Location $projectDir
$env:SERVER_PORT = "$Port"
$env:SERVER_ADDRESS = '127.0.0.1'
Write-Host "TaskFlow demo (H2): http://localhost:$Port"
& "$projectDir/mvnw.cmd" spring-boot:run '-Dspring-boot.run.profiles=demo'
exit $LASTEXITCODE
