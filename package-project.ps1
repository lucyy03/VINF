$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot = $PSScriptRoot
$target = Join-Path $projectRoot 'target'
[void][System.IO.Directory]::CreateDirectory($target)
$archivePath = Join-Path $target ('musicbrainz-project-code-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.zip')
$sourceFiles = @(Get-ChildItem -LiteralPath $projectRoot -File | Where-Object {
    $_.Extension -in '.java', '.ps1' -or $_.Name -in 'pom.xml', 'crawl-seeds.tsv', '.gitattributes'
})
$sourceFiles += @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'test-fixtures') -File -Filter '*.tsv')
$htmlFixtures = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'test-fixtures/final-sample/html') -File -Filter '*.html')
if ($htmlFixtures.Count -ne 20) { throw 'Expected exactly 20 portable HTML fixtures.' }
$sourceFiles += $htmlFixtures
foreach ($sourceFile in $sourceFiles) {
    if ([System.IO.File]::ReadAllText($sourceFile.FullName) -match '\b(?:pk|sk)\.[A-Za-z0-9_.-]{20,}') {
        throw "Mapbox token-shaped value found; redact before packaging: $($sourceFile.Name)"
    }
}
$archive = [System.IO.Compression.ZipFile]::Open($archivePath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($sourceFile in ($sourceFiles | Sort-Object FullName)) {
        $entryName = $sourceFile.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $archive, $sourceFile.FullName, $entryName, [System.IO.Compression.CompressionLevel]::Optimal)
    }
} finally { $archive.Dispose() }
$verify = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    if ($verify.Entries.Count -ne $sourceFiles.Count) { throw 'Archive entry count mismatch.' }
    foreach ($entry in $verify.Entries) {
        if ($entry.FullName -notmatch '^([^/]+\.(java|ps1)|pom\.xml|crawl-seeds\.tsv|\.gitattributes|test-fixtures/[^/]+\.tsv|test-fixtures/final-sample/html/[0-9]+\.html)$') {
            throw "Unexpected archive entry: $($entry.FullName)"
        }
    }
} finally { $verify.Dispose() }
Write-Output "Source ZIP: $archivePath"
Write-Output "Verified files: $($sourceFiles.Count)"
Write-Output 'Includes 20 copied final-corpus HTML fixtures for the default offline tests.'
Write-Output 'Dependencies, browsers, the full corpus and generated indexes are not included.'
Write-Output 'Default tests: .\test-project.ps1; optional external data tests: -Corpus or -FinalCorpus.'
