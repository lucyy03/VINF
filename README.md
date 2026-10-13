# MusicBrainz HTML crawler (Java 17+)

Uses Playwright Chromium to load HTML, follows a bounded BFS through release-group,
release and artist pages, and saves rendered HTML and TSV files. No SQL database.
`html/*.html` contains the browser DOM serialized as UTF-8, not original HTTP bytes.

## PowerShell

For daily use from this project folder:

```powershell
.\run-crawler.ps1
```

If your PowerShell execution policy blocks scripts, use the explicit Maven commands
below instead. The generic Code Runner `javac` command does not load Maven dependencies.

```powershell
mvn "-Dmaven.repo.local=.maven-repository" compile
$env:PLAYWRIGHT_BROWSERS_PATH = "$PWD/.playwright-browsers"
mvn "-Dmaven.repo.local=.maven-repository" exec:java "-Dexec.mainClass=com.microsoft.playwright.CLI" "-Dexec.args=install chromium"
mvn "-Dmaven.repo.local=.maven-repository" exec:java "-Dexec.mainClass=CrawlerChecks"
mvn "-Dmaven.repo.local=.maven-repository" exec:java "-Dexec.mainClass=MusicBrainzCrawler"
```

Install is needed once. Set `PLAYWRIGHT_BROWSERS_PATH` again in a new terminal.
Open this folder as a Maven project in your IDE: plain `javac MusicBrainzCrawler.java`
no longer supplies the required dependencies.

Optionally identify yourself with a real contact before launching the crawler:

```powershell
$env:CRAWLER_CONTACT = "mailto:your-real-address"
```

Replace the example with your actual contact; it is appended to the declared bot
user agent. Do not put passwords or API keys there.

## Crash recovery

Each new run saves `checkpoint.tsv` and `request_state.tsv` inside its output folder.
`crawler-output/latest_checkpoint.tsv` identifies the latest run with a checkpoint.
Keep the whole output folder to recover it, including the HTML and TSV files.

The usual command (or `run-crawler.ps1`) asks `fresh` or `recover` when a checkpoint
exists. For an explicit choice in PowerShell:

```powershell
.\run-crawler.ps1 -Mode fresh
.\run-crawler.ps1 -Mode recover
```

Or append `"-Dexec.args=--fresh"` or `"-Dexec.args=--recover"` to the Maven crawler
command. Run only one of these modes at a time. With no checkpoint, the default
starts fresh; explicit recovery reports that no checkpoint exists.

Recovery continues in the same folder with the saved BFS queue, visited URLs, link
deduplication and page count. A checkpoint commits only after the HTML, page record
and extracted links have been written. Partial TSV appends are rolled back to saved
byte offsets and uncommitted numbered HTML files are removed, so the interrupted
page can be retried under the next ID. Already committed pages are not downloaded
again. Requests sent before a crash cannot be undone, so one unfinished page may
be requested again. The cumulative request count is persisted before each request,
and recovering never resets the 350-request budget. Robots.txt is loaded again and
the last saved request timing and delay apply on restart.

Recovery is for interrupted runs, not completed crawls. Site blocking marks the run
STOPPED and refuses recovery; it does not automatically retry access restrictions.
Changing the start URL, page limit, depth or request budget makes a checkpoint
incompatible. Invalid or missing committed data causes recovery to fail instead of
silently starting over. A file lock prevents concurrent crawler processes.

Checkpoint replacement is atomic and file data is flushed before committing.
This protects against ordinary process crashes and Ctrl+C, but is not a guarantee
against disk failure or every possible power-loss scenario. Runs created before
this feature have no checkpoint and cannot be recovered through this mechanism.

## Request limits

Defaults: up to 100 saved pages, depth 2, 350 network requests including robots.txt and browser
document requests. Requests start at least 3 seconds apart, or longer if robots.txt
requires it. HTTP 403, 429 and server errors stop browser crawling, without retry.
The request budget also includes verification and redirects. Reaching 100 pages is
not guaranteed: blocking, exhausted requests or no more eligible links can stop the run
earlier. Allow at least 5 minutes, and longer for verification and loading.
Robots retrieval fails closed. Chromium uses an isolated session, the declared
SchoolAlbumCrawler user agent, browser sandbox, and no stealth modifications.

External requests and subresources are blocked. Normal inline JavaScript may run,
including the site's standard verification form; its document POST is subject to
the same host, robots, rate and request-budget checks. No custom verification solver,
cookie import, proxy rotation or alternate user-agent identity is used.

A challenge that remains after 30 seconds is saved as `html/blocked.html` and logged
as STOPPED. This is not a successful album download. Playwright does not guarantee
access, and assignment permission to use Playwright is not permission from the site.
If access remains blocked, seek guidance from MusicBrainz instead of escalating retries.

Robots matching supports agent groups, literal prefixes, `*`, terminal `$`, and allow
tie-breaking. It is a small site-focused parser, not a full RFC 9309 implementation
(percent-encoded octet equivalence is not fully implemented).

## References

- https://playwright.dev/java/docs/intro
- https://www.rfc-editor.org/rfc/rfc9309.html

`CrawlerChecks` is an offline regression executable. It reads the saved challenge
from the original run and checks link extraction, origin restrictions and robots rules.
