import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline checks with a tiny synthetic crawl; never opens a browser. */
public class StatisticsChecks {
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("target"));
        Path test = Files.createTempDirectory(Path.of("target"), "statistics-check-");
        Path run = Files.createDirectories(test.resolve("run/html")).getParent();
        String id = "b1392450-e666-3926-a536-22c65f834433";
        String prefix = "https://musicbrainz.org/";
        StringBuilder rows = new StringBuilder("id\turl\tdepth\tstatus\tcontent_type\thtml_file\n");
        int index = 0;
        for (String type : new String[]{"release-group", "release", "artist"}) {
            index++;
            Files.writeString(run.resolve("html/" + index + ".html"),
                "<html><title>Album or artist — MusicBrainz</title><body>data</body></html>", StandardCharsets.UTF_8);
            rows.append(index + "\t" + prefix + type + "/" + id + "\t0\t200\ttext/html\thtml/" + index + ".html\n");
        }
        Files.writeString(run.resolve("html/challenge.html"), "<html><title>Verifying your browser</title></html>");
        Files.writeString(run.resolve("html/orphan.html"), "partial page");
        rows.append("4\t" + prefix + "artist/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\t0\t200\ttext/html\thtml/challenge.html\n");
        rows.append("5\t" + prefix + "release/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\t0\t200\ttext/html\thtml/missing.html\n");
        rows.append("6\t" + prefix + "release-group/" + id + "\t0\t200\ttext/html\thtml/1.html\n");
        rows.append("broken\trow\n");
        Files.writeString(run.resolve("pages.tsv"), rows, StandardCharsets.UTF_8);
        long expectedBytes;
        try (var paths = Files.walk(run)) {
            expectedBytes = paths.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (Exception e) { throw new RuntimeException(e); }
            }).sum();
        }
        var result = CrawlStatistics.writeReport(run, test.resolve("report"));
        check(result.get("page_records") == 7, "Count all metadata rows");
        check(result.get("valid_entity_documents") == 3, "Count three unique valid entities");
        for (String type : new String[]{"artist", "release", "release_group"})
            check(result.get(type + "_documents") == 1, "Entity classification: " + type);
        check(result.get("challenge_records") == 1, "Reject HTTP 200 browser challenge");
        check(result.get("missing_html_records") == 1, "Detect missing HTML");
        check(result.get("malformed_records") == 1, "Detect malformed metadata");
        check(result.get("duplicate_url_records") == 1 && result.get("duplicate_html_records") == 1,
            "Do not inflate valid count with duplicates");
        check(result.get("html_files_on_disk") == 5 && result.get("unreferenced_html_files") == 1,
            "Separate inventory from referenced documents");
        check(result.get("all_files_bytes") == expectedBytes, "Exact logical byte total");
        check(Files.readString(test.resolve("report/document_audit.tsv")).contains("CHALLENGE"), "Audit report");
        System.out.println("Offline statistics checks passed.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
