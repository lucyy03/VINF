param([switch]$Long)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $checks = @('CrawlerChecks', 'CrawlerSafetyChecks', 'BreadthChecks', 'PaginationChecks', 'ExtensionChecks', 'StatisticsChecks', 'RegexExtractorChecks', 'DetailsChecks', 'IndexChecks', 'AlbumChecks', 'PipelineChecks')
    if ($Long) { $checks += 'LongRunChecks' }
    foreach ($checkClass in $checks) {
        & mvn '-Dmaven.repo.local=.maven-repository' compile exec:java "-Dexec.mainClass=$checkClass"
        if ($LASTEXITCODE -ne 0) { throw "$checkClass failed; stopping checks." }
    }
} finally { Pop-Location }
