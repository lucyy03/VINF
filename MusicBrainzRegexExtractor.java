import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Offline, site-specific REGEX extraction of the saved MusicBrainz DOM. */
public class MusicBrainzRegexExtractor {
    private static final String UUID = "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
    private static final Pattern ENTITY = Pattern.compile("^/(artist|release|release-group)/(" + UUID + ")/?$");
    private static final Pattern H1 = Pattern.compile("(?is)<h1\\b[^>]*>(.*?)</h1\\s*>");
    private static final Pattern TITLE = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title\\s*>");
    private static final Pattern ANCHOR = Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a\\s*>");
    private static final Pattern DD = Pattern.compile("(?is)<dd\\b([^>]*)>(.*?)</dd\\s*>");
    private static final Pattern ENTITIES = Pattern.compile("&(#x[0-9a-fA-F]+|#X[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");
    private static final Pattern NOISE = Pattern.compile("(?is)<!--.*?-->|<(script|style)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern PROPERTIES = blockPattern("dl", "properties");
    private static final Pattern SUBHEADER = blockPattern("p", "subheader");
    private static final Pattern SMALL = blockPattern("span", "small");
    private static final Pattern GENRES = blockPattern("div", "genre-list");
    private static final Pattern EVENTS = blockPattern("li", "release-event");
    private static final Pattern DATE = blockPattern("span", "release-date");
    private static final Map<String, String> NAMED = Map.ofEntries(
        Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
        Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("ndash", "–"), Map.entry("mdash", "—"),
        Map.entry("times", "×"), Map.entry("hellip", "…"), Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"),
        Map.entry("ldquo", "“"), Map.entry("rdquo", "”"), Map.entry("copy", "©"), Map.entry("reg", "®"));

    public record Event(String country, String date) { }
    public record Link(String relation, String url) { }
    public record Extraction(Map<String, String> fields, List<Event> events, List<Link> links, List<String> warnings) { }

    public static Extraction extract(String url, String rawHtml) throws IOException {
        URI source;
        try { source = URI.create(url); } catch (RuntimeException e) { throw new IOException("Invalid source URL", e); }
        var entity = ENTITY.matcher(source.getPath() == null ? "" : source.getPath());
        if (!"https".equalsIgnoreCase(source.getScheme()) || !"musicbrainz.org".equalsIgnoreCase(source.getHost())
            || source.getRawUserInfo() != null || (source.getPort() != -1 && source.getPort() != 443)
            || source.getRawQuery() != null || source.getRawFragment() != null || !entity.matches())
            throw new IOException("Not a canonical MusicBrainz entity URL");
        String html = NOISE.matcher(rawHtml).replaceAll("");
        String title = firstText(TITLE, html);
        if (!title.toLowerCase(Locale.ROOT).contains("musicbrainz")
            || Pattern.compile("(?i)verifying|access denied|just a moment|not a bot").matcher(title).find()
            || !Pattern.compile("(?is)</html\\s*>").matcher(html).find())
            throw new IOException("Challenge, incomplete or unexpected HTML");
        String type = entity.group(1), name = "";
        // Check the heading links to this entity; do not silently extract a different page.
        String h1 = firstBody(H1, html, 1);
        boolean headingMatches = false;
        var headingLinks = ANCHOR.matcher(h1);
        while (headingLinks.find()) {
            String target = resolve(source, attribute(headingLinks.group(1), "href"));
            if (!target.isEmpty() && "musicbrainz.org".equalsIgnoreCase(URI.create(target).getHost())
                && URI.create(target).getPath().replaceFirst("/$", "")
                    .equalsIgnoreCase(source.getPath().replaceFirst("/$", ""))) {
                headingMatches = true; name = text(headingLinks.group(2));
            }
        }
        if (!headingMatches || name.isBlank()) throw new IOException("Heading does not identify the requested entity");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("source_url", url); fields.put("entity_type", type);
        fields.put("mbid", entity.group(2).toLowerCase(Locale.ROOT)); fields.put("name", name);
        Map<String, String> properties = new LinkedHashMap<>();
        var boxes = PROPERTIES.matcher(html);
        while (boxes.find()) {
            var dd = DD.matcher(boxes.group(2));
            while (dd.find()) for (String token : attribute(dd.group(1), "class").split("\\s+"))
                properties.putIfAbsent(token, text(dd.group(2)));
        }
        List<String> warnings = new ArrayList<>();
        Set<Link> links = new LinkedHashSet<>();
        String subheader = firstBody(SUBHEADER, html, 2);
        String credit = "";
        Set<String> artistIds = new LinkedHashSet<>();
        if (!type.equals("artist")) {
            credit = text(SMALL.matcher(subheader).replaceAll(""))
                .replaceFirst("(?i)^~?\\s*Release(?: group)? by\\s+", "").strip();
            var anchors = ANCHOR.matcher(subheader);
            while (anchors.find()) {
                String target = resolve(source, attribute(anchors.group(1), "href"));
                if (target.isEmpty()) continue;
                var targetEntity = ENTITY.matcher(URI.create(target).getPath());
                if (targetEntity.matches() && targetEntity.group(1).equals("artist")) {
                    artistIds.add(targetEntity.group(2).toLowerCase(Locale.ROOT)); links.add(new Link("credited_artist", target));
                } else if (type.equals("release") && targetEntity.matches() && targetEntity.group(1).equals("release-group"))
                    links.add(new Link("release_group", target));
            }
            if (artistIds.isEmpty() || credit.isBlank()) { warnings.add("MISSING_ARTIST_CREDIT"); credit = ""; }
        }
        fields.put("artist_credit", credit); fields.put("artist_mbids", String.join(";", artistIds));
        for (String property : List.of("type", "barcode", "format", "length", "status", "language", "script", "area"))
            fields.put(property.equals("type") ? "musicbrainz_type" : property, properties.getOrDefault(property, ""));
        String begin = properties.getOrDefault("begin-date", "");
        var beginDate = Pattern.compile("^(\\d{4}(?:-\\d{2}(?:-\\d{2})?)?)(?:\\s|$)").matcher(begin);
        fields.put("artist_begin_date", type.equals("artist") && beginDate.find() ? beginDate.group(1) : "");
        Set<String> genres = new LinkedHashSet<>();
        var genreBoxes = GENRES.matcher(html);
        if (genreBoxes.find()) {
            var anchors = ANCHOR.matcher(genreBoxes.group(2));
            while (anchors.find()) genres.add(text(anchors.group(2)));
        }
        fields.put("genres", String.join(";", genres));
        var anchors = ANCHOR.matcher(html);
        while (anchors.find()) {
            String target = resolve(source, attribute(anchors.group(1), "href"));
            if (target.isEmpty()) continue;
            String host = URI.create(target).getHost().toLowerCase(Locale.ROOT);
            if (host.equals("wikidata.org") || host.endsWith(".wikidata.org")) links.add(new Link("wikidata_reference", target));
            if (host.equals("wikipedia.org") || host.endsWith(".wikipedia.org")) links.add(new Link("wikipedia_reference", target));
        }
        List<Event> events = new ArrayList<>();
        if (type.equals("release")) {
            var eventBlocks = EVENTS.matcher(html);
            while (eventBlocks.find()) {
                String body = eventBlocks.group(2), country = "";
                var countryLink = ANCHOR.matcher(body);
                if (countryLink.find()) country = text(countryLink.group(2));
                String date = firstText(DATE, body, 2);
                if (!date.isEmpty() && !date.matches("\\d{4}(?:-(?:\\d{2}|\\?\\?)(?:-(?:\\d{2}|\\?\\?))?)?")) {
                    warnings.add("UNRECOGNIZED_EVENT_DATE"); date = "";
                }
                Event event = new Event(country, date);
                if (!events.contains(event)) events.add(event);
            }
            if (events.isEmpty()) warnings.add("NO_RELEASE_EVENTS");
        }
        return new Extraction(fields, events, List.copyOf(links), warnings);
    }

    private static Pattern blockPattern(String tag, String cssClass) {
        String token = Pattern.quote(cssClass);
        return Pattern.compile("(?is)<" + tag + "\\b(?=[^>]*\\bclass\\s*=\\s*(?:\"(?:[^\"]*\\s)?" + token
            + "(?:\\s[^\"]*)?\"|'(?:[^']*\\s)?" + token + "(?:\\s[^']*)?'))([^>]*)>(.*?)</" + tag + "\\s*>");
    }
    private static String firstBody(Pattern pattern, String html, int group) {
        var matcher = pattern.matcher(html); return matcher.find() ? matcher.group(group) : "";
    }
    private static String firstText(Pattern pattern, String html) { return firstText(pattern, html, 1); }
    private static String firstText(Pattern pattern, String html, int group) { return text(firstBody(pattern, html, group)); }
    private static String attribute(String attributes, String name) {
        var matcher = Pattern.compile("(?is)(?:^|\\s)" + Pattern.quote(name)
            + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))").matcher(attributes);
        if (!matcher.find()) return "";
        for (int i = 1; i <= 3; i++) if (matcher.group(i) != null) return decode(matcher.group(i));
        return "";
    }
    static String text(String html) {
        return decode(NOISE.matcher(html).replaceAll("")
            .replaceAll("(?is)</?(?:br|p|div|li|dt|dd|h[1-6]|tr|td|th)\\b[^>]*>", " ")
            .replaceAll("(?is)<[^>]+>", ""))
            .replaceAll("[\\s\\p{Z}]+", " ").strip();
    }
    private static String decode(String input) {
        var matcher = ENTITIES.matcher(input); StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1), value = NAMED.get(key);
            if (key.startsWith("#")) try {
                boolean hex = key.length() > 2 && Character.toLowerCase(key.charAt(1)) == 'x';
                int cp = Integer.parseInt(key.substring(hex ? 2 : 1), hex ? 16 : 10);
                if (Character.isValidCodePoint(cp) && !(cp >= 0xd800 && cp <= 0xdfff)) value = new String(Character.toChars(cp));
            } catch (RuntimeException ignored) { }
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out); return out.toString();
    }
    private static String resolve(URI source, String href) {
        try {
            if (href.isBlank()) return "";
            URI target = source.resolve(href).normalize();
            if (target.getHost() == null || target.getRawUserInfo() != null
                || !(target.getScheme().equalsIgnoreCase("http") || target.getScheme().equalsIgnoreCase("https"))) return "";
            return target.toASCIIString();
        } catch (RuntimeException e) { return ""; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: MusicBrainzRegexExtractor <crawl-directory>");
        Path run = Path.of(args[0]).toRealPath();
        Path report = Path.of("analysis-output", run.getFileName().toString(), "regex");
        Path lockPath = run.getParent().resolve("crawler.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Stop the crawler before extraction.");
            extractRun(run, report);
        }
    }
    static void extractRun(Path run, Path report) throws IOException {
        List<String> rows = Files.readAllLines(run.resolve("pages.tsv"), StandardCharsets.UTF_8);
        if (rows.isEmpty() || !rows.get(0).equals("id\turl\tdepth\tstatus\tcontent_type\thtml_file"))
            throw new IOException("Unexpected pages.tsv schema");
        Files.createDirectories(report);
        try (var entities = Files.newBufferedWriter(report.resolve("entities.tsv"), StandardCharsets.UTF_8);
             var events = Files.newBufferedWriter(report.resolve("release_events.tsv"), StandardCharsets.UTF_8);
             var links = Files.newBufferedWriter(report.resolve("entity_links.tsv"), StandardCharsets.UTF_8);
             var audit = Files.newBufferedWriter(report.resolve("extraction_audit.tsv"), StandardCharsets.UTF_8)) {
            entities.write("page_id\tsource_url\tentity_type\tmbid\tname\tartist_credit\tartist_mbids\tmusicbrainz_type\tbarcode\tformat\tlength\tstatus\tlanguage\tscript\tarea\tartist_begin_date\tgenres\n");
            events.write("page_id\tsource_url\tcountry\tdate\n");
            links.write("page_id\tsource_url\trelation\ttarget_url\n");
            audit.write("page_id\tsource_url\tresult\tdetail\n");
            Set<String> seen = new LinkedHashSet<>(); int ok = 0, failed = 0;
            for (int i = 1; i < rows.size(); i++) {
                String[] row = rows.get(i).split("\t", -1);
                String id = row[0], url = row.length > 1 ? row[1] : "";
                try {
                    if (row.length != 6 || !row[3].matches("2[0-9]{2}") || !row[4].toLowerCase(Locale.ROOT)
                        .matches(".*(?:text/html|application/xhtml\\+xml).*")) throw new IOException("Invalid page metadata");
                    if (!seen.add(url)) throw new IOException("Duplicate source URL");
                    Path file = run.resolve(row[5]).normalize();
                    if (!file.startsWith(run.resolve("html")) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || !file.toRealPath().startsWith(run.resolve("html").toRealPath())) throw new IOException("Missing/unsafe HTML path");
                    Extraction value = extract(url, Files.readString(file, StandardCharsets.UTF_8));
                    entities.write(tsv(id) + "\t" + String.join("\t", value.fields().values().stream().map(MusicBrainzRegexExtractor::tsv).toList()) + "\n");
                    for (Event event : value.events()) events.write(tsv(id) + "\t" + tsv(url) + "\t" + tsv(event.country()) + "\t" + tsv(event.date()) + "\n");
                    for (Link link : value.links()) links.write(tsv(id) + "\t" + tsv(url) + "\t" + tsv(link.relation()) + "\t" + tsv(link.url()) + "\n");
                    audit.write(tsv(id) + "\t" + tsv(url) + "\t" + (value.warnings().isEmpty() ? "OK" : "WARNING")
                        + "\t" + tsv(String.join(";", value.warnings())) + "\n"); ok++;
                } catch (IOException | IllegalArgumentException e) {
                    audit.write(tsv(id) + "\t" + tsv(url) + "\tERROR\t" + tsv(e.getMessage()) + "\n"); failed++;
                }
            }
            System.out.println("REGEX extraction: " + ok + " documents; " + failed + " errors. Reports: " + report.toAbsolutePath());
            if (failed > 0) throw new IOException("Extraction contains errors; see extraction_audit.tsv");
        }
    }
    private static String tsv(String value) { return value.replaceAll("[\\t\\r\\n]", " "); }
}
