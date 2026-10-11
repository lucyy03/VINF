$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $PSScriptRoot '.playwright-browsers'
    & mvn '-Dmaven.repo.local=.maven-repository' compile exec:java '-Dexec.mainClass=MusicBrainzCrawler'
    if ($LASTEXITCODE -ne 0) {
        throw "Crawler or Maven failed (exit code $LASTEXITCODE). See the output above."
    }
} finally {
    Pop-Location
}
