import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One searchable document per fetched release group; editions retain their provenance. */
final class MusicBrainzAlbumIndexer {
    static MusicBrainzIndex build(Path input, Path pages, Path output) throws IOException {
        input = input.toRealPath(); output = output.toAbsolutePath().normalize();
        if (output.startsWith(input)) throw new IOException("Index must be outside extraction input");
        Files.createDirectories(output);
        try (var files = Files.list(output)) { if (files.findAny().isPresent()) throw new IOException("Use an empty index directory"); }
        MusicBrainzIndexer.atomicText(output.resolve("manifest.tsv"), "key\tvalue\nversion\t1\nstate\tBUILDING\n");
        var pageIndex = MusicBrainzIndex.load(pages);
        Map<String, MusicBrainzIndex.Document> docs = new LinkedHashMap<>();
        for (var doc : pageIndex.documents) docs.put(doc.url(), doc);
        Map<String, String> hashes = new LinkedHashMap<>();
        for (var row : MusicBrainzIndex.readTsv(pages.resolve("manifest.tsv"), "key", "value"))
            if (row.get("key").startsWith("input_sha256_")) hashes.put(row.get("key").substring(13), row.get("value"));
        hashes.put("entity_links.tsv", MusicBrainzIndexer.sha256(input.resolve("entity_links.tsv")));
        Map<String, String> editionGroups = new LinkedHashMap<>();
        for (var row : MusicBrainzIndex.readTsv(input.resolve("entity_links.tsv"), "page_id", "source_url", "relation", "target_url")) {
            var source = docs.get(row.get("source_url"));
            if (source == null || !source.pageId().equals(row.get("page_id"))) throw new IOException("Invalid relationship parent");
            if (!row.get("relation").equals("release_group")) continue;
            String target = row.get("target_url");
            if (!source.type().equals("release") || !target.equals(MusicBrainzUrls.canonical(target)) || !target.contains("/release-group/"))
                throw new IOException("Invalid release-group relationship");
            String previous = editionGroups.putIfAbsent(source.url(), target);
            if (previous != null && !previous.equals(target)) throw new IOException("Conflicting edition parents");
        }
        Map<String, List<MusicBrainzIndex.Document>> groupedEditions = new LinkedHashMap<>();
        for (var doc : pageIndex.documents) if (editionGroups.containsKey(doc.url()))
            groupedEditions.computeIfAbsent(editionGroups.get(doc.url()), k -> new ArrayList<>()).add(doc);
        Map<String, Set<String>> firstDates = new HashMap<>();
        Path catalogue = input.resolve("catalogue.tsv");
        if (Files.exists(catalogue)) {
            hashes.put("catalogue.tsv", MusicBrainzIndexer.sha256(catalogue));
            for (var row : MusicBrainzIndex.readTsv(catalogue, "page_id", "source_url", "relation", "target_url", "first_release_date")) {
                String date = row.get("first_release_date");
                String base = MusicBrainzUrls.canonical(row.get("source_url"));
                var source = docs.get(base);
                if (source == null || (MusicBrainzUrls.page(row.get("source_url")) == 1 && !source.pageId().equals(row.get("page_id"))))
                    throw new IOException("Invalid catalogue parent");
                if (!row.get("relation").equals("artist_release_group") || date.isEmpty()) continue;
                if (!source.type().equals("artist") || !date.matches("\\d{4}(?:-\\d{2}(?:-\\d{2})?)?"))
                    throw new IOException("Invalid first-release date evidence");
                firstDates.computeIfAbsent(row.get("target_url"), k -> new LinkedHashSet<>()).add(date);
            }
        }
        Map<String, Map<String, String>> entities = new HashMap<>();
        for (var row : MusicBrainzIndex.readTsv(input.resolve("entities.tsv"), "source_url", "artist_mbids")) entities.put(row.get("source_url"), row);
        MusicBrainzIndex result = new MusicBrainzIndex();
        StringBuilder editions = new StringBuilder("album_url\tpage_id\trelease_url\tname\tformat\n");
        StringBuilder albums = new StringBuilder("album_url\tfirst_release_date\tdate_basis\teditions\n");
        StringBuilder omitted = new StringBuilder("release_url\treason\n");
        for (var doc : pageIndex.documents) {
            if (!doc.type().equals("release-group")) continue;
            Set<String> text = new LinkedHashSet<>(); text.add(doc.text()); Set<String> formats = new LinkedHashSet<>(); int count = 0;
            String date = consistentDate(firstDates.getOrDefault(doc.url(), Set.of()));
            if (!date.isEmpty()) text.add(date);
            for (var edition : groupedEditions.getOrDefault(doc.url(), List.of())) {
                text.add(edition.text()); if (!edition.format().isBlank()) formats.add(edition.format()); count++;
                editions.append(doc.url()).append('\t').append(edition.pageId()).append('\t').append(edition.url()).append('\t')
                    .append(MusicBrainzIndex.cell(edition.name())).append('\t').append(MusicBrainzIndex.cell(edition.format())).append('\n');
            }
            for (String id : entities.get(doc.url()).get("artist_mbids").split(";")) {
                var artist = docs.get("https://musicbrainz.org/artist/" + id);
                if (artist != null) text.add(artist.text());
            }
            result.add(doc.pageId(), doc.url(), "release-group", doc.name(), doc.artist(), String.join(";", formats), String.join(" ", text));
            albums.append(doc.url()).append('\t').append(date).append('\t')
                .append(date.isEmpty() ? "unknown_or_conflicting" : "artist_catalogue").append('\t').append(count).append('\n');
        }
        for (var doc : pageIndex.documents) if (doc.type().equals("release") && !docs.containsKey(editionGroups.get(doc.url())))
            omitted.append(doc.url()).append("\tRelease group not fetched or relationship missing\n");
        if (result.documents.isEmpty()) throw new IOException("No fetched release groups; crawl album pages before building an album index");
        Files.writeString(output.resolve("editions.tsv"), editions);
        Files.writeString(output.resolve("albums.tsv"), albums);
        Files.writeString(output.resolve("unattached_editions.tsv"), omitted);
        int warnings = 0;
        for (var row : MusicBrainzIndex.readTsv(pages.resolve("statistics.tsv"), "metric", "value"))
            if (row.get("metric").equals("input_warnings")) warnings = Integer.parseInt(row.get("value"));
        MusicBrainzIndexer.persist(result, input, output, hashes, warnings);
        Files.writeString(output.resolve("manifest.tsv"), "entity_model\trelease-group-with-editions-v1\n", StandardOpenOption.APPEND);
        return result;
    }
    static String consistentDate(Set<String> dates) {
        String best = "";
        for (String date : dates) {
            if (!best.isEmpty() && !best.startsWith(date) && !date.startsWith(best)) return "";
            if (date.length() > best.length()) best = date;
        }
        return best;
    }
}
