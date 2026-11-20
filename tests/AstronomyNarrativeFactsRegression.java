import com.cielcompanion.astronomy.AstronomyNarrativeFacts;
import java.time.LocalDate;
public class AstronomyNarrativeFactsRegression {
    public static void main(String[] args) {
        var today=LocalDate.of(2026,11,19);
        if (Math.abs(AstronomyNarrativeFacts.illuminationFromElongation(0)) > .0001
                || Math.abs(AstronomyNarrativeFacts.illuminationFromElongation(90) - .5) > .0001
                || Math.abs(AstronomyNarrativeFacts.illuminationFromElongation(180) - 1) > .0001)
            throw new AssertionError("new/quarter/full geometric illumination");
        if (!AstronomyNarrativeFacts.moonIllumination(AstronomyNarrativeFacts.illuminationFromElongation(120)).contains("75 percent"))
            throw new AssertionError("gibbous cannot become two percent from raw fraction");
        if (!AstronomyNarrativeFacts.meteorWindow("Geminids",LocalDate.of(2026,12,11),today).isEmpty()) throw new AssertionError("future shower called active");
        if (AstronomyNarrativeFacts.meteorWindow("Leonids",today,today).isEmpty()) throw new AssertionError("current window");
        for(String key:new String[]{"constellation_0","constellation_anchors","deep_sky_1","planet_2","special_alignment","uranus_note"})
            if(!AstronomyNarrativeFacts.isCurrentVisualKey(key)) throw new AssertionError(key);
        if(AstronomyNarrativeFacts.isCurrentVisualKey("upcoming_sky_0")) throw new AssertionError("future forecast removed");
        if(AstronomyNarrativeFacts.clearViewing(true,0,10,30)
                || AstronomyNarrativeFacts.clearViewing(false,70,10,30)
                || AstronomyNarrativeFacts.clearViewing(false,-1,10,30)
                || AstronomyNarrativeFacts.clearViewing(false,0,Double.NaN,30)) throw new AssertionError("bad conditions");
        if(!AstronomyNarrativeFacts.clearViewing(false,10,10,30)) throw new AssertionError("clear conditions");
        System.out.println("Astronomy narrative facts regression passed");
    }
}
