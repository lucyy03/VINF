import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Small hand-computed retrieval checks and on-disk build/load checks. */
public class IndexChecks {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void close(double expected, double actual, String message) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) < 1e-12, message + ": " + expected + " vs " + actual);
    }
    public static void main(String[] args) throws Exception {
        MusicBrainzIndex index = new MusicBrainzIndex();
        index.add("1", "one", "release", "One", "", "", "radiohead radiohead computer");
        index.add("2", "two", "release", "Two", "", "", "radiohead cassette");
        index.add("3", "three", "release", "Three", "", "", "jazz cassette");
        check(index.postings.get("radiohead").get(1) == 2 && index.postings.get("radiohead").size() == 2, "Exact raw TF and DF");
        close(1 + Math.log(3.0 / 2), MusicBrainzIndex.idf(MusicBrainzIndex.Method.CLASSIC, 3, 2), "Classic IDF");
        close(1 + Math.log(4.0 / 3), MusicBrainzIndex.idf(MusicBrainzIndex.Method.SMOOTH, 3, 2), "Smooth IDF");
        for (var method : MusicBrainzIndex.Method.values()) {
            double common = MusicBrainzIndex.idf(method, 3, 2), rare = MusicBrainzIndex.idf(method, 3, 1);
            double weight = (1 + Math.log(2)) * common;
            var hits = index.search("RADIOHEAD", method, true, 10);
            check(hits.size() == 2 && hits.get(0).document().id() == 1, "Expected ranking");
            close(weight / Math.sqrt(weight * weight + rare * rare), hits.get(0).score(), "Hand-calculated cosine score");
            var both = index.search("radiohead cassette", method, true, 10);
            check(both.size() == 1 && both.get(0).document().id() == 2, "AND intersection");
            close(1, both.get(0).score(), "Identical query/document cosine");
            check(index.search("radiohead cassette", method, false, 10).size() == 3, "OR union");
            check(index.search("radiohead unknown", method, true, 10).isEmpty(), "Unknown required term");
            check(index.search("unknown", method, false, 10).isEmpty(), "Unknown-only query");
            close(hits.get(0).score(), index.search("radiohead radiohead", method, true, 10).get(0).score(), "Repeated query term");
        }
        check(MusicBrainzIndex.tokenize("ＣＤ café ８-bit").equals(List.of("cd", "café", "8", "bit")), "Shared Unicode tokenizer");
        check(index.search("!!!", MusicBrainzIndex.Method.CLASSIC, true, 10).isEmpty(), "Empty token query");
        MusicBrainzIndex ties = new MusicBrainzIndex();
        ties.add("1", "a", "artist", "A", "", "", "same"); ties.add("2", "b", "artist", "B", "", "", "same");
        var tied = ties.search("same", MusicBrainzIndex.Method.CLASSIC, true, 10);
        check(tied.size() == 2 && tied.get(0).document().id() == 1, "Deterministic tie order and nonzero common-term IDF");
        diskChecks();
        System.out.println("Offline index checks passed: TF, DF, both IDFs, cosine scores, AND/OR, Unicode, rebuild/load and invalid inputs.");
    }
    private static void diskChecks() throws Exception {
        Files.createDirectories(Path.of("target"));
        Path work = Files.createTempDirectory(Path.of("target"), "index-check-");
        Path input = Files.createDirectories(work.resolve("input"));
        String header = "page_id\tsource_url\tentity_type\tmbid\tname\tartist_credit\tartist_mbids\tmusicbrainz_type\tbarcode\tformat\tlength\tstatus\tlanguage\tscript\tarea\tartist_begin_date\tgenres\n";
        StringBuilder entities = new StringBuilder(header), audit = new StringBuilder("page_id\tsource_url\tresult\tdetail\n");
        String first = "";
        for (int i = 1; i <= 3; i++) {
            String id = "00000000-0000-0000-0000-" + String.format("%012x", i);
            String url = "https://musicbrainz.org/release/" + id;
            if (i == 1) first = url;
            entities.append(String.join("\t", "" + i, url, "release", id,
                List.of("radiohead radiohead computer", "radiohead cassette", "jazz cassette").get(i - 1),
                "", "", "Album", "", "", "", "", "", "", "", "", "")).append('\n');
            audit.append(i + "\t" + url + "\tOK\t\n");
        }
        Files.writeString(input.resolve("entities.tsv"), entities, StandardCharsets.UTF_8);
        Files.writeString(input.resolve("extraction_audit.tsv"), audit, StandardCharsets.UTF_8);
        String events = "page_id\tsource_url\tcountry\tdate\n" + "1\t" + first + "\tJapan\t1997-05-21\n";
        Files.writeString(input.resolve("release_events.tsv"), events + events.substring(events.indexOf('\n') + 1), StandardCharsets.UTF_8);
        Path output = work.resolve("first-index");
        MusicBrainzIndex built = MusicBrainzIndexer.build(input, output), loaded = MusicBrainzIndex.load(output);
        check(loaded.documents.size() == 3 && loaded.postings.equals(built.postings), "Persisted posting round trip");
        check(loaded.postings.get("japan").get(1) == 1 && loaded.postings.get("1997").get(1) == 1,
            "Duplicate event values do not inflate TF");
        check(loaded.search("Japan 1997", MusicBrainzIndex.Method.CLASSIC, true, 10).get(0).document().id() == 1, "Event join by entity");
        var rebuilt = MusicBrainzIndexer.build(input, work.resolve("second-index"));
        check(rebuilt.postings.equals(built.postings), "Rebuilding does not accumulate duplicate documents");
        try { MusicBrainzIndexer.build(input, output); throw new AssertionError("Existing generation overwritten"); }
        catch (java.io.IOException expected) { }
        String terms = Files.readString(output.resolve("terms.tsv"));
        Files.writeString(output.resolve("terms.tsv"), terms.replaceFirst("\\t[0-9]+\\t", "\t999\t"));
        try { MusicBrainzIndex.load(output); throw new AssertionError("Corrupt DF accepted"); }
        catch (java.io.IOException expected) { }
        Files.writeString(input.resolve("extraction_audit.tsv"), audit.toString().replaceFirst("OK", "ERROR"));
        Path failed = work.resolve("failed-index");
        try { MusicBrainzIndexer.build(input, failed); throw new AssertionError("Extraction errors accepted"); }
        catch (java.io.IOException expected) { }
        try { MusicBrainzIndex.load(failed); throw new AssertionError("Incomplete index accepted"); }
        catch (java.io.IOException expected) { }
    }
}
