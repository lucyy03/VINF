param([Parameter(Mandatory=$true)][string]$Run)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $crawlRun = (Resolve-Path -LiteralPath $Run).Path
    $reportRun = Join-Path (Join-Path $PSScriptRoot 'analysis-output') (Split-Path $crawlRun -Leaf)
    foreach ($analysisClass in @('CrawlStatistics', 'MusicBrainzRegexExtractor', 'MusicBrainzIndexer')) {
        $analysisInput = if ($analysisClass -eq 'MusicBrainzIndexer') { Join-Path $reportRun 'regex' } else { $crawlRun }
        & mvn '-Dmaven.repo.local=.maven-repository' compile exec:java "-Dexec.mainClass=$analysisClass" ('-Dexec.args="' + $analysisInput + '"')
        if ($LASTEXITCODE -ne 0) { throw "$analysisClass failed; later steps were not run." }
    }
} finally { Pop-Location }
