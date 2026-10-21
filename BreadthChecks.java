import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline multi-seed scheduling, edition limits and interrupted-run recovery. */
public class BreadthChecks {
    static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        var m = MusicBrainzCrawler.class.getDeclaredMethod(name, types); m.setAccessible(true);
        try { return m.invoke(null, args); } catch (InvocationTargetException e) { throw (Exception)e.getCause(); }
    }
    static Object get(String name) throws Exception { var f=MusicBrainzCrawler.class.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
    static void set(String name,Object value) throws Exception { var f=MusicBrainzCrawler.class.getDeclaredField(name); f.setAccessible(true); f.set(null,value); }
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static String url(String type,int n) { return "https://musicbrainz.org/"+type+"/00000000-0000-0000-0000-"+String.format("%012x",n); }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("target"));
        Path work=Files.createTempDirectory(Path.of("target"),"breadth-check-").toAbsolutePath();
        Path seeds=work.resolve("seeds.tsv");
        Files.writeString(seeds,"\uFEFFname\turl\nA\t"+url("artist",1)+"\nB\t"+url("artist",2)+"\nC\t"+url("artist",3)+"\n");
        Class<?> tc=Class.forName("MusicBrainzCrawler$CrawlTransport");
        var rc=Class.forName("MusicBrainzCrawler$RobotsFetch").getDeclaredConstructor(String.class); rc.setAccessible(true);
        var pc=Class.forName("MusicBrainzCrawler$PageResponse").getDeclaredConstructor(int.class,String.class,byte[].class);pc.setAccessible(true);
        List<String> requests=new ArrayList<>(); boolean[] crash={true};
        Object fake=Proxy.newProxyInstance(tc.getClassLoader(),new Class<?>[]{tc},(proxy,method,values)->{
            call("reserveRequest",new Class<?>[]{});
            if(method.getName().equals("robots")) return rc.newInstance("User-agent: *\nDisallow:\n");
            if(crash[0] && requests.size()==7) { crash[0]=false; throw new IllegalStateException("Simulated interruption"); }
            String u=(String)values[0]; requests.add(u);
            int n=Integer.parseInt(u.substring(u.lastIndexOf('-')+1),16);
            StringBuilder html=new StringBuilder("<html><title>Fixture - MusicBrainz</title><body>");
            if(u.contains("/artist/")) for(int i=0;i<3;i++) html.append("<a href='").append(url("release-group",n*10+i)).append("'>album</a>");
            if(u.contains("/release-group/")) for(int i=0;i<5;i++) html.append("<a href='").append(url("release",n*10+i)).append("'>edition</a>");
            if(u.contains("/release-group/") && n<40) html.append("<a href='").append(url("artist",99)).append("'>new artist</a>");
            return pc.newInstance(200,"text/html",html.append("</body></html>").toString().getBytes(StandardCharsets.UTF_8));
        });
        set("OUTPUT_ROOT",work.resolve("output"));set("transport",fake);
        call("configure",new Class<?>[]{String[].class},(Object)new String[]{"--fresh","--seeds",seeds.toString(),"--pages","40","--releases-per-group","2","--albums-per-artist","2"});
        try { call("runCrawler",new Class<?>[]{String[].class},(Object)new String[]{}); throw new AssertionError("Expected interruption"); }
        catch(IllegalStateException expected) {check(expected.getMessage().contains("Simulated"),"Unexpected failure: "+expected);}
        set("requestCount",0);set("checkpointDirectory",null);set("activeRobots",null);
        call("configure",new Class<?>[]{String[].class},(Object)new String[]{"--recover"});
        call("runCrawler",new Class<?>[]{String[].class},(Object)new String[]{});
        check(requests.size()==28 && new HashSet<>(requests).size()==28,"Exactly 4 artists + 8 groups + 16 editions, without redownloading commits");
        for(int i=0;i<3;i++) check(requests.get(i).equals(url("artist",i+1)),"All seed artists must precede albums");
        for(int i=0;i<3;i++) check(requests.get(i+3).equals(url("release-group",(i+1)*10)),"Album branches must alternate");
        check(requests.contains(url("artist",99)) && requests.contains(url("release-group",990)) && requests.contains(url("release",9900)),"Discover artist beyond seeds and expand their albums and editions");
        check((int)get("MAX_DEPTH")==6 && (int)get("ALBUMS_PER_ARTIST")==2,"Restore depth and artist album limit");
        check((boolean)get("BREADTH") && (int)get("RELEASES_PER_GROUP")==2 && ((List<?>)get("SEEDS")).size()==3,"Recover saved strategy without seed-file argument");
        Path root=(Path)get("OUTPUT_ROOT");Path run=root.resolve(Files.readString(root.resolve("latest_checkpoint.tsv")).strip());
        check(Files.readString(run.resolve("checkpoint.tsv")).contains("STATE\tEXHAUSTED"),"Finite fixture must exhaust");
        check(Files.readAllLines(run.resolve("breadth_coverage.tsv")).size()==4,"Coverage contains three branches");
        System.out.println("Breadth checks passed: 28 pages, four artists from three seeds, two albums/artist, two editions/group, interrupted recovery. "+work);
    }
}
