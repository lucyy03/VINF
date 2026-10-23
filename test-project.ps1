param([switch]$Long, [switch]$Corpus, [switch]$FinalCorpus,
      [string]$MavenRepository = '.maven-repository')
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    # Default checks are self-contained. Corpus checks require separately stored crawl data.
    $checks = @('CrawlerSafetyChecks', 'BreadthChecks', 'PaginationChecks', 'ExtensionChecks', 'StatisticsChecks', 'IndexChecks', 'PortableRegexChecks')
    if ($Corpus) { $checks += @('CrawlerChecks', 'RegexExtractorChecks', 'DetailsChecks', 'AlbumChecks', 'PipelineChecks', 'SavedGraphChecks') }
    if ($FinalCorpus) { $checks += 'FinalCorpusChecks' }
    if ($Long) { $checks += 'LongRunChecks' }
    foreach ($checkClass in $checks) {
        & mvn "-Dmaven.repo.local=$MavenRepository" compile exec:java "-Dexec.mainClass=$checkClass"
        if ($LASTEXITCODE -ne 0) { throw "$checkClass failed; stopping checks." }
    }
} finally { Pop-Location }
