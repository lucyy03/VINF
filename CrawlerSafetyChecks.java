import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Small offline scenarios for frontier, budget, policy refresh, disk and browser guards. */
public class CrawlerSafetyChecks {
    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        var method = MusicBrainzCrawler.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception failure) throw failure;
            throw new AssertionError(e.getCause());
        }
    }
    private static Object get(String name) throws Exception {
        var f = MusicBrainzCrawler.class.getDeclaredField(name); f.setAccessible(true); return f.get(null);
    }
    private static void set(String name, Object value) throws Exception {
        var f = MusicBrainzCrawler.class.getDeclaredField(name); f.setAccessible(true); f.set(null, value);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static String url(int n) {
        return "https://musicbrainz.org/artist/00000000-0000-0000-0000-" + String.format("%012x", n);
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--browser")) { browserGuards(); return; }
        Files.createDirectories(Path.of("target"));
        Path work = Files.createTempDirectory(Path.of("target"), "safety-check-").toAbsolutePath();
        Object originalRoot = get("OUTPUT_ROOT"), originalTransport = get("transport");
        Class<?> transportClass = Class.forName("MusicBrainzCrawler$CrawlTransport");
        var robots = Class.forName("MusicBrainzCrawler$RobotsFetch").getDeclaredConstructor(String.class); robots.setAccessible(true);
        var response = Class.forName("MusicBrainzCrawler$PageResponse").getDeclaredConstructor(int.class, String.class, byte[].class);
        response.setAccessible(true);
        var stop = Class.forName("MusicBrainzCrawler$StopCrawlException").getDeclaredConstructor(String.class, String.class);
        stop.setAccessible(true);
        int[] pages = {0}, loads = {0};
        String[] scenario = {""};
        Object fake = Proxy.newProxyInstance(transportClass.getClassLoader(), new Class<?>[]{transportClass}, (proxy, method, values) -> {
            call("reserveRequest", new Class<?>[]{});
            if (method.getName().equals("robots")) {
                loads[0]++;
                return robots.newInstance("User-agent: *\nDisallow: "
                    + (scenario[0].equals("refresh") && loads[0] > 1 ? "/" : "/blocked/") + "\n");
            }
            pages[0]++;
            if (scenario[0].equals("blocked")) throw (Throwable) stop.newInstance("429", "HTTP 429; no retry");
            if (scenario[0].equals("refresh") && pages[0] == 5) set("robotsLoadedAtMs", 0L);
            String html = "<html><title>Fixture - MusicBrainz</title><body><a href='" + url(pages[0])
                + "'>one</a><a href='" + url(pages[0] + 1) + "'>two</a></body></html>";
            return response.newInstance(200, "text/html", html.getBytes(StandardCharsets.UTF_8));
        });
        try {
            for (String name : new String[]{"artist-limit", "frontier", "depth", "budget", "blocked", "refresh", "disk", "complete-crash"}) {
                scenario[0] = name; pages[0] = 0; loads[0] = 0;
                Path root = Files.createDirectories(work.resolve(name));
                set("OUTPUT_ROOT", root); set("transport", fake); set("requestCount", 0);
                set("activeRobots", null); set("checkpointDirectory", null); set("lastRequestStartedAtMs", 0L);
                set("MIN_FREE_BYTES", 512L * 1048576); set("frontierDeferred", 0L);
                String[] options = switch (name) {
                    case "artist-limit" -> new String[]{"--fresh", "--breadth", "--artist-links", "1", "--pages", "10", "--depth", "100"};
                    case "frontier" -> new String[]{"--fresh", "--pages", "30", "--depth", "100", "--max-frontier", "1"};
                    case "depth" -> new String[]{"--fresh", "--pages", "10", "--depth", "0"};
                    case "budget" -> new String[]{"--fresh", "--pages", "10", "--depth", "100", "--max-requests", "10"};
                    case "disk" -> new String[]{"--fresh", "--pages", "10", "--min-free-mib", "2147483647"};
                    default -> new String[]{"--fresh", "--pages", "10", "--depth", "100"};
                };
                call("configure", new Class<?>[]{String[].class}, (Object) options);
                try {
                    call("runCrawler", new Class<?>[]{String[].class}, (Object) new String[]{});
                    check(!name.equals("disk"), "Disk guard must stop before requesting a page");
                } catch (java.io.IOException e) {
                    check(name.equals("disk") && e.getMessage().contains("Low free disk"), "Unexpected I/O failure: " + e);
                }
                Path run = root.resolve(Files.readString(root.resolve("latest_checkpoint.tsv")).strip());
                String manifest = Files.readString(run.resolve("checkpoint.tsv"));
                switch (name) {
                    case "artist-limit" -> {
                        var admissions = new java.util.HashMap<String,Integer>();
                        for (String line : Files.readAllLines(run.resolve("internal_links.tsv")).subList(1, Files.readAllLines(run.resolve("internal_links.tsv")).size())) {
                            var row = line.split("\t");
                            if (row[2].equals("true")) admissions.merge(MusicBrainzUrls.canonical(row[0]),1,Integer::sum);
                        }
                        check(pages[0]==10 && admissions.values().stream().allMatch(n -> n==1),"Artist discovery must obey per-entity cap");
                    }
                    case "frontier" -> check(pages[0] == 30 && (long) get("frontierDeferred") > 0
                        && manifest.contains("STATE\tCOMPLETE"), "Bounded frontier must keep progressing");
                    case "depth" -> check(pages[0] == 1 && manifest.contains("STATE\tEXHAUSTED"), "Depth exhaustion is not target completion");
                    case "budget" -> check(pages[0] == 9 && (int) get("requestCount") == 10
                        && manifest.contains("STATE\tBUDGET_EXHAUSTED"), "Hard cumulative request budget");
                    case "blocked" -> {
                        check(pages[0] == 1 && manifest.contains("STATE\tSTOPPED"), "429 must stop without retries");
                        call("configure", new Class<?>[]{String[].class}, (Object) new String[]{"--recover"});
                        try {
                            call("runCrawler", new Class<?>[]{String[].class}, (Object) new String[]{});
                            throw new AssertionError("Must refuse recovery from site blocking");
                        } catch (java.io.IOException e) { check(e.getMessage().contains("STOPPED"), "Explicit site-stop recovery refusal"); }
                        check(pages[0] == 1 && loads[0] == 1, "Refusal must make no requests");
                    }
                    case "refresh" -> check(pages[0] == 5 && loads[0] == 2, "Changed robots must stop further page requests");
                    case "disk" -> check(pages[0] == 0 && manifest.contains("STATE\tACTIVE"), "Disk stop preserves recoverable checkpoint");
                    case "complete-crash" -> {
                        int completed = pages[0], policyLoads = loads[0];
                        check(manifest.contains("STATE\tCOMPLETE"), "Fixture must have completed");
                        Files.writeString(run.resolve("checkpoint.tsv"), manifest.replace("STATE\tCOMPLETE", "STATE\tACTIVE"));
                        Files.writeString(run.resolve("request_state.tsv"), get("MAX_REQUESTS") + "\t"
                            + System.currentTimeMillis() + "\t3000\n");
                        set("requestCount", 0);
                        call("configure", new Class<?>[]{String[].class}, (Object) new String[]{"--recover"});
                        call("runCrawler", new Class<?>[]{String[].class}, (Object) new String[]{});
                        check(pages[0] == completed && loads[0] == policyLoads
                            && Files.readString(run.resolve("checkpoint.tsv")).contains("STATE\tCOMPLETE"),
                            "Crash after the last page must finalize without HTTP, even with exhausted budget");
                    }
                }
            }
            var normalized = call("normalizeUrl", new Class<?>[]{String.class, String.class},
                "https://musicbrainz.org/", "https://musicbrainz.org:443/a%2Fb?q=x%26y#fragment");
            check(normalized.equals("https://musicbrainz.org/a%2Fb?q=x%26y"), "Preserve reserved percent encoding");
            Class<?> rulesClass = Class.forName("MusicBrainzCrawler$RobotsRules");
            var parse = rulesClass.getDeclaredMethod("parse", String.class); parse.setAccessible(true);
            var allowed = rulesClass.getDeclaredMethod("isAllowed", String.class); allowed.setAccessible(true);
            Object rules = parse.invoke(null, "User-agent: *\nDisallow: /foo\nDisallow: /a%2Fb\nDisallow: /café\n");
            for (String path : Set.of("/%66oo", "/a%2fb", "/caf%C3%A9"))
                check(!(boolean) allowed.invoke(rules, "https://musicbrainz.org" + path), "Encoded robots restriction: " + path);
            check((boolean) allowed.invoke(rules, "https://musicbrainz.org/a/b"), "Encoded reserved slash must stay distinct");
            var handler = (java.net.http.HttpResponse.BodyHandler<?>) call("boundedBodyHandler", new Class<?>[]{int.class}, 10);
            var subscriber = handler.apply(null);
            boolean[] cancelled = {false};
            subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                public void request(long n) { }
                public void cancel() { cancelled[0] = true; }
            });
            subscriber.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[11])));
            try { subscriber.getBody().toCompletableFuture().join(); throw new AssertionError("Oversized response accepted"); }
            catch (java.util.concurrent.CompletionException e) { check(cancelled[0], "Oversized body must cancel download"); }
            System.out.println("Offline crawler safety scenarios passed. Evidence: " + work);
        } finally { set("OUTPUT_ROOT", originalRoot); set("transport", originalTransport); }
    }

    private static void browserGuards() throws Exception {
        set("requestCount", 0); set("checkpointDirectory", null);
        Class<?> rulesClass = Class.forName("MusicBrainzCrawler$RobotsRules");
        var parse = rulesClass.getDeclaredMethod("parse", String.class); parse.setAccessible(true);
        set("activeRobots", parse.invoke(null, "User-agent: *\nDisallow:\n"));
        set("robotsLoadedAtMs", System.currentTimeMillis());
        Class<?> browserClass = Class.forName("MusicBrainzCrawler$BrowserTransport");
        var constructor = browserClass.getDeclaredConstructor(); constructor.setAccessible(true);
        var download = browserClass.getDeclaredMethod("download", String.class); download.setAccessible(true);
        for (int i = 0; i < 2; i++) {
            try (AutoCloseable browser = (AutoCloseable) constructor.newInstance()) {
                try { download.invoke(browser, "https://example.invalid/"); throw new AssertionError("External navigation accepted"); }
                catch (InvocationTargetException e) { check(e.getCause().getMessage().contains("Navigation refused"), "Browser origin guard"); }
            }
        }
        check((int) get("requestCount") == 0, "Blocked browser navigation must not reserve/send a request");
        System.out.println("Chromium launch/close and external-origin guards passed; zero crawler requests.");
    }
}
