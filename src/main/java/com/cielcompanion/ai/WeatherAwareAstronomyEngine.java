package com.cielcompanion.ai;
import com.cielcompanion.service.AstronomyService.AstronomyReport;
import com.cielcompanion.service.WeatherService;
import com.cielcompanion.astronomy.AstronomyNarrativeFacts;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Weather visibility is measured policy, never a language-model inference. */
public class WeatherAwareAstronomyEngine {
    public static void processReport(AstronomyReport raw, Consumer<AstronomyReport> complete) {
        WeatherService.SkyConditions sky = WeatherService.getCurrentSkyConditions();
        complete.accept(filterForConditions(raw, sky, Instant.now().getEpochSecond()));
    }
    public static AstronomyReport filterForConditions(AstronomyReport raw, WeatherService.SkyConditions sky, long epoch) {
        long age = sky == null ? -1 : epoch - sky.fetchedAtEpochSeconds();
        boolean fresh = age >= 0 && age <= 900;
        boolean clear = fresh && AstronomyNarrativeFacts.clearViewing(
                sky.daytime(), sky.cloudCoverPercent(), sky.visibilityKm(),
                Integer.getInteger("ciel.astronomy.maxClearCloudPercent", 30));
        if (clear) return raw;
        var events = new LinkedHashMap<>(raw.sequentialEvents());
        events.keySet().removeIf(AstronomyNarrativeFacts::isCurrentVisualKey);
        var ambient = new ArrayList<String>();
        String reason = !fresh ? "Current weather visibility is unconfirmed."
                : sky.daytime() ? "Daylight prevents a dependable view of stars and most planets right now."
                : "Cloud or visibility conditions do not support a dependable skywatching report right now.";
        events.put("visual_cancellation", reason);
        System.out.println("[Astronomy Visibility] current_visuals=withheld reason=" + reason);
        return new AstronomyReport(events, ambient, raw.idleAmbientLines());
    }
}
