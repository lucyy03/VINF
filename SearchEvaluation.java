import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Offline evaluation against human-supplied binary relevance judgements (TSV). */
public class SearchEvaluation {
    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5) throw new IllegalArgumentException(
            "SearchEvaluation <index-directory> <queries.tsv> <qrels.tsv> <report.tsv> [K]");
        int k = args.length == 5 ? Integer.parseInt(args[4]) : 5;
        if (k < 1) throw new IllegalArgumentException("K must be positive");
        Path indexPath = Path.of(args[0]).toRealPath(), report = Path.of(args[3]).toAbsolutePath().normalize();
        if (report.startsWith(indexPath) || report.equals(Path.of(args[1]).toAbsolutePath().normalize())
            || report.equals(Path.of(args[2]).toAbsolutePath().normalize())) throw new IOException("Report would overwrite an input");
        var index = MusicBrainzIndex.load(indexPath);
        var queries = MusicBrainzIndex.readTsv(Path.of(args[1]), "query_id", "query");
        Map<String, Set<String>> relevant = new LinkedHashMap<>(); Set<String> urls = new HashSet<>();
        for (var doc : index.documents) urls.add(doc.url());
        Set<String> queryIds = new HashSet<>();
        for (var q : queries) if (!queryIds.add(q.get("query_id")) || q.get("query_id").isBlank()
            || MusicBrainzIndex.tokenize(q.get("query")).isEmpty()) throw new IOException("Invalid query IDs/text");
        for (var row : MusicBrainzIndex.readTsv(Path.of(args[2]), "query_id", "source_url")) {
            if (!queryIds.contains(row.get("query_id")) || !urls.contains(row.get("source_url"))) throw new IOException("Judgement references unknown query/document");
            relevant.computeIfAbsent(row.get("query_id"), key -> new LinkedHashSet<>()).add(row.get("source_url"));
        }
        StringBuilder out = new StringBuilder("query_id\tmethod\tk\tretrieved\trelevant_total\trelevant_retrieved\tprecision_at_k\trecall_at_k\trr_at_k\n");
        for (var q : queries) for (var method : MusicBrainzIndex.Method.values()) {
            Set<String> gold = relevant.getOrDefault(q.get("query_id"), Set.of());
            var hits = index.search(q.get("query"), method, true, k);
            int found = 0; double rr = 0;
            for (int i = 0; i < hits.size(); i++) if (gold.contains(hits.get(i).document().url())) {
                found++; if (rr == 0) rr = 1.0 / (i + 1);
            }
            out.append(q.get("query_id")).append('\t').append(method.name().toLowerCase(Locale.ROOT)).append('\t').append(k)
                .append('\t').append(hits.size()).append('\t').append(gold.size()).append('\t').append(found).append('\t')
                .append(gold.isEmpty() ? "" : (double) found / k).append('\t')
                .append(gold.isEmpty() ? "" : (double) found / gold.size()).append('\t')
                .append(gold.isEmpty() ? "" : rr).append('\n');
        }
        Files.createDirectories(report.getParent()); Files.writeString(report, out);
        System.out.println("Evaluation: " + queries.size() + " queries, both methods; " + report);
    }
}
