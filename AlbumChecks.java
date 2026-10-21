import java.nio.file.*;
import java.util.*;

/** Offline checks for album identity, joins, pagination scopes and first-date evidence. */
public class AlbumChecks {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        String artist = "https://musicbrainz.org/artist/a74b1b7f-71a5-4011-9441-d0b5e4122711";
        for (String suffix : List.of("?page=0", "?page=02", "?page=-1", "?page=2&x=1", "?page=%32", "/releases?page=2", "#x"))
            check(MusicBrainzUrls.canonical(artist + suffix).isEmpty(), "Reject query trap " + suffix);
        check(MusicBrainzUrls.normalize(artist + "?page=1").equals(artist), "Page one canonicalizes");
        check(MusicBrainzUrls.canonical(artist + "?page=2").equals(artist), "Pagination retains entity identity");
        check(MusicBrainzUrls.canonical(artist.replace("/artist/", "/release/") + "?page=2").isEmpty(), "No release pagination");
        check(MusicBrainzAlbumIndexer.consistentDate(Set.of("2013", "2013-09-09")).equals("2013-09-09"), "Compatible precision");
        check(MusicBrainzAlbumIndexer.consistentDate(Set.of("2013", "2014")).isEmpty(), "Contradictory dates remain unknown");
        Path run = Path.of("crawler-output/2026-10-10_23-08-15_7424884074887970765");
        var rows = Files.readAllLines(run.resolve("pages.tsv"));
        var row = rows.get(1).split("\t");
        var original = MusicBrainzRegexExtractor.extract(row[1], Files.readString(run.resolve(row[5])));
        var listing = MusicBrainzRegexExtractor.extract(row[1] + "?page=2", Files.readString(run.resolve(row[5])));
        check(original.fields().equals(listing.fields()), "Listing is not a second album identity");
        String group = row[1];
        var catalogue = MusicBrainzCatalogue.extract(artist,
            "<table><tr><td class='c'>1997</td><td><a href='" + group + "'>OK Computer</a></td></tr></table>");
        check(catalogue.size() == 1 && catalogue.get(0).firstDate().equals("1997"), "First date comes from artist row, not an edition minimum");
        Path input = Path.of("analysis-output/2026-10-10_23-08-15_7424884074887970765/regex");
        Files.createDirectories(Path.of("target"));
        Path output = Files.createTempDirectory(Path.of("target"), "album-index-check-");
        var index = MusicBrainzIndexer.buildAlbums(input, output);
        check(index.documents.size() == 51 && index.documents.stream().allMatch(d -> d.type().equals("release-group")), "One document per fetched album group");
        for (var method : MusicBrainzIndex.Method.values()) {
            var hits = index.search("Radiohead Airbag Parlophone", method, true, 5);
            check(hits.size() == 1 && hits.get(0).document().url().equals(group), "Edition information returns one album, not many editions");
            check(index.search("zzzneexistujuci", method, true, 5).isEmpty(), "Unknown words");
        }
        check(MusicBrainzIndex.load(output).postings.equals(index.postings), "Album index persists and loads");
        check(Files.readAllLines(output.resolve("editions.tsv")).size() == 40, "All 39 fetched editions attached");
        System.out.println("Album checks passed: 51 groups, 39 attached editions, both rankings, first dates and URL scope.");
    }
}
