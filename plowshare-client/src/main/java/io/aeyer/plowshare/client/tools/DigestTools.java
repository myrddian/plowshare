package io.aeyer.plowshare.client.tools;

import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DigestTools {
    private final ServerClient server;
    public DigestTools(ServerClient server) { this.server=server; }
    public void registerOn(ToolRegistry registry) {
        Map<String,Object> fields=new LinkedHashMap<>();
        fields.put("project",Schemas.string("Project name; omit for global memories"));
        fields.put("question",Schemas.string("What you want to remember"));
        registry.register("memory_navigate","Navigate memory digests to a lesson, original log, or surviving summary. "
                +"Runs an ephemeral navigator with its own system allowance; may take time. "
                +"The answer identifies its sources, level, and incomplete navigation. Use memory_recall for a shallow search.",
                Schemas.object(fields,List.of("question")),this::navigate);
        registry.register("memory_digest","Build or refresh a home's memory digest tree. Returns a job id; "
                +"use agent_poll and agent_result. Folds also trigger this work automatically.",
                Schemas.object(Map.of("project",Schemas.string("Project; omit for global")),List.of()),this::digest);
    }
    public Object navigate(Map<String,Object> args) {
        String question=text(args,"question");
        if(question==null || question.isBlank()) throw new IllegalArgumentException("question is required");
        try {
            var result=server.navigateMemory(text(args,"project"),question);
            return "Reached "+result.level()+"; complete="+result.complete()+"; ids="+result.ids()+"\n> "
                    +result.text().replace("\n","\n> ");
        } catch(IOException failed) { throw new IllegalStateException("Memory navigation could not reach the server",failed); }
    }
    public Object digest(Map<String,Object> args) {
        try { return "Started digest job "+server.digestMemory(text(args,"project")).id()+". Poll with agent_poll."; }
        catch(IOException failed) { throw new IllegalStateException("Digest pass could not reach the server",failed); }
    }
    private static String text(Map<String,Object> args,String key) {
        Object value=args.get(key);
        if(value==null) return null;
        if(!(value instanceof String text)) throw new IllegalArgumentException(key+" must be text");
        return text;
    }
}
