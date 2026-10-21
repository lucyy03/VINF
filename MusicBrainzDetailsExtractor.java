import java.util.*;
import java.util.regex.*;

/** Additional fields from visible saved HTML only; no requests or embedded JSON. */
public class MusicBrainzDetailsExtractor {
    private static final String UUID="[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
    private static final Pattern ANCHOR=Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a\\s*>");
    private static final Pattern ROW=Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>");
    public record Track(int sequence,String mbid,String position,String name,String recordingMbid,String duration,String durationMs) {}
    public record Label(String mbid,String name,String catalogNumbers) {}
    public record Details(Map<String,String> fields,List<Track> tracks,List<Label> labels) {}
    private static String text(String s) { return MusicBrainzRegexExtractor.text(s); }
    private static String attr(String s,String name) { return MusicBrainzRegexExtractor.attribute(s,name); }
    private static String block(String tag,String css,String html) {
        return MusicBrainzRegexExtractor.firstBody(MusicBrainzRegexExtractor.blockPattern(tag,css),html,2);
    }
    private static String idBlock(String id,String html) {
        var m=Pattern.compile("(?is)<div\\b(?=[^>]*\\bid\\s*=\\s*[\"']"+Pattern.quote(id)+"[\"'])[^>]*>(.*?)</div\\s*>").matcher(html);
        return m.find()?m.group(1):"";
    }
    private static String entityId(String href,String type) {
        var m=Pattern.compile("^(?:https://musicbrainz\\.org)?/"+type+"/("+UUID+")/?$").matcher(href);
        return m.matches()?m.group(1).toLowerCase(Locale.ROOT):"";
    }
    static Details extract(String type,String html) {
        // Sidebar contains the entity/parent rating; track rows have their own ratings.
        int sidebarStart=html.indexOf("id=\"sidebar\"");
        if(sidebarStart<0) sidebarStart=html.indexOf("id='sidebar'");
        String sidebar=sidebarStart<0?"":html.substring(sidebarStart);
        int footer=sidebar.indexOf("id=\"footer\""); if(footer>=0) sidebar=sidebar.substring(0,footer);
        Set<String> tags=new LinkedHashSet<>();
        var anchors=ANCHOR.matcher(idBlock("sidebar-tag-list",sidebar));
        while(anchors.find()) if(attr(anchors.group(1),"href").startsWith("/tag/")) tags.add(text(anchors.group(2)));
        String rating=text(block("span","current-rating",sidebar));
        if(!rating.matches("(?:[0-4](?:\\.\\d+)?|5(?:\\.0+)?)")) rating="";
        String scope=rating.isEmpty()?"":type.equals("release")?"release-group":type;
        String earliest="";
        if(type.equals("release-group")) {
            var dates=MusicBrainzRegexExtractor.blockPattern("span","release-date").matcher(html);
            while(dates.find()) { String date=text(dates.group(2));
                if(date.matches("\\d{4}(?:-\\d{2}(?:-\\d{2})?)?") && (earliest.isEmpty()||date.compareTo(earliest)<0)) earliest=date;
            }
        }
        Map<String,String> fields=new LinkedHashMap<>();
        fields.put("other_tags",String.join(";",tags));fields.put("rating",rating);fields.put("rating_scope",scope);
        fields.put("earliest_listed_release_date",earliest);
        for(String prop:List.of("end-date","gender","sort-name")) {
            String value=type.equals("artist")?text(block("dd",prop,sidebar)):"";
            if(prop.equals("end-date")) {var m=Pattern.compile("^(\\d{4}(?:-\\d{2}(?:-\\d{2})?)?)(?:\\s|$)").matcher(value); value=m.find()?m.group(1):"";}
            fields.put(prop,value);
        }
        List<Track> tracks=new ArrayList<>(); List<Label> labels=new ArrayList<>();
        if(type.equals("release")) {
            var rows=ROW.matcher(html); Set<String> seen=new HashSet<>();
            while(rows.find()) {
                String row=rows.group(1),position=text(block("td","pos",row)),trackId="",recordingId="",name="";
                var links=ANCHOR.matcher(block("td","pos",row));
                while(links.find()) {String id=entityId(attr(links.group(1),"href"),"track");if(!id.isEmpty())trackId=id;}
                if(trackId.isEmpty()) continue;
                links=ANCHOR.matcher(block("td","title",row));
                while(links.find()) {String id=entityId(attr(links.group(1),"href"),"recording");if(!id.isEmpty()){recordingId=id;name=text(links.group(2));break;}}
                if(!seen.add(trackId)) continue;
                String duration=text(block("td","treleases",row));
                if(duration.isEmpty()) duration=text(block("td","length",row));
                tracks.add(new Track(tracks.size()+1,trackId,position,name,recordingId,duration,durationMs(duration)));
            }
            // Restrict label names to the release sidebar Labels section, excluding relationships.
            var section=Pattern.compile("(?is)<h2\\b[^>]*class=[\"']labels[\"'][^>]*>.*?</h2>\\s*<ul\\b[^>]*>(.*?)</ul>").matcher(sidebar);
            if(section.find()) {
                var items=Pattern.compile("(?is)<li\\b[^>]*>(.*?)</li>").matcher(section.group(1));
                while(items.find()) {
                    String body=items.group(1);var link=ANCHOR.matcher(body);
                    Set<String> catalogs=new LinkedHashSet<>();var numbers=MusicBrainzRegexExtractor.blockPattern("span","catalog-number").matcher(body);
                    while(numbers.find()) catalogs.add(text(numbers.group(2)));
                    while(link.find()) {String id=entityId(attr(link.group(1),"href"),"label");if(!id.isEmpty()) labels.add(new Label(id,text(link.group(2)),String.join(";",catalogs)));}
                }
            }
        }
        return new Details(fields,List.copyOf(tracks),List.copyOf(labels));
    }
    static String durationMs(String duration) {
        if(!duration.matches("\\d+:[0-5]\\d(?:\\.\\d{1,3})?|\\d+:[0-5]\\d:[0-5]\\d(?:\\.\\d{1,3})?")) return "";
        String[] parts=duration.split(":");
        try {
            long minutes=Long.parseLong(parts[0]);
            if(parts.length==3) minutes=Math.addExact(Math.multiplyExact(minutes,60),Long.parseLong(parts[1]));
            return Long.toString(Math.addExact(Math.multiplyExact(minutes,60000),new java.math.BigDecimal(parts[parts.length-1]).movePointRight(3).longValueExact()));
        }
        catch(ArithmeticException|NumberFormatException e) {return "";}
    }
}
