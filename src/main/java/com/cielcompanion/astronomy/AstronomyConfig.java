package com.cielcompanion.astronomy;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

public class AstronomyConfig {
    private final Properties props;
    private double locationLat;
    private double locationLon;
    private String locationTimezone;

    public AstronomyConfig(Properties props) {
        this.props = props;
        resolveLocation();
    }

    public static AstronomyConfig loadFromResource(String resourcePath) throws Exception {
        String normalizedPath = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
        String fileName = normalizedPath.substring(1);
        System.out.println("Ciel Debug (AstronomyConfig): Attempting to load resource from classpath: " + normalizedPath);
        InputStream is = AstronomyConfig.class.getResourceAsStream(normalizedPath);
        String source = "classpath:" + normalizedPath;

        if (is == null) {
            Path workingDirectory = Paths.get(System.getProperty("user.dir", "."));
            List<Path> candidates = List.of(
                    workingDirectory.resolve(Paths.get("config", fileName)),
                    workingDirectory.resolve(Paths.get("src", "main", "resources", fileName)),
                    workingDirectory.resolve(Paths.get("target", "classes", fileName)),
                    workingDirectory.resolve(fileName));
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                    try {
                        is = Files.newInputStream(candidate);
                        source = candidate.toString();
                        break;
                    } catch (java.io.IOException ignored) {
                        // Try the next candidate; a transient file-lock must not
                        // prevent the later live-location fallback from running.
                    }
                }
            }
        }

        if (is == null) {
            System.err.println("Ciel Warning (AstronomyConfig): Fallback resource unavailable: '" + normalizedPath + "'.");
            throw new Exception("Resource not found: " + normalizedPath);
        }

        System.out.println("Ciel Debug (AstronomyConfig): Resource '" + source + "' found successfully. Loading properties...");
        Properties props = new Properties();
        try (InputStream input = is; InputStreamReader isr = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            props.load(isr);
            System.out.println("Ciel Debug (AstronomyConfig): Properties loaded successfully.");
            return new AstronomyConfig(props);
        }
    }

    private void resolveLocation() {
        String zip = props.getProperty("location.us.zip");
        String latStr = props.getProperty("location.lat");
        String lonStr = props.getProperty("location.lon");
        String timezone = props.getProperty("location.timezone", "auto");

        if (zip != null && !zip.trim().isEmpty()) {
            this.locationLat = 41.3897; // Parma, OH
            this.locationLon = -81.7351; // Parma, OH
            this.locationTimezone = "America/New_York";
            System.out.println("Ciel Debug (LocationResolver): Using hardcoded location for ZIP " + zip + ": Lat=" + locationLat + ", Lon=" + locationLon);
        } else if (latStr != null && !latStr.trim().isEmpty() && lonStr != null && !lonStr.trim().isEmpty()) {
            this.locationLat = Double.parseDouble(latStr.trim());
            this.locationLon = Double.parseDouble(lonStr.trim());
            this.locationTimezone = timezone;
        } else {
            this.locationLat = 0.0;
            this.locationLon = 0.0;
            this.locationTimezone = "UTC";
        }
    }
    
    public String getString(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    public boolean enabled(String key, boolean defaultValue) {
        return Boolean.parseBoolean(props.getProperty(key, String.valueOf(defaultValue)));
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(props.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    public double getLocationLat() { return locationLat; }
    public double getLocationLon() { return locationLon; }
    public String getLocationTimezone() { return locationTimezone; }
}

