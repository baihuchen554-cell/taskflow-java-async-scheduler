$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
# Spring Boot 3.5 supports Java 17-25. Prefer a compatible installed JDK.
$candidates = @($env:JAVA_HOME)
$candidates += @(Get-ChildItem "$env:USERPROFILE/.jdks", "$env:ProgramFiles/Java", "$env:ProgramFiles/Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue | ForEach-Object FullName)
foreach ($candidate in $candidates) {
    if (-not $candidate -or -not (Test-Path "$candidate/release") -or -not (Test-Path "$candidate/bin/javac.exe")) { continue }
    $versionLine = Get-Content "$candidate/release" | Where-Object { $_ -match '^JAVA_VERSION=' }
    if ($versionLine -match '"(\d+)') {
        $major = [int]$Matches[1]
        if ($major -ge 17 -and $major -le 25) {
            $env:JAVA_HOME = $candidate
            $env:PATH = "$candidate/bin;$env:PATH"
            return
        }
    }
}
throw 'Please install JDK 21 (or JDK 17-25), then set JAVA_HOME to its installation directory.'
