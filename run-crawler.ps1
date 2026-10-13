param([ValidateSet('fresh', 'recover')][string]$Mode)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $PSScriptRoot '.playwright-browsers'
    $mavenArguments = @('-Dmaven.repo.local=.maven-repository', 'compile', 'exec:java', '-Dexec.mainClass=MusicBrainzCrawler')
    if ($Mode) { $mavenArguments += "-Dexec.args=--$Mode" }
    & mvn @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Crawler or Maven failed (exit code $LASTEXITCODE). See the output above."
    }
} finally {
    Pop-Location
}
