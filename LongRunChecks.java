import java.io.BufferedOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/** End-to-end offline workload. Injects documents, never creates a browser or sends HTTP. */
public class LongRunChecks {
    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        var method = MusicBrainzCrawler.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception failure) throw failure;
            throw new AssertionError(e.getCause());
        }
    }

    private static Object get(String name) throws Exception {
        var field = MusicBrainzCrawler.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }

    private static void set(String name, Object value) throws Exception {
        var field = MusicBrainzCrawler.class.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String url(int number) {
        return "https://musicbrainz.org/release-group/00000000-0000-0000-0000-" + String.format("%012x", number);
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("target"));
        Path work = Files.createTempDirectory(Path.of("target"), "long-run-check-").toAbsolutePath();
        Path root = Files.createDirectories(work.resolve("crawler-output"));
        PrintStream console = System.out;
        Object oldRoot = get("OUTPUT_ROOT"), oldTransport = get("transport");
        String start = (String) get("START_URL");
        Set<Integer> returnedDocuments = new HashSet<>();
        int[] robotLoads = {0}, attempts = {0};
        boolean[] crashEnabled = {true};
        Class<?> responseClass = Class.forName("MusicBrainzCrawler$PageResponse");
        var responseConstructor = responseClass.getDeclaredConstructor(int.class, String.class, byte[].class);
        responseConstructor.setAccessible(true);
        Class<?> robotsClass = Class.forName("MusicBrainzCrawler$RobotsFetch");
        var robotsConstructor = robotsClass.getDeclaredConstructor(String.class); robotsConstructor.setAccessible(true);
        Class<?> transportClass = Class.forName("MusicBrainzCrawler$CrawlTransport");
        Object fake = Proxy.newProxyInstance(transportClass.getClassLoader(), new Class<?>[]{transportClass}, (proxy, method, values) -> {
            if (method.getName().equals("robots")) {
                robotLoads[0]++;
                call("reserveRequest", new Class<?>[]{});
                return robotsConstructor.newInstance("User-agent: *\nDisallow: /blocked/\nCrawl-delay: 3\n");
            }
            if (!method.getName().equals("page")) throw new AssertionError("Unexpected transport operation");
            String current = (String) values[0];
            int number = current.equals(start) ? 0 : Integer.parseInt(current.substring(current.length() - 12), 16);
            attempts[0]++;
            call("reserveRequest", new Class<?>[]{});
            if (number == 4321 && crashEnabled[0]) throw new IllegalStateException("SIMULATED_PROCESS_CRASH");
            check(returnedDocuments.add(number), "A committed document was downloaded again: " + number);
            if (number == 6000) set("robotsLoadedAtMs", 0L); // Force hourly-policy refresh branch.
            if (returnedDocuments.size() % 1000 == 0) console.println("Offline documents processed: " + returnedDocuments.size());
            StringBuilder html = new StringBuilder("<!doctype html><html><head><title>Fixture " + number
                + " - MusicBrainz</title></head><body>");
            if (number + 1 < 10000) html.append("<a href=\"").append(url(number + 1)).append("\">next</a>")
                .append("<a href=\"").append(url(number + 1)).append("\">duplicate next</a>");
            if (number + 2 < 10000) html.append("<a href=\"").append(url(number + 2)).append("\">next two</a>");
            html.append("<a href=\"https://example.invalid/\">external: record only</a></body></html>");
            return responseConstructor.newInstance(200, "text/html; charset=utf-8", html.toString().getBytes(StandardCharsets.UTF_8));
        });
        long started = System.nanoTime();
        try (PrintStream log = new PrintStream(new BufferedOutputStream(Files.newOutputStream(work.resolve("console.log"))),
                false, StandardCharsets.UTF_8)) {
            System.setOut(log); set("OUTPUT_ROOT", root); set("transport", fake);
            call("configure", new Class<?>[]{String[].class}, (Object) new String[]{"--fresh", "--pages", "10000", "--depth", "10000"});
            check((int) get("MAX_REQUESTS") == 40100, "Automatic request budget must scale with page target");
            try {
                call("runCrawler", new Class<?>[]{String[].class}, (Object) new String[]{});
                throw new AssertionError("Crash injection was not exercised");
            } catch (IllegalStateException e) { check(e.getMessage().equals("SIMULATED_PROCESS_CRASH"), "Unexpected crash"); }
            Path run = root.resolve(Files.readString(root.resolve("latest_checkpoint.tsv")).strip());
            check(Files.readAllLines(run.resolve("pages.tsv")).size() == 4322, "4321 pages must be committed before crash");
            Files.writeString(run.resolve("pages.tsv"), "partial TSV append", StandardOpenOption.APPEND);
            Files.writeString(run.resolve("html/4322.html"), "partial HTML");
            // Reset volatile process state; recovery must read the persisted budget and configuration.
            set("requestCount", 0); set("lastRequestStartedAtMs", 0L); set("checkpointDirectory", null);
            set("activeRobots", null); set("robotsLoadedAtMs", 0L);
            set("MAX_PAGES", 100); set("MAX_DEPTH", 10); set("MAX_REQUESTS", 500);
            crashEnabled[0] = false;
            call("configure", new Class<?>[]{String[].class}, (Object) new String[]{"--recover"});
            call("runCrawler", new Class<?>[]{String[].class}, (Object) new String[]{});
            var pages = Files.readAllLines(run.resolve("pages.tsv"));
            check(pages.size() == 10001, "Exactly 10000 page records and one header");
            Set<String> urls = new HashSet<>();
            for (int i = 1; i < pages.size(); i++) {
                String[] row = pages.get(i).split("\t");
                check(Integer.parseInt(row[0]) == i && urls.add(row[1]), "Continuous unique page IDs and URLs");
                check(Files.readString(run.resolve(row[5])).endsWith("</html>"), "Complete HTML for page " + i);
            }
            check(returnedDocuments.size() == 10000 && attempts[0] == 10001, "Only interrupted document is retried");
            check(robotLoads[0] == 3, "Initial, recovery and periodic robots refresh");
            check((int) get("requestCount") == 10004, "Cumulative budget includes interrupted request and all robots loads");
            check(Files.readString(run.resolve("checkpoint.tsv")).contains("STATE\tCOMPLETE"), "Final completed state");
            Class<?> checkpointClass = Class.forName("MusicBrainzCrawler$Checkpoint");
            var read = checkpointClass.getDeclaredMethod("read", Path.class); read.setAccessible(true);
            read.invoke(null, run); // Replay all 10002 committed journal entries.
            long journalBytes;
            try (var files = Files.list(run.resolve("checkpoint-journal"))) {
                journalBytes = files.mapToLong(file -> {
                    try { return Files.size(file); } catch (Exception e) { throw new RuntimeException(e); }
                }).sum();
            }
            check(journalBytes < 10 * 1024 * 1024, "Recovery data must grow linearly, not rewrite all historical state");
            long seconds = (System.nanoTime() - started) / 1000000000;
            Files.writeString(work.resolve("result.tsv"), "metric\tvalue\nsynthetic_documents\t10000\ncrash_after_pages\t4321"
                + "\nattempts\t" + attempts[0] + "\nrobots_loads\t" + robotLoads[0] + "\njournal_bytes\t" + journalBytes
                + "\nelapsed_seconds\t" + seconds + "\nnetwork_requests\t0\n");
            console.println("10000-document offline run and crash recovery passed in " + seconds + " seconds.");
            console.println("Evidence: " + work.resolve("result.tsv"));
        } finally {
            System.setOut(console); set("OUTPUT_ROOT", oldRoot); set("transport", oldTransport);
        }
    }
}
