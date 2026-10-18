import com.cielcompanion.service.ViewingProgress;
import com.google.gson.JsonObject;

public class ViewingProgressRegression {
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        String candidate="SERIES: Example\nEPISODE: Episode Seven\nPLAYBACK_IDENTITY_STATUS: OBSERVED\nMETADATA_STATUS: PENDING\n";
        check(ViewingProgress.identifiable(candidate),"Missing synopsis blocked viewing history");
        check(!ViewingProgress.identifiable(candidate.replace("OBSERVED","UNCONFIRMED")),"Unconfirmed identity counted");
        check(!ViewingProgress.identifiable("--- ON-SCREEN UI TEXT ---\n"+candidate),"Raw UI forged typed identity");
        JsonObject started=ViewingProgress.update(null,"Example","Episode Seven",300,24,1000);
        check(!started.get("near_end_observed").getAsBoolean(),"Five minutes counted as completion");
        JsonObject late=ViewingProgress.update(started,"Example","Episode Seven",1400,24,2000);
        check(late.get("near_end_observed").getAsBoolean(),"Near end lost");
        JsonObject rewind=ViewingProgress.update(late,"Example","Episode Seven",60,24,3000);
        check(rewind.get("max_position_seconds").getAsInt()==1400,"Rewind erased history");
        check(rewind.get("near_end_observed").getAsBoolean(),"Rewatch erased previous near-end evidence");
        check(!ViewingProgress.update(null,"Example","New",500,-1,4000).get("near_end_observed").getAsBoolean(),"Unknown runtime fabricated completion");
        System.out.println("Viewing progress regressions passed: independent identity, browsing isolation, partial/near-end distinction, monotonic history.");
    }
}
