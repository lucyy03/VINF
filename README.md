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

The user agent identifies this as an educational crawler. No email is requested
or required. The browser has no stealth modifications or imported cookies.

## Offline statistics

Run this while the crawler is stopped; it makes no network requests:

```powershell
mvn "-Dmaven.repo.local=.maven-repository" compile exec:java "-Dexec.mainClass=CrawlStatistics" "-Dexec.args=crawler-output/2026-10-13_16-45-01_7721259650104813825"
```

The report is saved separately in `analysis-output/<run-name>/statistics.tsv` and
`document_audit.tsv`. Existing HTML, crawl TSVs and checkpoints are not rewritten.
`html_bytes` counts all HTML files; `all_files_bytes` counts all regular files in
that run, including checkpoints and metadata. These are logical file lengths, not
filesystem allocation sizes. Divide bytes by 1,048,576 for MiB or 1,000,000 for MB.

`valid_entity_documents` requires a unique MusicBrainz entity URL/file, a successful
HTML response and a complete-looking MusicBrainz document that is not a known
challenge page. This is an input-quality check, not an assessment of relevance to
the project or proof that every entity field was extracted correctly. The per-file
audit identifies missing HTML, duplicate entries, challenges and malformed rows.
Token counts are not implemented yet and are not approximated from word counts.

Offline checks:

```powershell
mvn "-Dmaven.repo.local=.maven-repository" compile exec:java "-Dexec.mainClass=StatisticsChecks"
```

## Long runs and command-line configuration

The crawler is not capped at 100 pages. The default target is 1,000, and the target
can be changed without editing or recompiling the source by hand:

```powershell
.\run-crawler.ps1 -Mode fresh -Pages 10000 -Depth 10
```

Equivalent Maven command after setting `PLAYWRIGHT_BROWSERS_PATH` as above:

```powershell
mvn "-Dmaven.repo.local=.maven-repository" compile exec:java "-Dexec.mainClass=MusicBrainzCrawler" "-Dexec.args=--fresh --pages 10000 --depth 10"
```

Options:

| Java argument | PowerShell launcher | Meaning |
|---|---|---|
| `--fresh` / `--recover` | `-Mode fresh` / `-Mode recover` | New run or crash recovery |
| `--pages N` | `-Pages N` | Target successful pages; default 1,000 |
| `--depth N` | `-Depth N` | Maximum BFS depth; default 10 |
| `--max-requests N` | `-MaxRequests N` | Cumulative request budget; default `4 * pages + 100` |
| `--max-frontier N` | `-MaxFrontier N` | Pending queue cap; default 20,000 |
| `--min-free-mib N` | `-MinFreeMiB N` | Free-space floor; default 512 MiB, minimum 128 |
| `--start-url URL` | Use Maven | Initial HTTPS entity URL on musicbrainz.org |
| `--help` | Use Maven | Print usage without crawling |

For a target of 10,000 the default budget is 40,100 requests, including robots,
verification, redirects and failed requests. More pages do not mean a higher request
rate. Ten thousand single-request pages take at least roughly 8 h 20 min with the
3-second spacing; verification and loading extend this. Existing 100-page HTML is
62.06 MiB, so a proportional extrapolation is about 6.06 GiB for 10,000 HTML pages,
plus metadata. Actual sizes can differ substantially.

The pending frontier is bounded. When full, additional links are still recorded in
TSV with `queued_for_crawl=false`, but not scheduled at that moment. They may be seen
again later. This is a bounded discovery crawl, not a promise to exhaust every link
on MusicBrainz. Terminal states distinguish target completion, link/depth exhaustion
and request-budget exhaustion. `run_summary.tsv` records the outcome and remaining
frontier. `frontier_deferred_this_process` is a diagnostic for the current process,
not a cumulative count across recoveries.

## Crash recovery

Keep the entire output folder: HTML, crawl TSVs, `request_state.tsv`,
`checkpoint.tsv`, `checkpoint-base.tsv`, and `checkpoint-journal/`.
`crawler-output/latest_checkpoint.tsv` points to the latest run.

```powershell
.\run-crawler.ps1 -Mode recover
```

Recovery loads the saved target, depth and other settings automatically. Do not
repeat configuration arguments unless they exactly match. It reuses the same
folder, numbering and BFS queue, and never resets the cumulative request budget.
The usual command without a mode asks `fresh` or `recover` if a checkpoint exists.

Each page commit flushes its HTML and TSV data, writes a small journal delta and
atomically replaces a small manifest. Historical edges are stored in TSV, not kept
in growing global sets or copied into every checkpoint. Recovery replays only
committed deltas, trims uncommitted TSV appends and removes uncommitted numbered
HTML. One interrupted network request/page can be repeated; committed pages are
not downloaded again. Uncommitted journal files are ignored and replaced on retry.

Ctrl+C or a process crash uses the last committed checkpoint. Alternatively create
an empty `stop.request` in the run folder for a pause between pages, then remove it
before recovery. Low free space stops before the next page and preserves recovery;
free disk space before restarting. File locking prevents simultaneous production
crawler instances. Keep the machine awake and connected for unattended operation.

Old version-1 ACTIVE checkpoints are read using their original settings and upgraded
when recovery commits. Completed, exhausted and site-blocked runs are not crash
recovery candidates. Runs without any checkpoint cannot be recovered. This protects
ordinary process interruptions, not arbitrary disk damage or every power-loss case.

## Request policy and browser lifecycle

Requests are sequential and start at least 3 seconds apart, or longer for crawl-delay.
Robots are loaded on restart and refreshed hourly during a long run, including a
check before browser requests when due. Failed policy retrieval stops access;
403/429/server errors and persistent browser challenges are not automatically retried.
The robots document is capped at 1 MiB while receiving; page responses/serialized DOMs
larger than 25 MiB are rejected (browser responses have already been buffered when
that size check runs). Disk checks reserve the free-space floor plus one page.

Only main-frame MusicBrainz entity navigation and the site's standard verification
form are allowed. External hosts, subresources, subframes, service workers and
WebSockets are blocked. Redirect GETs are routed through the same checks and budget.
The browser is sandboxed, has an isolated session and declared educational user agent,
with no stealth modifications, email requirement, cookie imports or proxy rotation.
Inline scripts run normally; there is no custom verification solver.

Chromium is closed and recreated after each 250 committed pages to bound session
lifetime and release browser/API resources. `request_log.tsv` records request
reservations; a reservation may exist even if a crash prevents sending the request.
The durable counter is conservative and is never decreased on recovery.

Respecting robots is not authorization to scrape; see [RFC 9309](https://www.rfc-editor.org/rfc/rfc9309.html).
No test guarantees uninterrupted access to MusicBrainz, absence of blocking, or
permission from the operator. Do not run multiple copies or force retries after a block.

## Offline validation

```powershell
mvn "-Dmaven.repo.local=.maven-repository" compile exec:java "-Dexec.mainClass=CrawlerChecks"
mvn "-Dmaven.repo.local=.maven-repository" exec:java "-Dexec.mainClass=CrawlerSafetyChecks"
mvn "-Dmaven.repo.local=.maven-repository" exec:java "-Dexec.mainClass=LongRunChecks"
```

`LongRunChecks` drives the actual crawl/commit loop with 10,000 synthetic documents,
simulates a crash after 4,321 committed pages, adds partial output, resets process
state, recovers and verifies numbering, unique documents, all HTML files, request
counts, policy refresh and final journal replay. Its outputs and evidence stay under
`target/`. It makes no HTTP requests and does not launch Chromium. Synthetic pages
are small: this tests control flow and persistence, not transferring 10,000 live pages
or a multi-gigabyte browser-memory endurance benchmark.

`CrawlerSafetyChecks` covers bounded frontier progress, depth exhaustion, hard request
budget, 429 recovery refusal, changed robots rules, disk-space stops, URL/robots
encoding and oversized robots response cancellation. With `-Dexec.args=--browser`
it additionally launches/closes installed Chromium twice and verifies that external
navigation is refused before any crawler request is sent.

## References

- https://playwright.dev/java/docs/intro
- https://www.rfc-editor.org/rfc/rfc9309.html

`CrawlerChecks` is an offline regression executable. It reads the saved challenge
from the original run and checks link extraction, origin restrictions and robots rules.
