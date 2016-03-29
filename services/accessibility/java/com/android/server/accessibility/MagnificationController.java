/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.accessibility;

import com.android.internal.R;
import com.android.internal.os.SomeArgs;
import com.android.server.LocalServices;

import android.animation.ObjectAnimator;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.MathUtils;
import android.util.Property;
import android.util.Slog;
import android.view.MagnificationSpec;
import android.view.View;
import android.view.WindowManagerInternal;
import android.view.animation.DecelerateInterpolator;

import java.util.Locale;

/**
 * This class is used to control and query the state of display magnification
 * from the accessibility manager and related classes. It is responsible for
 * holding the current state of magnification and animation, and it handles
 * communication between the accessibility manager and window manager.
 */
class MagnificationController {
    private static final String LOG_TAG = "MagnificationController";

    private static final boolean DEBUG_SET_MAGNIFICATION_SPEC = false;

    private static final int DEFAULT_SCREEN_MAGNIFICATION_AUTO_UPDATE = 1;

    private static final float DEFAULT_MAGNIFICATION_SCALE = 2.0f;

    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 5.0f;

    /**
     * The minimum scaling factor that can be persisted to secure settings.
     * This must be > 1.0 to ensure that magnification is actually set to an
     * enabled state when the scaling factor is restored from settings.
     */
    private static final float MIN_PERSISTED_SCALE = 2.0f;

    private final Object mLock = new Object();

    /**
     * The current magnification spec. If an animation is running, this
     * reflects the end state.
     */
    private final MagnificationSpec mCurrentMagnificationSpec = MagnificationSpec.obtain();

    private final Region mMagnifiedRegion = Region.obtain();
    private final Region mAvailableRegion = Region.obtain();
    private final Rect mMagnifiedBounds = new Rect();

    private final Rect mTempRect = new Rect();
    private final Rect mTempRect1 = new Rect();

    private final AccessibilityManagerService mAms;
    private final ContentResolver mContentResolver;

    private final ScreenStateObserver mScreenStateObserver;
    private final WindowStateObserver mWindowStateObserver;

    private final SpecAnimationBridge mSpecAnimationBridge;

    private int mUserId;

    public MagnificationController(Context context, AccessibilityManagerService ams) {
        mAms = ams;
        mContentResolver = context.getContentResolver();
        mScreenStateObserver = new ScreenStateObserver(context, this);
        mWindowStateObserver = new WindowStateObserver(context, this);
        mSpecAnimationBridge = new SpecAnimationBridge(context);
    }

    /**
     * Registers magnification-related observers.
     */
    public void register() {
        mScreenStateObserver.register();
        mWindowStateObserver.register();

        // Obtain initial state.
        mWindowStateObserver.getRegions(mMagnifiedRegion, mAvailableRegion);
        mMagnifiedRegion.getBounds(mMagnifiedBounds);
    }

    /**
     * Unregisters magnification-related observers.
     */
    public void unregister() {
        mSpecAnimationBridge.cancel();

        mScreenStateObserver.unregister();
        mWindowStateObserver.unregister();
    }

    /**
     * @return {@code true} if magnification is active, e.g. the scale
     *         is > 1, {@code false} otherwise
     */
    public boolean isMagnifying() {
        return mCurrentMagnificationSpec.scale > 1.0f;
    }

    /**
     * Sets the magnified and available regions.
     *
     * @param magnified the magnified region
     * @param available the region available for magnification
     * @param updateSpec {@code true} to update the scale and center based on
     *                   the region bounds, {@code false} to leave them as-is
     */
    public void setMagnifiedRegion(Region magnified, Region available, boolean updateSpec) {
        synchronized (mLock) {
            mMagnifiedRegion.set(magnified);
            mMagnifiedRegion.getBounds(mMagnifiedBounds);
            mAvailableRegion.set(available);

            final MagnificationSpec sentSpec = mSpecAnimationBridge.mSentMagnificationSpec;
            final float scale = sentSpec.scale;
            final float offsetX = sentSpec.offsetX;
            final float offsetY = sentSpec.offsetY;

            // Compute the new center and update spec as needed.
            final float centerX = (mMagnifiedBounds.width() / 2.0f
                    + mMagnifiedBounds.left - offsetX) / scale;
            final float centerY = (mMagnifiedBounds.height() / 2.0f
                    + mMagnifiedBounds.top - offsetY) / scale;
            if (updateSpec) {
                setScaleAndCenter(scale, centerX, centerY, false);
            } else {
                mAms.onMagnificationStateChanged();
                mAms.notifyMagnificationChanged(mMagnifiedRegion, scale, centerX, centerY);
            }
        }
    }

    /**
     * Returns whether the magnified region contains the specified
     * screen-relative coordinates.
     *
     * @param x the screen-relative X coordinate to check
     * @param y the screen-relative Y coordinate to check
     * @return {@code true} if the coordinate is contained within the
     *         magnified region, or {@code false} otherwise
     */
    public boolean magnifiedRegionContains(float x, float y) {
        synchronized (mLock) {
            return mMagnifiedRegion.contains((int) x, (int) y);
        }
    }

    /**
     * Returns whether the region available for magnification contains the
     * specified screen-relative coordinates.
     *
     * @param x the screen-relative X coordinate to check
     * @param y the screen-relative Y coordinate to check
     * @return {@code true} if the coordinate is contained within the
     *         region available for magnification, or {@code false} otherwise
     */
    private boolean availableRegionContains(float x, float y) {
        synchronized (mLock) {
            return mAvailableRegion.contains((int) x, (int) y);
        }
    }

    /**
     * Populates the specified rect with the screen-relative bounds of the
     * magnified region. If magnification is not enabled, the returned
     * bounds will be empty.
     *
     * @param outBounds rect to populate with the bounds of the magnified
     *                  region
     */
    public void getMagnifiedBounds(@NonNull Rect outBounds) {
        synchronized (mLock) {
            outBounds.set(mMagnifiedBounds);
        }
    }

    /**
     * Populates the specified region with the screen-relative magnified
     * region. If magnification is not enabled, then the returned region
     * will be empty.
     *
     * @param outRegion the region to populate
     */
    public void getMagnifiedRegion(@NonNull Region outRegion) {
        synchronized (mLock) {
            outRegion.set(mMagnifiedRegion);
        }
    }

    /**
     * Returns the magnification scale. If an animation is in progress,
     * this reflects the end state of the animation.
     *
     * @return the scale
     */
    public float getScale() {
        return mCurrentMagnificationSpec.scale;
    }

    /**
     * Returns the X offset of the magnification viewport. If an animation
     * is in progress, this reflects the end state of the animation.
     *
     * @return the X offset
     */
    public float getOffsetX() {
        return mCurrentMagnificationSpec.offsetX;
    }


    /**
     * Returns the screen-relative X coordinate of the center of the
     * magnification viewport.
     *
     * @return the X coordinate
     */
    public float getCenterX() {
        synchronized (mLock) {
            return  (mMagnifiedBounds.width() / 2.0f
                    + mMagnifiedBounds.left - getOffsetX()) / getScale();
        }
    }

    /**
     * Returns the Y offset of the magnification viewport. If an animation
     * is in progress, this reflects the end state of the animation.
     *
     * @return the Y offset
     */
    public float getOffsetY() {
        return mCurrentMagnificationSpec.offsetY;
    }

    /**
     * Returns the screen-relative Y coordinate of the center of the
     * magnification viewport.
     *
     * @return the Y coordinate
     */
    public float getCenterY() {
        synchronized (mLock) {
            return (mMagnifiedBounds.height() / 2.0f
                    + mMagnifiedBounds.top - getOffsetY()) / getScale();
        }
    }

    /**
     * Returns the scale currently used by the window manager. If an
     * animation is in progress, this reflects the current state of the
     * animation.
     *
     * @return the scale currently used by the window manager
     */
    public float getSentScale() {
        return mSpecAnimationBridge.mSentMagnificationSpec.scale;
    }

    /**
     * Returns the X offset currently used by the window manager. If an
     * animation is in progress, this reflects the current state of the
     * animation.
     *
     * @return the X offset currently used by the window manager
     */
    public float getSentOffsetX() {
        return mSpecAnimationBridge.mSentMagnificationSpec.offsetX;
    }

    /**
     * Returns the Y offset currently used by the window manager. If an
     * animation is in progress, this reflects the current state of the
     * animation.
     *
     * @return the Y offset currently used by the window manager
     */
    public float getSentOffsetY() {
        return mSpecAnimationBridge.mSentMagnificationSpec.offsetY;
    }

    /**
     * Resets the magnification scale and center, optionally animating the
     * transition.
     *
     * @param animate {@code true} to animate the transition, {@code false}
     *                to transition immediately
     * @return {@code true} if the magnification spec changed, {@code false} if
     *         the spec did not change
     */
    public boolean reset(boolean animate) {
        synchronized (mLock) {
            return resetLocked(animate);
        }
    }

    private boolean resetLocked(boolean animate) {
        final MagnificationSpec spec = mCurrentMagnificationSpec;
        final boolean changed = !spec.isNop();
        if (changed) {
            spec.clear();
        }

        mSpecAnimationBridge.updateSentSpec(spec, animate);
        return changed;
    }

    /**
     * Scales the magnified region around the specified pivot point,
     * optionally animating the transition. If animation is disabled, the
     * transition is immediate.
     *
     * @param scale the target scale, must be >= 1
     * @param pivotX the screen-relative X coordinate around which to scale
     * @param pivotY the screen-relative Y coordinate around which to scale
     * @param animate {@code true} to animate the transition, {@code false}
     *                to transition immediately
     * @return {@code true} if the magnification spec changed, {@code false} if
     *         the spec did not change
     */
    public boolean setScale(float scale, float pivotX, float pivotY, boolean animate) {
        synchronized (mLock) {
            // Constrain scale immediately for use in the pivot calculations.
            scale = MathUtils.constrain(scale, MIN_SCALE, MAX_SCALE);

            final Rect viewport = mTempRect;
            mMagnifiedRegion.getBounds(viewport);
            final MagnificationSpec spec = mCurrentMagnificationSpec;
            final float oldScale = spec.scale;
            final float oldCenterX = (viewport.width() / 2.0f - spec.offsetX) / oldScale;
            final float oldCenterY = (viewport.height() / 2.0f - spec.offsetY) / oldScale;
            final float normPivotX = (pivotX - spec.offsetX) / oldScale;
            final float normPivotY = (pivotY - spec.offsetY) / oldScale;
            final float offsetX = (oldCenterX - normPivotX) * (oldScale / scale);
            final float offsetY = (oldCenterY - normPivotY) * (oldScale / scale);
            final float centerX = normPivotX + offsetX;
            final float centerY = normPivotY + offsetY;
            return setScaleAndCenterLocked(scale, centerX, centerY, animate);
        }
    }

    /**
     * Sets the center of the magnified region, optionally animating the
     * transition. If animation is disabled, the transition is immediate.
     *
     * @param centerX the screen-relative X coordinate around which to
     *                center
     * @param centerY the screen-relative Y coordinate around which to
     *                center
     * @param animate {@code true} to animate the transition, {@code false}
     *                to transition immediately
     * @return {@code true} if the magnification spec changed, {@code false} if
     *         the spec did not change
     */
    public boolean setCenter(float centerX, float centerY, boolean animate) {
        synchronized (mLock) {
            return setScaleAndCenterLocked(Float.NaN, centerX, centerY, animate);
        }
    }

    /**
     * Sets the scale and center of the magnified region, optionally
     * animating the transition. If animation is disabled, the transition
     * is immediate.
     *
     * @param scale the target scale, or {@link Float#NaN} to leave unchanged
     * @param centerX the screen-relative X coordinate around which to
     *                center and scale, or {@link Float#NaN} to leave unchanged
     * @param centerY the screen-relative Y coordinate around which to
     *                center and scale, or {@link Float#NaN} to leave unchanged
     * @param animate {@code true} to animate the transition, {@code false}
     *                to transition immediately
     * @return {@code true} if the magnification spec changed, {@code false} if
     *         the spec did not change
     */
    public boolean setScaleAndCenter(float scale, float centerX, float centerY, boolean animate) {
        synchronized (mLock) {
            return setScaleAndCenterLocked(scale, centerX, centerY, animate);
        }
    }

    private boolean setScaleAndCenterLocked(
            float scale, float centerX, float centerY, boolean animate) {
        final boolean changed = updateMagnificationSpecLocked(scale, centerX, centerY);
        mSpecAnimationBridge.updateSentSpec(mCurrentMagnificationSpec, animate);
        return changed;
    }

    /**
     * Offsets the center of the magnified region.
     *
     * @param offsetX the amount in pixels to offset the X center
     * @param offsetY the amount in pixels to offset the Y center
     */
    public void offsetMagnifiedRegionCenter(float offsetX, float offsetY) {
        synchronized (mLock) {
            final MagnificationSpec currSpec = mCurrentMagnificationSpec;
            final float nonNormOffsetX = currSpec.offsetX - offsetX;
            currSpec.offsetX = MathUtils.constrain(nonNormOffsetX, getMinOffsetXLocked(), 0);
            final float nonNormOffsetY = currSpec.offsetY - offsetY;
            currSpec.offsetY = MathUtils.constrain(nonNormOffsetY, getMinOffsetYLocked(), 0);
            mSpecAnimationBridge.updateSentSpec(currSpec, false);
        }
    }

    /**
     * Persists the current magnification scale to the current user's settings.
     */
    public void persistScale() {
        final float scale = mCurrentMagnificationSpec.scale;
        final int userId = mUserId;

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                Settings.Secure.putFloatForUser(mContentResolver,
                        Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, scale, userId);
                return null;
            }
        }.execute();
    }

    /**
     * Retrieves a previously persisted magnification scale from the current
     * user's settings.
     *
     * @return the previously persisted magnification scale, or the default
     *         scale if none is available
     */
    public float getPersistedScale() {
        return Settings.Secure.getFloatForUser(mContentResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
                DEFAULT_MAGNIFICATION_SCALE, mUserId);
    }

    /**
     * Updates the current magnification spec.
     *
     * @param scale the magnification scale
     * @param centerX the unscaled, screen-relative X coordinate of the center
     *                of the viewport, or {@link Float#NaN} to leave unchanged
     * @param centerY the unscaled, screen-relative Y coordinate of the center
     *                of the viewport, or {@link Float#NaN} to leave unchanged
     * @return {@code true} if the magnification spec changed or {@code false}
     *         otherwise
     */
    private boolean updateMagnificationSpecLocked(float scale, float centerX, float centerY) {
        // Handle defaults.
        if (Float.isNaN(centerX)) {
            centerX = getCenterX();
        }
        if (Float.isNaN(centerY)) {
            centerY = getCenterY();
        }
        if (Float.isNaN(scale)) {
            scale = getScale();
        }

        // Ensure requested center is within the available region.
        if (!availableRegionContains(centerX, centerY)) {
            return false;
        }

        // Compute changes.
        final MagnificationSpec currSpec = mCurrentMagnificationSpec;
        boolean changed = false;

        final float normScale = MathUtils.constrain(scale, MIN_SCALE, MAX_SCALE);
        if (Float.compare(currSpec.scale, normScale) != 0) {
            currSpec.scale = normScale;
            changed = true;
        }

        final float nonNormOffsetX = mMagnifiedBounds.width() / 2.0f
                + mMagnifiedBounds.left - centerX * scale;
        final float offsetX = MathUtils.constrain(nonNormOffsetX, getMinOffsetXLocked(), 0);
        if (Float.compare(currSpec.offsetX, offsetX) != 0) {
            currSpec.offsetX = offsetX;
            changed = true;
        }

        final float nonNormOffsetY = mMagnifiedBounds.height() / 2.0f
                + mMagnifiedBounds.top - centerY * scale;
        final float offsetY = MathUtils.constrain(nonNormOffsetY, getMinOffsetYLocked(), 0);
        if (Float.compare(currSpec.offsetY, offsetY) != 0) {
            currSpec.offsetY = offsetY;
            changed = true;
        }

        return changed;
    }

    private float getMinOffsetXLocked() {
        final float viewportWidth = mMagnifiedBounds.width();
        return viewportWidth - viewportWidth * mCurrentMagnificationSpec.scale;
    }

    private float getMinOffsetYLocked() {
        final float viewportHeight = mMagnifiedBounds.height();
        return viewportHeight - viewportHeight * mCurrentMagnificationSpec.scale;
    }

    /**
     * Sets the currently active user ID.
     *
     * @param userId the currently active user ID
     */
    public void setUserId(int userId) {
        if (mUserId != userId) {
            mUserId = userId;

            synchronized (mLock) {
                if (isMagnifying()) {
                    reset(false);
                }
            }
        }
    }

    private boolean isScreenMagnificationAutoUpdateEnabled() {
        return (Settings.Secure.getInt(mContentResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_AUTO_UPDATE,
                DEFAULT_SCREEN_MAGNIFICATION_AUTO_UPDATE) == 1);
    }

    /**
     * Resets magnification if magnification and auto-update are both enabled.
     *
     * @param animate whether the animate the transition
     * @return {@code true} if magnification was reset to the disabled state,
     *         {@code false} if magnification is still active
     */
    boolean resetIfNeeded(boolean animate) {
        synchronized (mLock) {
            if (isMagnifying() && isScreenMagnificationAutoUpdateEnabled()) {
                reset(animate);
                return true;
            }
            return false;
        }
    }

    private void getMagnifiedFrameInContentCoordsLocked(Rect outFrame) {
        final float scale = getSentScale();
        final float offsetX = getSentOffsetX();
        final float offsetY = getSentOffsetY();
        getMagnifiedBounds(outFrame);
        outFrame.offset((int) -offsetX, (int) -offsetY);
        outFrame.scale(1.0f / scale);
    }

    private void requestRectangleOnScreen(int left, int top, int right, int bottom) {
        synchronized (mLock) {
            final Rect magnifiedFrame = mTempRect;
            getMagnifiedBounds(magnifiedFrame);
            if (!magnifiedFrame.intersects(left, top, right, bottom)) {
                return;
            }

            final Rect magnifFrameInScreenCoords = mTempRect1;
            getMagnifiedFrameInContentCoordsLocked(magnifFrameInScreenCoords);

            final float scrollX;
            final float scrollY;
            if (right - left > magnifFrameInScreenCoords.width()) {
                final int direction = TextUtils
                        .getLayoutDirectionFromLocale(Locale.getDefault());
                if (direction == View.LAYOUT_DIRECTION_LTR) {
                    scrollX = left - magnifFrameInScreenCoords.left;
                } else {
                    scrollX = right - magnifFrameInScreenCoords.right;
                }
            } else if (left < magnifFrameInScreenCoords.left) {
                scrollX = left - magnifFrameInScreenCoords.left;
            } else if (right > magnifFrameInScreenCoords.right) {
                scrollX = right - magnifFrameInScreenCoords.right;
            } else {
                scrollX = 0;
            }

            if (bottom - top > magnifFrameInScreenCoords.height()) {
                scrollY = top - magnifFrameInScreenCoords.top;
            } else if (top < magnifFrameInScreenCoords.top) {
                scrollY = top - magnifFrameInScreenCoords.top;
            } else if (bottom > magnifFrameInScreenCoords.bottom) {
                scrollY = bottom - magnifFrameInScreenCoords.bottom;
            } else {
                scrollY = 0;
            }

            final float scale = getScale();
            offsetMagnifiedRegionCenter(scrollX * scale, scrollY * scale);
        }
    }

    /**
     * Class responsible for animating spec on the main thread and sending spec
     * updates to the window manager.
     */
    private static class SpecAnimationBridge {
        private static final int ACTION_UPDATE_SPEC = 1;

        private final Handler mHandler;
        private final WindowManagerInternal mWindowManager;

        /**
         * The magnification spec that was sent to the window manager. This should
         * only be accessed and modified on the main (e.g. animation) thread.
         */
        private final MagnificationSpec mSentMagnificationSpec = MagnificationSpec.obtain();

        /**
         * The animator that updates the sent spec. This should only be accessed
         * and modified on the main (e.g. animation) thread.
         */
        private final ValueAnimator mTransformationAnimator;

        private final long mMainThreadId;

        private SpecAnimationBridge(Context context) {
            final Looper mainLooper = context.getMainLooper();
            mMainThreadId = mainLooper.getThread().getId();

            mHandler = new UpdateHandler(context);
            mWindowManager = LocalServices.getService(WindowManagerInternal.class);

            final MagnificationSpecProperty property = new MagnificationSpecProperty();
            final MagnificationSpecEvaluator evaluator = new MagnificationSpecEvaluator();
            final long animationDuration = context.getResources().getInteger(
                    R.integer.config_longAnimTime);
            mTransformationAnimator = ObjectAnimator.ofObject(this, property, evaluator,
                    mSentMagnificationSpec);
            mTransformationAnimator.setDuration(animationDuration);
            mTransformationAnimator.setInterpolator(new DecelerateInterpolator(2.5f));
        }

        public void cancel() {
            if (mTransformationAnimator != null && mTransformationAnimator.isRunning()) {
                mTransformationAnimator.cancel();
            }
        }

        public void updateSentSpec(MagnificationSpec spec, boolean animate) {
            if (Thread.currentThread().getId() == mMainThreadId) {
                // Already on the main thread, don't bother proxying.
                updateSentSpecInternal(spec, animate);
            } else {
                mHandler.obtainMessage(ACTION_UPDATE_SPEC,
                        animate ? 1 : 0, 0, spec).sendToTarget();
            }
        }

        /**
         * Updates the sent spec.
         */
        private void updateSentSpecInternal(MagnificationSpec spec, boolean animate) {
            if (mTransformationAnimator.isRunning()) {
                mTransformationAnimator.cancel();
            }

            // If the current and sent specs don't match, update the sent spec.
            final boolean changed = !mSentMagnificationSpec.equals(spec);
            if (changed) {
                if (animate) {
                    animateMagnificationSpec(spec);
                } else {
                    setMagnificationSpec(spec);
                }
            }
        }

        private void animateMagnificationSpec(MagnificationSpec toSpec) {
            mTransformationAnimator.setObjectValues(mSentMagnificationSpec, toSpec);
            mTransformationAnimator.start();
        }

        private void setMagnificationSpec(MagnificationSpec spec) {
            if (DEBUG_SET_MAGNIFICATION_SPEC) {
                Slog.i(LOG_TAG, "Sending: " + spec);
            }

            mSentMagnificationSpec.setTo(spec);
            mWindowManager.setMagnificationSpec(spec);
        }

        private class UpdateHandler extends Handler {
            public UpdateHandler(Context context) {
                super(context.getMainLooper());
            }

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case ACTION_UPDATE_SPEC:
                        final boolean animate = msg.arg1 == 1;
                        final MagnificationSpec spec = (MagnificationSpec) msg.obj;
                        updateSentSpecInternal(spec, animate);
                        break;
                }
            }
        }

        private static class MagnificationSpecProperty
                extends Property<SpecAnimationBridge, MagnificationSpec> {
            public MagnificationSpecProperty() {
                super(MagnificationSpec.class, "spec");
            }

            @Override
            public MagnificationSpec get(SpecAnimationBridge object) {
                return object.mSentMagnificationSpec;
            }

            @Override
            public void set(SpecAnimationBridge object, MagnificationSpec value) {
                object.setMagnificationSpec(value);
            }
        }

        private static class MagnificationSpecEvaluator
                implements TypeEvaluator<MagnificationSpec> {
            private final MagnificationSpec mTempSpec = MagnificationSpec.obtain();

            @Override
            public MagnificationSpec evaluate(float fraction, MagnificationSpec fromSpec,
                    MagnificationSpec toSpec) {
                final MagnificationSpec result = mTempSpec;
                result.scale = fromSpec.scale + (toSpec.scale - fromSpec.scale) * fraction;
                result.offsetX = fromSpec.offsetX + (toSpec.offsetX - fromSpec.offsetX) * fraction;
                result.offsetY = fromSpec.offsetY + (toSpec.offsetY - fromSpec.offsetY) * fraction;
                return result;
            }
        }
    }

    private static class ScreenStateObserver extends BroadcastReceiver {
        private static final int MESSAGE_ON_SCREEN_STATE_CHANGE = 1;

        private final Context mContext;
        private final MagnificationController mController;
        private final Handler mHandler;

        public ScreenStateObserver(Context context, MagnificationController controller) {
            mContext = context;
            mController = controller;
            mHandler = new StateChangeHandler(context);
        }

        public void register() {
            mContext.registerReceiver(this, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        }

        public void unregister() {
            mContext.unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            mHandler.obtainMessage(MESSAGE_ON_SCREEN_STATE_CHANGE,
                    intent.getAction()).sendToTarget();
        }

        private void handleOnScreenStateChange() {
            mController.resetIfNeeded(false);
        }

        private class StateChangeHandler extends Handler {
            public StateChangeHandler(Context context) {
                super(context.getMainLooper());
            }

            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MESSAGE_ON_SCREEN_STATE_CHANGE:
                        handleOnScreenStateChange();
                        break;
                }
            }
        }
    }

    /**
     * This class handles the screen magnification when accessibility is enabled.
     */
    private static class WindowStateObserver
            implements WindowManagerInternal.MagnificationCallbacks {
        private static final int MESSAGE_ON_MAGNIFIED_BOUNDS_CHANGED = 1;
        private static final int MESSAGE_ON_RECTANGLE_ON_SCREEN_REQUESTED = 2;
        private static final int MESSAGE_ON_USER_CONTEXT_CHANGED = 3;
        private static final int MESSAGE_ON_ROTATION_CHANGED = 4;

        private final MagnificationController mController;
        private final WindowManagerInternal mWindowManager;
        private final Handler mHandler;

        private boolean mSpecIsDirty;

        public WindowStateObserver(Context context, MagnificationController controller) {
            mController = controller;
            mWindowManager = LocalServices.getService(WindowManagerInternal.class);
            mHandler = new CallbackHandler(context);
        }

        public void register() {
            mWindowManager.setMagnificationCallbacks(this);
        }

        public void unregister() {
            mWindowManager.setMagnificationCallbacks(null);
        }

        @Override
        public void onMagnifiedBoundsChanged(Region magnified, Region available) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = Region.obtain(magnified);
            args.arg2 = Region.obtain(available);
            mHandler.obtainMessage(MESSAGE_ON_MAGNIFIED_BOUNDS_CHANGED, args).sendToTarget();
        }

        private void handleOnMagnifiedBoundsChanged(Region magnified, Region available) {
            mController.setMagnifiedRegion(magnified, available, mSpecIsDirty);
            mSpecIsDirty = false;
        }

        @Override
        public void onRectangleOnScreenRequested(int left, int top, int right, int bottom) {
            final SomeArgs args = SomeArgs.obtain();
            args.argi1 = left;
            args.argi2 = top;
            args.argi3 = right;
            args.argi4 = bottom;
            mHandler.obtainMessage(MESSAGE_ON_RECTANGLE_ON_SCREEN_REQUESTED, args).sendToTarget();
        }

        private void handleOnRectangleOnScreenRequested(int left, int top, int right, int bottom) {
            mController.requestRectangleOnScreen(left, top, right, bottom);
        }

        @Override
        public void onRotationChanged(int rotation) {
            mHandler.obtainMessage(MESSAGE_ON_ROTATION_CHANGED, rotation, 0).sendToTarget();
        }

        private void handleOnRotationChanged() {
            // If there was a rotation and magnification is still enabled,
            // we'll need to rewrite the spec to reflect the new screen
            // configuration. Conveniently, we'll receive a callback from
            // the window manager with updated bounds for the magnified
            // region.
            mSpecIsDirty = !mController.resetIfNeeded(true);
        }

        @Override
        public void onUserContextChanged() {
            mHandler.sendEmptyMessage(MESSAGE_ON_USER_CONTEXT_CHANGED);
        }

        private void handleOnUserContextChanged() {
            mController.resetIfNeeded(true);
        }

        public void getRegions(@NonNull Region outMagnified, @NonNull Region outAvailable) {
            mWindowManager.getMagnificationRegions(outMagnified, outAvailable);
        }

        private class CallbackHandler extends Handler {
            public CallbackHandler(Context context) {
                super(context.getMainLooper());
            }

            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MESSAGE_ON_MAGNIFIED_BOUNDS_CHANGED: {
                        final SomeArgs args = (SomeArgs) message.obj;
                        final Region magnifiedBounds = (Region) args.arg1;
                        final Region availableBounds = (Region) args.arg2;
                        handleOnMagnifiedBoundsChanged(magnifiedBounds, availableBounds);
                        magnifiedBounds.recycle();
                        availableBounds.recycle();
                    } break;
                    case MESSAGE_ON_RECTANGLE_ON_SCREEN_REQUESTED: {
                        final SomeArgs args = (SomeArgs) message.obj;
                        final int left = args.argi1;
                        final int top = args.argi2;
                        final int right = args.argi3;
                        final int bottom = args.argi4;
                        handleOnRectangleOnScreenRequested(left, top, right, bottom);
                        args.recycle();
                    } break;
                    case MESSAGE_ON_USER_CONTEXT_CHANGED: {
                        handleOnUserContextChanged();
                    } break;
                    case MESSAGE_ON_ROTATION_CHANGED: {
                        handleOnRotationChanged();
                    } break;
                }
            }
        }
    }
}
