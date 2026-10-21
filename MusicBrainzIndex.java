import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Own inverted index and TF-IDF cosine search. No SQL, Lucene or network. */
public final class MusicBrainzIndex {
    static final String TOKENIZER = "nfkc-lower-unicode-v1";
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    public enum Method { CLASSIC, SMOOTH }
    public record Document(int id, String pageId, String url, String type, String name, String artist,
                           String format, String text, int tokenCount) { }
    public record Hit(Document document, double score) { }
    final List<Document> documents = new ArrayList<>();
    final Map<String, Map<Integer, Integer>> postings = new TreeMap<>();
    private final Map<Method, double[]> norms = new HashMap<>();

    public static List<String> tokenize(String text) {
        var matcher = TOKEN.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        List<String> result = new ArrayList<>();
        while (matcher.find()) result.add(matcher.group());
        return result;
    }
    static Map<String, Integer> frequencies(String text) {
        Map<String, Integer> result = new TreeMap<>();
        for (String token : tokenize(text)) result.merge(token, 1, Math::addExact);
        return result;
    }
    public static double idf(Method method, int n, int df) {
        if (n < 1 || df < 1 || df > n) throw new IllegalArgumentException("Require 1 <= df <= N");
        return switch (method) {
            case CLASSIC -> 1.0 + Math.log((double) n / df);
            case SMOOTH -> 1.0 + Math.log((n + 1.0) / (df + 1.0));
        };
    }
    static double tf(int frequency) {
        if (frequency < 1) throw new IllegalArgumentException("TF must be positive");
        return 1.0 + Math.log(frequency);
    }
    void add(String pageId, String url, String type, String name, String artist, String format, String text) {
        Map<String, Integer> terms = frequencies(text);
        int id = documents.size() + 1;
        documents.add(new Document(id, pageId, url, type, name, artist, format, text,
            terms.values().stream().mapToInt(Integer::intValue).sum()));
        for (var term : terms.entrySet()) postings.computeIfAbsent(term.getKey(), ignored -> new TreeMap<>()).put(id, term.getValue());
        norms.clear();
    }
    private double[] norms(Method method) {
        return norms.computeIfAbsent(method, ignored -> {
            double[] result = new double[documents.size() + 1];
            for (var term : postings.values()) {
                double idf = idf(method, documents.size(), term.size());
                for (var entry : term.entrySet()) {
                    double weight = tf(entry.getValue()) * idf;
                    result[entry.getKey()] += weight * weight;
                }
            }
            for (int i = 1; i < result.length; i++) result[i] = Math.sqrt(result[i]);
            return result;
        });
    }
    public List<Hit> search(String query, Method method, boolean allWords, int top) {
        if (top < 1) throw new IllegalArgumentException("top must be positive");
        Map<String, Integer> queryTerms = frequencies(query);
        if (queryTerms.isEmpty() || documents.isEmpty()) return List.of();
        Map<Integer, Double> scores = new HashMap<>();
        Map<Integer, Integer> matches = new HashMap<>();
        double querySquared = 0;
        for (var term : queryTerms.entrySet()) {
            Map<Integer, Integer> list = postings.get(term.getKey());
            if (list == null) { if (allWords) return List.of(); else continue; }
            double idf = idf(method, documents.size(), list.size());
            double queryWeight = tf(term.getValue()) * idf;
            querySquared += queryWeight * queryWeight;
            for (var entry : list.entrySet()) {
                scores.merge(entry.getKey(), queryWeight * tf(entry.getValue()) * idf, Double::sum);
                matches.merge(entry.getKey(), 1, Integer::sum);
            }
        }
        if (querySquared == 0) return List.of();
        double queryNorm = Math.sqrt(querySquared);
        double[] lengths = norms(method);
        List<Hit> hits = new ArrayList<>();
        for (var entry : scores.entrySet()) if (!allWords || matches.get(entry.getKey()) == queryTerms.size()) {
            int id = entry.getKey();
            hits.add(new Hit(documents.get(id - 1), entry.getValue() / (lengths[id] * queryNorm)));
        }
        hits.sort(java.util.Comparator.comparingDouble(Hit::score).reversed().thenComparingInt(hit -> hit.document().id()));
        return List.copyOf(hits.subList(0, Math.min(top, hits.size())));
    }

    static List<Map<String, String>> readTsv(Path path, String... required) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) throw new IOException("Empty TSV: " + path);
            String[] columns = header.split("\t", -1);
            if (new java.util.HashSet<>(List.of(columns)).size() != columns.length) throw new IOException("Duplicate TSV headers: " + path);
            for (String name : required) if (!List.of(columns).contains(name)) throw new IOException("Missing column " + name + " in " + path);
            List<Map<String, String>> rows = new ArrayList<>();
            String line; int number = 1;
            while ((line = reader.readLine()) != null) {
                number++;
                String[] values = line.split("\t", -1);
                if (values.length != columns.length) throw new IOException("Malformed TSV row " + number + " in " + path);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.length; i++) row.put(columns[i], values[i]);
                rows.add(row);
            }
            return rows;
        }
    }
    static String cell(String value) { return value.replaceAll("[\\t\\r\\n]", " "); }
    public static MusicBrainzIndex load(Path directory) throws IOException {
        var manifest = readTsv(directory.resolve("manifest.tsv"), "key", "value");
        Map<String, String> meta = new HashMap<>();
        for (var row : manifest) if (meta.put(row.get("key"), row.get("value")) != null) throw new IOException("Duplicate index metadata");
        if (!"COMPLETE".equals(meta.get("state")) || !"1".equals(meta.get("version")) || !TOKENIZER.equals(meta.get("tokenizer")))
            throw new IOException("Incomplete/incompatible index");
        MusicBrainzIndex result = new MusicBrainzIndex();
        try {
            var docs = readTsv(directory.resolve("documents.tsv"), "doc_id", "page_id", "source_url", "entity_type", "name",
                "artist_credit", "format", "indexed_text", "token_count");
            java.util.Set<String> urls = new java.util.HashSet<>();
            for (var row : docs) {
                int id = Integer.parseInt(row.get("doc_id")), count = Integer.parseInt(row.get("token_count"));
                if (id != result.documents.size() + 1 || count < 0 || !urls.add(row.get("source_url"))) throw new IOException("Invalid document IDs/URLs");
                result.documents.add(new Document(id, row.get("page_id"), row.get("source_url"), row.get("entity_type"),
                    row.get("name"), row.get("artist_credit"), row.get("format"), row.get("indexed_text"), count));
            }
            int[] counts = new int[result.documents.size() + 1];
            for (var row : readTsv(directory.resolve("postings.tsv"), "term", "doc_id", "tf")) {
                int id = Integer.parseInt(row.get("doc_id")), tf = Integer.parseInt(row.get("tf"));
                String term = row.get("term");
                if (id < 1 || id >= counts.length || tf < 1 || !tokenize(term).equals(List.of(term))) throw new IOException("Invalid posting");
                if (result.postings.computeIfAbsent(term, ignored -> new TreeMap<>()).put(id, tf) != null) throw new IOException("Duplicate posting");
                counts[id] = Math.addExact(counts[id], tf);
            }
            for (Document doc : result.documents) if (counts[doc.id()] != doc.tokenCount()) throw new IOException("Token count mismatch");
            var terms = readTsv(directory.resolve("terms.tsv"), "term", "df", "idf_classic", "idf_smooth");
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (var row : terms) {
                String term = row.get("term"); int df = Integer.parseInt(row.get("df"));
                if (!seen.add(term) || !result.postings.containsKey(term) || result.postings.get(term).size() != df)
                    throw new IOException("Dictionary DF mismatch");
                for (Method method : Method.values()) {
                    double value = Double.parseDouble(row.get("idf_" + method.name().toLowerCase(Locale.ROOT)));
                    if (!Double.isFinite(value) || Math.abs(value - idf(method, result.documents.size(), df)) > 1e-12)
                        throw new IOException("Dictionary IDF mismatch");
                }
            }
            if (seen.size() != result.postings.size() || result.documents.size() != Integer.parseInt(meta.get("documents")))
                throw new IOException("Index counts do not match manifest");
        } catch (RuntimeException e) { throw new IOException("Invalid index data", e); }
        return result;
    }
}
