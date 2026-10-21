import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MusicBrainzIndexer {
    static final List<String> TEXT_FIELDS = List.of("name", "artist_credit", "musicbrainz_type", "barcode", "format",
        "status", "language", "script", "area", "artist_begin_date", "genres");

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !args[1].equals("--pages"))) throw new IllegalArgumentException("Usage: MusicBrainzIndexer <regex-output-directory> [--pages]");
        Path input = Path.of(args[0]).toRealPath(), root = Path.of("index-output").toAbsolutePath();
        Files.createDirectories(root);
        try (FileChannel channel = FileChannel.open(root.resolve("indexer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another index build is running");
            Path output = Files.createTempDirectory(root, input.getParent().getFileName() + "_");
            MusicBrainzIndex index = args.length == 2 ? build(input, output) : buildAlbums(input, output);
            // Publish only after a complete generation has been independently loaded/validated.
            MusicBrainzIndex.load(output);
            atomicText(root.resolve("latest-index.tsv"), output.getFileName() + "\n");
            System.out.println("Index built: " + index.documents.size() + " documents; " + index.postings.size() + " terms.");
            System.out.println("Index directory: " + output);
        }
    }

    static MusicBrainzIndex build(Path input, Path output) throws IOException {
        input = input.toRealPath(); output = output.toAbsolutePath().normalize();
        if (output.startsWith(input)) throw new IOException("Index must be outside the extracted input directory");
        Files.createDirectories(output);
        try (var files = Files.list(output)) { if (files.findAny().isPresent()) throw new IOException("Use a new empty index directory"); }
        atomicText(output.resolve("manifest.tsv"), "key\tvalue\nversion\t1\nstate\tBUILDING\n");
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String file : List.of("entities.tsv", "release_events.tsv", "extraction_audit.tsv")) hashes.put(file, sha256(input.resolve(file)));
        List<String> required = new ArrayList<>(TEXT_FIELDS);
        required.addAll(List.of("page_id", "source_url", "entity_type", "mbid"));
        var entities = MusicBrainzIndex.readTsv(input.resolve("entities.tsv"), required.toArray(String[]::new));
        var audit = MusicBrainzIndex.readTsv(input.resolve("extraction_audit.tsv"), "page_id", "source_url", "result", "detail");
        Map<String, Map<String, String>> audited = new HashMap<>(); int warnings = 0;
        for (var row : audit) {
            if (row.get("result").equals("PAGINATION")) {
                if (MusicBrainzUrls.page(row.get("source_url")) <= 1) throw new IOException("Invalid pagination audit");
                continue;
            }
            if (!Set.of("OK", "WARNING").contains(row.get("result"))) throw new IOException("Input extraction audit contains errors");
            if (audited.put(row.get("source_url"), row) != null) throw new IOException("Duplicate audit URL");
            if (row.get("result").equals("WARNING")) warnings++;
        }
        Map<String, Set<String>> countries = new HashMap<>(), dates = new HashMap<>();
        for (var row : MusicBrainzIndex.readTsv(input.resolve("release_events.tsv"), "page_id", "source_url", "country", "date")) {
            var parent = audited.get(row.get("source_url"));
            if (parent == null || !parent.get("page_id").equals(row.get("page_id"))) throw new IOException("Orphan release event");
            countries.computeIfAbsent(row.get("source_url"), ignored -> new LinkedHashSet<>()).add(row.get("country"));
            dates.computeIfAbsent(row.get("source_url"), ignored -> new LinkedHashSet<>()).add(row.get("date"));
        }
        Map<String, Map<String, String>> parents = new HashMap<>();
        for (var row : entities) if (parents.put(row.get("source_url"), row) != null) throw new IOException("Duplicate entity URL");
        Map<String, Set<String>> additional = new HashMap<>();
        readAdditional(input, "entity_details.tsv", List.of("other_tags", "artist_end_date", "gender", "artist_sort_name"), false, parents, hashes, additional);
        readAdditional(input, "tracks.tsv", List.of("name"), true, parents, hashes, additional);
        readAdditional(input, "labels.tsv", List.of("name", "catalog_numbers"), true, parents, hashes, additional);
        MusicBrainzIndex index = new MusicBrainzIndex();
        Set<String> seen = new HashSet<>(), pageIds = new HashSet<>();
        for (var row : entities) {
            String url = row.get("source_url");
            if (!seen.add(url) || !pageIds.add(row.get("page_id"))) throw new IOException("Duplicate source URL/page ID");
            var checked = audited.get(url);
            if (checked == null || !checked.get("page_id").equals(row.get("page_id"))) throw new IOException("Entity/audit mismatch");
            if (row.get("name").isBlank() || !Set.of("artist", "release", "release-group").contains(row.get("entity_type")))
                throw new IOException("Missing name or invalid entity type");
            if (!url.equals("https://musicbrainz.org/" + row.get("entity_type") + "/" + row.get("mbid")))
                throw new IOException("Noncanonical entity identity: " + url);
            if (!row.get("entity_type").equals("release") && countries.containsKey(url)) throw new IOException("Release event attached to another entity type");
            List<String> parts = new ArrayList<>();
            for (String field : TEXT_FIELDS) parts.add(row.get(field));
            parts.addAll(countries.getOrDefault(url, Set.of())); parts.addAll(dates.getOrDefault(url, Set.of()));
            parts.addAll(additional.getOrDefault(url, Set.of()));
            index.add(row.get("page_id"), url, row.get("entity_type"), row.get("name"), row.get("artist_credit"), row.get("format"),
                String.join(" ", parts).strip());
        }
        if (index.documents.isEmpty() || seen.size() != audited.size()) throw new IOException("Empty or incomplete extraction dataset");
        persist(index, input, output, hashes, warnings);
        return index;
    }
    static void persist(MusicBrainzIndex index, Path input, Path output, Map<String, String> hashes, int warnings) throws IOException {
        try (var writer = Files.newBufferedWriter(output.resolve("documents.tsv"), StandardCharsets.UTF_8)) {
            writer.write("doc_id\tpage_id\tsource_url\tentity_type\tname\tartist_credit\tformat\tindexed_text\ttoken_count\n");
            for (var doc : index.documents) writer.write(doc.id() + "\t" + MusicBrainzIndex.cell(doc.pageId()) + "\t"
                + MusicBrainzIndex.cell(doc.url()) + "\t" + MusicBrainzIndex.cell(doc.type()) + "\t" + MusicBrainzIndex.cell(doc.name())
                + "\t" + MusicBrainzIndex.cell(doc.artist()) + "\t" + MusicBrainzIndex.cell(doc.format())
                + "\t" + MusicBrainzIndex.cell(doc.text()) + "\t" + doc.tokenCount() + "\n");
        }
        long postingCount = 0;
        try (var terms = Files.newBufferedWriter(output.resolve("terms.tsv"), StandardCharsets.UTF_8);
             var postings = Files.newBufferedWriter(output.resolve("postings.tsv"), StandardCharsets.UTF_8)) {
            terms.write("term\tdf\tidf_classic\tidf_smooth\n"); postings.write("term\tdoc_id\ttf\n");
            for (var entry : index.postings.entrySet()) {
                int df = entry.getValue().size();
                terms.write(entry.getKey() + "\t" + df + "\t" + MusicBrainzIndex.idf(MusicBrainzIndex.Method.CLASSIC, index.documents.size(), df)
                    + "\t" + MusicBrainzIndex.idf(MusicBrainzIndex.Method.SMOOTH, index.documents.size(), df) + "\n");
                for (var posting : entry.getValue().entrySet()) {
                    postings.write(entry.getKey() + "\t" + posting.getKey() + "\t" + posting.getValue() + "\n"); postingCount++;
                }
            }
        }
        for (String file : hashes.keySet()) if (!hashes.get(file).equals(sha256(input.resolve(file))))
            throw new IOException("Extracted input changed during indexing; stop extraction and rebuild");
        long tokens = index.documents.stream().mapToLong(MusicBrainzIndex.Document::tokenCount).sum();
        Files.writeString(output.resolve("statistics.tsv"), "metric\tvalue\ndocuments\t" + index.documents.size()
            + "\nterms\t" + index.postings.size() + "\npostings\t" + postingCount + "\nlexical_tokens\t" + tokens
            + "\ninput_warnings\t" + warnings + "\n", StandardCharsets.UTF_8);
        StringBuilder manifest = new StringBuilder("key\tvalue\nversion\t1\nstate\tCOMPLETE\ntokenizer\t" + MusicBrainzIndex.TOKENIZER
            + "\ndocuments\t" + index.documents.size() + "\ncreated_utc\t" + Instant.now() + "\ninput\t" + MusicBrainzIndex.cell(input.toString()) + "\n");
        for (var hash : hashes.entrySet()) manifest.append("input_sha256_").append(hash.getKey()).append('\t').append(hash.getValue()).append('\n');
        atomicText(output.resolve("manifest.tsv"), manifest.toString());
    }
    static MusicBrainzIndex buildAlbums(Path input, Path output) throws IOException {
        Files.createDirectories(Path.of("target"));
        Path pageOutput = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "album-pages-");
        build(input, pageOutput);
        return MusicBrainzAlbumIndexer.build(input, pageOutput, output);
    }

    // Optional tables keep old extractor outputs usable. Join strictly by source URL + page ID.
    private static void readAdditional(Path input, String file, List<String> textFields, boolean releaseOnly,
            Map<String, Map<String, String>> parents, Map<String, String> hashes,
            Map<String, Set<String>> additional) throws IOException {
        Path path = input.resolve(file);
        if (!Files.exists(path)) return;
        hashes.put(file, sha256(path));
        List<String> required = new ArrayList<>(List.of("page_id", "source_url")); required.addAll(textFields);
        for (var row : MusicBrainzIndex.readTsv(path, required.toArray(String[]::new))) {
            String url = row.get("source_url"); var parent = parents.get(url);
            if (parent == null || !parent.get("page_id").equals(row.get("page_id")))
                throw new IOException("Orphan/mismatched supplemental row in " + file);
            if (releaseOnly && !parent.get("entity_type").equals("release"))
                throw new IOException("Edition data attached to another entity type in " + file);
            for (String field : textFields) {
                String value = row.get(field).strip();
                if (!value.isEmpty()) additional.computeIfAbsent(url, ignored -> new LinkedHashSet<>()).add(value);
            }
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(file)) {
                byte[] bytes = new byte[8192]; int n;
                while ((n = stream.read(bytes)) >= 0) digest.update(bytes, 0, n);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static void atomicText(Path target, String text) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        for (int i = 0; ; i++) try {
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (java.nio.file.AccessDeniedException e) {
            if (i >= 5) throw e;
            try { Thread.sleep(50); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("Index publication interrupted");
            }
        }
    }
}
