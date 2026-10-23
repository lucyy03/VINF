import java.nio.file.*;
import java.util.*;

/** Frozen independent DOM expectations versus both REGEX code and final persisted TSV. */
public class FinalCorpusChecks {
    private static final String RUN = "2026-10-22_17-15-40_1520030084247990884";
    private static Map<String,List<Map<String,String>>> byPage(Path file) throws Exception {
        Map<String,List<Map<String,String>>> result=new LinkedHashMap<>();
        for(var row:MusicBrainzIndex.readTsv(file,"page_id")) result.computeIfAbsent(row.get("page_id"),k->new ArrayList<>()).add(row);
        return result;
    }
    private static void indexed(Map<String,String> output,String prefix,int index,Map<String,String> values) {
        values.forEach((k,v)->output.put(prefix+"."+index+"."+k,v));
    }
    private static Map<String,String> fresh(MusicBrainzRegexExtractor.Extraction e) {
        Map<String,String> result=new LinkedHashMap<>(e.fields());
        e.details().fields().forEach((k,v)->result.put("detail."+k,v));
        result.put("track_count",""+e.details().tracks().size());result.put("label_count",""+e.details().labels().size());result.put("event_count",""+e.events().size());
        for(int i=0;i<e.details().tracks().size();i++) {
            var t=e.details().tracks().get(i);
            indexed(result,"track",i,Map.of("mbid",t.mbid(),"position",t.position(),"name",t.name(),"recording_mbid",t.recordingMbid(),"duration",t.duration(),"duration_ms",t.durationMs()));
        }
        for(int i=0;i<e.details().labels().size();i++) {var l=e.details().labels().get(i);indexed(result,"label",i,Map.of("mbid",l.mbid(),"name",l.name(),"catalog_numbers",l.catalogNumbers()));}
        for(int i=0;i<e.events().size();i++) {var event=e.events().get(i);indexed(result,"event",i,Map.of("country",event.country(),"date",event.date()));}
        return result;
    }
    public static void main(String[] args) throws Exception {
        if(args.length>1)throw new IllegalArgumentException("FinalCorpusChecks [crawl-directory]");
        Path run=Path.of(args.length==0?"crawler-output/"+RUN:args[0]).toRealPath();
        Path root=Path.of("analysis-output",run.getFileName().toString()),rx=root.resolve("regex");
        var entities=byPage(rx.resolve("entities.tsv"));var details=byPage(rx.resolve("entity_details.tsv"));
        var tracks=byPage(rx.resolve("tracks.tsv"));var labels=byPage(rx.resolve("labels.tsv"));var events=byPage(rx.resolve("release_events.tsv"));
        var fixtures=MusicBrainzIndex.readTsv(Path.of("test-fixtures/final-corpus-golden-20.tsv"),"page_id","source_url","entity_type","html_file","sha256","field","expected");
        Map<String,Map<String,String>> actual=new HashMap<>(),stored=new HashMap<>();Set<String> checked=new HashSet<>();int failures=0;
        StringBuilder report=new StringBuilder("page_id\tsource_url\tfield\texpected\tregex_value\tstored_value\tresult\n");
        for(var test:fixtures) {
            String id=test.get("page_id"),url=test.get("source_url");
            if(checked.add(id)) {
                Path file=run.resolve(test.get("html_file")).normalize();
                if(!file.startsWith(run.resolve("html"))||!file.toRealPath().startsWith(run.resolve("html").toRealPath()))throw new AssertionError("Unsafe fixture path");
                String hash=MusicBrainzIndexer.sha256(file);
                if(!hash.equals(test.get("sha256")))throw new AssertionError("Fixture HTML changed for page "+id);
                var entity=entities.get(id).get(0);
                if(!entity.get("source_url").equals(url)||!entity.get("entity_type").equals(test.get("entity_type")))throw new AssertionError("Fixture identity mismatch");
                actual.put(id,fresh(MusicBrainzRegexExtractor.extract(url,Files.readString(file))));
                Map<String,String> values=new LinkedHashMap<>(entity);
                details.get(id).get(0).forEach((k,v)->values.put("detail."+k,v));
                var ts=tracks.getOrDefault(id,List.of());var ls=labels.getOrDefault(id,List.of());var es=events.getOrDefault(id,List.of());
                values.put("track_count",""+ts.size());values.put("label_count",""+ls.size());values.put("event_count",""+es.size());
                for(int i=0;i<ts.size();i++) {var t=new LinkedHashMap<>(ts.get(i));t.put("mbid",t.get("track_mbid"));indexed(values,"track",i,t);}
                for(int i=0;i<ls.size();i++) {var l=new LinkedHashMap<>(ls.get(i));l.put("mbid",l.get("label_mbid"));indexed(values,"label",i,l);}
                for(int i=0;i<es.size();i++)indexed(values,"event",i,es.get(i));
                stored.put(id,values);
            }
            String field=test.get("field"),expected=test.get("expected"),a=actual.get(id).get(field),b=stored.get(id).get(field);
            boolean pass=expected.equals(a)&&expected.equals(b);
            if(!pass)failures++;
            report.append(id).append('\t').append(url).append('\t').append(field).append('\t').append(MusicBrainzIndex.cell(expected)).append('\t')
                .append(MusicBrainzIndex.cell(a==null?"[MISSING]":a)).append('\t').append(MusicBrainzIndex.cell(b==null?"[MISSING]":b)).append('\t').append(pass?"OK":"MISMATCH").append('\n');
        }
        Files.writeString(root.resolve("final-sample-validation.tsv"),report);
        if(checked.size()!=20||failures>0)throw new AssertionError("Final sample: "+checked.size()+" pages; "+failures+" mismatches. See final-sample-validation.tsv");
        System.out.println("Final corpus checks passed: 20 pages, "+fixtures.size()+" fixed fields, REGEX and persisted TSV agree with independent DOM expectations.");
    }
}
