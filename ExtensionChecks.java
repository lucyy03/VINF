import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Offline exhausted-run extension, read-only preview, crash recovery and blocking refusal. */
public class ExtensionChecks {
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    static void configure(String...args)throws Exception { BreadthChecks.call("configure",new Class<?>[]{String[].class},(Object)args); }
    static void run()throws Exception { BreadthChecks.call("runCrawler",new Class<?>[]{String[].class},(Object)new String[]{}); }
    static String url(String type,int id) { return "https://musicbrainz.org/"+type+"/00000000-0000-0000-0000-"+String.format("%012x",id); }
    public static void main(String[] args)throws Exception {
        Files.createDirectories(Path.of("target"));
        Path work=Files.createTempDirectory(Path.of("target"),"extension-check-").toAbsolutePath();
        Path output=work.resolve("output");
        BreadthChecks.set("OUTPUT_ROOT",output);
        Class<?> tc=Class.forName("MusicBrainzCrawler$CrawlTransport");
        var robots=Class.forName("MusicBrainzCrawler$RobotsFetch").getDeclaredConstructor(String.class);robots.setAccessible(true);
        var response=Class.forName("MusicBrainzCrawler$PageResponse").getDeclaredConstructor(int.class,String.class,byte[].class);response.setAccessible(true);
        Set<String> downloaded=new HashSet<>();int[] attempts={0};boolean[] crash={false};
        Object fake=Proxy.newProxyInstance(tc.getClassLoader(),new Class<?>[]{tc},(proxy,method,values)->{
            BreadthChecks.call("reserveRequest",new Class<?>[]{});
            if(method.getName().equals("robots"))return robots.newInstance("User-agent: *\nDisallow: /blocked/\n");
            attempts[0]++;
            if(crash[0] && downloaded.size()==4) {crash[0]=false;throw new IllegalStateException("EXTENSION_CRASH");}
            String u=(String)values[0];check(downloaded.add(u),"Committed URL downloaded twice: "+u);
            StringBuilder html=new StringBuilder("<html><title>Fixture - MusicBrainz</title><body>");
            if(u.equals(url("artist",1))) {
                for(int n=10;n<=12;n++) html.append("<a href='").append(url("release-group",n)).append("'>album</a>");
                html.append("<a href='").append(u).append("?page=2'>next page</a>");
            }
            if(u.contains("/release-group/")) {
                int id=Integer.parseInt(u.substring(u.lastIndexOf('-')+1),16);
                html.append("<a href='").append(url("release",id*10)).append("'>edition</a>");
                if(id==10) html.append("<a href='").append(url("artist",2)).append("'>new artist</a>");
            }
            return response.newInstance(200,"text/html",html.append("</body></html>").toString().getBytes(StandardCharsets.UTF_8));
        });
        BreadthChecks.set("transport",fake);
        configure("--fresh","--breadth","--start-url",url("artist",1),"--pages","9","--depth","1","--albums-per-artist","1","--releases-per-group","1","--artist-links","1","--list-pages","1");run();
        Path directory=output.resolve(Files.readString(output.resolve("latest_checkpoint.tsv")).strip());
        check(downloaded.size()==2,"Fixture must exhaust with two saved pages");
        String original=Files.readString(directory.resolve("checkpoint.tsv"));
        check(original.contains("STATE\tEXHAUSTED"),"Exhaustion state");
        byte[] originalLinks=Files.readAllBytes(directory.resolve("internal_links.tsv"));
        configure("--plan","--depth","3","--albums-per-artist","3","--list-pages","2");run();
        check(Files.readString(directory.resolve("checkpoint.tsv")).equals(original)
            && Arrays.equals(originalLinks,Files.readAllBytes(directory.resolve("internal_links.tsv"))) && attempts[0]==2,"Preview changes neither outputs nor network counters");
        configure("--extend","--depth","0");
        try {run();throw new AssertionError("Decreasing scope accepted");}
        catch(java.io.IOException expected) {check(expected.getMessage().contains("decrease"),"Specific decrease refusal");}
        // Failed pre-commit preparation may have left TSV bytes; extension must trim them first.
        Files.writeString(directory.resolve("internal_links.tsv"),"uncommitted\ttail\ttrue\n",StandardOpenOption.APPEND);
        crash[0]=true;
        configure("--extend","--depth","3","--albums-per-artist","3","--list-pages","2");
        try {run();throw new AssertionError("Expected interruption");}
        catch(IllegalStateException expected) {check(expected.getMessage().equals("EXTENSION_CRASH"),"Expected crash");}
        check(Files.readString(directory.resolve("checkpoint.tsv")).contains("STORAGE\t"),"Extension uses independent atomic checkpoint storage");
        check(!Files.readString(directory.resolve("internal_links.tsv")).contains("uncommitted"),"Discard only uncommitted tail");
        check(Files.readString(directory.resolve("checkpoint-base.tsv")).contains("\t9\t1\t136"),"Old checkpoint base remains unchanged");
        BreadthChecks.set("requestCount",0);BreadthChecks.set("checkpointDirectory",null);BreadthChecks.set("activeRobots",null);
        configure("--recover");run();
        check(downloaded.size()==9 && attempts[0]==10,"Exactly nine unique pages plus one interrupted attempt");
        check(Files.readAllLines(directory.resolve("pages.tsv")).size()==10,"One continuous page sequence");
        check((int)BreadthChecks.get("requestCount")==13,"Old requests, interruption and all robots loads retained");
        check((int)BreadthChecks.get("MAX_DEPTH")==3 && (int)BreadthChecks.get("ALBUMS_PER_ARTIST")==3,"Expanded settings restored after crash");
        String complete=Files.readString(directory.resolve("checkpoint.tsv"));check(complete.contains("STATE\tCOMPLETE"),"Exact requested total");
        Files.writeString(directory.resolve("checkpoint.tsv"),complete.replace("STATE\tCOMPLETE","STATE\tSTOPPED"));
        configure("--extend","--pages","12","--depth","4");
        try {run();throw new AssertionError("Blocked run extension accepted");}
        catch(java.io.IOException expected) {check(expected.getMessage().contains("blocked runs cannot be extended"),"Explicit blocking refusal");}
        check(attempts[0]==10,"Blocking refusal makes no page requests");
        Files.writeString(directory.resolve("checkpoint.tsv"),complete);
        System.out.println("Extension checks passed: preview, preserved pages/budget, atomic checkpoint switch, crash recovery, exact total and blocking refusal. "+work);
    }
}
