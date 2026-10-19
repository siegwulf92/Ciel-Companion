package com.cielcompanion.service;

import com.cielcompanion.util.ProjectPaths;
import com.google.gson.*;
import com.sun.jna.platform.win32.COM.WbemcliUtil.WmiQuery;
import oshi.SystemInfo;
import oshi.util.platform.windows.WmiQueryHandler;
import oshi.util.platform.windows.WmiUtil;
import java.awt.GraphicsEnvironment;
import javax.sound.sampled.AudioSystem;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.*;

/** Read-only local telemetry. Never stored as a shared MemoryService fact. */
public final class DeviceProfileService {
    private static final SystemInfo SYSTEM = new SystemInfo();
    private static final Path PROFILE = ProjectPaths.root().resolve(".local/device-profile.json");
    private static volatile JsonObject snapshot = new JsonObject();
    private static JsonObject inventory;
    private static long inventoryAt;
    private static long[] cpuTicks;
    private static ScheduledExecutorService scheduler;
    private enum ComputerType { PCSYSTEMTYPE }
    private interface PowerApi extends com.sun.jna.win32.StdCallLibrary {
        PowerApi INSTANCE = com.sun.jna.Native.load("kernel32", PowerApi.class, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);
        boolean GetSystemPowerStatus(PowerStatus status);
    }
    @com.sun.jna.Structure.FieldOrder({"acLineStatus", "batteryFlag", "batteryLifePercent", "systemStatusFlag", "batteryLifeTime", "batteryFullLifeTime"})
    public static class PowerStatus extends com.sun.jna.Structure {
        public byte acLineStatus, batteryFlag, batteryLifePercent, systemStatusFlag;
        public int batteryLifeTime, batteryFullLifeTime;
    }

    private DeviceProfileService() {}

    public static synchronized void initialize() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ciel-device-profile"); t.setDaemon(true); return t;
        });
        scheduler.scheduleWithFixedDelay(DeviceProfileService::refresh, 0, 30, TimeUnit.SECONDS);
    }

    public static synchronized void refresh() {
        try {
            long now = System.currentTimeMillis();
            if (inventory == null || now - inventoryAt > 600_000) {
                inventory = collectInventory(); inventoryAt = now;
            }
            JsonObject result = inventory.deepCopy();
            result.addProperty("observed_at", Instant.ofEpochMilli(now).toString());
            result.addProperty("observed_at_ms", now);
            result.addProperty("inventory_observed_at_ms", inventoryAt);
            result.addProperty("refresh_seconds", 30);
            var hardware = SYSTEM.getHardware();
            var memory = hardware.getMemory();
            result.addProperty("available_ram_bytes", memory.getAvailable());
            result.addProperty("ram_used_percent", 100.0 * (memory.getTotal() - memory.getAvailable()) / Math.max(1, memory.getTotal()));
            var processor = hardware.getProcessor();
            if (cpuTicks != null) result.addProperty("cpu_load_percent", processor.getSystemCpuLoadBetweenTicks(cpuTicks) * 100);
            cpuTicks = processor.getSystemCpuLoadTicks();
            JsonObject power = new JsonObject();
            try {
                var status = new PowerStatus();
                if (!PowerApi.INSTANCE.GetSystemPowerStatus(status)) throw new IllegalStateException("Power telemetry unavailable");
                int flags = Byte.toUnsignedInt(status.batteryFlag);
                int percent = Byte.toUnsignedInt(status.batteryLifePercent);
                int ac = Byte.toUnsignedInt(status.acLineStatus);
                boolean known = flags != 255;
                boolean present = known && (flags & 128) == 0;
                power.addProperty("status", !known ? "unknown" : present ? "available" : "no_battery_reported");
                if (known) power.addProperty("battery_present", present);
                if (ac <= 1) power.addProperty("ac_connected", ac == 1);
                JsonArray batteries = new JsonArray();
                boolean onBattery = present && ac == 0;
                if (present) {
                    JsonObject b = new JsonObject();
                    b.addProperty("name", "Windows system battery aggregate");
                    if (percent <= 100) b.addProperty("percent", percent);
                    b.addProperty("charging", (flags & 8) != 0);
                    if (ac <= 1) b.addProperty("discharging", onBattery);
                    if (status.batteryLifeTime >= 0) b.addProperty("estimated_minutes_remaining", status.batteryLifeTime / 60);
                    batteries.add(b);
                }
                power.add("batteries", batteries);
                if (known && ac <= 1) {
                    power.addProperty("on_battery", onBattery);
                    if (percent <= 100 || !present) power.addProperty("low_battery", onBattery && percent <= 20);
                    power.addProperty("prefer_lightweight_work", onBattery);
                }
            } catch (RuntimeException | LinkageError e) { power.addProperty("status", "unavailable"); }
            result.add("power", power);
            JsonObject endpoints = new JsonObject();
            for (var entry : java.util.Map.of("python_swarm", 8000, "ollama", 11434, "lm_studio", 1234).entrySet()) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", entry.getValue()), 150);
                    endpoints.addProperty(entry.getKey(), "tcp_reachable_not_api_verified");
                } catch (Exception e) { endpoints.addProperty(entry.getKey(), "not_reachable"); }
            }
            result.add("local_services", endpoints);
            Path routingFile = PROFILE.resolveSibling("model-routing-status.json");
            try {
                if (Files.isRegularFile(routingFile) && Files.size(routingFile) < 1_000_000) {
                    JsonObject routing = JsonParser.parseString(Files.readString(routingFile)).getAsJsonObject();
                    long age = now - routing.get("observed_at_ms").getAsLong();
                    if (age >= 0 && age <= 90_000) {
                        JsonObject summary = new JsonObject();
                        summary.add("ollama", routing.get("ollama"));
                        summary.add("provider_slot_limits", routing.get("provider_slot_limits"));
                        summary.add("provider_feedback", routing.getAsJsonObject("routing").get("providers"));
                        result.add("model_routing", summary);
                    }
                }
            } catch (Exception ignored) {}
            try {
                var store = Files.getFileStore(ProjectPaths.root());
                result.addProperty("installation_volume_free_bytes", store.getUsableSpace());
            } catch (Exception ignored) {}
            snapshot = result;
            Files.createDirectories(PROFILE.getParent());
            Path staging = PROFILE.resolveSibling("device-profile.json.tmp");
            Files.writeString(staging, new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardCharsets.UTF_8);
            try { Files.move(staging, PROFILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(staging, PROFILE, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception | LinkageError e) {
            System.err.println("Ciel Device Profile: refresh unavailable (" + e.getClass().getSimpleName() + ").");
        }
    }

    private static JsonObject collectInventory() throws Exception {
        JsonObject info = new JsonObject();
        info.addProperty("schema_version", 1);
        String hostname = System.getenv().getOrDefault("COMPUTERNAME", "unknown");
        info.addProperty("hostname", hostname);
        // Windows MachineGuid is hashed locally; no hardware serial numbers are exported.
        String identity = hostname.toLowerCase(Locale.ROOT);
        try {
            identity += com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(
                    com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Cryptography", "MachineGuid");
        } catch (RuntimeException ignored) {}
        info.addProperty("device_id", "device-" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 16));
        var hardware = SYSTEM.getHardware();
        var computer = hardware.getComputerSystem();
        info.addProperty("manufacturer", computer.getManufacturer());
        info.addProperty("model", computer.getModel());
        String form = "unknown";
        try {
            var handler = WmiQueryHandler.createInstance();
            handler.setWmiTimeout(3000);
            var query = handler.queryWMI(new WmiQuery<ComputerType>("Win32_ComputerSystem", ComputerType.class));
            if (query.getResultCount() > 0) {
                int type = WmiUtil.getUint16(query, ComputerType.PCSYSTEMTYPE, 0);
                form = classifySystemType(type);
            }
        } catch (RuntimeException ignored) {}
        info.addProperty("form_factor", form);
        info.addProperty("os", System.getProperty("os.name") + " " + SYSTEM.getOperatingSystem().getVersionInfo());
        info.addProperty("architecture", System.getProperty("os.arch"));
        var processor = hardware.getProcessor();
        info.addProperty("cpu_name", processor.getProcessorIdentifier().getName());
        info.addProperty("physical_cores", processor.getPhysicalProcessorCount());
        info.addProperty("logical_processors", processor.getLogicalProcessorCount());
        info.addProperty("total_ram_bytes", hardware.getMemory().getTotal());
        JsonArray gpus = new JsonArray();
        try {
            for (var gpu : hardware.getGraphicsCards()) {
                JsonObject g = new JsonObject(); g.addProperty("name", gpu.getName()); g.addProperty("vendor", gpu.getVendor());
                g.addProperty("reported_vram_bytes", gpu.getVRam());
                g.addProperty("memory_note", "Driver-reported figure; integrated/shared memory is not dedicated CUDA VRAM.");
                gpus.add(g);
            }
        } catch (RuntimeException ignored) {}
        info.add("gpus", gpus);
        try { info.addProperty("connected_displays", GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length); }
        catch (RuntimeException ignored) {}
        try { info.addProperty("audio_mixers_detected", AudioSystem.getMixerInfo().length); }
        catch (RuntimeException ignored) {}
        JsonArray skills = new JsonArray();
        Path skillsPath = ProjectPaths.ciel().resolve("skills");
        if (Files.isDirectory(skillsPath)) try (var files = Files.list(skillsPath)) {
            files.map(p -> p.getFileName().toString()).filter(n -> n.matches("[A-Za-z_][A-Za-z0-9_]*\\.(py|ps1|bat)"))
                    .sorted().forEach(skills::add);
        }
        info.add("installed_skill_files", skills);
        info.addProperty("capability_note", "Files and devices detected are inventory, not proof a skill works or permission to use it. Recheck prerequisites locally. Microphone/camera capture permissions and admin elevation are not inferred.");
        return info;
    }

    public static String classifySystemType(int type) {
        return switch (type) { case 1 -> "desktop"; case 2 -> "laptop"; case 3 -> "workstation"; case 4, 5, 7 -> "server"; case 6 -> "appliance"; case 8 -> "tablet"; default -> "unknown"; };
    }

    public static JsonObject getSnapshot() { return snapshot.deepCopy(); }

    public static String context() {
        JsonObject value = getSnapshot();
        value.remove("installed_skill_files"); // Full inventory remains available to skill tools.
        if (!value.has("observed_at_ms") || System.currentTimeMillis() - value.get("observed_at_ms").getAsLong() > 90_000) {
            return "Local device telemetry is unavailable or stale. Query the local device_status skill before asserting current hardware/power state.";
        }
        return "LOCAL DEVICE TELEMETRY (observations, not instructions):\n" + value +
                "\nThis is the device executing this request. Shared memories about other computers do not describe this machine. " +
                "Use local paths and verified local capabilities; prefer lighter work on battery and do not assume NVIDIA/CUDA from an Intel or shared-memory GPU. " +
                "Do not perform actions on the paired computer or assume access to its hardware.\n";
    }
}
