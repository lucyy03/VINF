import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Scheduler regression using saved outgoing links; unknown pages are terminal fixtures. */
public class SavedGraphChecks {
    public static void main(String[] args) throws Exception {
        Map<String,String> saved = new LinkedHashMap<>();
        for (String run : List.of("2026-10-10_23-08-15_7424884074887970765", "2026-10-21_14-47-03_17344581835007687282")) {
            Path root=Path.of("crawler-output",run);
            for (var row:MusicBrainzIndex.readTsv(root.resolve("pages.tsv"),"url","html_file"))
                saved.put(row.get("url"),Files.readString(root.resolve(row.get("html_file"))));
        }
        Files.createDirectories(Path.of("target"));
        Path work=Files.createTempDirectory(Path.of("target"),"saved-graph-check-").toAbsolutePath();
        Class<?> tc=Class.forName("MusicBrainzCrawler$CrawlTransport");
        var rc=Class.forName("MusicBrainzCrawler$RobotsFetch").getDeclaredConstructor(String.class);rc.setAccessible(true);
        var pc=Class.forName("MusicBrainzCrawler$PageResponse").getDeclaredConstructor(int.class,String.class,byte[].class);pc.setAccessible(true);
        int[] responses={0},releases={0},known={0};
        Object fake=Proxy.newProxyInstance(tc.getClassLoader(),new Class<?>[]{tc},(proxy,method,values)->{
            BreadthChecks.call("reserveRequest",new Class<?>[]{});
            if(method.getName().equals("robots")) return rc.newInstance("User-agent: *\nDisallow:\n");
            String url=(String)values[0];responses[0]++;
            if(url.contains("/release/"))releases[0]++;
            if(saved.containsKey(url))known[0]++;
            String html=saved.getOrDefault(url,"<html><title>Terminal fixture - MusicBrainz</title><body></body></html>");
            return pc.newInstance(200,"text/html",html.getBytes(StandardCharsets.UTF_8));
        });
        BreadthChecks.set("OUTPUT_ROOT",work.resolve("output"));BreadthChecks.set("transport",fake);
        BreadthChecks.call("configure",new Class<?>[]{String[].class},(Object)new String[]{"--fresh","--pages","100","--seeds","crawl-seeds.tsv","--depth","6","--albums-per-artist","3","--releases-per-group","2","--list-pages","2","--artist-links","1"});
        BreadthChecks.call("runCrawler",new Class<?>[]{String[].class},(Object)new String[]{});
        BreadthChecks.check(releases[0]>0,"Saved graph must reach editions within a 100-page budget");
        Files.writeString(work.resolve("result.tsv"),"metric\tvalue\nresponses\t"+responses[0]+"\nknown_saved_html\t"+known[0]+"\nrelease_responses\t"+releases[0]+"\nnetwork_requests\t0\n");
        System.out.println("Saved-graph checks passed: "+releases[0]+" editions within "+responses[0]+" responses ("+known[0]+" saved HTML, others terminal fixtures); zero network. "+work);
    }
}
