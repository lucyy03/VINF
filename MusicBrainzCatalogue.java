import java.util.*;
import java.util.regex.*;

/** Visible catalogue rows, including artist-list first-release dates and edition links. */
final class MusicBrainzCatalogue {
    record Entry(String relation, String target, String name, String firstDate) {}
    static List<Entry> extract(String sourceUrl, String html) {
        String source = MusicBrainzUrls.canonical(sourceUrl);
        if (source.isEmpty()) return List.of();
        List<Entry> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        var rows = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>").matcher(html);
        while (rows.find()) {
            String row = rows.group(1);
            var anchors = Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a\\s*>").matcher(row);
            while (anchors.find()) {
                String href = MusicBrainzRegexExtractor.attribute(anchors.group(1), "href");
                String target;
                try { target = MusicBrainzUrls.canonical(java.net.URI.create(source).resolve(href).toString()); }
                catch (RuntimeException e) { continue; }
                boolean group = source.contains("/artist/") && target.contains("/release-group/");
                boolean edition = source.contains("/release-group/") && target.contains("/release/");
                if ((!group && !edition) || !seen.add(target)) continue;
                String date = "";
                if (group) {
                    var cell = Pattern.compile("(?is)^\\s*<td\\b[^>]*>(.*?)</td>").matcher(row);
                    if (cell.find()) {
                        String value = MusicBrainzRegexExtractor.text(cell.group(1));
                        if (value.matches("\\d{4}(?:-\\d{2}(?:-\\d{2})?)?")) date = value;
                    }
                }
                result.add(new Entry(group ? "artist_release_group" : "group_edition", target,
                    MusicBrainzRegexExtractor.text(anchors.group(2)), date));
            }
        }
        return result;
    }
}
