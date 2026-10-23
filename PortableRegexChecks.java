import java.nio.file.*;
import java.util.*;

/** Offline REGEX checks using only the 20 HTML copies shipped with the source ZIP. */
public class PortableRegexChecks {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("PortableRegexChecks takes no arguments");
        Path root = Path.of("test-fixtures/final-sample").toRealPath();
        // Copies have third-party map tokens redacted; original corpus hashes remain in the golden file.
        Map<String,String> copyHashes = new HashMap<>();
        for (var row : MusicBrainzIndex.readTsv(Path.of("test-fixtures/portable-html-sha256.tsv"), "html_file", "sha256"))
            if (copyHashes.putIfAbsent(row.get("html_file"), row.get("sha256")) != null) throw new AssertionError("Duplicate copy hash");
        if (copyHashes.size() != 20) throw new AssertionError("Expected 20 fixture hashes");
        var tests = MusicBrainzIndex.readTsv(Path.of("test-fixtures/final-corpus-golden-20.tsv"),
            "page_id", "source_url", "entity_type", "html_file", "sha256", "field", "expected");
        Map<String,Map<String,String>> extracted = new HashMap<>();
        Map<String,String> identities = new HashMap<>();
        Set<String> fields = new HashSet<>();
        int failures = 0;
        StringBuilder report = new StringBuilder("page_id\tsource_url\tfield\texpected\tactual\tresult\n");
        for (var test : tests) {
            String id = test.get("page_id"), url = test.get("source_url");
            String identity = url + "\t" + test.get("entity_type") + "\t" + test.get("html_file") + "\t" + test.get("sha256");
            String previous = identities.putIfAbsent(id, identity);
            if (previous != null && !previous.equals(identity)) throw new AssertionError("Conflicting fixture identity: " + id);
            if (!fields.add(id + "\t" + test.get("field"))) throw new AssertionError("Duplicate fixture field");
            if (!extracted.containsKey(id)) {
                Path file = root.resolve(test.get("html_file")).normalize().toRealPath();
                if (!file.startsWith(root.resolve("html").toRealPath())) throw new AssertionError("Unsafe HTML fixture path");
                if (!MusicBrainzIndexer.sha256(file).equals(copyHashes.get(test.get("html_file")))) throw new AssertionError("HTML hash mismatch: " + id);
                var value = MusicBrainzRegexExtractor.extract(url, Files.readString(file));
                if (!value.fields().get("entity_type").equals(test.get("entity_type"))) throw new AssertionError("Wrong entity type: " + id);
                extracted.put(id, FinalCorpusChecks.fresh(value));
            }
            String expected = test.get("expected"), actual = extracted.get(id).get(test.get("field"));
            boolean pass = expected.equals(actual);
            if (!pass) failures++;
            report.append(id).append('\t').append(url).append('\t').append(test.get("field")).append('\t')
                .append(MusicBrainzIndex.cell(expected)).append('\t').append(MusicBrainzIndex.cell(actual == null ? "[MISSING]" : actual))
                .append('\t').append(pass ? "OK" : "MISMATCH").append('\n');
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/portable-regex-validation.tsv"), report);
        if (extracted.size() != 20 || tests.size() != 996 || failures != 0)
            throw new AssertionError("Portable REGEX: " + extracted.size() + " pages, " + tests.size() + " checks, " + failures + " mismatches");
        System.out.println("Portable REGEX checks passed: 20 final-corpus HTML copies, 996 fixed values; no external corpus or network required.");
    }
}
