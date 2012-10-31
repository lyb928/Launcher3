/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.launcher3;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region.Op;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.AndroidRuntimeException;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.DragEvent;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.View.MeasureSpec;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.IMTKWidget;
import android.widget.TextView;
import android.widget.Toast;

import com.android.launcher3.R;
import com.android.launcher3.FolderIcon.FolderRingAnimator;
import com.android.launcher3.InstallWidgetReceiver.WidgetMimeTypeHandlerData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.xml.datatype.Duration;

/**
 * The workspace is a wide area with a wallpaper and a finite number of pages.
 * Each page contains a number of icons, folders or widgets the user can
 * interact with. A workspace is meant to be used with a fixed width only.
 */
public class Workspace extends SmoothPagedView
        implements DropTarget, DragSource, DragScroller, View.OnTouchListener,
        DragController.DragListener, BackgroundAlphable{
    @SuppressWarnings({"UnusedDeclaration"})
    private static final String TAG = "Launcher.Workspace";

    // Y rotation to apply to the workspace screens
    private static final float WORKSPACE_ROTATION = 12.5f;
    private static final float WORKSPACE_OVERSCROLL_ROTATION = 44f;
    private static float CAMERA_DISTANCE = 3500;

    private static final int CHILDREN_OUTLINE_FADE_OUT_DELAY = 0;
    private static final int CHILDREN_OUTLINE_FADE_OUT_DURATION = 375;
    private static final int CHILDREN_OUTLINE_FADE_IN_DURATION = 100;

    private static final int BACKGROUND_FADE_OUT_DURATION = 200;
    private static final int ADJACENT_SCREEN_DROP_DURATION = 150;

    // These animators are used to fade the children's outlines
    private ObjectAnimator mChildrenOutlineFadeInAnimation;
    private ObjectAnimator mChildrenOutlineFadeOutAnimation;
    private float mChildrenOutlineAlpha = 0;

    // These properties refer to the background protection gradient used for AllApps and Customize
    private ValueAnimator mBackgroundFadeInAnimation;
    private ValueAnimator mBackgroundFadeOutAnimation;
    private Drawable mBackground;
    boolean mDrawBackground = true;
    private float mBackgroundAlpha = 0;
    private float mOverScrollMaxBackgroundAlpha = 0.0f;
    private int mOverScrollPageIndex = -1;

    private float mWallpaperScrollRatio = 1.0f;

    private final WallpaperManager mWallpaperManager;
    private IBinder mWindowToken;
    private static final float WALLPAPER_SCREENS_SPAN = 2.0f;

    private int mDefaultPage;

    /**
     * CellInfo for the cell that is currently being dragged
     */
    private CellLayout.CellInfo mDragInfo;

    /**
     * Target drop area calculated during last acceptDrop call.
     */
    private int[] mTargetCell = new int[2];

    /**
     * The CellLayout that is currently being dragged over
     */
    private CellLayout mDragTargetLayout = null;

    private IconCache mIconCache;
    private DragController mDragController;

    // These are temporary variables to prevent having to allocate a new object just to
    // return an (x, y) value from helper functions. Do NOT use them to maintain other state.
    private int[] mTempCell = new int[2];
    private int[] mTempEstimate = new int[2];
    private float[] mDragViewVisualCenter = new float[2];
    private float[] mTempDragCoordinates = new float[2];
    private float[] mTempCellLayoutCenterCoordinates = new float[2];
    private float[] mTempDragBottomRightCoordinates = new float[2];
    private Matrix mTempInverseMatrix = new Matrix();

    private SpringLoadedDragController mSpringLoadedDragController;
    private float mSpringLoadedShrinkFactor;

    private static final int DEFAULT_CELL_COUNT_X = 4;
    private static final int DEFAULT_CELL_COUNT_Y = 4;

    // State variable that indicates whether the pages are small (ie when you're
    // in all apps or customize mode)

    enum State { NORMAL, SPRING_LOADED, SMALL, PREVIEWS};
    private State mState = State.NORMAL;
    private boolean mSwitchStateAfterFirstLayout = false;
    private State mStateAfterFirstLayout;
    private boolean mIsSwitchingState = false;

    private AnimatorSet mAnimator;
    private AnimatorListener mChangeStateAnimationListener;

    boolean mAnimatingViewIntoPlace = false;
    boolean mIsDragOccuring = false;
    boolean mChildrenLayersEnabled = true;

    /** Is the user is dragging an item near the edge of a page? */
    private boolean mInScrollArea = false;

    private final HolographicOutlineHelper mOutlineHelper = new HolographicOutlineHelper();
    private Bitmap mDragOutline = null;
//    private final Rect mTempRect = new Rect();
    private final int[] mTempXY = new int[2];
    private int mDragViewMultiplyColor;
    private float mOverscrollFade = 0;

    // Paint used to draw external drop outline
    private final Paint mExternalDragOutlinePaint = new Paint();

    // Camera and Matrix used to determine the final position of a neighboring CellLayout
    private int[][] mMatrix/* = new Matrix()*/;
//    private final Camera mCamera = new Camera();
    private final float mTempFloat2[] = new float[2];

//    enum WallpaperVerticalOffset { TOP, MIDDLE, BOTTOM };
    int mWallpaperWidth;
    int mWallpaperHeight;
    WallpaperOffsetInterpolator mWallpaperOffset;
    boolean mUpdateWallpaperOffsetImmediately = false;
    private Runnable mDelayedResizeRunnable;
    private ScrollInterpolator mScrollInterpolator;
    private DecelerateInterpolator mDecelerateInterpolator;
    private DecelerateInterpolator mScaleInterpolator;
    private int mDisplayWidth;
    private int mDisplayHeight;
    private int mWallpaperTravelWidth;

    // Variables relating to the creation of user folders by hovering shortcuts over shortcuts
    private static final int FOLDER_CREATION_TIMEOUT = 150;
    private final Alarm mFolderCreationAlarm = new Alarm();
    private FolderRingAnimator mDragFolderRingAnimator = null;
    private View mLastDragOverView = null;
    private boolean mCreateUserFolderOnDrop = false;
    
    private DragView mLastExchangeDragView = null;
    /**
     * restore all original cell position by indicated view as a temporary repository,
     * added by leeyb
     */
    private Map<DragView, int[]> mCellPosition = new HashMap<DragView, int[]>();
    /**
     * The desktop item relayout or database operation pending in this Runnable wait for UI thread commit it
     */
    private Runnable mPendingDropOperation;

    // Variables relating to touch disambiguation (scrolling workspace vs. scrolling a widget)
    private float mXDown;
    private float mYDown;
    final static float START_DAMPING_TOUCH_SLOP_ANGLE = (float) Math.PI / 6;
    final static float MAX_SWIPE_ANGLE = (float) Math.PI / 3;
    final static float TOUCH_SLOP_DAMPING_FACTOR = 4;
    final static float MAX_DESKTOP_ROTATE = 85f;
    final static float MAX_DESKTOP_ROTATE_DOTDRAG = 60f;
    final static float MAX_DESKTOP_TRANSLATE = 0.5f;
    final static float MAX_DESKTOP_TRANSLATE_DOTDRAG = 0.65f;
    final static long PAGE_EXCHANGE_DELAY_AMOUNT = 60;
    final static String WORKSPACE_CELLLAYOUT_ORDER = "celllayout_order";
    final static String WORKSPACE_DEFAULT_PAGE = "workspace_home_page";
    final static String DEFAULT_WORKSPACE_ORDER_KEY = "0123456";
    final static int MAX_WORKSPACE_CELLLAYOUT_NUMBER = 8;
    final static int MIN_WORKSPACE_CELLLAYOUT_NUMBER = 3;

    // These variables are used for storing the initial and final values during workspace animations
    private int mSavedScrollX;
    private float mSavedRotationY;
    private float mSavedTranslationX;
    private float mCurrentScaleX;
    private float mCurrentScaleY;
    private float mCurrentRotationY;
    private float mCurrentTranslationX;
    private float mCurrentTranslationY;
    private float[] mOldTranslationXs;
    private float[] mOldTranslationYs;
    private float[] mOldScaleXs;
    private float[] mOldScaleYs;
    private float[] mOldBackgroundAlphas;
    private float[] mOldBackgroundAlphaMultipliers;
    private float[] mOldAlphas;
    private float[] mOldRotationYs;
    private float[] mNewTranslationXs;
    private float[] mNewTranslationYs;
    private float[] mNewScaleXs;
    private float[] mNewScaleYs;
    private float[] mNewBackgroundAlphas;
    private float[] mNewBackgroundAlphaMultipliers;
    private float[] mNewAlphas;
    private float[] mNewRotationYs;
    private float mTransitionProgress;

    /**
     * Used to inflate the Workspace from XML.
     *
     * @param context The application's context.
     * @param attrs The attributes set containing the Workspace's customization values.
     */
    public Workspace(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * Used to inflate the Workspace from XML.
     *
     * @param context The application's context.
     * @param attrs The attributes set containing the Workspace's customization values.
     * @param defStyle Unused.
     */
    public Workspace(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContentIsRefreshable = false;

        mFadeInAdjacentScreens =
            getResources().getBoolean(R.bool.config_workspaceFadeAdjacentScreens);
        mWallpaperManager = WallpaperManager.getInstance(context);

        int cellCountX = DEFAULT_CELL_COUNT_X;
        int cellCountY = DEFAULT_CELL_COUNT_Y;

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.Workspace, defStyle, 0);

        final Resources res = context.getResources();
        if (LauncherApplication.isScreenLarge()) {
            // Determine number of rows/columns dynamically
            // TODO: This code currently fails on tablets with an aspect ratio < 1.3.
            // Around that ratio we should make cells the same size in portrait and
            // landscape
            TypedArray actionBarSizeTypedArray =
                context.obtainStyledAttributes(new int[] { android.R.attr.actionBarSize });
            final float actionBarHeight = actionBarSizeTypedArray.getDimension(0, 0f);
            final float systemBarHeight = res.getDimension(R.dimen.status_bar_height);
            final float smallestScreenDim = res.getConfiguration().smallestScreenWidthDp;

            cellCountX = 1;
            while (CellLayout.widthInPortrait(res, cellCountX + 1) <= smallestScreenDim) {
                cellCountX++;
            }

            cellCountY = 1;
            while (actionBarHeight + CellLayout.heightInLandscape(res, cellCountY + 1)
                <= smallestScreenDim - systemBarHeight) {
                cellCountY++;
            }
        }

        mSpringLoadedShrinkFactor =
            res.getInteger(R.integer.config_workspaceSpringLoadShrinkPercentage) / 100.0f;
        mDragViewMultiplyColor = res.getColor(R.color.drag_view_multiply_color);

        // if the value is manually specified, use that instead
        cellCountX = a.getInt(R.styleable.Workspace_cellCountX, cellCountX);
        cellCountY = a.getInt(R.styleable.Workspace_cellCountY, cellCountY);
//        mDefaultPage = a.getInt(R.styleable.Workspace_defaultScreen, 1);
        a.recycle();

        LauncherModel.updateWorkspaceLayoutCells(cellCountX, cellCountY);
        setHapticFeedbackEnabled(true);

        mLauncher = (Launcher) context;
        initWorkspace();

        // Disable multitouch across the workspace/all apps/customize tray
        setMotionEventSplittingEnabled(false);
    }

    // estimate the size of a widget with spans hSpan, vSpan. return MAX_VALUE for each
    // dimension if unsuccessful
    public int[] estimateItemSize(int hSpan, int vSpan,
            PendingAddItemInfo pendingItemInfo, boolean springLoaded) {
        int[] size = new int[2];
        if (getChildCount() > 0) {
            CellLayout cl = (CellLayout) mLauncher.getWorkspace().getPageAt(0);
            RectF r = estimateItemPosition(cl, pendingItemInfo, 0, 0, hSpan, vSpan);
            size[0] = (int) r.width();
            size[1] = (int) r.height();
            if (springLoaded) {
                size[0] *= mSpringLoadedShrinkFactor;
                size[1] *= mSpringLoadedShrinkFactor;
            }
            return size;
        } else {
            size[0] = Integer.MAX_VALUE;
            size[1] = Integer.MAX_VALUE;
            return size;
        }
    }
    public RectF estimateItemPosition(CellLayout cl, ItemInfo pendingInfo,
            int hCell, int vCell, int hSpan, int vSpan) {
        RectF r = new RectF();
        cl.cellToRect(hCell, vCell, hSpan, vSpan, r);
        if (pendingInfo instanceof PendingAddWidgetInfo) {
            PendingAddWidgetInfo widgetInfo = (PendingAddWidgetInfo) pendingInfo;
            Rect p = AppWidgetHostView.getDefaultPaddingForWidget(mContext,
                    widgetInfo.componentName, null);
            r.top += p.top;
            r.left += p.left;
            r.right -= p.right;
            r.bottom -= p.bottom;
        }
        return r;
    }

    public void buildPageHardwareLayers() {
        if (getWindowToken() != null) {
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                CellLayout cl = (CellLayout) getChildAt(i);
                cl.buildChildrenLayer();
            }
        }
    }

    public void onDragStart(DragSource source, Object info, int dragAction) {
    	if (LauncherLog.DEBUG_DRAG) {
    	    LauncherLog.d(TAG, "(Workspace)onDragStart source = " + source + ", info = " + info
    	            + ",info = " + info + ",dragAction = " + dragAction);
    	}
        mIsDragOccuring = true;
        updateChildrenLayersEnabled();
        mLauncher.lockScreenOrientationOnLargeUI();
//        changeState(State.SPRING_LOADED, true);
        mPendingDropOperation = null;
    }

    public void onDragEnd() {
    	if (LauncherLog.DEBUG_DRAG) {
    	    LauncherLog.d(TAG, "(Workspace)onDragEnd:mIsDragOccuring = " + mIsDragOccuring);    	
    	}
        mIsDragOccuring = false;
        updateChildrenLayersEnabled();
        mLauncher.unlockScreenOrientationOnLargeUI();
    }

    /**
     * Initializes various states for this workspace.
     */
    protected void initWorkspace() {
        Context context = getContext();
        LauncherApplication app = (LauncherApplication)context.getApplicationContext();
        mIconCache = app.getIconCache();
        mExternalDragOutlinePaint.setAntiAlias(true);
        setWillNotDraw(false);
        setChildrenDrawnWithCacheEnabled(true);
        mScrollInterpolator = new ScrollInterpolator();
        mDecelerateInterpolator = new DecelerateInterpolator(0.5f);
        mScaleInterpolator = new DecelerateInterpolator(0.1f);
        try {
            final Resources res = getResources();
            mBackground = res.getDrawable(R.drawable.apps_customize_bg);
        } catch (Resources.NotFoundException e) {
            // In this case, we will skip drawing background protection
        }

        mChangeStateAnimationListener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mIsSwitchingState = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mIsSwitchingState = false;
                mWallpaperOffset.setOverrideHorizontalCatchupConstant(false);
                mAnimator = null;
                updateChildrenLayersEnabled();
            }
        };

        mSnapVelocity = 350;
        mWallpaperOffset = new WallpaperOffsetInterpolator();
        Display display = mLauncher.getWindowManager().getDefaultDisplay();
        mDisplayWidth = display.getWidth();
        mDisplayHeight = display.getHeight();
        mWallpaperTravelWidth = (int) (mDisplayWidth *
                wallpaperTravelToScreenWidthRatio(mDisplayWidth, mDisplayHeight));
        
        setClipChildren(false);//added by leeyb
        mPersonator = new CellLayoutPersonator(context);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int widthSpec = 0;
        int heightSpec = 0;
        for (int i = 0; i < getPageCount(); i++) {
            CellLayout child = (CellLayout) getPageAt(i);
            child.rememberRawSize(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
            if (child.getMeasuredHeight() > MeasureSpec.getSize(heightSpec)) {
                widthSpec = child.getMeasuredWidthAndState();
                heightSpec = child.getMeasuredHeightAndState();
            }
        }
        mPersonator.measure(widthSpec, heightSpec);
        rememeberRawSize(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
    }

    @Override
    protected int getScrollMode() {
        return SmoothPagedView.X_LARGE_MODE;
    }

    @Override
    protected void onViewAdded(View child) {
        super.onViewAdded(child);
        if (!(child instanceof CellLayout)) {
            throw new IllegalArgumentException("A Workspace can only have CellLayout children.");
        }
        CellLayout cl = ((CellLayout) child);
        cl.setOnInterceptTouchListener(this);
        cl.setClickable(true);
        cl.enableHardwareLayers();
        cl.setCameraDistance(mDensity * CAMERA_DISTANCE);
        if (mCellLayoutPosition == null) {
            mCellLayoutPosition = new ArrayList<View>(MAX_WORKSPACE_CELLLAYOUT_NUMBER);
        }
        int temp = indexOfPage(child);
        if (!mCellLayoutPosition.contains(child)) {
            mCellLayoutPosition.add(child);
        }
    }
    
    /*private void debugArray() {
        for (int i = 0; i < mCellLayoutPosition.size(); i++) {
            Log.v("leeyb", "i:" + i + "\telement:" + mCellLayoutPosition.get(i));
        }
    }*/

    /**
     * @return The open folder on the current screen, or null if there is none
     */
    Folder getOpenFolder() {
        DragLayer dragLayer = mLauncher.getDragLayer();
        int count = dragLayer.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = dragLayer.getChildAt(i);
            if (child instanceof Folder) {
                Folder folder = (Folder) child;
                if (folder.getInfo().opened)
                    return folder;
            }
        }
        return null;
    }

    boolean isTouchActive() {
        return mTouchState != TOUCH_STATE_REST;
    }

    /**
     * Adds the specified child in the specified screen. The position and dimension of
     * the child are defined by x, y, spanX and spanY.
     *
     * @param child The child to add in one of the workspace's screens.
     * @param screen The screen in which to add the child.
     * @param x The X position of the child in the screen's grid.
     * @param y The Y position of the child in the screen's grid.
     * @param spanX The number of cells spanned horizontally by the child.
     * @param spanY The number of cells spanned vertically by the child.
     */
    void addInScreen(View child, long container, int screen, int x, int y, int spanX, int spanY) {
        addInScreen(child, container, screen, x, y, spanX, spanY, false);
    }

    /**
     * Adds the specified child in the specified screen. The position and dimension of
     * the child are defined by x, y, spanX and spanY.
     *
     * @param child The child to add in one of the workspace's screens.
     * @param screen The screen in which to add the child.
     * @param x The X position of the child in the screen's grid.
     * @param y The Y position of the child in the screen's grid.
     * @param spanX The number of cells spanned horizontally by the child.
     * @param spanY The number of cells spanned vertically by the child.
     * @param insert When true, the child is inserted at the beginning of the children list.
     */
    void addInScreen(View child, long container, int screen, int x, int y, int spanX, int spanY,
            boolean insert) {
        if (container == LauncherSettings.Favorites.CONTAINER_DESKTOP) {
            if (screen < 0 || screen >= getChildCount()) {
                Log.e(TAG, "The screen must be >= 0 and < " + getChildCount()
                    + " (was " + screen + "); skipping child");
                return;
            }
        }

        final CellLayout layout;
        if (container == LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
            layout = mLauncher.getHotseat().getLayout();
            child.setOnKeyListener(null);
            if (child instanceof BubbleTextView) {
            	BubbleTextView btv = (BubbleTextView) child;
            	btv.setCompoundDrawablePadding(0);
            }//added by leeyb
            
            // Hide folder title in the hotseat
//            if (child instanceof FolderIcon) {
//                ((FolderIcon) child).setTextVisible(false);
//            }

            if (screen < 0) {
                screen = mLauncher.getHotseat().getOrderInHotseat(x, y);
            } else {
                // Note: We do this to ensure that the hotseat is always laid out in the orientation
                // of the hotseat in order regardless of which orientation they were added
                x = mLauncher.getHotseat().getCellXFromOrder(screen);
                y = mLauncher.getHotseat().getCellYFromOrder(screen);
            }
        } else {
            // Show folder title if not in the hotseat
//            if (child instanceof FolderIcon) {
//                ((FolderIcon) child).setTextVisible(true);
//            }
            if (child instanceof BubbleTextView) {
            	BubbleTextView btv = (BubbleTextView) child;
            	btv.setCompoundDrawablePadding(getResources().getDimensionPixelSize(R.dimen.app_icon_drawable_padding));
            }//added by leeyb

            layout = (CellLayout) getChildAt(screen);
            child.setOnKeyListener(new IconKeyEventListener());
        }

        CellLayout.LayoutParams lp = (CellLayout.LayoutParams) child.getLayoutParams();
        if (lp == null) {
            lp = new CellLayout.LayoutParams(x, y, spanX, spanY);
        } else {
            lp.cellX = x;
            lp.cellY = y;
            lp.cellHSpan = spanX;
            lp.cellVSpan = spanY;
        }

        if (spanX < 0 && spanY < 0) {
            lp.isLockedToGrid = false;
        }

        // Get the canonical child id to uniquely represent this view in this screen
        int childId = LauncherModel.getCellLayoutChildId(container, screen, x, y, spanX, spanY);
        boolean markCellsAsOccupied = !(child instanceof Folder);
        if (!layout.addViewToCellLayout(child, insert ? 0 : -1, childId, lp, markCellsAsOccupied)) {
            // TODO: This branch occurs when the workspace is adding views
            // outside of the defined grid
            // maybe we should be deleting these items from the LauncherModel?
            Log.w(TAG, "Failed to add to item at (" + lp.cellX + "," + lp.cellY + ") to CellLayout");
        }

        if (!(child instanceof Folder)) {
            child.setHapticFeedbackEnabled(false);
            child.setOnLongClickListener(mLongClickListener);
        }
        if (child instanceof DropTarget) {
            mDragController.addDropTarget((DropTarget) child);
        }
    }

    /**
     * Check if the point (x, y) hits a given page.
     */
    private boolean hitsPage(int index, float x, float y) {
        final View page = getPageAt(index);
        if (page != null) {
            float[] localXY = { x, y };
            mapPointFromSelfToChild(page, localXY);
            return (localXY[0] >= 0 && localXY[0] < page.getWidth()
                    && localXY[1] >= 0 && localXY[1] < page.getHeight());
        }
        return false;
    }

    @Override
    protected boolean hitsPreviousPage(float x, float y) {
        // mNextPage is set to INVALID_PAGE whenever we are stationary.
        // Calculating "next page" this way ensures that you scroll to whatever page you tap on
        final int current = (mNextPage == INVALID_PAGE) ? mCurrentPage : mNextPage;

        // Only allow tap to next page on large devices, where there's significant margin outside
        // the active workspace
        return LauncherApplication.isScreenLarge() && hitsPage(current - 1, x, y);
    }

    @Override
    protected boolean hitsNextPage(float x, float y) {
        // mNextPage is set to INVALID_PAGE whenever we are stationary.
        // Calculating "next page" this way ensures that you scroll to whatever page you tap on
        final int current = (mNextPage == INVALID_PAGE) ? mCurrentPage : mNextPage;

        // Only allow tap to next page on large devices, where there's significant margin outside
        // the active workspace
        return LauncherApplication.isScreenLarge() && hitsPage(current + 1, x, y);
    }

    /**
     * Called directly from a CellLayout (not by the framework), after we've been added as a
     * listener via setOnInterceptTouchEventListener(). This allows us to tell the CellLayout
     * that it should intercept touch events, which is not something that is normally supported.
     */
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (LauncherLog.DEBUG_MOTION) {
            LauncherLog.d(TAG, "onTouch: v = " + v + ", event = " + event + ",mIsSwitchingState = "
                    + mIsSwitchingState + ",mState = " + mState + ",mScrollX = " + mScrollX);
        }
        return (isSmall() || mIsSwitchingState);
    }

    public boolean isSwitchingState() {
        return mIsSwitchingState;
    }

    protected void onWindowVisibilityChanged (int visibility) {
        mLauncher.onWindowVisibilityChanged(visibility);
    }

    @Override
    public boolean dispatchUnhandledMove(View focused, int direction) {
        if (isSmall() || mIsSwitchingState) {
            // when the home screens are shrunken, shouldn't allow side-scrolling
            return false;
        }
        return super.dispatchUnhandledMove(focused, direction);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
    	if (LauncherLog.DEBUG_MOTION) {
    	    LauncherLog.d(TAG, "onInterceptTouchEvent: ev = " + ev + ",mScrollX = " + mScrollX);
    	}
        switch (ev.getAction() & MotionEvent.ACTION_MASK) {
        case MotionEvent.ACTION_DOWN:
            mXDown = ev.getX();
            mYDown = ev.getY();
            break;
        case MotionEvent.ACTION_POINTER_UP:
        case MotionEvent.ACTION_UP:
            if (mTouchState == TOUCH_STATE_REST) {
                final CellLayout currentPage = (CellLayout) getPageAt(mCurrentPage);
                if (!currentPage.lastDownOnOccupiedCell()) {
                    onWallpaperTap(ev);
                }
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

	@Override
    protected void determineScrollingStart(MotionEvent ev) {
        if (!isSmall() && !mIsSwitchingState) {
            float deltaX = Math.abs(ev.getX() - mXDown);
            float deltaY = Math.abs(ev.getY() - mYDown);

            if (Float.compare(deltaX, 0f) == 0) return;

            float slope = deltaY / deltaX;
            float theta = (float) Math.atan(slope);

            if (deltaX > mTouchSlop || deltaY > mTouchSlop) {
                cancelCurrentPageLongPress();
            }

            if (theta > MAX_SWIPE_ANGLE) {
                // Above MAX_SWIPE_ANGLE, we don't want to ever start scrolling the workspace
                return;
            } else if (theta > START_DAMPING_TOUCH_SLOP_ANGLE) {
                // Above START_DAMPING_TOUCH_SLOP_ANGLE and below MAX_SWIPE_ANGLE, we want to
                // increase the touch slop to make it harder to begin scrolling the workspace. This 
                // results in vertically scrolling widgets to more easily. The higher the angle, the
                // more we increase touch slop.
                theta -= START_DAMPING_TOUCH_SLOP_ANGLE;
                float extraRatio = (float)
                        Math.sqrt((theta / (MAX_SWIPE_ANGLE - START_DAMPING_TOUCH_SLOP_ANGLE)));
                super.determineScrollingStart(ev, 1 + TOUCH_SLOP_DAMPING_FACTOR * extraRatio);
            } else {
                // Below START_DAMPING_TOUCH_SLOP_ANGLE, we don't do anything special
                super.determineScrollingStart(ev);
            }
        }
    }

    @Override
    protected boolean isScrollingIndicatorEnabled() {
        return mState != State.SPRING_LOADED;
    }

    protected void onPageBeginMoving() {
        super.onPageBeginMoving();

        if (isHardwareAccelerated()) {
            updateChildrenLayersEnabled();
        } else {
            if (mNextPage != INVALID_PAGE) {
                // we're snapping to a particular screen
                enableChildrenCache(mCurrentPage, mNextPage);
            } else {
                // this is when user is actively dragging a particular screen, they might
                // swipe it either left or right (but we won't advance by more than one screen)
                enableChildrenCache(mCurrentPage - 1, mCurrentPage + 1);
            }
        }

        // Only show page outlines as we pan if we are on large screen
        if (LauncherApplication.isScreenLarge()) {
            showOutlines();
        }
    }

    protected void onPageEndMoving() {
        super.onPageEndMoving();

        if (isHardwareAccelerated()) {
            updateChildrenLayersEnabled();
        } else {
            clearChildrenCache();
        }

        // Hide the outlines, as long as we're not dragging
        if (!mDragController.dragging()) {
            // Only hide page outlines as we pan if we are on large screen
            if (LauncherApplication.isScreenLarge()) {
                hideOutlines();
            }
        }
        mOverScrollMaxBackgroundAlpha = 0.0f;
        mOverScrollPageIndex = -1;

        if (mDelayedResizeRunnable != null) {
            mDelayedResizeRunnable.run();
            mDelayedResizeRunnable = null;
        }
    }

    @Override
    protected void notifyPageSwitchListener() {
        super.notifyPageSwitchListener();
        Launcher.setScreen(mCurrentPage);
    };

    // As a ratio of screen height, the total distance we want the parallax effect to span
    // horizontally
    private float wallpaperTravelToScreenWidthRatio(int width, int height) {
        float aspectRatio = width / (float) height;

        // At an aspect ratio of 16/10, the wallpaper parallax effect should span 1.5 * screen width
        // At an aspect ratio of 10/16, the wallpaper parallax effect should span 1.2 * screen width
        // We will use these two data points to extrapolate how much the wallpaper parallax effect
        // to span (ie travel) at any aspect ratio:

        final float ASPECT_RATIO_LANDSCAPE = 16/10f;
        final float ASPECT_RATIO_PORTRAIT = 10/16f;
        final float WALLPAPER_WIDTH_TO_SCREEN_RATIO_LANDSCAPE = 1.5f;
        final float WALLPAPER_WIDTH_TO_SCREEN_RATIO_PORTRAIT = 1.2f;

        // To find out the desired width at different aspect ratios, we use the following two
        // formulas, where the coefficient on x is the aspect ratio (width/height):
        //   (16/10)x + y = 1.5
        //   (10/16)x + y = 1.2
        // We solve for x and y and end up with a final formula:
        final float x =
            (WALLPAPER_WIDTH_TO_SCREEN_RATIO_LANDSCAPE - WALLPAPER_WIDTH_TO_SCREEN_RATIO_PORTRAIT) /
            (ASPECT_RATIO_LANDSCAPE - ASPECT_RATIO_PORTRAIT);
        final float y = WALLPAPER_WIDTH_TO_SCREEN_RATIO_PORTRAIT - x * ASPECT_RATIO_PORTRAIT;
        return x * aspectRatio + y;
    }

    // The range of scroll values for Workspace
    private int getScrollRange() {
        return getChildOffset(getChildCount() - 1) - getChildOffset(0);
    }

    protected void setWallpaperDimension() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        mLauncher.getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        final int maxDim = Math.max(displayMetrics.widthPixels, displayMetrics.heightPixels);
        final int minDim = Math.min(displayMetrics.widthPixels, displayMetrics.heightPixels);

        // We need to ensure that there is enough extra space in the wallpaper for the intended
        // parallax effects
        if (LauncherApplication.isScreenLarge()) {
            mWallpaperWidth = (int) (maxDim * wallpaperTravelToScreenWidthRatio(maxDim, minDim));
            mWallpaperHeight = maxDim;
        } else {
            mWallpaperWidth = Math.max((int) (minDim * WALLPAPER_SCREENS_SPAN), maxDim);
            mWallpaperHeight = maxDim;
        }
        new Thread("setWallpaperDimension") {
            public void run() {
                mWallpaperManager.suggestDesiredDimensions(mWallpaperWidth, mWallpaperHeight);
            }
        }.start();
    }

    public void setVerticalWallpaperOffset(float offset) {
        mWallpaperOffset.setFinalY(offset);
    }
    public float getVerticalWallpaperOffset() {
        return mWallpaperOffset.getCurrY();
    }
    public void setHorizontalWallpaperOffset(float offset) {
        mWallpaperOffset.setFinalX(offset);
    }
    public float getHorizontalWallpaperOffset() {
        return mWallpaperOffset.getCurrX();
    }

    private float wallpaperOffsetForCurrentScroll() {
        // The wallpaper travel width is how far, from left to right, the wallpaper will move
        // at this orientation. On tablets in portrait mode we don't move all the way to the
        // edges of the wallpaper, or otherwise the parallax effect would be too strong.
        int wallpaperTravelWidth = mWallpaperWidth;
        if (LauncherApplication.isScreenLarge()) {
            wallpaperTravelWidth = mWallpaperTravelWidth;
        }

        // Set wallpaper offset steps (1 / (number of screens - 1))
        mWallpaperManager.setWallpaperOffsetSteps(1.0f / (getChildCount() - 1), 1.0f);

        // For the purposes of computing the scrollRange and overScrollOffset, we assume
        // that mLayoutScale is 1. This means that when we're in spring-loaded mode,
        // there's no discrepancy between the wallpaper offset for a given page.
        float layoutScale = mLayoutScale;
        mLayoutScale = 1f;
        int scrollRange = getScrollRange();

        // Again, we adjust the wallpaper offset to be consistent between values of mLayoutScale
        float adjustedScrollX = Math.max(0, Math.min(mScrollX, mMaxScrollX));
        adjustedScrollX *= mWallpaperScrollRatio;
        mLayoutScale = layoutScale;

        float scrollProgress =
            adjustedScrollX / (float) scrollRange;
        float offsetInDips = wallpaperTravelWidth * scrollProgress;
        float offset = offsetInDips / (float) wallpaperTravelWidth;
        return offset;
    }
    private void syncWallpaperOffsetWithScroll() {
        final boolean enableWallpaperEffects = isHardwareAccelerated();
        if (enableWallpaperEffects) {
            mWallpaperOffset.setFinalX(wallpaperOffsetForCurrentScroll());
        }
    }

    public void updateWallpaperOffsetImmediately() {
        mUpdateWallpaperOffsetImmediately = true;
    }

    private void updateWallpaperOffsets() {
        boolean updateNow = false;
        boolean keepUpdating = true;
        if (mUpdateWallpaperOffsetImmediately) {
            updateNow = true;
            keepUpdating = false;
            mWallpaperOffset.jumpToFinal();
            mUpdateWallpaperOffsetImmediately = false;
        } else {
            updateNow = keepUpdating = mWallpaperOffset.computeScrollOffset();
        }
        if (updateNow) {
            if (mWindowToken != null) {
                mWallpaperManager.setWallpaperOffsets(mWindowToken,
                        mWallpaperOffset.getCurrX(), mWallpaperOffset.getCurrY());
            }
        }
        if (keepUpdating) {
            fastInvalidate();
        }
    }

    @Override
    protected void updateCurrentPageScroll() {
//        super.updateCurrentPageScroll();
        int newX = getMeasuredWidth() * mCurrentPage/*getChildOffset(mCurrentPage) - getRelativeChildOffset(mCurrentPage)*/;
        scrollTo(newX, 0);
        mScroller.setFinalX(newX);
        computeWallpaperScrollRatio(mCurrentPage);
    }

    @Override
    protected void snapToPage(int whichPage) {
        super.snapToPage(whichPage);
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "snapToPage: whichPage = " + whichPage + ",mScrollX = " + mScrollX);
        }
        computeWallpaperScrollRatio(whichPage);
    }

    private void computeWallpaperScrollRatio(int page) {
        // Here, we determine what the desired scroll would be with and without a layout scale,
        // and compute a ratio between the two. This allows us to adjust the wallpaper offset
        // as though there is no layout scale.
        float layoutScale = mLayoutScale;
        int scaled = getChildOffset(page) - getRelativeChildOffset(page);
        mLayoutScale = 1.0f;
        float unscaled = getChildOffset(page) - getRelativeChildOffset(page);
        mLayoutScale = layoutScale;
        if (scaled > 0) {
            mWallpaperScrollRatio = (1.0f * unscaled) / scaled;
        } else {
            mWallpaperScrollRatio = 1f;
        }
    }

    class WallpaperOffsetInterpolator {
        float mFinalHorizontalWallpaperOffset = 0.0f;
        float mFinalVerticalWallpaperOffset = 0.5f;
        float mHorizontalWallpaperOffset = 0.0f;
        float mVerticalWallpaperOffset = 0.5f;
        long mLastWallpaperOffsetUpdateTime;
        boolean mIsMovingFast;
        boolean mOverrideHorizontalCatchupConstant;
        float mHorizontalCatchupConstant = 0.35f;
        float mVerticalCatchupConstant = 0.35f;

        public WallpaperOffsetInterpolator() {
        }

        public void setOverrideHorizontalCatchupConstant(boolean override) {
            mOverrideHorizontalCatchupConstant = override;
        }

        public void setHorizontalCatchupConstant(float f) {
            mHorizontalCatchupConstant = f;
        }

        public void setVerticalCatchupConstant(float f) {
            mVerticalCatchupConstant = f;
        }

        public boolean computeScrollOffset() {
            if (Float.compare(mHorizontalWallpaperOffset, mFinalHorizontalWallpaperOffset) == 0 &&
                    Float.compare(mVerticalWallpaperOffset, mFinalVerticalWallpaperOffset) == 0) {
                mIsMovingFast = false;
                return false;
            }
            boolean isLandscape = mDisplayWidth > mDisplayHeight;

            long currentTime = System.currentTimeMillis();
            long timeSinceLastUpdate = currentTime - mLastWallpaperOffsetUpdateTime;
            timeSinceLastUpdate = Math.min((long) (1000/30f), timeSinceLastUpdate);
            timeSinceLastUpdate = Math.max(1L, timeSinceLastUpdate);

            float xdiff = Math.abs(mFinalHorizontalWallpaperOffset - mHorizontalWallpaperOffset);
            if (!mIsMovingFast && xdiff > 0.07) {
                mIsMovingFast = true;
            }

            float fractionToCatchUpIn1MsHorizontal;
            if (mOverrideHorizontalCatchupConstant) {
                fractionToCatchUpIn1MsHorizontal = mHorizontalCatchupConstant;
            } else if (mIsMovingFast) {
                fractionToCatchUpIn1MsHorizontal = isLandscape ? 0.5f : 0.75f;
            } else {
                // slow
                fractionToCatchUpIn1MsHorizontal = isLandscape ? 0.27f : 0.5f;
            }
            float fractionToCatchUpIn1MsVertical = mVerticalCatchupConstant;

            fractionToCatchUpIn1MsHorizontal /= 33f;
            fractionToCatchUpIn1MsVertical /= 33f;

            final float UPDATE_THRESHOLD = 0.00001f;
            float hOffsetDelta = mFinalHorizontalWallpaperOffset - mHorizontalWallpaperOffset;
            float vOffsetDelta = mFinalVerticalWallpaperOffset - mVerticalWallpaperOffset;
            boolean jumpToFinalValue = Math.abs(hOffsetDelta) < UPDATE_THRESHOLD &&
                Math.abs(vOffsetDelta) < UPDATE_THRESHOLD;

            // Don't have any lag between workspace and wallpaper on non-large devices
            if (!LauncherApplication.isScreenLarge() || jumpToFinalValue) {
                mHorizontalWallpaperOffset = mFinalHorizontalWallpaperOffset;
                mVerticalWallpaperOffset = mFinalVerticalWallpaperOffset;
            } else {
                float percentToCatchUpVertical =
                    Math.min(1.0f, timeSinceLastUpdate * fractionToCatchUpIn1MsVertical);
                float percentToCatchUpHorizontal =
                    Math.min(1.0f, timeSinceLastUpdate * fractionToCatchUpIn1MsHorizontal);
                mHorizontalWallpaperOffset += percentToCatchUpHorizontal * hOffsetDelta;
                mVerticalWallpaperOffset += percentToCatchUpVertical * vOffsetDelta;
            }

            mLastWallpaperOffsetUpdateTime = System.currentTimeMillis();
            return true;
        }

        public float getCurrX() {
            return mHorizontalWallpaperOffset;
        }

        public float getFinalX() {
            return mFinalHorizontalWallpaperOffset;
        }

        public float getCurrY() {
            return mVerticalWallpaperOffset;
        }

        public float getFinalY() {
            return mFinalVerticalWallpaperOffset;
        }

        public void setFinalX(float x) {
            mFinalHorizontalWallpaperOffset = Math.max(0f, Math.min(x, 1.0f));
        }

        public void setFinalY(float y) {
            mFinalVerticalWallpaperOffset = Math.max(0f, Math.min(y, 1.0f));
        }

        public void jumpToFinal() {
            mHorizontalWallpaperOffset = mFinalHorizontalWallpaperOffset;
            mVerticalWallpaperOffset = mFinalVerticalWallpaperOffset;
        }
    }

//    @Override
//    public void computeScroll() {
//        super.computeScroll();
//        syncWallpaperOffsetWithScroll();
//    }

    void showOutlines() {
        if (!isSmall() && !mIsSwitchingState) {
            if (mChildrenOutlineFadeOutAnimation != null) mChildrenOutlineFadeOutAnimation.cancel();
            if (mChildrenOutlineFadeInAnimation != null) mChildrenOutlineFadeInAnimation.cancel();
            mChildrenOutlineFadeInAnimation = ObjectAnimator.ofFloat(this, "childrenOutlineAlpha", 1.0f);
            mChildrenOutlineFadeInAnimation.setDuration(CHILDREN_OUTLINE_FADE_IN_DURATION);
            mChildrenOutlineFadeInAnimation.start();
        }
    }

    void hideOutlines() {
        if (!isSmall() && !mIsSwitchingState) {
            if (mChildrenOutlineFadeInAnimation != null) mChildrenOutlineFadeInAnimation.cancel();
            if (mChildrenOutlineFadeOutAnimation != null) mChildrenOutlineFadeOutAnimation.cancel();
            mChildrenOutlineFadeOutAnimation = ObjectAnimator.ofFloat(this, "childrenOutlineAlpha", 0.0f);
            mChildrenOutlineFadeOutAnimation.setDuration(CHILDREN_OUTLINE_FADE_OUT_DURATION);
            mChildrenOutlineFadeOutAnimation.setStartDelay(CHILDREN_OUTLINE_FADE_OUT_DELAY);
            mChildrenOutlineFadeOutAnimation.start();
        }
    }

    public void showOutlinesTemporarily() {
        if (!mIsPageMoving && !isTouchActive()) {
            snapToPage(mCurrentPage);
        }
    }

    public void setChildrenOutlineAlpha(float alpha) {
        mChildrenOutlineAlpha = alpha;
        for (int i = 0; i < getChildCount(); i++) {
            BackgroundAlphable ba = (BackgroundAlphable) getChildAt(i);
            ba.setBackgroundAlpha(alpha);
        }
    }

    public float getChildrenOutlineAlpha() {
        return mChildrenOutlineAlpha;
    }

    void disableBackground() {
        mDrawBackground = false;
    }
    void enableBackground() {
        mDrawBackground = true;
    }

    private void animateBackgroundGradient(float finalAlpha, boolean animated) {
        if (mBackground == null) return;
        if (mBackgroundFadeInAnimation != null) {
            mBackgroundFadeInAnimation.cancel();
            mBackgroundFadeInAnimation = null;
        }
        if (mBackgroundFadeOutAnimation != null) {
            mBackgroundFadeOutAnimation.cancel();
            mBackgroundFadeOutAnimation = null;
        }
        float startAlpha = getBackgroundAlpha();
        if (finalAlpha != startAlpha) {
            if (animated) {
                mBackgroundFadeOutAnimation = ValueAnimator.ofFloat(startAlpha, finalAlpha);
                mBackgroundFadeOutAnimation.addUpdateListener(new AnimatorUpdateListener() {
                    public void onAnimationUpdate(ValueAnimator animation) {
                        setBackgroundAlpha(((Float) animation.getAnimatedValue()).floatValue());
                    }
                });
                mBackgroundFadeOutAnimation.setInterpolator(new DecelerateInterpolator(1.5f));
                mBackgroundFadeOutAnimation.setDuration(BACKGROUND_FADE_OUT_DURATION);
                mBackgroundFadeOutAnimation.start();
            } else {
                setBackgroundAlpha(finalAlpha);
            }
        }
    }

    @Override
    public void setBackgroundAlpha(float alpha) {
        if (alpha != mBackgroundAlpha) {
            mBackgroundAlpha = alpha;
            invalidate();
        }
    }

    public float getBackgroundAlpha() {
        return mBackgroundAlpha;
    }

    /**
     * Due to 3D transformations, if two CellLayouts are theoretically touching each other,
     * on the xy plane, when one is rotated along the y-axis, the gap between them is perceived
     * as being larger. This method computes what offset the rotated view should be translated
     * in order to minimize this perceived gap.
     * @param degrees Angle of the view
     * @param width Width of the view
     * @param height Height of the view
     * @return Offset to be used in a View.setTranslationX() call
     */
    /*private float getOffsetXForRotation(float degrees, int width, int height) {
        mMatrix.reset();
        mCamera.save();
        mCamera.rotateY(Math.abs(degrees));
        mCamera.getMatrix(mMatrix);
        mCamera.restore();

        mMatrix.preTranslate(-width * 0.5f, -height * 0.5f);
        mMatrix.postTranslate(width * 0.5f, height * 0.5f);
        mTempFloat2[0] = width;
        mTempFloat2[1] = height;
        mMatrix.mapPoints(mTempFloat2);
        return (width - mTempFloat2[0]) * (degrees > 0.0f ? 1.0f : -1.0f);
    }*/

    /*float backgroundAlphaInterpolator(float r) {
        float pivotA = 0.1f;
        float pivotB = 0.4f;
        if (r < pivotA) {
            return 0;
        } else if (r > pivotB) {
            return 1.0f;
        } else {
            return (r - pivotA)/(pivotB - pivotA);
        }
    }*/

    /*float overScrollBackgroundAlphaInterpolator(float r) {
        float threshold = 0.08f;

        if (r > mOverScrollMaxBackgroundAlpha) {
            mOverScrollMaxBackgroundAlpha = r;
        } else if (r < mOverScrollMaxBackgroundAlpha) {
            r = mOverScrollMaxBackgroundAlpha;
        }

        return Math.min(r / threshold, 1.0f);
    }*/

    /*private void screenScrolledLargeUI(int screenCenter) {
        if (isSwitchingState()) return;
        boolean isInOverscroll = false;
        for (int i = 0; i < getChildCount(); i++) {
            CellLayout cl = (CellLayout) getChildAt(i);
            if (cl != null) {
                float scrollProgress = getScrollProgress(screenCenter, cl, i);
                float rotation = WORKSPACE_ROTATION * scrollProgress;
                float translationX = getOffsetXForRotation(rotation, cl.getWidth(), cl.getHeight());

                // If the current page (i) is being over scrolled, we use a different
                // set of rules for setting the background alpha multiplier.
                if (!isSmall()) {
                    if ((mOverScrollX < 0 && i == 0) || (mOverScrollX > mMaxScrollX &&
                            i == getChildCount() -1)) {
                        isInOverscroll = true;
                        rotation *= -1;
                        cl.setBackgroundAlphaMultiplier(
                                overScrollBackgroundAlphaInterpolator(Math.abs(scrollProgress)));
                        mOverScrollPageIndex = i;
                        cl.setOverScrollAmount(Math.abs(scrollProgress), i == 0);
                        cl.setPivotX(cl.getMeasuredWidth() * (i == 0 ? 0.75f : 0.25f));
                        cl.setPivotY(cl.getMeasuredHeight() * 0.5f);
                        cl.setOverscrollTransformsDirty(true);
                    } else if (mOverScrollPageIndex != i) {
                        cl.setBackgroundAlphaMultiplier(
                                backgroundAlphaInterpolator(Math.abs(scrollProgress)));
                    }
                }
                cl.setFastTranslationX(translationX);
                cl.setFastRotationY(rotation);
                if (mFadeInAdjacentScreens && !isSmall()) {
                    float alpha = 1 - Math.abs(scrollProgress);
                    cl.setFastAlpha(alpha);
                }
                cl.fastInvalidate();
            }
        }
        if (!isSwitchingState() && !isInOverscroll) {
            ((CellLayout) getChildAt(0)).resetOverscrollTransforms();
            ((CellLayout) getChildAt(getChildCount() - 1)).resetOverscrollTransforms();
        }
        invalidate();
    }

    private void screenScrolledStandardUI(int screenCenter) {
        if (mOverScrollX < 0 || mOverScrollX > mMaxScrollX) {
            int index = mOverScrollX < 0 ? 0 : getChildCount() - 1;
            CellLayout cl = (CellLayout) getChildAt(index);
            float scrollProgress = getScrollProgress(screenCenter, cl, index);
            cl.setOverScrollAmount(Math.abs(scrollProgress), index == 0);
            float rotation = - WORKSPACE_OVERSCROLL_ROTATION * scrollProgress;
            cl.setCameraDistance(mDensity * CAMERA_DISTANCE);
            cl.setPivotX(cl.getMeasuredWidth() * 0.5f(index == 0 ? 0.75f : 0.25f));
            cl.setPivotY(cl.getMeasuredHeight() * 0.5f);
            cl.setRotationY(rotation);
            cl.setOverscrollTransformsDirty(true);
            setFadeForOverScroll(Math.abs(scrollProgress));
        } else {
            if (mOverscrollFade != 0) {
                setFadeForOverScroll(0);
            }
            // We don't want to mess with the translations during transitions
            if (!isSwitchingState()) {
                ((CellLayout) getChildAt(0)).resetOverscrollTransforms();
                ((CellLayout) getChildAt(getChildCount() - 1)).resetOverscrollTransforms();
            }
        }
    }*/

//    @Override
//    protected void screenScrolled(int screenCenter) {
//        if (LauncherApplication.isScreenLarge()) {
//            // We don't call super.screenScrolled() here because we handle the adjacent pages alpha
//            // ourselves (for efficiency), and there are no scrolling indicators to update.
//            screenScrolledLargeUI(screenCenter);
//        } else {
//            super.screenScrolled(screenCenter);
//            screenScrolledStandardUI(screenCenter);
//        }
//    }

    @Override
    protected void overScroll(float amount) {
        if (LauncherApplication.isScreenLarge()) {
            dampedOverScroll(amount);
        } else {
            acceleratedOverScroll(amount);
        }
    }

    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "onAttachedToWindow: mWindowToken = " + mWindowToken);
        }
        mWindowToken = getWindowToken();
        computeScroll();
        mDragController.setWindowToken(mWindowToken);
    }

    protected void onDetachedFromWindow() {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "onDetachedFromWindow: mWindowToken = " + mWindowToken);
        }
        mWindowToken = null;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mFirstLayout && mCurrentPage >= 0 && mCurrentPage < getChildCount()) {
            mUpdateWallpaperOffsetImmediately = true;
        }
        super.onLayout(changed, left, top, right, bottom);

        if (LauncherLog.DEBUG_LAYOUT) {
            LauncherLog.d(TAG, "onLayout: changed = " + changed + ", left = " + left
                    + ", top = " + top + ", right = " + right + ", bottom = " + bottom 
                    + ",mSwitchStateAfterFirstLayout = " + mSwitchStateAfterFirstLayout);
        }
        // if shrinkToBottom() is called on initialization, it has to be deferred
        // until after the first call to onLayout so that it has the correct width
        if (mSwitchStateAfterFirstLayout) {
            mSwitchStateAfterFirstLayout = false;
            // shrink can trigger a synchronous onLayout call, so we
            // post this to avoid a stack overflow / tangled onLayout calls
            post(new Runnable() {
                public void run() {
                    changeState(mStateAfterFirstLayout, false);
                }
            });
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
//        if (!mPreviewsState) {
//            updateWallpaperOffsets();
//        }
        // Draw the background gradient if necessary
        if (mBackground != null && mBackgroundAlpha > 0.0f && mDrawBackground) {
            int alpha = (int) (mBackgroundAlpha * 255);
            mBackground.setAlpha(alpha);
            mBackground.setBounds(mScrollX, 0, mScrollX + getMeasuredWidth(),
                    getMeasuredHeight());
            mBackground.draw(canvas);
        }

        super.onDraw(canvas);
    }

    boolean isDrawingBackgroundGradient() {
        return (mBackground != null && mBackgroundAlpha > 0.0f && mDrawBackground);
    }

    @Override
    public void scrollTo (int x, int y) {
        super.scrollTo(x, y);
        syncChildrenLayersEnabledOnVisiblePages();
    }

    // This method just applies the value mChildrenLayersEnabled to all the pages that
    // will be rendered on the next frame.
    // We do this because calling setChildrenLayersEnabled on a view that's not
    // visible/rendered causes slowdowns on some graphics cards
    private void syncChildrenLayersEnabledOnVisiblePages() {
        if (mChildrenLayersEnabled) {
//            getVisiblePages(mTempVisiblePagesRange);
//            final int leftScreen = mTempVisiblePagesRange[0];
//            final int rightScreen = mTempVisiblePagesRange[1];
//            if (leftScreen != -1 && rightScreen != -1) {
                for (int i = 0/*leftScreen*/; i < getPageCount()/*= rightScreen*/; i++) {
                    ViewGroup page = (ViewGroup) getPageAt(i);
                    if (page.getVisibility() == VISIBLE &&
                            page.getAlpha() > ViewConfiguration.ALPHA_THRESHOLD) {
                        ((ViewGroup)getPageAt(i)).setChildrenLayersEnabled(true);
                    }
                }
//            }
        }
    }

    /**
     * This view will be draw as a celllayout add icon, which look the same as other cellLayout
     */
    private CellLayoutPersonator mPersonator;
    @Override
    public void draw(Canvas canvas) {
        CellLayoutPersonator local = mPersonator;//show icon here
        if (isPreviewsState() && getPageCount() < MAX_WORKSPACE_CELLLAYOUT_NUMBER) {
            canvas.save();
            canvas.translate(local.x, local.y);
            canvas.scale(local.scaleX, local.scaleY);
            local.draw(canvas);
            canvas.restore();
            local.visible = true;
        } else {
            local.visible = false;
        }
        super.draw(canvas);
    }
    
    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        //FIXME: Draw sth in this method directly will make HardwareAccelerate crash in framework 
        //sometimes, perhaps following block should be move to draw() instead.
        if (mInScrollArea && !LauncherApplication.isScreenLarge()) {
            final int width = getWidth();
            final int height = getHeight();
            final int pageHeight = getPageAt(0).getHeight();

            // Set the height of the outline to be the height of the page
            final int offset = (height - pageHeight - mPaddingTop - mPaddingBottom) / 2;
            final int paddingTop = mPaddingTop + offset;
            final int paddingBottom = mPaddingBottom + offset;

            final CellLayout leftPage = (CellLayout) getPageAt(mCurrentPage - 1);
            final CellLayout rightPage = (CellLayout) getPageAt(mCurrentPage + 1);

            if (leftPage != null && leftPage.getIsDragOverlapping()) {
                final Drawable d = getResources().getDrawable(R.drawable.page_hover_left_holo);
                d.setBounds(mScrollX, paddingTop, mScrollX + d.getIntrinsicWidth(),
                        height - paddingBottom);
                d.draw(canvas);
            } else if (rightPage != null && rightPage.getIsDragOverlapping()) {
                final Drawable d = getResources().getDrawable(R.drawable.page_hover_right_holo);
                d.setBounds(mScrollX + width - d.getIntrinsicWidth(), paddingTop, mScrollX + width,
                        height - paddingBottom);
                d.draw(canvas);
            }
        }
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        if (!mLauncher.isAllAppsVisible()) {
            final Folder openFolder = getOpenFolder();
            if (openFolder != null) {
                return openFolder.requestFocus(direction, previouslyFocusedRect);
            } else {
                return super.onRequestFocusInDescendants(direction, previouslyFocusedRect);
            }
        }
        return false;
    }

    @Override
    public int getDescendantFocusability() {
        if (isSmall()) {
            return ViewGroup.FOCUS_BLOCK_DESCENDANTS;
        }
        return super.getDescendantFocusability();
    }

    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if (!mLauncher.isAllAppsVisible()) {
            final Folder openFolder = getOpenFolder();
            if (openFolder != null) {
                openFolder.addFocusables(views, direction);
            } else {
                super.addFocusables(views, direction, focusableMode);
            }
        }
    }

    public boolean isSmall() {
        return mState == State.SMALL || mState == State.SPRING_LOADED
        		 || mState == State.PREVIEWS;
    }

    void enableChildrenCache(int fromPage, int toPage) {
        if (fromPage > toPage) {
            final int temp = fromPage;
            fromPage = toPage;
            toPage = temp;
        }

        final int screenCount = getPageCount();

        fromPage = Math.max(fromPage, 0);
        toPage = Math.min(toPage, screenCount - 1);

        for (int i = fromPage; i <= toPage; i++) {
            final CellLayout layout = (CellLayout) getPageAt(i);
            layout.setChildrenDrawnWithCacheEnabled(true);
            layout.setChildrenDrawingCacheEnabled(true);
        }
    }

    void clearChildrenCache() {
        final int screenCount = getPageCount();
        for (int i = 0; i < screenCount; i++) {
            final CellLayout layout = (CellLayout) getPageAt(i);
            layout.setChildrenDrawnWithCacheEnabled(false);
            // In software mode, we don't want the items to continue to be drawn into bitmaps
            if (!isHardwareAccelerated()) {
                layout.setChildrenDrawingCacheEnabled(false);
            }
        }
    }

    private void updateChildrenLayersEnabled() {
        boolean small = isSmall() || mIsSwitchingState;
        boolean dragging = mAnimatingViewIntoPlace || mIsDragOccuring;
        boolean enableChildrenLayers = small || dragging || isPageMoving();

        if (enableChildrenLayers != mChildrenLayersEnabled) {
            mChildrenLayersEnabled = enableChildrenLayers;
            // calling setChildrenLayersEnabled on a view that's not visible/rendered
            // causes slowdowns on some graphics cards, so we only disable it here and leave
            // the enabling to dispatchDraw
            if (!enableChildrenLayers) {
                for (int i = 0; i < getPageCount(); i++) {
                    ((ViewGroup)getChildAt(i)).setChildrenLayersEnabled(false);
                }
            }
        }
    }

    protected void onWallpaperTap(MotionEvent ev) {
        final int[] position = mTempCell;
        getLocationOnScreen(position);

        int pointerIndex = ev.getActionIndex();
        position[0] += (int) ev.getX(pointerIndex);
        position[1] += (int) ev.getY(pointerIndex);
        
        mWallpaperManager.sendWallpaperCommand(getWindowToken(),
                ev.getAction() == MotionEvent.ACTION_UP
                        ? WallpaperManager.COMMAND_TAP : WallpaperManager.COMMAND_SECONDARY_TAP,
                position[0], position[1], 0, null);
    }

    /*
     * This interpolator emulates the rate at which the perceived scale of an object changes
     * as its distance from a camera increases. When this interpolator is applied to a scale
     * animation on a view, it evokes the sense that the object is shrinking due to moving away
     * from the camera. 
     */
    static class ZInterpolator implements TimeInterpolator {
        private float focalLength;

        public ZInterpolator(float foc) {
            focalLength = foc;
        }

        public float getInterpolation(float input) {
            return (1.0f - focalLength / (focalLength + input)) /
                (1.0f - focalLength / (focalLength + 1.0f));
        }
    }

    /*
     * The exact reverse of ZInterpolator.
     */
    static class InverseZInterpolator implements TimeInterpolator {
        private ZInterpolator zInterpolator;
        public InverseZInterpolator(float foc) {
            zInterpolator = new ZInterpolator(foc);
        }
        public float getInterpolation(float input) {
            return 1 - zInterpolator.getInterpolation(1 - input);
        }
    }

    /*
     * ZInterpolator compounded with an ease-out.
     */
    static class ZoomOutInterpolator implements TimeInterpolator {
        private final DecelerateInterpolator decelerate = new DecelerateInterpolator(0.75f);
        private final ZInterpolator zInterpolator = new ZInterpolator(0.13f);

        public float getInterpolation(float input) {
            return decelerate.getInterpolation(zInterpolator.getInterpolation(input));
        }
    }

    /*
     * InvereZInterpolator compounded with an ease-out.
     */
    static class ZoomInInterpolator implements TimeInterpolator {
        private final InverseZInterpolator inverseZInterpolator = new InverseZInterpolator(0.35f);
        private final DecelerateInterpolator decelerate = new DecelerateInterpolator(3.0f);

        public float getInterpolation(float input) {
            return decelerate.getInterpolation(inverseZInterpolator.getInterpolation(input));
        }
    }

    private final ZoomInInterpolator mZoomInInterpolator = new ZoomInInterpolator();

	private Hotseat mHotseat;

    private ViewPropertyAnimator mGhost;

    private float mFinalScaleFactor;

    /*
    *
    * We call these methods (onDragStartedWithItemSpans/onDragStartedWithSize) whenever we
    * start a drag in Launcher, regardless of whether the drag has ever entered the Workspace
    *
    * These methods mark the appropriate pages as accepting drops (which alters their visual
    * appearance).
    *
    */
    public void onDragStartedWithItem(View v) {
    	if (LauncherLog.DEBUG_DRAG) {
    	    LauncherLog.d(TAG, "ondragStartedWithItem: v = " + v);
    	}
        final Canvas canvas = new Canvas();

        // We need to add extra padding to the bitmap to make room for the glow effect
        final int bitmapPadding = HolographicOutlineHelper.MAX_OUTER_BLUR_RADIUS;

        // The outline is used to visualize where the item will land if dropped
        mDragOutline = createDragOutline(v, canvas, bitmapPadding);
    }

    public void onDragStartedWithItem(PendingAddItemInfo info, Bitmap b, Paint alphaClipPaint) {
        final Canvas canvas = new Canvas();

        // We need to add extra padding to the bitmap to make room for the glow effect
        final int bitmapPadding = HolographicOutlineHelper.MAX_OUTER_BLUR_RADIUS;

        int[] size = estimateItemSize(info.spanX, info.spanY, info, false);

        // The outline is used to visualize where the item will land if dropped
        mDragOutline = createDragOutline(b, canvas, bitmapPadding, size[0], size[1], alphaClipPaint);
    }

    // we call this method whenever a drag and drop in Launcher finishes, even if Workspace was
    // never dragged over
    public void onDragStopped(boolean success) {
    	if (LauncherLog.DEBUG_DRAG) {
    	    LauncherLog.d(TAG, "onDragStopped: success = " + success);
    	}
        // In the success case, DragController has already called onDragExit()
        if (!success) {
            doDragExit(null);
        }
    }
    
    /**
     * Drag into or out from Garbage lead this method invoked. 
     * added by leeyb
     * @param enter true enter, otherwise exit
     */
    public void onDragGarbage(boolean enter) {
    	if (enter) {
    	    /*
    	     * case 3 : when we drag an icon close to garbage, we move previous icon back to its
    	     * original position, let the garbage prepare to delete dragged item
    	     */
    		if (mLastExchangeDragView != null) {
    			exchangePosition(null);
    		}
    		mHotseat.setBackgroundResource(R.drawable.bottom_bar_warn_right);
		} else {
			mHotseat.setBackgroundResource(R.drawable.bottom_bar);
		}
    }

    public void exitWidgetResizeMode() {
    	if (LauncherLog.DEBUG) {
    	    LauncherLog.d(TAG, "exitWidgetResizeMode.");
    	}
        DragLayer dragLayer = mLauncher.getDragLayer();
        dragLayer.clearAllResizeFrames();
    }

    private void initAnimationArrays() {
        final int childCount = getChildCount();
        if (mOldTranslationXs != null && mOldTranslationXs.length == childCount) return;
        mOldTranslationXs = new float[childCount];
        mOldTranslationYs = new float[childCount];
        mOldScaleXs = new float[childCount];
        mOldScaleYs = new float[childCount];
        mOldBackgroundAlphas = new float[childCount];
        mOldBackgroundAlphaMultipliers = new float[childCount];
        mOldAlphas = new float[childCount];
        mOldRotationYs = new float[childCount];
        mNewTranslationXs = new float[childCount];
        mNewTranslationYs = new float[childCount];
        mNewScaleXs = new float[childCount];
        mNewScaleYs = new float[childCount];
        mNewBackgroundAlphas = new float[childCount];
        mNewBackgroundAlphaMultipliers = new float[childCount];
        mNewAlphas = new float[childCount];
        mNewRotationYs = new float[childCount];
    }

    public void changeState(State shrinkState) {
        changeState(shrinkState, true);
    }

    void changeState(final State state, boolean animated) {
        changeState(state, animated, 0);
    }
    
	/**
	 * replaced by sumsung style animation , modified by leeyb
	 * @param state
	 * @param animated
	 * @param delay
	 */
    void changeState(final State state, boolean animated, int delay) {
        if (mState == state) {
            return;
        }
        if (mFirstLayout) {
            // (mFirstLayout == "first layout has not happened yet")
            // cancel any pending shrinks that were set earlier
            mSwitchStateAfterFirstLayout = false;
            mStateAfterFirstLayout = state;
//            return;
        }

        // Cancel any running transition animations
        if (mAnimator != null) mAnimator.cancel();
        mAnimator = new AnimatorSet();

        final State oldState = mState;
        final boolean oldStateIsNormal = (oldState == State.NORMAL);
        final boolean oldStateIsSmall = (oldState == State.SMALL);
        final boolean oldStateIsSpringLoaded = (oldState == State.SPRING_LOADED);
        final boolean oldStateIsPreviews = (oldState == State.PREVIEWS);
        mState = state;
        final boolean stateIsNormal = (state == State.NORMAL);
        final boolean stateIsSpringLoaded = (state == State.SPRING_LOADED);
        final boolean stateIsSmall = (state == State.SMALL);
        final boolean stateIsPreviews = (state == State.PREVIEWS);
        mFinalScaleFactor = 1.0f;
        float finalBackgroundAlpha = stateIsSpringLoaded || stateIsPreviews ? 1.0f : 0f;
        float translationX = 0;
        boolean zoomIn = true;

        if (state != State.NORMAL) {
            mFinalScaleFactor = mSpringLoadedShrinkFactor - (stateIsSmall ? 0.1f : 0);
            if ((oldStateIsNormal || oldStateIsSpringLoaded) && stateIsSmall) {
                zoomIn = false;
//                setLayoutScale(mFinalScaleFactor);
                updateChildrenLayersEnabled();
            }/* else {
                finalBackgroundAlpha = 1.0f;
            }*/
        } else if (oldStateIsPreviews && mRelayoutNeeded) {
            int targetPage = (mNextPage != INVALID_PAGE) ? mNextPage : mCurrentPage;
            final int count = mDeletedLayouts.size();
            for (int i = 0; i < count; i++) {
                CellLayout deleted = mDeletedLayouts.get(i);
                for (int j = 0; j < getChildCount(); j++) {
                    View member = getChildAt(j);
                    if (member != null && member == deleted) {
                        removeViewInLayout(deleted);//do not requestLayout, we will handle it later
                        invalidateCachedOffsets();//update mMaxScrollX and children Offsets
                        break;
                    }
                }
            }
            targetPage = Math.min(targetPage, getPageCount() - 1);
            layoutChildren();
            mLauncher.rememberDesktopOrder();
            mDeletedLayouts.clear();
            mRelayoutNeeded = false;
            if (targetPage != mCurrentPage) {
                int offset = (targetPage - mCurrentPage) * getMeasuredWidth();
                View member = null;
                for (int i = 0;; i++) {
                    if (member == mPersonator) {
                        break;
                    }
                    member = getChildAt(i);
                    if (member == null) {
                        member = mPersonator;
                    }
                    member.setX(member.getX() + offset);
                }
            }
            mNextPage = targetPage;
        }
        
        // Stop any scrolling, move to the current page right away
        setCurrentPage((mNextPage != INVALID_PAGE) ? mNextPage : mCurrentPage);
        
        // Initialize animation arrays for the first time if necessary
        initAnimationArrays();
        
        float translationY = 0;
        int duration;
        if (stateIsSpringLoaded) {
        	translationY = 0;
        	duration = getResources().getInteger(R.integer.config_workspaceSpringLoadTime);
		} else if (stateIsPreviews) {
		    int count = Math.min(MAX_WORKSPACE_CELLLAYOUT_NUMBER, getPageCount() + 1);
	        mMatrix = new int[count][2];
	        mFinalScaleFactor = previewMatrix(null, Style.ADW, mMatrix);
        	duration = getResources().getInteger(R.integer.config_workspacePreviewsTime);
		} else {
			if (oldStateIsSpringLoaded && stateIsNormal) {
				duration = getResources().getInteger(R.integer.config_workspaceSpringLoadTime);
			} else if (oldStateIsPreviews && stateIsNormal) {
				duration = getResources().getInteger(R.integer.config_workspacePreviewsTime);
			} else {
				duration = zoomIn ? 
		                getResources().getInteger(R.integer.config_workspaceUnshrinkTime) :
		                getResources().getInteger(R.integer.config_appsCustomizeWorkspaceShrinkTime);
			}
			translationY = !zoomIn ? -getMeasuredHeight() * 0.1f : 0f;
		}
        
        final float originalDscale = mScrollIndicator.getScaleX();
        final float originalDheight = mScrollIndicator.getTranslationY();
        final float originalDalpha = mScrollIndicator.getAlpha();
        final float targetDheight = !zoomIn ? -mScrollIndicator.getMeasuredHeight() : 0f;
    	final float originalSscale = mHotseat.getScaleX();
        final float originalSheight = mHotseat.getTranslationY();
        final float originalSalpha = mHotseat.getAlpha();
        final float targetSheight = !zoomIn ? -mHotseat.getMeasuredHeight() * 0.4f : 0f;
        final float targetDAlpha = stateIsSmall || stateIsPreviews ? 0f : 1f;
        final float targetSAlpha = stateIsSmall || stateIsPreviews ? 0f : 1f;
        final float targetDScale = stateIsSpringLoaded || stateIsPreviews ? 1f : mFinalScaleFactor;
        final float targetSScale = stateIsSpringLoaded ? 0.9f : mFinalScaleFactor;
        final float originalBgAlpha = mHotseat.getBackgroundAlpha();
        final float targetHotseatBgAlpha = stateIsPreviews || stateIsSpringLoaded ? 1f : 0f;
        final float originalHotseatBgY = mHotseat.getBackgroundTranslationY();
        final float targetHotseatBgY;
        if (stateIsSpringLoaded) {
        	targetHotseatBgY = mHotseat.getMeasuredHeight() * 0.2f;
		} else {
			if (stateIsPreviews) {
				targetHotseatBgY = mHotseat.getMeasuredHeight() * 0.25f;
			} else {
				targetHotseatBgY = mHotseat.getMeasuredHeight();
			}
		}
        final float originalPersonatorX = mPersonator.getX();
        final float originalPersonatorY = mPersonator.getY();
        final float originalPersonatorScaleX = mPersonator.getScaleX();
        final float originalPersonatorScaleY = mPersonator.getScaleY();
        final float originalPersonatorBgAlpha = mPersonator.getBackgroundAlpha();
        final float targetPersonatorScale = stateIsPreviews ? mFinalScaleFactor : 1.0f;
        final float targetPersonatorBgAlpha = stateIsPreviews ? 1.0f : 0.0f;
        final float targetPersonatorX = stateIsPreviews ? mMatrix[mMatrix.length - 1][0] : mPersonator.turnBack();
        final float targetPersonatorY = stateIsPreviews ? mMatrix[mMatrix.length - 1][1] : 0;
        
        for (int i = 0; i < getPageCount(); i++) {
            final CellLayout cl = (CellLayout) getPageAt(i);
            float rotation = 0f;
            float initialAlpha = cl.getAlpha();
//            float finalAlphaMultiplierValue = 1f;
            float finalAlpha = (!mFadeInAdjacentScreens || stateIsPreviews || stateIsSpringLoaded ||
                    (i == mCurrentPage)) ? 1f : 0f;

            // Determine the pages alpha during the state transition
//            if ((oldStateIsSmall && stateIsNormal) ||
//                (oldStateIsNormal && stateIsSmall)) {
//                // To/from workspace - only show the current page unless the transition is not
//                //                     animated and the animation end callback below doesn't run
//                if (i == mCurrentPage || !animated) {
//                    finalAlpha = /*1*/0f;
//                    finalAlphaMultiplierValue = 0f;
//                } else {
//                    initialAlpha = 0f;
//                    finalAlpha = 0f;
//                }
//            }
            if (oldStateIsPreviews && cl.isHome()) {
                mDefaultPage = i;
            }
			finalAlpha = !zoomIn ? 0f : 1f;

            // Update the rotation of the screen (don't apply rotation on Phone UI)
            if (LauncherApplication.isScreenLarge()) {
                if (i < mCurrentPage) {
                    rotation = WORKSPACE_ROTATION;
                } else if (i > mCurrentPage) {
                    rotation = -WORKSPACE_ROTATION;
                }
            }

            // If the screen is not xlarge, then don't rotate the CellLayouts
            // NOTE: If we don't update the side pages alpha, then we should not hide the side
            //       pages. see unshrink().
//            if (LauncherApplication.isScreenLarge()) {
//                translationX = getOffsetXForRotation(rotation, cl.getWidth(), cl.getHeight());
//            }
            

            mOldAlphas[i] = initialAlpha;
            mNewAlphas[i] = finalAlpha;
            float pivotX = cl.getMeasuredWidth() * 0.5f;
            float pivotY = cl.getMeasuredHeight() * 0.5f;
            if (stateIsPreviews || (oldStateIsPreviews && stateIsNormal)) {
            	pivotX = 0f;
            	pivotY = 0f;
			} else {
				cl.setBackgroundAlpha(0.0f);
			}
            cl.setBackgroundFocused(false);
            cl.setPivotX(pivotX);
            cl.setPivotY(pivotY);//ready for transformation
            if (stateIsPreviews || stateIsSpringLoaded) {
				cl.setFastRotationY(0.0f);
				cl.setFastTranslationX(0.0f);
			}
            if (animated) {
                mOldTranslationXs[i] = cl.getTranslationX();
                mOldTranslationYs[i] = cl.getTranslationY();
                mOldScaleXs[i] = cl.getScaleX();
                mOldScaleYs[i] = cl.getScaleY();
                mOldBackgroundAlphas[i] = cl.getBackgroundAlpha();
//                mOldBackgroundAlphaMultipliers[i] = cl.getBackgroundAlphaMultiplier();
                mOldRotationYs[i] = cl.getRotationY();
                if (stateIsPreviews && mMatrix != null) {
                	mNewTranslationXs[i] = mMatrix[i][0] - cl.getX();
                	mNewTranslationYs[i] = mMatrix[i][1] - cl.getY();
//                	mCellLayoutPosition.put(i, cl);
				} else {
					mNewTranslationXs[i] = translationX;
	                mNewTranslationYs[i] = translationY;
				}
                mNewScaleXs[i] = mFinalScaleFactor;
                mNewScaleYs[i] = mFinalScaleFactor;
                mNewBackgroundAlphas[i] = finalBackgroundAlpha;
//                mNewBackgroundAlphaMultipliers[i] = finalAlphaMultiplierValue;
                mNewRotationYs[i] = rotation;
            } else {
            	if (stateIsPreviews && mMatrix != null) {
            		cl.setTranslationX(mMatrix[i][0] - cl.getX());
            		cl.setTranslationY(mMatrix[i][1] - cl.getY());
//            		mCellLayoutPosition.put(i, cl);
            	} else {
            		cl.setTranslationX(translationX);
            		cl.setTranslationY(translationY);
            	}
                cl.setScaleX(mFinalScaleFactor);
                cl.setScaleY(mFinalScaleFactor);
                cl.setBackgroundAlpha(finalBackgroundAlpha);
//                cl.setBackgroundAlphaMultiplier(finalAlphaMultiplierValue);
                cl.setAlpha(finalAlpha);
                cl.setRotationY(rotation);
                mChangeStateAnimationListener.onAnimationEnd(null);
            }
        }
        if (animated) {
            ValueAnimator engine = ValueAnimator.ofFloat(0f, 1f).setDuration(duration);
            engine.setInterpolator(mZoomInInterpolator);
            if (stateIsPreviews || oldStateIsPreviews) {
                 engine.addListener(new AnimatorListenerAdapter() {
                     @Override
                     public void onAnimationEnd(android.animation.Animator animation) {
                         mPreviewsSwitching = false;
                         if (oldStateIsPreviews) {
                             for (int i = 0; i < getChildCount(); i++) {
                                 CellLayout layout = (CellLayout) getChildAt(i);
                                 layout.setBackgroundResource(R.drawable.panel_frame);
                             }
                         } else if (stateIsPreviews) {
                             mPreviewsState = true;
                             mScrollIndicator.setVisibility(View.INVISIBLE);
                        }
                     }
                     @Override
                     public void onAnimationStart(Animator animation) {
                         mPreviewsSwitching = true;
                         if (stateIsPreviews) {
                             for (int i = 0; i < getChildCount(); i++) {
                                 CellLayout layout = (CellLayout) getChildAt(i);
                                 layout.setBackgroundResource(R.drawable.homescreen_quick_view_bg);
                             }
                         } else if (oldStateIsPreviews) {
                             mPreviewsState = false;
                             mScrollIndicator.setVisibility(View.VISIBLE);
                        }
                     }
                 });
             }
            engine.addUpdateListener(new LauncherAnimatorUpdateListener() {
                public void onAnimationUpdate(float a, float b) {
                    mTransitionProgress = b;
                    if (b == 0f) {
                        // an optimization, but not required
                        return;
                    }
                    invalidate();
                    for (int i = 0; i < getPageCount(); i++) {
                        final CellLayout cl = (CellLayout) getPageAt(i);
                        cl.invalidate();
                        cl.setFastTranslationX(a * mOldTranslationXs[i] + b * mNewTranslationXs[i]);
                        cl.setFastTranslationY(a * mOldTranslationYs[i] + b * mNewTranslationYs[i]);
                        cl.setFastScaleX(a * mOldScaleXs[i] + b * mNewScaleXs[i]);
                        cl.setFastScaleY(a * mOldScaleYs[i] + b * mNewScaleYs[i]);
                        cl.setFastBackgroundAlpha(
                                a * mOldBackgroundAlphas[i] + b * mNewBackgroundAlphas[i]);
//                        cl.setBackgroundAlphaMultiplier(a * mOldBackgroundAlphaMultipliers[i] +
//                                b * mNewBackgroundAlphaMultipliers[i]); 
                        cl.setFastAlpha(a * mOldAlphas[i] + b * mNewAlphas[i]);
                        cl.invalidate();
                    }
                    mScrollIndicator.setTranslationY(a * originalDheight + b * targetDheight);
                    mScrollIndicator.setScaleX(a * originalDscale + b * targetDScale);
                    mScrollIndicator.setScaleY(a * originalDscale + b * targetDScale);
                    mScrollIndicator.setAlpha(a * originalDalpha + b * targetDAlpha);
                    mHotseat.setTranslationY(a * originalSheight + b * targetSheight);
                    mHotseat.setScaleX(a * originalSscale + b * targetSScale);
                    mHotseat.setScaleY(a * originalSscale + b * targetSScale);
                    mHotseat.setAlpha(a * originalSalpha + b * targetSAlpha);
                    mHotseat.setBackgroundAlpha(a * originalBgAlpha + b * targetHotseatBgAlpha);
                    mHotseat.setBackgroundTranslationY(a * originalHotseatBgY + b * targetHotseatBgY);
                    mPersonator.setX(a * originalPersonatorX + b * targetPersonatorX);
                    mPersonator.setY(a * originalPersonatorY + b * targetPersonatorY);
                    mPersonator.setScaleX(a * originalPersonatorScaleX + b * targetPersonatorScale);
                    mPersonator.setScaleY(a * originalPersonatorScaleY + b * targetPersonatorScale);
                    mPersonator.setBackgroundAlpha(a * originalPersonatorBgAlpha + b * targetPersonatorBgAlpha);
//                    mHotseat.invalidate();
                    syncChildrenLayersEnabledOnVisiblePages();
                }
            });
//            ValueAnimator rotationAnim = null;
//            if (stateIsSpringLoaded) {
//            	final float originalBgTranslationY = mHotseat.getBackgroundTranslationY();
//            	rotationAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(duration);
//            	rotationAnim.addUpdateListener(new LauncherAnimatorUpdateListener() {
//            		public void onAnimationUpdate(float a, float b) {
//            			if (b == 0f) {
//            				// an optimization, but not required
//            				return;
//            			}
//            			
//            		}
//            	});
//			}
//            mAnimator.playTogether(engine/*, rotationAnim*/);
            engine.setStartDelay(delay);
            // If we call this when we're not animated, onAnimationEnd is never called on
            // the listener; make sure we only use the listener when we're actually animating
            engine.addListener(mChangeStateAnimationListener);
            engine.start();
        } else {
        	mScrollIndicator.setTranslationY(targetSheight);
            mScrollIndicator.setScaleX(targetDScale);
            mScrollIndicator.setScaleY(targetDScale);
            mScrollIndicator.setAlpha(targetDAlpha);
            mHotseat.setTranslationY(targetSheight);
            mHotseat.setBackgroundTranslationY(targetHotseatBgY);
            mHotseat.setBackgroundResource(R.drawable.bottom_bar);
            mHotseat.setScaleX(targetSScale);
            mHotseat.setScaleY(targetSScale);
            mHotseat.setAlpha(targetSAlpha);
            mPreviewsState = stateIsPreviews;
        }

        if (stateIsSpringLoaded) {
            // Right now we're covered by Apps Customize
            // Show the background gradient immediately, so the gradient will
            // be showing once AppsCustomize disappears
            animateBackgroundGradient(getResources().getInteger(
                    R.integer.config_appsCustomizeSpringLoadedBgAlpha) / 100f, false);
        } else {
            // Fade the background gradient away
            animateBackgroundGradient(0f, true);
        }
        syncChildrenLayersEnabledOnVisiblePages();
    }

    private void clearCellLayout(CellLayout target) {
        CellLayoutChildren inner = target.getChildrenLayout();
        for (int i = 0; i < inner.getChildCount(); i++) {
            Object victim = inner.getChildAt(i).getTag();
            if (victim instanceof ShortcutInfo) {
                LauncherModel.deleteItemFromDatabase(mLauncher, (ItemInfo) victim);
            } else if (victim instanceof FolderInfo) {
                // Remove the folder from the workspace and delete the contents from launcher model
                FolderInfo folderInfo = (FolderInfo) victim;
                mLauncher.removeFolder(folderInfo);
                LauncherModel.deleteFolderContentsFromDatabase(mLauncher, folderInfo);
            } else if (victim instanceof LauncherAppWidgetInfo) {
                // Remove the widget from the workspace
                mLauncher.removeAppWidget((LauncherAppWidgetInfo) victim);
                LauncherModel.deleteItemFromDatabase(mLauncher, (ItemInfo) victim);

                final LauncherAppWidgetInfo launcherAppWidgetInfo = (LauncherAppWidgetInfo) victim;
                final LauncherAppWidgetHost appWidgetHost = mLauncher.getAppWidgetHost();
                if (appWidgetHost != null) {
                    // Deleting an app widget ID is a void call but writes to disk before returning
                    // to the caller...
                    new Thread("deleteAppWidgetId") {
                        public void run() {
                            appWidgetHost.deleteAppWidgetId(launcherAppWidgetInfo.appWidgetId);
                        }
                    }.start();
                }
            } else {
                throw new IllegalStateException("delete crash here");
            }
        }
        target.removeAllViewsInLayout();
    }

    /**
     * Draw the View v into the given Canvas.
     *
     * @param v the view to draw
     * @param destCanvas the canvas to draw on
     * @param padding the horizontal and vertical padding to use when drawing
     */
    private void drawDragView(View v, Canvas destCanvas, int padding, boolean pruneToDrawable) {
        final Rect clipRect = mTempRect;
        v.getDrawingRect(clipRect);

        boolean textVisible = false;

        destCanvas.save();
        if (v instanceof TextView && pruneToDrawable) {
            Drawable d = ((TextView) v).getCompoundDrawables()[1];
            clipRect.set(0, 0, d.getIntrinsicWidth() + padding, d.getIntrinsicHeight() + padding);
            destCanvas.translate(padding / 2, padding / 2);
            d.draw(destCanvas);
        } else {
            if (v instanceof FolderIcon) {
                // For FolderIcons the text can bleed into the icon area, and so we need to
                // hide the text completely (which can't be achieved by clipping).
                if (((FolderIcon) v).getTextVisible()) {
                    ((FolderIcon) v).setTextVisible(false);
                    textVisible = true;
                }
            } else if (v instanceof BubbleTextView) {
                final BubbleTextView tv = (BubbleTextView) v;
                clipRect.bottom = tv.getExtendedPaddingTop() - (int) BubbleTextView.PADDING_V +
                        tv.getLayout().getLineTop(0);
            } else if (v instanceof TextView) {
                final TextView tv = (TextView) v;
                clipRect.bottom = tv.getExtendedPaddingTop() - tv.getCompoundDrawablePadding() +
                        tv.getLayout().getLineTop(0);
            }
            destCanvas.translate(-v.getScrollX() + padding / 2, -v.getScrollY() + padding / 2);
            destCanvas.clipRect(clipRect, Op.REPLACE);
            v.draw(destCanvas);

            // Restore text visibility of FolderIcon if necessary
            if (textVisible) {
                ((FolderIcon) v).setTextVisible(true);
            }
        }
        destCanvas.restore();
    }

    /**
     * Returns a new bitmap to show when the given View is being dragged around.
     * Responsibility for the bitmap is transferred to the caller.
     * @param outline added by leeyb, we don't need outline when exchange drag happens
     */
    public Bitmap createDragBitmap(View v, Canvas canvas, int padding, boolean outline) {
        final int outlineColor = getResources().getColor(android.R.color.white/*holo_blue_light*/);
        Bitmap b;

        if (v instanceof TextView) {
            Drawable d = ((TextView) v).getCompoundDrawables()[1];
            b = Bitmap.createBitmap(d.getIntrinsicWidth() + padding,
                    d.getIntrinsicHeight() + padding, Bitmap.Config.ARGB_8888);
        } else {
            b = Bitmap.createBitmap(
                    v.getWidth() + padding, v.getHeight() + padding, Bitmap.Config.ARGB_8888);
        }

        canvas.setBitmap(b);
        drawDragView(v, canvas, padding, true);
        if (outline) {
        	mOutlineHelper.applyOuterBlur(b, canvas, outlineColor);
        	canvas.drawColor(mDragViewMultiplyColor, PorterDuff.Mode.MULTIPLY);
		}
        canvas.setBitmap(null);

        return b;
    }

    /**
     * Returns a new bitmap to be used as the object outline, e.g. to visualize the drop location.
     * Responsibility for the bitmap is transferred to the caller.
     */
    private Bitmap createDragOutline(View v, Canvas canvas, int padding) {
        final int outlineColor = getResources().getColor(android.R.color.white/*holo_blue_light*/);
        final Bitmap b = Bitmap.createBitmap(
                v.getWidth() + padding, v.getHeight() + padding, Bitmap.Config.ARGB_8888);

        canvas.setBitmap(b);
        drawDragView(v, canvas, padding, true);
        mOutlineHelper.applyMediumExpensiveOutlineWithBlur(b, canvas, outlineColor, outlineColor);
        canvas.setBitmap(null);
        return b;
    }

    /**
     * Returns a new bitmap to be used as the object outline, e.g. to visualize the drop location.
     * Responsibility for the bitmap is transferred to the caller.
     */
    private Bitmap createDragOutline(Bitmap orig, Canvas canvas, int padding, int w, int h,
            Paint alphaClipPaint) {
        final int outlineColor = getResources().getColor(android.R.color.white/*holo_blue_light*/);
        final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        canvas.setBitmap(b);

        Rect src = new Rect(0, 0, orig.getWidth(), orig.getHeight());
        float scaleFactor = Math.min((w - padding) / (float) orig.getWidth(),
                (h - padding) / (float) orig.getHeight());
        int scaledWidth = (int) (scaleFactor * orig.getWidth());
        int scaledHeight = (int) (scaleFactor * orig.getHeight());
        Rect dst = new Rect(0, 0, scaledWidth, scaledHeight);

        // center the image
        dst.offset((w - scaledWidth) / 2, (h - scaledHeight) / 2);

        canvas.drawBitmap(orig, src, dst, null);
        mOutlineHelper.applyMediumExpensiveOutlineWithBlur(b, canvas, outlineColor, outlineColor,
                alphaClipPaint);
        canvas.setBitmap(null);

        return b;
    }

    /**
     * Creates a drag outline to represent a drop (that we don't have the actual information for
     * yet).  May be changed in the future to alter the drop outline slightly depending on the
     * clip description mime data.
     */
    private Bitmap createExternalDragOutline(Canvas canvas, int padding) {
        Resources r = getResources();
        final int outlineColor = r.getColor(android.R.color.white/*holo_blue_light*/);
        final int iconWidth = r.getDimensionPixelSize(R.dimen.workspace_cell_width);
        final int iconHeight = r.getDimensionPixelSize(R.dimen.workspace_cell_height);
        final int rectRadius = r.getDimensionPixelSize(R.dimen.external_drop_icon_rect_radius);
        final int inset = (int) (Math.min(iconWidth, iconHeight) * 0.2f);
        final Bitmap b = Bitmap.createBitmap(
                iconWidth + padding, iconHeight + padding, Bitmap.Config.ARGB_8888);

        canvas.setBitmap(b);
        canvas.drawRoundRect(new RectF(inset, inset, iconWidth - inset, iconHeight - inset),
                rectRadius, rectRadius, mExternalDragOutlinePaint);
        mOutlineHelper.applyMediumExpensiveOutlineWithBlur(b, canvas, outlineColor, outlineColor);
        canvas.setBitmap(null);
        return b;
    }

    void startDrag(CellLayout.CellInfo cellInfo) {
        View child = cellInfo.cell;
       mCellPosition.clear();
    	mLastExchangeDragView = null;
        if (LauncherLog.DEBUG_DRAG) {
            LauncherLog.d(TAG, "startDrag cellInfo = " + cellInfo + ",child = " + child);
        }

        if (child != null && child.getTag() == null) {
            // Abnormal case, if user long press on all apps button and then
            // long press on other shortcuts in hotseat, the dragInfo will be
            // null, exception will happen, return directly.
            LauncherLog.i(TAG, "Abnormal start drag: cellInfo = " + cellInfo + ",child = " + child);
            return;
        }

        // Make sure the drag was started by a long press as opposed to a long click.
        if (!child.isInTouchMode()) {
        	if (LauncherLog.DEBUG) {
        	    LauncherLog.i(TAG, "The child " + child + " is not in touch mode.");
        	}
            return;
        }

        mDragInfo = cellInfo;
        child.setVisibility(GONE);

        child.clearFocus();
        child.setPressed(false);

        final Canvas canvas = new Canvas();

        // We need to add extra padding to the bitmap to make room for the glow effect
        final int bitmapPadding = HolographicOutlineHelper.MAX_OUTER_BLUR_RADIUS;

        // The outline is used to visualize where the item will land if dropped
        mDragOutline = createDragOutline(child, canvas, bitmapPadding);
        beginDragShared(child, this);
    }

    public void beginDragShared(View child, DragSource source) {
        Resources r = getResources();
        if (!(source instanceof AppsCustomizePagedView)) {
            changeState(State.SPRING_LOADED, true);
        }
        
        // We need to add extra padding to the bitmap to make room for the glow effect
        final int bitmapPadding = HolographicOutlineHelper.MAX_OUTER_BLUR_RADIUS;

        // The drag bitmap follows the touch point around on the screen
        Bitmap b = createDragBitmap(child, new Canvas(), bitmapPadding, false);
        final int bmpWidth = b.getWidth();
        mLauncher.getDragLayer().getLocationInDragLayer(child, mTempXY);

        final int dragLayerX = (int) mTempXY[0] + (child.getWidth() - bmpWidth) / 2;
        int dragLayerY = mTempXY[1] - bitmapPadding / 2;
        if (LauncherLog.DEBUG_DRAG) {
            LauncherLog.d(TAG, "beginDragShared: child = " + child + ", source = " + source
                    + ",dragLayerX = " + dragLayerX + ",dragLayerY = " + dragLayerY);
        }
        Point dragVisualizeOffset = null;
        Rect dragRect = null;
        if (child instanceof BubbleTextView || child instanceof PagedViewIcon) {
            int iconSize = r.getDimensionPixelSize(R.dimen.app_icon_size);
            int iconPaddingTop = r.getDimensionPixelSize(R.dimen.app_icon_padding_top);
            int top = child.getPaddingTop();
            int left = (bmpWidth - iconSize) / 2;
            int right = left + iconSize;
            int bottom = top + iconSize;
            dragLayerY += top;
            // Note: The drag region is used to calculate drag layer offsets, but the
            // dragVisualizeOffset in addition to the dragRect (the size) to position the outline.
            dragVisualizeOffset = new Point(-bitmapPadding / 2, iconPaddingTop - bitmapPadding / 2);
            dragRect = new Rect(left, top, right, bottom);
        } else if (child instanceof FolderIcon) {
            int previewSize = r.getDimensionPixelSize(R.dimen.folder_preview_size);
            dragRect = new Rect(0, 0, child.getWidth(), previewSize);
        }

        // Clear the pressed state if necessary
        if (child instanceof BubbleTextView) {
            BubbleTextView icon = (BubbleTextView) child;                                                                   
            icon.clearPressedOrFocusedBackground();
        }

        mDragController.startDrag(b, dragLayerX, dragLayerY, source, child.getTag(),
                DragController.DRAG_ACTION_MOVE, dragVisualizeOffset, dragRect);
        b.recycle();
    }

    void addApplicationShortcut(ShortcutInfo info, CellLayout target, long container, int screen,
            int cellX, int cellY, boolean insertAtFirst, int intersectX, int intersectY) {
        View view = mLauncher.createShortcut(R.layout.application, target, (ShortcutInfo) info);

        final int[] cellXY = new int[2];
        target.findCellForSpanThatIntersects(cellXY, 1, 1, intersectX, intersectY);
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "addApplicationShortcut: info = " + info + ", view = "
                    + view + ", container = " + container + ", screen = " + screen
                    + ", cellXY[0] = " + cellXY[0] + ", cellXY[1] = " + cellXY[1]
                    + ", insertAtFirst = " + insertAtFirst);
        }
        addInScreen(view, container, screen, cellXY[0], cellXY[1], 1, 1, insertAtFirst);
        LauncherModel.addOrMoveItemInDatabase(mLauncher, info, container, screen, cellXY[0],
                cellXY[1]);
    }

    public boolean transitionStateShouldAllowDrop() {
        return (!isSwitchingState() || mTransitionProgress > 0.5f);
    }

    /**
     * {@inheritDoc}
     */
    public boolean acceptDrop(DragObject d) {
        // If it's an external drop (e.g. from All Apps), check if it should be accepted
        if (d.dragSource != this) {
            // Don't accept the drop if we're not over a screen at time of drop
            if (mDragTargetLayout == null) {
                return false;
            }
            if (!transitionStateShouldAllowDrop()) return false;

            mDragViewVisualCenter = getDragViewVisualCenter(d.x, d.y, d.xOffset, d.yOffset,
                    d.dragView, mDragViewVisualCenter);

            // We want the point to be mapped to the dragTarget.
            if (mLauncher.isHotseatLayout(mDragTargetLayout)) {
                mapPointFromSelfToSibling(mLauncher.getHotseat(), mDragViewVisualCenter);
            } else {
                mapPointFromSelfToChild(mDragTargetLayout, mDragViewVisualCenter, null);
            }

            int spanX = 1;
            int spanY = 1;
            View ignoreView = null;
            if (mDragInfo != null) {
                final CellLayout.CellInfo dragCellInfo = mDragInfo;
                spanX = dragCellInfo.spanX;
                spanY = dragCellInfo.spanY;
                ignoreView = dragCellInfo.cell;
            } else {
                final ItemInfo dragInfo = (ItemInfo) d.dragInfo;
                spanX = dragInfo.spanX;
                spanY = dragInfo.spanY;
            }

            mTargetCell = findNearestArea((int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1], spanX, spanY, mDragTargetLayout, mTargetCell);
            if (willCreateUserFolder((ItemInfo) d.dragInfo, mDragTargetLayout, mTargetCell, true)) {
                return true;
            }
            if (willAddToExistingUserFolder((ItemInfo) d.dragInfo, mDragTargetLayout,
                    mTargetCell)) {
                return true;
            }


            // Don't accept the drop if there's no room for the item
            if (!mDragTargetLayout.findCellForSpanIgnoring(null, spanX, spanY, ignoreView)) {
                // Don't show the message if we are dropping on the AllApps button and the hotseat
                // is full
                if (mTargetCell != null && mLauncher.isHotseatLayout(mDragTargetLayout)) {
                    Hotseat hotseat = mLauncher.getHotseat();
                    if (Hotseat.isAllAppsButtonRank(
                            hotseat.getOrderInHotseat(mTargetCell[0], mTargetCell[1]))) {
                        return false;
                    }
                }

                mLauncher.showOutOfSpaceMessage();
                return false;
            }
        }
        return true;
    }

    boolean willCreateUserFolder(ItemInfo info, CellLayout target, int[] targetCell,
            boolean considerTimeout) {
        View dropOverView = target.getChildAt(targetCell[0], targetCell[1]);

        boolean hasntMoved = false;
        if (mDragInfo != null) {
            CellLayout cellParent = getParentCellLayoutForView(mDragInfo.cell);
            hasntMoved = (mDragInfo.cellX == targetCell[0] &&
                    mDragInfo.cellY == targetCell[1]) && (cellParent == target);
        }

        if (dropOverView == null || hasntMoved || (considerTimeout && !mCreateUserFolderOnDrop)) {
            return false;
        }

        boolean aboveShortcut = (dropOverView.getTag() instanceof ShortcutInfo);
        boolean willBecomeShortcut =
                (info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION ||
                info.itemType == LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT);

        return (aboveShortcut && willBecomeShortcut);
    }

    boolean willAddToExistingUserFolder(Object dragInfo, CellLayout target, int[] targetCell) {
        View dropOverView = target.getChildAt(targetCell[0], targetCell[1]);
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "willAddToExistingUserFolder dragInfo = " + dragInfo + ", target = "
                    + target + ", targetCell[0] = " + targetCell[0] + ", targetCell[1] = "
                    + targetCell[1] + ", dropOverView = " + dropOverView);
        }        		
        if (dropOverView instanceof FolderIcon) {
            FolderIcon fi = (FolderIcon) dropOverView;
            if (fi.acceptDrop(dragInfo)) {
            	if (LauncherLog.DEBUG) {
            	    LauncherLog.d(TAG, "willAddToExistingUserFolder return true");
            	}
                return true;
            }
        }
        return false;
    }

    boolean createUserFolderIfNecessary(View newView, long container, CellLayout target,
            int[] targetCell, boolean external, DragView dragView, Runnable postAnimationRunnable) {
        View v = target.getChildAt(targetCell[0], targetCell[1]);
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "createUserFolderIfNecessary: newView = " + newView
                    + ", container = " + container + ", target = " + target + ", targetCell[0] = "
                    + targetCell[0] + ", targetCell[1] = " + targetCell[1] + ", external = "
                    + external + "dragView = " + dragView + ", v = " + v + ",mCreateUserFolderOnDrop = " +
                    mCreateUserFolderOnDrop);
        }
        boolean hasntMoved = false;
        if (mDragInfo != null) {
            CellLayout cellParent = getParentCellLayoutForView(mDragInfo.cell);
            hasntMoved = (mDragInfo.cellX == targetCell[0] &&
                    mDragInfo.cellY == targetCell[1]) && (cellParent == target);
        }

        if (v == null || hasntMoved || !mCreateUserFolderOnDrop) {
        	if (LauncherLog.DEBUG) {
        	    LauncherLog.d(TAG, "Do no create user folder: hasntMoved = " + hasntMoved 
        	            + ",mCreateUserFolderOnDrop = " + mCreateUserFolderOnDrop + ",v = " + v);
        	}
        	return false;
        }
        mCreateUserFolderOnDrop = false;
        final int screen = (targetCell == null) ? mDragInfo.screen : indexOfChild(target);

        boolean aboveShortcut = (v.getTag() instanceof ShortcutInfo);
        boolean willBecomeShortcut = (newView.getTag() instanceof ShortcutInfo);

        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "createUserFolderIfNecessary: aboveShortcut = "
                    + aboveShortcut + ", willBecomeShortcut = " + willBecomeShortcut);
        }

        if (aboveShortcut && willBecomeShortcut) {
            ShortcutInfo sourceInfo = (ShortcutInfo) newView.getTag();
            ShortcutInfo destInfo = (ShortcutInfo) v.getTag();
            // if the drag started here, we need to remove it from the workspace
            if (!external) {
                getParentCellLayoutForView(mDragInfo.cell).removeView(mDragInfo.cell);
            }

            Rect folderLocation = new Rect();
            float scale = mLauncher.getDragLayer().getDescendantRectRelativeToSelf(v, folderLocation);
            target.removeView(v);

            FolderIcon fi =
                mLauncher.addFolder(target, container, screen, targetCell[0], targetCell[1]);
            destInfo.cellX = -1;
            destInfo.cellY = -1;
            sourceInfo.cellX = -1;
            sourceInfo.cellY = -1;

            // If the dragView is null, we can't animate
            boolean animate = dragView != null;
            if (animate) {
                fi.performCreateAnimation(destInfo, v, sourceInfo, dragView, folderLocation, scale,
                        postAnimationRunnable);
            } else {
                fi.addItem(destInfo);
                fi.addItem(sourceInfo);
            }
            return true;
        }
        return false;
    }

    boolean addToExistingFolderIfNecessary(View newView, CellLayout target, int[] targetCell,
            DragObject d, boolean external) {
        if (mLauncher.isHotseatLayout(target)) {
            return false;
        }
        View dropOverView = target.getChildAt(targetCell[0], targetCell[1]);
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "createUserFolderIfNecessary: newView = " + newView
                    + ", target = " + target + ", targetCell[0] = " + targetCell[0]
                    + ", targetCell[1] = " + targetCell[1] + ", external = " + external + "d = "
                    + d + ", dropOverView = " + dropOverView);
        }
        if (dropOverView instanceof FolderIcon) {
            FolderIcon fi = (FolderIcon) dropOverView;
            if (fi.acceptDrop(d.dragInfo)) {
                fi.onDrop(d);

                // if the drag started here, we need to remove it from the workspace
                if (!external) {
                    getParentCellLayoutForView(mDragInfo.cell).removeView(mDragInfo.cell);
                }
                if (LauncherLog.DEBUG) {
                    LauncherLog.d(TAG, "addToExistingFolderIfNecessary: fi = " + fi
                            + ", d = " + d);
                }
                return true;
            }
        }
        return false;
    }

    public void onDrop(DragObject d) {
        mDragViewVisualCenter = getDragViewVisualCenter(d.x, d.y, d.xOffset, d.yOffset, d.dragView,
                mDragViewVisualCenter);

        // We want the point to be mapped to the dragTarget.
        if (mDragTargetLayout != null) {
            if (mLauncher.isHotseatLayout(mDragTargetLayout)) {
                mapPointFromSelfToSibling(mLauncher.getHotseat(), mDragViewVisualCenter);
            } else {
                mapPointFromSelfToChild(mDragTargetLayout, mDragViewVisualCenter, null);
            }
        }

        CellLayout dropTargetLayout = mDragTargetLayout;

        if (LauncherLog.DEBUG_DRAG) {
            LauncherLog.d(TAG, "onDrop 1: drag view = " + d.dragView + ",d = " + d + ",drag source  = "
                    + d.dragSource + ",dropTargetLayout = " + dropTargetLayout + ",mDragInfo = "
                    + mDragInfo + ",mInScrollArea = " + mInScrollArea + ",this = " + this);
        }
        
        int snapScreen = -1;
        if (d.dragSource != this) {
            final int[] touchXY = new int[] { (int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1] };
            onDropExternal(touchXY, d.dragInfo, dropTargetLayout, false, d);
        } else if (mDragInfo != null) {
            final View cell = mDragInfo.cell;
            if (mLastExchangeDragView != null) {
                mLauncher.getDragLayer().clearDragViews();
                mLastExchangeDragView = null;
            }

            if (dropTargetLayout != null) {
                // Move internally
                boolean hasMovedLayouts = (getParentCellLayoutForView(cell) != dropTargetLayout);
                boolean hasMovedIntoHotseat = mLauncher.isHotseatLayout(dropTargetLayout);
                long container = hasMovedIntoHotseat ?
                        LauncherSettings.Favorites.CONTAINER_HOTSEAT :
                        LauncherSettings.Favorites.CONTAINER_DESKTOP;
                int screen = (mTargetCell[0] < 0) ?
                        mDragInfo.screen : indexOfChild(dropTargetLayout);
                int spanX = mDragInfo != null ? mDragInfo.spanX : 1;
                int spanY = mDragInfo != null ? mDragInfo.spanY : 1;
                // First we find the cell nearest to point at which the item is
                // dropped, without any consideration to whether there is an item there.
                mTargetCell = findNearestArea((int) mDragViewVisualCenter[0], (int)
                        mDragViewVisualCenter[1], spanX, spanY, dropTargetLayout, mTargetCell);
                
                if (LauncherLog.DEBUG_DRAG) {
                    LauncherLog.d(TAG, "onDrop 2: cell = " + cell + ",screen = " + screen
                            + ",mInScrollArea = " + mInScrollArea + ",mTargetCell = " + mTargetCell
                            + ",this = " + this);
                }                
                // If the item being dropped is a shortcut and the nearest drop
                // cell also contains a shortcut, then create a folder with the two shortcuts.
                if (!mInScrollArea && createUserFolderIfNecessary(cell, container,
                        dropTargetLayout, mTargetCell, false, d.dragView, null)) {
                    return;
                }

                if (addToExistingFolderIfNecessary(cell, dropTargetLayout, mTargetCell, d, false)) {
                    return;
                }

                // Aside from the special case where we're dropping a shortcut onto a shortcut,
                // we need to find the nearest cell location that is vacant
                mTargetCell = findNearestVacantArea((int) mDragViewVisualCenter[0],
                        (int) mDragViewVisualCenter[1], mDragInfo.spanX, mDragInfo.spanY, cell,
                        dropTargetLayout, mTargetCell);
                
                int visualScreen = indexOfPage(dropTargetLayout);
                if (mCurrentPage != visualScreen && !hasMovedIntoHotseat) {
                    snapScreen = visualScreen;
                    snapToPage(visualScreen);
                }
                
                if (cell instanceof BubbleTextView) {
                	((BubbleTextView)cell).setCompoundDrawablePadding(hasMovedIntoHotseat ? 
                			0 : getResources().getDimensionPixelSize(R.dimen.app_icon_drawable_padding));
                }//added by leeyb
                if (mTargetCell[0] >= 0 && mTargetCell[1] >= 0) {
                    if (hasMovedLayouts) {
                        // Reparent the view
                        getParentCellLayoutForView(cell).removeView(cell);//<--key
                        addInScreen(cell, container, screen, mTargetCell[0], mTargetCell[1],
                                mDragInfo.spanX, mDragInfo.spanY);
                    }
                    if (mPendingDropOperation != null) {
                        mPendingDropOperation.run();
                    }
                    // update the item's position after drop
                    final ItemInfo info = (ItemInfo) cell.getTag();
                    CellLayout.LayoutParams lp = (CellLayout.LayoutParams) cell.getLayoutParams();
                    dropTargetLayout.onMove(cell, mTargetCell[0], mTargetCell[1]);
                    lp.cellX = mTargetCell[0];
                    lp.cellY = mTargetCell[1];
                    cell.setId(LauncherModel.getCellLayoutChildId(container, mDragInfo.screen,
                            mTargetCell[0], mTargetCell[1], mDragInfo.spanX, mDragInfo.spanY));

                    if (container != LauncherSettings.Favorites.CONTAINER_HOTSEAT &&
                            cell instanceof LauncherAppWidgetHostView) {
                        final CellLayout cellLayout = dropTargetLayout;
                        // We post this call so that the widget has a chance to be placed
                        // in its final location

                        final LauncherAppWidgetHostView hostView = (LauncherAppWidgetHostView) cell;
                        AppWidgetProviderInfo pinfo = hostView.getAppWidgetInfo();
                        if (pinfo != null && pinfo.resizeMode != AppWidgetProviderInfo.RESIZE_NONE) {
                            final Runnable resizeRunnable = new Runnable() {
                                public void run() {
                                    DragLayer dragLayer = mLauncher.getDragLayer();
                                    dragLayer.addResizeFrame(info, hostView, cellLayout);
                                }
                            };
                            post(new Runnable() {
                                public void run() {
                                    if (!isPageMoving()) {
                                        resizeRunnable.run();
                                    } else {
                                        mDelayedResizeRunnable = resizeRunnable;
                                    }
                                }
                            });
                        }
                    }

                    LauncherModel.moveItemInDatabase(mLauncher, info, container, screen, lp.cellX,
                            lp.cellY);
                }
            }

            final CellLayout parent = (CellLayout) cell.getParent().getParent();
            // Prepare it to be animated into its new position
            // This must be called after the view has been re-parented
            final Runnable disableHardwareLayersRunnable = new Runnable() {
                @Override
                public void run() {
                    mAnimatingViewIntoPlace = false;
                    updateChildrenLayersEnabled();
                }
            };
            mAnimatingViewIntoPlace = true;
            if (d.dragView.hasDrawn()) {
//                int duration = /*snapScreen < 0 ? -1 : */ADJACENT_SCREEN_DROP_DURATION;
                setFinalScrollForPageChange(snapScreen);
                mLauncher.getDragLayer().animateViewIntoPosition(d.dragView, cell, -1,
                        disableHardwareLayersRunnable);
                resetFinalScrollForPageChange(snapScreen);
            } else {
                cell.setVisibility(VISIBLE);
            }
            parent.onDropChild(cell);
        }
    }

    public void setFinalScrollForPageChange(int screen) {
        if (screen >= 0) {
            mSavedScrollX = getScrollX();
            CellLayout cl = (CellLayout) getPageAt(screen);
            mSavedTranslationX = cl.getTranslationX();
            mSavedRotationY = cl.getRotationY();
            final int newX = getChildOffset(screen) - getRelativeChildOffset(screen);
            setScrollX(newX);
            cl.setTranslationX(0f);
            cl.setRotationY(0f);
        }
    }

    public void resetFinalScrollForPageChange(int screen) {
        if (screen >= 0) {
            CellLayout cl = (CellLayout) getPageAt(screen);
            setScrollX(mSavedScrollX);
            cl.setTranslationX(mSavedTranslationX);
            cl.setRotationY(mSavedRotationY);
        }
    }

    public void getViewLocationRelativeToSelf(View v, int[] location) {
        getLocationInWindow(location);
        int x = location[0];
        int y = location[1];

        v.getLocationInWindow(location);
        int vX = location[0];
        int vY = location[1];

        location[0] = vX - x;
        location[1] = vY - y;
    }

    public void onDragEnter(DragObject d) {
        if (LauncherLog.DEBUG_DRAG) {
            LauncherLog.d(TAG, "onDragEnter: d = " + d + ",mDragTargetLayout = "
                    + mDragTargetLayout);
        }
        if (mDragTargetLayout != null) {
            mDragTargetLayout.setIsDragOverlapping(false);
            mDragTargetLayout.onDragExit();
        }
        mDragTargetLayout = getCurrentDropLayout();
        mDragTargetLayout.setIsDragOverlapping(true);
        mDragTargetLayout.onDragEnter();

        // Because we don't have space in the Phone UI (the CellLayouts run to the edge) we
        // don't need to show the outlines
        if (LauncherApplication.isScreenLarge()) {
            showOutlines();
        }
    }

    private void doDragExit(DragObject d) {
        // Clean up folders
        cleanupFolderCreation(d);
        // Reset the scroll area and previous drag target
        onResetScrollArea();
        if (LauncherLog.DEBUG_DRAG) {
            LauncherLog.d(TAG, "doDragExit: d = " + d + ",mDragTargetLayout = " + mDragTargetLayout
                    + ",mIsPageMoving = " + mIsPageMoving);
        }
        if (mDragTargetLayout != null) {
            mDragTargetLayout.setIsDragOverlapping(false);
            mDragTargetLayout.onDragExit();
        }
        mLastDragOverView = null;
        mSpringLoadedDragController.cancel();

        if (!mIsPageMoving) {
            hideOutlines();
        }
    }

    public void onDragExit(DragObject d) {
    	if (LauncherLog.DEBUG_DRAG) {
    	    LauncherLog.d(TAG, "onDragExit d = " + d);
    	}
        doDragExit(d);
    }

    public DropTarget getDropTargetDelegate(DragObject d) {
        return null;
    }

    /**
     * Tests to see if the drop will be accepted by Launcher, and if so, includes additional data
     * in the returned structure related to the widgets that match the drop (or a null list if it is
     * a shortcut drop).  If the drop is not accepted then a null structure is returned.
     */
    private Pair<Integer, List<WidgetMimeTypeHandlerData>> validateDrag(DragEvent event) {
        final LauncherModel model = mLauncher.getModel();
        final ClipDescription desc = event.getClipDescription();
        final int mimeTypeCount = desc.getMimeTypeCount();
        for (int i = 0; i < mimeTypeCount; ++i) {
            final String mimeType = desc.getMimeType(i);
            if (mimeType.equals(InstallShortcutReceiver.SHORTCUT_MIMETYPE)) {
                return new Pair<Integer, List<WidgetMimeTypeHandlerData>>(i, null);
            } else {
                final List<WidgetMimeTypeHandlerData> widgets =
                    model.resolveWidgetsForMimeType(mContext, mimeType);
                if (widgets.size() > 0) {
                    return new Pair<Integer, List<WidgetMimeTypeHandlerData>>(i, widgets);
                }
            }
        }
        return null;
    }

    /**
     * Global drag and drop handler
     */
    @Override
    public boolean onDragEvent(DragEvent event) {
        final ClipDescription desc = event.getClipDescription();
        final CellLayout layout = (CellLayout) getPageAt(mCurrentPage);
        final int[] pos = new int[2];
        layout.getLocationOnScreen(pos);
        // We need to offset the drag coordinates to layout coordinate space
        final int x = (int) event.getX() - pos[0];
        final int y = (int) event.getY() - pos[1];
        Thread.dumpStack();
        if (LauncherLog.DEBUG_DRAG) {
            LauncherLog.d(TAG, "onDragEvent: event = " + event + ",desc = " + desc
                    + ",layout = " + layout + ",x = " + x + ",y = " + y);
        }
        switch (event.getAction()) {
        case DragEvent.ACTION_DRAG_STARTED: {
            // Validate this drag
            Pair<Integer, List<WidgetMimeTypeHandlerData>> test = validateDrag(event);
            if (test != null) {
                boolean isShortcut = (test.second == null);
                if (isShortcut) {
                    // Check if we have enough space on this screen to add a new shortcut
                    if (!layout.findCellForSpan(pos, 1, 1)) {
                        mLauncher.showOutOfSpaceMessage();
                        return false;
                    }
                }
            } else {
                // Show error message if we couldn't accept any of the items
                Toast.makeText(mContext, mContext.getString(R.string.external_drop_widget_error),
                        Toast.LENGTH_SHORT).show();
                return false;
            }

            // Create the drag outline
            // We need to add extra padding to the bitmap to make room for the glow effect
            final Canvas canvas = new Canvas();
            final int bitmapPadding = HolographicOutlineHelper.MAX_OUTER_BLUR_RADIUS;
            mDragOutline = createExternalDragOutline(canvas, bitmapPadding);

            // Show the current page outlines to indicate that we can accept this drop
            showOutlines();
            layout.onDragEnter();
            layout.visualizeDropLocation(null, mDragOutline, x, y, 1, 1, null, null);

            return true;
        }
        case DragEvent.ACTION_DRAG_LOCATION:
            // Visualize the drop location
            layout.visualizeDropLocation(null, mDragOutline, x, y, 1, 1, null, null);
            return true;
        case DragEvent.ACTION_DROP: {
            // Try and add any shortcuts
            final LauncherModel model = mLauncher.getModel();
            final ClipData data = event.getClipData();

            // We assume that the mime types are ordered in descending importance of
            // representation. So we enumerate the list of mime types and alert the
            // user if any widgets can handle the drop.  Only the most preferred
            // representation will be handled.
            pos[0] = x;
            pos[1] = y;
            Pair<Integer, List<WidgetMimeTypeHandlerData>> test = validateDrag(event);
            if (test != null) {
                final int index = test.first;
                final List<WidgetMimeTypeHandlerData> widgets = test.second;
                final boolean isShortcut = (widgets == null);
                final String mimeType = desc.getMimeType(index);
                if (isShortcut) {
                    final Intent intent = data.getItemAt(index).getIntent();
                    Object info = model.infoFromShortcutIntent(mContext, intent, data.getIcon());
                    if (info != null) {
                        onDropExternal(new int[] { x, y }, info, layout, false);
                    }
                } else {
                    if (widgets.size() == 1) {
                        // If there is only one item, then go ahead and add and configure
                        // that widget
                        final AppWidgetProviderInfo widgetInfo = widgets.get(0).widgetInfo;
                        final PendingAddWidgetInfo createInfo =
                                new PendingAddWidgetInfo(widgetInfo, mimeType, data);
                        mLauncher.addAppWidgetFromDrop(createInfo,
                            LauncherSettings.Favorites.CONTAINER_DESKTOP, mCurrentPage, null, pos);
                    } else {
                        // Show the widget picker dialog if there is more than one widget
                        // that can handle this data type
                        final InstallWidgetReceiver.WidgetListAdapter adapter =
                            new InstallWidgetReceiver.WidgetListAdapter(mLauncher, mimeType,
                                    data, widgets, layout, mCurrentPage, pos);
                        final AlertDialog.Builder builder =
                            new AlertDialog.Builder(mContext);
                        builder.setAdapter(adapter, adapter);
                        builder.setCancelable(true);
                        builder.setTitle(mContext.getString(
                                R.string.external_drop_widget_pick_title));
                        builder.setIcon(R.drawable.ic_no_applications);
                        builder.show();
                    }
                }
            }
            return true;
        }
        case DragEvent.ACTION_DRAG_ENDED:
            // Hide the page outlines after the drop
            layout.onDragExit();
            hideOutlines();
            return true;
        }
        return super.onDragEvent(event);
    }

    /*
    *
    * Convert the 2D coordinate xy from the parent View's coordinate space to this CellLayout's
    * coordinate space. The argument xy is modified with the return result.
    *
    */
   void mapPointFromSelfToChild(View v, float[] xy) {
       mapPointFromSelfToChild(v, xy, null);
   }

   /*
    *
    * Convert the 2D coordinate xy from the parent View's coordinate space to this CellLayout's
    * coordinate space. The argument xy is modified with the return result.
    *
    * if cachedInverseMatrix is not null, this method will just use that matrix instead of
    * computing it itself; we use this to avoid redundant matrix inversions in
    * findMatchingPageForDragOver
    *
    */
   void mapPointFromSelfToChild(View v, float[] xy, Matrix cachedInverseMatrix) {
       if (cachedInverseMatrix == null) {
           v.getMatrix().invert(mTempInverseMatrix);
           cachedInverseMatrix = mTempInverseMatrix;
       }
       xy[0] = xy[0] + mScrollX - v.getLeft();
       xy[1] = xy[1] + mScrollY - v.getTop();
       cachedInverseMatrix.mapPoints(xy);
   }

   /*
    * Maps a point from the Workspace's coordinate system to another sibling view's. (Workspace
    * covers the full screen)
    */
   void mapPointFromSelfToSibling(View v, float[] xy) {
       xy[0] = xy[0] - v.getLeft();
       xy[1] = xy[1] - v.getTop();
       v.getMatrix().mapPoints(xy);
   }

   /*
    *
    * Convert the 2D coordinate xy from this CellLayout's coordinate space to
    * the parent View's coordinate space. The argument xy is modified with the return result.
    *
    */
   void mapPointFromChildToSelf(View v, float[] xy) {
       v.getMatrix().mapPoints(xy);
       xy[0] -= (mScrollX - v.getLeft());
       xy[1] -= (mScrollY - v.getTop());
   }

   static private float squaredDistance(float[] point1, float[] point2) {
        float distanceX = point1[0] - point2[0];
        float distanceY = point2[1] - point2[1];
        return distanceX * distanceX + distanceY * distanceY;
   }

    /*
     *
     * Returns true if the passed CellLayout cl overlaps with dragView
     *
     */
    boolean overlaps(CellLayout cl, DragView dragView,
            int dragViewX, int dragViewY, Matrix cachedInverseMatrix) {
        // Transform the coordinates of the item being dragged to the CellLayout's coordinates
        final float[] draggedItemTopLeft = mTempDragCoordinates;
        draggedItemTopLeft[0] = dragViewX;
        draggedItemTopLeft[1] = dragViewY;
        final float[] draggedItemBottomRight = mTempDragBottomRightCoordinates;
        draggedItemBottomRight[0] = draggedItemTopLeft[0] + dragView.getDragRegionWidth();
        draggedItemBottomRight[1] = draggedItemTopLeft[1] + dragView.getDragRegionHeight();

        // Transform the dragged item's top left coordinates
        // to the CellLayout's local coordinates
        mapPointFromSelfToChild(cl, draggedItemTopLeft, cachedInverseMatrix);
        float overlapRegionLeft = Math.max(0f, draggedItemTopLeft[0]);
        float overlapRegionTop = Math.max(0f, draggedItemTopLeft[1]);

        if (overlapRegionLeft <= cl.getWidth() && overlapRegionTop >= 0) {
            // Transform the dragged item's bottom right coordinates
            // to the CellLayout's local coordinates
            mapPointFromSelfToChild(cl, draggedItemBottomRight, cachedInverseMatrix);
            float overlapRegionRight = Math.min(cl.getWidth(), draggedItemBottomRight[0]);
            float overlapRegionBottom = Math.min(cl.getHeight(), draggedItemBottomRight[1]);

            if (overlapRegionRight >= 0 && overlapRegionBottom <= cl.getHeight()) {
                float overlap = (overlapRegionRight - overlapRegionLeft) *
                         (overlapRegionBottom - overlapRegionTop);
                if (overlap > 0) {
                    return true;
                }
             }
        }
        return false;
    }

    /*
     *
     * This method returns the CellLayout that is currently being dragged to. In order to drag
     * to a CellLayout, either the touch point must be directly over the CellLayout, or as a second
     * strategy, we see if the dragView is overlapping any CellLayout and choose the closest one
     *
     * Return null if no CellLayout is currently being dragged over
     *
     */
    private CellLayout findMatchingPageForDragOver(
            DragView dragView, float originX, float originY, boolean exact) {
        // We loop through all the screens (ie CellLayouts) and see which ones overlap
        // with the item being dragged and then choose the one that's closest to the touch point
        final int screenCount = getPageCount();
        CellLayout bestMatchingScreen = null;
        float smallestDistSoFar = Float.MAX_VALUE;

        for (int i = 0; i < screenCount; i++) {
            CellLayout cl = (CellLayout) getPageAt(i);

            final float[] touchXy = {originX, originY};
            // Transform the touch coordinates to the CellLayout's local coordinates
            // If the touch point is within the bounds of the cell layout, we can return immediately
            cl.getMatrix().invert(mTempInverseMatrix);
            mapPointFromSelfToChild(cl, touchXy, mTempInverseMatrix);

            if (touchXy[0] >= 0 && touchXy[0] <= cl.getWidth() &&
                    touchXy[1] >= 0 && touchXy[1] <= cl.getHeight()) {
                return cl;
            }

            if (!exact) {
                // Get the center of the cell layout in screen coordinates
                final float[] cellLayoutCenter = mTempCellLayoutCenterCoordinates;
                cellLayoutCenter[0] = cl.getWidth()/2;
                cellLayoutCenter[1] = cl.getHeight()/2;
                mapPointFromChildToSelf(cl, cellLayoutCenter);

                touchXy[0] = originX;
                touchXy[1] = originY;

                // Calculate the distance between the center of the CellLayout
                // and the touch point
                float dist = squaredDistance(touchXy, cellLayoutCenter);

                if (dist < smallestDistSoFar) {
                    smallestDistSoFar = dist;
                    bestMatchingScreen = cl;
                }
            }
        }
        return bestMatchingScreen;
    }

    // This is used to compute the visual center of the dragView. This point is then
    // used to visualize drop locations and determine where to drop an item. The idea is that
    // the visual center represents the user's interpretation of where the item is, and hence
    // is the appropriate point to use when determining drop location.
    private float[] getDragViewVisualCenter(int x, int y, int xOffset, int yOffset,
            DragView dragView, float[] recycle) {
        float res[];
        if (recycle == null) {
            res = new float[2];
        } else {
            res = recycle;
        }

        // First off, the drag view has been shifted in a way that is not represented in the
        // x and y values or the x/yOffsets. Here we account for that shift.
        x += getResources().getDimensionPixelSize(R.dimen.dragViewOffsetX);
        y += getResources().getDimensionPixelSize(R.dimen.dragViewOffsetY);

        // These represent the visual top and left of drag view if a dragRect was provided.
        // If a dragRect was not provided, then they correspond to the actual view left and
        // top, as the dragRect is in that case taken to be the entire dragView.
        // R.dimen.dragViewOffsetY.
        int left = x - xOffset;
        int top = y - yOffset;

        // In order to find the visual center, we shift by half the dragRect
        res[0] = left + dragView.getDragRegion().width() / 2;
        res[1] = top + dragView.getDragRegion().height() / 2;

        return res;
    }

    private boolean isDragWidget(DragObject d) {
        return (d.dragInfo instanceof LauncherAppWidgetInfo ||
                d.dragInfo instanceof PendingAddWidgetInfo);
    }
    private boolean isExternalDragWidget(DragObject d) {
        return d.dragSource != this && isDragWidget(d);
    }

    public void onDragOver(DragObject d) {
    	if (LauncherLog.DEBUG_DRAG) {
    	    LauncherLog.d(TAG, "onDragOver: d = " + d + ",mInScrollArea = "
    	            + mInScrollArea + ",mIsSwitchingState = " + mIsSwitchingState);
    	}
        // Skip drag over events while we are dragging over side pages
        if (mInScrollArea) return;
        if (mIsSwitchingState) return;

        Rect r = new Rect();
        CellLayout layout = null;
        ItemInfo item = (ItemInfo) d.dragInfo;

        // Ensure that we have proper spans for the item that we are dropping
        if (item.spanX < 0 || item.spanY < 0) throw new RuntimeException("Improper spans found");
        mDragViewVisualCenter = getDragViewVisualCenter(d.x, d.y, d.xOffset, d.yOffset,
            d.dragView, mDragViewVisualCenter);

        // Identify whether we have dragged over a side page
        if (isSmall()) {
            if (mLauncher.getHotseat() != null && !isExternalDragWidget(d)) {
                mLauncher.getHotseat().getHitRect(r);
                if (d.dragSource == this && r.contains(d.x, d.y)) {
                    layout = mLauncher.getHotseat().getLayout();
                }
            }
            if (layout == null) {
                layout = findMatchingPageForDragOver(d.dragView, d.x, d.y, false);
            }
            if (layout != mDragTargetLayout) {
                // Cancel all intermediate folder states
                cleanupFolderCreation(d);

                if (mDragTargetLayout != null) {
                    mDragTargetLayout.setIsDragOverlapping(false);
                    mDragTargetLayout.onDragExit();
                }
                mDragTargetLayout = layout;
                if (mDragTargetLayout != null) {
                    mDragTargetLayout.setIsDragOverlapping(true);
                    mDragTargetLayout.onDragEnter();
                } else {
                    mLastDragOverView = null;
                }

                boolean isInSpringLoadedMode = (mState == State.SPRING_LOADED);
                if (isInSpringLoadedMode) {
                    if (mLauncher.isHotseatLayout(layout)) {
                        mSpringLoadedDragController.cancel();
                    } else {
                        mSpringLoadedDragController.setAlarm(mDragTargetLayout);
                    }
                }
            }
        } else {
            // Test to see if we are over the hotseat otherwise just use the current page
            if (mLauncher.getHotseat() != null && !isDragWidget(d)) {
                mLauncher.getHotseat().getHitRect(r);
                if (r.contains(d.x, d.y)) {
                    layout = mLauncher.getHotseat().getLayout();
                }
            }
            if (layout == null) {
                layout = getCurrentDropLayout();
            }
            if (layout != mDragTargetLayout) {
                if (mDragTargetLayout != null) {
                    mDragTargetLayout.setIsDragOverlapping(false);
                    mDragTargetLayout.onDragExit();
                }
                mDragTargetLayout = layout;
                mDragTargetLayout.setIsDragOverlapping(true);
                mDragTargetLayout.onDragEnter();
            }
        }

        // Handle the drag over
        if (mDragTargetLayout != null) {
            final View child = (mDragInfo == null) ? null : mDragInfo.cell;
            
            /* this flag indicate where our finger located, true if exchange wanted*/
            boolean exchangeSwitch = false;
            // We want the point to be mapped to the dragTarget.
            if (mLauncher.isHotseatLayout(mDragTargetLayout)) {
                mapPointFromSelfToSibling(mLauncher.getHotseat(), mDragViewVisualCenter);
                exchangeSwitch = true;
            } else {
                mapPointFromSelfToChild(mDragTargetLayout, mDragViewVisualCenter, null);
            }
            ItemInfo info = (ItemInfo) d.dragInfo;

            mTargetCell = findNearestArea((int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1], 1, 1, mDragTargetLayout, mTargetCell);
            View dragOverView = mDragTargetLayout.getChildAt(mTargetCell[0], mTargetCell[1]);
            
            boolean userFolderPending = !exchangeSwitch && willCreateUserFolder(info, mDragTargetLayout,
                    mTargetCell, false);
            boolean isOverFolder = !exchangeSwitch && dragOverView instanceof FolderIcon;
            if (d.dragSource == this && mDragInfo != null && dragOverView != null) {
            	ItemInfo infoOver = (ItemInfo) dragOverView.getTag();
				if (/*isOverFolder || */infoOver != null && infoOver.container == info.container && 
						mDragInfo.cellX == mTargetCell[0] && mDragInfo.cellY == mTargetCell[1]) {
					dragOverView = null;
					isOverFolder = false;
				}
			}
            int[] judge = mLastExchangeDragView != null ? mCellPosition.get(mLastExchangeDragView) : null;
            if (dragOverView != mLastDragOverView && (d.dragSource != this || dragOverView != child)
            		&& !(dragOverView instanceof AllAppsEater)) {
                cancelFolderCreation();
                if (mLastDragOverView != null && mLastDragOverView instanceof FolderIcon) {
                    ((FolderIcon) mLastDragOverView).onDragExit(d.dragInfo);
                }
                if (exchangeSwitch && d.dragSource == this && dragOverView != null) {
                    /*
                     *  case 1 : our finger drag into hotseat <-- exchangeSwitch
                     *  && start drag from workspace <-- d.dragSource == this
                     *  && drag over a unnull item <-- dragOverView != null
                     *  --> move this item up from hotseat to workspace in this case
                     */
                	exchangePosition(dragOverView);
				} else if ((dragOverView == null || dragOverView instanceof AllAppsEater)
						&& judge != null && (judge[0] != mTargetCell[0] || !exchangeSwitch)) {
					/*
					 *  case 2 : our finger located in a unoccupied cell <-- dragOverView == null
                	 *  || we drag over garbage icon (the garbage must be visible now) <-- dragOverView instanceof AllAppsEater
                	 *   && mLastExchangeDragView is not null <-- judge != null
                	 *   && the unoccupied cell our finger located can not be the original cell the last item start at<-- judge[0] != mTargetCell[0]
                	 *   || our finger into workspace <-- !exchangeSwitch
                	 *   --> move last dragView back from workspace to hotseat in this case
                	 */
					exchangePosition(null);
				}
            }

            if (userFolderPending && dragOverView != mLastDragOverView) {
                mFolderCreationAlarm.setOnAlarmListener(new
                        FolderCreationAlarmListener(mDragTargetLayout, mTargetCell[0], mTargetCell[1]));
                mFolderCreationAlarm.setAlarm(FOLDER_CREATION_TIMEOUT);
            }

            if (dragOverView != mLastDragOverView && isOverFolder) {
                ((FolderIcon) dragOverView).onDragEnter(d.dragInfo);
                if (mDragTargetLayout != null) {
                    mDragTargetLayout.clearDragOutlines();
                }
            }
            mLastDragOverView = dragOverView;

            if (!mCreateUserFolderOnDrop && !isOverFolder) {
                mDragTargetLayout.visualizeDropLocation(child, mDragOutline,
                        (int) mDragViewVisualCenter[0], (int) mDragViewVisualCenter[1],
                        item.spanX, item.spanY, d.dragView.getDragVisualizeOffset(),
                        d.dragView.getDragRegion());
            }
        }
    }
    
    /**
     * Drag an icon from Workspace to Hotseat lead this method to be invoked, that is,
     * exchange position with each other instead of generate a new folder. 
     * added by leeyb
     * @param dragOverView the exchange target we should deal with
     */
    private void exchangePosition(final View dragOverView) {
    	if (mDragInfo == null || mDragTargetLayout == null || mDragInfo.cell instanceof LauncherAppWidgetHostView) {
			return;
		}
    	final DragView lastChangedView = mLastExchangeDragView;
    	final DragView activeView;
    	final boolean backMode = dragOverView == null && lastChangedView != null;
    	final CellLayout parent = getParentCellLayoutForView(mDragInfo.cell);
    	final CellLayout dropTargetLayout = mDragTargetLayout;
    	final float scale = parent.getScaleX();
    	final boolean moveLayout = parent != dropTargetLayout;
    	final RectF temp = new RectF();
    	final boolean inHotseat = mLauncher.isHotseatLayout(parent);
    	//init a view used for display in moving animation by what we want
    	if (backMode) {
    		activeView = lastChangedView;
		} else {
			Bitmap b = null;
	    	ItemInfo info = (ItemInfo) dragOverView.getTag();
	        if (info.dragImage != null) {
				b = info.dragImage;
			} else {
				int bitmapPadding = HolographicOutlineHelper.MAX_OUTER_BLUR_RADIUS;
				b = createDragBitmap(dragOverView, new Canvas(), bitmapPadding, false);
				info.dragImage = b;
			}
	        activeView = new DragView(mLauncher, b, 0,
	        		0, 0, 0, b.getWidth(), b.getHeight(), dragOverView);
	        if (!inHotseat) {
	        	mLauncher.getDragLayer().getLocationInDragLayer(dragOverView, mTempXY);
			    mTempXY[1] += dragOverView.getPaddingTop();
			    mTempXY[0] += dragOverView.getPaddingLeft();
			}
		}
    	final int[] local = mCellPosition.get(lastChangedView);
		if (!backMode) {
			final long container = mDragInfo.container;
	        final int updateCellX = mDragInfo.cellX;
	        final int updateCellY = mDragInfo.cellY;
	        final int spanCellX = mDragInfo.spanX;
	        final int spanCellY = mDragInfo.spanY;
	        final int screenCell = mDragInfo.screen;
			final CellLayout.LayoutParams lp =(CellLayout.LayoutParams) dragOverView.getLayoutParams();
			final int screen = indexOfChild(parent);
			dropTargetLayout.markCellsAsUnoccupiedForView(dragOverView);
			//move the dragover view into drag start position by refresh this runnable
			mPendingDropOperation = new Runnable() {
				@Override
				public void run() {
					if (moveLayout) {
						dropTargetLayout.removeView(dragOverView);
						addInScreen(dragOverView, container, screen, updateCellX, updateCellY,
								spanCellX, spanCellY);
					} else {
				        lp.cellX = updateCellX;
				        lp.cellY = updateCellY;
				        dropTargetLayout.markCellsAsOccupiedForView(dragOverView);
					}
					dragOverView.setId(LauncherModel.getCellLayoutChildId(container, screenCell,
			        		updateCellX, updateCellY, spanCellX, spanCellY));
					LauncherModel.moveItemInDatabase(mLauncher, (ItemInfo) dragOverView.getTag(), moveLayout ? 
							LauncherSettings.Favorites.CONTAINER_DESKTOP : LauncherSettings.Favorites.CONTAINER_HOTSEAT,
							screenCell, updateCellX, updateCellY);
					if (mGhost != null) {
                        mGhost.cancel();
                    }
					dragOverView.setAlpha(1.0f);
				}
			};
			//calculate the destination and its offset
	        final int[] destination = new int[2];
	        mDragInfo.cell.getHitRect(mTempRect);
	        mLauncher.getDragLayer().offsetDescendantRectToMyCoords2(mDragInfo.cell, mTempRect, true);
	        destination[0] = mTempRect.left;
	        destination[1] = mTempRect.top;
	        if (inHotseat) {
	        	mTempXY[1] = destination[1];
		        mTempXY[0] = destination[0];
			} else {
		        if (dragOverView instanceof TextView) {
					TextView tv = (TextView) dragOverView;
					Drawable top = tv.getCompoundDrawables()[1];
			        destination[1] += ((1 - scale) * top.getIntrinsicHeight()) / 2;
				}
				destination[0] += dragOverView.getPaddingLeft();
			}
	        //remember the original position every time per view
			mCellPosition.put(activeView, new int[]{lp.cellX, lp.cellY, destination[0], destination[1], mTempXY[0], mTempXY[1]});
			mGhost = dragOverView.animate().alpha(0f).setDuration(350).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    dragOverView.setAlpha(1.0f);
                }
            });
			mAnimatingViewIntoPlace = true;
			//play a short animation that shows the process of moving
			mLauncher.getDragLayer().animateDragViewIntoPosition(activeView, mTempXY, destination, scale, new Runnable() {
				@Override
				public void run() {
					mAnimatingViewIntoPlace = false;
					updateChildrenLayersEnabled();
				}
			}, false);
			mLastExchangeDragView = activeView;
		}
		if (lastChangedView != null && local != null) {
        	//recheck and move previous view located at drag start position back to its original position visually
        	CellLayout target = mLauncher.getHotseat().getLayout();
        	target.markCellsForView(local[0], local[1], 1, 1, true);
        	Runnable out = new Runnable() {
				@Override
				public void run() {
					mAnimatingViewIntoPlace = false;
					updateChildrenLayersEnabled();
				}
			};
        	if (backMode) {
        	    //the drop runnable initialized in last step become unuseful now, cancel it
        		mPendingDropOperation = null;
				mLastExchangeDragView = null;
			}
        	mAnimatingViewIntoPlace = true;
        	View holder = lastChangedView.mHolder;
            if (holder != null) {
                holder.animate().alpha(1f).setDuration(350);
            }
			mLauncher.getDragLayer().animateDragViewIntoPosition(lastChangedView, new int[]{local[2], local[3]},
					new int[]{local[4], local[5]}, 1f, out, true);
		}
	}

    private void cleanupFolderCreation(DragObject d) {
        if (mDragFolderRingAnimator != null && mCreateUserFolderOnDrop) {
            mDragFolderRingAnimator.animateToNaturalState();
        }
        if (mLastDragOverView != null && mLastDragOverView instanceof FolderIcon) {
            if (d != null) {
                ((FolderIcon) mLastDragOverView).onDragExit(d.dragInfo);
            }
        }
        mFolderCreationAlarm.cancelAlarm();
    }

    private void cancelFolderCreation() {
        if (mDragFolderRingAnimator != null && mCreateUserFolderOnDrop) {
            mDragFolderRingAnimator.mIsCreate = true;
            mDragFolderRingAnimator.animateToNaturalState();
        }
        mCreateUserFolderOnDrop = false;
        mFolderCreationAlarm.cancelAlarm();
    }

    class FolderCreationAlarmListener implements OnAlarmListener {
        CellLayout layout;
        int cellX;
        int cellY;

        public FolderCreationAlarmListener(CellLayout layout, int cellX, int cellY) {
            this.layout = layout;
            this.cellX = cellX;
            this.cellY = cellY;
        }

        public void onAlarm(Alarm alarm) {
            if (mDragFolderRingAnimator == null) {
                mDragFolderRingAnimator = new FolderRingAnimator(mLauncher, null);
            }
            mDragFolderRingAnimator.setCell(cellX, cellY);
            mDragFolderRingAnimator.setCellLayout(layout);
            mDragFolderRingAnimator.mIsCreate = true;
            mDragFolderRingAnimator.animateToAcceptState();
            layout.showFolderAccept(mDragFolderRingAnimator);
            layout.clearDragOutlines();
            mCreateUserFolderOnDrop = true;
        }
    }

    @Override
    public void getHitRect(Rect outRect) {
        // We want the workspace to have the whole area of the display (it will find the correct
        // cell layout to drop to in the existing drag/drop logic.
        outRect.set(0, 0, mDisplayWidth, mDisplayHeight);
    }

    /**
     * Add the item specified by dragInfo to the given layout.
     * @return true if successful
     */
    public boolean addExternalItemToScreen(ItemInfo dragInfo, CellLayout layout) {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "addExternalItemToScreen: dragInfo = " + dragInfo
                    + ", layout = " + layout);
        }
        if (layout.findCellForSpan(mTempEstimate, dragInfo.spanX, dragInfo.spanY)) {
            onDropExternal(dragInfo.dropPos, (ItemInfo) dragInfo, (CellLayout) layout, false);
            return true;
        }
        mLauncher.showOutOfSpaceMessage();
        return false;
    }

    private void onDropExternal(int[] touchXY, Object dragInfo, CellLayout cellLayout,
            boolean insertAtFirst) {
        onDropExternal(touchXY, dragInfo, cellLayout, insertAtFirst, null);
    }

    /**
     * Drop an item that didn't originate on one of the workspace screens.
     * It may have come from Launcher (e.g. from all apps or customize), or it may have
     * come from another app altogether.
     *
     * NOTE: This can also be called when we are outside of a drag event, when we want
     * to add an item to one of the workspace screens.
     */
    private void onDropExternal(final int[] touchXY, final Object dragInfo,
            final CellLayout cellLayout, boolean insertAtFirst, DragObject d) {
        mLauncher.exitSpringLoadedDragModeDelayed(true, false);
        
        ItemInfo info = (ItemInfo) dragInfo;
        int spanX = info.spanX;
        int spanY = info.spanY;
        if (mDragInfo != null) {
            spanX = mDragInfo.spanX;
            spanY = mDragInfo.spanY;
        }

        final long container = mLauncher.isHotseatLayout(cellLayout) ?
                LauncherSettings.Favorites.CONTAINER_HOTSEAT :
                    LauncherSettings.Favorites.CONTAINER_DESKTOP;
        final int screen = indexOfChild(cellLayout);
//        final int visualScreen = indexOfPage(cellLayout);
//        if (!mLauncher.isHotseatLayout(cellLayout) && visualScreen != mCurrentPage
//                && mState != State.SPRING_LOADED) {
//            snapToPage(visualScreen);
//        }

        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "onDropExternal: touchXY[0] = " + touchXY[0]
                    + ", touchXY[1] = " + touchXY[1] + ", dragInfo = " + dragInfo
                    + ", cellLayout = " + cellLayout + ", insertAtFirst = " + insertAtFirst
                    + ",d = " + d + ",screen = " + screen + ",container = " + container);
        } 
        if (info instanceof PendingAddItemInfo) {
            final PendingAddItemInfo pendingInfo = (PendingAddItemInfo) dragInfo;

            boolean findNearestVacantCell = true;
            if (pendingInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT) {
                mTargetCell = findNearestArea((int) touchXY[0], (int) touchXY[1], spanX, spanY,
                        cellLayout, mTargetCell);
                if (willCreateUserFolder((ItemInfo) d.dragInfo, mDragTargetLayout, mTargetCell,
                        true) || willAddToExistingUserFolder((ItemInfo) d.dragInfo,
                                mDragTargetLayout, mTargetCell)) {
                    findNearestVacantCell = false;
                }
            }
            if (findNearestVacantCell) {
                    mTargetCell = findNearestVacantArea(touchXY[0], touchXY[1], spanX, spanY, null,
                        cellLayout, mTargetCell);
            }

            Runnable onAnimationCompleteRunnable = new Runnable() {
                @Override
                public void run() {
                    // When dragging and dropping from customization tray, we deal with creating
                    // widgets/shortcuts/folders in a slightly different way
                    switch (pendingInfo.itemType) {
                    case LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET:
                        mLauncher.addAppWidgetFromDrop((PendingAddWidgetInfo) pendingInfo,
                                container, screen, mTargetCell, null);
                        break;
                    case LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT:
                        mLauncher.processShortcutFromDrop(pendingInfo.componentName,
                                container, screen, mTargetCell, null);
                        break;
                    default:
                        throw new IllegalStateException("Unknown item type: " +
                                pendingInfo.itemType);
                    }
                    cellLayout.onDragExit();
                }
            };

            // Now we animate the dragView, (ie. the widget or shortcut preview) into its final
            // location and size on the home screen.
            RectF r = estimateItemPosition(cellLayout, pendingInfo,
                    mTargetCell[0], mTargetCell[1], spanX, spanY);
            int loc[] = new int[2];
            loc[0] = (int) r.left;
            loc[1] = (int) r.top;
            setFinalTransitionTransform(cellLayout);
            mLauncher.getDragLayer().getDescendantCoordRelativeToSelf(cellLayout, loc, false);
            resetTransitionTransform(cellLayout);
            float dragViewScale =  Math.min(r.width() / d.dragView.getMeasuredWidth(),
                    r.height() / d.dragView.getMeasuredHeight());
            // The animation will scale the dragView about its center, so we need to center about
            // the final location.
            loc[0] -= (d.dragView.getMeasuredWidth() - /*cellLayoutScale * */r.width()) / 2;
            loc[1] -= (d.dragView.getMeasuredHeight() - /*cellLayoutScale * */r.height()) / 2;

            mLauncher.getDragLayer().animateViewIntoPosition(d.dragView, loc,
                    dragViewScale/* * cellLayoutScale*/, onAnimationCompleteRunnable);
        } else {
            // This is for other drag/drop cases, like dragging from All Apps
            View view = null;

            switch (info.itemType) {
            case LauncherSettings.Favorites.ITEM_TYPE_APPLICATION:
            case LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT:
                if (info.container == NO_ID && info instanceof ApplicationInfo) {
                    // Came from all apps -- make a copy
                    info = new ShortcutInfo((ApplicationInfo) info);
                }
                view = mLauncher.createShortcut(R.layout.application, cellLayout,
                        (ShortcutInfo) info);
                break;
            case LauncherSettings.Favorites.ITEM_TYPE_FOLDER:
                view = FolderIcon.fromXml(R.layout.folder_icon, mLauncher, cellLayout,
                        (FolderInfo) info, mIconCache);
                break;
            default:
                throw new IllegalStateException("Unknown item type: " + info.itemType);
            }

            // First we find the cell nearest to point at which the item is
            // dropped, without any consideration to whether there is an item there.
            if (touchXY != null) {
                mTargetCell = findNearestArea((int) touchXY[0], (int) touchXY[1], spanX, spanY,
                        cellLayout, mTargetCell);
                d.postAnimationRunnable = null/*exitSpringLoadedRunnable*/;
                if (createUserFolderIfNecessary(view, container, cellLayout, mTargetCell, true,
                        d.dragView, d.postAnimationRunnable)) {
                    return;
                }
                if (addToExistingFolderIfNecessary(view, cellLayout, mTargetCell, d, true)) {
                    return;
                }
            }

            if (touchXY != null) {
                // when dragging and dropping, just find the closest free spot
                mTargetCell = findNearestVacantArea(touchXY[0], touchXY[1], 1, 1, null,
                        cellLayout, mTargetCell);
            } else {
                cellLayout.findCellForSpan(mTargetCell, 1, 1);
            }
            addInScreen(view, container, screen, mTargetCell[0], mTargetCell[1], info.spanX,
                    info.spanY, insertAtFirst);
            cellLayout.onDropChild(view);
            CellLayout.LayoutParams lp = (CellLayout.LayoutParams) view.getLayoutParams();
            cellLayout.getChildrenLayout().measureChild(view);

            LauncherModel.addOrMoveItemInDatabase(mLauncher, info, container, screen,
                    lp.cellX, lp.cellY);

            if (d.dragView != null) {
                // We wrap the animation call in the temporary set and reset of the current
                // cellLayout to its final transform -- this means we animate the drag view to
                // the correct final location.
                setFinalTransitionTransform(cellLayout);
                mLauncher.getDragLayer().animateViewIntoPosition(d.dragView, view, -1,
                        /*exitSpringLoadedRunnable*/null);
                resetTransitionTransform(cellLayout);
            }
        }
    }

    public void setFinalTransitionTransform(CellLayout layout) {
        if (isSwitchingState()) {
            int index = indexOfPage(layout);
            mCurrentScaleX = layout.getScaleX();
            mCurrentScaleY = layout.getScaleY();
            mCurrentTranslationX = layout.getTranslationX();
            mCurrentTranslationY = layout.getTranslationY();
            mCurrentRotationY = layout.getRotationY();
            layout.setScaleX(mNewScaleXs[index]);
            layout.setScaleY(mNewScaleYs[index]);
            layout.setTranslationX(mNewTranslationXs[index]);
            layout.setTranslationY(mNewTranslationYs[index]);
            layout.setRotationY(mNewRotationYs[index]);
        }
    }
    public void resetTransitionTransform(CellLayout layout) {
        if (isSwitchingState()) {
            /*mCurrentScaleX = layout.getScaleX();
            mCurrentScaleY = layout.getScaleY();
            mCurrentTranslationX = layout.getTranslationX();
            mCurrentTranslationY = layout.getTranslationY();
            mCurrentRotationY = layout.getRotationY();  delete by leeyb*/
            layout.setScaleX(mCurrentScaleX);
            layout.setScaleY(mCurrentScaleY);
            layout.setTranslationX(mCurrentTranslationX);
            layout.setTranslationY(mCurrentTranslationY);
            layout.setRotationY(mCurrentRotationY);
        }
    }

    /**
     * Return the current {@link CellLayout}, correctly picking the destination
     * screen while a scroll is in progress.
     */
    public CellLayout getCurrentDropLayout() {
        return (CellLayout) getPageAt(mNextPage == INVALID_PAGE ? mCurrentPage : mNextPage);
    }

    /**
     * Return the current CellInfo describing our current drag; this method exists
     * so that Launcher can sync this object with the correct info when the activity is created/
     * destroyed
     *
     */
    public CellLayout.CellInfo getDragInfo() {
        return mDragInfo;
    }

    /**
     * Calculate the nearest cell where the given object would be dropped.
     *
     * pixelX and pixelY should be in the coordinate system of layout
     */
    private int[] findNearestVacantArea(int pixelX, int pixelY,
            int spanX, int spanY, View ignoreView, CellLayout layout, int[] recycle) {
        return layout.findNearestVacantArea(
                pixelX, pixelY, spanX, spanY, ignoreView, recycle);
    }

    /**
     * Calculate the nearest cell where the given object would be dropped.
     *
     * pixelX and pixelY should be in the coordinate system of layout
     */
    private int[] findNearestArea(int pixelX, int pixelY,
            int spanX, int spanY, CellLayout layout, int[] recycle) {
        return layout.findNearestArea(
                pixelX, pixelY, spanX, spanY, recycle);
    }

    void setup(DragController dragController, SlideDivider mDockDivider, Hotseat hotseat) {
        mSpringLoadedDragController = new SpringLoadedDragController(mLauncher);
        mDragController = dragController;
        mHotseat = hotseat;
        // hardware layers on children are enabled on startup, but should be disabled until
        // needed
        updateChildrenLayersEnabled();
        setWallpaperDimension();
        registeSlideDividerforMyself(mDockDivider);
        updateDivider(mCurrentPage);
    }

    @Override
	protected void updateDivider(int which) {
        super.updateDivider(which);
    	if (mScrollIndicator != null) {
    		mScrollIndicator.setTotalPages(getChildCount());
			mScrollIndicator.setCurrentDivider(which);
		}
	}

	/**
     * Called at the end of a drag which originated on the workspace.
     */
    public void onDropCompleted(View target, DragObject d, boolean success) {
        if (!(d.dragSource instanceof AppsCustomizePagedView)) {
            changeState(State.NORMAL, true);
        }
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "onDropCompleted: target = " + target + ", d = " + d
                    + ", mDragInfo = " + mDragInfo + ", success = " + success);
        }
    			
        if (success) {
            if (target != this) {
                if (mDragInfo != null) {
                    getParentCellLayoutForView(mDragInfo.cell).removeView(mDragInfo.cell);
                    if (mDragInfo.cell instanceof DropTarget) {
                        mDragController.removeDropTarget((DropTarget) mDragInfo.cell);
                    }
                }
            }
        } else if (mDragInfo != null) {
            // NOTE: When 'success' is true, onDragExit is called by the DragController before
            // calling onDropCompleted(). We call it ourselves here, but maybe this should be
            // moved into DragController.cancelDrag().
            //doDragExit(null);
            CellLayout cellLayout;
            if (mLauncher.isHotseatLayout(target)) {
                cellLayout = mLauncher.getHotseat().getLayout();
            } else {
                cellLayout = (CellLayout) getChildAt(mDragInfo.screen);
            }
            cellLayout.onDropChild(mDragInfo.cell);
        }
        doDragExit(null);
        if (d.cancelled &&  mDragInfo.cell != null) {
                mDragInfo.cell.setVisibility(VISIBLE);
        }
        View mtkWidgetView = searchIMTKWidget(getCurrentDropLayout());
        if (mtkWidgetView != null) {
        	((IMTKWidget)mtkWidgetView).setScreen(mCurrentPage);
        	((IMTKWidget)mtkWidgetView).stopDrag();
            if (LauncherLog.DEBUG_SURFACEWIDGET) {
                LauncherLog.d(Launcher.TAG_SURFACEWIDGET, "stopDrag: mtkWidgetView = " + mtkWidgetView);
            }
        }
        mDragOutline = null;
        mDragInfo = null;
    }

    public boolean isDropEnabled() {
        return true;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        super.onRestoreInstanceState(state);
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "onRestoreInstanceState: state = " + state
                    + ", mCurrentPage = " + mCurrentPage);
        }
        Launcher.setScreen(mCurrentPage);
    }

    @Override
    public void scrollLeft() {
        if (/*!isSmall() &&*/ !mIsSwitchingState) {
            super.scrollLeft();
        }
        Folder openFolder = getOpenFolder();
        if (openFolder != null) {
            openFolder.completeDragExit();
        }
    }

    @Override
    public void scrollRight() {
        if (/*!isSmall() &&*/ !mIsSwitchingState) {
            super.scrollRight();
        }
        Folder openFolder = getOpenFolder();
        if (openFolder != null) {
            openFolder.completeDragExit();
        }
    }

    @Override
    public boolean onEnterScrollArea(int x, int y, int direction) {
        // Ignore the scroll area if we are dragging over the hot seat
        if (mLauncher.getHotseat() != null) {
            Rect r = new Rect();
            mLauncher.getHotseat().getHitRect(r);
            if (r.contains(x, y)) {
                return false;
            }
        }

        boolean result = false;
        if (/*!isSmall() &&*/ !mIsSwitchingState) {
            mInScrollArea = true;

            final int center = mScrollX + getMeasuredWidth() / 2 + getMeasuredWidth() * (direction == DragController.SCROLL_LEFT ? -1 : 1);
            final CellLayout layout = (CellLayout) getPageAt(center / getMeasuredWidth()/*locateOfCenter(center)*/);
            cancelFolderCreation();

            if (layout != null) {
                // Exit the current layout and mark the overlapping layout
                if (mDragTargetLayout != null) {
                    mDragTargetLayout.setIsDragOverlapping(false);
                    mDragTargetLayout.onDragExit();
                }
                mDragTargetLayout = layout;
                mDragTargetLayout.setIsDragOverlapping(true);

                // Workspace is responsible for drawing the edge glow on adjacent pages,
                // so we need to redraw the workspace when this may have changed.
                invalidate();
                result = true;
            }
        }
        return result;
    }

    @Override
    public boolean onExitScrollArea() {
        boolean result = false;
        if (mInScrollArea) {
            if (mDragTargetLayout != null) {
                // Unmark the overlapping layout and re-enter the current layout
                mDragTargetLayout.setIsDragOverlapping(false);
                mDragTargetLayout = getCurrentDropLayout();
                mDragTargetLayout.onDragEnter();

                // Workspace is responsible for drawing the edge glow on adjacent pages,
                // so we need to redraw the workspace when this may have changed.
                invalidate();
                result = true;
            }
            mInScrollArea = false;
        }
        return result;
    }

    private void onResetScrollArea() {
        if (mDragTargetLayout != null) {
            // Unmark the overlapping layout
            mDragTargetLayout.setIsDragOverlapping(false);

            // Workspace is responsible for drawing the edge glow on adjacent pages,
            // so we need to redraw the workspace when this may have changed.
            invalidate();
        }
        mInScrollArea = false;
    }

    /**
     * Returns a specific CellLayout
     */
    CellLayout getParentCellLayoutForView(View v) {
        ArrayList<CellLayout> layouts = getWorkspaceAndHotseatCellLayouts();
        for (CellLayout layout : layouts) {
            if (layout.getChildrenLayout().indexOfChild(v) > -1) {
                return layout;
            }
        }
        return null;
    }

    /**
     * Returns a list of all the CellLayouts in the workspace.
     */
    ArrayList<CellLayout> getWorkspaceAndHotseatCellLayouts() {
        ArrayList<CellLayout> layouts = new ArrayList<CellLayout>();
        int screenCount = getChildCount();
        for (int screen = 0; screen < screenCount; screen++) {
            layouts.add(((CellLayout) getChildAt(screen)));
        }
        if (mLauncher.getHotseat() != null) {
            layouts.add(mLauncher.getHotseat().getLayout());
        }
        return layouts;
    }

    /**
     * We should only use this to search for specific children.  Do not use this method to modify
     * CellLayoutChildren directly.
     */
    ArrayList<CellLayoutChildren> getWorkspaceAndHotseatCellLayoutChildren() {
        ArrayList<CellLayoutChildren> childrenLayouts = new ArrayList<CellLayoutChildren>();
        int screenCount = getChildCount();
        for (int screen = 0; screen < screenCount; screen++) {
            childrenLayouts.add(((CellLayout) getChildAt(screen)).getChildrenLayout());
        }
        if (mLauncher.getHotseat() != null) {
            childrenLayouts.add(mLauncher.getHotseat().getLayout().getChildrenLayout());
        }
        return childrenLayouts;
    }

    public Folder getFolderForTag(Object tag) {
        ArrayList<CellLayoutChildren> childrenLayouts = getWorkspaceAndHotseatCellLayoutChildren();
        for (CellLayoutChildren layout: childrenLayouts) {
            int count = layout.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = layout.getChildAt(i);
                if (child instanceof Folder) {
                    Folder f = (Folder) child;
                    if (f.getInfo() == tag && f.getInfo().opened) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    public View getViewForTag(Object tag) {
        ArrayList<CellLayoutChildren> childrenLayouts = getWorkspaceAndHotseatCellLayoutChildren();
        for (CellLayoutChildren layout: childrenLayouts) {
            int count = layout.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = layout.getChildAt(i);
                if (child.getTag() == tag) {
                    return child;
                }
            }
        }
        return null;
    }

    void clearDropTargets() {
        ArrayList<CellLayoutChildren> childrenLayouts = getWorkspaceAndHotseatCellLayoutChildren();
        for (CellLayoutChildren layout: childrenLayouts) {
            int childCount = layout.getChildCount();
            for (int j = 0; j < childCount; j++) {
                View v = layout.getChildAt(j);
                if (v instanceof DropTarget && !(v instanceof AllAppsEater)) {
                    mDragController.removeDropTarget((DropTarget) v);
                }
            }
        }
    }

    void removeItems(final ArrayList<ApplicationInfo> apps) {
        final AppWidgetManager widgets = AppWidgetManager.getInstance(getContext());

        final HashSet<String> packageNames = new HashSet<String>();
        final int appCount = apps.size();
        for (int i = 0; i < appCount; i++) {
            packageNames.add(apps.get(i).componentName.getPackageName());
        }
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "removeItems: apps = " + apps + ",appCount = " + appCount);
        }
        ArrayList<CellLayout> cellLayouts = getWorkspaceAndHotseatCellLayouts();
        for (final CellLayout layoutParent: cellLayouts) {
            final ViewGroup layout = layoutParent.getChildrenLayout();

            // Avoid ANRs by treating each screen separately
            post(new Runnable() {
                public void run() {
                    final ArrayList<View> childrenToRemove = new ArrayList<View>();
                    childrenToRemove.clear();

                    int childCount = layout.getChildCount();
                    for (int j = 0; j < childCount; j++) {
                        final View view = layout.getChildAt(j);
                        Object tag = view.getTag();

                        if (tag instanceof ShortcutInfo) {
                            final ShortcutInfo info = (ShortcutInfo) tag;
                            final Intent intent = info.intent;
                            final ComponentName name = intent.getComponent();

                            if (Intent.ACTION_MAIN.equals(intent.getAction()) && name != null) {
                                for (String packageName: packageNames) {
                                    if (packageName.equals(name.getPackageName())) {
                                        LauncherModel.deleteItemFromDatabase(mLauncher, info);
                                        childrenToRemove.add(view);
                                    }
                                }
                            }
                        } else if (tag instanceof FolderInfo) {
                            final FolderInfo info = (FolderInfo) tag;
                            final ArrayList<ShortcutInfo> contents = info.contents;
                            final int contentsCount = contents.size();
                            final ArrayList<ShortcutInfo> appsToRemoveFromFolder =
                                    new ArrayList<ShortcutInfo>();

                            for (int k = 0; k < contentsCount; k++) {
                                final ShortcutInfo appInfo = contents.get(k);
                                final Intent intent = appInfo.intent;
                                final ComponentName name = intent.getComponent();

                                if (Intent.ACTION_MAIN.equals(intent.getAction()) && name != null) {
                                    for (String packageName: packageNames) {
                                        if (packageName.equals(name.getPackageName())) {
                                            appsToRemoveFromFolder.add(appInfo);
                                        }
                                    }
                                }
                            }
                            for (ShortcutInfo item: appsToRemoveFromFolder) {
                                info.remove(item);
                                LauncherModel.deleteItemFromDatabase(mLauncher, item);
                            }
                        } else if (tag instanceof LauncherAppWidgetInfo) {
                            final LauncherAppWidgetInfo info = (LauncherAppWidgetInfo) tag;
                            final AppWidgetProviderInfo provider =
                                    widgets.getAppWidgetInfo(info.appWidgetId);
                            if (provider != null) {
                                for (String packageName: packageNames) {
                                    if (packageName.equals(provider.provider.getPackageName())) {
                                        LauncherModel.deleteItemFromDatabase(mLauncher, info);
                                        childrenToRemove.add(view);
                                    }
                                }
                            }
                        }
                    }

                    childCount = childrenToRemove.size();
                    for (int j = 0; j < childCount; j++) {
                        View child = childrenToRemove.get(j);
                        // Note: We can not remove the view directly from CellLayoutChildren as this
                        // does not re-mark the spaces as unoccupied.
                        layoutParent.removeViewInLayout(child);
                        if (child instanceof DropTarget) {
                            mDragController.removeDropTarget((DropTarget)child);
                        }
                    }

                    if (childCount > 0) {
                        layout.requestLayout();
                        layout.invalidate();
                    }
                }
            });
        }
    }

    void updateShortcuts(ArrayList<ApplicationInfo> apps) {
    	if (LauncherLog.DEBUG) {
    	    LauncherLog.d(TAG, "updateShortcuts: apps = " + apps);
    	}
        ArrayList<CellLayoutChildren> childrenLayouts = getWorkspaceAndHotseatCellLayoutChildren();
        for (CellLayoutChildren layout: childrenLayouts) {
            int childCount = layout.getChildCount();
            for (int j = 0; j < childCount; j++) {
                final View view = layout.getChildAt(j);
                Object tag = view.getTag();
                if (tag instanceof ShortcutInfo) {
                    ShortcutInfo info = (ShortcutInfo)tag;
                    // We need to check for ACTION_MAIN otherwise getComponent() might
                    // return null for some shortcuts (for instance, for shortcuts to
                    // web pages.)
                    final Intent intent = info.intent;
                    final ComponentName name = intent.getComponent();
                    if (info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION &&
                            Intent.ACTION_MAIN.equals(intent.getAction()) && name != null) {
                        final int appCount = apps.size();
                        for (int k = 0; k < appCount; k++) {
                            ApplicationInfo app = apps.get(k);
                            if (app.componentName.equals(name)) {
                                info.setIcon(mIconCache.getIcon(info.intent));
                                ((TextView)view).setCompoundDrawablesWithIntrinsicBounds(null,
                                        new FastBitmapDrawable(info.getIcon(mIconCache)),
                                        null, null);
                                }
                        }
                    }
                } else if (tag instanceof FolderInfo) {
                    final FolderInfo info = (FolderInfo) tag;
                    final ArrayList<ShortcutInfo> contents = info.contents;
                    final int contentsCount = contents.size();

                    for (int k = 0; k < contentsCount; k++) {
                        final ShortcutInfo appInfo = contents.get(k);
                        final Intent intent = appInfo.intent;
                        final ComponentName name = intent.getComponent();

                        if (appInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION &&
                                Intent.ACTION_MAIN.equals(intent.getAction()) && name != null) {
                            final int appCount = apps.size();
                            for (int m=0; m<appCount; m++) {
								ApplicationInfo app = apps.get(m);
								if (app.componentName.equals(name)) {
									appInfo.setIcon(mIconCache.getIcon(appInfo.intent));
								}
                            }
                        }
                    }
                    final Folder folder = getOpenFolder();
                    if (folder != null) folder.notifyDataSetChanged();
                }
            }
        }
    }

    void moveToDefaultScreen(boolean animate) {
        if (!isSmall()) {
            if (animate) {
                snapToPage(mDefaultPage);
            } else {
                setCurrentPage(mDefaultPage);
            }
        }
        getPageAt(mDefaultPage).requestFocus();
    }

    @Override
    public void syncPages() {
    }

    @Override
    public void syncPageItems(int page, boolean immediate) {
    }

    @Override
    protected String getCurrentPageDescription() {
        int page = (mNextPage != INVALID_PAGE) ? mNextPage : mCurrentPage;
        return String.format(mContext.getString(R.string.workspace_scroll_format),
                page + 1, getChildCount());
    }

    public void getLocationInDragLayer(int[] loc) {
        mLauncher.getDragLayer().getLocationInDragLayer(this, loc);
    }

    void setFadeForOverScroll(float fade) {
        if (!isScrollingIndicatorEnabled()) return;

        mOverscrollFade = fade;
        float reducedFade = 0.5f + 0.5f * (1 - fade);
        final ViewGroup parent = (ViewGroup) getParent();
//        final ImageView qsbDivider = (ImageView) (parent.findViewById(R.id.qsb_divider));
//        final View dockDivider = parent.findViewById(R.id.dock_divider);
//        final ImageView scrollIndicator = getScrollingIndicator();

        cancelScrollingIndicatorAnimations();
//        if (qsbDivider != null) qsbDivider.setAlpha(reducedFade);
//        if (dockDivider != null) dockDivider.setAlpha(reducedFade);
//        scrollIndicator.setAlpha(1 - fade);
    }
    
    @Override
    protected void layoutChildren() {
        if (!mCountWatcher.isAnimateRest() || mState == State.PREVIEWS) {
            return;
        }
//    	final int verticalPadding = mPaddingTop + mPaddingBottom;
    	int childLeft = getRelativeChildOffset(0);
    	for (int i = 0; i < getPageCount(); i++) {
            final View child = getPageAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                final int childWidth = getScaledMeasuredWidth(child);
                final int childHeight = child.getMeasuredHeight();
                int childTop = child.getPaddingTop();
//                if (mCenterPagesVertically) {
//                    childTop += ((getMeasuredHeight() - verticalPadding) - childHeight) / 2;
//                }
                child.layout(childLeft, childTop,
                        childLeft + child.getMeasuredWidth(), childTop + childHeight);
                childLeft += childWidth + mPageSpacing * 2;
                if (mPreviewsState && mMatrix != null) {
                    child.setX(mMatrix[i][0]);
                    child.setY(mMatrix[i][1]);//reset every view's x and y coords to compensate offset caused by layout change
                }
            }
        }
	}
    
    private int locateOfCenter(int center) {
        int out = INVALID_PAGE;
        if (center < 0) {
            if (center >= -getMeasuredWidth()) {
                out = getPageCount() - 1;
            }
        } else if (center >= mMaxScrollX + getMeasuredWidth()) {
            if (center < mMaxScrollX + 2 * getMeasuredWidth()) {
                out = 0;
            }
        } else {
            out = center / getMeasuredWidth();
        }
        return out;
    }
    
    @Override
    protected void getVisiblePages(int[] range, int center) {
        range[0] = range[1] = range[2] = INVALID_PAGE;
        final int width = getMeasuredWidth();
        if (center - width > 0) {
            range[0] = (center - width) / width; 
        }
        if (center >= 0 && center <= mMaxScrollX + width) {
            range[1] = center / width;
        }
        if (center + width < mMaxScrollX + width) {
            range[2] = (center + width) / width; 
        }
    }
    
    /**
     * CellLayout 3D transformation in Desktop implement here
     * it will only work in NORMAL state, there is a contradiction between SPRINGLOADED state 
     * and NORMAL state that the PivotX or Y can only be one figure during drawing process
     * added by leeyb
     */
	@Override
	protected void screenScrolled(int center) {
		if (mState == State.NORMAL) {
		    final int visualIndex = (center + getMeasuredWidth()) / getMeasuredWidth() - 1;//make ensure this figure can be nagetive
            int middle = locateOfCenter(center);
            int leftOne = locateOfCenter(center - getMeasuredWidth());
            int rightOne = locateOfCenter(center + getMeasuredWidth());
		    CellLayout page = (CellLayout) getPageAt(middle);
		    CellLayout prevPage = (CellLayout) getPageAt(leftOne);
		    CellLayout nextPage = (CellLayout) getPageAt(rightOne);
		    float scrollProgress = getScrollProgress(center, page, visualIndex);
            boolean positive = scrollProgress >= 0;
            float degree = scrollProgress * (mDotDragging ? MAX_DESKTOP_ROTATE_DOTDRAG : MAX_DESKTOP_ROTATE);
            float translateMax = mDotDragging ? MAX_DESKTOP_TRANSLATE_DOTDRAG : MAX_DESKTOP_TRANSLATE;
            float avaliableAlpha = Math.abs(scrollProgress);
            float alphaPageBg = mDotDragging ? getLessAccelerateAlpha(1 - avaliableAlpha) : getAccelerateAlpha(avaliableAlpha);
            float alphaNeighbourBg = mDotDragging ? getLessAccelerateAlpha(avaliableAlpha) : getAccelerateAlpha(1f - avaliableAlpha);
		    if (page != null) {
		        if (mDotDragging) {
	                if (page.getPivotX() != page.getMeasuredWidth() * 0.5f) {
	                    page.setPivotX(page.getMeasuredWidth() * 0.5f); 
	                }
	                page.setFastScaleX(getLessDecelarateScale(scrollProgress));
	                page.setFastScaleY(getLessDecelarateScale(scrollProgress));
	            } else if (positive && page.getPivotX() != 0f) {//optimization  " != 0f" before pivot changes
	                page.setPivotX(0f);
	            } else if (!positive && page.getPivotX() != page.getMeasuredWidth()) {
	                page.setPivotX(page.getMeasuredWidth());
	            }
	            if (page.getPivotY() != page.getMeasuredHeight() * 0.5f) {
	                page.setPivotY(page.getMeasuredHeight() * 0.5f);
	            }
	            page.setFastRotationY(degree);
	            page.setFastTranslationX(scrollProgress * page.getMeasuredWidth() * translateMax);
	            page.setFastAlpha(getLessAccelerateAlpha(1 - avaliableAlpha));
	            page.setFastBackgroundAlpha(alphaPageBg);
	            page.invalidate();
            }
            if (prevPage != null && (!positive || leftOne != rightOne)) {
                if (mDotDragging) {
                    if (prevPage.getPivotX() != prevPage.getMeasuredWidth() * 0.5f) {
                        prevPage.setPivotX(prevPage.getMeasuredWidth() * 0.5f);
                    }
                } else if (prevPage.getPivotX() != 0f) {
                    prevPage.setPivotX(0f);
                }
                if (prevPage.getPivotY() != prevPage.getMeasuredHeight() * 0.5f) {
                    prevPage.setPivotY(prevPage.getMeasuredHeight() * 0.5f);
                }
                prevPage.setFastBackgroundAlpha(alphaNeighbourBg);
                prevPage.setFastAlpha(getLessAccelerateAlpha(avaliableAlpha));
                prevPage.setFastRotationY(degree + (mDotDragging ? MAX_DESKTOP_ROTATE_DOTDRAG : MAX_DESKTOP_ROTATE));
                float scrollProgressFade = getScrollProgress(center, prevPage, visualIndex - 1);
                float translationX = scrollProgressFade * prevPage.getMeasuredWidth() * translateMax;
                prevPage.setFastTranslationX(translationX);
                if (mDotDragging) {
                    prevPage.setFastScaleX(getLessDecelarateScale(scrollProgressFade));
                    prevPage.setFastScaleY(getLessDecelarateScale(scrollProgressFade));
                }
                prevPage.invalidate();
            }
            if (nextPage != null && (positive || leftOne != rightOne)) {
                if (mDotDragging) {
                    if (nextPage.getPivotX() != nextPage.getMeasuredWidth() * 0.5f) {
                        nextPage.setPivotX(nextPage.getMeasuredWidth() * 0.5f);
                    }
                } else if (nextPage.getPivotX() != nextPage.getMeasuredWidth()) {
                    nextPage.setPivotX(nextPage.getMeasuredWidth());
                }
                if (nextPage.getPivotY() != nextPage.getMeasuredHeight() * 0.5f) {
                    nextPage.setPivotY(nextPage.getMeasuredHeight() * 0.5f);
                }
                nextPage.setFastBackgroundAlpha(alphaNeighbourBg);
                nextPage.setFastAlpha(getLessAccelerateAlpha(avaliableAlpha));
                nextPage.setFastRotationY(degree - (mDotDragging ? MAX_DESKTOP_ROTATE_DOTDRAG : MAX_DESKTOP_ROTATE));
                float scrollProgressFade = getScrollProgress(center, nextPage, visualIndex + 1);
                float translationX = scrollProgressFade * nextPage.getMeasuredWidth() * translateMax;
                nextPage.setFastTranslationX(translationX);
                if (mDotDragging) {
                    nextPage.setFastScaleX(getLessDecelarateScale(scrollProgressFade));
                    nextPage.setFastScaleY(getLessDecelarateScale(scrollProgressFade));
                }
                nextPage.invalidate();
            }
            /*for (int i = 0; i < getChildCount(); i++) {
                CellLayout child = (CellLayout) getChildAt(i);
                if (child != null && child != page && child != prevPage && child != nextPage) {
                    child.setFastRotationY(0.0f);
                    child.setFastTranslationX(0.0f);
                    child.setFastBackgroundAlpha(0.0f);
                    child.setFastAlpha(1.0f);
                }
            }*/
		} /*else {
			for (int i = 0; i < getChildCount(); i++) {
				CellLayout child = (CellLayout) getChildAt(i);
				child.setFastRotationY(0.0f);
				child.setFastTranslationX(0.0f);
			}
		}*/
	}
	/**
	 * Calculate specified alpha with extremely high speed
	 * @param out
	 * @return
	 */
	private float getAccelerateAlpha(float out) {
	    out = mScrollInterpolator.getInterpolation(out);
        return mDotDragging ? Math.max(0.35f, out) : out;
	}
	private float getLessAccelerateAlpha(float out) {
	    out = mDecelerateInterpolator.getInterpolation(out);
		return mDotDragging ? Math.max(0.35f, out) : out;
	}
	private float getLessDecelarateScale(float out) {
	    float base = getResources().getInteger(R.integer.config_workspaceSpringLoadShrinkPercentage) / 100f;
        return Math.max(0.6f, base * (1 - Math.abs(mScaleInterpolator.getInterpolation(out))));
	}

	@Override
	protected void showPreviews(boolean start) {
//		super.showPreviews(start);
	    if (mPreviewsState && start) {
            return;
        }
		State state = start ? State.PREVIEWS : State.NORMAL;
		changeState(state, true);
	}

	@Override
	public boolean isPreviewsState() {
		return super.isPreviewsState() || mState == State.PREVIEWS;
	}

    @Override
    public void onDotClick(int page) {
        page = Math.max(0, Math.min(page, getPageCount()));
        if (page != mCurrentPage) {
            snapToPage(page, Math.abs(page - mCurrentPage) * PAGE_SNAP_ANIMATION_DURATION);
        }
    }

    @Override
    protected boolean onDotDragStart(final boolean enter) {
        if (enter) {
            return super.onDotDragStart(enter);
        } else {
            if (mPreviewsSwitching || getPageCount() <= 0 || mIsSwitchingState) {
                return false;
            }
            ValueAnimator engine = ValueAnimator.ofFloat(0f, 1f);
            Resources res = getResources();
            engine.setDuration(res.getInteger(R.integer.config_workspacePreviewsTime));
            initAnimationArrays();
            for (int i = 0; i < getPageCount(); i++) {
                CellLayout child = (CellLayout) getPageAt(i);
                mOldTranslationXs[i] = child.getTranslationY();
                mOldRotationYs[i] = child.getRotationY();
                mNewRotationYs[i] = 0.0f;
                mNewTranslationXs[i] = 0.0f;
                mOldScaleXs[i] = child.getScaleX();
                mOldScaleYs[i] = child.getScaleY();
                mNewScaleXs[i] = 1.0f;
                mNewScaleYs[i] = mNewScaleXs[i];
            }
            engine.addUpdateListener(new LauncherAnimatorUpdateListener() {
                @Override
                void onAnimationUpdate(float a, float b) {
                    invalidate();
                    for (int i = 0; i < getPageCount(); i++) {
                        CellLayout cl = (CellLayout) getPageAt(i);
                        cl.invalidate();
                        cl.setFastScaleX(a * mOldScaleXs[i] + b * mNewScaleXs[i]);
                        cl.setFastScaleY(a * mOldScaleYs[i] + b * mNewScaleYs[i]);
                        cl.setFastRotationY(a * mOldRotationYs[i] + b * mNewRotationYs[i]);
                        cl.setFastTranslationX(a * mOldTranslationXs[i] + b * mNewTranslationXs[i]);   
                        cl.invalidate();
                    }
                }
            });
            mDotDragging = false;
            post(new Runnable() {
                @Override
                public void run() {
                    snapToDestination();
                    screenScrolled(mScrollX + getMeasuredWidth() / 2);//force screen to scroll once
                }
            });
            engine.start();
            return true;
        }
    }
    
    @Override
    protected LayoutParams generateLayoutParams(LayoutParams p) {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    private Runnable mCheckForLongPressOfPreview;
    @Override
    protected boolean onPreviewTouchEvent(MotionEvent ev) {
        final int active = ev.findPointerIndex(mActivePointerId);
        final int x = (int) ev.getX(active) + mScrollX;
        final int y = (int) ev.getY(active) + mScrollY;
        switch (ev.getAction() & MotionEvent.ACTION_MASK) {
        case MotionEvent.ACTION_DOWN:
            if (!mCountWatcher.isAnimateRest()) {
                return false;
            }//reject all touch event when we are animating busy
            mLastMotionX = ev.getX();
            mLastMotionY = ev.getY();
            mActivePointerId = ev.getPointerId(0);
            mDragTargetLayout = null;
            mPreviewClickTargetPage = indexOfTarget(x, y);
            if (mPreviewClickTargetPage >= 0) {
                CellLayout estimate = (CellLayout) getPageAt(mPreviewClickTargetPage);
                if (estimate.checkHomeHintClicked(x, y)) {
                    performCellLayoutHintClick(estimate, true);
                    mPreviewClickTargetPage = INVALID_PAGE;
                } else if (getPageCount() > MIN_WORKSPACE_CELLLAYOUT_NUMBER) {
                    postLongPressAction();
                }
            }
            break;
        case MotionEvent.ACTION_CANCEL:
        case MotionEvent.ACTION_UP:
            if (!mPreviewsLongPressPerformed) {
                cancelLongPress();
            } else if (mDragTargetLayout != null) {
                //deal with the case we are dragging a celllayout
                if (mLastPassingByPage == LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
                    mHotseat.getCellLayoutEater().onDragExit();
                    mHotseat.setBackgroundResource(R.drawable.bottom_bar);
                    checkAndRemoveCellLayout(mDragTargetLayout, true);
                    mLastPassingByPage = INVALID_PAGE;
                } else {
                    moveCellLayoutBack();
                    mDragTargetLayout = null;
                }
                return true;
            }
            if (mPreviewClickTargetPage >= 0 && mPreviewClickTargetPage == indexOfTarget(x, y)) {
                if (mPreviewClickTargetPage != mCurrentPage) {
                    mRelayoutNeeded = true;
                }
                mNextPage = mPreviewClickTargetPage;
                showPreviews(false);
            } else if (mPreviewClickTargetPage == INDEX_ADD &&
                    INDEX_ADD == indexOfTarget(x, y)) {
                CellLayout added = null;
                if (mDeletedLayouts.size() > 0) {
                    CellLayout dirty = mDeletedLayouts.get(0);
                    clearCellLayout(dirty);
                    mDeletedLayouts.remove(dirty);
                    mCellLayoutPosition.add(dirty);
                    dirty.getChildrenLayout().invalidate();
                    added = dirty;
                } else {
                    CellLayout raw = mLauncher.generateCellLayout(false);
                    addViewInLayout(raw, getChildCount(), null, true);
                    initNewCellLayout(raw);
                    added = raw;
                }
                mRelayoutNeeded = true;
                mPersonator.applyNewCellLayout(added);
                possession();
            }
            break;
        case MotionEvent.ACTION_MOVE:
            if (mPreviewsLongPressPerformed && mDragTargetLayout != null) {
                CellLayout target = mDragTargetLayout;
                float oldX = target.getX();
                float oldY = target.getY();
                float deltaX = x - mLastMotionX - mScrollX;
                float deltaY = y - mLastMotionY - mScrollY;
                invalidate();
                final float newX = oldX + deltaX;
                final float newY = oldY + deltaY;
                target.setX(newX);//make the dragging celllayout stick to our finger
                target.setY(newY);
                mLastMotionX = x - mScrollX;
                mLastMotionY = y - mScrollY;
                int index = indexOfTarget(x, y);
                if (index != mLastPassingByPage && mCountWatcher.isAnimateRest() && index >= 0) {
                    previewsPageExchange(index);
                    mLastPassingByPage = index;
                } else {
                    mTempXY[0] = x - mScrollX;
                    mTempXY[1] = y - mScrollY;
                    mLauncher.getDragLayer().getDescendantCoordRelativeToSelf(this, mTempXY, true);
                    mHotseat.getHitRect(mTempRect);
                    if (mTempRect.contains(mTempXY[0], mTempXY[1])) {
                        if (mLastPassingByPage != LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
                            mHotseat.getCellLayoutEater().onDragEnter();
                            target.setBackgroundFocused(true);
                            mHotseat.setBackgroundResource(R.drawable.bottom_bar_warn_center);
                        }
                        mLastPassingByPage = LauncherSettings.Favorites.CONTAINER_HOTSEAT;
                    } else if (mLastPassingByPage == LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
                        mHotseat.getCellLayoutEater().onDragExit();
                        target.setBackgroundFocused(false);
                        mLastPassingByPage = INVALID_PAGE;
                        mHotseat.setBackgroundResource(R.drawable.bottom_bar);
                    }
                }
            }
            break;
        case MotionEvent.ACTION_POINTER_UP:
            final int pointerIndex = (ev.getAction() & MotionEvent.ACTION_POINTER_INDEX_MASK) >>
            MotionEvent.ACTION_POINTER_INDEX_SHIFT;
            final int pointerId = ev.getPointerId(pointerIndex);
            if (pointerId == mActivePointerId) {
                final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
                //do not update mLastMotionX and mLastMotionY but mActivePointerId only,
                //so we can remember out last position used in MOVE action
                mActivePointerId = ev.getPointerId(newPointerIndex);
            }
            break;
        }
        return true;
    }
    
    /**
     * Default home hint of CellLayout icon click implementation
     * @param estimate which celllayout the home hint belong to
     * @param fromUser show toast or not
     */
    private void performCellLayoutHintClick(CellLayout estimate, boolean fromUser) {
        for (int i = 0, j = 0; i < getPageCount(); i++) {
            CellLayout child = (CellLayout) getPageAt(i);
            if (child == estimate) {
                if (!child.isHome()) {
                    child.setHome(true);
                    mLauncher.rememberDefaultHomePage(j);
                    mDefaultPage = j;
                    if (fromUser) {
                        Toast.makeText(mLauncher, R.string.workspace_default_page_changed, Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                child.setHome(false);
                if (!mDeletedLayouts.contains(child)) {
                    j++;
                }
            }
        }
        invalidate();
    }

    /**
     * see getPageAt()
    private void bringChildToFrontVisually(View child) {
        if (child.getParent() == this) {
            final SparseArray<View> local = mCellLayoutPosition;
            int index = local.indexOfValue(child);
            int key = local.keyAt(index);
            int size = local.size();
            if (index == size - 1 && index == key) {
                return;//negative, the target child has been in front of others
            } else {
                for (int i = index; i < size; i++) {
                    View token = local.get(i + 1);
                    if (token != null) {
                        local.put(i, token);
                    }
                }
                local.put(size - 1, child);
            }
        } else {
            throw new IllegalStateException("the args child must has been a child of workspace");
        }
    }*/

    private void initNewCellLayout(CellLayout added) {
        final LayoutParams lp = (LayoutParams) added.getLayoutParams();
        final int childWidthMeasureSpec =
            MeasureSpec.makeMeasureSpec(mRawWidth - mPaddingLeft - mPaddingRight, MeasureSpec.AT_MOST);
        final int childHeightMeasureSpec =
            MeasureSpec.makeMeasureSpec(mRawHeight - mPaddingTop - mPaddingBottom, MeasureSpec.AT_MOST);
        added.measure(childWidthMeasureSpec, childHeightMeasureSpec);
        int childLeft = getRelativeChildOffset(0);
        int childTop = mPaddingTop;
        for (int i = 0; i < getPageCount(); i++) {
            final View child = getPageAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                childLeft += child.getMeasuredWidth() + mPageSpacing * 2;
            }
        }
        added.layout(childLeft, childTop, childLeft + added.getMeasuredWidth(), childTop + added.getMeasuredHeight());
        invalidateCachedOffsets();
    }

    private void moveCellLayoutBack() {
        final CellLayout target = mDragTargetLayout;
        target.setBackgroundFocused(false);
//        if (mSavedChildIndex != indexOfChild(target)) {
//            detachViewFromParent(target);
//            attachViewToParent(target, mSavedChildIndex, target.getLayoutParams());
//        }
        final float oldScaleX = target.getScaleX();
        final float oldScaleY = target.getScaleY();
        final float oldX = target.getX();
        final float oldY = target.getY();
        final float newScaleX = mFinalScaleFactor;
        final float newScaleY = mFinalScaleFactor;
        final float newX = mMatrix[indexOfPage(target)][0];
        final float newY = mMatrix[indexOfPage(target)][1];
        ValueAnimator end = ValueAnimator.ofFloat(0.0f, 1.0f).setDuration(100);
        end.addUpdateListener(new LauncherAnimatorUpdateListener() {
            @Override
            void onAnimationUpdate(float a, float b) {
                invalidate();
                target.setScaleX(a * oldScaleX + b * newScaleX);
                target.setScaleY(a * oldScaleY + b * newScaleY);
                target.setX(a * oldX + b * newX);
                target.setY(a * oldY + b * newY);
            }
        });
        end.addListener(mCountWatcher);
        end.start();
    }

    /**
     * Check whether the specified CellLayout has unremoved children view and remove it
     * @param target the celllayout to be removed
     * @param animated play a scale animation shows delete process
     */
    private void checkAndRemoveCellLayout(final CellLayout target, boolean animated) {
        if (target.getChildrenLayout().getChildCount() == 0) {
//            Log.v("leeyb", "before remove");
//            debugArray();
            if (animated) {
                mTempXY[0] = (int) (mHotseat.getCellLayoutEater().getLeft() + mHotseat.getCellLayoutEater().getWidth() / 4);
                mTempXY[1] = (int) (mHotseat.getBackgroundTranslationY());
                mLauncher.getDragLayer().getDescendantCoordRelativeToSelf(mHotseat, mTempXY, true);
                mTempXY[0] += mScrollX;
                mTempXY[1] += mScrollY;
                ValueAnimator deleteEngine = ValueAnimator.ofFloat(0f, 1f).setDuration(250);
                final float oldScale = target.getScaleX();
                final float newScale = 0.1f;
                final float oldAlpha = target.getAlpha();
                final float newAlpha = 0.0f;
                final float oldX = target.getX();
                final float oldY = target.getY();
                deleteEngine.addUpdateListener(new LauncherAnimatorUpdateListener() {
                    @Override
                    void onAnimationUpdate(float a, float b) {
                        invalidate();
                        target.setX(a * oldX + b * mTempXY[0]);
                        target.setFastBackgroundAlpha(a * oldAlpha + b * newAlpha);
                        target.setFastScaleX(a * oldScale + b * newScale);
                        target.setFastScaleY(a * oldScale + b * newScale);
                        target.setY(a * oldY + b * mTempXY[1]);
                    }
                });
                deleteEngine.setInterpolator(new DecelerateInterpolator());
                deleteEngine.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        //View.GONE will make requestLayout() be invoked, so use INVISIBLE instead
                        target.setVisibility(View.INVISIBLE);
                    }
                    @Override
                    public void onAnimationStart(Animator animation) {
                        mHotseat.getCellLayoutEater().onCellLayoutEat();
                        possession();
                    }
                });
                deleteEngine.addListener(mCountWatcher);
                deleteEngine.start();
            } else {
                possession();
                target.setVisibility(View.INVISIBLE);
            }
            int index = mCellLayoutPosition.indexOf(target);
            if (target.isHome()) {
                CellLayout estimate = (CellLayout) getPageAt(index - 1);
                if (estimate == null || mDeletedLayouts.contains(estimate)) {
                    estimate = (CellLayout) getPageAt(index + 1);
                }
                if (estimate == null) {
                    return;
                }
                performCellLayoutHintClick(estimate, false);
            } else if (index < mDefaultPage && index >= 0) {
                mDefaultPage--;
                mLauncher.rememberDefaultHomePage(mDefaultPage);
            }
            if (!mCellLayoutPosition.remove(target)) {
                throw new IllegalStateException("trying to delete a cellLayout which is not existed");
            }
            mRelayoutNeeded = mDeletedLayouts.add(target);
//            debugArray();
        } else {
            //popup a dialog to notify user that the CellLayout to be deleted is not empty, and ask for confirmation
            moveCellLayoutBack();
            new AlertDialog.Builder(mLauncher).setMessage(R.string.workspace_previews_delete_message)
            .setTitle(R.string.workspace_previews_delete_title)
            .setPositiveButton(R.string.rename_action, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    clearCellLayout(target);
                    checkAndRemoveCellLayout(target, false);
                }
            }).setNegativeButton(R.string.cancel_action, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            }).show();
        }
    }
    
    /**
     * make every CellLayout positioned correctly, including the personator
     */
    private void possession() {
        final CellLayout target = mDragTargetLayout;
        final ArrayList<View> standBy = new ArrayList<View>();
        for (int i = 0; i < getPageCount(); i++) {
            View v = getPageAt(i);
            if (v != target && !mDeletedLayouts.contains(v)) {
                standBy.add(v);
            }
        }
        if (mPersonator.visible || target != null) {
            standBy.add(mPersonator);
        }
        final int count = standBy.size();
        final float[] oldXs = new float[count];
        final float[] oldYs = new float[count];
        final float[] oldScale = new float[count];
        for (int i = 0; i < count; i++) {
            View v = standBy.get(i);
            oldXs[i] = v.getX();
            oldYs[i] = v.getY();
            oldScale[i] = Math.min(v.getScaleX(), v.getScaleY());
        }
        mMatrix = new int[count][2];
        mFinalScaleFactor = previewMatrix(this, Style.ADW, mMatrix);
        ValueAnimator possess = ValueAnimator.ofFloat(0f, 1f);
        possess.addUpdateListener(new LauncherAnimatorUpdateListener() {
            @Override
            void onAnimationUpdate(float a, float b) {
                invalidate();
                for (int i = 0; i < count; i++) {
                    View v = standBy.get(i);
                    v.setX(a * oldXs[i] + b * mMatrix[i][0]);
                    v.setY(a * oldYs[i] + b * mMatrix[i][1]);
                    v.setScaleX(a * oldScale[i] + b * mFinalScaleFactor);
                    v.setScaleY(a * oldScale[i] + b * mFinalScaleFactor);
                }
            }
        });
        possess.addListener(mCountWatcher);
        possess.start();
    }

    private void previewsPageExchange(int destination) {
        int home = mCellLayoutPosition.indexOf(mDragTargetLayout);
        if (destination == home) {
            return;
        }
        int delay = 0;
        if (destination > home) {
            for (int i = home + 1; i <= destination; i++) {
                final View target = mCellLayoutPosition.get(i);
                ValueAnimator engine = ValueAnimator.ofFloat(0f, 1f).setDuration(150);
                engine.setStartDelay(delay);
                final int previous = i - 1;
                final float oldX = target.getX();
                final float oldY = target.getY();
                final float newX = mMatrix[previous][0];
                final float newY = mMatrix[previous][1];
                engine.addUpdateListener(new LauncherAnimatorUpdateListener() {
                    @Override
                    void onAnimationUpdate(float a, float b) {
                        invalidate();
                        target.setX(a * oldX + b * newX);
                        target.setY(a * oldY + b * newY);
                    }
                });
                delay += PAGE_EXCHANGE_DELAY_AMOUNT;
                engine.addListener(mCountWatcher);
                engine.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        mCellLayoutPosition.set(previous, target);
                    }
                });
                engine.start();
            }
        } else {
            for (int i = home - 1; i >= destination; i--) {
                final View target = mCellLayoutPosition.get(i);
                ValueAnimator engine = ValueAnimator.ofFloat(0f, 1f).setDuration(150);
                engine.setStartDelay(delay);
                final int next = i + 1;
                final float oldX = target.getX();
                final float oldY = target.getY();
                final float newX = mMatrix[next][0];
                final float newY = mMatrix[next][1];
                engine.addUpdateListener(new LauncherAnimatorUpdateListener() {
                    @Override
                    void onAnimationUpdate(float a, float b) {
                        invalidate();
                        target.setX(a * oldX + b * newX);
                        target.setY(a * oldY + b * newY);
                    }
                });
                delay += PAGE_EXCHANGE_DELAY_AMOUNT;
                engine.addListener(mCountWatcher);
                engine.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        mCellLayoutPosition.set(next, target);
                    }
                });
                engine.start();
            } 
        }
        mCellLayoutPosition.set(destination, mDragTargetLayout);
        mRelayoutNeeded = true;
//        debugArray();
    }

    /**
     * This list contains cellLayouts which will be deleted in next changeState happens
     */
    private ArrayList<CellLayout> mDeletedLayouts = new ArrayList<CellLayout>();
    /**
     * mCellLayoutPosition contains every child view's position, like field mChildren[] in ViewGroup,
     * the diffrent is that workspace's layout process use this field instead of mChildren[],
     * which means workspace can modify CellLayout's layout coordinates with little cost, 
     * and the index in mChildren[] remains invariant.
     */
    private ArrayList<View> mCellLayoutPosition;
    private CountWatcher mCountWatcher = new CountWatcher();
    private class CountWatcher extends AnimatorListenerAdapter {
        private int mAnimatingCount;
        @Override
        public void onAnimationEnd(Animator animation) {
            mAnimatingCount--;
        }
        @Override
        public void onAnimationStart(Animator animation) {
            mAnimatingCount++;
        }
        
        public boolean isAnimateRest() {
            return mAnimatingCount == 0;
        }
    }
    private boolean mPreviewsLongPressPerformed;
    private int mLastPassingByPage;
    private int mSavedChildIndex;
    private boolean mRelayoutNeeded;
    
    private void postLongPressAction() {
        mPreviewsLongPressPerformed = false;
        mLastPassingByPage = INVALID_PAGE;
        if (mCheckForLongPressOfPreview == null) {
            mCheckForLongPressOfPreview = new Runnable() {
                @Override
                public void run() {
                    if (mPreviewClickTargetPage >= 0) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        final CellLayout target = (CellLayout) getPageAt(mPreviewClickTargetPage);
                        final float oldScaleX = target.getScaleX();
                        final float oldScaleY = target.getScaleY();
                        final float newScaleX = oldScaleX * 1.2f;
                        final float newScaleY = oldScaleY * 1.2f;
                        ValueAnimator start = ValueAnimator.ofFloat(0.0f, 1.0f).setDuration(50);
                        start.addUpdateListener(new LauncherAnimatorUpdateListener() {
                            @Override
                            void onAnimationUpdate(float a, float b) {
                                invalidate();
                                target.setScaleX(a * oldScaleX + b * newScaleX);
                                target.setScaleY(a * oldScaleY + b * newScaleY);
                            }
                        });
                        start.addListener(mCountWatcher);
                        start.start();
                        mPreviewClickTargetPage = INVALID_PAGE;
                        mPreviewsLongPressPerformed = true;
                        mSavedChildIndex = indexOfChild(target);
//                        if (mSavedChildIndex < getChildCount() - 1) {
//                            bringChildToFront(target);//bring it to front so we can see it above all of others
//                        }
                        mDragTargetLayout = target;
                    }
                }
            };
        }
        postDelayed(mCheckForLongPressOfPreview, ViewConfiguration.getLongPressTimeout());
    }
    
    @Override
    protected int getChildDrawingOrder(int childCount, int i) {
        if (mState == State.PREVIEWS && getChildAt(mSavedChildIndex) != null) {
            if (i == mSavedChildIndex) {
                return childCount - 1;
            } else if (i >= mSavedChildIndex) {
                return i + 1;
            }
        }
        return i;
    }
    
    @Override
    public void cancelLongPress() {
        super.cancelLongPress();
        if (mCheckForLongPressOfPreview != null) {
            removeCallbacks(mCheckForLongPressOfPreview);
        }
    }
    
    /**
     * Following three methods are used to seperate visual order of CellLayout from default order, 
     * we use visual order to implement movement process of CellLayout, 
     * and framework use default "getChildAt()" or "indexOfChild()" to implement dispatching of draw, touchevent, etc.  added by leeyb
     */
    @Override
    public View getPageAt(int index) {
        if (index < 0 || index >= mCellLayoutPosition.size()) {
            return null;
        }
        try {
            return mCellLayoutPosition.get(index);
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    @Override
    public int indexOfPage(View child) {
        int lookup = mCellLayoutPosition.indexOf(child);
        if (lookup < 0) {
            return indexOfChild(child);
        }
        return lookup;
    }

    @Override
    int getPageCount() {
        return mCellLayoutPosition.size();
    }

    private int indexOfTarget(int x, int y) {
        for (int i = 0; i < getPageCount(); i++) {
            View v = getPageAt(i);
            v.getHitRect(mTempRect);
            if (!mDeletedLayouts.contains(v) && mTempRect.contains(x, y) && v != mDragTargetLayout) {
                return i;
            }
        }
        if (mPersonator.visible) {
            mPersonator.getHitRect(mTempRect);
            if (mTempRect.contains(x, y)) {
                return INDEX_ADD;
            }
        }
        return INVALID_PAGE;
    }
    
    void restorePageOrderFromKey(String key) {
        int count = key.length();
//        Log.v("leeyb", "key:" + key);
        if (DEFAULT_WORKSPACE_ORDER_KEY.equals(key)) {
            setDataIsReady();
//            debugArray();
            return;
        } else if (count > MAX_WORKSPACE_CELLLAYOUT_NUMBER ||
                count < MIN_WORKSPACE_CELLLAYOUT_NUMBER) {
            Log.e("leeyb", count + " wrong... the number of celllayout in workspace can only between "
                    + MIN_WORKSPACE_CELLLAYOUT_NUMBER + " and " + MAX_WORKSPACE_CELLLAYOUT_NUMBER);
            restorePageOrderFromKey(DEFAULT_WORKSPACE_ORDER_KEY);
            return;
        }
        for (int i = 0; i < count; i++) {
            char symbol = key.charAt(i);
            int position = Character.getNumericValue(symbol);
            if (key.indexOf(symbol) != i) {
                Log.e("leeyb", "workspace want to restore page order from a Illegal key");
                restorePageOrderFromKey(DEFAULT_WORKSPACE_ORDER_KEY);
                mCellLayoutPosition.clear();
                for (int j = 0; j < getChildCount(); j++) {
                    mCellLayoutPosition.add(getChildAt(j));
                }
                return;
            }
            View v = getChildAt(position);
            if (v != null) {
                mCellLayoutPosition.set(i, v);
            }
        }
//        debugArray();
        setDataIsReady();
        requestLayout();
    }
    
    void restoreHomePageFromKey(int home) {
        CellLayout yo = (CellLayout) getPageAt(home);
        if (yo != null) {
            yo.setHome(true);
        } else {
            home = getPageCount() / 2;
            CellLayout you = (CellLayout) getPageAt(home);
            you.setHome(true);
        }
        mCurrentPage = mDefaultPage = home;
        Launcher.setScreen(home);
    }
    
    void rememeberRawSize(int width, int height) {
        mRawWidth = width;
        mRawHeight = height;            
    }
    
    @Override
    protected void handleCirculation() {
        if (mNextPage == -1) {
            scrollTo(getChildOffset(getPageCount() - 1) - getRelativeChildOffset(getPageCount() - 1), 0);
            mNextPage = getPageCount() - 1;
        } else if (mNextPage == getPageCount()) {
            scrollTo(getChildOffset(0) - getRelativeChildOffset(0), 0);
            mNextPage = 0;
        }
    }

    @Override
    public void scrollBy(int x, int y) {
        final int width = getMeasuredWidth();
        if (mScrollX <= -width) {
            mNextPage = -1;
        } else if (mScrollX >= mMaxScrollX + width) {
            mNextPage = getPageCount();
        }
        super.scrollBy(x, y);
    }
    
    @Override
    protected void onDrawBeforeDispatching(Canvas canvas, int screenCenter) {
        super.onDrawBeforeDispatching(canvas, screenCenter);
        if (mState == State.NORMAL) {
            if (screenCenter > getChildOffset(getPageCount() - 1) - getRelativeChildOffset(getPageCount() - 1)) {
                View fake = getPageAt(0);
                if (fake != null) {
                    canvas.save();
                    canvas.translate(getPageCount() * getMeasuredWidth(), 0);
                    drawChild(canvas, fake, getDrawingTime());
                    canvas.restore();
                }
            } else if (screenCenter < getMeasuredWidth()) {
                View fake = getPageAt(getPageCount() - 1);
                if (fake != null) {
                    canvas.save();
                    canvas.translate(-getPageCount() * getMeasuredWidth(), 0);
                    drawChild(canvas, fake, getDrawingTime());
                    canvas.restore();
                }
            }
        }
    }
    
    private int mRawWidth;
    private int mRawHeight;
    static final int INDEX_ADD = -100;
    
    class CellLayoutPersonator extends View implements BackgroundAlphable{
        private float mBackgroundAlpha;
        private Drawable mAddCross;
        private Drawable mBackground;
        boolean visible;
        float x;
        float y;
        float scaleX = 1.0f;
        float scaleY = 1.0f;

        public CellLayoutPersonator(Context context) {
            super(context);
            mAddCross = getResources().getDrawable(R.drawable.homescreen_quick_view_add);
            mBackground = getResources().getDrawable(R.drawable.homescreen_quick_view_bg);
        }
        
        public int turnBack() {
            int local = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == View.VISIBLE) {
                    local += v.getMeasuredWidth();
                }
            }
            return local;
        }
        
        public void applyNewCellLayout(CellLayout added) {
            added.setX(x);
            added.setY(y);
            added.setPivotX(0.0f);
            added.setPivotY(0.0f);
            added.setScaleX(scaleX);
            added.setScaleY(scaleY);
            added.setVisibility(View.VISIBLE);
            added.setBackgroundFocused(false);
            added.setOnLongClickListener(mLauncher);
            added.setBackgroundDrawable(mBackground);
            added.setBackgroundAlpha(mBackgroundAlpha);
            added.rememberRawSize(mRawWidth, mRawHeight);
            visible = getPageCount() < MAX_WORKSPACE_CELLLAYOUT_NUMBER;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            mAddCross.setBounds(0, 0, mAddCross.getIntrinsicWidth() * 2, mAddCross.getIntrinsicHeight() * 2);
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            mBackground.setBounds(0,  0, width, height);
        }

        @Override
        public void setBackgroundAlpha(float alpha) {
            mBackgroundAlpha = alpha;
        }

        @Override
        public float getBackgroundAlpha() {
            return mBackgroundAlpha;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.save();
            Rect out = mBackground.getBounds();
            Rect in = mAddCross.getBounds();
            canvas.scale((float)mRawWidth / out.width(), (float)mRawHeight / out.height(),
                    mScrollX + out.width() * 0.5f, mScrollY + out.height() * 0.5f);
            mBackground.clearColorFilter();
            mBackground.setAlpha((int) (255 * mBackgroundAlpha));
            mBackground.draw(canvas);
            canvas.restore();
            canvas.save();
            canvas.translate((out.width() - in.width()) / 2, (out.height() - in.height()) / 2);
            mAddCross.setAlpha((int) (255 * mBackgroundAlpha));
            mAddCross.draw(canvas);
            canvas.restore();
        }

        @Override
        public void setX(float x) {
            this.x = x;
        }

        @Override
        public void setY(float y) {
            this.y = y;
        }
        
        @Override
        public float getX() {
            return x;
        }
        
        @Override
        public float getY() {
            return y;
        }
        
        @Override
        public void setScaleX(float scaleX) {
            this.scaleX = scaleX;
        }

        @Override
        public void setScaleY(float scaleY) {
            this.scaleY = scaleY;
        }

        @Override
        public float getScaleX() {
            return scaleX;
        }

        @Override
        public float getScaleY() {
            return scaleY;
        }

        @Override
        public void getHitRect(Rect outRect) {
            Rect out = mBackground.getBounds();
            outRect.set((int)x, (int)y, (int)(x + out.width() * scaleX), (int)(y + out.height() * scaleY));
        }
    }
}
