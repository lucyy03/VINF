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
        System.out.println("Offline crawler checks passed.");
    }
}
