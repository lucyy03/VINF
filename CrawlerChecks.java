import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Offline regression checks; makes no network requests. */
public class CrawlerChecks {
    private static Object call(Class<?> type, String name, Class<?>[] parameters, Object target,
                               Object... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Class<?> crawler = MusicBrainzCrawler.class;
        String base = "https://musicbrainz.org/";
        String saved = Files.readString(Path.of("crawler-output/2026-10-10_18-10-33/html/0001.html"));
        check((boolean) call(crawler, "isChallengePage", new Class<?>[]{String.class}, null, saved),
            "Saved browser challenge must not count as success");
        check(!(boolean) call(crawler, "isChallengePage", new Class<?>[]{String.class}, null,
            "<title>Album - MusicBrainz</title>"), "Normal title must pass");
        Set<?> links = (Set<?>) call(crawler, "extractLinks", new Class<?>[]{String.class, String.class},
            null, "<!-- <a href='/fake'> --> <a HREF=/artist/test>x</a>"
                + "<a href='/release/test?a=1&amp;b=2'>y</a>", base);
        check(links.size() == 2 && links.contains(base + "artist/test")
            && links.contains(base + "release/test?a=1&b=2"), "HTML parsing and entity decoding");
        for (String url : new String[]{"http://musicbrainz.org/", "https://musicbrainz.org:444/",
                                      "https://other.example/"}) {
            check(!(boolean) call(crawler, "isInternal", new Class<?>[]{String.class}, null, url),
                "Unsafe origin accepted: " + url);
        }
        Class<?> rulesClass = Class.forName("MusicBrainzCrawler$RobotsRules");
        Object rules = call(rulesClass, "parse", new Class<?>[]{String.class}, null,
            "User-agent: *\nDisallow: /\nUser-agent: SchoolAlbumCrawler\nDisallow: /a?b\n"
                + "Disallow: /private/*\nAllow: /private/open$\n"
                + "User-agent: SchoolAlbumCrawler\nDisallow: /extra\n");
        for (String path : new String[]{"a?b", "private/closed", "extra"}) {
            check(!(boolean) call(rulesClass, "isAllowed", new Class<?>[]{String.class}, rules, base + path),
                "Disallowed path accepted: " + path);
        }
        for (String path : new String[]{"album", "prefix/a?b", "ab", "private/open"}) {
            check((boolean) call(rulesClass, "isAllowed", new Class<?>[]{String.class}, rules, base + path),
                "Allowed path rejected: " + path);
        }
        rules = call(rulesClass, "parse", new Class<?>[]{String.class}, null,
            "User-agent: *\nDisallow:\n");
        check((boolean) call(rulesClass, "isAllowed", new Class<?>[]{String.class}, rules, base),
            "Empty disallow must permit crawling");
        checkRecovery();
        System.out.println("Offline crawler checks passed (including crash recovery).");
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void checkRecovery() throws Exception {
        Files.createDirectories(Path.of("target"));
        Path directory = Files.createTempDirectory(Path.of("target"), "recovery-check-");
        Files.createDirectories(directory.resolve("html"));
        String[] names = {"pages.tsv", "internal_links.tsv", "external_links.tsv", "crawl_log.tsv"};
        for (String name : names) Files.writeString(directory.resolve(name), "committed\n");
        Files.writeString(directory.resolve("html/0001.html"), "saved page one");
        String start = "https://musicbrainz.org/release-group/b1392450-e666-3926-a536-22c65f834433";
        String pending = "https://musicbrainz.org/artist/a74b1b7f-71a5-4011-9441-d0b5e4122711";
        Class<?> itemClass = Class.forName("MusicBrainzCrawler$CrawlItem");
        var itemConstructor = itemClass.getDeclaredConstructor(String.class, int.class);
        itemConstructor.setAccessible(true);
        java.util.Queue<Object> queue = new java.util.ArrayDeque<>();
        queue.add(itemConstructor.newInstance(pending, 1));
        Class<?> checkpointClass = Class.forName("MusicBrainzCrawler$Checkpoint");
        var constructor = checkpointClass.getDeclaredConstructor(Path.class, java.util.Queue.class,
            Set.class, Set.class, Set.class, Set.class, int.class);
        constructor.setAccessible(true);
        Object checkpoint = constructor.newInstance(directory, queue,
            new java.util.HashSet<>(Set.of(start, pending)), new java.util.HashSet<>(Set.of(start)),
            new java.util.HashSet<>(Set.of(start + "\t" + pending)), new java.util.HashSet<>(), 1);
        call(checkpointClass, "save", new Class<?>[]{String.class}, checkpoint, "ACTIVE");
        // Simulate a crash after writing page two and appending TSV rows, before committing.
        for (String name : names) Files.writeString(directory.resolve(name), "partial page two\n",
            java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(directory.resolve("html/0002.html"), "incomplete page two");
        Object recovered = call(checkpointClass, "read", new Class<?>[]{Path.class}, null, directory);
        check((int) field(recovered, "pages") == 1, "Preserve page numbering");
        check(((java.util.Queue<?>) field(recovered, "queue")).size() == 1, "Restore pending page");
        check(((Set<?>) field(recovered, "visited")).equals(Set.of(start)), "Keep committed visited URLs");
        check(((Set<?>) field(recovered, "internalEdges")).contains(start + "\t" + pending), "Restore deduplication");
        call(checkpointClass, "restoreOutputs", new Class<?>[]{}, recovered);
        for (String name : names) check(Files.readString(directory.resolve(name)).equals("committed\n"),
            "Roll back uncommitted TSV writes: " + name);
        check(!Files.exists(directory.resolve("html/0002.html")), "Remove uncommitted HTML");
        check(Files.readString(directory.resolve("html/0001.html")).equals("saved page one"), "Preserve committed HTML");
        // Simulate successful retry/commit: next recovery must retain page two.
        queue.clear();
        Files.writeString(directory.resolve("html/0002.html"), "complete page two");
        var pagesField = checkpointClass.getDeclaredField("pages"); pagesField.setAccessible(true);
        pagesField.set(checkpoint, 2);
        call(checkpointClass, "save", new Class<?>[]{String.class}, checkpoint, "ACTIVE");
        recovered = call(checkpointClass, "read", new Class<?>[]{Path.class}, null, directory);
        call(checkpointClass, "restoreOutputs", new Class<?>[]{}, recovered);
        check((int) field(recovered, "pages") == 2 && Files.exists(directory.resolve("html/0002.html")),
            "Committed retry survives recovery");
        call(MusicBrainzCrawler.class, "markCheckpointStopped", new Class<?>[]{Path.class}, null, directory);
        recovered = call(checkpointClass, "read", new Class<?>[]{Path.class}, null, directory);
        check(field(recovered, "state").equals("STOPPED"), "Site stops must not become crash retries");
        // Missing committed output must fail before truncating any other file.
        Files.writeString(directory.resolve("pages.tsv"), "tail", java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(directory.resolve("external_links.tsv"), "");
        try {
            call(checkpointClass, "restoreOutputs", new Class<?>[]{}, recovered);
            throw new AssertionError("Missing committed data must refuse recovery");
        } catch (java.lang.reflect.InvocationTargetException e) {
            check(e.getCause() instanceof java.io.IOException, "Expected recovery validation failure");
        }
        check(Files.readString(directory.resolve("pages.tsv")).endsWith("tail"), "Validate before changing originals");
        String validCheckpoint = Files.readString(directory.resolve("checkpoint.tsv"));
        Files.writeString(directory.resolve("checkpoint.tsv"), validCheckpoint.replace("VERSION\t1", "VERSION\t999"));
        try {
            call(checkpointClass, "read", new Class<?>[]{Path.class}, null, directory);
            throw new AssertionError("Incompatible checkpoint must be rejected");
        } catch (java.lang.reflect.InvocationTargetException e) {
            check(e.getCause() instanceof java.io.IOException, "Expected checkpoint version rejection");
        }
        var directoryField = MusicBrainzCrawler.class.getDeclaredField("checkpointDirectory");
        var requestsField = MusicBrainzCrawler.class.getDeclaredField("requestCount");
        var timeField = MusicBrainzCrawler.class.getDeclaredField("lastRequestStartedAtMs");
        directoryField.setAccessible(true); requestsField.setAccessible(true); timeField.setAccessible(true);
        Object oldDirectory = directoryField.get(null), oldRequests = requestsField.get(null), oldTime = timeField.get(null);
        try {
            directoryField.set(null, directory); requestsField.setInt(null, 10);
            call(MusicBrainzCrawler.class, "reserveRequest", new Class<?>[]{}, null);
            long savedTime = timeField.getLong(null);
            requestsField.setInt(null, 0); timeField.setLong(null, 0);
            call(MusicBrainzCrawler.class, "loadRequestState", new Class<?>[]{Path.class}, null, directory);
            check(requestsField.getInt(null) == 11 && timeField.getLong(null) == savedTime,
                "Recovery must preserve requests consumed after the page checkpoint and last request timing");
        } finally {
            directoryField.set(null, oldDirectory); requestsField.set(null, oldRequests); timeField.set(null, oldTime);
        }
    }
}
