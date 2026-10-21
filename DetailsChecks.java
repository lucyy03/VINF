import java.nio.file.*;
import java.util.*;
public class DetailsChecks {
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path run=Path.of("crawler-output/2026-10-10_23-08-15_7424884074887970765");
        List<String> rows=Files.readAllLines(run.resolve("pages.tsv"));
        var row=rows.get(9).split("\t");
        var release=MusicBrainzRegexExtractor.extract(row[1],Files.readString(run.resolve(row[5]))).details();
        check(release.tracks().size()==12,"Cassette has twelve tracks");
        var first=release.tracks().get(0);
        check(first.name().equals("Airbag") && first.position().equals("A1") && first.duration().equals("4:44") && first.durationMs().equals("284000"),"Airbag identity, position and duration");
        check(release.tracks().get(1).name().equals("Paranoid Android"),"Second track name excludes relationships");
        check(release.labels().size()==1 && release.labels().get(0).name().equals("Parlophone") && release.labels().get(0).catalogNumbers().equals("TCNODATA 02"),"Sidebar label and catalog");
        check(release.fields().get("rating").equals("4.55") && release.fields().get("rating_scope").equals("release-group"),"Album rating must not be first track rating 3.85");
        row=rows.get(1).split("\t");
        var group=MusicBrainzRegexExtractor.extract(row[1],Files.readString(run.resolve(row[5]))).details();
        check(group.fields().get("other_tags").contains("atmospheric"),"Other tags");
        check(group.fields().get("earliest_listed_release_date").equals("1997"),"Preserve year-only precision among visible edition dates");
        check(group.tracks().isEmpty() && group.labels().isEmpty(),"Edition data must not leak into release group");
        check(MusicBrainzDetailsExtractor.durationMs("1:01.250").equals("61250"),"Fractional seconds");
        check(MusicBrainzDetailsExtractor.durationMs("1:02:03.250").equals("3723250"),"Hour-long track");
        for(String invalid:List.of("?:??","1:60","","999999999999999999999:00")) check(MusicBrainzDetailsExtractor.durationMs(invalid).isEmpty(),"Invalid duration "+invalid);
        var missing=MusicBrainzDetailsExtractor.extract("release","<span class='current-rating'>3.85</span>");
        check(missing.fields().get("rating").isEmpty(),"Never treat a track rating outside sidebar as album rating");
        var metadata = new HashMap<String,String[]>();
        for (String line : rows.subList(1, rows.size())) { var values=line.split("\t"); metadata.put(values[0],values); }
        int checked=0;
        for (var expected : MusicBrainzIndex.readTsv(Path.of("test-fixtures/details-golden-20.tsv"), "page_id", "source_url", "track_count", "first_position", "first_name", "first_duration", "labels", "catalogs", "rating")) {
            var values=metadata.get(expected.get("page_id"));
            check(values[1].equals(expected.get("source_url")), "Golden source identity");
            var actual=MusicBrainzRegexExtractor.extract(values[1],Files.readString(run.resolve(values[5]))).details();
            check(actual.tracks().size()==Integer.parseInt(expected.get("track_count")), "Track count on page "+values[0]);
            check(actual.tracks().get(0).position().equals(expected.get("first_position")), "First position "+values[0]);
            check(actual.tracks().get(0).name().equals(expected.get("first_name")), "First name "+values[0]);
            check(actual.tracks().get(0).duration().equals(expected.get("first_duration")), "First duration "+values[0]);
            check(String.join(";",actual.labels().stream().map(MusicBrainzDetailsExtractor.Label::name).toList()).equals(expected.get("labels")), "Labels "+values[0]);
            check(String.join(";",actual.labels().stream().map(MusicBrainzDetailsExtractor.Label::catalogNumbers).toList()).equals(expected.get("catalogs")), "Catalog numbers "+values[0]);
            check(actual.fields().get("rating").equals(expected.get("rating")), "Album rating "+values[0]);
            checked++;
        }
        check(checked>=20,"At least twenty additional-field fixtures");
        System.out.println("Details checks passed: 20 real-page golden cases plus tracks, durations, labels/catalog, rating scope, tags, observed date and missing data.");
    }
}
