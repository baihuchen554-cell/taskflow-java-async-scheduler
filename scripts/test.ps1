. "$PSScriptRoot/java-env.ps1"
Set-Location $projectDir
& "$projectDir/mvnw.cmd" test
exit $LASTEXITCODE
