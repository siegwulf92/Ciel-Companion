import com.cielcompanion.service.DeviceProfileService;

public class DeviceProfileRegression {
    public static void main(String[] args) throws Exception {
        if (!DeviceProfileService.classifySystemType(2).equals("laptop")) throw new AssertionError("Portable chassis classification");
        if (!DeviceProfileService.classifySystemType(1).equals("desktop")) throw new AssertionError("Desktop chassis classification");
        if (!DeviceProfileService.classifySystemType(0).equals("unknown")) throw new AssertionError("Missing telemetry must be unknown");
        DeviceProfileService.refresh();
        var profile = DeviceProfileService.getSnapshot();
        if (!profile.has("device_id") || !profile.has("power") || profile.get("total_ram_bytes").getAsLong() <= 0) {
            throw new AssertionError("Hardware profile not collected: " + profile);
        }
        if (!DeviceProfileService.context().contains(profile.get("hostname").getAsString())) throw new AssertionError("Missing identity in AI context");
        if (DeviceProfileService.context().contains("installed_skill_files")) throw new AssertionError("Full inventory bloats conversation context");
        System.out.println("Device awareness regression passed: " + profile.get("hostname") + " / " + profile.get("form_factor") + " / " + profile.get("power"));
    }
}
