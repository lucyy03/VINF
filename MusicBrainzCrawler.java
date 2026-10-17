import java.io.IOException;
import java.io.StringReader;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MusicBrainzCrawler {
    private static String START_URL =
        "https://musicbrainz.org/release-group/b1392450-e666-3926-a536-22c65f834433";

    private static final String ALLOWED_HOST = "musicbrainz.org";

    private static final String USER_AGENT =
        "SchoolAlbumCrawler/2.0 (STUBA educational HTML crawl)";

    private static int MAX_PAGES = 1000;
    private static int MAX_DEPTH = 10;
    // Includes robots.txt, verification POSTs, redirects and unsuccessful requests.
    private static int MAX_REQUESTS = 4100;
    private static int MAX_FRONTIER = 20000;
    private static long MIN_FREE_BYTES = 512L * 1024 * 1024;
    private static final long ROBOTS_REFRESH_MS = Duration.ofHours(1).toMillis();
    private static final int BROWSER_RECYCLE_PAGES = 250;
    private static final int MAX_PAGE_BYTES = 25 * 1024 * 1024;
    private static long robotsLoadedAtMs;
    private static long frontierDeferred;
    private static String requestedMode;
    private static final Set<String> explicitOptions = new HashSet<>();
    private static int requestCount = 0;

    private static final long MIN_DELAY_MS = 3000;

    private static final Pattern ENTITY_PATH_PATTERN = Pattern.compile(
        "^/(release-group|release|artist)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/?$"
    );

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private static Path OUTPUT_ROOT = Path.of("crawler-output");
    private static final CrawlTransport LIVE_TRANSPORT = new CrawlTransport() {
        public RobotsFetch robots() throws IOException, InterruptedException, StopCrawlException { return fetchLiveRobotsTxt(); }
        public PageResponse page(String url) throws IOException, InterruptedException, StopCrawlException { return downloadLivePage(url); }
    };
    private static CrawlTransport transport = LIVE_TRANSPORT;
    private interface CrawlTransport {
        RobotsFetch robots() throws IOException, InterruptedException, StopCrawlException;
        PageResponse page(String url) throws IOException, InterruptedException, StopCrawlException;
    }
    private static Path checkpointDirectory;
    private static BrowserTransport browserTransport;
    private static RobotsRules activeRobots;

    private static long requestDelayMs = MIN_DELAY_MS;
    private static long lastRequestStartedAtMs = 0;

    public static void main(String[] args) {
        try {
            configure(args);
            if (requestedMode != null && requestedMode.equals("help")) { printUsage(); return; }
            Files.createDirectories(OUTPUT_ROOT);
            try (java.nio.channels.FileChannel lockChannel = java.nio.channels.FileChannel.open(
                    OUTPUT_ROOT.resolve("crawler.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 java.nio.channels.FileLock lock = lockChannel.tryLock()) {
                if (lock == null) throw new IOException("Another crawler is already running.");
                runCrawler(args);
            }
        } catch (Exception e) {
            System.err.println("Crawler stopped: " + e.getMessage());
            e.printStackTrace();
            throw new IllegalStateException("Crawler failed", e);
        } finally {
            if (browserTransport != null) browserTransport.close();
        }
    }

    private static void runCrawler(String[] args) throws Exception {
        Checkpoint recovered = selectCheckpoint(args);
        validateConfiguration();
        Path outputDirectory = recovered == null ? createOutputDirectory() : recovered.directory;
        checkpointDirectory = outputDirectory;
        if (recovered != null) {
            loadRequestState(outputDirectory);
            recovered.restoreOutputs();
            System.out.println("Recovering " + outputDirectory + "; completed pages: " + recovered.pages);
            if (recovered.pages >= MAX_PAGES || recovered.queue.isEmpty()) {
                // The process may have died after the last page commit but before
                // publishing its terminal state. Finalize locally, without HTTP.
                String state = recovered.pages >= MAX_PAGES ? "COMPLETE" : "EXHAUSTED";
                recovered.save(state);
                atomicText(outputDirectory.resolve("run_summary.tsv"), "metric\tvalue\nstate\t" + state
                    + "\npages\t" + recovered.pages + "\nrequests\t" + requestCount
                    + "\nfrontier_remaining\t" + recovered.queue.size() + "\n");
                System.out.println("Recovered terminal state: " + state + "; no further requests needed.");
                return;
            }
        }
        if (requestCount >= MAX_REQUESTS) throw new IOException("Saved request budget exhausted; recovery cannot reset it.");
        Path htmlDirectory = outputDirectory.resolve("html");

        Files.createDirectories(htmlDirectory);

        Path pagesFile = outputDirectory.resolve("pages.tsv");
        Path internalLinksFile = outputDirectory.resolve("internal_links.tsv");
        Path externalLinksFile = outputDirectory.resolve("external_links.tsv");
        Path crawlLogFile = outputDirectory.resolve("crawl_log.tsv");
        Path robotsFile = outputDirectory.resolve("robots.txt");

        if (recovered == null) {
        writeLine(
            pagesFile,
            "id\turl\tdepth\tstatus\tcontent_type\thtml_file"
        );

        writeLine(
            internalLinksFile,
            "source_url\ttarget_url\tqueued_for_crawl"
        );

        writeLine(
            externalLinksFile,
            "source_url\ttarget_url\tdomain\ttype"
        );

        writeLine(
            crawlLogFile,
            "time\turl\tdepth\tresult\thttp_status\tdetail"
        );

        }

        System.out.println("Loading robots.txt...");
        try {
            refreshRobots();
        } catch (StopCrawlException e) {
            if (recovered != null) markCheckpointStopped(outputDirectory);
            throw e;
        }
        RobotsRules robotsRules = activeRobots;

        System.out.println("robots.txt loaded and saved.");
        System.out.println(
            "Request delay: " + requestDelayMs + " ms"
        );
        System.out.println(
            "Maximum successful pages: " + MAX_PAGES
        );
        System.out.println(
            "Maximum depth: " + MAX_DEPTH
        );
        System.out.println("Maximum total requests: " + MAX_REQUESTS);
        System.out.println();

        String startUrl = normalizeUrl(
            START_URL,
            START_URL
        );

        if (startUrl == null || !isInternal(startUrl)) {
            throw new IllegalArgumentException(
                "START_URL must be a valid musicbrainz.org URL."
            );
        }

        if (!robotsRules.isAllowed(startUrl)) {
            throw new IllegalStateException(
                "START_URL is blocked by robots.txt."
            );
        }

        Queue<CrawlItem> queue = recovered == null ? new ArrayDeque<>() : recovered.queue;
        Set<String> queued = recovered == null ? new HashSet<>() : recovered.queued;
        Set<String> visited = recovered == null ? new HashSet<>() : recovered.visited;
        Set<String> savedInternalEdges = recovered == null ? new HashSet<>() : recovered.internalEdges;
        Set<String> savedExternalEdges = recovered == null ? new HashSet<>() : recovered.externalEdges;
        if (recovered == null) { queue.add(new CrawlItem(startUrl, 0)); queued.add(startUrl); }
        int successfulPages = recovered == null ? 0 : recovered.pages;
        // Each source is processed once and extractLinks already deduplicates targets.
        // Historical edge sets need not live in memory or grow the recovery state.
        savedInternalEdges.clear(); savedExternalEdges.clear();
        Checkpoint checkpoint = recovered != null ? recovered : new Checkpoint(outputDirectory, queue, queued, visited,
            savedInternalEdges, savedExternalEdges, successfulPages);
        checkpoint.save("ACTIVE");
        boolean stoppedBySite = false;

        while (
            !queue.isEmpty()
                && successfulPages < MAX_PAGES
                && requestCount < MAX_REQUESTS
        ) {
            if (Files.exists(outputDirectory.resolve("stop.request"))) {
                System.out.println("Pause requested via stop.request; checkpoint remains recoverable.");
                stoppedBySite = true;
                break;
            }
            checkDiskSpace(outputDirectory);
            if (robotsRefreshDue()) {
                try { refreshRobots(); }
                catch (StopCrawlException e) { markCheckpointStopped(outputDirectory); throw e; }
            }
            robotsRules = activeRobots;
            CrawlItem item = queue.poll();

            String currentUrl = item.url();

            if (!visited.add(currentUrl)) {
                continue;
            }

            if (!robotsRules.isAllowed(currentUrl)) {
                System.out.println(
                    "ROBOTS BLOCKED: " + currentUrl
                );

                writeCrawlLog(
                    crawlLogFile,
                    currentUrl,
                    item.depth(),
                    "ROBOTS_BLOCKED",
                    "",
                    "not requested"
                );

                checkpoint.save("ACTIVE");
                continue;
            }

            System.out.println(
                "["
                    + (successfulPages + 1)
                    + "/"
                    + MAX_PAGES
                    + "] depth="
                    + item.depth()
                    + " "
                    + currentUrl
            );

            PageResponse page;

            try {
                page = downloadPage(currentUrl);
                String html = new String(page.body(), StandardCharsets.UTF_8);
                if (isChallengePage(html)) {
                    Files.write(htmlDirectory.resolve("blocked.html"), page.body());
                    throw new StopCrawlException(Integer.toString(page.statusCode()),
                        "Browser verification/access challenge; response saved to html/blocked.html. "
                        + "Standard browser verification did not finish. No additional retry attempted.");
                }
            } catch (StopCrawlException e) {
                writeCrawlLog(
                    crawlLogFile,
                    currentUrl,
                    item.depth(),
                    "STOPPED",
                    e.statusCode(),
                    e.getMessage()
                );

                System.err.println(
                    "Stopping safely: "
                        + e.getMessage()
                );

                // Keep the pre-page checkpoint for transport failures. Site blocks are
                // deliberate stops and must not become automatic recovery retries.
                int status = Integer.parseInt(e.statusCode());
                if (status == 403 || status == 429 || status >= 500
                    || e.getMessage().startsWith("Browser verification/access challenge"))
                    markCheckpointStopped(outputDirectory);
                stoppedBySite = true;
                break;
            } catch (IOException e) {
                writeCrawlLog(
                    crawlLogFile,
                    currentUrl,
                    item.depth(),
                    "SKIPPED",
                    "",
                    e.getMessage()
                );

                System.out.println(
                    "  skipped: "
                        + e.getMessage()
                );

                System.out.println();
                checkpoint.save("ACTIVE");
                continue;
            }

            successfulPages++;

            String htmlFileName =
                String.format(
                    "%04d.html",
                    successfulPages
                );

            Path htmlFile =
                htmlDirectory.resolve(
                    htmlFileName
                );

            Files.write(
                htmlFile,
                page.body()
            );

            writeLine(
                pagesFile,
                successfulPages
                    + "\t"
                    + tsv(currentUrl)
                    + "\t"
                    + item.depth()
                    + "\t"
                    + page.statusCode()
                    + "\t"
                    + tsv(page.contentType())
                    + "\t"
                    + "html/"
                    + htmlFileName
            );

            writeCrawlLog(
                crawlLogFile,
                currentUrl,
                item.depth(),
                "SAVED",
                Integer.toString(
                    page.statusCode()
                ),
                "html/" + htmlFileName
            );

            String htmlForLinkExtraction =
                new String(
                    page.body(),
                    StandardCharsets.UTF_8
                );

            Set<String> links =
                extractLinks(
                    htmlForLinkExtraction,
                    currentUrl
                );

            int internalCount = 0;
            int externalCount = 0;
            int queuedCount = 0;

            for (String link : links) {
                if (isInternal(link)) {
                    internalCount++;

                    boolean shouldQueue =
                        item.depth()
                            < MAX_DEPTH
                            && isRelevantEntityUrl(
                                link
                            )
                            && robotsRules
                                .isAllowed(link)
                            && !visited
                                .contains(link)
                            && !queued
                                .contains(link)
                            && queue.size() < MAX_FRONTIER;
                    if (item.depth() < MAX_DEPTH && isRelevantEntityUrl(link)
                        && !visited.contains(link) && !queued.contains(link) && queue.size() >= MAX_FRONTIER)
                        frontierDeferred++;

                    String edgeKey =
                        currentUrl
                            + "\t"
                            + link;

                    if (
                        savedInternalEdges
                            .add(edgeKey)
                    ) {
                        writeLine(
                            internalLinksFile,
                            tsv(currentUrl)
                                + "\t"
                                + tsv(link)
                                + "\t"
                                + shouldQueue
                        );
                    }

                    if (shouldQueue) {
                        queue.add(
                            new CrawlItem(
                                link,
                                item.depth()
                                    + 1
                            )
                        );

                        queued.add(link);
                        queuedCount++;
                    }
                } else {
                    externalCount++;

                    String edgeKey =
                        currentUrl
                            + "\t"
                            + link;

                    if (
                        savedExternalEdges
                            .add(edgeKey)
                    ) {
                        writeLine(
                            externalLinksFile,
                            tsv(currentUrl)
                                + "\t"
                                + tsv(link)
                                + "\t"
                                + tsv(
                                    getHost(link)
                                )
                                + "\t"
                                + classifyExternalLink(
                                    link
                                )
                        );
                    }
                }
            }

            savedInternalEdges.clear(); savedExternalEdges.clear();
            checkpoint.pages = successfulPages;
            checkpoint.save("ACTIVE");
            if (browserTransport != null && successfulPages % BROWSER_RECYCLE_PAGES == 0) {
                browserTransport.close(); browserTransport = null;
            }

            System.out.println(
                "  links found: "
                    + links.size()
            );

            System.out.println(
                "  internal: "
                    + internalCount
            );

            System.out.println(
                "  external: "
                    + externalCount
            );

            System.out.println(
                "  added to BFS queue: "
                    + queuedCount
            );

            System.out.println(
                "  Rendered HTML saved: html/"
                    + htmlFileName
            );

            System.out.println();
        }

        String finalState = successfulPages >= MAX_PAGES ? "COMPLETE"
            : queue.isEmpty() ? "EXHAUSTED" : "BUDGET_EXHAUSTED";
        if (!stoppedBySite) checkpoint.save(finalState);
        atomicText(outputDirectory.resolve("run_summary.tsv"), "metric\tvalue\nstate\t"
            + (stoppedBySite ? "INTERRUPTED_OR_STOPPED" : finalState) + "\npages\t" + successfulPages
            + "\nrequests\t" + requestCount + "\nfrontier_remaining\t" + queue.size()
            + "\nfrontier_deferred_this_process\t" + frontierDeferred + "\n");
        System.out.println(
            "Crawler finished."
        );

        System.out.println(
            "Successful pages: "
                + successfulPages
        );
        System.out.println("Total requests: " + requestCount + "/" + MAX_REQUESTS);
        if (!stoppedBySite && requestCount >= MAX_REQUESTS && successfulPages < MAX_PAGES) {
            System.out.println("Stopped at the request budget; page target was not reached.");
        } else if (!stoppedBySite && queue.isEmpty() && successfulPages < MAX_PAGES) {
            System.out.println("No more eligible pages within the configured depth.");
        }

        System.out.println(
            "Output directory: "
                + outputDirectory
                    .toAbsolutePath()
        );
    }

    private static void printUsage() {
        System.out.println("MusicBrainzCrawler [--fresh|--recover] [--pages N] [--depth N] "
            + "[--max-requests N] [--max-frontier N] [--min-free-mib N] [--start-url URL]");
        System.out.println("Example: --fresh --pages 10000 --depth 10");
        System.out.println("Recovery automatically uses saved settings; it cannot reset the request budget.");
    }

    private static void configure(String[] args) throws IOException {
        requestedMode = null; explicitOptions.clear();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (key.equals("--help")) { requestedMode = "help"; continue; }
            if (key.equals("--fresh") || key.equals("--recover")) {
                if (requestedMode != null) throw new IOException("Choose one mode only.");
                requestedMode = key.substring(2); continue;
            }
            if (key.equals("--start-url")) {
                if (!explicitOptions.add(key) || ++i >= args.length || !isRelevantEntityUrl(args[i]))
                    throw new IOException("--start-url must be a MusicBrainz entity HTTPS URL");
                START_URL = normalizeUrl(args[i], args[i]); continue;
            }
            if (!Set.of("--pages", "--depth", "--max-requests", "--max-frontier", "--min-free-mib").contains(key)
                || !explicitOptions.add(key) || ++i >= args.length) throw new IOException("Invalid option: " + key);
            try {
                int value = Integer.parseInt(args[i]);
                switch (key) {
                    case "--pages" -> MAX_PAGES = value;
                    case "--depth" -> MAX_DEPTH = value;
                    case "--max-requests" -> MAX_REQUESTS = value;
                    case "--max-frontier" -> MAX_FRONTIER = value;
                    case "--min-free-mib" -> MIN_FREE_BYTES = Math.multiplyExact((long) value, 1048576L);
                }
            } catch (RuntimeException e) { throw new IOException("Invalid numeric option: " + key, e); }
        }
        if (!explicitOptions.contains("--max-requests")) {
            long budget = 4L * MAX_PAGES + 100;
            if (budget > Integer.MAX_VALUE) throw new IOException("Page target is too large.");
            MAX_REQUESTS = (int) budget;
        }
        validateConfiguration();
    }

    private static void adoptSavedConfiguration(Path directory) throws IOException {
        for (String line : Files.readAllLines(directory.resolve("checkpoint.tsv"))) {
            String[] fields = line.split("\t");
            try {
                if (fields[0].equals("CONFIG")) {
                    String start = decoded(fields[1]);
                    if (explicitOptions.contains("--start-url") && !START_URL.equals(start))
                        throw new IOException("Recovery must use the saved start URL");
                    if (!isRelevantEntityUrl(start)) throw new IOException("Invalid saved start URL");
                    START_URL = start;
                    int pages = Integer.parseInt(fields[2]), depth = Integer.parseInt(fields[3]), requests = Integer.parseInt(fields[4]);
                    requireSavedOption("--pages", MAX_PAGES, pages); requireSavedOption("--depth", MAX_DEPTH, depth);
                    requireSavedOption("--max-requests", MAX_REQUESTS, requests);
                    MAX_PAGES = pages; MAX_DEPTH = depth; MAX_REQUESTS = requests;
                } else if (fields[0].equals("SETTINGS")) {
                    int frontier = Integer.parseInt(fields[1]); long free = Long.parseLong(fields[2]);
                    requireSavedOption("--max-frontier", MAX_FRONTIER, frontier);
                    requireSavedOption("--min-free-mib", MIN_FREE_BYTES, free);
                    MAX_FRONTIER = frontier; MIN_FREE_BYTES = free;
                }
            } catch (RuntimeException e) { throw new IOException("Invalid saved configuration", e); }
        }
        validateConfiguration();
    }

    private static void requireSavedOption(String name, long requested, long saved) throws IOException {
        if (explicitOptions.contains(name) && requested != saved)
            throw new IOException("Recovery must use saved " + name + "=" + saved + "; omit that option.");
    }

    private static void checkDiskSpace(Path directory) throws IOException {
        if (Files.getFileStore(directory).getUsableSpace() < MIN_FREE_BYTES + MAX_PAGE_BYTES)
            throw new IOException("Low free disk space; checkpoint preserved. Free space before recovery.");
    }

    private static boolean robotsRefreshDue() {
        return activeRobots == null || System.currentTimeMillis() - robotsLoadedAtMs >= ROBOTS_REFRESH_MS;
    }

    private static void refreshRobots() throws IOException, InterruptedException, StopCrawlException {
        RobotsFetch fetch = fetchRobotsTxt();
        RobotsRules refreshed = RobotsRules.parse(fetch.body());
        if (checkpointDirectory != null) {
            atomicText(checkpointDirectory.resolve("robots.txt"), fetch.body());
            writeCrawlLog(checkpointDirectory.resolve("crawl_log.tsv"),
                "https://musicbrainz.org/robots.txt", 0, "ROBOTS_REFRESHED", "200", "hourly policy refresh");
        }
        activeRobots = refreshed;
        requestDelayMs = Math.max(MIN_DELAY_MS, refreshed.crawlDelayMs());
        robotsLoadedAtMs = System.currentTimeMillis();
        if (checkpointDirectory != null) atomicText(checkpointDirectory.resolve("request_state.tsv"),
            requestCount + "\t" + lastRequestStartedAtMs + "\t" + requestDelayMs + "\n");
    }

    private static HttpResponse.BodyHandler<byte[]> boundedBodyHandler(int limit) {
        return ignored -> new HttpResponse.BodySubscriber<>() {
            final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
            final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.util.concurrent.Flow.Subscription subscription;
            public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
            public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; value.request(1); }
            public void onNext(List<java.nio.ByteBuffer> buffers) {
                for (var buffer : buffers) {
                    if (bytes.size() + (long) buffer.remaining() > limit) {
                        subscription.cancel(); result.completeExceptionally(new IOException("Response exceeds byte limit")); return;
                    }
                    byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
                }
                subscription.request(1);
            }
            public void onError(Throwable error) { result.completeExceptionally(error); }
            public void onComplete() { result.complete(bytes.toByteArray()); }
        };
    }

    private static final List<String> OUTPUT_FILES = List.of(
        "pages.tsv", "internal_links.tsv", "external_links.tsv", "crawl_log.tsv");

    private static void atomicText(Path target, String text) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        // Do not fall back to a non-atomic replacement: the old checkpoint must remain intact.
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                break;
            } catch (java.nio.file.AccessDeniedException e) {
                // Windows scanners can briefly hold the destination. Retry only this
                // local atomic rename; never weaken atomicity or repeat a web request.
                if (attempt >= 7) throw e;
                try { Thread.sleep(Math.min(200, 25L << attempt)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("Interrupted while committing checkpoint");
                }
            }
        }
    }

    private static String encoded(String value) {
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(String value) {
        return new String(java.util.Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void reserveRequest() throws IOException {
        if (requestCount >= MAX_REQUESTS) throw new IOException("Total request budget exhausted.");
        requestCount++;
        lastRequestStartedAtMs = System.currentTimeMillis();
        // Persist before sending: a crash may overcount a request, but cannot reset the budget.
        if (checkpointDirectory != null) atomicText(checkpointDirectory.resolve("request_state.tsv"),
            requestCount + "\t" + lastRequestStartedAtMs + "\t" + requestDelayMs + "\n");
    }

    private static void reserveRequest(String url, String method) throws IOException {
        reserveRequest();
        if (checkpointDirectory != null) {
            Path log = checkpointDirectory.resolve("request_log.tsv");
            if (!Files.exists(log)) writeLine(log, "request_no\treserved_at\tmethod\turl");
            writeLine(log, requestCount + "\t" + Instant.ofEpochMilli(lastRequestStartedAtMs)
                + "\t" + tsv(method) + "\t" + tsv(url));
        }
    }

    private static void loadRequestState(Path directory) throws IOException {
        String[] fields = Files.readString(directory.resolve("request_state.tsv")).trim().split("\t");
        try {
            requestCount = Integer.parseInt(fields[0]);
            lastRequestStartedAtMs = Long.parseLong(fields[1]);
            requestDelayMs = Math.max(MIN_DELAY_MS, Long.parseLong(fields[2]));
            if (requestCount < 0 || requestCount > MAX_REQUESTS || lastRequestStartedAtMs < 0)
                throw new IllegalArgumentException("invalid request state");
        } catch (RuntimeException e) { throw new IOException("Invalid saved request state", e); }
    }

    private static void markCheckpointStopped(Path directory) throws IOException {
        Path file = directory.resolve("checkpoint.tsv");
        String text = Files.readString(file);
        atomicText(file, text.replaceFirst("STATE\\tACTIVE", "STATE\tSTOPPED"));
    }

    private static Checkpoint selectCheckpoint(String[] args) throws IOException {

        Path pointer = OUTPUT_ROOT.resolve("latest_checkpoint.tsv");
        Path directory = null;
        if (Files.exists(pointer)) {
            Path root = OUTPUT_ROOT.toAbsolutePath().normalize();
            directory = root.resolve(Files.readString(pointer).trim()).normalize();
            if (!root.equals(directory.getParent())) throw new IOException("Invalid checkpoint directory.");
        }
        if ("fresh".equals(requestedMode)) return null;
        if (directory == null) {
            if ("recover".equals(requestedMode)) throw new IOException("No checkpoint exists yet.");
            return null;
        }
        String choice = requestedMode;
        if (choice == null) {
            System.out.println("Latest checkpoint: " + directory);
            System.out.print("Type fresh to start over, or recover to recover that run: ");
            choice = new java.io.BufferedReader(new java.io.InputStreamReader(System.in,
                StandardCharsets.UTF_8)).readLine();
        }
        if (choice != null && choice.trim().equalsIgnoreCase("fresh")) return null;
        if (choice == null || !choice.trim().equalsIgnoreCase("recover"))
            throw new IOException("No choice made. Run with --fresh or --recover.");
        adoptSavedConfiguration(directory);
        Checkpoint checkpoint = Checkpoint.read(directory);
        if (!checkpoint.state.equals("ACTIVE")) throw new IOException(
            "This run is " + checkpoint.state + ". Recovery is only for an interrupted run, "
            + "not a completed run or site blocking. Choose fresh for an intentional new run.");
        return checkpoint;
    }

    private static final class Checkpoint {
        final Path directory;
        final Queue<CrawlItem> queue;
        final Set<String> queued, visited, internalEdges, externalEdges;
        final java.util.Map<String, Long> sizes = new java.util.HashMap<>();
        int pages;
        String state;

        Checkpoint(Path directory, Queue<CrawlItem> queue, Set<String> queued, Set<String> visited,
                   Set<String> internalEdges, Set<String> externalEdges, int pages) {
            this.directory = directory; this.queue = queue; this.queued = queued; this.visited = visited;
            this.internalEdges = internalEdges; this.externalEdges = externalEdges; this.pages = pages;
        }

        private int sequence;
        private boolean journalInitialized;
        private List<CrawlItem> previousQueue = List.of();
        private Set<String> previousVisited = Set.of();

        void save(String state) throws IOException {
            String snapshot = snapshot(state);
            if (!journalInitialized) {
                atomicText(directory.resolve("checkpoint-base.tsv"), snapshot);
                sequence = 0;
            } else {
                List<CrawlItem> now = new ArrayList<>(queue);
                int removed = previousQueue.size();
                if (!now.isEmpty()) {
                    int first = previousQueue.indexOf(now.get(0));
                    if (first >= 0) removed = first;
                }
                int retained = previousQueue.size() - removed;
                if (now.size() < retained || !previousQueue.subList(removed, previousQueue.size()).equals(now.subList(0, retained)))
                    throw new IOException("Checkpoint queue must preserve BFS order");
                StringBuilder delta = new StringBuilder("REMOVE\t" + removed + "\n");
                for (int i = retained; i < now.size(); i++) delta.append("QUEUE\t").append(encoded(now.get(i).url()))
                    .append('\t').append(now.get(i).depth()).append('\n');
                for (String url : visited) if (!previousVisited.contains(url))
                    delta.append("VISITED\t").append(encoded(url)).append('\n');
                appendSet(delta, "INTERNAL", internalEdges); appendSet(delta, "EXTERNAL", externalEdges);
                Files.createDirectories(directory.resolve("checkpoint-journal"));
                atomicText(journalFile(sequence + 1), delta.toString());
                sequence++;
            }
            // Small manifest is the commit point. Orphan deltas beyond its sequence are ignored.
            String manifest = snapshot.lines().filter(line -> !line.startsWith("QUEUE\t")
                && !line.startsWith("QUEUED\t") && !line.startsWith("VISITED\t")
                && !line.startsWith("INTERNAL\t") && !line.startsWith("EXTERNAL\t"))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"))
                .replaceFirst("VERSION\\t1", "VERSION\t2")
                + "SEQUENCE\t" + sequence + "\nSETTINGS\t" + MAX_FRONTIER + "\t" + MIN_FREE_BYTES + "\n";
            atomicText(directory.resolve("checkpoint.tsv"), manifest);
            if (!journalInitialized) atomicText(directory.getParent().resolve("latest_checkpoint.tsv"), directory.getFileName() + "\n");
            previousQueue = new ArrayList<>(queue); previousVisited = new HashSet<>(visited);
            journalInitialized = true; this.state = state;
        }

        private Path journalFile(int index) {
            return directory.resolve("checkpoint-journal").resolve(String.format("%08d.tsv", index));
        }

        private String snapshot(String state) throws IOException {
            StringBuilder text = new StringBuilder("VERSION\t1\nSTATE\t" + state + "\nCONFIG\t"
                + encoded(START_URL) + "\t" + MAX_PAGES + "\t" + MAX_DEPTH + "\t" + MAX_REQUESTS
                + "\nPAGES\t" + pages + "\n");
            for (String name : OUTPUT_FILES) {
                try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                        directory.resolve(name), StandardOpenOption.WRITE)) {
                    channel.force(true);
                    text.append("OUTPUT\t").append(name).append('\t').append(channel.size()).append('\n');
                }
            }
            // Flush page bytes before publishing a checkpoint referencing them.
            if (pages > 0) try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    directory.resolve("html").resolve(String.format("%04d.html", pages)), StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (!journalInitialized) for (CrawlItem item : queue) text.append("QUEUE\t").append(encoded(item.url()))
                .append('\t').append(item.depth()).append('\n');
            if (!journalInitialized) {
                appendSet(text, "QUEUED", queued); appendSet(text, "VISITED", visited);
                appendSet(text, "INTERNAL", internalEdges); appendSet(text, "EXTERNAL", externalEdges);
            }
            return text.toString();
        }

        private static void appendSet(StringBuilder text, String kind, Set<String> values) {
            for (String value : values) text.append(kind).append('\t').append(encoded(value)).append('\n');
        }

        static Checkpoint read(Path directory) throws IOException {
            List<String> manifest = Files.readAllLines(directory.resolve("checkpoint.tsv"), StandardCharsets.UTF_8);
            if (!manifest.isEmpty() && manifest.get(0).equals("VERSION\t1")) return readSnapshot(directory, manifest);
            if (manifest.isEmpty() || !manifest.get(0).equals("VERSION\t2")) throw new IOException("Unknown checkpoint version");
            int committed = -1;
            List<String> metadata = new ArrayList<>();
            for (String line : manifest) {
                if (line.startsWith("SEQUENCE\t")) {
                    try { committed = Integer.parseInt(line.substring(9)); }
                    catch (RuntimeException e) { throw new IOException("Invalid journal sequence", e); }
                } else if (!line.startsWith("SETTINGS\t")) metadata.add(line.replaceFirst("^VERSION\\t2$", "VERSION\t1"));
            }
            if (committed < 0 || committed > (long) MAX_REQUESTS + MAX_FRONTIER + MAX_PAGES + 100)
                throw new IOException("Invalid journal sequence");
            Checkpoint meta = readSnapshot(directory, metadata);
            Checkpoint result = readSnapshot(directory, Files.readAllLines(directory.resolve("checkpoint-base.tsv"), StandardCharsets.UTF_8));
            try {
                for (int i = 1; i <= committed; i++) {
                    List<String> lines = Files.readAllLines(result.journalFile(i), StandardCharsets.UTF_8);
                    if (lines.isEmpty() || !lines.get(0).startsWith("REMOVE\t")) throw new IOException("Invalid journal entry: " + i);
                    int removed = Integer.parseInt(lines.get(0).substring(7));
                    if (removed < 0 || removed > result.queue.size()) throw new IOException("Invalid journal queue removal");
                    for (int n = 0; n < removed; n++) result.queue.remove();
                    result.internalEdges.clear(); result.externalEdges.clear();
                    for (int n = 1; n < lines.size(); n++) {
                        String[] f = lines.get(n).split("\t", -1);
                        switch (f[0]) {
                            case "QUEUE" -> {
                                String url = decoded(f[1]); int depth = Integer.parseInt(f[2]);
                                if (!isRelevantEntityUrl(url) || depth < 0 || depth > MAX_DEPTH) throw new IOException("Invalid journal URL");
                                result.queue.add(new CrawlItem(url, depth));
                            }
                            case "VISITED" -> result.visited.add(decoded(f[1]));
                            case "INTERNAL" -> result.internalEdges.add(decoded(f[1]));
                            case "EXTERNAL" -> result.externalEdges.add(decoded(f[1]));
                            default -> throw new IOException("Unknown journal record");
                        }
                    }
                }
            } catch (RuntimeException e) { throw new IOException("Invalid recovery journal; originals preserved", e); }
            result.queued.clear(); result.queued.addAll(result.visited);
            for (CrawlItem item : result.queue) {
                if (!result.queued.add(item.url())) throw new IOException("Duplicate/visited queue item in journal");
            }
            result.pages = meta.pages; result.state = meta.state;
            result.sizes.clear(); result.sizes.putAll(meta.sizes);
            result.sequence = committed; result.journalInitialized = true;
            result.previousQueue = new ArrayList<>(result.queue); result.previousVisited = new HashSet<>(result.visited);
            return result;
        }

        private static Checkpoint readSnapshot(Path directory, List<String> lines) throws IOException {
            Checkpoint result = new Checkpoint(directory, new ArrayDeque<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashSet<>(), -1);
            boolean version = false, config = false;
            try {
                for (String line : lines) {
                    String[] f = line.split("\t", -1);
                    switch (f[0]) {
                        case "VERSION" -> version = f[1].equals("1");
                        case "STATE" -> result.state = f[1];
                        case "CONFIG" -> config = decoded(f[1]).equals(START_URL)
                            && Integer.parseInt(f[2]) == MAX_PAGES && Integer.parseInt(f[3]) == MAX_DEPTH
                            && Integer.parseInt(f[4]) == MAX_REQUESTS;
                        case "PAGES" -> result.pages = Integer.parseInt(f[1]);
                        case "OUTPUT" -> {
                            if (!OUTPUT_FILES.contains(f[1])) throw new IllegalArgumentException("Unknown output");
                            long size = Long.parseLong(f[2]);
                            if (size < 0) throw new IllegalArgumentException("Negative output size");
                            result.sizes.put(f[1], size);
                        }
                        case "QUEUE" -> {
                            String url = decoded(f[1]); int depth = Integer.parseInt(f[2]);
                            if (!isRelevantEntityUrl(url) || depth < 0 || depth > MAX_DEPTH)
                                throw new IllegalArgumentException("Invalid queued URL/depth");
                            result.queue.add(new CrawlItem(url, depth));
                        }
                        case "QUEUED" -> result.queued.add(decoded(f[1]));
                        case "VISITED" -> result.visited.add(decoded(f[1]));
                        case "INTERNAL" -> result.internalEdges.add(decoded(f[1]));
                        case "EXTERNAL" -> result.externalEdges.add(decoded(f[1]));
                        default -> throw new IllegalArgumentException("Unknown checkpoint record");
                    }
                }
                if (!version || !config || result.state == null || result.pages < 0 || result.pages > MAX_PAGES
                    || result.sizes.size() != OUTPUT_FILES.size()) throw new IllegalArgumentException("Incompatible checkpoint");
                for (CrawlItem item : result.queue) if (!result.queued.contains(item.url()) || result.visited.contains(item.url()))
                    throw new IllegalArgumentException("Inconsistent queue");
                return result;
            } catch (RuntimeException e) { throw new IOException("Invalid/incompatible checkpoint; originals preserved", e); }
        }

        void restoreOutputs() throws IOException {
            // Validate everything before truncating anything.
            for (String name : OUTPUT_FILES) if (Files.size(directory.resolve(name)) < sizes.get(name))
                throw new IOException("Committed output is missing/truncated: " + name);
            for (int id = 1; id <= pages; id++) if (!Files.isRegularFile(
                    directory.resolve("html").resolve(String.format("%04d.html", id))))
                throw new IOException("Committed HTML is missing: " + id);
            for (String name : OUTPUT_FILES) try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    directory.resolve(name), StandardOpenOption.WRITE)) {
                channel.truncate(sizes.get(name)); channel.force(true);
            }
            // Remove only uncommitted numbered HTML files within this run's html directory.
            try (var files = Files.list(directory.resolve("html"))) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (name.matches("[0-9]+\\.html") && Integer.parseInt(name.substring(0, name.indexOf('.'))) > pages)
                        Files.delete(file);
                }
            }
        }
    }

    private static void validateConfiguration() {
        if (
            MAX_PAGES < 1
                || MAX_DEPTH < 0
                || MAX_REQUESTS < MAX_PAGES || MAX_FRONTIER < 1 || MIN_FREE_BYTES < 128L * 1048576
                || MIN_DELAY_MS < 3000
        ) {
            throw new IllegalStateException(
                "Unsafe crawler configuration."
            );
        }
    }

    private static RobotsFetch fetchRobotsTxt() throws IOException, InterruptedException, StopCrawlException {
        return transport.robots();
    }

    private static RobotsFetch fetchLiveRobotsTxt()
        throws IOException,
        InterruptedException,
        StopCrawlException {

        String robotsUrl =
            "https://"
                + ALLOWED_HOST
                + "/robots.txt";

        HttpResponse<byte[]> response =
            sendGetRequest(
                robotsUrl,
                "text/plain,*/*"
            );

        int statusCode =
            response.statusCode();

        if (
            statusCode == 429
                || statusCode >= 500
                || statusCode == 403
        ) {
            throw new StopCrawlException(
                Integer.toString(
                    statusCode
                ),
                "robots.txt request returned HTTP "
                    + statusCode
            );
        }

        if (statusCode != 200) {
            throw new IOException(
                "Could not safely load robots.txt. HTTP "
                    + statusCode
            );
        }

        String body =
            new String(
                response.body(),
                StandardCharsets.UTF_8
            );

        String robotsContentType = response.headers().firstValue("Content-Type").orElse("")
            .toLowerCase(java.util.Locale.ROOT);
        if (!robotsContentType.startsWith("text/plain") || body.replaceFirst("^\uFEFF", "").stripLeading().startsWith("<")
            || isChallengePage(body))
            throw new StopCrawlException("200", "robots.txt is not a usable robots policy");
        return new RobotsFetch(body);
    }

    private static PageResponse downloadPage(String url) throws IOException, InterruptedException, StopCrawlException {
        return transport.page(url);
    }

    private static PageResponse downloadLivePage(
        String url
    ) throws IOException,
        InterruptedException,
        StopCrawlException {

        if (!isRelevantEntityUrl(url)) throw new IOException("Refusing an out-of-scope page.");

        if (browserTransport == null) browserTransport = new BrowserTransport();
        return browserTransport.download(url);
    }

    /** Browser requests are intercepted so redirects, verification and budgets stay controlled. */
    private static final class BrowserTransport implements AutoCloseable {
        private final com.microsoft.playwright.Playwright playwright;
        private final com.microsoft.playwright.Browser browser;
        private final com.microsoft.playwright.BrowserContext context;
        private final com.microsoft.playwright.Page page;
        private String stopReason;
        private int lastStatus;
        private String lastContentType;

        BrowserTransport() {
            playwright = com.microsoft.playwright.Playwright.create(
                new com.microsoft.playwright.Playwright.CreateOptions().setEnv(
                    java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            try {
                browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions()
                    .setHeadless(true).setChromiumSandbox(true));
                context = browser.newContext(new com.microsoft.playwright.Browser.NewContextOptions()
                    .setUserAgent(USER_AGENT).setAcceptDownloads(false)
                    .setServiceWorkers(com.microsoft.playwright.options.ServiceWorkerPolicy.BLOCK));
                context.routeWebSocket("**/*", socket -> socket.close());
                context.route("**/*", route -> {
                    com.microsoft.playwright.Request request = route.request();
                    String path = URI.create(request.url()).getPath();
                    boolean document = request.isNavigationRequest() && request.resourceType().equals("document")
                        && request.frame() == pageFrame();
                    boolean verification = "/__meb_verify".equals(path) && request.method().equals("POST");
                    // No images, ads, third-party calls, XHR, or background scripts.
                    // Inline scripts in the normal document still run in Chromium.
                    if (!(document && (request.method().equals("GET") || verification))
                        || !isInternal(request.url())
                        || (!verification && !isRelevantEntityUrl(request.url()))
                        || !activeRobots.isAllowed(request.url())) {
                        if (document) stopReason = "Navigation refused by origin, method or robots policy: " + request.url();
                        route.abort(); return;
                    }
                    try {
                        if (robotsRefreshDue()) refreshRobots();
                        if (!activeRobots.isAllowed(request.url())) {
                            stopReason = "Navigation blocked by refreshed robots.txt"; route.abort(); return;
                        }
                        waitForRequestSlot();
                        if (requestCount >= MAX_REQUESTS) {
                            stopReason = "Total request budget exhausted";
                            route.abort(); return;
                        }
                        reserveRequest(request.url(), request.method());
                        com.microsoft.playwright.APIResponse response = route.fetch(
                            new com.microsoft.playwright.Route.FetchOptions().setMaxRedirects(0)
                                .setMaxRetries(0).setTimeout(30000));
                        try {
                            int status = response.status();
                            if (response.body().length > MAX_PAGE_BYTES) {
                                stopReason = "Response exceeds 25 MiB page limit"; route.abort(); return;
                            }
                            if (status == 403 || status == 429 || status >= 500) {
                                lastStatus = status;
                                stopReason = "MusicBrainz returned HTTP " + status + "; no retry attempted";
                                route.abort(); return;
                            }
                            if (status >= 300 && status < 400) {
                                String location = response.headers().get("location");
                                String target = location == null ? null : normalizeUrl(request.url(), location);
                                if ((status != 301 && status != 302 && status != 303)
                                    || target == null || !isInternal(target) || !activeRobots.isAllowed(target)) {
                                    lastStatus = status;
                                    stopReason = "Unsafe or unsupported redirect refused";
                                    route.abort(); return;
                                }
                                // Browser-native HTTP redirects may skip Playwright routing.
                                // Turn the redirect into a document navigation so the next GET
                                // is intercepted, checked and paced like every other request.
                                String escaped = target.replace("&", "&amp;").replace("\"", "&quot;")
                                    .replace("<", "&lt;").replace(">", "&gt;");
                                route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                                    .setStatus(200).setContentType("text/html; charset=utf-8")
                                    .setBody("<!doctype html><meta http-equiv=\"refresh\" content=\"0;url="
                                        + escaped + "\"><title>Following permitted redirect</title>"));
                                return;
                            }
                            lastStatus = status;
                            lastContentType = response.headers().getOrDefault("content-type", "");
                            route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setResponse(response));
                        } finally { response.dispose(); }
                    } catch (StopCrawlException e) {
                        lastStatus = Integer.parseInt(e.statusCode());
                        stopReason = e.getMessage(); route.abort();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        stopReason = "Interrupted"; route.abort();
                    } catch (Exception e) {
                        stopReason = "Browser request failed: " + e.getMessage(); route.abort();
                    }
                });
                page = context.newPage();
                page.onDialog(dialog -> dialog.dismiss());
                context.onPage(extra -> { if (extra != page) extra.close(); });
            } catch (RuntimeException e) { playwright.close(); throw e; }
        }

        private com.microsoft.playwright.Frame pageFrame() { return page == null ? null : page.mainFrame(); }

        PageResponse download(String url) throws IOException, StopCrawlException {
            stopReason = null; lastStatus = 0; lastContentType = "";
            try {
                page.navigate(url, new com.microsoft.playwright.Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(45000));
                if (stopReason == null && lastStatus >= 400 && lastStatus < 500)
                    throw new IOException("HTTP " + lastStatus);
                try {
                    // A verification POST can trigger a new navigation after initial DOMContentLoaded.
                    // Wait for the actual entity document, not a transient DOM during the redirect.
                    page.waitForFunction("() => document.readyState !== 'loading' && "
                        + "/MusicBrainz/i.test(document.title) && "
                        + "!/verifying|just a moment|access denied/i.test(document.title)",
                        null, new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(30000));
                } catch (com.microsoft.playwright.TimeoutError e) {
                    if (stopReason == null && lastStatus >= 400 && lastStatus < 500)
                        throw new IOException("HTTP " + lastStatus);
                    if (stopReason == null && !isChallengePage(page.content()))
                        throw new StopCrawlException(Integer.toString(lastStatus),
                            "Expected MusicBrainz document did not load within 30 seconds");
                }
                if (stopReason != null) throw new StopCrawlException(Integer.toString(lastStatus), stopReason);
                if (isChallengePage(page.content()))
                    return new PageResponse(lastStatus, lastContentType, page.content().getBytes(StandardCharsets.UTF_8));
                String finalUrl = normalizeUrl(url, page.url());
                if (!isInternal(finalUrl) || !isRelevantEntityUrl(finalUrl))
                    throw new IOException("Unexpected final page: " + page.url());
                if (!url.equals(finalUrl)) throw new IOException("Redirected entity skipped: " + finalUrl);
                if (lastStatus < 200 || lastStatus >= 300) throw new IOException("HTTP " + lastStatus);
                if (!lastContentType.toLowerCase(java.util.Locale.ROOT).matches(".*(?:text/html|application/xhtml\\+xml).*"))
                    throw new IOException("Not HTML: " + lastContentType);
                byte[] rendered = page.content().getBytes(StandardCharsets.UTF_8);
                if (rendered.length > MAX_PAGE_BYTES) throw new IOException("Rendered HTML exceeds 25 MiB");
                return new PageResponse(lastStatus, lastContentType, rendered);
            } catch (com.microsoft.playwright.PlaywrightException e) {
                throw new StopCrawlException(Integer.toString(lastStatus),
                    stopReason != null ? stopReason : "Browser navigation failed: " + e.getMessage());
            }
        }

        public void close() {
            try { context.close(); }
            finally {
                try { browser.close(); }
                finally { playwright.close(); }
            }
        }
    }

    private static HttpResponse<byte[]>
        sendGetRequest(
            String url,
            String accept
        ) throws IOException,
            InterruptedException {

        waitForRequestSlot();
        reserveRequest(url, "GET");

        HttpRequest request =
            HttpRequest.newBuilder()
                .uri(
                    URI.create(url)
                )
                .timeout(
                    Duration.ofSeconds(
                        30
                    )
                )
                .header(
                    "User-Agent",
                    USER_AGENT
                )
                .header(
                    "Accept",
                    accept
                )
                .GET()
                .build();

        return HTTP_CLIENT.send(
            request,
            boundedBodyHandler(1024 * 1024)
        );
    }

    private static void waitForRequestSlot()
        throws InterruptedException {

        if (
            lastRequestStartedAtMs == 0
        ) {
            return;
        }

        long elapsed = Math.max(0, System.currentTimeMillis() - lastRequestStartedAtMs);

        long remaining =
            requestDelayMs
                - elapsed;

        if (remaining > 0) {
            Thread.sleep(remaining);
        }
    }

    private static Set<String> extractLinks(
        String html,
        String currentUrl
    ) {
        Set<String> links =
            new LinkedHashSet<>();

        try {
            new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
                @Override
                public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int position) {
                    if (tag == HTML.Tag.A) {
                        Object href = attrs.getAttribute(HTML.Attribute.HREF);
                        String normalized = href == null ? null : normalizeUrl(currentUrl, href.toString());
                        if (normalized != null) links.add(normalized);
                    }
                }
            }, true);
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse HTML", e);
        }

        return links;
    }

    private static boolean isChallengePage(String html) {
        Matcher title = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (!title.find()) return false;
        String text = title.group(1).trim().toLowerCase(java.util.Locale.ROOT);
        return text.contains("verifying your browser") || text.contains("just a moment")
            || text.contains("access denied") || text.contains("making sure you're not a bot")
            || text.contains("verify you are human");
    }

    private static String normalizeUrl(
        String currentUrl,
        String href
    ) {
        if (
            href == null
                || href.isBlank()
        ) {
            return null;
        }

        href =
            decodeBasicHtmlEntities(
                href.trim()
            );

        String lower =
            href.toLowerCase();

        if (
            lower.startsWith("mailto:")
                || lower.startsWith("tel:")
                || lower.startsWith(
                    "javascript:"
                )
                || lower.startsWith(
                    "data:"
                )
        ) {
            return null;
        }

        try {
            URI resolved =
                URI.create(currentUrl)
                    .resolve(href)
                    .normalize();

            String scheme =
                resolved.getScheme();

            String host =
                resolved.getHost();

            if (
                scheme == null
                    || host == null
                    || (
                        !scheme.equalsIgnoreCase(
                            "http"
                        )
                            && !scheme
                                .equalsIgnoreCase(
                                    "https"
                                )
                    )
            ) {
                return null;
            }

            if (resolved.getRawUserInfo() != null) return null;
            int port = resolved.getPort();
            if ((scheme.equalsIgnoreCase("https") && port == 443) || (scheme.equalsIgnoreCase("http") && port == 80)) port = -1;
            String path = resolved.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (resolved.getPath() != null && ENTITY_PATH_PATTERN.matcher(resolved.getPath()).matches())
                path = resolved.getPath().toLowerCase(java.util.Locale.ROOT).replaceFirst("/$", "");
            String authority = host.toLowerCase(java.util.Locale.ROOT);
            if (authority.contains(":")) authority = "[" + authority + "]";
            return URI.create(scheme.toLowerCase(java.util.Locale.ROOT) + "://" + authority
                + (port == -1 ? "" : ":" + port) + path
                + (resolved.getRawQuery() == null ? "" : "?" + resolved.getRawQuery())).toASCIIString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String decodeBasicHtmlEntities(
        String value
    ) {
        return value
            .replace("&amp;", "&")
            .replace("&#38;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");
    }

    private static boolean isInternal(
        String url
    ) {
        try {
            String host =
                URI.create(url)
                    .getHost();

            return host != null
                && host.equalsIgnoreCase(
                    ALLOWED_HOST
                ) && "https".equalsIgnoreCase(URI.create(url).getScheme())
                && (URI.create(url).getPort() == -1 || URI.create(url).getPort() == 443)
                && URI.create(url).getRawUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isRelevantEntityUrl(
        String url
    ) {
        try {
            URI uri =
                URI.create(url);

            if (
                !isInternal(url)
                    || uri.getQuery()
                        != null
            ) {
                return false;
            }

            String path =
                uri.getPath();

            return path != null
                && ENTITY_PATH_PATTERN
                    .matcher(path)
                    .matches();
        } catch (Exception e) {
            return false;
        }
    }

    private static String getHost(
        String url
    ) {
        try {
            String host =
                URI.create(url)
                    .getHost();

            return host == null
                ? ""
                : host;
        } catch (Exception e) {
            return "";
        }
    }

    private static String classifyExternalLink(
        String url
    ) {
        String host =
            getHost(url)
                .toLowerCase();

        if (
            host.equals("wikipedia.org")
                || host.endsWith(
                    ".wikipedia.org"
                )
        ) {
            return "WIKIPEDIA";
        }

        if (
            host.equals("wikidata.org")
                || host.endsWith(
                    ".wikidata.org"
                )
        ) {
            return "WIKIDATA";
        }

        return "OTHER";
    }

    private static Path createOutputDirectory()
        throws IOException {

        String timestamp =
            LocalDateTime.now()
                .format(
                    DateTimeFormatter
                        .ofPattern(
                            "yyyy-MM-dd_HH-mm-ss"
                        )
                );

        Path directory =
            OUTPUT_ROOT.resolve(timestamp);

        Files.createDirectories(directory.getParent());
        return Files.createTempDirectory(directory.getParent(), timestamp + "_");
    }

    private static void writeLine(
        Path file,
        String line
    ) throws IOException {

        Files.writeString(
            file,
            line
                + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }

    private static void writeCrawlLog(
        Path file,
        String url,
        int depth,
        String result,
        String status,
        String detail
    ) throws IOException {

        writeLine(
            file,
            tsv(
                Instant.now()
                    .toString()
            )
                + "\t"
                + tsv(url)
                + "\t"
                + depth
                + "\t"
                + tsv(result)
                + "\t"
                + tsv(status)
                + "\t"
                + tsv(detail)
        );
    }

    private static String tsv(
        String value
    ) {
        if (value == null) {
            return "";
        }

        return value
            .replace("\t", " ")
            .replace("\r", " ")
            .replace("\n", " ");
    }

    private record CrawlItem(
        String url,
        int depth
    ) {}

    private record PageResponse(
        int statusCode,
        String contentType,
        byte[] body
    ) {}

    private record RobotsFetch(
        String body
    ) {}

    private record RobotsRule(
        boolean allow,
        String pattern
    ) {}

    private static class StopCrawlException
        extends Exception {

        private final String statusCode;

        public StopCrawlException(
            String statusCode,
            String message
        ) {
            super(message);
            this.statusCode =
                statusCode;
        }

        public String statusCode() {
            return statusCode;
        }
    }

    private static final java.util.Map<String, Pattern> ROBOTS_PATTERNS = new java.util.HashMap<>();

    private static String normalizeRobotsPath(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length();) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16), low = Character.digit(value.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    int decoded = high * 16 + low;
                    if ((decoded >= 'a' && decoded <= 'z') || (decoded >= 'A' && decoded <= 'Z')
                        || (decoded >= '0' && decoded <= '9') || "-._~".indexOf(decoded) >= 0)
                        result.append((char) decoded);
                    else result.append(String.format(java.util.Locale.ROOT, "%%%02X", decoded));
                    i += 3; continue;
                }
            }
            int codepoint = value.codePointAt(i);
            if (codepoint > 127) {
                for (byte b : new String(Character.toChars(codepoint)).getBytes(StandardCharsets.UTF_8))
                    result.append(String.format(java.util.Locale.ROOT, "%%%02X", b & 255));
            } else result.append(c);
            i += Character.charCount(codepoint);
        }
        return result.toString();
    }

    private record RobotsRules(
        List<RobotsRule> rules,
        long crawlDelayMs
    ) {
        private static RobotsRules parse(
            String robotsText
        ) {
            List<RobotsRule> selected = new ArrayList<>();
            List<String> agents = new ArrayList<>();
            List<RobotsRule> groupRules = new ArrayList<>();
            long delay = MIN_DELAY_MS, groupDelay = MIN_DELAY_MS;
            int bestMatch = -1;
            boolean directives = false;
            String token = "schoolalbumcrawler";
            // Sentinel flushes the last group, including groups with empty rules.
            for (String original : (robotsText.replaceFirst("^\uFEFF", "") + "\nUser-agent: __end__\nDisallow:").split("\\R")) {
                String line = stripComment(original).trim();
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (key.equals("user-agent")) {
                    if (directives || value.equals("__end__")) {
                        int match = agents.stream().mapToInt(agent -> agent.equals(token) ? token.length()
                            : agent.equals("*") ? 0 : -1).max().orElse(-1);
                        if (match >= 0 && match >= bestMatch) {
                            if (match > bestMatch) { selected.clear(); delay = MIN_DELAY_MS; }
                            selected.addAll(groupRules);
                            delay = Math.max(delay, groupDelay);
                            bestMatch = match;
                        }
                        agents.clear(); groupRules.clear(); groupDelay = MIN_DELAY_MS;
                        directives = false;
                    }
                    agents.add(value.toLowerCase(java.util.Locale.ROOT));
                } else if (!agents.isEmpty()) {
                    if (key.equals("allow") || key.equals("disallow")) {
                        directives = true;
                        if (value.startsWith("/")) groupRules.add(new RobotsRule(key.equals("allow"), value));
                    } else if (key.equals("crawl-delay")) {
                        directives = true;
                        try {
                            double seconds = Double.parseDouble(value);
                            if (Double.isFinite(seconds) && seconds >= 0)
                                groupDelay = Math.max(groupDelay, (long) Math.ceil(seconds * 1000));
                        } catch (NumberFormatException ignored) { }
                    }
                }
            }
            return new RobotsRules(selected, delay);
        }

        private static String stripComment(String line) {
            int hashIndex = line.indexOf('#');
            if (hashIndex >= 0) {
                return line.substring(0, hashIndex);
            }
            return line;
        }

        public boolean isAllowed(
            String url
        ) {
            try {
                URI uri =
                    URI.create(url);

                String path =
                    uri.getRawPath();

                if (
                    path == null
                        || path.isEmpty()
                ) {
                    path = "/";
                }

                String query =
                    uri.getRawQuery();

                String pathAndQuery =
                    query == null
                        ? path
                        : path
                            + "?"
                            + query;

                pathAndQuery = normalizeRobotsPath(pathAndQuery);
                RobotsRule bestRule = null;

                int bestSpecificity = -1;

                for (
                    RobotsRule rule
                        : rules
                ) {
                    if (
                        !matchesRobotsRule(
                            pathAndQuery,
                            rule.pattern()
                        )
                    ) {
                        continue;
                    }

                    int specificity =
                        robotsRuleSpecificity(
                            rule.pattern()
                        );

                    if (
                        specificity
                            > bestSpecificity
                            || (
                                specificity
                                    == bestSpecificity
                                    && rule
                                        .allow()
                            )
                    ) {
                        bestSpecificity = specificity;
                        bestRule = rule;
                    }
                }

                if (bestRule == null) {
                    return true;
                }

                return bestRule.allow();
            } catch (Exception e) {
                return false;
            }
        }

        private static boolean matchesRobotsRule(String pathAndQuery, String pattern) {
            pattern = normalizeRobotsPath(pattern);
            Pattern cached = ROBOTS_PATTERNS.get(pattern);
            if (cached != null) return cached.matcher(pathAndQuery).find();
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '*') regex.append(".*");
                else if (c == '$' && i == pattern.length() - 1) regex.append('$');
                else regex.append(Pattern.quote(String.valueOf(c)));
            }
            Pattern compiled = Pattern.compile(regex.toString());
            if (ROBOTS_PATTERNS.size() >= 256) ROBOTS_PATTERNS.clear();
            ROBOTS_PATTERNS.put(pattern, compiled);
            return compiled.matcher(pathAndQuery).find();
        }

        private static int robotsRuleSpecificity(String pattern) {
            return normalizeRobotsPath(pattern).replace("*", "").replaceAll("\\$$", "")
                .getBytes(StandardCharsets.UTF_8).length;
        }
    }
}
