package net.kdt.pojavlaunch.customcontrols.mouse;

import android.view.GestureDetector;
import android.view.MotionEvent;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.SingleTapConfirm;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.lwjgl.glfw.CallbackBridge;

public class InGUIEventProcessor implements TouchEventProcessor {
    public static final float FINGER_SCROLL_THRESHOLD = Tools.dpToPx(6);
    public static final float FINGER_STILL_THRESHOLD = Tools.dpToPx(5);
    // Wildermyth: two fingers pan (middle drag) and pinch zooms (wheel), a long press right-clicks.
    public static boolean sWildermyth;
    // The game zooms 5% per wheel notch, so a notch per 5% of finger spread tracks the fingers.
    private static final double PINCH_NOTCH = Math.log(1.05);
    private static final long LONG_PRESS_MS = 500;

    private final PointerTracker mTracker = new PointerTracker();
    private final TapDetector mSingleTapDetector;
    private AbstractTouchpad mTouchpad;
    private boolean mIsMouseDown = false;
    private float mStartX, mStartY;
    private final Scroller mScroller = new Scroller(FINGER_SCROLL_THRESHOLD);
    private boolean mTwoFinger, mMiddleDown;
    private float mPinchSpan;

    public InGUIEventProcessor() {
        mSingleTapDetector = new TapDetector(1, TapDetector.DETECTION_METHOD_BOTH);
    }

    @Override
    public boolean processTouchEvent(MotionEvent motionEvent) {
        if (sWildermyth && twoFingers(motionEvent)) return true;
        boolean singleTap = mSingleTapDetector.onTouchEvent(motionEvent);

        switch (motionEvent.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTracker.startTracking(motionEvent);
                if(!touchpadDisplayed()) {
                    sendTouchCoordinates(motionEvent.getX(), motionEvent.getY());

                    // disabled gestures means no scrolling possible, send gesture early
                    if (LauncherPreferences.PREF_DISABLE_GESTURES) enableMouse();
                    else setGestureStart(motionEvent);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                int pointerCount = motionEvent.getPointerCount();
                int pointerIndex = mTracker.trackEvent(motionEvent);
                if(pointerCount == 1 || LauncherPreferences.PREF_DISABLE_GESTURES) {
                    if(touchpadDisplayed()) {
                        mTouchpad.applyMotionVector(mTracker.getMotionVector());
                    } else {
                        float mainPointerX = motionEvent.getX(pointerIndex);
                        float mainPointerY = motionEvent.getY(pointerIndex);
                        sendTouchCoordinates(mainPointerX, mainPointerY);

                        if(!mIsMouseDown) {
                            if(!hasGestureStarted()) setGestureStart(motionEvent);
                            if(!LeftClickGesture.isFingerStill(mStartX, mStartY, FINGER_STILL_THRESHOLD))
                                enableMouse();
                        }

                    }
                } else mScroller.performScroll(mTracker.getMotionVector());
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                mScroller.resetScrollOvershoot();
                mTracker.cancelTracking();

                // Handle single tap on gestures
                if((!LauncherPreferences.PREF_DISABLE_GESTURES || touchpadDisplayed()) && !mIsMouseDown && singleTap) {
                    CallbackBridge.putMouseEventWithCoords(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, CallbackBridge.mouseX, CallbackBridge.mouseY);
                } else if (sWildermyth && !mIsMouseDown && !touchpadDisplayed()
                        && motionEvent.getEventTime() - motionEvent.getDownTime() >= LONG_PRESS_MS) {
                    CallbackBridge.putMouseEventWithCoords(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, CallbackBridge.mouseX, CallbackBridge.mouseY);
                }

                if(mIsMouseDown) disableMouse();
                resetGesture();
        }


        return true;
    }

    /** Takes the gesture once a second finger lands, until every finger lifts; true while it owns the event. */
    private boolean twoFingers(MotionEvent e) {
        int action = e.getActionMasked();
        if (!mTwoFinger) {
            if (action != MotionEvent.ACTION_POINTER_DOWN || touchpadDisplayed()) return false;
            if (mIsMouseDown) disableMouse();
            mTwoFinger = true;
            mPinchSpan = Math.max(span(e), 1);
            sendCentroid(e);
            CallbackBridge.sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE, true);
            mMiddleDown = true;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && mMiddleDown && e.getPointerCount() >= 2) {
            sendCentroid(e);
            float span = Math.max(span(e), 1);
            int notches = (int) (Math.log(span / mPinchSpan) / PINCH_NOTCH);
            if (notches != 0) {
                CallbackBridge.sendScroll(0, notches);
                mPinchSpan *= (float) Math.pow(1.05, notches);
            }
        } else if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            // One finger left is not a new drag: the gesture ends with the last finger.
            if (mMiddleDown) CallbackBridge.sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE, false);
            mMiddleDown = false;
            if (action != MotionEvent.ACTION_POINTER_UP) {
                mTwoFinger = false;
                mTracker.cancelTracking();
                resetGesture();
            }
        }
        return true;
    }

    private void sendCentroid(MotionEvent e) {
        sendTouchCoordinates((e.getX(0) + e.getX(1)) / 2, (e.getY(0) + e.getY(1)) / 2);
    }

    private static float span(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private boolean touchpadDisplayed() {
        return mTouchpad != null && mTouchpad.getDisplayState();
    }

    public void setAbstractTouchpad(AbstractTouchpad touchpad) {
        mTouchpad = touchpad;
    }

    private void sendTouchCoordinates(float x, float y) {
        CallbackBridge.sendCursorPos( x * LauncherPreferences.PREF_SCALE_FACTOR, y * LauncherPreferences.PREF_SCALE_FACTOR);
    }

    private void enableMouse() {
        CallbackBridge.sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
        mIsMouseDown = true;
    }

    private void disableMouse() {
        CallbackBridge.sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
        mIsMouseDown = false;
    }

    private void setGestureStart(MotionEvent event) {
        mStartX = event.getX() * LauncherPreferences.PREF_SCALE_FACTOR;
        mStartY = event.getY() * LauncherPreferences.PREF_SCALE_FACTOR;
    }

    private void resetGesture() {
        mStartX = mStartY = -1;
    }

    private boolean hasGestureStarted() {
        return mStartX != -1 || mStartY != -1;
    }

    @Override
    public void cancelPendingActions() {
        mScroller.resetScrollOvershoot();
        disableMouse();
        if (mMiddleDown) CallbackBridge.sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE, false);
        mMiddleDown = mTwoFinger = false;
    }
}
