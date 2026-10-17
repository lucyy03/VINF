param(
    [ValidateSet('fresh', 'recover')][string]$Mode,
    [ValidateRange(1, 1000000)][int]$Pages,
    [ValidateRange(0, 1000)][int]$Depth,
    [ValidateRange(1, 2147483647)][int]$MaxRequests,
    [ValidateRange(1, 1000000)][int]$MaxFrontier,
    [ValidateRange(128, 2147483647)][int]$MinFreeMiB
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $PSScriptRoot '.playwright-browsers'
    $mavenArguments = @('-Dmaven.repo.local=.maven-repository', 'compile', 'exec:java', '-Dexec.mainClass=MusicBrainzCrawler')
    $crawlerArguments = @()
    if ($Mode) { $crawlerArguments += "--$Mode" }
    foreach ($option in @{'Pages'='--pages'; 'Depth'='--depth'; 'MaxRequests'='--max-requests'; 'MaxFrontier'='--max-frontier'; 'MinFreeMiB'='--min-free-mib'}.GetEnumerator()) {
        if ($PSBoundParameters.ContainsKey($option.Key)) {
            $crawlerArguments += $option.Value
            $crawlerArguments += [string]$PSBoundParameters[$option.Key]
        }
    }
    if ($crawlerArguments.Count -gt 0) { $mavenArguments += ('-Dexec.args=' + ($crawlerArguments -join ' ')) }
    & mvn @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Crawler or Maven failed (exit code $LASTEXITCODE). See the output above."
    }
} finally {
    Pop-Location
}
