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

## Offline REGEX extractor

Extract the already saved English MusicBrainz entity pages without launching the
browser or downloading anything. Stop the crawler first:

```powershell
mvn "-Dmaven.repo.local=.maven-repository" compile exec:java "-Dexec.mainClass=MusicBrainzRegexExtractor" "-Dexec.args=crawler-output/2026-10-21_09-33-31_1207958737307495699"
```

Results go to `analysis-output/<run-name>/regex/`:

- `entities.tsv`: source URL, entity type, MBID, name, credited artists and their
  MBIDs, MusicBrainz type, barcode, format, duration, status, language, script,
  artist area/begin date and genres.
- `release_events.tsv`: one row per country/date pair for a particular release.
  Multiple countries stay associated with their dates. Missing date components are
  not invented. Release-group table rows are not treated as that group's own date.
- `entity_links.tsv`: credited artist and release-group relations, plus Wikipedia
  and Wikidata references found in anchors. Wiki references are not proof of entity
  identity; review them before implementing the later join.
- `extraction_audit.tsv`: OK, WARNING or ERROR per input page. Invalid pages are
  excluded from entities. The command fails if it encounters errors; inspect the
  audit before consuming output. Rerunning overwrites these generated reports.

This is a site-specific `java.util.regex.Pattern`/`Matcher` extractor, not a general
HTML parser. It works on the crawler's saved DOM, excludes script/style/comments,
scopes properties to sidebar property lists and verifies the primary heading's
identity. Missing optional values stay empty; an explicit `[none]` barcode remains
distinct from a missing barcode. Tracks, label/catalogue details and an album's
first-release date are not extracted in this first version. DOM/language changes
may require pattern changes; the controls do not prove every possible page layout
is supported. Numeric HTML entities and a documented common set of named entities
are decoded; unrecognized named entities are preserved.

Fixed-value regression checks on the 20-page snapshot:

```powershell
mvn "-Dmaven.repo.local=.maven-repository" compile exec:java "-Dexec.mainClass=RegexExtractorChecks"
```

Keep `test-fixtures/regex-golden-20.tsv` and the original 20 HTML files and `pages.tsv`
to reproduce the checks. The expected values were recorded independently from the
HTML, not generated by the extractor. Tests cover 20 distinct URLs (one artist,
one release-group, eighteen releases), 180 fixed-value field comparisons, links,
identity, multiple release events, missing/explicitly absent barcodes and synthetic
edge cases. The sample is mostly Radiohead/OK Computer; more artists and layouts
should be checked before claiming broad dataset coverage. These are simple executable
unit/regression checks without JUnit. They do not send network requests.

## Own index and terminal search (offline)

Build from a completed REGEX output directory, while extraction is stopped:

```powershell
mvn "-Dmaven.repo.local=.maven-repository" compile exec:java "-Dexec.mainClass=MusicBrainzIndexer" "-Dexec.args=analysis-output/2026-10-10_23-08-15_7424884074887970765/regex"
```

Each build creates a new generation under `index-output/`; it does not append to or
overwrite the previous index. After validation, `latest-index.tsv` points to the new
generation. Failed builds remain unpublished with BUILDING state. Nothing reads HTML,
launches Chromium or accesses MusicBrainz here. No SQL or Lucene is used.

Index files:

| File | Content |
|---|---|
| `documents.tsv` | Document IDs, display metadata, indexed text and lexical token counts |
| `postings.tsv` | Inverted index: term → document ID → raw TF |
| `terms.tsv` | DF and both IDF values per term |
| `statistics.tsv` | Document, vocabulary, posting and lexical token counts |
| `manifest.tsv` | Version, completion state, tokenizer and input SHA-256 provenance |

One entity URL is one document, including each distinct release edition. The corpus
is rebuilt from all supplied entities; names/terms are not hardcoded. Duplicate
documents, extraction ERROR rows, inconsistent audit and orphan release events are
rejected. Optional extraction warnings are counted. Source checksums must remain
unchanged during the build. Old generations remain available until removed manually.

Indexed fields: name, artist credit, MusicBrainz type, barcode, format, status,
language, script, area, artist begin date, genres, release-event countries and dates.
Repeated identical countries/dates for an entity are deduplicated before tokenization.
URLs, IDs and duration are stored or retained in source data, not used as search text.
All indexed fields have equal weight in this first version.

Tokenization applies Unicode NFKC, lowercase with `Locale.ROOT`, then
`[\p{L}\p{N}]+`. Hyphens, semicolons and dates split into tokens; accents are preserved.
There is no stopword removal or stemming. `lexical_tokens` is this tokenizer's count,
not the tiktoken bonus.

For N documents and document frequency df:

- `classic`: `idf = 1 + ln(N / df)` (shifted classic IDF).
- `smooth`: `idf = 1 + ln((N + 1) / (df + 1))`.
- Both use `tf_weight = 1 + ln(raw_tf)` and `weight = tf_weight * idf`.
- Score is cosine similarity of the full document vector and query vector, using
  the selected IDF consistently for document weights, query weights and norms.
  Scores are not probabilities. Equal scores are ordered by internal document ID.

Search uses the latest complete generation unless `--index DIR` is supplied:

```powershell
mvn "-Dmaven.repo.local=.maven-repository" exec:java "-Dexec.mainClass=MusicBrainzSearch" "-Dexec.args=--method classic --top 5 --query Quinton Sung"
mvn "-Dmaven.repo.local=.maven-repository" exec:java "-Dexec.mainClass=MusicBrainzSearch" "-Dexec.args=--method smooth --match any --top 5 --query Radiohead cassette"
```

Place options before `--query`; the remaining arguments are the query text. Default
`--match all` requires all distinct query tokens. `--match any` accepts any known
token and ignores unknown tokens. All-mode with any unknown token returns no hits.
Blank queries are rejected. This is keyword retrieval, not natural-language QA,
phrase search, field filters or structured date filtering. For example `Japan 1997`
can match a document with Japan in an event and 1997 in its title, even if its release
date is 2017; it does not mean “released in Japan in 1997”. Country/date pairs remain
available in the source event TSV for a later structured filter.

Compare both methods on five example queries and save text results for wiki review:

```powershell
mvn "-Dmaven.repo.local=.maven-repository" exec:java "-Dexec.mainClass=MusicBrainzSearch" "-Dexec.args=--compare --top 5 --queries test-fixtures/search-queries.tsv --save analysis-output/2026-10-10_23-08-15_7424884074887970765/search-comparison.tsv"
```

The examples are test inputs, not the only supported queries. Empty results are
reported to stderr; the TSV contains ranked hits, so a no-hit query has no result rows.
The comparison is not a relevance evaluation or a precision/recall gold standard.

Run hand-calculated and persistence checks:

```powershell
mvn "-Dmaven.repo.local=.maven-repository" compile exec:java "-Dexec.mainClass=IndexChecks"
```

Tests cover raw TF/DF, both IDFs, exact cosine scores/ranking, AND/OR, unknown words,
Unicode, tied scores, event joining/deduplication, disk round trip, clean rebuild,
corrupt DF and rejection of incomplete/extraction-error input.

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

## Zber naprieč interpretmi (šírka)

`crawl-seeds.tsv` obsahuje šesť počiatočných interpretov: Radiohead, Beyoncé,
Daft Punk, Nina Simone, Kendrick Lamar a Metallica. Odkazy smerujú na ich
MusicBrainz profily. Zoznam môžete rozšíriť (stĺpce `name` a `url`, oddelené tabulátorom).

Malý živý test spustite až po ukončení predchádzajúceho crawlera:

```powershell
.\run-crawler.ps1 -Mode fresh -Pages 20 -Seeds crawl-seeds.tsv -Depth 2 -ReleasesPerGroup 3
```

Crawler najprv prejde počiatočných interpretov a potom strieda ich vetvy na rovnakej
hĺbke podľa počtu pokusov. Hĺbka má prednosť: pri limite 20 stránok môže výsledok
obsahovať najmä interpretov a release groups; konkrétne vydania prídu na ďalšej
úrovni. Z každej release group zaradí najviac tri nové vydania. Ostatné odkazy
zaznamená do `internal_links.tsv`, ale nemusí ich navštíviť. Limity nezaručujú
úplnú diskografiu ani reprezentatívnu vzorku MusicBrainz; stránkované odkazy s query
parametrami crawler nenavštevuje. Počet stránok je maximum, nie záruka.

`seed_sources.tsv` uchová počiatočné adresy, `breadth_coverage.tsv` po ukončení
počet pokusov a uložených typov stránok podľa počiatočnej vetvy. Zdieľaná stránka
patrí vetve, ktorá ju objavila prvá; nejde o hudobné priradenie interpreta.

Obnova použije uložené zdroje, limity aj poradie (súbor so zdrojmi netreba znovu zadávať):

```powershell
.\run-crawler.ps1 -Mode recover
```

Novú stratégiu začnite cez `fresh`; existujúcemu checkpointu ju nemožno pridať.
Nové checkpointy majú verziu 3; čítanie starých verzií 1 a 2 zostáva podporované.
Staršiu verziu programu nepoužívajte na obnovu nového checkpointu.
Súbežné požiadavky sa nepridávajú; pôvodné kontroly robots, tempa, rozpočtu,
blokovania a voľného disku zostávajú aktívne. Tento krok nezaručuje povolenie
ľubovoľne veľkého zberu ani vylúčenie blokovania serverom.

Offline kontrola používa iba umelé stránky a zahŕňa pád po siedmej stránke,
obnovu, striedanie troch vetiev a limit vydaní:

```powershell
mvn "-Dmaven.repo.local=.maven-repository" compile exec:java "-Dexec.mainClass=BreadthChecks"
```

### Pokrytie požadovaných údajov

Rozšírenie zdrojov samo nepridáva nové extrakčné polia. Pred veľkým zberom treba
dokončiť a otestovať zostávajúce polia, potom rozšíriť index.

| Údaj | Aktuálny REGEX extraktor |
| --- | --- |
| Názov albumu/release group, interpret, MusicBrainz ID, typ | Implementované |
| Formát, krajiny a dátumy konkrétnych vydaní | Implementované |
| Základné údaje interpreta | Čiastočne: typ, oblasť, začiatok |
| Wikipedia/Wikidata odkazy | Implementované ako odkazy nájdené v HTML |
| Žánre a tagy | Žánre implementované; všeobecné tagy doplniť |
| Dátum prvého vydania release group | Doplniť; minimum stiahnutých edícií nie je zaručený prvý dátum |
| Hodnotenie, ak dostupné | Doplniť |
| Skladby a ich dĺžky | Doplniť z HTML konkrétnych vydaní |
| Vydavateľstvo | Doplniť |
| Úplný zoznam konkrétnych vydaní | Odkazy sa zbierajú; návštevy sú obmedzené konfiguráciou |


### Rozv?janie novoobjaven?ch interpretov

Pre nov? ?irok? zber je predvolen? h?bka 6. BFS st?le spracuje plyt?ie str?nky
pred hlb??mi; v???ia maxim?lna h?bka dovol? rozvin?? aj albumy ?al??ch interpretov.
Po?iato?n? zoznam nie je whitelist. Z ka?d?ho interpreta sa zarad? najviac 20
nov?ch release groups a z ka?dej skupiny najviac 3 nov? vydania. Toto s? limity
nov?ch odkazov zaraden?ch z danej str?nky, nie ?plnos? diskografie. Poradie
vybran?ch albumov vych?dza z poradia odkazov v HTML; nejde o n?hodn? vzorku.

Na ?al?? mal? test pou?ite men?? limit albumov, aby sa crawler r?chlej?ie dostal
aj ku konkr?tnym vydaniam:

```powershell
.\run-crawler.ps1 -Mode fresh -Pages 50 -Seeds crawl-seeds.tsv -Depth 6 -AlbumsPerArtist 3 -ReleasesPerGroup 2
```

Obnova zachov? p?vodn? h?bku a limity. Nov? nastavenie nepremen? existuj?ci
20-str?nkov? beh na v????. Star? checkpointy bez limitu albumov sa obnovuj? bez
nov?ho obmedzenia albumov. Glob?lne limity str?nok, po?iadaviek, frontu a disku
platia aj pri v???ej h?bke. Dostupnos? odkazov, robots a reakcie servera m??u
zber ukon?i? pred cie?ov?m po?tom. Str?nkovanie diskografi? zatia? zost?va mimo
podporovan?ho rozsahu; presn? po?et dosiahnute?n?ch re?lnych str?nok nebol overen?.

`BreadthChecks` overuje aj interpreta mimo seed zoznamu, rozvinutie jeho albumov
na h?bke 3 a vydan? na h?bke 4, oba limity, deduplik?ciu a obnovu po p?de.


### Roz??ren? REGEX v?stupy (offline)

Po spusten? `MusicBrainzRegexExtractor` pribudn? v rovnakom prie?inku `regex`:

- `tracks.tsv`: jeden riadok na skladbu vydania; poradov? ??slo v extrakcii,
  MusicBrainz track ID, p?vodn? poz?cia (napr. A1), n?zov, recording ID,
  zobrazen? d??ka a prepo?et na milisekundy. Ch?baj?ca/nezn?ma d??ka ost?va pr?zdna.
- `labels.tsv`: vydavate?stvo konkr?tneho vydania, jeho ID a katal?gov? ??sla.
- `entity_details.tsv`: ?al?ie vidite?n? tagy, hodnotenie (0 a? 5), rozsah hodnotenia,
  najstar?? vidite?n? d?tum zo zoznamu vydan? a ?al?ie dostupn? ?daje interpreta.

Hodnotenie zobrazen? na str?nke vydania patr? release group, ?o v?slovne uv?dza
`rating_scope=release-group`. Nejde o hodnotenie tejto konkr?tnej ed?cie ani skladby.
`earliest_listed_release_date` je najstar?? d?tum n?jden? v aktu?lnom HTML release
group, s p?vodnou presnos?ou (napr. iba rok). Nie je garantovan?m prv?m vydan?m:
zoznam m??e by? str?nkovan? a ne?pln?. Pr?zdne hodnoty znamenaj?, ?e ?daj nebol
n?jden? v podporovanej ?trukt?re HTML; nedokazuj?, ?e ?daj v MusicBrainz neexistuje.
Zoznam tagov obsahuje iba vidite?n? tagy, nie cel? obsah str?nky ?See all tags?.

P?vodn? `entities.tsv`, `release_events.tsv`, `entity_links.tsv` zost?vaj? kompatibiln?.
Indexer u? prip?ja n?zvy skladieb, men? vydavate?stiev, katal?gov? ??sla, ?al?ie tagy a dopl?uj?ce ?daje interpreta.
Uk??ky pre dokument?ciu s? v `REGEX-EXAMPLES.md`; zverejnenie na wiki je samostatn? krok.

```powershell
mvn "-Dmaven.repo.local=.maven-repository" compile exec:java "-Dexec.mainClass=DetailsChecks"
```


### Indexovanie roz??ren?ch ?dajov

Nov? TSV sa prip?jaj? pod?a `source_url` a `page_id`. Nespr?vna v?zba alebo
skladba/vydavate?stvo priraden? in?mu typu ne? release zastav? zostavenie indexu.
Rovnak? textov? hodnoty v doplnkov?ch tabu?k?ch sa na jednej str?nke indexuj? iba
raz. S?bory s? volite?n?, preto zost?va podporovan? zostavenie zo star??ch v?stupov.
Existuj?ci po?koden? doplnkov? s?bor sa nepresko??. Hash ka?d?ho pou?it?ho vstupu
sa ulo?? do manifestu a pred dokon?en?m sa op?? skontroluje.

V?sledkom zost?va str?nka albumu/vydania/interpreta, nie samostatn? dokument pre
ka?d? skladbu. Hodnotenie ani d??ka skladby zatia? nie s? numerick? filtre/rankovacie
pravidl?; oba existuj?ce sp?soby radenia na?alej pou??vaj? TF-IDF a kos?nus.
Pozorovan? minimum d?tumov sa nezamie?a za potvrden? prv? d?tum vydania a indexer
ho neprid?va medzi d?tumy udalost? konkr?tneho vydania.

```powershell
mvn "-Dmaven.repo.local=.maven-repository" exec:java "-Dexec.mainClass=MusicBrainzIndexer" "-Dexec.args=analysis-output/2026-10-10_23-08-15_7424884074887970765/regex"
mvn "-Dmaven.repo.local=.maven-repository" exec:java "-Dexec.mainClass=MusicBrainzSearch" "-Dexec.args=--compare --top 3 --query Radiohead Airbag Parlophone"
```

Nov? index m??e zmeni? sk?re aj poradie p?vodn?ch dopytov, preto?e dokumenty u?
obsahuj? viac slov. Predo?l? v?sledky porovn?vajte iba s rovnakou gener?ciou indexu.


Praktick? postup pre dlh?? beh, obnovu a spracovanie v?sledkov: [LONG-RUN.md](LONG-RUN.md).
Lok?lne porovnanie roz??ren?ch dopytov: `test-fixtures/search-expanded-queries.tsv`;
v?sledky nad 100-str?nkovou kolekciou s? v jej `search-expanded.tsv`.
Kontrolovan? pr?klady: `TCNODATA 02` vracia str?nku 9 v oboch met?dach,
`atmospheric` zah??a str?nku 1 a neexistuj?ce slovo nem? v?sledky. Toto s?
regresn? pr?klady; nejde o nez?visl? ve?k? benchmark relevancie.
