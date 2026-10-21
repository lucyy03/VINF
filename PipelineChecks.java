import java.nio.file.*;
import java.util.*;

/** Entire saved-pages -> extraction -> album index pipeline, including navigation pages. */
public class PipelineChecks {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("target"));
        Path work = Files.createTempDirectory(Path.of("target"), "pipeline-check-");
        Path run = Files.createDirectories(work.resolve("run/html")).getParent();
        Path source = Path.of("crawler-output/2026-10-10_23-08-15_7424884074887970765");
        String artist = "https://musicbrainz.org/artist/a74b1b7f-71a5-4011-9441-d0b5e4122711";
        var all = MusicBrainzIndex.readTsv(source.resolve("pages.tsv"), "url", "html_file");
        var group = all.get(0); var edition = all.get(8);
        var artistRow = all.stream().filter(r -> r.get("url").equals(artist)).findFirst().orElseThrow();
        List<Map<String,String>> selected = List.of(artistRow, group, edition, artistRow);
        StringBuilder meta = new StringBuilder("id\turl\tdepth\tstatus\tcontent_type\thtml_file\n");
        for (int i=0;i<selected.size();i++) {
            String name = "html/"+(i+1)+".html"; var row=selected.get(i);
            Files.copy(source.resolve(row.get("html_file")), run.resolve(name));
            meta.append(i+1).append('\t').append(row.get("url")).append(i==3?"?page=2":"")
                .append("\t0\t200\ttext/html\t").append(name).append('\n');
        }
        Files.writeString(run.resolve("pages.tsv"),meta);
        Path extracted=work.resolve("extracted");
        MusicBrainzRegexExtractor.extractRun(run,extracted);
        check(Files.readAllLines(extracted.resolve("entities.tsv")).size()==4,"Four pages represent three entities");
        check(Files.readString(extracted.resolve("extraction_audit.tsv")).contains("PAGINATION"),"Navigation audited separately");
        var stats=CrawlStatistics.writeReport(run,work.resolve("stats"));
        check(stats.get("unique_entities")==3 && stats.get("pagination_documents")==1,"Separate page/entity statistics");
        Path output=work.resolve("index");var index=MusicBrainzIndexer.buildAlbums(extracted,output);
        check(index.documents.size()==1,"One album from four source pages");
        check(index.search("Radiohead Airbag Parlophone",MusicBrainzIndex.Method.SMOOTH,true,5).size()==1,"Joined edition fields searchable");
        var albums=MusicBrainzIndex.readTsv(output.resolve("albums.tsv"),"first_release_date","date_basis","editions");
        check(albums.get(0).get("first_release_date").equals("1997") && albums.get(0).get("date_basis").equals("artist_catalogue"),"First-release year comes from artist catalogue");
        Path queries=work.resolve("queries.tsv"),qrels=work.resolve("qrels.tsv"),report=work.resolve("evaluation.tsv");
        Files.writeString(queries,"query_id\tquery\npositive\tRadiohead Airbag\nnegative\tzzzneexistujuci\n");
        Files.writeString(qrels,"query_id\tsource_url\npositive\t"+group.get("url")+"\n");
        SearchEvaluation.main(new String[]{output.toString(),queries.toString(),qrels.toString(),report.toString(),"5"});
        var metrics=MusicBrainzIndex.readTsv(report,"query_id","retrieved","precision_at_k","recall_at_k","rr_at_k");
        for(var row:metrics) if(row.get("query_id").equals("positive")) {
            check(row.get("precision_at_k").equals("0.2") && row.get("recall_at_k").equals("1.0") && row.get("rr_at_k").equals("1.0"),"Hand-computed evaluation metrics");
        } else check(row.get("retrieved").equals("0") && row.get("recall_at_k").isEmpty(),"Negative query and undefined recall");
        System.out.println("Pipeline checks passed: paginated HTML, first date, one album, edition joins and evaluation metrics.");
    }
}
