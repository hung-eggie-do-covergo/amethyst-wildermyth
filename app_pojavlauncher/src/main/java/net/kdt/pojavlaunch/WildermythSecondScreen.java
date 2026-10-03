package net.kdt.pojavlaunch;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The game's own HUD widgets on a second display (dual-screen handhelds). The game agent (dlcagent
 * DualScreen) connects to a loopback socket and sends length-prefixed messages: 'J' + JSON state, or
 * 'F' + widget id + width + height + RGBA pixels drawn by the game. Taps go back as JSON lines.
 */
final class WildermythSecondScreen {
    static final String PREF = "wm_dual_screen";
    private static final String TAG = "WMSecondScreen";
    /** One zoom for every widget: the game's render shown 1:1, shrunk only where it can't fit its slot. */
    private static final float MAX_SCALE = 1f;
    /** The game's own selection blue, as it outlines the selected tile on the map. */
    private static final int SELECTED = 0xFF55B5F5;
    /** Widget ids shared with the agent. */
    private static final int ROSTER = 0, CONSOLE = 1, CONSOLE_TOGGLE = 2, THREATS = 3, SHEET = 4, STATUS = 5,
            PLACE = 6, COUNT = 7;
    /**
     * Where each widget goes, as fractions {left, top, right, bottom, horizontal align, vertical align}:
     * heroes down the left as in the HUD, the selected hero's sheet (or the selected place's card) beside
     * them. Threats, the hero's card and the log are overlays, shown on demand.
     */
    private static final float[][] SLOTS = new float[COUNT][];
    /** The sheet's header row (name, tab dropdown, Back) takes the top of the space right of the roster. */
    private static final float SHEET_TOP = 0.1f;
    /** Where the header row ends: right of it sit the Threats and log buttons. */
    private static final float HEADER_END = 0.79f;
    static {
        SLOTS[ROSTER] = new float[]{0f, 0f, 0.17f, 1f, 0.5f, 0f};
        SLOTS[THREATS] = new float[]{0.8f, SHEET_TOP, 1f, 1f, 0.5f, 0f}; // an overlay, shown on demand
        SLOTS[SHEET] = new float[]{0.18f, SHEET_TOP, 1f, 1f, 0.5f, 0f};
        SLOTS[STATUS] = new float[]{0.18f, SHEET_TOP, 1f, 1f, 0.5f, 0f}; // an overlay, from the hero's name
        SLOTS[PLACE] = new float[]{0.18f, SHEET_TOP, 1f, 1f, 0.5f, 0f}; // in the sheet's place while a tile is selected
        SLOTS[CONSOLE] = new float[]{0f, 0f, 1f, 1f, 0.5f, 0f};
        SLOTS[CONSOLE_TOGGLE] = new float[]{0.95f, 0f, 1f, 0.055f, 1f, 0f};
    }

    private final Activity activity;
    private final ServerSocket server;
    private volatile OutputStream out;
    private volatile String consoleSize, sheetSize;
    private Panel panel;

    private WildermythSecondScreen(Activity activity) throws Exception {
        this.activity = activity;
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); // IPv4: ART's loopback default is ::1
    }

    /** The first presentation display, e.g. a handheld's bottom screen; null on single-screen devices. */
    static Display display(Context ctx) {
        Display[] d = ((DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE))
                .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        return d.length == 0 ? null : d[0];
    }

    /** Shows the panel and returns the port for -Dwm.ds.port, or 0 when there is no second display. */
    static int start(Activity activity, File game) {
        Display display = display(activity);
        if (display == null) return 0;
        try {
            WildermythSecondScreen s = new WildermythSecondScreen(activity);
            activity.runOnUiThread(() -> {
                s.panel = s.new Panel(activity, display, new WildermythTheme(activity, game));
                s.panel.show();
            });
            Thread t = new Thread(s::serve, "wm-second-screen");
            t.setDaemon(true);
            t.start();
            return s.server.getLocalPort();
        } catch (Exception e) {
            Log.w(TAG, "second screen unavailable", e);
            return 0;
        }
    }

    /** One connection at a time; the agent reconnects if the game drops it. */
    private void serve() {
        while (true) {
            try (Socket s = server.accept();
                 DataInputStream in = new DataInputStream(s.getInputStream())) {
                out = s.getOutputStream();
                if (consoleSize != null) send(consoleSize); // the boxes were laid out before the game connected
                if (sheetSize != null) send(sheetSize);
                while (true) {
                    byte[] msg = new byte[in.readInt()];
                    in.readFully(msg);
                    if (msg[0] == 'F') {
                        int id = msg[1];
                        Bitmap frame = frame(msg);
                        activity.runOnUiThread(() -> { if (panel != null) panel.frame(id, frame); });
                    } else {
                        JSONObject json = new JSONObject(new String(msg, 1, msg.length - 1, StandardCharsets.UTF_8));
                        activity.runOnUiThread(() -> { if (panel != null) panel.update(json); });
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "game connection ended", e);
            }
            out = null;
        }
    }

    /** GL rows arrive top-down already (the agent flips them); RGBA bytes are ARGB_8888's memory layout. */
    private static Bitmap frame(byte[] msg) {
        ByteBuffer b = ByteBuffer.wrap(msg);
        b.position(2);
        int w = b.getInt(), h = b.getInt();
        if (w <= 0 || h <= 0) return null; // the widget has nothing to show right now
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(msg, 10, w * h * 4));
        return bmp;
    }

    /** 0 list, 1 detail: which sheet column the agent is streaming (it tells us in its state). */
    private volatile int sheetView;

    private void send(String json) {
        OutputStream o = out;
        if (o == null) return;
        new Thread(() -> {
            try {
                synchronized (o) {
                    o.write((json + "\n").getBytes(StandardCharsets.UTF_8));
                    o.flush();
                }
            } catch (Exception ignored) {
                // The game went away; serve() notices.
            }
        }).start();
    }

    private final class Panel extends Presentation {
        private final WildermythTheme theme;
        private final FrameView[] widgets = new FrameView[COUNT];
        private View content, idle;
        private SheetHeader header;
        private Button threatsToggle;

        Panel(Context ctx, Display display, WildermythTheme theme) {
            super(ctx, display);
            this.theme = theme;
        }

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            // Never take focus: the pad must keep driving the game on the main screen.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            FrameLayout root = new FrameLayout(getContext());
            root.setBackground(theme.background());
            int pad = theme.dp(12);

            FrameLayout content = new FrameLayout(getContext()) {
                @Override
                protected void onLayout(boolean changed, int l, int t, int r, int b) {
                    int w = r - l - getPaddingLeft() - getPaddingRight(), h = b - t - getPaddingTop() - getPaddingBottom();
                    for (int id = 0; id < COUNT; id++) {
                        float[] f = SLOTS[id];
                        widgets[id].align(f[4], f[5]);
                        int x0 = getPaddingLeft() + Math.round(f[0] * w), y0 = getPaddingTop() + Math.round(f[1] * h);
                        int x1 = getPaddingLeft() + Math.round(f[2] * w), y1 = getPaddingTop() + Math.round(f[3] * h);
                        widgets[id].measure(View.MeasureSpec.makeMeasureSpec(x1 - x0, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(y1 - y0, View.MeasureSpec.EXACTLY));
                        widgets[id].layout(x0, y0, x1, y1);
                    }
                    int x0 = getPaddingLeft() + Math.round(SLOTS[SHEET][0] * w), y1 = getPaddingTop() + Math.round(SHEET_TOP * h);
                    int x1 = getPaddingLeft() + Math.round(HEADER_END * w);
                    header.measure(View.MeasureSpec.makeMeasureSpec(x1 - x0, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(y1 - getPaddingTop(), View.MeasureSpec.EXACTLY));
                    header.layout(x0, getPaddingTop(), x1, y1);
                    int t0 = x1 + theme.dp(8), t1 = getPaddingLeft() + Math.round(SLOTS[CONSOLE_TOGGLE][0] * w) - theme.dp(8);
                    threatsToggle.measure(View.MeasureSpec.makeMeasureSpec(t1 - t0, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(y1 - getPaddingTop(), View.MeasureSpec.EXACTLY));
                    threatsToggle.layout(t0, getPaddingTop(), t1, y1);
                }
            };
            content.setPadding(pad, pad, pad, pad);
            for (int id = 0; id < COUNT; id++) {
                widgets[id] = new FrameView(getContext(), id);
                content.addView(widgets[id]);
            }
            header = new SheetHeader(getContext(), theme);
            content.addView(header);
            widgets[SHEET].addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                sheetSize = "{\"sheetSize\":[" + (r - l) + "," + (b - t) + "]}";
                send(sheetSize);
            });
            // The hero's card (the HUD's selection tooltip): opened by tapping the name, closed by tapping it.
            widgets[STATUS].setBackgroundColor(0xCC000000);
            widgets[STATUS].setVisibility(View.GONE);
            widgets[STATUS].bringToFront();
            // Threats: hidden until asked for, then a column over the sheet's right edge.
            widgets[THREATS].setBackgroundColor(0xE6000000 | (WildermythTheme.BG & 0xFFFFFF));
            widgets[THREATS].setVisibility(View.GONE);
            widgets[THREATS].bringToFront();
            threatsToggle = new Button(getContext());
            theme.style(threatsToggle);
            threatsToggle.setTextSize(16);
            threatsToggle.setPadding(0, 0, 0, 0);
            threatsToggle.setFocusable(false);
            threatsToggle.setText("Threats");
            threatsToggle.setOnClickListener(v -> {
                boolean show = widgets[THREATS].getVisibility() != View.VISIBLE;
                widgets[THREATS].setVisibility(show ? View.VISIBLE : View.GONE);
                threatsToggle.setAlpha(show ? 1f : 0.7f);
            });
            threatsToggle.setAlpha(0.7f);
            content.addView(threatsToggle);
            // The log covers everything else when shown, on an opaque ground; its button stays on top.
            widgets[CONSOLE].setBackgroundColor(WildermythTheme.BG);
            widgets[CONSOLE].bringToFront();
            widgets[CONSOLE_TOGGLE].bringToFront();
            widgets[CONSOLE].addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                consoleSize = "{\"consoleSize\":[" + (r - l) + "," + (b - t) + "]}";
                send(consoleSize);
            });
            this.content = content;
            content.setVisibility(View.GONE);
            root.addView(content, new FrameLayout.LayoutParams(-1, -1));

            // Outside a campaign: just the game's logo (the app's icon if the game's art is missing).
            ImageView idle = new ImageView(getContext());
            if (theme.logo != null) idle.setImageBitmap(theme.logo);
            else idle.setImageResource(getContext().getApplicationInfo().icon);
            idle.setAdjustViewBounds(true);
            idle.setAlpha(0.6f);
            this.idle = idle;
            root.addView(idle, new FrameLayout.LayoutParams(theme.dp(320), -2, Gravity.CENTER));
            setContentView(root);
        }

        void update(JSONObject msg) {
            boolean campaign = msg.optBoolean("campaign");
            content.setVisibility(campaign ? View.VISIBLE : View.GONE);
            idle.setVisibility(campaign ? View.GONE : View.VISIBLE);
            // INVISIBLE, not GONE: the box keeps its size, which the agent sizes the log to.
            widgets[CONSOLE].setVisibility(msg.optBoolean("console", true) ? View.VISIBLE : View.INVISIBLE);
            widgets[ROSTER].highlight(msg.optJSONArray("selectedCard"));
            JSONObject sheet = msg.optJSONObject("sheet");
            boolean place = sheet != null && sheet.has("place");
            header.show(sheet);
            widgets[SHEET].setVisibility(sheet == null || place ? View.INVISIBLE : View.VISIBLE); // keeps its size
            widgets[PLACE].setVisibility(place ? View.VISIBLE : View.GONE);
            if (place) header.showStatus(false);
            if (sheet != null) sheetView = sheet.optInt("view");
        }

        void frame(int id, Bitmap frame) {
            if (id >= 0 && id < widgets.length) widgets[id].show(frame);
        }
    }

    /** A game widget's pixels, scaled to fit; a tap is sent as fractions of the image. */
    private final class FrameView extends View {
        private final int id;
        private float alignX, alignY;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF dst = new RectF();
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private Bitmap frame;
        private final Rect src = new Rect();
        private float lastY;
        private boolean dragged;
        private float[] mark;
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        {
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(getResources().getDisplayMetrics().density * 3);
            outline.setColor(SELECTED);
        }

        FrameView(Context ctx, int id) {
            super(ctx);
            this.id = id;
        }

        private float scale() {
            return Math.min(MAX_SCALE, Math.min(getWidth() / (float) src.width(), getHeight() / (float) src.height()));
        }


        private float clamp(float f) {
            return Math.max(0f, Math.min(1f, f));
        }


        /** Where the image sits in a box larger than it: 0 start, 0.5 centre, 1 end. */
        void align(float x, float y) {
            alignX = x;
            alignY = y;
        }

        /** Outlines part of the image, e.g. the selected hero's card: fractions {l, t, r, b}, or null. */
        void highlight(JSONArray f) {
            float[] next = f == null || f.length() < 4 ? null
                    : new float[]{(float) f.optDouble(0), (float) f.optDouble(1), (float) f.optDouble(2), (float) f.optDouble(3)};
            if (!java.util.Arrays.equals(next, mark)) {
                mark = next;
                invalidate();
            }
        }

        void show(Bitmap b) {
            Bitmap old = frame;
            frame = b;
            if (b == null) dst.setEmpty();
            else src.set(0, 0, b.getWidth(), b.getHeight());
            invalidate();
            if (old != null && old != b) old.recycle();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (frame == null) return;
            float scale = scale(), w = src.width() * scale, h = src.height() * scale;
            float x = (getWidth() - w) * alignX, y = (getHeight() - h) * alignY;
            dst.set(x, y, x + w, y + h);
            c.drawBitmap(frame, src, dst, paint);
            if (mark != null) {
                // Left and right from the image: the game draws inactive cards off their reported slot.
                int row = Math.round((mark[1] + mark[3]) / 2 * frame.getHeight());
                int[] px = new int[frame.getWidth()];
                frame.getPixels(px, 0, px.length, 0, Math.max(0, Math.min(frame.getHeight() - 1, row)), px.length, 1);
                int x0 = 0, x1 = px.length - 1;
                while (x0 < x1 && (px[x0] >>> 24) < 128) x0++;
                while (x1 > x0 && (px[x1] >>> 24) < 128) x1--;
                float sx = dst.width() / frame.getWidth(), pad = outline.getStrokeWidth();
                c.drawRoundRect(dst.left + x0 * sx - pad, dst.top + mark[1] * dst.height() - pad,
                        dst.left + (x1 + 1) * sx + pad, dst.top + mark[3] * dst.height() + pad, pad * 2, pad * 2, outline);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (id == STATUS) { // just a card to read: any tap closes it
                if (e.getActionMasked() == MotionEvent.ACTION_UP) panel.header.showStatus(false);
                return true;
            }
            if (id == SHEET) { // the sheet gets the whole gesture: its lists scroll, its entries press
                int action = e.getActionMasked() == MotionEvent.ACTION_DOWN ? 0 : e.getActionMasked() == MotionEvent.ACTION_MOVE ? 1
                        : e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL ? 2 : -1;
                if (action == 0) {
                    lastY = e.getY();
                    dragged = false;
                } else if (action == 1 && Math.abs(e.getY() - lastY) > slop) {
                    dragged = true;
                }
                if (action >= 0 && frame != null && dst.width() > 0)
                    send(String.format(Locale.US, "{\"touch\":[%d,%d,%.5f,%.5f]}", id, action,
                            clamp((e.getX() - dst.left) / dst.width()),
                            clamp((src.top + (e.getY() - dst.top) / dst.height() * src.height()) / frame.getHeight())));
                // A tap on a list entry opens its detail, full width; Back in the header returns.
                if (e.getActionMasked() == MotionEvent.ACTION_UP && !dragged && sheetView == 0) send("{\"sheetView\":1}");
                return true;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastY = e.getY();
                    dragged = false;
                    break;
                case MotionEvent.ACTION_MOVE: // only the log scrolls; elsewhere a drag is just a sloppy tap
                    if (id == CONSOLE && dst.height() > 0 && (dragged || Math.abs(e.getY() - lastY) > slop)) {
                        dragged = true;
                        send(String.format(Locale.US, "{\"scroll\":[%.5f]}", (e.getY() - lastY) / dst.height()));
                        lastY = e.getY();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (!dragged && dst.contains(e.getX(), e.getY()))
                        send(String.format(Locale.US, "{\"tap\":[%d,%.5f,%.5f]}", id, (e.getX() - dst.left) / dst.width(),
                                (src.top + (e.getY() - dst.top) / dst.height() * src.height()) / frame.getHeight()));
                    break;
            }
            return true;
        }
    }

    /** One row over the sheet: hero name, then a dropdown for the tab, or Back while a detail is open. */
    private final class SheetHeader extends LinearLayout {
        private final WildermythTheme theme;
        private final TextView name;
        private final Button tab, back;
        private JSONArray labels;

        SheetHeader(Context ctx, WildermythTheme theme) {
            super(ctx);
            this.theme = theme;
            setGravity(Gravity.CENTER_VERTICAL);
            name = new TextView(ctx);
            name.setTypeface(theme.fontBold);
            name.setTextSize(24);
            name.setTextColor(WildermythTheme.TEXT);
            name.setSingleLine(true);
            name.setOnClickListener(v -> showStatus(panel.widgets[STATUS].getVisibility() != VISIBLE));
            // While the hero's card is open, the name looks held down: framed in the selection blue.
            android.graphics.drawable.GradientDrawable open = new android.graphics.drawable.GradientDrawable();
            open.setColor(0x3355B5F5);
            open.setStroke(theme.dp(2), SELECTED);
            open.setCornerRadius(theme.dp(8));
            // Closed: a faint frame, so the name reads as something to tap. A place's name is just a title.
            android.graphics.drawable.GradientDrawable closed = new android.graphics.drawable.GradientDrawable();
            closed.setStroke(theme.dp(1), 0x55F0E2C8);
            closed.setCornerRadius(theme.dp(8));
            android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
            bg.addState(new int[]{-android.R.attr.state_enabled}, new android.graphics.drawable.ColorDrawable(0));
            bg.addState(new int[]{android.R.attr.state_activated}, open);
            bg.addState(new int[]{}, closed);
            name.setBackground(bg);
            name.setPadding(theme.dp(10), theme.dp(2), theme.dp(10), theme.dp(2));
            addView(name, new LayoutParams(0, -2, 1));
            tab = button(this::pickTab);
            LayoutParams tp = new LayoutParams(theme.dp(150), -1);
            tp.leftMargin = theme.dp(24); // apart from the name
            addView(tab, tp);
            back = button(() -> send("{\"sheetView\":0}"));
            back.setText("Back");
            LayoutParams bp = new LayoutParams(theme.dp(150), -1);
            bp.leftMargin = theme.dp(24);
            addView(back, bp);
            setVisibility(INVISIBLE);
        }

        private Button button(Runnable action) {
            Button b = new Button(getContext());
            theme.style(b);
            b.setTextSize(18);
            b.setPadding(theme.dp(6), 0, theme.dp(6), 0);
            b.setFocusable(false);
            b.setOnClickListener(v -> action.run());
            return b;
        }

        /** The tabs as a list of large rows, opened from the dropdown button. */
        private void pickTab() {
            if (labels == null) return;
            String[] items = new String[labels.length()];
            for (int i = 0; i < items.length; i++) items[i] = labels.optString(i);
            ListPopupWindow list = new ListPopupWindow(getContext());
            list.setAnchorView(tab);
            list.setWidth(theme.dp(260));
            list.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xF0201812));
            list.setAdapter(new android.widget.ArrayAdapter<String>(getContext(), 0, items) {
                @Override
                public View getView(int i, View reuse, android.view.ViewGroup parent) {
                    TextView t = reuse instanceof TextView ? (TextView) reuse : new TextView(getContext());
                    t.setText(items[i]);
                    t.setTypeface(theme.font);
                    t.setTextSize(22);
                    t.setTextColor(WildermythTheme.TEXT);
                    t.setPadding(theme.dp(18), theme.dp(14), theme.dp(18), theme.dp(14));
                    return t;
                }
            });
            list.setOnItemClickListener((parent, v, i, id) -> {
                send("{\"sheetTab\":" + i + "}");
                list.dismiss();
            });
            list.show();
        }

        /** Opens or closes the hero's card (the HUD's selection tooltip) over the sheet. */
        void showStatus(boolean open) {
            panel.widgets[STATUS].setVisibility(open ? VISIBLE : GONE);
            name.setActivated(open);
            if (name.isEnabled()) name.setText(heroName + (open ? "  ▴" : "  ▾")); // more to see, or open now
        }

        private String heroName = "";

        void show(JSONObject sheet) {
            setVisibility(sheet == null ? INVISIBLE : VISIBLE);
            if (sheet == null) return;
            if (sheet.has("place")) { // a tile, site or threat: what it is; its card below has its name
                name.setEnabled(false);
                name.setText(sheet.optString("place"));
                name.setTextColor(SELECTED);
                tab.setVisibility(GONE);
                back.setVisibility(GONE);
                return;
            }
            name.setEnabled(true);
            name.setTextColor(WildermythTheme.TEXT); // blue is for a selected place; a hero has the roster outline
            heroName = sheet.optString("name");
            name.setText(heroName + (name.isActivated() ? "  ▴" : "  ▾"));
            labels = sheet.optJSONArray("tabs");
            tab.setText((labels == null ? "" : labels.optString(sheet.optInt("tab"))) + "  ▾");
            // In a detail, Back takes the dropdown's place: tabs are switched from the list.
            boolean detail = sheet.optInt("view") == 1;
            back.setVisibility(detail ? VISIBLE : GONE);
            tab.setVisibility(detail ? GONE : VISIBLE);
        }
    }
}
