package net.kdt.pojavlaunch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.IntConsumer;

/**
 * The campaign map, drawn natively from the game's tile outlines: crisp at any zoom and cheap to redraw.
 * Pinch to zoom, drag to pan, tap a tile to select it (the game flies its camera there).
 */
final class WildermythOverviewMap extends View {
    private static final int HIDDEN = 0xFF1C1610, SELECTED = 0xFF55B5F5;

    private Path[] tiles = new Path[0];
    private float[][] polys = new float[0][];
    private int[] colors = new int[0];
    private String vis = "";
    private int selected = -1;
    private float[] centers = new float[0];
    private JSONArray marks = new JSONArray();
    private float[] frame;
    private final RectF world = new RectF();
    private final Matrix view = new Matrix(), inverse = new Matrix();
    private float zoom = 1, panX, panY;
    private final IntConsumer onTap;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
            outline = new Paint(Paint.ANTI_ALIAS_FLAG), framePaint = new Paint(Paint.ANTI_ALIAS_FLAG),
            markFill = new Paint(Paint.ANTI_ALIAS_FLAG), markRing = new Paint(Paint.ANTI_ALIAS_FLAG),
            count = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG),
            labelShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;

    WildermythOverviewMap(Context ctx, IntConsumer onTap) {
        super(ctx);
        this.onTap = onTap;
        float d = ctx.getResources().getDisplayMetrics().density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(0x66000000);
        edge.setStrokeWidth(d);
        outline.setStyle(Paint.Style.STROKE);
        outline.setColor(SELECTED);
        outline.setStrokeWidth(3 * d);
        outline.setStrokeJoin(Paint.Join.ROUND);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setColor(0xCCF0E2C8);
        framePaint.setStrokeWidth(2 * d);
        framePaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8 * d, 5 * d}, 0));
        markRing.setStyle(Paint.Style.STROKE);
        markRing.setColor(0xFF15100C);
        markRing.setStrokeWidth(1.5f * d);
        count.setTextAlign(Paint.Align.CENTER);
        count.setTextSize(12 * d);
        count.setFakeBoldText(true);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(12 * d);
        labelShadow.set(label);
        labelShadow.setStyle(Paint.Style.STROKE);
        labelShadow.setStrokeWidth(3 * d);
        labelShadow.setColor(0xCC15100C);
        pinch = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector g) {
                float next = Math.max(1, Math.min(6, zoom * g.getScaleFactor()));
                float k = next / zoom; // keep the point under the fingers still
                panX = g.getFocusX() - (g.getFocusX() - panX) * k;
                panY = g.getFocusY() - (g.getFocusY() - panY) * k;
                zoom = next;
                clampPan();
                invalidate();
                return true;
            }
        });
        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                panX -= dx;
                panY -= dy;
                clampPan();
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                int tile = tileAt(e.getX(), e.getY());
                if (tile >= 0 && vis.length() > tile && vis.charAt(tile) != 'h') onTap.accept(tile);
                return true;
            }
        });
    }

    /** The map's geometry: {"tiles":[{"p":[x,y,...],"c":rgb}, ...]}, in world units (y up). */
    void geometry(JSONObject map) {
        JSONArray list = map.optJSONArray("tiles");
        int n = list == null ? 0 : list.length();
        tiles = new Path[n];
        centers = new float[2 * n];
        polys = new float[n][];
        colors = new int[n];
        world.setEmpty();
        for (int i = 0; i < n; i++) {
            JSONObject t = list.optJSONObject(i);
            JSONArray p = t.optJSONArray("p");
            float[] pts = new float[p.length()];
            Path path = new Path();
            for (int k = 0; k + 1 < pts.length; k += 2) {
                pts[k] = (float) p.optDouble(k);
                pts[k + 1] = -(float) p.optDouble(k + 1); // world y is up, the screen's is down
                if (k == 0) path.moveTo(pts[0], pts[1]);
                else path.lineTo(pts[k], pts[k + 1]);
                if (world.isEmpty() && k == 0) world.set(pts[0], pts[1], pts[0], pts[1]);
                else world.union(pts[k], pts[k + 1]);
            }
            path.close();
            for (int k = 0; k + 1 < pts.length; k += 2) {
                centers[2 * i] += pts[k] * 2 / pts.length;
                centers[2 * i + 1] += pts[k + 1] * 2 / pts.length;
            }
            tiles[i] = path;
            polys[i] = pts;
            colors[i] = 0xFF000000 | t.optInt("c");
        }
        invalidate();
    }

    /** What the player sees ("h"/"p"/"v" per tile) and which tile is selected (-1 for none). */
    void state(JSONObject s) {
        vis = s.optString("vis");
        selected = s.optInt("sel", -1);
        marks = s.optJSONArray("marks") != null ? s.optJSONArray("marks") : new JSONArray();
        JSONArray v = s.optJSONArray("view");
        frame = null;
        if (v != null && v.length() == 8) {
            frame = new float[8];
            for (int k = 0; k < 8; k++) frame[k] = (float) (k % 2 == 0 ? v.optDouble(k) : -v.optDouble(k));
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (tiles.length == 0 || world.isEmpty()) return;
        fit();
        c.save();
        c.concat(view);
        edge.setStrokeWidth(getResources().getDisplayMetrics().density / scaleOf(view));
        outline.setStrokeWidth(3 * getResources().getDisplayMetrics().density / scaleOf(view));
        for (int i = 0; i < tiles.length; i++) {
            char v = i < vis.length() ? vis.charAt(i) : 'h';
            fill.setColor(v == 'h' ? HIDDEN : v == 'p' ? dim(colors[i]) : colors[i]);
            c.drawPath(tiles[i], fill);
            if (v != 'h') c.drawPath(tiles[i], edge);
        }
        if (selected >= 0 && selected < tiles.length) c.drawPath(tiles[selected], outline);
        c.restore();
        drawFrame(c);
        drawMarks(c);
    }

    /** What the main screen is looking at, as a dashed frame. */
    private void drawFrame(Canvas c) {
        if (frame == null) return;
        float[] p = frame.clone();
        view.mapPoints(p);
        Path path = new Path();
        path.moveTo(p[0], p[1]);
        for (int k = 2; k < 8; k += 2) path.lineTo(p[k], p[k + 1]);
        path.close();
        c.drawPath(path, framePaint);
    }

    /** Sites, heroes and threats, at a fixed size on screen whatever the zoom; names once zoomed in. */
    private void drawMarks(Canvas c) {
        float d = getResources().getDisplayMetrics().density, r = 7 * d;
        boolean names = zoom >= 1.8f;
        for (int m = 0; m < marks.length(); m++) {
            JSONObject k = marks.optJSONObject(m);
            int i = k.optInt("i", -1);
            if (i < 0 || 2 * i + 1 >= centers.length) continue;
            float[] p = {centers[2 * i], centers[2 * i + 1]};
            view.mapPoints(p);
            float x = p[0], y = p[1];
            boolean hostile = k.optInt("x") == 1;
            if (hostile) { // a site with a threat lurking: what you go out to fight
                markFill.setColor(0xFFC8553D);
                c.drawCircle(x, y, r, markFill);
                c.drawCircle(x, y, r, markRing);
            }
            if (k.has("s")) { // most tiles have a site: only their names, and only zoomed in
                if (names) {
                    label.setColor(0xFFF0E2C8);
                    c.drawText(k.optString("s"), x, y + r + label.getTextSize() + 2 * d, labelShadow);
                    c.drawText(k.optString("s"), x, y + r + label.getTextSize() + 2 * d, label);
                }
            }
            if (k.has("h")) { // the party: a blue coin with how many heroes are there
                float hx = x - (hostile ? 2.2f * r : 0), hy = y - (hostile ? 0.6f * r : 0);
                markFill.setColor(SELECTED);
                c.drawCircle(hx, hy, r * 1.25f, markFill);
                c.drawCircle(hx, hy, r * 1.25f, markRing);
                count.setColor(0xFF15100C);
                c.drawText(String.valueOf(k.optInt("h")), hx, hy + count.getTextSize() * 0.36f, count);
            }
            if (k.has("t") && !hostile) { // a threat on the move (one in a site is that site's red dot)
                float tx = x, ty = y;
                Path dmd = new Path();
                dmd.moveTo(tx, ty - r * 1.3f);
                dmd.lineTo(tx + r * 1.1f, ty);
                dmd.lineTo(tx, ty + r * 1.3f);
                dmd.lineTo(tx - r * 1.1f, ty);
                dmd.close();
                markFill.setColor(0xFFB0302A);
                c.drawPath(dmd, markFill);
                c.drawPath(dmd, markRing);
            }
        }
    }

    /** World to screen: the whole map fitted to the view, then the user's zoom and pan. */
    private void fit() {
        float s = Math.min(getWidth() / world.width(), getHeight() / world.height()) * 0.95f;
        view.reset();
        view.postTranslate(-world.centerX(), -world.centerY());
        view.postScale(s * zoom, s * zoom);
        view.postTranslate(getWidth() / 2f + panX, getHeight() / 2f + panY);
        view.invert(inverse);
    }

    /** Keeps some of the map on screen however far it is dragged. */
    private void clampPan() {
        float maxX = getWidth() * zoom / 2, maxY = getHeight() * zoom / 2;
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
    }

    private int tileAt(float x, float y) {
        float[] p = {x, y};
        inverse.mapPoints(p);
        for (int i = 0; i < polys.length; i++) if (contains(polys[i], p[0], p[1])) return i;
        return -1;
    }

    /** Even-odd ray cast. */
    private static boolean contains(float[] poly, float x, float y) {
        boolean in = false;
        for (int i = 0, j = poly.length - 2; i < poly.length; j = i, i += 2) {
            float xi = poly[i], yi = poly[i + 1], xj = poly[j], yj = poly[j + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    private static int dim(int c) {
        return Color.rgb(Color.red(c) * 45 / 100, Color.green(c) * 45 / 100, Color.blue(c) * 45 / 100);
    }

    private static float scaleOf(Matrix m) {
        float[] v = new float[9];
        m.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e);
        if (!pinch.isInProgress()) gestures.onTouchEvent(e);
        return true;
    }
}
