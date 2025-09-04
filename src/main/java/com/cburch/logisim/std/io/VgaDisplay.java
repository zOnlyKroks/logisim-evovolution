package com.cburch.logisim.std.io;

import static com.cburch.logisim.std.Strings.S;

import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.AbstractComponentFactory;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.comp.ComponentEvent;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.comp.ComponentState;
import com.cburch.logisim.comp.ComponentUserEvent;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.comp.ManagedComponent;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeEvent;
import com.cburch.logisim.data.AttributeListener;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.AttributeSets;
import com.cburch.logisim.data.Attributes;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.tools.ToolTipMaker;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

class VgaDisplay extends ManagedComponent implements ToolTipMaker, AttributeListener {

    public static final String _ID = "VGA Display";
    public static final ComponentFactory factory = new Factory();

    static final Integer[] WIDTH_OPTIONS = {320, 640, 800, 1024, 1280, 1920};
    static final Integer[] HEIGHT_OPTIONS = {240, 480, 600, 768, 1024, 1080};
    static final Integer[] SCALE_OPTIONS = {1, 2, 3, 4};

    public static final Attribute<Integer> ATTR_WIDTH =
            Attributes.forOption("width", S.getter("vgaDisplayWidth"), WIDTH_OPTIONS);
    public static final Attribute<Integer> ATTR_HEIGHT =
            Attributes.forOption("height", S.getter("vgaDisplayHeight"), HEIGHT_OPTIONS);
    public static final Attribute<Integer> ATTR_SCALE =
            Attributes.forOption("scale", S.getter("vgaDisplayScale"), SCALE_OPTIONS);

    private static final Attribute<?>[] ATTRIBUTES = {ATTR_WIDTH, ATTR_HEIGHT, ATTR_SCALE};

    private static class Factory extends AbstractComponentFactory {
        private Factory() {}

        @Override
        public String getName() { return _ID; }

        @Override
        public String getDisplayName() { return S.get("vgaDisplayComponent"); }

        @Override
        public AttributeSet createAttributeSet() {
            return AttributeSets.fixedSet(ATTRIBUTES, new Object[] {640, 480, 1});
        }

        @Override
        public Component createComponent(Location loc, AttributeSet attrs) {
            return new VgaDisplay(loc, attrs);
        }

        @Override
        public Bounds getOffsetBounds(AttributeSet attrs) {
            final var scale = attrs.getValue(ATTR_SCALE);
            final var width = attrs.getValue(ATTR_WIDTH);
            final var height = attrs.getValue(ATTR_HEIGHT);
            final var dispWidth = Math.max(scale * width / 4 + 30, 140);
            final var dispHeight = Math.max(scale * height / 4, 100);
            return Bounds.create(-20, -50, dispWidth + 30, dispHeight + 60);
        }

        @Override
        public void paintIcon(ComponentDrawContext context, int x, int y, AttributeSet attrs) {
            drawVgaIcon(context, x, y);
        }
    }

    static final int P_RED = 0;
    static final int P_GREEN = 1;
    static final int P_BLUE = 2;
    static final int P_HSYNC = 3;
    static final int P_VSYNC = 4;
    static final int P_CLK   = 5;

    private VgaDisplay(Location loc, AttributeSet attrs) {
        super(loc, attrs, 6);
        configureComponent();
        attrs.addAttributeListener(this);
    }

    @Override
    public ComponentFactory getFactory() { return factory; }

    Location loc(int pin) { return getEndLocation(pin); }

    private static int toByte(Value v) {
        if (v == null || !v.isFullyDefined()) return 0;
        long n = v.toLongValue();
        if (n < 0) n = 0;
        if (n > 255) n = 255;
        return (int) n;
    }

    @Override
    public void propagate(CircuitState circuitState) {
        final var state = getState(circuitState);

        final int r = toByte(circuitState.getValue(loc(P_RED)));
        final int g = toByte(circuitState.getValue(loc(P_GREEN)));
        final int b = toByte(circuitState.getValue(loc(P_BLUE)));

        final boolean hsyncHigh = (circuitState.getValue(loc(P_HSYNC)) == Value.TRUE);
        final boolean vsyncHigh = (circuitState.getValue(loc(P_VSYNC)) == Value.TRUE);

        final boolean clk = (circuitState.getValue(loc(P_CLK)) == Value.TRUE);

        if (clk && !state.prevClk) {
            state.tick(r, g, b, hsyncHigh, vsyncHigh);
        }

        state.prevClk = clk;
    }

    @Override
    public void draw(ComponentDrawContext context) {
        final var loc = getLocation();
        final var state = getState(context.getCircuitState());
        drawVga(context, loc.getX(), loc.getY(), state);
    }

    static void drawVgaIcon(ComponentDrawContext context, int x, int y) {
        final var g = context.getGraphics().create();
        g.translate(x, y);
        g.setColor(Color.BLACK);
        g.drawRoundRect(scale(2), scale(2), scale(16), scale(12), scale(2), scale(2));
        g.setColor(Color.DARK_GRAY);
        g.fillRoundRect(scale(3), scale(3), scale(14), scale(10), scale(1), scale(1));
        g.setColor(Color.YELLOW);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 5; col++) {
                g.fillOval(scale(4 + col * 3), scale(4 + row * 3), scale(1), scale(1));
            }
        }
        g.dispose();
    }

    private static int scale(int v) { return AppPreferences.getScaled(v); }

    void drawVga(ComponentDrawContext context, int x, int y, State state) {
        final var g = context.getGraphics();
        final var attrs = getAttributeSet();

        final var scale = attrs.getValue(ATTR_SCALE);
        final var width = attrs.getValue(ATTR_WIDTH);
        final var height = attrs.getValue(ATTR_HEIGHT);
        final var dispWidth = Math.max(scale * width / 4 + 60, 140);
        final var dispHeight = Math.max(scale * height / 4 + 60, 100);

        x += (-20);
        y += (-50);

        g.setColor(new Color(AppPreferences.COMPONENT_COLOR.get()));
        g.drawRoundRect(x, y, dispWidth, dispHeight, 6, 6);

        // Draw pins
        drawVgaPins(context);

        // Screen rectangle
        final var screenX = x + 20;
        final var screenY = y + 30;
        final var screenWidth = dispWidth - 40;
        final var screenHeight = dispHeight - 50;

        // Background bezel
        g.setColor(Color.BLACK);
        g.fillRect(screenX, screenY, screenWidth, screenHeight);
        g.setColor(Color.LIGHT_GRAY);
        g.drawRect(screenX, screenY, screenWidth, screenHeight);

        // Label
        g.setColor(Color.WHITE);
        g.drawString("VGA", x + 5, y + 15);

        // Draw framebuffer (scaled)
        final var frame = state.ensureFrame(width, height);
        if (frame != null) {
            final var g2 = (Graphics2D) g.create();
            // Smooth scaling for nicer look on non-1x scales
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(frame, screenX + 2, screenY + 2, screenWidth - 4, screenHeight - 4, null);
            g2.dispose();
        }
    }

    void drawVgaPins(ComponentDrawContext context) {
        context.drawPin(this, P_RED);
        context.drawPin(this, P_GREEN);
        context.drawPin(this, P_BLUE);
        context.drawPin(this, P_HSYNC);
        context.drawPin(this, P_VSYNC);
        context.drawPin(this, P_CLK);
    }

    private State getState(CircuitState circuitState) {
        var state = (State) circuitState.getData(this);
        if (state == null) {
            state = new State(getAttributeSet().getValue(ATTR_WIDTH), getAttributeSet().getValue(ATTR_HEIGHT));
            circuitState.setData(this, state);
        }
        // Ensure framebuffer matches current attributes
        final var w = getAttributeSet().getValue(ATTR_WIDTH);
        final var h = getAttributeSet().getValue(ATTR_HEIGHT);
        if (state.width != w || state.height != h) {
            state.resize(w, h);
        }
        return state;
    }

    private static class State implements ComponentState, Cloneable {
        int width, height;
        BufferedImage frame;
        int x = 0, y = 0;

        boolean hsyncAsserted = false;
        boolean vsyncAsserted = false;
        boolean prevHsyncAsserted = false;
        boolean prevVsyncAsserted = false;
        boolean prevClk = false;

        State(int w, int h) { resize(w, h); }

        BufferedImage ensureFrame(int w, int h) {
            if (frame == null || width != w || height != h) resize(w, h);
            return frame;
        }

        void resize(int w, int h) {
            width = Math.max(1, w);
            height = Math.max(1, h);
            frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            x = 0;
            y = 0;
        }

        // One simulation "pixel" tick
        void tick(int r, int g, int b, boolean hsync, boolean vsync) {
            prevHsyncAsserted = hsyncAsserted;
            prevVsyncAsserted = vsyncAsserted;
            hsyncAsserted = hsync;
            vsyncAsserted = vsync;

            if (vsyncAsserted && !prevVsyncAsserted) {
                x = 0;
                y = 0;
                return;
            }

            if (hsyncAsserted && !prevHsyncAsserted) {
                x = 0;
                y++;
                if (y >= height) y = 0;
                return;
            }

            if (!hsyncAsserted && !vsyncAsserted) {
                if (x >= 0 && x < width && y >= 0 && y < height) {
                    final int rgb = ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
                    frame.setRGB(x, y, rgb);
                }
                x++;
                if (x >= width) {
                    x = 0;
                    y++;
                    if (y >= height) y = 0;
                }
            }
        }

        @Override
        public Object clone() {
            try {
                return super.clone();
            } catch (CloneNotSupportedException e) {
                return null;
            }
        }
    }

    @Override
    public Object getFeature(Object key) {
        if (key == ToolTipMaker.class) return this;
        else return super.getFeature(key);
    }

    @Override
    public String getToolTip(ComponentUserEvent e) {
        int end = -1;
        for (var i = getEnds().size() - 1; i >= 0; i--) {
            if (getEndLocation(i).manhattanDistanceTo(e.getX(), e.getY()) < 10) {
                end = i;
                break;
            }
        }
        return switch (end) {
            case P_RED -> S.get("vgaDisplayRed");
            case P_GREEN -> S.get("vgaDisplayGreen");
            case P_BLUE -> S.get("vgaDisplayBlue");
            case P_HSYNC -> S.get("vgaDisplayHSync");
            case P_VSYNC -> S.get("vgaDisplayVSync");
            case P_CLK -> S.get("vgaDisplayPixelClock");
            default -> S.get("vgaDisplayPin", end + 1);
        };
    }

    @Override
    public void attributeValueChanged(AttributeEvent e) {
        configureComponent();
    }

    void configureComponent() {
        final var attrs = getAttributeSet();

        final var scale = attrs.getValue(ATTR_SCALE);
        final var width = attrs.getValue(ATTR_WIDTH);
        final var height = attrs.getValue(ATTR_HEIGHT);

        // Calculate visual dimensions (clamped for usability)
        int dispWidth  = Math.max(scale * width / 4 + 60, 140);
        int dispHeight = Math.max(scale * height / 4 + 60, 100);

        // Cap the displayed size so 1920×1080 doesn't make the box enormous
        final int MAX_W = 300;
        final int MAX_H = 200;
        if (dispWidth > MAX_W)  dispWidth = MAX_W;
        if (dispHeight > MAX_H) dispHeight = MAX_H;

        // Component-local bounds
        final var bounds = Bounds.create(-20, -50, dispWidth + 30, dispHeight + 60);

        final var base = getLocation();
        final var originX = base.getX() + bounds.getX();
        final var originY = base.getY() + bounds.getY();

        // Left side: RGB pins, fixed spacing relative to bezel
        setEnd(P_RED,   Location.create(originX, originY + 60, true),
                BitWidth.create(8), EndData.INPUT_ONLY);
        setEnd(P_GREEN, Location.create(originX, originY + 80, true),
                BitWidth.create(8), EndData.INPUT_ONLY);
        setEnd(P_BLUE,  Location.create(originX, originY + 100, true),
                BitWidth.create(8), EndData.INPUT_ONLY);

        setEnd(P_HSYNC, Location.create(originX, originY + 120, true),
                BitWidth.ONE, EndData.INPUT_ONLY);
        setEnd(P_VSYNC, Location.create(originX, originY + 140, true),
                BitWidth.ONE, EndData.INPUT_ONLY);

        setEnd(P_CLK, Location.create(originX, originY + 160, true),
                BitWidth.ONE, EndData.INPUT_ONLY);

        recomputeBounds();
        fireComponentInvalidated(new ComponentEvent(this));
    }
}