package com.cielcompanion.ui;

import com.cielcompanion.mood.EmotionalState;
import com.cielcompanion.mood.MoodConfig;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.AffineTransform;
import java.awt.RadialGradientPaint;
import java.util.Random;

public class CielGui {
    
    public enum GuiState { IDLE, LISTENING, SPEAKING }

    private JFrame frame;
    private CielPanel panel;
    private Point mouseOffset;
    private volatile boolean isInitialized = false;

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
            frame.setVisible(true);
            isInitialized = true;
            System.out.println("Ciel Debug: GUI Initialized (Visual Effects Restored).");
        });
    }

    public void setState(GuiState state) { if (panel != null) panel.setCurrentState(state); }
    public void setVisualState(EmotionalState.VisualState state) { if (panel != null) panel.setCurrentVisualState(state); }
    
    private static class CielPanel extends JPanel {
        private GuiState currentState = GuiState.IDLE;
        private EmotionalState.VisualState currentVisualState;
        private float animationTick = 0;
        private final Random random = new Random();
        private final Particle[] particles = new Particle[50];
        
        private Point2D.Double currentPos;
        private Point2D.Double targetPos;
        private boolean isPaused = false;

        public CielPanel() {
            setOpaque(false);
            for (int i = 0; i < particles.length; i++) particles[i] = new Particle();
            
            new Timer(16, e -> {
                animationTick += 0.01f;
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
        public void setCurrentState(GuiState state) { this.currentState = state; repaint(); }
        public void setCurrentVisualState(EmotionalState.VisualState state) { this.currentVisualState = state; }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            // 1. Visibility Logic:
            // If IDLE, we draw NOTHING (creating a 100% transparent window).
            // She will only "appear" when state changes to SPEAKING or LISTENING.
            if (currentState == GuiState.IDLE) {
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
            
            Color baseColor = currentVisualState.color();
            float brightness = (float) currentVisualState.brightness();
            float maxRadius = (width / 2f) - 10f;
            
            // Animation Pulses
            float breath = (float) (Math.sin(animationTick * GuiSettings.getBreathingSpeed() * 2.0)) / 2.0f + 0.5f;
            float pulse = (currentState == GuiState.SPEAKING) 
                ? (float) Math.abs(Math.sin(animationTick * GuiSettings.getSpeakingFlickerSpeed() * 2.0)) 
                : breath;

            float coreR = maxRadius * (0.65f + pulse * 0.1f);
            float glowR = maxRadius * (0.9f + breath * 0.1f);

            // --- LAYER 1: Outer Glow (Soft Halo) ---
            Color g1 = new Color(baseColor.getRed()/255f, baseColor.getGreen()/255f, baseColor.getBlue()/255f, brightness * 0.4f);
            Color g2 = new Color(baseColor.getRed()/255f, baseColor.getGreen()/255f, baseColor.getBlue()/255f, 0f);
            g2d.setPaint(new RadialGradientPaint(center, glowR, new float[]{0f, 1f}, new Color[]{g1, g2}));
            g2d.fill(new Ellipse2D.Float(center.x - glowR, center.y - glowR, glowR*2, glowR*2));

            // --- LAYER 2: Crystal Core (Solid Center) ---
            Color coreC = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), (int)(255 * brightness));
            Color coreHigh = new Color(255, 255, 255, (int)(100 * brightness)); // Highlight
            g2d.setPaint(new RadialGradientPaint(new Point2D.Float(center.x - coreR*0.2f, center.y - coreR*0.2f), coreR, new float[]{0f, 1f}, new Color[]{coreHigh, coreC}));
            g2d.fill(new Ellipse2D.Float(center.x - coreR, center.y - coreR, coreR*2, coreR*2));

            // --- LAYER 3: Particles (Energy Motes) ---
            for (Particle p : particles) {
                float particleAlpha = p.alpha * (0.5f + pulse * 0.5f);
                g2d.setColor(new Color(1f, 1f, 1f, particleAlpha * brightness));
                g2d.fill(new Ellipse2D.Float(p.x - p.size/2, p.y - p.size/2, p.size, p.size));
            }

            // --- LAYER 4: Rotating Rings (The "Tech" Look) ---
            AffineTransform oldTransform = g2d.getTransform();
            
            // Ring 1: Slow outer ring
            g2d.setStroke(new BasicStroke(0.5f));
            g2d.setColor(new Color(1f, 1f, 1f, 0.2f * brightness));
            g2d.translate(center.x, center.y);
            g2d.scale(1.0, 0.3); // Flatten perspective
            for(int i = 0; i < 6; i++) {
                 g2d.rotate(Math.toRadians(30 + (animationTick * 2))); // Rotate
                 g2d.draw(new Ellipse2D.Float(-coreR * 1.5f, -coreR * 1.5f, coreR * 3.0f, coreR * 3.0f));
            }
            g2d.setTransform(oldTransform);
            
            // Ring 2: Medium middle ring
            g2d.setStroke(new BasicStroke(1.0f));
            g2d.setColor(new Color(1f, 1f, 1f, 0.4f * brightness));
            g2d.translate(center.x, center.y);
            g2d.rotate(animationTick * 0.7);
            g2d.scale(1.0, 0.4);
            g2d.draw(new Ellipse2D.Float(-coreR * 1.2f, -coreR * 1.2f, coreR * 2.4f, coreR * 2.4f));
            g2d.setTransform(oldTransform);

            // Ring 3: Fast inner ring (Counter-rotate)
            g2d.setStroke(new BasicStroke(1.5f));
            g2d.setColor(new Color(1f, 1f, 1f, 0.7f * brightness));
            g2d.translate(center.x, center.y);
            g2d.rotate(-animationTick * 1.2);
            g2d.scale(1.0, 0.2);
            g2d.draw(new Ellipse2D.Float(-coreR * 1.1f, -coreR * 1.1f, coreR * 2.2f, coreR * 2.2f));
            g2d.setTransform(oldTransform);

            // --- LAYER 5: Listening Overlay ---
            if (currentState == GuiState.LISTENING) {
                float listeningPulse = (float) (Math.sin(animationTick * 20) + 1) / 2.0f;
                g2d.setColor(Color.CYAN);
                g2d.setStroke(new BasicStroke(3.0f));
                float r = maxRadius * (0.95f - listeningPulse * 0.1f);
                g2d.draw(new Ellipse2D.Float(center.x - r, center.y - r, r * 2, r * 2));
            }
            
            g2d.dispose();
        }
    }
}