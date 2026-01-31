package com.cielcompanion.ui;

import java.awt.Color;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class GuiSettings {
    public enum CoreStyle { PULSING, STATIC, NONE }

    private static int GUI_SIZE;
    private static Color SPEAK_GLOW_COLOR;
    private static boolean VISIBLE_WHEN_IDLE;
    private static boolean MOVEMENT_ENABLED;
    private static double MOVEMENT_SPEED;
    private static int NEW_TARGET_DELAY_MS;
    private static CoreStyle CORE_STYLE;
    private static double BREATHING_SPEED;
    private static int BREATHING_SIZE_VARIATION;
    private static double SPEAKING_BREATH_SPEED;
    private static double SPEAKING_FLICKER_SPEED;
    private static double SPEAKING_FLICKER_INTENSITY;

    public static void initialize() {
        Properties props = new Properties();
        try (InputStream is = GuiSettings.class.getResourceAsStream("/gui_settings.properties")) {
            if (is == null) throw new RuntimeException("Resource not found: /gui_settings.properties");
            
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));

            GUI_SIZE = Integer.parseInt(props.getProperty("gui.size", "150"));
            SPEAK_GLOW_COLOR = loadColor(props, "gui.speak.glow.color", new Color(0.5f, 0.8f, 1.0f));

            VISIBLE_WHEN_IDLE = Boolean.parseBoolean(props.getProperty("display.visibleWhen.idle", "false"));
            MOVEMENT_ENABLED = Boolean.parseBoolean(props.getProperty("behavior.movement.enabled", "true"));
            MOVEMENT_SPEED = Double.parseDouble(props.getProperty("behavior.movement.speed", "1.0"));
            NEW_TARGET_DELAY_MS = Integer.parseInt(props.getProperty("behavior.movement.newTargetDelaySec", "15")) * 1000;
            CORE_STYLE = CoreStyle.valueOf(props.getProperty("behavior.core.style", "PULSING").toUpperCase());
            BREATHING_SPEED = Double.parseDouble(props.getProperty("behavior.breathing.speed", "0.5"));
            BREATHING_SIZE_VARIATION = Integer.parseInt(props.getProperty("behavior.breathing.sizeVariation", "10"));
            SPEAKING_BREATH_SPEED = Double.parseDouble(props.getProperty("behavior.speaking.breathSpeed", "1.5"));
            SPEAKING_FLICKER_SPEED = Double.parseDouble(props.getProperty("behavior.speaking.flickerSpeed", "10.0"));
            SPEAKING_FLICKER_INTENSITY = Double.parseDouble(props.getProperty("behavior.speaking.flickerIntensity", "0.8"));

            System.out.println("Ciel Debug: GUI settings loaded successfully.");
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to load GUI settings. Using defaults.");
            e.printStackTrace();
        }
    }
    
    private static Color loadColor(Properties props, String key, Color defaultColor) {
        String rStr = props.getProperty(key + ".r", String.valueOf(defaultColor.getRed() / 255f));
        String gStr = props.getProperty(key + ".g", String.valueOf(defaultColor.getGreen() / 255f));
        String bStr = props.getProperty(key + ".b", String.valueOf(defaultColor.getBlue() / 255f));
        return new Color(Float.parseFloat(rStr), Float.parseFloat(gStr), Float.parseFloat(bStr));
    }
    
    // --- Getters ---
    public static int getGuiSize() { return GUI_SIZE; }
    public static Color getSpeakGlowColor() { return SPEAK_GLOW_COLOR; }
    public static boolean isVisibleWhenIdle() { return VISIBLE_WHEN_IDLE; }
    public static boolean isMovementEnabled() { return MOVEMENT_ENABLED; }
    public static double getMovementSpeed() { return MOVEMENT_SPEED; }
    public static int getNewTargetDelayMs() { return NEW_TARGET_DELAY_MS; }
    public static CoreStyle getCoreStyle() { return CORE_STYLE; }
    public static double getBreathingSpeed() { return BREATHING_SPEED; }
    public static int getBreathingSizeVariation() { return BREATHING_SIZE_VARIATION; }
    public static double getSpeakingBreathSpeed() { return SPEAKING_BREATH_SPEED; }
    public static double getSpeakingFlickerSpeed() { return SPEAKING_FLICKER_SPEED; }
    public static double getSpeakingFlickerIntensity() { return SPEAKING_FLICKER_INTENSITY; }
}

