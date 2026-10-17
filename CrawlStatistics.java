import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Offline inventory of one crawl. No browser, HTTP requests or database. */
public class CrawlStatistics {
    private static final Pattern ENTITY = Pattern.compile(
        "^/(artist|release|release-group)/[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}/?$");
    private static final Pattern TITLE = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title>");

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2)
            throw new IllegalArgumentException("Usage: CrawlStatistics <crawl-directory> [report-directory]");
        Path run = Path.of(args[0]).toRealPath();
        Path report = args.length == 2 ? Path.of(args[1]) : Path.of("analysis-output", run.getFileName().toString());
        report = report.toAbsolutePath().normalize();
        if (report.startsWith(run)) throw new IOException("Reports must be outside the crawl directory.");
        Path lockFile = run.getParent().resolve("crawler.lock");
        if (Files.exists(lockFile)) {
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Stop the running crawler before collecting statistics.");
                writeReport(run, report);
            }
        } else writeReport(run, report);
    }

    static Map<String, Long> writeReport(Path directory, Path report) throws IOException {
        Path run = directory.toRealPath();
        List<String> rows = Files.readAllLines(run.resolve("pages.tsv"), StandardCharsets.UTF_8);
        if (rows.isEmpty() || !rows.get(0).equals("id\turl\tdepth\tstatus\tcontent_type\thtml_file"))
            throw new IOException("Unexpected pages.tsv header.");
        Map<String, Long> metrics = new LinkedHashMap<>();
        for (String key : List.of("page_records", "malformed_records", "duplicate_url_records", "duplicate_html_records",
                "missing_html_records", "challenge_records", "invalid_document_records", "valid_entity_documents",
                "artist_documents", "release_documents", "release_group_documents", "html_files_on_disk",
                "unreferenced_html_files", "html_bytes", "all_files_bytes")) metrics.put(key, 0L);
        Set<String> urls = new HashSet<>();
        Set<Path> references = new HashSet<>();
        StringBuilder audit = new StringBuilder("id\turl\thtml_file\tentity_type\tbytes\tresult\ttitle\n");
        for (int n = 1; n < rows.size(); n++) {
            if (rows.get(n).isBlank()) continue;
            increment(metrics, "page_records", 1);
            String[] row = rows.get(n).split("\t", -1);
            if (row.length != 6) {
                increment(metrics, "malformed_records", 1);
                audit.append("\t\t\t\t0\tMALFORMED_ROW_" + (n + 1) + "\t\n");
                continue;
            }
            Set<String> issues = new java.util.LinkedHashSet<>();
            boolean duplicateUrl = !urls.add(row[1]);
            if (duplicateUrl) { increment(metrics, "duplicate_url_records", 1); issues.add("DUPLICATE_URL"); }
            String type = entityType(row[1]);
            if (type.isEmpty()) issues.add("OUT_OF_SCOPE_URL");
            if (!row[3].matches("2[0-9]{2}") || !row[4].toLowerCase(java.util.Locale.ROOT)
                    .matches(".*(?:text/html|application/xhtml\\+xml).*")) issues.add("STATUS_OR_CONTENT_TYPE");
            Path file = run.resolve(row[5]).normalize();
            String title = "";
            long bytes = 0;
            if (!file.startsWith(run.resolve("html")) || !file.getFileName().toString().endsWith(".html")) {
                issues.add("INVALID_HTML_PATH");
            } else if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                increment(metrics, "missing_html_records", 1); issues.add("MISSING_HTML");
            } else if (!file.toRealPath().startsWith(run.resolve("html").toRealPath())) {
                issues.add("INVALID_HTML_PATH");
            } else {
                if (!references.add(file)) {
                    increment(metrics, "duplicate_html_records", 1); issues.add("DUPLICATE_HTML");
                }
                bytes = Files.size(file);
                String html = Files.readString(file, StandardCharsets.UTF_8);
                var matcher = TITLE.matcher(html);
                if (matcher.find()) title = matcher.group(1).replaceAll("(?s)<[^>]*>", "").strip();
                String lowerTitle = title.toLowerCase(java.util.Locale.ROOT);
                boolean challenge = lowerTitle.contains("verifying your browser") || lowerTitle.contains("just a moment")
                    || lowerTitle.contains("access denied") || lowerTitle.contains("making sure you're not a bot")
                    || lowerTitle.contains("verify you are human");
                if (challenge) { increment(metrics, "challenge_records", 1); issues.add("CHALLENGE"); }
                else if (bytes == 0 || !lowerTitle.contains("musicbrainz")
                    || !Pattern.compile("(?is)<html\\b").matcher(html).find()
                    || !Pattern.compile("(?is)</html\\s*>").matcher(html).find()) issues.add("UNEXPECTED_OR_INCOMPLETE_HTML");
            }
            if (issues.isEmpty()) {
                increment(metrics, "valid_entity_documents", 1);
                increment(metrics, type.replace('-', '_') + "_documents", 1);
            } else increment(metrics, "invalid_document_records", 1);
            audit.append(clean(row[0])).append('\t').append(clean(row[1])).append('\t').append(clean(row[5]))
                .append('\t').append(type).append('\t').append(bytes).append('\t')
                .append(issues.isEmpty() ? "OK" : String.join(",", issues)).append('\t').append(clean(title)).append('\n');
        }
        try (var paths = Files.walk(run)) {
            for (Path file : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                long bytes = Files.size(file);
                increment(metrics, "all_files_bytes", bytes);
                if (file.startsWith(run.resolve("html")) && file.getFileName().toString().endsWith(".html")) {
                    increment(metrics, "html_files_on_disk", 1); increment(metrics, "html_bytes", bytes);
                    if (!references.contains(file)) increment(metrics, "unreferenced_html_files", 1);
                }
            }
        }
        Files.createDirectories(report);
        StringBuilder summary = new StringBuilder("metric\tvalue\tunit\n");
        for (var entry : metrics.entrySet()) summary.append(entry.getKey()).append('\t').append(entry.getValue())
            .append('\t').append(entry.getKey().endsWith("bytes") ? "bytes" : "count").append('\n');
        Files.writeString(report.resolve("statistics.tsv"), summary, StandardCharsets.UTF_8);
        Files.writeString(report.resolve("document_audit.tsv"), audit, StandardCharsets.UTF_8);
        System.out.println("Run: " + run.getFileName());
        System.out.println("Valid entity documents: " + metrics.get("valid_entity_documents")
            + "; invalid records: " + metrics.get("invalid_document_records") + "; malformed: " + metrics.get("malformed_records"));
        System.out.printf(java.util.Locale.ROOT, "HTML: %.2f MiB; all run files: %.2f MiB%n",
            metrics.get("html_bytes") / 1048576.0, metrics.get("all_files_bytes") / 1048576.0);
        System.out.println("Reports: " + report.toAbsolutePath());
        return metrics;
    }

    private static String entityType(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"musicbrainz.org".equalsIgnoreCase(uri.getHost())
                || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getQuery() != null || uri.getFragment() != null) return "";
            var matcher = ENTITY.matcher(uri.getPath());
            return matcher.matches() ? matcher.group(1) : "";
        } catch (RuntimeException e) { return ""; }
    }

    private static void increment(Map<String, Long> metrics, String key, long amount) {
        metrics.put(key, metrics.getOrDefault(key, 0L) + amount);
    }

    private static String clean(String value) { return value.replaceAll("[\\t\\r\\n]", " "); }
}
