package com.cielcompanion.ui;

import com.cielcompanion.mood.EmotionalState;
import com.cielcompanion.mood.MoodConfig;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.RadialGradientPaint;
import java.util.Random;

public class CielGui {

    // CRITICAL FIX: Added the THINKING state to the Enum
    public enum GuiState { IDLE, LISTENING, SPEAKING, THINKING }

    private volatile JFrame frame;
    private volatile CielPanel panel;
    private Point mouseOffset;
    private volatile boolean isInitialized = false;
    // Startup work can publish a visual state before Swing has created the
    // panel. Keep the latest state so it is not lost during that handoff.
    private volatile GuiState pendingState = GuiState.IDLE;

    public void initialize() {
        frame = new JFrame();
        frame.setUndecorated(true);
        frame.setBackground(new Color(0, 0, 0, 0)); // Transparent background
        frame.setSize(GuiSettings.getGuiSize(), GuiSettings.getGuiSize());
        frame.setAlwaysOnTop(true);
        frame.setLocationRelativeTo(null);
        frame.setType(Window.Type.UTILITY);
        frame.setFocusableWindowState(false);

        panel = new CielPanel();
        panel.setCurrentState(pendingState);
        frame.setContentPane(panel);

        MouseAdapter mouseAdapter = new MouseAdapter() {
            public void mousePressed(MouseEvent e) { mouseOffset = e.getPoint(); panel.pauseMovement(); }
            public void mouseDragged(MouseEvent e) {
                Point curr = frame.getLocation();
                frame.setLocation(curr.x + (e.getX() - mouseOffset.x), curr.y + (e.getY() - mouseOffset.y));
            }
            public void mouseReleased(MouseEvent e) { panel.resumeMovement(); }
        };
        frame.addMouseListener(mouseAdapter);
        frame.addMouseMotionListener(mouseAdapter);

        SwingUtilities.invokeLater(() -> {
            // The orb is an activity indicator, not a permanent desktop widget.
            // It becomes visible only when the state machine enters THINKING,
            // LISTENING, or SPEAKING (unless the explicit idle setting opts in).
            frame.setVisible(GuiSettings.isVisibleWhenIdle());
            isInitialized = true;
            System.out.println("Ciel Debug: GUI Initialized (Visual Effects Restored).");
        });
    }

    public void setState(GuiState state) {
        if (state == null) state = GuiState.IDLE;
        pendingState = state;
        final GuiState appliedState = state;
        CielPanel targetPanel = panel;
        if (targetPanel != null) {
            targetPanel.setCurrentState(state);
            if (state == GuiState.IDLE) targetPanel.clearSpeechLevel();

            if (frame != null) {
                final boolean visible = appliedState != GuiState.IDLE || GuiSettings.isVisibleWhenIdle();
                SwingUtilities.invokeLater(() -> {
                    frame.setVisible(visible);
                    // Force the window to the absolute front whenever she becomes active.
                    if (visible && appliedState != GuiState.IDLE) {
                        frame.setAlwaysOnTop(false);
                        frame.setAlwaysOnTop(true);
                        frame.toFront();
                    }
                });
            }
        }
    }

    /** Feed the current output-envelope level (0..1) to the visualizer. */
    public void setSpeechLevel(float level) {
        if (panel != null) panel.setSpeechLevel(level);
    }

    /** Feed a peak level from the final Windows speaker endpoint. */
    public void setOutputSpeechLevel(float level) {
        if (panel != null) panel.setOutputSpeechLevel(level);
    }

    public boolean isSpeaking() {
        return panel != null && panel.isSpeaking();
    }

    public void clearSpeechLevel() {
        if (panel != null) panel.clearSpeechLevel();
    }

    /** Mark a provider word boundary; used to make speech cadence visibly track output. */
    public void markSpeechWord() {
        if (panel != null) panel.markSpeechWord();
    }

    public void setVisualState(EmotionalState.VisualState state) { if (panel != null) panel.setCurrentVisualState(state); }

    /**
     * Maps the final audio envelope onto the orb's 1..10-style motion range.
     * Kept pure so the speech visual contract can be regression-tested without
     * opening a desktop window or audio device.
     */
    public static float calculateSpeechPulse(float audioLevel, long sinceWordNanos, float intensity) {
        float audio = Float.isFinite(audioLevel) ? Math.max(0f, Math.min(1f, audioLevel)) : 0f;
        float safeIntensity = Float.isFinite(intensity) ? Math.max(0.1f, Math.min(1f, intensity)) : 0.8f;
        float wordKick = 0f;
        if (sinceWordNanos >= 0 && sinceWordNanos < 220_000_000L) {
            wordKick = 1.0f - (sinceWordNanos / 220_000_000f);
        }
        return Math.max(0.03f, Math.min(1.0f,
                0.03f + audio * (0.83f + safeIntensity * 0.12f) + wordKick * 0.92f));
    }

    private static class CielPanel extends JPanel {
        private volatile GuiState currentState = GuiState.IDLE;
        private volatile EmotionalState.VisualState currentVisualState;
        private volatile float targetSpeechLevel = 0f;
        private volatile long lastSpeechSignalNanos = 0L;
        private volatile long lastSpeechWordNanos = 0L;
        private float renderedSpeechLevel = 0f;
        private float renderedHue = -1f;
        private float renderedSaturation = 0f;
        private float renderedValue = 1f;
        private float animationTick = 0;
        private long lastAnimationNanos = System.nanoTime();
        private final Random random = new Random();
        // Soft motes provide motion without drawing orbit paths or glyphs.
        private final Particle[] particles = new Particle[56];

        private Point2D.Double currentPos;
        private Point2D.Double targetPos;
        private boolean isPaused = false;

        public CielPanel() {
            setOpaque(false);
            for (int i = 0; i < particles.length; i++) particles[i] = new Particle();

            // Thirty frames per second keeps the glow fluid while cutting paint/GPU
            // work roughly in half compared with the old 60 fps repaint loop.
            new Timer(33, e -> {
                long now = System.nanoTime();
                animationTick += (now - lastAnimationNanos) / 1_000_000_000f * 0.6f;
                lastAnimationNanos = now;
                for (Particle p : particles) p.update();
                repaint();
            }).start();

            if (GuiSettings.isMovementEnabled()) initializeMovement();
        }

        private class Particle {
            float x, y, angle, speed, radius, maxRadius, alpha, size;
            Particle() { reset(); }
            void reset() {
                float baseRadius = (getWidth() / 2f - 10f) * 0.75f;
                if (baseRadius <= 0) baseRadius = 1;
                angle = (float) (random.nextDouble() * 2 * Math.PI);
                speed = (float) (random.nextDouble() * 0.01 + 0.005);
                maxRadius = (float) (random.nextDouble() * baseRadius * 0.9);
                radius = (float) (random.nextDouble() * maxRadius);
                alpha = 0;
                size = (float) (random.nextDouble() * 1.5 + 1.0);
            }
            void update() {
                if (getWidth() == 0) return;
                angle += speed;
                radius -= 0.2;
                if (radius < 0) reset();
                alpha = (radius / maxRadius);
                x = (float) (getWidth() / 2.0 + Math.cos(angle) * radius);
                y = (float) (getHeight() / 2.0 + Math.sin(angle) * radius);
            }
        }

        private void initializeMovement() {
            Dimension s = Toolkit.getDefaultToolkit().getScreenSize();
            int sx = (s.width - GuiSettings.getGuiSize()) / 2;
            int sy = (s.height - GuiSettings.getGuiSize()) / 2;
            currentPos = new Point2D.Double(sx, sy);
            targetPos = new Point2D.Double(sx, sy);

            new Timer(16, e -> {
                if (isPaused) return;
                double dx = targetPos.x - currentPos.x;
                double dy = targetPos.y - currentPos.y;
                double dist = currentPos.distance(targetPos);
                if (dist > 1) {
                    currentPos.x += (dx / dist) * GuiSettings.getMovementSpeed();
                    currentPos.y += (dy / dist) * GuiSettings.getMovementSpeed();
                    Window w = SwingUtilities.getWindowAncestor(this);
                    if (w != null) w.setLocation((int)currentPos.x, (int)currentPos.y);
                }
            }).start();
            new Timer(GuiSettings.getNewTargetDelayMs(), e -> pickNewTarget()).start();
        }

        private void pickNewTarget() {
            if (isPaused) return;
            GraphicsConfiguration gc = getGraphicsConfiguration();
            if (gc == null) return;
            Rectangle b = gc.getBounds();
            int pad = 50;
            int sz = GuiSettings.getGuiSize();
            targetPos.x = b.x + pad + random.nextInt(b.width - sz - (2 * pad));
            targetPos.y = b.y + pad + random.nextInt(b.height - sz - (2 * pad));
        }

        public void pauseMovement() { isPaused = true; }
        public void resumeMovement() { isPaused = false; pickNewTarget(); }
        public void setCurrentState(GuiState state) {
            this.currentState = state;
            if (state != GuiState.SPEAKING) targetSpeechLevel = 0f;
            repaint();
        }
        public void setSpeechLevel(float level) {
            if (Float.isNaN(level) || Float.isInfinite(level)) level = 0.12f;
            targetSpeechLevel = Math.max(0f, Math.min(1f, level));
            lastSpeechSignalNanos = System.nanoTime();
        }
        public void setOutputSpeechLevel(float level) {
            if (Float.isNaN(level) || Float.isInfinite(level)) return;
            // Endpoint meters include the quiet lead-in and hardware floor.
            // Scale the useful range without replacing the deterministic
            // cadence used when a provider or speaker reports no level.
            setSpeechLevel(Math.max(0.08f, Math.min(1f, level * 1.75f)));
        }
        public boolean isSpeaking() { return currentState == GuiState.SPEAKING; }
        public void clearSpeechLevel() { targetSpeechLevel = 0f; }
        public void markSpeechWord() {
            lastSpeechWordNanos = System.nanoTime();
        }
        public void setCurrentVisualState(EmotionalState.VisualState state) { this.currentVisualState = state; }

       @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            // 1. Visibility Logic:
            // If IDLE and the setting is false, we draw NOTHING.
            // Otherwise, she remains visible.
            if (currentState == GuiState.IDLE && !GuiSettings.isVisibleWhenIdle()) {
                return;
            }

            // 2. Safety Fallback for Color:
            // Ensures she doesn't try to draw "Null" color if the EmotionManager is slightly slow.
            if (currentVisualState == null) {
                currentVisualState = new EmotionalState.VisualState(new Color(100, 100, 255), MoodConfig.AnimationStyle.GENTLE_PULSE, 0.5);
            }

            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            float width = getWidth();
            float height = getHeight();
            Point2D.Float center = new Point2D.Float(width / 2f, height / 2f);

            Color targetColor = cycleEmotionColor(currentVisualState.color(), currentVisualState.animation());
            // Preserve distinct listening/thinking states without bringing back
            // the orbit-ring geometry: the filled halo and motes carry the cue.
            if (currentState == GuiState.LISTENING) {
                targetColor = blend(targetColor, new Color(95, 225, 255), 0.24f);
            } else if (currentState == GuiState.THINKING) {
                targetColor = blend(targetColor, new Color(160, 200, 255), 0.22f);
            }
            float brightness = (float) currentVisualState.brightness();
            float maxRadius = (width / 2f) - 10f;

            // Animation Pulses
            float breath = (float) (Math.sin(animationTick * GuiSettings.getBreathingSpeed() * 2.0)) / 2.0f + 0.5f;
            // Attack quickly enough to catch consonants and syllable peaks;
            // release more gently so short vowels do not make the orb blink.
            float speechResponse = targetSpeechLevel > renderedSpeechLevel ? 0.52f : 0.22f;
            renderedSpeechLevel += (targetSpeechLevel - renderedSpeechLevel) * speechResponse;

            // GUI SYNC FIX: Distinct rapid pulse logic for the THINKING state
            float pulse = breath;
            if (currentState == GuiState.SPEAKING) {
                float intensity = (float) Math.max(0.1, Math.min(1.0, GuiSettings.getSpeakingFlickerIntensity()));
                // Keep a visible articulation cadence even when Java Sound's
                // mixer exposes no useful peak meter (muted/quiet endpoints
                // are common on laptops).  Measured energy still dominates;
                // cadence fills only the missing signal instead of replacing
                // it, so loud words expand farther than quiet ones.
                // A slower carrier gives the shell a smooth rise/fall rather
                // than a rapid flicker; the measured envelope still controls
                // the amplitude when the speaker meter is available.
                float cadence = (float) ((Math.sin(animationTick
                        * GuiSettings.getSpeakingFlickerSpeed() * 0.72) + 1.0) * 0.5);
                // A quiet/muted endpoint must still produce a readable smooth
                // pulse.  This is deliberately a broad envelope, not a ring or
                // flicker, and measured audio still wins whenever it is present.
                float fallbackEnvelope = 0.12f + cadence * 0.70f;
                long signalAge = System.nanoTime() - lastSpeechSignalNanos;
                boolean liveSignal = lastSpeechSignalNanos > 0L && signalAge < 180_000_000L;
                float signal = liveSignal ? renderedSpeechLevel : fallbackEnvelope;
                float speechEnvelope = calculateSpeechPulse(signal, Long.MAX_VALUE, intensity);
                // When PCM is arriving, its amplitude is authoritative.  The
                // carrier is used only as a recovery path when no signal update
                // has arrived, so the orb cannot fake a wave over real speech.
                pulse = liveSignal
                        ? speechEnvelope
                        : speechEnvelope * (0.42f + cadence * 0.58f);
            } else if (currentState == GuiState.THINKING) {
                pulse = (float) (Math.sin(animationTick * 15.0) + 1) / 2.0f; // Rapid processing throb
            } else if (currentState == GuiState.LISTENING) {
                // A gentle listening shimmer keeps this state visibly distinct
                // without drawing a separate outline around the orb.
                pulse = Math.max(pulse,
                        0.18f + (float) ((Math.sin(animationTick * 8.0) + 1.0) * 0.06));
            }

            // EmotionManager may change the target several times during a
            // lament or other intense passage. Interpolate in HSB space so
            // blue-to-pink, happy-to-sad, and similar changes travel through
            // the spectrum instead of snapping between RGB values. The small
            // pulse-linked value wave keeps the color movement visually in
            // phase with the audio without overriding the emotional target.
            Color baseColor = smoothEmotionColor(targetColor, pulse);

            // Restore the older desktop/GitHub silhouette: a smaller crystal
            // core nested inside a broad halo.  Pulse still comes from the
            // real PCM envelope; only the proportions are restored here.
            // Keep the center airy and translucent; the halo carries most of
            // the visible speech expansion instead of a solid filled disk.
            float coreR = maxRadius * (0.44f + pulse * 0.28f);
            float glowR = maxRadius * (0.74f + breath * 0.10f + pulse * 0.16f);
            float displayBrightness = brightness;
            if (currentState == GuiState.SPEAKING) {
                displayBrightness = Math.max(0.08f, Math.min(1.0f,
                        brightness * (0.28f + pulse * 0.90f)));
            }

            // --- LAYER 1: Outer Glow (Soft Halo) ---
            float glowAlpha = Math.max(0.08f, Math.min(0.96f, displayBrightness * (0.24f + pulse * 0.70f)));
            Color g1 = new Color(baseColor.getRed()/255f, baseColor.getGreen()/255f, baseColor.getBlue()/255f, glowAlpha);
            Color g2 = new Color(baseColor.getRed()/255f, baseColor.getGreen()/255f, baseColor.getBlue()/255f, 0f);
            g2d.setPaint(new RadialGradientPaint(center, glowR, new float[]{0f, 1f}, new Color[]{g1, g2}));
            g2d.fill(new Ellipse2D.Float(center.x - glowR, center.y - glowR, glowR*2, glowR*2));

            // --- LAYER 2: Crystal Core (Solid Center) ---
            // Keep the core stable and translucent.  Speech changes the shell
            // and its glow, not the crystal's silhouette or opacity.
            // The crystal should read as translucent depth, not a flat filled
            // disk.  Leave enough alpha for a stable silhouette while allowing
            // the halo and motes to remain visible through it.
            float coreAlpha = currentState == GuiState.SPEAKING
                    ? Math.max(0.30f, Math.min(0.64f, 0.30f + pulse * 0.34f))
                    : Math.min(0.46f, Math.max(0.20f, displayBrightness * 0.68f));
            Color coreC = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), (int)(255 * coreAlpha));
            float coreHighlight = currentState == GuiState.SPEAKING ? (58f + pulse * 54f) : 58f;
            Color coreHigh = new Color(255, 255, 255, (int)(coreHighlight * coreAlpha)); // Stable highlight
            g2d.setPaint(new RadialGradientPaint(new Point2D.Float(center.x - coreR*0.2f, center.y - coreR*0.2f), coreR, new float[]{0f, 1f}, new Color[]{coreHigh, coreC}));
            g2d.fill(new Ellipse2D.Float(center.x - coreR, center.y - coreR, coreR*2, coreR*2));

            // --- LAYER 3: Particles (Energy Motes) ---
            // The motes drift independently of speech; only the halo responds
            // to the output envelope so the silhouette remains calm and clear.
            for (Particle p : particles) {
                float particleAlpha = p.alpha * (0.32f + displayBrightness * 0.48f);
                g2d.setColor(new Color(1f, 1f, 1f, particleAlpha));
                g2d.fill(new Ellipse2D.Float(p.x - p.size/2, p.y - p.size/2, p.size, p.size));
            }

            g2d.dispose();
        }

        private Color cycleEmotionColor(Color emotionalColor, MoodConfig.AnimationStyle style) {
            float[] hsb = Color.RGBtoHSB(emotionalColor.getRed(), emotionalColor.getGreen(), emotionalColor.getBlue(), null);
            double seconds = System.nanoTime() / 1_000_000_000.0;
            if (style == MoodConfig.AnimationStyle.RAINBOW_CYCLE) {
                hsb[0] = (float) ((hsb[0] + (seconds / 10.0)) % 1.0); // One calm rainbow cycle every 10 seconds.
            } else {
                // Keep each emotion recognizable, with a small ambient color drift around its tint.
                hsb[0] = (float) ((hsb[0] + Math.sin(seconds * (2.0 * Math.PI / 12.0)) * 0.035 + 1.0) % 1.0);
            }
            return Color.getHSBColor(hsb[0], hsb[1], hsb[2]);
        }

        private Color blend(Color first, Color second, float amount) {
            float t = Math.max(0f, Math.min(1f, amount));
            int r = Math.round(first.getRed() * (1f - t) + second.getRed() * t);
            int g = Math.round(first.getGreen() * (1f - t) + second.getGreen() * t);
            int b = Math.round(first.getBlue() * (1f - t) + second.getBlue() * t);
            return new Color(r, g, b);
        }

        private Color smoothEmotionColor(Color targetColor, float pulse) {
            float[] target = Color.RGBtoHSB(targetColor.getRed(), targetColor.getGreen(),
                    targetColor.getBlue(), null);
            if (renderedHue < 0f) {
                renderedHue = target[0];
                renderedSaturation = target[1];
                renderedValue = target[2];
            } else {
                // About 350-500 ms for a meaningful mood transition at the
                // 30 fps paint rate; quick changes remain visible but graceful.
                float amount = currentState == GuiState.SPEAKING ? 0.095f : 0.075f;
                float hueDelta = target[0] - renderedHue;
                if (hueDelta > 0.5f) hueDelta -= 1.0f;
                if (hueDelta < -0.5f) hueDelta += 1.0f;
                renderedHue = (renderedHue + hueDelta * amount + 1.0f) % 1.0f;
                renderedSaturation += (target[1] - renderedSaturation) * amount;
                renderedValue += (target[2] - renderedValue) * amount;
            }

            float safePulse = Math.max(0f, Math.min(1f, pulse));
            float wave = (safePulse - 0.5f) * 0.035f;
            float value = Math.max(0f, Math.min(1f, renderedValue + wave));
            return Color.getHSBColor(renderedHue,
                    Math.max(0f, Math.min(1f, renderedSaturation)), value);
        }
    }
}
