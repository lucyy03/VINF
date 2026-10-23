import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public class MusicBrainzSearch {
    public static void main(String[] args) throws Exception {
        Path directory = null, queries = null, save = null;
        String query = null, match = "all";
        MusicBrainzIndex.Method method = MusicBrainzIndex.Method.CLASSIC;
        MusicBrainzIndex.Ranking ranking = MusicBrainzIndex.Ranking.ALBUM_FIELDS;
        boolean compare = false; int top = 10;
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (key.equals("--compare")) { compare = true; continue; }
            if (key.equals("--query")) { query = String.join(" ", java.util.Arrays.copyOfRange(args, i + 1, args.length)); break; }
            if (++i >= args.length) throw new IllegalArgumentException("Missing value for " + key);
            switch (key) {
                case "--index" -> directory = Path.of(args[i]);
                case "--method" -> method = MusicBrainzIndex.Method.valueOf(args[i].toUpperCase(Locale.ROOT));
                case "--ranking" -> ranking = MusicBrainzIndex.Ranking.valueOf(args[i].toUpperCase(Locale.ROOT));
                case "--match" -> match = args[i];
                case "--top" -> top = Integer.parseInt(args[i]);
                case "--queries" -> queries = Path.of(args[i]);
                case "--save" -> save = Path.of(args[i]);
                default -> throw new IllegalArgumentException("Unknown option: " + key);
            }
        }
        if (top < 1 || !List.of("all", "any").contains(match) || (query == null) == (queries == null)
            || (query != null && MusicBrainzIndex.tokenize(query).isEmpty())) throw new IllegalArgumentException(
                "Use [--index DIR] [--method classic|smooth|--compare] [--match all|any] [--top N] "
                + "[--ranking cosine|album_fields] [--save FILE] --query words... OR --queries queries.tsv");
        if (directory == null) {
            Path root = Path.of("index-output").toAbsolutePath().normalize();
            directory = root.resolve(Files.readString(root.resolve("latest-index.tsv")).strip()).normalize();
            if (!root.equals(directory.getParent())) throw new IOException("Invalid latest-index pointer");
        }
        MusicBrainzIndex index = MusicBrainzIndex.load(directory);
        java.util.Map<String,String> firstDates = new java.util.HashMap<>();
        java.util.Map<String,java.util.List<String>> editions = new java.util.HashMap<>();
        if (Files.exists(directory.resolve("albums.tsv")))
            for (var row : MusicBrainzIndex.readTsv(directory.resolve("albums.tsv"), "album_url", "first_release_date")) firstDates.put(row.get("album_url"),row.get("first_release_date"));
        if (Files.exists(directory.resolve("editions.tsv")))
            for (var row : MusicBrainzIndex.readTsv(directory.resolve("editions.tsv"), "album_url", "release_url"))
                editions.computeIfAbsent(row.get("album_url"),key -> new java.util.ArrayList<>()).add(row.get("release_url"));
        StringBuilder result = new StringBuilder("query_id\tquery\tmethod\trank\tscore\tpage_id\tname\tartist_credit\tentity_type\tformat\tsource_url\tfirst_release_date\teditions_count\trelease_urls\n");
        var requests = queries == null ? List.of(java.util.Map.of("query_id", "1", "query", query))
            : MusicBrainzIndex.readTsv(queries, "query_id", "query");
        java.util.Set<String> queryIds = new java.util.HashSet<>();
        for (var request : requests) {
            if (request.get("query_id").isBlank() || !queryIds.add(request.get("query_id"))
                || MusicBrainzIndex.tokenize(request.get("query")).isEmpty())
                throw new IllegalArgumentException("Queries require distinct IDs and nonempty words");
            for (var selected : compare ? MusicBrainzIndex.Method.values() : new MusicBrainzIndex.Method[]{method}) {
                var hits = index.search(request.get("query"), selected, match.equals("all"), top, ranking);
                int rank = 0;
                for (var hit : hits) {
                    var doc = hit.document();
                    result.append(MusicBrainzIndex.cell(request.get("query_id"))).append('\t')
                        .append(MusicBrainzIndex.cell(request.get("query"))).append('\t').append(selected.name().toLowerCase(Locale.ROOT))
                        .append('\t').append(++rank).append('\t').append(String.format(Locale.ROOT, "%.9f", hit.score()))
                        .append('\t').append(doc.pageId()).append('\t').append(MusicBrainzIndex.cell(doc.name()))
                        .append('\t').append(MusicBrainzIndex.cell(doc.artist())).append('\t').append(doc.type())
                        .append('\t').append(MusicBrainzIndex.cell(doc.format())).append('\t').append(doc.url()).append('\t')
                        .append(firstDates.getOrDefault(doc.url(), "")).append('\t').append(editions.getOrDefault(doc.url(), List.of()).size()).append('\t')
                        .append(String.join(";",editions.getOrDefault(doc.url(), List.of()))).append('\n');
                }
                if (hits.isEmpty()) System.err.println("No matches: " + request.get("query") + " [" + selected + "]");
            }
        }
        if (save != null) {
            Path target = save.toAbsolutePath().normalize();
            if (target.startsWith(directory.toAbsolutePath().normalize())) throw new IOException("Save results outside the index");
            Files.createDirectories(target.getParent()); Files.writeString(target, result, StandardCharsets.UTF_8);
        }
        System.out.print(result);
    }
}
