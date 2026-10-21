import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline multi-seed scheduling, edition limits and interrupted-run recovery. */
public class PaginationChecks {
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
            int n=Integer.parseInt(java.net.URI.create(u).getPath().substring(java.net.URI.create(u).getPath().lastIndexOf('-')+1),16);
            StringBuilder html=new StringBuilder("<html><title>Fixture - MusicBrainz</title><body>");
            if(u.contains("/artist/") && n<4) html.append("<a href='").append(url("artist",n)).append("?page=1'>one</a><a href='").append(url("artist",n)).append("?page=2'>two</a><a href='").append(url("artist",n)).append("?page=3'>over cap</a><a href='").append(url("artist",n)).append("?page=2&amp;x=1'>trap</a>");
            if(u.contains("/artist/")) for(int i=0;i<3;i++) html.append("<a href='").append(url("release-group",(n*10+i+(MusicBrainzUrls.page(u)>1?100:0)))).append("'>album</a>");
            if(u.contains("/release-group/")) for(int i=0;i<5;i++) html.append("<a href='").append(url("release",(n*10+i+(MusicBrainzUrls.page(u)>1?100:0)))).append("'>edition</a>");
            if(u.contains("/release-group/") && n<40) html.append("<a href='").append(url("artist",99)).append("'>new artist</a>");
            return pc.newInstance(200,"text/html",html.append("</body></html>").toString().getBytes(StandardCharsets.UTF_8));
        });
        set("OUTPUT_ROOT",work.resolve("output"));set("transport",fake);
        call("configure",new Class<?>[]{String[].class},(Object)new String[]{"--fresh","--artist-links","1","--seeds",seeds.toString(),"--pages","100","--list-pages","2","--releases-per-group","2","--albums-per-artist","4"});
        try { call("runCrawler",new Class<?>[]{String[].class},(Object)new String[]{}); throw new AssertionError("Expected interruption"); }
        catch(IllegalStateException expected) {check(expected.getMessage().contains("Simulated"),"Unexpected failure: "+expected);}
        set("requestCount",0);set("checkpointDirectory",null);set("activeRobots",null);
        call("configure",new Class<?>[]{String[].class},(Object)new String[]{"--recover"});
        call("runCrawler",new Class<?>[]{String[].class},(Object)new String[]{});
        check(requests.size()==52 && new HashSet<>(requests).size()==52,"Exactly 4 artists + 8 groups + 16 editions, without redownloading commits");
        for(int i=0;i<3;i++) check(requests.get(i).equals(url("artist",i+1)),"All seed artists must precede albums");
        check(requests.contains(url("artist",99)) && requests.contains(url("release-group",990)) && requests.contains(url("release",9900)),"Discover artist beyond seeds and expand their albums and editions");
        check(requests.stream().filter(u -> MusicBrainzUrls.page(u)==2).count()==3, "Each second page fetched once, including after recovery");
        check(requests.stream().noneMatch(u -> u.contains("page=1") || u.contains("page=3") || u.contains("&")), "Canonicalization, cap and query traps");
        check(requests.stream().filter(u -> u.contains("/release-group/")).count()==15,"Album cap spans pagination pages and survives recovery");
        check((int)get("ARTIST_LINKS")==1,"Recovery restores artist discovery limit");
        check((int)get("MAX_LIST_PAGES")==2,"Recover pagination cap");
        check((int)get("MAX_DEPTH")==6 && (int)get("ALBUMS_PER_ARTIST")==4,"Restore depth and artist album limit");
        check((boolean)get("BREADTH") && (int)get("RELEASES_PER_GROUP")==2 && ((List<?>)get("SEEDS")).size()==3,"Recover saved strategy without seed-file argument");
        Path root=(Path)get("OUTPUT_ROOT");Path run=root.resolve(Files.readString(root.resolve("latest_checkpoint.tsv")).strip());
        check(Files.readString(run.resolve("checkpoint.tsv")).contains("STATE\tEXHAUSTED"),"Finite fixture must exhaust");
        check(Files.readAllLines(run.resolve("breadth_coverage.tsv")).size()==4,"Coverage contains three branches");
        System.out.println("Pagination checks passed: bounded listing pages, query traps, discovery and crash recovery. "+work);
    }
}
