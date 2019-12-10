/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.evolution.header.StatusBarHeaderMachine;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.util.animation.PhysicsAnimator;

/**
 * Wrapper view with background which contains {@link QSPanel} and {@link BaseStatusBarHeader}
 */
public class QSContainerImpl extends FrameLayout implements ColorExtractor.OnColorsChangedListener,
        StatusBarHeaderMachine.IStatusBarHeaderMachineObserver {

    private static final String TAG = "QSContainerImpl";

    private final Point mSizePoint = new Point();
    private static final FloatPropertyCompat<QSContainerImpl> BACKGROUND_BOTTOM =
            new FloatPropertyCompat<QSContainerImpl>("backgroundBottom") {
                @Override
                public float getValue(QSContainerImpl qsImpl) {
                    return qsImpl.getBackgroundBottom();
                }

                @Override
                public void setValue(QSContainerImpl background, float value) {
                    background.setBackgroundBottom((int) value);
                }
            };
    private static final PhysicsAnimator.SpringConfig BACKGROUND_SPRING
            = new PhysicsAnimator.SpringConfig(SpringForce.STIFFNESS_MEDIUM,
            SpringForce.DAMPING_RATIO_LOW_BOUNCY);
    private int mBackgroundBottom = -1;
    private int mHeightOverride = -1;
    private QSPanel mQSPanel;
    private View mQSDetail;
    private QuickStatusBarHeader mHeader;
    private float mQsExpansion;
    private QSCustomizer mQSCustomizer;
    private View mDragHandle;
    private View mQSPanelContainer;

    private View mBackground;
    private View mStatusBarBackground;

    private int mSideMargins;
    private boolean mQsDisabled;
    private int mContentPaddingStart = -1;
    private int mContentPaddingEnd = -1;
    private boolean mAnimateBottomOnNextLayout;

    private boolean mHeaderImageEnabled;
    private ImageView mBackgroundImage;
    private StatusBarHeaderMachine mStatusBarHeaderMachine;
    private Drawable mCurrentBackground;
    private boolean mLandscape;

    private Drawable mQsBackGround;
    private int mQsBackGroundAlpha;
    private int mQsBackGroundColor;
    private boolean mSetQsFromWall;
    private boolean mSetQsFromResources;

    private SysuiColorExtractor mColorExtractor;
    private IOverlayManager mOverlayManager;

    public QSContainerImpl(Context context, AttributeSet attrs) {
        super(context, attrs);
        mStatusBarHeaderMachine = new StatusBarHeaderMachine(context);
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        Handler handler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(handler);
        settingsObserver.observe();
        mColorExtractor = Dependency.get(SysuiColorExtractor.class);
        mColorExtractor.addOnColorsChangedListener(this);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQSPanel = findViewById(R.id.quick_settings_panel);
        mQSPanelContainer = findViewById(R.id.expanded_qs_scroll_view);
        mQSDetail = findViewById(R.id.qs_detail);
        mHeader = findViewById(R.id.header);
        mQSCustomizer = findViewById(R.id.qs_customize);
        mDragHandle = findViewById(R.id.qs_drag_handle_view);
        mBackground = findViewById(R.id.quick_settings_background);
        mStatusBarBackground = findViewById(R.id.quick_settings_status_bar_background);
        mBackgroundImage = findViewById(R.id.qs_header_image_view);
        mBackgroundImage.setClipToOutline(true);
        updateResources();
        mHeader.getHeaderQsPanel().setMediaVisibilityChangedListener((visible) -> {
            if (mHeader.getHeaderQsPanel().isShown()) {
                mAnimateBottomOnNextLayout = true;
            }
        });
        mQSPanel.setMediaVisibilityChangedListener((visible) -> {
            if (mQSPanel.isShown()) {
                mAnimateBottomOnNextLayout = true;
            }
        });

        mQsBackGround = getContext().getDrawable(R.drawable.qs_background_primary);

        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        updateSettings();
    }

    @Override
    public void onColorsChanged(ColorExtractor colorExtractor, int which) {
        setQsBackground();
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            getContext().getContentResolver().registerContentObserver(Settings.System
                            .getUriFor(Settings.System.QS_PANEL_BG_ALPHA), false,
                    this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_PANEL_BG_COLOR), false,
                    this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System
                    .getUriFor(Settings.System.SYSUI_COLORS_ACTIVE), false,
                    this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_PANEL_BG_USE_FW), false,
                    this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.System.getUriFor(Settings.System.QS_PANEL_BG_ALPHA))) {
                mQsBackGroundAlpha = Settings.System.getIntForUser(getContext().getContentResolver(),
                        Settings.System.QS_PANEL_BG_ALPHA, 255, UserHandle.USER_CURRENT);
                setQsBackground();
            } else if (uri.equals(Settings.System.getUriFor(Settings.System.QS_PANEL_BG_COLOR))) {
                mQsBackGroundColor = Settings.System.getIntForUser(getContext().getContentResolver(),
                        Settings.System.QS_PANEL_BG_COLOR, Color.WHITE, UserHandle.USER_CURRENT);
                setQsBackground();
            } else if (uri.equals(Settings.System.getUriFor(Settings.System.SYSUI_COLORS_ACTIVE))) {
                mSetQsFromWall = Settings.System.getIntForUser(getContext().getContentResolver(),
                        Settings.System.SYSUI_COLORS_ACTIVE, 0, UserHandle.USER_CURRENT) == 1;
                setQsBackground();
            } else if (uri.equals(Settings.System.getUriFor(Settings.System.QS_PANEL_BG_USE_FW))) {
                mSetQsFromResources = Settings.System.getIntForUser(getContext().getContentResolver(),
                        Settings.System.QS_PANEL_BG_USE_FW, 1, UserHandle.USER_CURRENT) == 1;
                setQsBackground();
            }
        }
    }

    private void updateSettings() {
        mQsBackGroundAlpha = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.QS_PANEL_BG_ALPHA, 255, UserHandle.USER_CURRENT);
        mQsBackGroundColor = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.QS_PANEL_BG_COLOR, Color.WHITE, UserHandle.USER_CURRENT);
        mSetQsFromWall = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.SYSUI_COLORS_ACTIVE, 0, UserHandle.USER_CURRENT) == 1;
        mSetQsFromResources = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.QS_PANEL_BG_USE_FW, 1, UserHandle.USER_CURRENT) == 1;

        setQsBackground();
    }

    private void setBackgroundBottom(int value) {
        // We're saving the bottom separately since otherwise the bottom would be overridden in
        // the layout and the animation wouldn't properly start at the old position.
        mBackgroundBottom = value;
        mBackground.setBottom(value);
    }

    private float getBackgroundBottom() {
        if (mBackgroundBottom == -1) {
            return mBackground.getBottom();
        }
        return mBackgroundBottom;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mStatusBarHeaderMachine.addObserver(this);
        mStatusBarHeaderMachine.updateEnablement();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mStatusBarHeaderMachine.removeObserver(this);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;

        updateResources();
        mSizePoint.set(0, 0); // Will be retrieved on next measure pass.
    }

    private void setQsBackground() {
        int currentColor = mSetQsFromWall ? getWallpaperColor() : mQsBackGroundColor;
        if (mSetQsFromResources) {
            mQsBackGround = getContext().getDrawable(R.drawable.qs_background_primary);
            try {
                mOverlayManager.setEnabled("com.android.systemui.qstheme.color",
                        false, ActivityManager.getCurrentUser());
            } catch (RemoteException e) {
                Log.w("QSContainerImpl", "Can't change qs theme", e);
            }
        } else {
            if (mQsBackGround != null) {
                mQsBackGround.setColorFilter(currentColor, PorterDuff.Mode.SRC_ATOP);
            }
            try {
                mOverlayManager.setEnabled("com.android.systemui.qstheme.color",
                        true, ActivityManager.getCurrentUser());
            } catch (RemoteException e) {
                Log.w("QSContainerImpl", "Can't change qs theme", e);
            }
        }

        if (mQsBackGround != null)
            mQsBackGround.setAlpha(mQsBackGroundAlpha);
        if (mQsBackGround != null && mBackground != null) {
            mBackground.setBackground(mQsBackGround);
        }
    }

    private int getWallpaperColor() {
        // TODO: Find a way to trigger setBackground on lock event, and use FLAG_LOCK there
        return mColorExtractor.getWallpaperColors(WallpaperManager.FLAG_SYSTEM).getPrimaryColor().toArgb();
    }

    @Override
    public boolean performClick() {
        // Want to receive clicks so missing QQS tiles doesn't cause collapse, but
        // don't want to do anything with them.
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // QSPanel will show as many rows as it can (up to TileLayout.MAX_ROWS) such that the
        // bottom and footer are inside the screen.
        Configuration config = getResources().getConfiguration();
        boolean navBelow = config.smallestScreenWidthDp >= 600
                || config.orientation != Configuration.ORIENTATION_LANDSCAPE;
        MarginLayoutParams layoutParams = (MarginLayoutParams) mQSPanelContainer.getLayoutParams();

        // The footer is pinned to the bottom of QSPanel (same bottoms), therefore we don't need to
        // subtract its height. We do not care if the collapsed notifications fit in the screen.
        int maxQs = getDisplayHeight() - layoutParams.topMargin - layoutParams.bottomMargin
                - getPaddingBottom();
        if (navBelow) {
            maxQs -= getResources().getDimensionPixelSize(R.dimen.navigation_bar_height);
        }

        int padding = mPaddingLeft + mPaddingRight + layoutParams.leftMargin
                + layoutParams.rightMargin;
        final int qsPanelWidthSpec = getChildMeasureSpec(widthMeasureSpec, padding,
                layoutParams.width);
        mQSPanelContainer.measure(qsPanelWidthSpec,
                MeasureSpec.makeMeasureSpec(maxQs, MeasureSpec.AT_MOST));
        int width = mQSPanelContainer.getMeasuredWidth() + padding;
        int height = layoutParams.topMargin + layoutParams.bottomMargin
                + mQSPanelContainer.getMeasuredHeight() + getPaddingBottom();
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        // QSCustomizer will always be the height of the screen, but do this after
        // other measuring to avoid changing the height of the QS.
        mQSCustomizer.measure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(getDisplayHeight(), MeasureSpec.EXACTLY));
    }


    @Override
    protected void measureChildWithMargins(View child, int parentWidthMeasureSpec, int widthUsed,
            int parentHeightMeasureSpec, int heightUsed) {
        // Do not measure QSPanel again when doing super.onMeasure.
        // This prevents the pages in PagedTileLayout to be remeasured with a different (incorrect)
        // size to the one used for determining the number of rows and then the number of pages.
        if (child != mQSPanelContainer) {
            super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed,
                    parentHeightMeasureSpec, heightUsed);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateExpansion(mAnimateBottomOnNextLayout /* animate */);
        mAnimateBottomOnNextLayout = false;
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mBackground.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        updateStatusbarVisibility();
    }

    private void updateResources() {
        int topMargin = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height) + (mHeaderImageEnabled ?
                mContext.getResources().getDimensionPixelSize(R.dimen.qs_header_image_offset) : 0);

        LayoutParams layoutParams = (LayoutParams) mQSPanelContainer.getLayoutParams();
        layoutParams.topMargin = topMargin;
        mQSPanelContainer.setLayoutParams(layoutParams);

        mSideMargins = getResources().getDimensionPixelSize(R.dimen.notification_side_paddings);
        mContentPaddingStart = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_start);
        int newPaddingEnd = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_end);
        boolean marginsChanged = newPaddingEnd != mContentPaddingEnd;
        mContentPaddingEnd = newPaddingEnd;
        if (marginsChanged) {
            updatePaddingsAndMargins();
        }

        int statusBarSideMargin = mHeaderImageEnabled ? mContext.getResources().getDimensionPixelSize(
                R.dimen.qs_header_image_side_margin) : 0;

        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mStatusBarBackground.getLayoutParams();
        lp.height = topMargin;
        lp.setMargins(statusBarSideMargin, 0, statusBarSideMargin, 0);
        mStatusBarBackground.setLayoutParams(lp);

        updateStatusbarVisibility();
    }

    /**
     * Overrides the height of this view (post-layout), so that the content is clipped to that
     * height and the background is set to that height.
     *
     * @param heightOverride the overridden height
     */
    public void setHeightOverride(int heightOverride) {
        mHeightOverride = heightOverride;
        updateExpansion();
    }

    public void updateExpansion() {
        updateExpansion(false /* animate */);
    }

    public void updateExpansion(boolean animate) {
        int height = calculateContainerHeight();
        setBottom(getTop() + height);
        mQSDetail.setBottom(getTop() + height);
        // Pin the drag handle to the bottom of the panel.
        mDragHandle.setTranslationY(height - mDragHandle.getHeight());
        mBackground.setTop(mQSPanelContainer.getTop());
        updateBackgroundBottom(height, animate);
    }

    private void updateBackgroundBottom(int height, boolean animated) {
        PhysicsAnimator<QSContainerImpl> physicsAnimator = PhysicsAnimator.getInstance(this);
        if (physicsAnimator.isPropertyAnimating(BACKGROUND_BOTTOM) || animated) {
            // An animation is running or we want to animate
            // Let's make sure to set the currentValue again, since the call below might only
            // start in the next frame and otherwise we'd flicker
            BACKGROUND_BOTTOM.setValue(this, BACKGROUND_BOTTOM.getValue(this));
            physicsAnimator.spring(BACKGROUND_BOTTOM, height, BACKGROUND_SPRING).start();
        } else {
            BACKGROUND_BOTTOM.setValue(this, height);
        }

    }

    protected int calculateContainerHeight() {
        int heightOverride = mHeightOverride != -1 ? mHeightOverride : getMeasuredHeight();
        return mQSCustomizer.isCustomizing() ? mQSCustomizer.getHeight()
                : Math.round(mQsExpansion * (heightOverride - mHeader.getHeight()))
                + mHeader.getHeight();
    }

    public void setExpansion(float expansion) {
        mQsExpansion = expansion;
        mDragHandle.setAlpha(1.0f - expansion);
        updateExpansion();
    }

    private void updatePaddingsAndMargins() {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view == mStatusBarBackground || view == mQSCustomizer) {
                // Some views are always full width
                continue;
            }
            LayoutParams lp = (LayoutParams) view.getLayoutParams();
            lp.rightMargin = mSideMargins;
            lp.leftMargin = mSideMargins;
            if (view == mQSPanelContainer) {
                // QS panel lays out some of its content full width
                mQSPanel.setContentMargins(mContentPaddingStart, mContentPaddingEnd);
            } else if (view == mHeader) {
                // The header contains the QQS panel which needs to have special padding, to
                // visually align them.
                mHeader.setContentMargins(mContentPaddingStart, mContentPaddingEnd);
            } else {
                view.setPaddingRelative(
                        mContentPaddingStart,
                        view.getPaddingTop(),
                        mContentPaddingEnd,
                        view.getPaddingBottom());
            }
        }
    }

    private int getDisplayHeight() {
        if (mSizePoint.y == 0) {
            getDisplay().getRealSize(mSizePoint);
        }
        return mSizePoint.y;
    }

    @Override
    public void updateHeader(final Drawable headerImage, final boolean force) {
        post(new Runnable() {
            public void run() {
                doUpdateStatusBarCustomHeader(headerImage, force);
            }
        });
    }

    @Override
    public void disableHeader() {
        post(new Runnable() {
            public void run() {
                mCurrentBackground = null;
                mBackgroundImage.setVisibility(View.GONE);
                mHeaderImageEnabled = false;
                updateResources();
            }
        });
    }

    @Override
    public void refreshHeader() {
        post(new Runnable() {
            public void run() {
                doUpdateStatusBarCustomHeader(mCurrentBackground, true);
            }
        });
    }

    private void doUpdateStatusBarCustomHeader(final Drawable next, final boolean force) {
        if (next != null) {
            mBackgroundImage.setVisibility(View.VISIBLE);
            mCurrentBackground = next;
            setNotificationPanelHeaderBackground(next, force);
            mHeaderImageEnabled = true;
        } else {
            mCurrentBackground = null;
            mBackgroundImage.setVisibility(View.GONE);
            mHeaderImageEnabled = false;
        }
        updateResources();
    }

    private void setNotificationPanelHeaderBackground(final Drawable dw, final boolean force) {
        if (mBackgroundImage.getDrawable() != null && !force) {
            Drawable[] arrayDrawable = new Drawable[2];
            arrayDrawable[0] = mBackgroundImage.getDrawable();
            arrayDrawable[1] = dw;

            TransitionDrawable transitionDrawable = new TransitionDrawable(arrayDrawable);
            transitionDrawable.setCrossFadeEnabled(true);
            mBackgroundImage.setImageDrawable(transitionDrawable);
            transitionDrawable.startTransition(1000);
        } else {
            mBackgroundImage.setImageDrawable(dw);
        }
    }

    private void updateStatusbarVisibility() {
        boolean hideStatusbar = mLandscape && !mHeaderImageEnabled;

        mStatusBarBackground.setBackgroundColor(Color.TRANSPARENT);
        mStatusBarBackground.setVisibility(hideStatusbar ? View.INVISIBLE : View.VISIBLE);
    }
}
