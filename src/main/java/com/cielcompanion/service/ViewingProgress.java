package com.cielcompanion.service;

import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Playback history is independent of plot verification. It never proves unseen plot knowledge. */
public final class ViewingProgress {
    private ViewingProgress() {}
    private static String lastIdentity = "", lastStream = "";
    private static long identityAt;
    private static final Map<String,Long> lastWrites = new HashMap<>();
    private static final Map<String,JsonObject> prior = new HashMap<>();

    private static String key(String series,String episode) {
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest((series.trim().toLowerCase(Locale.ROOT)+"|"+episode.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            return "watch_episode_"+HexFormat.of().formatHex(hash);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private static String seriesKey(String series) { return key(series,"").replace("watch_episode_","watch_series_"); }

    public static boolean identifiable(String dom) {
        return ("OBSERVED".equals(MediaObservation.field(dom,"PLAYBACK_IDENTITY_STATUS")) || MediaObservation.verified(dom))
            && !MediaObservation.field(dom,"SERIES").isBlank()
            && !MediaObservation.field(dom,"SERIES").equalsIgnoreCase("Unknown")
            && !MediaObservation.field(dom,"EPISODE").isBlank();
    }

    public static JsonObject update(JsonObject previous,String series,String episode,int position,int runtime,long now) {
        JsonObject value=previous==null?new JsonObject():previous.deepCopy();
        value.addProperty("series",series);value.addProperty("episode",episode);
        if(!value.has("first_observed_at")) value.addProperty("first_observed_at",now);
        value.addProperty("last_observed_at",now);
        int maximum=previous!=null&&previous.has("max_position_seconds")?previous.get("max_position_seconds").getAsInt():0;
        value.addProperty("max_position_seconds",Math.max(maximum,position));
        if(runtime>0) value.addProperty("runtime_seconds",runtime*60);
        boolean nearEnd=previous!=null&&previous.has("near_end_observed")&&previous.get("near_end_observed").getAsBoolean();
        value.addProperty("near_end_observed",nearEnd || (runtime>0 && position>=runtime*54));
        value.addProperty("evidence","Observed player position; may include seeking. Not proof every scene was watched.");
        return value;
    }

    public static synchronized void observe(String dom,String state,int position,int runtime,long now) {
        try {
            String stream=MediaObservation.field(dom,"STREAM_LINK");
            if(identifiable(dom)) { lastIdentity=dom; lastStream=stream; identityAt=now; }
            else if(lastIdentity.isBlank() || stream.isBlank() || !stream.equals(lastStream) || now-identityAt>20*60_000) return;
            if(!"PLAYING".equals(state) || position<1) return;
            String series=MediaObservation.field(lastIdentity,"SERIES"),episode=MediaObservation.field(lastIdentity,"EPISODE");
            String key=key(series,episode);
            JsonObject previous=MemoryService.getFact(key).map(f -> JsonParser.parseString(f.value()).getAsJsonObject()).orElse(null);
            if(!prior.containsKey(key)) prior.put(key,previous==null?new JsonObject():previous.deepCopy());
            if(now-lastWrites.getOrDefault(key,0L)<60_000) return;
            JsonObject value=update(previous,series,episode,position,runtime,now);
            String season=MediaObservation.field(lastIdentity,"SEASON"),number=MediaObservation.field(lastIdentity,"EPISODE_NUMBER");
            if(season.matches("\\d+") && number.matches("\\d+")) {
                value.addProperty("season",Integer.parseInt(season));value.addProperty("episode_number",Integer.parseInt(number));
            }
            MemoryService.addFact(new Fact(key,value.toString(),now,"user_knowledge","viewing_progress",1));
            JsonObject recent=MemoryService.getFact(seriesKey(series)).map(f -> JsonParser.parseString(f.value()).getAsJsonObject()).orElse(new JsonObject());
            JsonObject entry=new JsonObject();
            entry.addProperty("episode",episode);entry.addProperty("last_observed_at",now);
            entry.addProperty("near_end_observed",value.get("near_end_observed").getAsBoolean());
            recent.add(key,entry);
            if(recent.size()>30) {
                String oldest=recent.entrySet().stream().min(java.util.Comparator.comparingLong(e -> e.getValue().getAsJsonObject().get("last_observed_at").getAsLong())).get().getKey();
                recent.remove(oldest); // Per-episode facts remain intact; only the prompt summary is bounded.
            }
            MemoryService.addFact(new Fact(seriesKey(series),recent.toString(),now,"user_knowledge","viewing_progress",1));
            lastWrites.put(key,now);
            System.out.println("[Viewing Progress] Saved observed episode '"+series+" - "+episode+"' at "+position+"s; plot verification independent; nearEnd="+value.get("near_end_observed"));
        } catch(Exception e) { System.err.println("[Viewing Progress] Save deferred: "+e.getClass().getSimpleName()); }
    }

    public static synchronized String context(String series,String episode,int position) {
        try {
            String key=key(series,episode);
            String recent=MemoryService.getFact(seriesKey(series)).map(f -> " Recent observed episodes (not proof of full viewing): "+f.value()).orElse("");
            JsonObject history=prior.containsKey(key)?prior.get(key):MemoryService.getFact(key).map(f -> JsonParser.parseString(f.value()).getAsJsonObject()).orElse(null);
            if(history==null || !history.has("max_position_seconds")) return "No prior exact-episode playback record. Do not assume a rewatch."+recent;
            int maximum=history.get("max_position_seconds").getAsInt();
            boolean nearEnd=history.has("near_end_observed")&&history.get("near_end_observed").getAsBoolean();
            return "Prior playback for this exact episode reached "+maximum+" seconds. Near-end previously observed: "+nearEnd
                +". Rewatch candidate: "+(nearEnd && position>=0 && position<maximum-120)
                +". This may include seeking and does not establish every scene or intermediate episode was watched. Keep current-playback spoiler limits."+recent;
        } catch(Exception e) { return "Prior playback history unavailable; do not assume a rewatch."; }
    }
}
