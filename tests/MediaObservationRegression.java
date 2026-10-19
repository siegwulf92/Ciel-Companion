import com.cielcompanion.service.MediaObservation;

public class MediaObservationRegression {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(MediaObservation.verified("MEDIA_KIND: VIDEO\nMETADATA_STATUS: PUBLISHER\nMETADATA_SOURCES: youtube_publisher\nMEDIA_TITLE: Published video\nDESCRIPTION: Publisher-provided context\n"), "Publisher metadata not recognized");
        String verified = "MEDIA_KIND: EPISODE\nSERIES: The Ghost in the Shell\nEPISODE: PHANTOM FUND\nMETADATA_STATUS: VERIFIED\nDESCRIPTION: supported\n";
        String browsing = "SERIES: Unknown\nEPISODE: \nMEDIA_KIND: UNKNOWN\nMETADATA_STATUS: BROWSING\nDURATION_MINUTES: 24\n";
        for (boolean update : new boolean[]{false, true}) {
            String merged = MediaObservation.merge(verified, browsing, update);
            check(MediaObservation.verified(merged), "Browsing must preserve verified identity");
            check(MediaObservation.field(merged, "EPISODE").equals("PHANTOM FUND"), "Episode lost");
            check(MediaObservation.field(merged, "DURATION_MINUTES").equals("24"), "Telemetry lost");
        }
        String next = verified.replace("PHANTOM FUND", "NEXT EPISODE");
        check(MediaObservation.field(MediaObservation.merge(verified, next, true), "EPISODE").equals("NEXT EPISODE"), "Verified next episode not accepted");
        check(MediaObservation.field(browsing, "EPISODE").isEmpty(), "Empty episode consumed next line");
        check(!MediaObservation.verified("--- ON-SCREEN UI TEXT ---\n" + verified), "Raw UI became metadata");
        check(MediaObservation.merge(verified, "NO_CHANGE", false).equals(verified), "NO_CHANGE erased metadata");
        check(!MediaObservation.freshPlayback("PAUSED", 1000, 2000), "Pause counted as viewing");
        check(!MediaObservation.freshPlayback("UNCONFIRMED", 1000, 17000), "Stale playback counted");
        check(MediaObservation.freshPlayback("PLAYING", 1000, 2000), "Fresh playback rejected");
        check(MediaObservation.commentaryTargetSeconds(false,24)==300,"Short episode not five minutes");
        check(MediaObservation.commentaryTargetSeconds(false,-1)==300,"Unknown episode must default to five minutes");
        check(MediaObservation.commentaryTargetSeconds(false,120)==900,"Long episode exceeded fifteen minutes");
        check(MediaObservation.commentaryTargetSeconds(true,120)==7020,"Film must wait until last three minutes");
        check(MediaObservation.commentaryTargetSeconds(true,-1)==-1,"Unknown film must not trigger early");
        check(!MediaObservation.commentaryTimeReached(120,20,300),"Wall time overrode visible playback position");
        check(MediaObservation.commentaryTimeReached(300,0,300),"Player threshold not honored");
        check(MediaObservation.commentaryTimeReached(-1,5,300),"Confirmed playback estimate unavailable as fallback");
        check(MediaObservation.commentaryTargetSeconds(false,60)==540,"Hour-long episode must target nine minutes");
        for (int runtime=1;runtime<=360;runtime++) {
            for (boolean film:new boolean[]{false,true}) {
                int target=MediaObservation.commentaryTargetSeconds(film,runtime);
                check(target>0 && target<runtime*60,"Target reaches/passes the ending");
                if(runtime>3) check(target<=runtime*60-180,"Target later than final-three-minute boundary");
            }
        }
        check(!MediaObservation.commentaryTimeReached(3600,60,540,60),"Ended episode triggered commentary");
        System.out.println("MediaObservation regressions passed: identity retention, replacement, empty fields, UI isolation, playback freshness.");
    }
}
