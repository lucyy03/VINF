import java.net.URI;
import java.util.regex.Pattern;

/** Narrow shared URL scope. Pagination is navigation, not a separate music entity. */
final class MusicBrainzUrls {
    private static final Pattern PATH = Pattern.compile("/(artist|release-group|release)/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    static String canonical(String url) {
        try {
            URI u = URI.create(url);
            if (!"https".equals(u.getScheme()) || !"musicbrainz.org".equals(u.getHost())
                || u.getRawUserInfo() != null || (u.getPort() != -1 && u.getPort() != 443)
                || u.getRawFragment() != null || !PATH.matcher(u.getRawPath()).matches()) return "";
            if (u.getRawQuery() != null && (u.getPath().startsWith("/release/")
                || !u.getRawQuery().matches("page=[1-9][0-9]{0,5}"))) return "";
            return "https://musicbrainz.org" + u.getPath();
        } catch (RuntimeException e) { return ""; }
    }
    static int page(String url) {
        if (canonical(url).isEmpty()) return -1;
        String q = URI.create(url).getRawQuery();
        return q == null ? 1 : Integer.parseInt(q.substring(5));
    }
    static String normalize(String url) {
        String base = canonical(url);
        return base.isEmpty() ? "" : base + (page(url) == 1 ? "" : "?page=" + page(url));
    }
}
