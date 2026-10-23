$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot = $PSScriptRoot
$target = Join-Path $projectRoot 'target'
[void][System.IO.Directory]::CreateDirectory($target)
$archivePath = Join-Path $target ('musicbrainz-project-code-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.zip')
$sourceFiles = @(Get-ChildItem -LiteralPath $projectRoot -File | Where-Object {
    $_.Extension -in '.java', '.ps1' -or $_.Name -in 'pom.xml', 'crawl-seeds.tsv'
})
$sourceFiles += @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'test-fixtures') -File -Filter '*.tsv')
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
        if ($entry.FullName -notmatch '^([^/]+\.(java|ps1)|pom\.xml|crawl-seeds\.tsv|test-fixtures/[^/]+\.tsv)$') {
            throw "Unexpected archive entry: $($entry.FullName)"
        }
    }
} finally { $verify.Dispose() }
Write-Output "Source ZIP: $archivePath"
Write-Output "Verified files: $($sourceFiles.Count)"
Write-Output 'Dependencies, browsers, downloaded HTML and generated indexes are not included.'
