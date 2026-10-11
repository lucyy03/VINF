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
    private static final String START_URL =
        "https://musicbrainz.org/release-group/b1392450-e666-3926-a536-22c65f834433";

    private static final String ALLOWED_HOST = "musicbrainz.org";

    private static final String USER_AGENT =
        "SchoolAlbumCrawler/2.0 (STUBA educational HTML crawl"
            + (System.getenv("CRAWLER_CONTACT") == null ? "" : "; " + System.getenv("CRAWLER_CONTACT")) + ")";

    private static final int MAX_PAGES = 100;
    private static final int MAX_DEPTH = 2;
    // Includes robots.txt, verification POSTs, redirects and unsuccessful requests.
    private static final int MAX_REQUESTS = 350;
    private static int requestCount = 0;

    private static final long MIN_DELAY_MS = 3000;

    private static final Pattern ENTITY_PATH_PATTERN = Pattern.compile(
        "^/(release-group|release|artist)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/?$"
    );

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private static BrowserTransport browserTransport;
    private static RobotsRules activeRobots;

    private static long requestDelayMs = MIN_DELAY_MS;
    private static long lastRequestStartedAtMs = 0;

    public static void main(String[] args) {
        try {
            runCrawler();
        } catch (Exception e) {
            System.err.println("Crawler stopped: " + e.getMessage());
            e.printStackTrace();
            throw new IllegalStateException("Crawler failed", e);
        } finally {
            if (browserTransport != null) browserTransport.close();
        }
    }

    private static void runCrawler() throws Exception {
        validateConfiguration();

        Path outputDirectory = createOutputDirectory();
        Path htmlDirectory = outputDirectory.resolve("html");

        Files.createDirectories(htmlDirectory);

        Path pagesFile = outputDirectory.resolve("pages.tsv");
        Path internalLinksFile = outputDirectory.resolve("internal_links.tsv");
        Path externalLinksFile = outputDirectory.resolve("external_links.tsv");
        Path crawlLogFile = outputDirectory.resolve("crawl_log.tsv");
        Path robotsFile = outputDirectory.resolve("robots.txt");

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

        System.out.println("Loading robots.txt...");

        RobotsFetch robotsFetch = fetchRobotsTxt();

        Files.writeString(
            robotsFile,
            robotsFetch.body(),
            StandardCharsets.UTF_8
        );

        RobotsRules robotsRules =
            RobotsRules.parse(robotsFetch.body());

        activeRobots = robotsRules;

        requestDelayMs = Math.max(
            MIN_DELAY_MS,
            robotsRules.crawlDelayMs()
        );

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

        Queue<CrawlItem> queue = new ArrayDeque<>();

        Set<String> queued = new HashSet<>();
        Set<String> visited = new HashSet<>();

        Set<String> savedInternalEdges =
            new HashSet<>();

        Set<String> savedExternalEdges =
            new HashSet<>();

        queue.add(
            new CrawlItem(startUrl, 0)
        );

        queued.add(startUrl);

        int successfulPages = 0;

        while (
            !queue.isEmpty()
                && successfulPages < MAX_PAGES
                && requestCount < MAX_REQUESTS
        ) {
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
                                .contains(link);

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

        System.out.println(
            "Crawler finished."
        );

        System.out.println(
            "Successful pages: "
                + successfulPages
        );
        System.out.println("Total requests: " + requestCount + "/" + MAX_REQUESTS);
        if (requestCount >= MAX_REQUESTS && successfulPages < MAX_PAGES) {
            System.out.println("Stopped at the request budget; page target was not reached.");
        } else if (queue.isEmpty() && successfulPages < MAX_PAGES) {
            System.out.println("No more eligible pages within the configured depth.");
        }

        System.out.println(
            "Output directory: "
                + outputDirectory
                    .toAbsolutePath()
        );
    }

    private static void validateConfiguration() {
        if (
            USER_AGENT.contains(
                "YOUR_EMAIL"
            )
        ) {
            throw new IllegalStateException(
                "Replace YOUR_EMAIL@example.com in USER_AGENT with a real contact before running."
            );
        }

        if (
            MAX_PAGES < 1
                || MAX_DEPTH < 0
                || MIN_DELAY_MS < 3000
        ) {
            throw new IllegalStateException(
                "Unsafe crawler configuration."
            );
        }
    }

    private static RobotsFetch fetchRobotsTxt()
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
                || statusCode == 503
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

        return new RobotsFetch(body);
    }

    private static PageResponse downloadPage(
        String url
    ) throws IOException,
        InterruptedException,
        StopCrawlException {

        if (!isInternal(url)) {
            throw new IOException(
                "Refusing to request an external host."
            );
        }

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
                context.route("**/*", route -> {
                    com.microsoft.playwright.Request request = route.request();
                    String path = URI.create(request.url()).getPath();
                    boolean document = request.isNavigationRequest() && request.resourceType().equals("document");
                    boolean verification = "/__meb_verify".equals(path) && request.method().equals("POST");
                    // No images, ads, third-party calls, XHR, or background scripts.
                    // Inline scripts in the normal document still run in Chromium.
                    if (!(document && (request.method().equals("GET") || verification))
                        || !isInternal(request.url()) || !activeRobots.isAllowed(request.url())) {
                        if (document) stopReason = "Navigation refused by origin, method or robots policy: " + request.url();
                        route.abort(); return;
                    }
                    try {
                        waitForRequestSlot();
                        if (requestCount >= MAX_REQUESTS) {
                            stopReason = "Total request budget exhausted";
                            route.abort(); return;
                        }
                        requestCount++;
                        lastRequestStartedAtMs = System.currentTimeMillis();
                        com.microsoft.playwright.APIResponse response = route.fetch(
                            new com.microsoft.playwright.Route.FetchOptions().setMaxRedirects(0)
                                .setMaxRetries(0).setTimeout(30000));
                        try {
                            int status = response.status();
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
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        stopReason = "Interrupted"; route.abort();
                    } catch (Exception e) {
                        stopReason = "Browser request failed: " + e.getMessage(); route.abort();
                    }
                });
                page = context.newPage();
            } catch (RuntimeException e) { playwright.close(); throw e; }
        }

        PageResponse download(String url) throws IOException, StopCrawlException {
            stopReason = null; lastStatus = 0; lastContentType = "";
            try {
                page.navigate(url, new com.microsoft.playwright.Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(45000));
                try {
                    // A verification POST can trigger a new navigation after initial DOMContentLoaded.
                    // Wait for the actual entity document, not a transient DOM during the redirect.
                    page.waitForFunction("() => document.readyState !== 'loading' && "
                        + "/MusicBrainz/i.test(document.title) && "
                        + "!/verifying|just a moment|access denied/i.test(document.title)",
                        null, new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(30000));
                } catch (com.microsoft.playwright.TimeoutError e) {
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
                return new PageResponse(lastStatus, lastContentType, page.content().getBytes(StandardCharsets.UTF_8));
            } catch (com.microsoft.playwright.PlaywrightException e) {
                throw new StopCrawlException(Integer.toString(lastStatus),
                    stopReason != null ? stopReason : "Browser navigation failed: " + e.getMessage());
            }
        }

        public void close() { playwright.close(); }
    }

    private static HttpResponse<byte[]>
        sendGetRequest(
            String url,
            String accept
        ) throws IOException,
            InterruptedException {

        waitForRequestSlot();
        if (++requestCount > MAX_REQUESTS) {
            throw new IOException("Total request budget exhausted (including robots.txt).");
        }

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

        lastRequestStartedAtMs =
            System.currentTimeMillis();

        return HTTP_CLIENT.send(
            request,
            HttpResponse
                .BodyHandlers
                .ofByteArray()
        );
    }

    private static void waitForRequestSlot()
        throws InterruptedException {

        if (
            lastRequestStartedAtMs == 0
        ) {
            return;
        }

        long elapsed =
            System.currentTimeMillis()
                - lastRequestStartedAtMs;

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

            URI normalized =
                new URI(
                    resolved
                        .getScheme()
                        .toLowerCase(),
                    null,
                    host.toLowerCase(),
                    resolved.getPort(),
                    resolved.getPath(),
                    resolved.getQuery(),
                    null
                );

            return normalized.toString();
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
            Path.of(
                "crawler-output",
                timestamp
            );

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
            for (String original : (robotsText + "\nUser-agent: __end__\nDisallow:").split("\\R")) {
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
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '*') regex.append(".*");
                else if (c == '$' && i == pattern.length() - 1) regex.append('$');
                else regex.append(Pattern.quote(String.valueOf(c)));
            }
            return Pattern.compile(regex.toString()).matcher(pathAndQuery).find();
        }

        private static int robotsRuleSpecificity(String pattern) {
            return pattern.replace("*", "").replaceAll("\\$$", "")
                .getBytes(StandardCharsets.UTF_8).length;
        }
    }
}
