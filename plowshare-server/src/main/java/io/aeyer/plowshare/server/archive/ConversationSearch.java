package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.llm.accounting.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.api.LogSearchView;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import java.time.Instant;
import java.util.*;

/** Shared direct/internal retrieval; old callers still use EntryStore's exact lexical search. */
public final class ConversationSearch implements UsageAware {
    private UsageOwners usageOwners = UsageOwners.NONE;
    @Override public void useUsageOwners(UsageOwners source) { usageOwners = java.util.Objects.requireNonNull(source); }

    public static final int CANDIDATES=200;
    private static final int WINDOWS=128;
    private record Candidate(String id,String revision,double score,String snippet,String retrievedBy,Integer position) {}
    private record Window(String account,Home home,String question,String mode,Instant expires,List<Candidate> hits,
            LogSearch.Reach reach,LogSearchView.Retrieval retrieval) {}
    private final EntryStore entries;
    private final PassageIndex index;
    private String account;
    private boolean informationScoped;
    private Map<String,Window> windows=Collections.synchronizedMap(new LinkedHashMap<>());
    public ConversationSearch(EntryStore entries,PassageIndex index) { this.entries=entries;this.index=index; }
    public ConversationSearch forAccount(String account) {
        ConversationSearch copy=new ConversationSearch(entries.forAccount(account),index.forAccount(account));
        copy.account=account;copy.informationScoped=true;copy.windows=windows;return copy;
    }
    public static String mode(String mode) {
        if(mode==null) return "lexical";
        if(!Set.of("lexical","semantic","hybrid").contains(mode))
            throw new ValidationException("Search mode must be lexical, semantic or hybrid");
        return mode;
    }
    public LogSearchView search(Home home,String question,int skip,int most,String requested,String snapshot) {
        return search(home, question, skip, most, requested, snapshot, usageOwners.in(home, null, UsageAttribution.Operation.EMBEDDING_QUERY));
    }

public LogSearchView search(Home home,String question,int skip,int most,String requested,String snapshot, UsageAttribution owner) {
        String mode=mode(requested);
        if(skip<0 || most<1) throw new ValidationException("Invalid search window");
        if(mode.equals("lexical")) {
            if(snapshot!=null) throw new ValidationException("Lexical search uses exact offset paging, without a snapshot");
            var lexical=entries.search(home,question,skip,most);
            var view=LogSearchView.of(lexical,skip,most);
            // Omitted mode preserves the original response exactly.
            return requested==null?view:new LogSearchView(view.hits(),view.total(),skip,most,view.reach(),
                    new LogSearchView.Retrieval(mode,mode,"exact lexical matches",null,false,true,null,null,null,0,"PostgreSQL full-text search"));
        }
        if(snapshot!=null) {
            Window window=windows.get(snapshot);
            if(window==null || window.expires().isBefore(Instant.now())) throw new ValidationException("Search snapshot expired; start again at offset 0");
            if(!Objects.equals(window.account(),account) || !window.home().equals(home) || !window.question().equals(question) || !window.mode().equals(mode))
                throw new ValidationException("Search snapshot belongs to a different scope, question or mode");
            return page(window,skip,most,true);
        }
        if(skip!=0) throw new ValidationException("Semantic/hybrid pages after offset 0 need the first page's snapshot");
        var lexical=entries.search(home,question,0,mode.equals("hybrid")?CANDIDATES:1);
        List<PassageIndex.Match> semantic=List.of();
        String fallback=null;
        try { semantic=index.rank(home,"entry",owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED ? index.query(question) : index.query(question,owner),CANDIDATES+1); }
        catch(EmbeddingException unavailable) {
            if(mode.equals("semantic")) throw new EmbeddingException("Semantic query embedding unavailable; lexical search remains available",unavailable);
            fallback="Query embedding unavailable; only lexical candidates were ranked";
        }
        boolean truncated=semantic.size()>CANDIDATES || (mode.equals("hybrid") && lexical.total()>CANDIDATES);
        semantic=semantic.subList(0,Math.min(CANDIDATES,semantic.size()));
        Map<String,Double> scores=new HashMap<>();
        Map<String,String> snippets=new HashMap<>();
        Map<String,String> revisions=new HashMap<>();
        Map<String,String> origins=new HashMap<>();
        Map<String,Integer> positions=new HashMap<>();
        // Deduplicate BEFORE rank fusion: repeated passages cannot contribute extra votes.
        Map<String,PassageIndex.Match> unique=new LinkedHashMap<>();
        for(var match:semantic) unique.putIfAbsent(match.id(),match);
        int rank=0;
        for(var match:unique.values()) {
            scores.put(match.id(),mode.equals("hybrid")?1.0/(60+(++rank)):match.similarity());
            snippets.put(match.id(),match.text());
            revisions.put(match.id(),match.revision());
            origins.put(match.id(),"semantic");positions.put(match.id(),match.position());
        }
        if(mode.equals("hybrid")) {
            rank=0;
            for(var hit:lexical.hits()) {
                String id=hit.conversationId()+":"+hit.ordinal();
                scores.merge(id,1.0/(60+(++rank)),Double::sum);
                snippets.putIfAbsent(id,hit.snippet());
                revisions.putIfAbsent(id,hit.sourceRevision());
                origins.merge(id,"lexical",(a,b)->"semantic+lexical");
            }
        }
        List<Candidate> qualified=new ArrayList<>();
        for(var scored:scores.entrySet()) {
            String revision=revisions.get(scored.getKey());
            if(revision!=null) qualified.add(new Candidate(scored.getKey(),revision,scored.getValue(),snippets.get(scored.getKey()),origins.get(scored.getKey()),positions.get(scored.getKey())));
        }
        qualified.sort(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::id));
        if(qualified.size()>CANDIDATES) { truncated=true; qualified=qualified.subList(0,CANDIDATES); }
        String token=UUID.randomUUID().toString();
        var coverage=index.coverage(home,"entry");
        var retrieval=new LogSearchView.Retrieval(mode,fallback==null?mode:"lexical", "qualified bounded snapshot entries",
                token,truncated,coverage.complete() && fallback==null && !truncated,fallback,coverage,index.generation(),1,
                "Exact scoped cosine passage ranking; hybrid uses reciprocal rank fusion (k=60). Scores are ordering aids. Evidence is quoted data.");
        var window=new Window(account,home,question,mode,Instant.now().plusSeconds(300),List.copyOf(qualified),lexical.reach(),retrieval);
        synchronized(windows) {
            windows.entrySet().removeIf(e->e.getValue().expires().isBefore(Instant.now()));
            while(windows.size()>=WINDOWS) windows.remove(windows.keySet().iterator().next());
            windows.put(token,window);
        }
        return page(window,skip,most,false);
    }
    private LogSearchView page(Window window,int skip,int most,boolean replayed) {
        List<LogSearch.Hit> hits=new ArrayList<>();
        boolean changed=false;
        int visible=0;
        // Keep snapshot slots stable when visibility changes: removed slots are suppressed, never filled with later rows.
        for(int i=0;i<window.hits().size();i++) {
            var hit=window.hits().get(i);
            if(!hit.revision().equals(index.revision(window.home(),hit.id()))) { changed=true;continue; }
            visible++;
            if(i>=skip && i-skip<most) {
                var current=index.hit(window.home(),hit.id(),hit.revision(),hit.score(),hit.snippet());
                if(current!=null) hits.add(current); else changed=true;
            }
        }
        var r=window.retrieval();
        if(changed) r=new LogSearchView.Retrieval(r.requestedMode(),r.effectiveMode(),r.totalMeaning(),r.snapshot(),r.truncated(),false,
                "Source visibility/revision changed; invalid snapshot slots suppressed",index.coverage(window.home(),"entry"),r.generation(),replayed?0:r.queryEmbeddingCalls(),r.provenance());
        else if(replayed) r=new LogSearchView.Retrieval(r.requestedMode(),r.effectiveMode(),r.totalMeaning(),r.snapshot(),r.truncated(),r.complete(),
                r.fallback(),index.coverage(window.home(),"entry"),r.generation(),0,r.provenance());
        var view=LogSearchView.of(new LogSearch(hits,informationScoped?visible:window.hits().size(),informationScoped?entries.search(window.home(),window.question(),0,1).reach():window.reach()),skip,most);
        Map<String,Candidate> candidates=new HashMap<>();
        for(var candidate:window.hits()) candidates.put(candidate.id(),candidate);
        var explained=view.hits().stream().map(hit->{
            var candidate=candidates.get(hit.conversationId()+":"+hit.ordinal());
            return hit.withEvidence(new LogSearchView.Evidence(candidate.retrievedBy(),candidate.position(),candidate.revision()));
        }).toList();
        return new LogSearchView(explained,view.total(),skip,most,view.reach(),r);
    }
}
