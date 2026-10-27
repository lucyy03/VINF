import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Offline recommendations from genre/tag profiles, independent of album keyword search. */
final class MusicBrainzSimilarity {
    static void persist(Path input, Path output) throws IOException {
        var entities = MusicBrainzIndex.readTsv(input.resolve("entities.tsv"), "source_url", "genres");
        Map<String, Set<String>> profiles = new HashMap<>();
        Map<String, Map<String, String>> byUrl = new HashMap<>();
        for (var row : entities) {
            byUrl.put(row.get("source_url"), row);
            profiles.put(row.get("source_url"), features(row.get("genres")));
        }
        if (Files.exists(input.resolve("entity_details.tsv")))
            for (var row : MusicBrainzIndex.readTsv(input.resolve("entity_details.tsv"), "source_url", "other_tags"))
                profiles.get(row.get("source_url")).addAll(features(row.get("other_tags")));
        MusicBrainzIndex artists = new MusicBrainzIndex(), songs = new MusicBrainzIndex();
        for (var row : entities) if (row.get("entity_type").equals("artist"))
            artists.add(row.get("page_id"), row.get("source_url"), "artist", row.get("name"), "", "",
                String.join(" ", profiles.get(row.get("source_url"))));
        // Recording MBIDs merge the same song across editions; missing IDs use artist + title.
        Map<String, Map<String, String>> recordings = new LinkedHashMap<>();
        Map<String, Set<String>> songProfiles = new LinkedHashMap<>();
        if (Files.exists(input.resolve("tracks.tsv"))) {
            for (var row : MusicBrainzIndex.readTsv(input.resolve("tracks.tsv"), "source_url", "name")) {
                var parent = byUrl.get(row.get("source_url"));
                if (parent == null || row.get("name").isBlank()) continue;
                Set<String> profile = new TreeSet<>(profiles.get(parent.get("source_url")));
                for (String id : parent.getOrDefault("artist_mbids", "").split(";"))
                    profile.addAll(profiles.getOrDefault("https://musicbrainz.org/artist/" + id, Set.of()));
                String id = row.getOrDefault("recording_mbid", "");
                String key = id.isBlank() ? "title:" + normalized(parent.get("artist_credit")) + ":" + normalized(row.get("name")) : id;
                recordings.putIfAbsent(key, Map.of("page", parent.get("page_id"), "name", row.get("name"),
                    "artist", parent.get("artist_credit"), "url", id.isBlank() ? parent.get("source_url") : "https://musicbrainz.org/recording/" + id));
                songProfiles.computeIfAbsent(key, ignored -> new TreeSet<>()).addAll(profile);
            }
        }
        for (var entry : recordings.entrySet()) {
            var row = entry.getValue();
            songs.add(row.get("page"), row.get("url"), "recording", row.get("name"), row.get("artist"), "",
                String.join(" ", songProfiles.get(entry.getKey())));
        }
        write(output.resolve("similar-artists.tsv"), artists);
        write(output.resolve("similar-songs.tsv"), songs);
    }
    private static Set<String> features(String value) {
        Set<String> result = new TreeSet<>();
        for (String tag : value.split(";")) {
            String normalized = normalized(tag);
            if (!normalized.isEmpty()) result.add("tag" + HexFormat.of().formatHex(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        return result;
    }
    private static String normalized(String text) { return String.join(" ", MusicBrainzIndex.tokenize(text)); }
    private static void write(Path path, MusicBrainzIndex index) throws IOException {
        StringBuilder data = new StringBuilder("page_id\tsource_url\tentity_type\tname\tartist_credit\tprofile\n");
        for (var doc : index.documents) data.append(String.join("\t", MusicBrainzIndex.cell(doc.pageId()),
            MusicBrainzIndex.cell(doc.url()), doc.type(), MusicBrainzIndex.cell(doc.name()), MusicBrainzIndex.cell(doc.artist()), doc.text())).append('\n');
        Files.writeString(path, data);
    }
    static MusicBrainzIndex load(Path directory, String type) throws IOException {
        Path path = directory.resolve("similar-" + (type.equals("artist") ? "artists" : "songs") + ".tsv");
        if (!Files.exists(path)) throw new IOException("Similarity profiles missing. Rebuild the index from extracted data using MusicBrainzIndexer.");
        MusicBrainzIndex result = new MusicBrainzIndex();
        for (var row : MusicBrainzIndex.readTsv(path, "page_id", "source_url", "entity_type", "name", "artist_credit", "profile"))
            result.add(row.get("page_id"), row.get("source_url"), row.get("entity_type"), row.get("name"), row.get("artist_credit"), "", row.get("profile"));
        return result;
    }
    static List<MusicBrainzIndex.Hit> search(MusicBrainzIndex profiles, String query, MusicBrainzIndex.Method method, int top) {
        if (top < 1) throw new IllegalArgumentException("top must be positive");
        String name = normalized(query);
        var seeds = profiles.documents.stream().filter(doc -> doc.url().equals(query)
            || normalized(doc.name()).equals(name) || normalized(doc.name() + " " + doc.artist()).equals(name)).toList();
        if (seeds.isEmpty()) throw new IllegalArgumentException("Artist/song not found: " + query);
        if (seeds.size() > 1) throw new IllegalArgumentException("Ambiguous name; use title followed by artist or a MusicBrainz URL: " + query);
        var seed = seeds.get(0);
        if (seed.text().isBlank()) throw new IllegalArgumentException("No genres/tags available for: " + query);
        return profiles.search(seed.text(), method, false, profiles.documents.size()).stream()
            .filter(hit -> hit.document().id() != seed.id()).limit(top).toList();
    }
}
