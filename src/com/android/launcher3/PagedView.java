/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.util.ArrayList;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.Checkable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.IMTKWidget;
import android.widget.Scroller;
import android.widget.TextView;

import com.android.launcher3.R;

/**
 * An abstraction of the original Workspace which supports browsing through a
 * sequential list of "pages"
 */
public abstract class PagedView extends ViewGroup implements SlideDivider.onDotDragListener
     , SlideDivider.onDotClickListener{
    private static final String TAG = "PagedView";
    private static final boolean DEBUG = false;
    protected static final int INVALID_PAGE = -2;

    enum Style{
    	ADW,
    	SUMSUNG,
    	HORIZONTAL,
    	VETICAL,
    }
    
    // the min drag distance for a fling to register, to prevent random page shifts
    private static final int MIN_LENGTH_FOR_FLING = 15;

    protected static final int PAGE_SNAP_ANIMATION_DURATION = 300;
    protected static final float NANOTIME_DIV = 1000000000.0f;

    private static final float OVERSCROLL_ACCELERATE_FACTOR = 3;
    private static final float OVERSCROLL_DAMP_FACTOR = 0.14f;
    static final int MINIMUM_SNAP_VELOCITY = 2200;
    private static final int MIN_FLING_VELOCITY = 250;
    private static final float RETURN_TO_ORIGINAL_PAGE_THRESHOLD = 0.33f;
    // The page is moved more than halfway, automatically move to the next page on touch up.
    private static final float SIGNIFICANT_MOVE_THRESHOLD = 0.4f;

    // the velocity at which a fling gesture will cause us to snap to the next page
    protected int mSnapVelocity = 200;

    protected float mDensity;
    protected float mSmoothingTime;
    protected float mTouchX;

    protected boolean mFirstLayout = true;

    protected int mCurrentPage;
    protected int mNextPage = INVALID_PAGE;
    protected int mMaxScrollX;
    protected Scroller mScroller;
    protected VelocityTracker mVelocityTracker;
    private float mDownMotionX;
    protected float mLastMotionX;
    protected float mLastMotionXRemainder;
    protected float mLastMotionY;
    protected float mTotalMotionX;
    private int mLastScreenCenter = -1;
    private int[] mChildOffsets;
    private int[] mChildRelativeOffsets;
    private int[] mChildOffsetsWithLayoutScale;

    protected final static int TOUCH_STATE_REST = 0;
    protected final static int TOUCH_STATE_SCROLLING = 1;
    protected final static int TOUCH_STATE_PREV_PAGE = 2;
    protected final static int TOUCH_STATE_NEXT_PAGE = 3;
    protected final static float ALPHA_QUANTIZE_LEVEL = 0.0001f;

    protected int mTouchState = TOUCH_STATE_REST;
    protected boolean mForceScreenScrolled = false;

    protected OnLongClickListener mLongClickListener;

    protected boolean mAllowLongPress = true;

    protected int mTouchSlop;
    private int mPagingTouchSlop;
    private int mMaximumVelocity;
    private int mMinimumWidth;
    protected int mPageSpacing;
    protected int mPageLayoutPaddingTop;
    protected int mPageLayoutPaddingBottom;
    protected int mPageLayoutPaddingLeft;
    protected int mPageLayoutPaddingRight;
    protected int mPageLayoutWidthGap;
    protected int mPageLayoutHeightGap;
    protected int mCellCountX = 0;
    protected int mCellCountY = 0;
    protected boolean mCenterPagesVertically;
    protected boolean mAllowOverScroll = true;
    protected int mUnboundedScrollX;
    protected int[] mTempVisiblePagesRange = new int[3];

    // mOverScrollX is equal to mScrollX when we're within the normal scroll range. Otherwise
    // it is equal to the scaled overscroll position. We use a separate value so as to prevent
    // the screens from continuing to translate beyond the normal bounds.
    protected int mOverScrollX;

    // parameter that adjusts the layout to be optimized for pages with that scale factor
    protected float mLayoutScale = 1.0f;

    protected static final int INVALID_POINTER = -1;

    protected int mActivePointerId = INVALID_POINTER;

    private PageSwitchListener mPageSwitchListener;

    protected ArrayList<Boolean> mDirtyPageContent;

    // choice modes
    protected static final int CHOICE_MODE_NONE = 0;
    protected static final int CHOICE_MODE_SINGLE = 1;
    // Multiple selection mode is not supported by all Launcher actions atm
    protected static final int CHOICE_MODE_MULTIPLE = 2;

    protected int mChoiceMode;
    private ActionMode mActionMode;

    // If true, syncPages and syncPageItems will be called to refresh pages
    protected boolean mContentIsRefreshable = true;

    // If true, modify alpha of neighboring pages as user scrolls left/right
    protected boolean mFadeInAdjacentScreens = true;

    // It true, use a different slop parameter (pagingTouchSlop = 2 * touchSlop) for deciding
    // to switch to a new page
    protected boolean mUsePagingTouchSlop = true;

    // If true, the subclass should directly update mScrollX itself in its computeScroll method
    // (SmoothPagedView does this)
    protected boolean mDeferScrollUpdate = false;

    protected boolean mIsPageMoving = false;

    // All syncs and layout passes are deferred until data is ready.
    protected boolean mIsDataReady = false;
    protected Launcher mLauncher;

    // Scrolling indicator
    private ValueAnimator mScrollIndicatorAnimator;
    protected SlideDivider mScrollIndicator;
    private int mScrollIndicatorPaddingLeft;
    private int mScrollIndicatorPaddingRight;
    private boolean mHasScrollIndicator = true;
    private boolean mIamAppPageView = false;
    protected static final int sScrollIndicatorFadeInDuration = 150;
    protected static final int sScrollIndicatorFadeOutDuration = 650;
    protected static final int sScrollIndicatorFlashDuration = 650;

    // If set, will defer loading associated pages until the scrolling settles
    private boolean mDeferLoadAssociatedPagesUntilScrollCompletes;

    protected static boolean canSendMessage = true;
    
    private static final boolean ENABLE_GOOGLE_SMOOTH = false;

    public interface PageSwitchListener {
        void onPageSwitch(View newPage, int newPageIndex);
    }

    public PagedView(Context context) {
        this(context, null);
    }

    public PagedView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PagedView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mChoiceMode = CHOICE_MODE_NONE;
        mIamAppPageView = this instanceof AppsCustomizePagedView;
        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.PagedView, defStyle, 0);
        setPageSpacing(a.getDimensionPixelSize(R.styleable.PagedView_pageSpacing, 0));
        mPageLayoutPaddingTop = a.getDimensionPixelSize(
                R.styleable.PagedView_pageLayoutPaddingTop, 0);
        mPageLayoutPaddingBottom = a.getDimensionPixelSize(
                R.styleable.PagedView_pageLayoutPaddingBottom, 0);
        mPageLayoutPaddingLeft = a.getDimensionPixelSize(
                R.styleable.PagedView_pageLayoutPaddingLeft, 0);
        mPageLayoutPaddingRight = a.getDimensionPixelSize(
                R.styleable.PagedView_pageLayoutPaddingRight, 0);
        mPageLayoutWidthGap = a.getDimensionPixelSize(
                R.styleable.PagedView_pageLayoutWidthGap, 0);
        mPageLayoutHeightGap = a.getDimensionPixelSize(
                R.styleable.PagedView_pageLayoutHeightGap, 0);
        mScrollIndicatorPaddingLeft =
            a.getDimensionPixelSize(R.styleable.PagedView_scrollIndicatorPaddingLeft, 0);
        mScrollIndicatorPaddingRight =
            a.getDimensionPixelSize(R.styleable.PagedView_scrollIndicatorPaddingRight, 0);
        a.recycle();

        setHapticFeedbackEnabled(false);
        init();
    }

    /**
     * Initializes various states for this workspace.
     */
    protected void init() {
        mDirtyPageContent = new ArrayList<Boolean>();
        mDirtyPageContent.ensureCapacity(32);
        mScroller = new Scroller(getContext(), mIamAppPageView ? new ScrollInterpolator() : new DecelerateInterpolator());
        mCurrentPage = 0;
        mCenterPagesVertically = true;

        final ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mPagingTouchSlop = configuration.getScaledPagingTouchSlop();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mDensity = getResources().getDisplayMetrics().density;
    }

    public void setPageSwitchListener(PageSwitchListener pageSwitchListener) {
        mPageSwitchListener = pageSwitchListener;
        if (mPageSwitchListener != null) {
            mPageSwitchListener.onPageSwitch(getPageAt(mCurrentPage), mCurrentPage);
        }
    }

    /**
     * Called by subclasses to mark that data is ready, and that we can begin loading and laying
     * out pages.
     */
    protected void setDataIsReady() {
        mIsDataReady = true;
    }
    protected boolean isDataReady() {
        return mIsDataReady;
    }

    /**
     * Returns the index of the currently displayed page.
     *
     * @return The index of the currently displayed page.
     */
    int getCurrentPage() {
        return mCurrentPage;
    }

    int getPageCount() {
        return getChildCount();
    }

    View getPageAt(int index) {
        return getChildAt(index);
    }

    protected int indexToPage(int index) {
        return index;
    }

    /**
     * Updates the scroll of the current page immediately to its final scroll position.  We use this
     * in CustomizePagedView to allow tabs to share the same PagedView while resetting the scroll of
     * the previous tab page.
     */
    protected void updateCurrentPageScroll() {
        int newX = getChildOffset(mCurrentPage) - getRelativeChildOffset(mCurrentPage);
        scrollTo(newX, 0);
        mScroller.setFinalX(newX);
    }

    /**
     * Sets the current page.
     */
    void setCurrentPage(int currentPage) {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "setCurrentPage: currentPage = " + currentPage + ",mCurrentPage = "
                    + mCurrentPage + ",mScrollX = " + mScrollX + ",this = " + this);
        }
        if (!mScroller.isFinished()) {
            mScroller.abortAnimation();
        }
        // don't introduce any checks like mCurrentPage == currentPage here-- if we change the
        // the default
        if (getChildCount() == 0) {
            return;
        }
        mCurrentPage = Math.max(0, /*Math.min(*/currentPage/*, getPageCount() - 1)*/);
//        mScrollIndicator.setCurrentDivider(mCurrentPage);
        updateCurrentPageScroll();
//        updateScrollingIndicator();
        notifyPageSwitchListener();
        invalidate();
    }

    protected void notifyPageSwitchListener() {
        if (mPageSwitchListener != null) {
            mPageSwitchListener.onPageSwitch(getPageAt(mCurrentPage), mCurrentPage);
        }
    }

    protected void pageBeginMoving() {
        if (!mIsPageMoving) {
            mIsPageMoving = true;
            onPageBeginMoving();
        }
    }

    protected void pageEndMoving() {
        if (mIsPageMoving) {
            mIsPageMoving = false;
            onPageEndMoving();
        }
    }

    protected boolean isPageMoving() {
        return mIsPageMoving;
    }

    // a method that subclasses can override to add behavior
    protected void onPageBeginMoving() {
//        showScrollingIndicator(false);
    }

    // a method that subclasses can override to add behavior
    protected void onPageEndMoving() {
//        hideScrollingIndicator(false);
    }

    /**
     * Registers the specified listener on each page contained in this workspace.
     *
     * @param l The listener used to respond to long clicks.
     */
    @Override
    public void setOnLongClickListener(OnLongClickListener l) {
        mLongClickListener = l;
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            getChildAt(i).setOnLongClickListener(l);
        }
    }

    @Override
    public void scrollBy(int x, int y) {
        scrollTo(mUnboundedScrollX + x, mScrollY + y);
    }

    @Override
    public void scrollTo(int x, int y) {
        mUnboundedScrollX = x;
        super.scrollTo(x, y);
        mTouchX = x;
        mSmoothingTime = System.nanoTime() / NANOTIME_DIV;
        mOverScrollX = x;
        /*if (x < 0) {
            super.scrollTo(0, y);
            if (mAllowOverScroll) {
                overScroll(x);
            }
        } else if (x > mMaxScrollX) {
            super.scrollTo(mMaxScrollX, y);
            if (mAllowOverScroll) {
                overScroll(x - mMaxScrollX);
            }
        } else {
            mOverScrollX = x;
            super.scrollTo(x, y);
        }

        mTouchX = x;
        mSmoothingTime = System.nanoTime() / NANOTIME_DIV;*/
    }

    // we moved this functionality to a helper function so SmoothPagedView can reuse it
    protected boolean computeScrollHelper() {
        if (mScroller.computeScrollOffset()) {
         // Don't bother scrolling if the page does not need to be moved
            if (mScrollX != mScroller.getCurrX() || mScrollY != mScroller.getCurrY()) {
                scrollTo(mScroller.getCurrX(), mScroller.getCurrY());
            }
            postInvalidate();
            return true;
        } else if (mNextPage != INVALID_PAGE) {
        	handleCirculation();
        	View whichHostView = getPageAt(mNextPage);
			View whichMtkWidgetView = searchIMTKWidget(whichHostView);
			if (whichMtkWidgetView != null) {
				((IMTKWidget) whichMtkWidgetView).moveIn(mNextPage);
				canSendMessage = true;
                if (LauncherLog.DEBUG_SURFACEWIDGET) {
                    LauncherLog.d(Launcher.TAG_SURFACEWIDGET, "moveIn");
                }
			}
			mCurrentPage = mNextPage;
            mNextPage = INVALID_PAGE;
            notifyPageSwitchListener();
//            updateDivider(mCurrentPage);
//            mScrollIndicator.setCurrentDivider(mCurrentPage);
            invalidate();

            // Load the associated pages if necessary
            if (mDeferLoadAssociatedPagesUntilScrollCompletes) {
                loadAssociatedPages(mCurrentPage);
                mDeferLoadAssociatedPagesUntilScrollCompletes = false;
            }

            // We don't want to trigger a page end moving unless the page has settled
            // and the user has stopped scrolling
            if (mTouchState == TOUCH_STATE_REST) {
                pageEndMoving();
            }

            // Notify the user when the page changes
            if (AccessibilityManager.getInstance(getContext()).isEnabled()) {
                AccessibilityEvent ev =
                    AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
                ev.getText().add(getCurrentPageDescription());
                sendAccessibilityEventUnchecked(ev);
            }
            return true;
        }
        return false;
    }

    /**
     * handle scroll in previews situation,  added by leeyb
     * @return
     */
    protected boolean computeScrollPreviewsHelper() {
        return false;
    }

    /**
     *circulate operation should implement here , added by leeyb
     */
    protected void handleCirculation() {
	}

	@Override
    public void computeScroll() {
        computeScrollHelper();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (!mIsDataReady) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        if (widthMode != MeasureSpec.EXACTLY) {
            throw new IllegalStateException("Workspace can only be used in EXACTLY mode.");
        }

        /* Allow the height to be set as WRAP_CONTENT. This allows the particular case
         * of the All apps view on XLarge displays to not take up more space then it needs. Width
         * is still not allowed to be set as WRAP_CONTENT since many parts of the code expect
         * each page to have the same width.
         */
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int maxChildHeight = 0;

        final int verticalPadding = mPaddingTop + mPaddingBottom;
        final int horizontalPadding = mPaddingLeft + mPaddingRight;


        // The children are given the same width and height as the workspace
        // unless they were set to WRAP_CONTENT
        if (DEBUG) Log.d(TAG, "PagedView.onMeasure(): " + widthSize + ", " + heightSize);
        final int childCount = getPageCount();
        for (int i = 0; i < childCount; i++) {
            // disallowing padding in paged view (just pass 0)
            final View child = getPageAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            int childWidthMode;
            if (lp.width == LayoutParams.WRAP_CONTENT) {
                childWidthMode = MeasureSpec.AT_MOST;
            } else {
                childWidthMode = MeasureSpec.EXACTLY;
            }

            int childHeightMode;
            if (lp.height == LayoutParams.WRAP_CONTENT) {
                childHeightMode = MeasureSpec.AT_MOST;
            } else {
                childHeightMode = MeasureSpec.EXACTLY;
            }

            final int childWidthMeasureSpec =
                MeasureSpec.makeMeasureSpec(widthSize - horizontalPadding, childWidthMode);
            final int childHeightMeasureSpec =
                MeasureSpec.makeMeasureSpec(heightSize - verticalPadding, childHeightMode);

            child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
            maxChildHeight = Math.max(maxChildHeight, child.getMeasuredHeight());
            if (LauncherLog.DEBUG_LAYOUT) {
                LauncherLog.d(TAG, "measure-child " + i + ": child = " + child
                        + ",childWidthMode = " + childWidthMode + ", childHeightMode = "
                        + childHeightMode + ",this = " + this);
            }            
        }

        if (heightMode == MeasureSpec.AT_MOST) {
            heightSize = maxChildHeight + verticalPadding;
        }

        setMeasuredDimension(widthSize, heightSize);

        // We can't call getChildOffset/getRelativeChildOffset until we set the measured dimensions.
        // We also wait until we set the measured dimensions before flushing the cache as well, to
        // ensure that the cache is filled with good values.
        invalidateCachedOffsets();
//        updateScrollingIndicatorPosition();
//        mScrollIndicator.setCurrentDivider(mCurrentPage);
    }

    protected void scrollToNewPageWithoutMovingPages(int newCurrentPage) {        
        int newX = getChildOffset(newCurrentPage) - getRelativeChildOffset(newCurrentPage);
        int delta = newX - mScrollX;
        final int pageCount = getPageCount();
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "Scroll to new page without moving pages: newCurrentPage = "
                    + newCurrentPage + ",newX = " + newX + ",mScrollX = " + mScrollX);
        }
        for (int i = 0; i < pageCount; i++) {
            View page = (View) getPageAt(i);
            page.setX(page.getX() + delta);
        }
        setCurrentPage(newCurrentPage);
    }

    // A layout scale of 1.0f assumes that the pages, in their unshrunken state, have a
    // scale of 1.0f. A layout scale of 0.8f assumes the pages have a scale of 0.8f, and
    // tightens the layout accordingly
    public void setLayoutScale(float childrenScale) {
        mLayoutScale = childrenScale;
        invalidateCachedOffsets();

        // Now we need to do a re-layout, but preserving absolute X and Y coordinates
        int childCount = getPageCount();
        float childrenX[] = new float[childCount];
        float childrenY[] = new float[childCount];
        for (int i = 0; i < childCount; i++) {
            final View child = getPageAt(i);
            childrenX[i] = child.getX();
            childrenY[i] = child.getY();
        }
        // Trigger a full re-layout (never just call onLayout directly!)
        int widthSpec = MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY);
        int heightSpec = MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY);
        requestLayout();
        measure(widthSpec, heightSpec);
        layout(mLeft, mTop, mRight, mBottom);
        for (int i = 0; i < childCount; i++) {
            final View child = getPageAt(i);
            child.setX(childrenX[i]);
            child.setY(childrenY[i]);
        }

        // Also, the page offset has changed  (since the pages are now smaller);
        // update the page offset, but again preserving absolute X and Y coordinates
        scrollToNewPageWithoutMovingPages(mCurrentPage);
    }

    public void setPageSpacing(int pageSpacing) {
        mPageSpacing = pageSpacing;
        invalidateCachedOffsets();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (!mIsDataReady) {
            return;
        }
        
        if (DEBUG) Log.d(TAG, "PagedView.onLayout()");
        final int childCount = getChildCount();
//        int childLeft = 0;
        if (childCount > 0) {
            if (DEBUG) Log.d(TAG, "getRelativeChildOffset(): " + getMeasuredWidth() + ", "
                    + getChildWidth(0));
//            childLeft = getRelativeChildOffset(0);

            // Calculate the variable page spacing if necessary
            if (mPageSpacing < 0) {
                setPageSpacing(((right - left) - getPageAt(0).getMeasuredWidth()) / 2);
            }
        }
        
        layoutChildren();

        if (mFirstLayout && mCurrentPage >= 0/* && mCurrentPage < getChildCount()*/) {
            setHorizontalScrollBarEnabled(false);
            int newX = getChildOffset(mCurrentPage) - getRelativeChildOffset(mCurrentPage);
            scrollTo(newX, 0);
            mScroller.setFinalX(newX);
            setHorizontalScrollBarEnabled(true);
            mFirstLayout = false;
        }

//        if (mFirstLayout && mCurrentPage >= 0 && mCurrentPage < getChildCount()) {
//            mFirstLayout = false;
//        }
    }
    
    /**
     * Subclass must override this method implement layout process , added by leeyb
     */
    protected abstract void layoutChildren();

	protected void screenScrolled(int center) {
//        if (isScrollingIndicatorEnabled()) {
//            updateScrollingIndicator();
//        }
//        if (mFadeInAdjacentScreens) {
//            for (int i = 0; i < getChildCount(); i++) {
//                View child = getChildAt(i);
//                if (child != null) {
//                    float scrollProgress = getScrollProgress(screenCenter, child, i);
//                    float alpha = 1 - Math.abs(scrollProgress);
//                    child.setFastAlpha(alpha);
//                    child.fastInvalidate();
//                }
//            }
//            invalidate();
//        }
    }

    @Override
    protected void onViewAdded(View child) {
        super.onViewAdded(child);
        // This ensures that when children are added, they get the correct transforms / alphas
        // in accordance with any scroll effects.
        mForceScreenScrolled = true;
        invalidate();
        invalidateCachedOffsets();
    }
    
    protected void invalidateCachedOffsets() {
        int count = getChildCount();
        if (count == 0) {
            mChildOffsets = null;
            mChildRelativeOffsets = null;
            mChildOffsetsWithLayoutScale = null;
            return;
        }

        mChildOffsets = new int[count];
        mChildRelativeOffsets = new int[count];
        mChildOffsetsWithLayoutScale = new int[count];
        for (int i = 0; i < count; i++) {
            mChildOffsets[i] = -1;
            mChildRelativeOffsets[i] = -1;
            mChildOffsetsWithLayoutScale[i] = -1;
        }
        if (count > 0) {
            mMaxScrollX = getChildOffset(count - 1) - getRelativeChildOffset(count - 1);
        } else {
            mMaxScrollX = 0;
        }
    }

    protected int getChildOffset(int index) {
        int[] childOffsets = mChildOffsets/*Float.compare(mLayoutScale, 1f) == 0 ?
                mChildOffsets : mChildOffsetsWithLayoutScale*/;
        if (childOffsets != null && childOffsets[getPagePositionInArray(index)] != -1) {
            return childOffsets[getPagePositionInArray(index)];
        } else {
            if (getChildCount() == 0)
                return 0;

            int offset = getRelativeChildOffset(0);
            for (int i = 0; i < index; ++i) {
                offset += getMeasuredWidth()/*getScaledMeasuredWidth(mIamAppPageView ?
                		((AppsCustomizePagedView)this) :getPageAt(i)) + mPageSpacing * 2*/;
            }
            if (childOffsets != null) {
                childOffsets[getPagePositionInArray(index)] = offset;
            }
            return offset;
        }
    }

    protected int getPagePositionInArray(int index) {
		// TODO Auto-generated method stub
		return index;
	}

	protected int getRelativeChildOffset(int index) {
        if (mChildRelativeOffsets != null && mChildRelativeOffsets[getPagePositionInArray(index)] != -1) {
            if (LauncherLog.DEBUG_DRAW) {
                LauncherLog.d(TAG, "getRelativeChildOffset 1: index = " + index +
                        ",mChildRelativeOffsets[index] = " + mChildRelativeOffsets[getPagePositionInArray(index)] +
                        ",this = " + this);
            }
            return mChildRelativeOffsets[getPagePositionInArray(index)];
        } else {
            final int padding = mPaddingLeft + mPaddingRight;
            final int offset = mPaddingLeft +
                    (getMeasuredWidth() - padding - getChildWidth(index)) / 2;
            if (mChildRelativeOffsets != null) {
                mChildRelativeOffsets[getPagePositionInArray(index)] = offset;
            }
            if (LauncherLog.DEBUG_DRAW) {
                LauncherLog.d(TAG, "getRelativeChildOffset 2: index = " + index
                        + ",mPaddingLeft = " + mPaddingLeft + ",mPaddingRight = " + mPaddingRight
                        + ",padding = " + padding + ",offset = " + offset + ",measure width = "
                        + getMeasuredWidth() + ",this = " + this);
            }
            return offset;
        }
    }

    protected int getScaledRelativeChildOffset(int index) {
        final int padding = mPaddingLeft + mPaddingRight;
        final int offset = mPaddingLeft + (getMeasuredWidth() - padding -
                getScaledMeasuredWidth(getPageAt(index))) / 2;
        return offset;
    }

    protected int getScaledMeasuredWidth(View child) {
        // This functions are called enough times that it actually makes a difference in the
        // profiler -- so just inline the max() here
    	if (child == null) {
			return getMeasuredWidth();
		}
        final int measuredWidth = child.getMeasuredWidth();
        final int minWidth = mMinimumWidth;
        final int maxWidth = (minWidth > measuredWidth) ? minWidth : measuredWidth;
        return (int) (maxWidth * mLayoutScale + 0.5f);
    }

    protected void getVisiblePages(int[] range, int center) {
        range[0] = mCurrentPage - 1;
        range[1] = mCurrentPage;
        range[2] = mCurrentPage + 1;
        /*final int pageCount = getChildCount();
        if (pageCount > 0) {
            final int pageWidth = getScaledMeasuredWidth(getPageAt(0));
            final int screenWidth = getMeasuredWidth();
            int x = getScaledRelativeChildOffset(0) + pageWidth;
            int leftScreen = 0;
            int rightScreen = 0;
            while (x <= mScrollX && leftScreen < pageCount - 1) {
                leftScreen++;
                x += getScaledMeasuredWidth(getPageAt(0)) + mPageSpacing;
            }
            rightScreen = leftScreen;
            while (x < mScrollX + screenWidth && rightScreen < pageCount - 1) {
                rightScreen++;
                x += getScaledMeasuredWidth(getPageAt(0)) + mPageSpacing;
            }
            range[0] = leftScreen;
            range[1] = rightScreen;
        } else {
            range[0] = -1;
            range[1] = -1;
        }*/
    }
    @Override
    protected void dispatchDraw(Canvas canvas) {
        int halfScreenSize = getMeasuredWidth() / 2;
        // mOverScrollX is equal to mScrollX when we're within the normal scroll range. Otherwise
        // it is equal to the scaled overscroll position.
        int screenCenter = (mIamAppPageView ? mOverScrollX : mScrollX) + halfScreenSize;
        onDrawBeforeDispatching(canvas, screenCenter);
        final int pageCount = getChildCount();
        if ((screenCenter != mLastScreenCenter || mForceScreenScrolled) && pageCount > 0 && !isPreviewsState()) {
            screenScrolled(screenCenter);
            mLastScreenCenter = screenCenter;
            mForceScreenScrolled = false;
        }

        // Find out which screens are visible; as an optimization we only call draw on them
        if (LauncherLog.DEBUG_MOTION || LauncherLog.DEBUG_DRAW) {
            LauncherLog.d(TAG, "------dispatchDraw: mScrollX = " + mScrollX + ",screenCenter = "
                    + screenCenter + ",mOverScrollX = " + mOverScrollX + ",pageCount = "
                    + pageCount + ",mLeft = " + mLeft + ",mRight = " + mRight + ",this = " + this);
        }
        if (pageCount > 0) {
//            final int leftScreen = mTempVisiblePagesRange[0];
//            final int rightScreen = mTempVisiblePagesRange[1];
//            if (leftScreen != -1 && rightScreen != -1) {
                final long drawingTime = getDrawingTime();
                // Clip to the bounds
//                canvas.save();
//                canvas.clipRect(mScrollX, mScrollY, mScrollX + mRight - mLeft,
//                        mScrollY + mBottom - mTop);

                /*
                 * modified by leeyb, draw all child view immediately in previews case,
                 * draw part of them in nornal case, and do not clip canvas for previews animation
                 */
                if (isPreviewsState()) {
                    for (int i = 0; i < getChildCount(); i++) {
                        View v = getChildAt(i);
                        if (v.getVisibility() == View.VISIBLE) {
                            drawChild(canvas, v, drawingTime);
                        }
                    }
                } else {
                    getVisiblePages(mTempVisiblePagesRange, screenCenter);
                    for (int i = 0; i < mTempVisiblePagesRange.length; i++) {
                        View v = getPageAt(mTempVisiblePagesRange[i]);
                        if (v != null) {
                            drawChild(canvas, v, drawingTime);
                        }
                    }
                }
//                canvas.restore();
//            }
        }
    }
    
    /**
     * Supply a chance for subclass to do sth before real drawing happens,
     * such as 3D transformation, alpha animation,
     * added by leeyb
     * @param canvas
     * @param screenCenter 
     */
    protected void onDrawBeforeDispatching(Canvas canvas, int screenCenter) {
		updateDivider(screenCenter / getMeasuredWidth());
	}

	@Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        int page = indexToPage(indexOfPage(child));
        if (page != mCurrentPage || !mScroller.isFinished()) {
            snapToPage(page);
            return true;
        }
        return false;
    }
	
	protected int indexOfPage(View v) {
	    return indexOfChild(v);
	}

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        int focusablePage;
        if (mNextPage != INVALID_PAGE) {
            focusablePage = mNextPage;
        } else {
            focusablePage = mCurrentPage;
        }
        View v = getPageAt(focusablePage);
        if (v != null) {
            return v.requestFocus(direction, previouslyFocusedRect);
        }
        return false;
    }

    @Override
    public boolean dispatchUnhandledMove(View focused, int direction) {
        if (direction == View.FOCUS_LEFT) {
            if (getCurrentPage() > 0) {
                snapToPage(getCurrentPage() - 1);
                return true;
            }
        } else if (direction == View.FOCUS_RIGHT) {
            if (getCurrentPage() < getPageCount() - 1) {
                snapToPage(getCurrentPage() + 1);
                return true;
            }
        }
        return super.dispatchUnhandledMove(focused, direction);
    }

    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if (mCurrentPage >= 0 && mCurrentPage < getPageCount()) {
            getPageAt(mCurrentPage).addFocusables(views, direction);
        }
        if (direction == View.FOCUS_LEFT) {
            if (mCurrentPage > 0) {
                getPageAt(mCurrentPage - 1).addFocusables(views, direction);
            }
        } else if (direction == View.FOCUS_RIGHT){
            if (mCurrentPage < getPageCount() - 1) {
                getPageAt(mCurrentPage + 1).addFocusables(views, direction);
            }
        }
    }

    /**
     * If one of our descendant views decides that it could be focused now, only
     * pass that along if it's on the current page.
     *
     * This happens when live folders requery, and if they're off page, they
     * end up calling requestFocus, which pulls it on page.
     */
    @Override
    public void focusableViewAvailable(View focused) {
        View current = getPageAt(mCurrentPage);
        View v = focused;
        while (true) {
            if (v == current) {
                super.focusableViewAvailable(focused);
                return;
            }
            if (v == this) {
                return;
            }
            ViewParent parent = v.getParent();
            if (parent instanceof View) {
                v = (View)v.getParent();
            } else {
                return;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        if (disallowIntercept) {
            // We need to make sure to cancel our long press if
            // a scrollable widget takes over touch events
            final View currentPage = getPageAt(mCurrentPage);
            currentPage.cancelLongPress();
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    /**
     * Return true if a tap at (x, y) should trigger a flip to the previous page.
     */
    protected boolean hitsPreviousPage(float x, float y) {
        return (x < getRelativeChildOffset(mCurrentPage) - mPageSpacing);
    }

    /**
     * Return true if a tap at (x, y) should trigger a flip to the next page.
     */
    protected boolean hitsNextPage(float x, float y) {
        return  (x > (getMeasuredWidth() - getRelativeChildOffset(mCurrentPage) + mPageSpacing));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (LauncherLog.DEBUG_MOTION) {
            LauncherLog.d(TAG, "(PagedView)onInterceptTouchEvent: ev = " + ev +
                    ",mScrollX = " + mScrollX + ",this = " + this);
        }
        /*
         * This method JUST determines whether we want to intercept the motion.
         * If we return true, onTouchEvent will be called and we do the actual
         * scrolling there.
         */
        acquireVelocityTrackerAndAddMovement(ev);

        // Skip touch handling if there are no pages to swipe
        if (getChildCount() <= 0) {
            if (LauncherLog.DEBUG) {
                LauncherLog.d(TAG, "There are no pages to swipe, page count = " + getChildCount());
            }
        	return super.onInterceptTouchEvent(ev);
        }
        
        /*
         * Shortcut the most recurring case: the user is in the dragging
         * state and he is moving his finger.  We want to intercept this
         * motion.
         */
        final int action = ev.getAction();
        if ((action == MotionEvent.ACTION_MOVE) &&
                (mTouchState == TOUCH_STATE_SCROLLING)) {
            if (LauncherLog.DEBUG) {
                LauncherLog.d(TAG, "(PagedView)onInterceptTouchEvent touch move during scrolling.");
            }
            return true;
        }

        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE: {
                /*
                 * mIsBeingDragged == false, otherwise the shortcut would have caught it. Check
                 * whether the user has moved far enough from his original down touch.
                 */
                if (mActivePointerId != INVALID_POINTER) {
                    determineScrollingStart(ev);
                    break;
                }
                // if mActivePointerId is INVALID_POINTER, then we must have missed an ACTION_DOWN
                // event. in that case, treat the first occurence of a move event as a ACTION_DOWN
                // i.e. fall through to the next case (don't break)
                // (We sometimes miss ACTION_DOWN events in Workspace because it ignores all events
                // while it's small- this was causing a crash before we checked for INVALID_POINTER)
            }

            case MotionEvent.ACTION_DOWN: {
                final float x = ev.getX();
                final float y = ev.getY();
                // Remember location of down touch
                mDownMotionX = x;
                mLastMotionX = x;
                mLastMotionY = y;
                mLastMotionXRemainder = 0;
                mTotalMotionX = 0;
                mActivePointerId = ev.getPointerId(0);
                mAllowLongPress = true;
                
                mDistancePre = 0;
                
                /*
                 * If being flinged and user touches the screen, initiate drag;
                 * otherwise don't.  mScroller.isFinished should be false when
                 * being flinged.
                 */
                final int xDist = Math.abs(mScroller.getFinalX() - mScroller.getCurrX());
                final boolean finishedScrolling = (mScroller.isFinished() || xDist < mTouchSlop);
                if (finishedScrolling) {
//                    Log.v("leeyb", "mTouchState = TOUCH_STATE_REST here 1072");
                    mTouchState = TOUCH_STATE_REST;
                    mScroller.abortAnimation();
                } else {
                    mTouchState = TOUCH_STATE_SCROLLING;
                }

                // check if this can be the beginning of a tap on the side of the pages
                // to scroll the current page
                if (mTouchState != TOUCH_STATE_PREV_PAGE && mTouchState != TOUCH_STATE_NEXT_PAGE) {
                    if (getChildCount() > 0) {
                        if (hitsPreviousPage(x, y)) {
                            mTouchState = TOUCH_STATE_PREV_PAGE;
                        } else if (hitsNextPage(x, y)) {
                            mTouchState = TOUCH_STATE_NEXT_PAGE;
                        }
                    }
                }
                if (LauncherLog.DEBUG_MOTION) {
                    LauncherLog.d(TAG, "onInterceptTouchEvent touch down: finishedScrolling = "
                            + finishedScrolling + ",mScrollX = " + mScrollX + ",xDist = " + xDist
                            + ",mTouchState = " + mTouchState + ",this = " + this);
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                /*
                 * It means the workspace is in the middle if the scrollX can
                 * not be divided by the width of its child, need to snap to edge.
                 */
                final View firstChild = getPageAt(0);
                if (LauncherLog.DEBUG_MOTION && firstChild != null) {
                    LauncherLog.d(TAG, "onInterceptTouchEvent ACTION_UP: mTouchState = "
                            + mTouchState + ",mScrollX = " + mScrollX + ",child width = "
                            + firstChild.getMeasuredWidth() + ",this = " + this);
                }
                if (firstChild != null && mScrollX % ( firstChild.getMeasuredWidth() + mPageSpacing * 2 ) != 0) {
                    snapToDestination();
                }
//                Log.v("leeyb", "mTouchState = TOUCH_STATE_REST here 1114");
                mTouchState = TOUCH_STATE_REST;
                mAllowLongPress = false;
                mActivePointerId = INVALID_POINTER;
                releaseVelocityTracker();
                break;

            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                releaseVelocityTracker();
                break;
        }

        /*
         * The only time we want to intercept motion events is if we are in the
         * drag mode.
         */
        boolean handle = mTouchState != TOUCH_STATE_REST || isPreviewsState();
        if (!handle && ev.getPointerCount() > 1) {
            cancelCurrentPageLongPress();
            handle = true;
        }
        Log.v("TouchDetector", "onInterceptTouchEvent PagedView event:" + ev.getAction() + "\thandle:" + handle);
        return handle;
    }

    protected void animateClickFeedback(View v, final Runnable r) {
        // animate the view slightly to show click feedback running some logic after it is "pressed"
        ObjectAnimator anim = (ObjectAnimator) AnimatorInflater.
                loadAnimator(mContext, R.anim.paged_view_click_feedback);
        anim.setTarget(v);
        anim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationRepeat(Animator animation) {
                r.run();
            }
        });
        anim.start();
    }

    protected void determineScrollingStart(MotionEvent ev) {
        determineScrollingStart(ev, 1.0f);
    }

    /*
     * Determines if we should change the touch state to start scrolling after the
     * user moves their touch point too far.
     */
    protected void determineScrollingStart(MotionEvent ev, float touchSlopScale) {
        /*
         * Locally do absolute value. mLastMotionX is set to the y value
         * of the down event.
         */
        final int pointerIndex = ev.findPointerIndex(mActivePointerId);
        if (pointerIndex == -1) {
            if (LauncherLog.DEBUG) {
                LauncherLog.d(TAG, "(PagedView)determineScrollingStart pointerIndex == -1.");
            }
            return;
        }
        final float x = ev.getX(pointerIndex);
        final float y = ev.getY(pointerIndex);
        final int xDiff = (int) Math.abs(x - mLastMotionX);
        final int yDiff = (int) Math.abs(y - mLastMotionY);

        final int touchSlop = Math.round(touchSlopScale * mTouchSlop);
        boolean xPaged = xDiff > mPagingTouchSlop;
        boolean xMoved = xDiff > touchSlop;
        boolean yMoved = yDiff > touchSlop;

        if (xMoved || xPaged || yMoved) {
            if (mUsePagingTouchSlop ? xPaged : xMoved) {
                // Scroll if the user moved far enough along the X axis
                mTouchState = TOUCH_STATE_SCROLLING;
                mTotalMotionX += Math.abs(mLastMotionX - x);
                mLastMotionX = x;
                mLastMotionXRemainder = 0;
                mTouchX = mScrollX;
                mSmoothingTime = System.nanoTime() / NANOTIME_DIV;
                pageBeginMoving();
            }
            // Either way, cancel any pending longpress
            cancelCurrentPageLongPress();
        }
    }

    protected void cancelCurrentPageLongPress() {
        if (mAllowLongPress) {
            mAllowLongPress = false;
            // Try canceling the long press. It could also have been scheduled
            // by a distant descendant, so use the mAllowLongPress flag to block
            // everything
            final View currentPage = getPageAt(mCurrentPage);
            if (currentPage != null) {
                currentPage.cancelLongPress();
            }
        }
    }

    protected float getScrollProgress(int screenCenter, View v, int page) {
        final int halfScreenSize = getMeasuredWidth() / 2;

        int totalDistance = getScaledMeasuredWidth(v) + mPageSpacing;
        int delta = screenCenter - (page * getMeasuredWidth()/*v.getLeft()getChildOffset(page) -
                getRelativeChildOffset(page)*/ + halfScreenSize);

        float scrollProgress = delta / (totalDistance * 1.0f);
        scrollProgress = Math.min(scrollProgress, 1.0f);
        scrollProgress = Math.max(scrollProgress, -1.0f);
        return scrollProgress;
    }

    // This curve determines how the effect of scrolling over the limits of the page dimishes
    // as the user pulls further and further from the bounds
    private float overScrollInfluenceCurve(float f) {
        f -= 1.0f;
        return f * f * f + 1.0f;
    }

    protected void acceleratedOverScroll(float amount) {
        int screenSize = getMeasuredWidth();

        // We want to reach the max over scroll effect when the user has
        // over scrolled half the size of the screen
        float f = OVERSCROLL_ACCELERATE_FACTOR * (amount / screenSize);

        if (f == 0) return;

        // Clamp this factor, f, to -1 < f < 1
        if (Math.abs(f) >= 1) {
            f /= Math.abs(f);
        }

        int overScrollAmount = (int) Math.round(f * screenSize);
        if (amount < 0) {
            mOverScrollX = overScrollAmount;
            mScrollX = 0;
        } else {
            mOverScrollX = mMaxScrollX + overScrollAmount;
            mScrollX = mMaxScrollX;
        }
        invalidate();
    }

    protected void dampedOverScroll(float amount) {
        int screenSize = getMeasuredWidth();

        float f = (amount / screenSize);

        if (f == 0) return;
        f = f / (Math.abs(f)) * (overScrollInfluenceCurve(Math.abs(f)));

        // Clamp this factor, f, to -1 < f < 1
        if (Math.abs(f) >= 1) {
            f /= Math.abs(f);
        }

        int overScrollAmount = (int) Math.round(OVERSCROLL_DAMP_FACTOR * f * screenSize);
        if (amount < 0) {
            mOverScrollX = overScrollAmount;
            mScrollX = 0;
        } else {
            mOverScrollX = mMaxScrollX + overScrollAmount;
            mScrollX = mMaxScrollX;
        }
        invalidate();
    }

    protected void overScroll(float amount) {
        dampedOverScroll(amount);
    }

    protected float maxOverScroll() {
        // Using the formula in overScroll, assuming that f = 1.0 (which it should generally not
        // exceed). Used to find out how much extra wallpaper we need for the over scroll effect
        float f = 1.0f;
        f = f / (Math.abs(f)) * (overScrollInfluenceCurve(Math.abs(f)));
        return OVERSCROLL_DAMP_FACTOR * f;
    }
    
    private double mDistancePre;
    protected boolean mPreviewsState;
    protected boolean mPreviewsSwitching;
	private static final double DISTANCE_DELTA = 20;
	
	private double distance(float x1, float x2, float y1, float y2) {
    	double xDouble=Math.pow(x1 - x2, 2);
		double yDouble=Math.pow(y1 - y2, 2);
		return Math.sqrt(xDouble + yDouble);
	}
	
	/**
	 * Subclass override this method to implement previews display.  
	 * added by leeyb
	 * @param start true is start previews play, otherwise exit previews mode
	 */
	protected void showPreviews(boolean start) {
		mPreviewsState = start;
	}

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // Skip touch handling if there are no pages to swipe
    	Log.v("TouchDetector", "onTouchEvent PagedView event:" + ev.getAction() + "\tev.getPointerCount():" + ev.getPointerCount());
        if (getChildCount() <= 0) {
            if (LauncherLog.DEBUG) {
                LauncherLog.d(TAG, "(PagedView)onTouchEvent getChildCount() = " + getChildCount());
            }
        	return super.onTouchEvent(ev);
        }
        
//        Log.v("leeyb", "mTouchState:" + mTouchState + "\tev.getAction():" + ev.getAction());
        if (mTouchState == TOUCH_STATE_REST && ev.getPointerCount() == 2 && !isPreviewsState() && !mDotDragging) {
    		switch (ev.getAction()) {
    		case MotionEvent.ACTION_POINTER_2_DOWN:
    		case MotionEvent.ACTION_POINTER_1_DOWN:
    			mDistancePre = 0;
    		case MotionEvent.ACTION_MOVE:
    			double distanceAft;
    			double distanceDelta;
    			if (mDistancePre == 0) {
    				mDistancePre = distance(ev.getX(0), ev.getX(1), ev.getY(0), ev.getY(1));
    			}
    			distanceAft = distance(ev.getX(0), ev.getX(1), ev.getY(0), ev.getY(1));
    			distanceDelta = distanceAft - mDistancePre;
    			if (Math.abs(distanceDelta) >= DISTANCE_DELTA ) {
    				mDistancePre = 0;
    				showPreviews(true);
    			}
    			break;
    		}	
    		return true;
		} else if (mPreviewsState) {
			return onPreviewTouchEvent(ev);
		} else if (mPreviewsSwitching || mDotDragging) {
			return true;
		}
        
        acquireVelocityTrackerAndAddMovement(ev);

        final int action = ev.getAction();
//        Log.v("leeyb", "actual action :" + (action & MotionEvent.ACTION_MASK));
        switch (action & MotionEvent.ACTION_MASK) {
        case MotionEvent.ACTION_DOWN:
            /*
             * If being flinged and user touches, stop the fling. isFinished
             * will be false if being flinged.
             */
            if (!mScroller.isFinished()) {
                mScroller.abortAnimation();
            }

            // Remember where the motion event started
            mDownMotionX = mLastMotionX = ev.getX();
            mLastMotionXRemainder = 0;
            mTotalMotionX = 0;
            mActivePointerId = ev.getPointerId(0);
            if (LauncherLog.DEBUG_MOTION) {
                LauncherLog.d(TAG, "Touch down: mDownMotionX = " + mDownMotionX
                        + ",mTouchState = " + mTouchState + ",mCurrentPage = " + mCurrentPage
                        + ",mScrollX = " + mScrollX + ",this = " + this);
            }
            if (mTouchState == TOUCH_STATE_SCROLLING) {
                pageBeginMoving();
            }
            break;

        case MotionEvent.ACTION_MOVE:
        	if (mTouchState == TOUCH_STATE_SCROLLING) {
        		if (canSendMessage /*&& ! mDragController.isDraging()*/ ) {
                    View currentHostView = getPageAt(mCurrentPage);
                    View currentMtkWidgetView = searchIMTKWidget(currentHostView);
                    
                    if (currentMtkWidgetView != null) {
                        boolean result = ((IMTKWidget) currentMtkWidgetView).moveOut(mCurrentPage);
                        if (result == false) {
                            if (LauncherLog.DEBUG_SURFACEWIDGET) {
                                LauncherLog.d(Launcher.TAG_SURFACEWIDGET, "moveOut false: currentMtkWidgetView = "
                                            + currentMtkWidgetView);
                            }
                            return true;
                        }
                        canSendMessage = false;
                        if (LauncherLog.DEBUG_SURFACEWIDGET) {
                            LauncherLog.d(Launcher.TAG_SURFACEWIDGET,
                                    "moveOut true: currentMtkWidgetView = " + currentMtkWidgetView);
                        }
                    }
                }
        		
                // Scroll to follow the motion event
                final int pointerIndex = ev.findPointerIndex(mActivePointerId);
                final float x = ev.getX(pointerIndex);
                final float deltaX = mLastMotionX + mLastMotionXRemainder - x;

                mTotalMotionX += Math.abs(deltaX);

                // Only scroll and update mLastMotionX if we have moved some discrete amount.  We
                // keep the remainder because we are actually testing if we've moved from the last
                // scrolled position (which is discrete).
                if (Math.abs(deltaX) >= 1.0f) {
                    mTouchX += deltaX;
                    mSmoothingTime = System.nanoTime() / NANOTIME_DIV;
                    if (!mDeferScrollUpdate) {
                        scrollBy((int) deltaX, 0);
                        if (DEBUG) Log.d(TAG, "onTouchEvent().Scrolling: " + deltaX);
                    } else {
                        invalidate();
                    }
                    mLastMotionX = x;
                    mLastMotionXRemainder = deltaX - (int) deltaX;
                } else {
                    awakenScrollBars();
                }

                if (LauncherLog.DEBUG_MOTION) {
                    LauncherLog.d(TAG, "Touch move scroll: x = " + x + ",deltaX = " + deltaX
                            + ",mTotalMotionX = " + mTotalMotionX + ",mLastMotionX = "
                            + mLastMotionX + ",mCurrentPage = " + mCurrentPage + ",mTouchX = "
                            + mTouchX + ",mLastMotionX = " + mLastMotionX + ",mScrollX = " + mScrollX);
                }
            } else {
                determineScrollingStart(ev);
            }
            break;

        case MotionEvent.ACTION_UP:
            if (mTouchState == TOUCH_STATE_SCROLLING) {
                final int activePointerId = mActivePointerId;
                final int pointerIndex = ev.findPointerIndex(activePointerId);
//                Log.v("leeyb", "pointerIndex:" + pointerIndex);
                //FIXME The activePointerId is not always correctly, in the case pointerIndex == -1 we call getX by args 0, is there a better way?
                int active = pointerIndex != -1 ? pointerIndex : 0;
                final float x = ev.getX(active);
                final VelocityTracker velocityTracker = mVelocityTracker;
                velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                int velocityX = (int) velocityTracker.getXVelocity(activePointerId);
                final int deltaX = (int) (x - mDownMotionX);
                final int pageWidth = getScaledMeasuredWidth(
                		mIamAppPageView ? ((AppsCustomizePagedView)this).getPageAtByCurrent(mCurrentPage)
                		: getPageAt(mCurrentPage));
                boolean isSignificantMove = Math.abs(deltaX) > pageWidth *
                        SIGNIFICANT_MOVE_THRESHOLD;
                final int snapVelocity = mSnapVelocity;

                mTotalMotionX += Math.abs(mLastMotionX + mLastMotionXRemainder - x);

                boolean isFling = mTotalMotionX > MIN_LENGTH_FOR_FLING &&
                        Math.abs(velocityX) > snapVelocity;

                if (LauncherLog.DEBUG) {
                    LauncherLog.d(TAG, "Touch up scroll: x = " + x + ",deltaX = " + deltaX
                            + ",mTotalMotionX = " + mTotalMotionX + ",mLastMotionX = "
                            + mLastMotionX + ",velocityX = " + velocityX + ",mCurrentPage = "
                            + mCurrentPage + ",pageWidth = " + pageWidth + ",isFling = "
                            + isFling + ",isSignificantMove = " + isSignificantMove
                            + ",mScrollX = " + mScrollX);
                }
                // In the case that the page is moved far to one direction and then is flung
                // in the opposite direction, we use a threshold to determine whether we should
                // just return to the starting page, or if we should skip one further.
                boolean returnToOriginalPage = false;
                if (Math.abs(deltaX) > pageWidth * RETURN_TO_ORIGINAL_PAGE_THRESHOLD &&
                        Math.signum(velocityX) != Math.signum(deltaX) && isFling) {
                    if (LauncherLog.DEBUG) {
                        LauncherLog.d(TAG, "#### Return to origin page: deltaX = " + deltaX
                                + ",velocityX = " + velocityX + ",isFling = " + isFling);
                    }
                    returnToOriginalPage = true;
                }

                int finalPage = 0;
                // We give flings precedence over large moves, which is why we short-circuit our
                // test for a large move if a fling has been registered. That is, a large
                // move to the left and fling to the right will register as a fling to the right.
                if (/*(*/(isSignificantMove && deltaX > 0 && !isFling) ||
                        (isFling && velocityX > 0)/*) && (mCurrentPage > 0 || mIamAppPageView)*/) {
                    finalPage = returnToOriginalPage ? mCurrentPage : mCurrentPage - 1;
                    if (LauncherLog.DEBUG) {
                        LauncherLog.d(TAG, "1 finalPage = " + finalPage + ",mCurrentPage = "
                                + mCurrentPage + ",velocityX = " + velocityX);
                    }
                    snapToPageWithVelocity(finalPage, velocityX);
                } else if (/*(*/(isSignificantMove && deltaX < 0 && !isFling) ||
                        (isFling && velocityX < 0)/*) &&
                        (mCurrentPage < getChildCount() - 1 || mIamAppPageView)*/) {
                    finalPage = returnToOriginalPage ? mCurrentPage : mCurrentPage + 1;
                    if (LauncherLog.DEBUG) {
                        LauncherLog.d(TAG, "2 finalPage = " + finalPage + ",mCurrentPage = "
                                + mCurrentPage + ",velocityX = " + velocityX);
                    }
                    snapToPageWithVelocity(finalPage, velocityX);
                } else {
                    if (LauncherLog.DEBUG) {
                        LauncherLog.d(TAG, "3 mCurrentPage = " + mCurrentPage + ",mScrollX = " + mScrollX);
                    }
                    snapToDestination();
                }
            } else if (mTouchState == TOUCH_STATE_PREV_PAGE) {
                // at this point we have not moved beyond the touch slop
                // (otherwise mTouchState would be TOUCH_STATE_SCROLLING), so
                // we can just page
                int nextPage = Math.max(0, mCurrentPage - 1);
                if (LauncherLog.DEBUG) {
                    LauncherLog.d(TAG, "TOUCH_STATE_PREV_PAGE: mCurrentPage = " + mCurrentPage
                            + ",nextPage = " + nextPage + ",this = " + this);
                }
                if (nextPage != mCurrentPage) {
                    snapToPage(nextPage);
                } else {
                    snapToDestination();
                }
            } else if (mTouchState == TOUCH_STATE_NEXT_PAGE) {
                // at this point we have not moved beyond the touch slop
                // (otherwise mTouchState would be TOUCH_STATE_SCROLLING), so
                // we can just page
                int nextPage = mIamAppPageView ? mCurrentPage + 1 : Math.min(getChildCount() - 1, mCurrentPage + 1);
                if (LauncherLog.DEBUG) {
                    LauncherLog.d(TAG, "TOUCH_STATE_NEXT_PAGE: mCurrentPage = " + mCurrentPage
                            + ",nextPage = " + nextPage + ",this = " + this);
                }
                if (nextPage != mCurrentPage) {
                    snapToPage(nextPage);
                } else {
                    snapToDestination();
                }
            } else {
                if (LauncherLog.DEBUG) {
                    LauncherLog.d(TAG, "[--Case Watcher--]Touch up unhandled: mCurrentPage = "
                            + mCurrentPage + ",mTouchState = " + mTouchState + ",mScrollX = "
                            + mScrollX + ",this = " + this);
                }
                /*
                 * Handle special wrong case, the child stop in the middle,
                 * we need to snap it to destination, but we have no
                 * efficient way to detect this case, so do the snap process
                 * all the way, this has no side effect because the distance
                 * will be 0 if it is a normal case.
                 */
                snapToDestination();
                onUnhandledTap(ev);
            }
//            Log.v("leeyb", "mTouchState = TOUCH_STATE_REST here 1555");
            mTouchState = TOUCH_STATE_REST;
            mActivePointerId = INVALID_POINTER;
            releaseVelocityTracker();
            break;

        case MotionEvent.ACTION_CANCEL:
            if (LauncherLog.DEBUG) {
                LauncherLog.d(TAG, "Touch cancel: mCurrentPage = " + mCurrentPage
                        + ",mTouchState = " + mTouchState + ",mScrollX = " + mScrollX
                        + ",this = " + this);
            }
            if (mTouchState == TOUCH_STATE_SCROLLING) {
                snapToDestination();
            }
//            Log.v("leeyb", "mTouchState = TOUCH_STATE_REST here 1570");
            mTouchState = TOUCH_STATE_REST;
            mActivePointerId = INVALID_POINTER;
            releaseVelocityTracker();
            break;

        case MotionEvent.ACTION_POINTER_UP:
            if (LauncherLog.DEBUG) {
                LauncherLog.d(TAG, "Touch ACTION_POINTER_UP: mCurrentPage = " + mCurrentPage
                        + ",mTouchState = " + mTouchState + ",mActivePointerId = "
                        + mActivePointerId + ",this = " + this);
            }
            onSecondaryPointerUp(ev);
            break;
        }

        return true;
    }

    protected int mPreviewClickTargetPage;
    protected final Rect mTempRect = new Rect();
    /**
     * Handle touch event in previews display state.  added by leeyb
     * @param ev
     * @return true if not decide a target page, otherwise handle it
     */
    protected boolean onPreviewTouchEvent(MotionEvent ev) {
		return true;
	}

	@Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_SCROLL: {
                    // Handle mouse (or ext. device) by shifting the page depending on the scroll
                    final float vscroll;
                    final float hscroll;
                    if ((event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) {
                        vscroll = 0;
                        hscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    } else {
                        vscroll = -event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                        hscroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                    }
                    if (hscroll != 0 || vscroll != 0) {
                        if (hscroll > 0 || vscroll > 0) {
                            scrollRight();
                        } else {
                            scrollLeft();
                        }
                        return true;
                    }
                }
            }
        }
        return super.onGenericMotionEvent(event);
    }

    protected void acquireVelocityTrackerAndAddMovement(MotionEvent ev) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);
    }

    protected void releaseVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = (ev.getAction() & MotionEvent.ACTION_POINTER_INDEX_MASK) >>
                MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        final int pointerId = ev.getPointerId(pointerIndex);
        if (pointerId == mActivePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            // TODO: Make this decision more intelligent.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mLastMotionX = mDownMotionX = ev.getX(newPointerIndex);
            mLastMotionY = ev.getY(newPointerIndex);
            mLastMotionXRemainder = 0;
            mActivePointerId = ev.getPointerId(newPointerIndex);
            if (mVelocityTracker != null) {
                mVelocityTracker.clear();
            }
        }
    }

    protected void onUnhandledTap(MotionEvent ev) {}

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        int page = indexToPage(indexOfPage(child));
        if (page >= 0 && page != getCurrentPage() && !isInTouchMode()) {
            snapToPage(page);
        }
    }

    protected int getChildIndexForRelativeOffset(int relativeOffset) {
        final int childCount = getPageCount();
        int left;
        int right;
        for (int i = 0; i < childCount; ++i) {
            left = getRelativeChildOffset(i);
            right = (left + getScaledMeasuredWidth(getPageAt(i)));
            if (left <= relativeOffset && relativeOffset <= right) {
                if (LauncherLog.DEBUG) {
                    LauncherLog.d(TAG, "(PagedView)getChildIndexForRelativeOffset i = " + i);
                }
                return i;
            }
        }
        return -1;
    }

    protected int getChildWidth(int index) {
        // This functions are called enough times that it actually makes a difference in the
        // profiler -- so just inline the max() here
    	int measuredWidth = 0;
    	try {
    		measuredWidth = getPageAt(index).getMeasuredWidth();
		} catch (NullPointerException e) {
			// TODO: handle exception
			System.out.println("Launcher Nullpointer in PagedView -> line 1691");
			e.printStackTrace();
			measuredWidth = getMeasuredWidth();
		}
        if (LauncherLog.DEBUG_LAYOUT) {
            LauncherLog.d(TAG, "getChildWidth: index = " + index + ",child = " + getPageAt(index)
                    + ",measured width = " + measuredWidth + ",mMinimumWidth = " + mMinimumWidth);
        }        
        final int minWidth = mMinimumWidth;
        return (minWidth > measuredWidth) ? minWidth : measuredWidth;
    }

    int getPageNearestToCenterOfScreen() {
        int minDistanceFromScreenCenter = Integer.MAX_VALUE;
        int minDistanceFromScreenCenterIndex = -1;
        int screenCenter = mScrollX + (getMeasuredWidth() / 2);
        final int childCount = getPageCount();
        for (int i = 0; i < childCount; ++i) {
            View layout = (View) getPageAt(i);
            int childWidth = getScaledMeasuredWidth(layout);
            int halfChildWidth = (childWidth / 2);
            int childCenter = getChildOffset(i) + halfChildWidth;
            int distanceFromScreenCenter = Math.abs(childCenter - screenCenter);
            if (distanceFromScreenCenter < minDistanceFromScreenCenter) {
                minDistanceFromScreenCenter = distanceFromScreenCenter;
                minDistanceFromScreenCenterIndex = i;
            }
        }
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "getPageNearestToCenterOfScreen: minDistanceFromScreenCenterIndex = "
                    + minDistanceFromScreenCenterIndex + ",mScrollX = " + mScrollX);
        }
        return minDistanceFromScreenCenterIndex;
    }

    protected void snapToDestination() {
        snapToPage(getPageNearestToCenterOfScreen(), PAGE_SNAP_ANIMATION_DURATION);
    }

    public static class ScrollInterpolator implements Interpolator {
        public ScrollInterpolator() {
        }

        public float getInterpolation(float t) {
            t -= 1.0f;
            return t*t*t*t*t + 1;
        }
    }

    // We want the duration of the page snap animation to be influenced by the distance that
    // the screen has to travel, however, we don't want this duration to be effected in a
    // purely linear fashion. Instead, we use this method to moderate the effect that the distance
    // of travel has on the overall snap duration.
    float distanceInfluenceForSnapDuration(float f) {
        f -= 0.5f; // center the values about 0.
        f *= 0.1f * Math.PI / 2.0f;
        return (float) Math.sin(f);
    }

    protected void snapToPageWithVelocity(int whichPage, int velocity) {
//        whichPage = Math.max(0, Math.min(whichPage, getChildCount() - 1));
        int halfScreenSize = getMeasuredWidth() / 2;
        int newX;
        if(whichPage >= 0 /*&& whichPage <= getChildCount() - 1*/){
        	newX = getMeasuredWidth() * whichPage/*getChildOffset(whichPage) - getRelativeChildOffset(whichPage)*/;
        } else if (whichPage < 0){
        	newX = - getMeasuredWidth();
        } else {
        	newX = getMeasuredWidth() * getChildCount();
        }
        int delta = newX - mUnboundedScrollX;
        int duration = 0;
        if (/*LauncherLog.DEBUG*/false) {
            LauncherLog.d(TAG, "snapToPage.getChildOffset(): " + getChildOffset(whichPage)
                    + ",measured width = " + +getMeasuredWidth() + ", " + getChildWidth(whichPage)
                    + ",newX = " + newX + ",mUnboundedScrollX = " + mUnboundedScrollX
                    + ",halfScreenSize = " + halfScreenSize);
        }

        if (Math.abs(velocity) < MIN_FLING_VELOCITY) {
            // If the velocity is low enough, then treat this more as an automatic page advance
            // as opposed to an apparent physical response to flinging
            LauncherLog.i(TAG, "snapToPageWithVelocity: velocity = " + velocity + ",whichPage = "
                    + whichPage + ",MIN_FLING_VELOCITY = " + MIN_FLING_VELOCITY + ",this = " + this);
            snapToPage(whichPage, PAGE_SNAP_ANIMATION_DURATION);
            return;
        }

        // Here we compute a "distance" that will be used in the computation of the overall
        // snap duration. This is a function of the actual distance that needs to be traveled;
        // we keep this value close to half screen size in order to reduce the variance in snap
        // duration as a function of the distance the page needs to travel.
        float distanceRatio = Math.min(1f, 1.0f * Math.abs(delta) / (2 * halfScreenSize));
        float distance = halfScreenSize + halfScreenSize *
                distanceInfluenceForSnapDuration(distanceRatio);

        velocity = Math.abs(velocity);
        velocity = Math.max(MINIMUM_SNAP_VELOCITY, velocity);

        // we want the page's snap velocity to approximately match the velocity at which the
        // user flings, so we scale the duration by a value near to the derivative of the scroll
        // interpolator at zero, ie. 5. We use 4 to make it a little slower.
        duration = (int)(4.5f * Math.round(1000 * Math.abs(distance / velocity)));

        if (LauncherLog.DEBUG_MOTION) {
            LauncherLog.d(TAG, "snapToPageWithVelocity: velocity = " + velocity + ",whichPage = "
                    + whichPage + ",duration = " + duration + ",delta = " + delta + ",mScrollX = "
                    + mScrollX + ",mUnboundedScrollX = " + mUnboundedScrollX + ",this = " + this);
        }
        snapToPage(whichPage, delta, duration);
    }

    protected void snapToPage(int whichPage) {
        snapToPage(whichPage, PAGE_SNAP_ANIMATION_DURATION);
    }

    protected void snapToPage(int whichPage, int duration) {
//        whichPage = Math.max(0, Math.min(whichPage, getPageCount() - 1));
        if (DEBUG) Log.d(TAG, "snapToPage.getChildOffset(): " + getChildOffset(whichPage));
        if (DEBUG) Log.d(TAG, "snapToPage.getRelativeChildOffset(): " + getMeasuredWidth() + ", "
                + getChildWidth(whichPage));
        int newX;
        if(whichPage >= 0/* && whichPage <= getChildCount() - 1*/){//modified by leeyb
        	newX = getMeasuredWidth() * whichPage/*getChildOffset(whichPage) - getRelativeChildOffset(whichPage)*/;
        } else if (whichPage < 0){
        	newX = - getMeasuredWidth();
        } else {
        	newX = getMeasuredWidth() * getChildCount();
        }
        int delta = newX - mUnboundedScrollX;
        snapToPage(whichPage, delta, duration);
    }

    protected void snapToPage(int whichPage, int delta, int duration) {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "(PagedView)snapToPage whichPage = " + whichPage + ", delta = "
                    + delta + ", duration = " + duration + ",mNextPage = " + mNextPage
                    + ",mUnboundedScrollX = " + mUnboundedScrollX + ",mDeferScrollUpdate = "
                    + mDeferScrollUpdate + ",mScrollX = " + mScrollX + ",this = " + this);
        }        
        mNextPage = whichPage;
        View focusedChild = getFocusedChild();
        if (focusedChild != null && whichPage != mCurrentPage &&
                focusedChild == getPageAt(mCurrentPage)) {
            focusedChild.clearFocus();
        }

        pageBeginMoving();
        awakenScrollBars(duration);
        if (duration == 0) {
            duration = Math.abs(delta);
        }
        
        controlMTKWidget(whichPage);

        final int screenDelta = Math.max(1, Math.abs(whichPage - mCurrentPage));
        
        if (!mScroller.isFinished()) mScroller.abortAnimation();
        mScroller.startScroll(mUnboundedScrollX, 0, delta, 0, duration);

        // Load associated pages immediately if someone else is handling the scroll, otherwise defer
        // loading associated pages until the scroll settles
        if (mDeferScrollUpdate) {
            loadAssociatedPages(mNextPage);
        } else {
            mDeferLoadAssociatedPagesUntilScrollCompletes = true;
        }
        notifyPageSwitchListener();
        invalidate();
    }

    public void scrollLeft() {
        if (mScroller.isFinished()) {
            if (mCurrentPage > 0) snapToPage(mCurrentPage - 1);
        } else {
            if (mNextPage > 0) snapToPage(mNextPage - 1);
        }
    }

    public void scrollRight() {
        if (mScroller.isFinished()) {
            if (mCurrentPage < getChildCount() -1) snapToPage(mCurrentPage + 1);
        } else {
            if (mNextPage < getChildCount() -1) snapToPage(mNextPage + 1);
        }
    }

    public int getPageForView(View v) {
        int result = -1;
        if (v != null) {
            ViewParent vp = v.getParent();
            int count = getPageCount();
            for (int i = 0; i < count; i++) {
                if (vp == getPageAt(i)) {
                    return i;
                }
            }
        }
        return result;
    }

    /**
     * @return True is long presses are still allowed for the current touch
     */
    public boolean allowLongPress() {
        return mAllowLongPress;
    }

    /**
     * Set true to allow long-press events to be triggered, usually checked by
     * {@link Launcher} to accept or block dpad-initiated long-presses.
     */
    public void setAllowLongPress(boolean allowLongPress) {
        mAllowLongPress = allowLongPress;
    }

    public static class SavedState extends BaseSavedState {
        int currentPage = -1;

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            currentPage = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(currentPage);
        }

        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    protected void loadAssociatedPages(int page) {
        loadAssociatedPages(page, false);
    }

    protected void loadAssociatedPages(int page, boolean immediateAndOnly) {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "loadAssociatedPages: page = " + page
                    + ", immediateAndOnly = " + immediateAndOnly + ",mContentIsRefreshable = "
                    + mContentIsRefreshable + ",mDirtyPageContent = " + mDirtyPageContent);
        }
        if (mContentIsRefreshable) {
            final int count = getPageCount();
            if (page < count + 2) {
//                int lowerPageBound = getAssociatedLowerPageBound(page);
//                int upperPageBound = getAssociatedUpperPageBound(page);
//                if (LauncherLog.DEBUG) {
//                    LauncherLog.d(TAG, "loadAssociatedPages: " + lowerPageBound + "/"
//                            + upperPageBound + ",page = " + page + ",count = " + count);      
//                }
                for (int i = 0; i < count; ++i) {
//                    if ((i != page) && immediateAndOnly) {
//                        continue;
//                    }
                    Page layout = (Page) getPageAt(i);
//                    final int childCount = layout.getPageChildCount();
//                    if (lowerPageBound <= i && i <= upperPageBound) {
                        if (mDirtyPageContent.get(i)) {
                            syncPageItems(i, (i == page) && immediateAndOnly);
                            mDirtyPageContent.set(i, false);
                        }
//                    } else {
//                        if (childCount > 0) {
//                            layout.removeAllViewsOnPage();
//                        }
//                        mDirtyPageContent.set(i, true);
//                    }
                }
            }
        }
    }

    protected int getAssociatedLowerPageBound(int page) {
        return Math.max(0, page - 1);
    }
    protected int getAssociatedUpperPageBound(int page) {
        final int count = getChildCount();
        return Math.min(page + 1, count - 1);
    }

    protected void startChoiceMode(int mode, ActionMode.Callback callback) {
        if (isChoiceMode(CHOICE_MODE_NONE)) {
            mChoiceMode = mode;
            mActionMode = startActionMode(callback);
        }
    }

    public void endChoiceMode() {
        if (!isChoiceMode(CHOICE_MODE_NONE)) {
            mChoiceMode = CHOICE_MODE_NONE;
            resetCheckedGrandchildren();
            if (mActionMode != null) mActionMode.finish();
            mActionMode = null;
        }
    }

    protected boolean isChoiceMode(int mode) {
        return mChoiceMode == mode;
    }

    protected ArrayList<Checkable> getCheckedGrandchildren() {
        ArrayList<Checkable> checked = new ArrayList<Checkable>();
        final int childCount = getPageCount();
        for (int i = 0; i < childCount; ++i) {
            Page layout = (Page) getPageAt(i);
            final int grandChildCount = layout.getPageChildCount();
            for (int j = 0; j < grandChildCount; ++j) {
                final View v = layout.getChildOnPageAt(j);
                if (v instanceof Checkable && ((Checkable) v).isChecked()) {
                    checked.add((Checkable) v);
                }
            }
        }
        return checked;
    }

    /**
     * If in CHOICE_MODE_SINGLE and an item is checked, returns that item.
     * Otherwise, returns null.
     */
    protected Checkable getSingleCheckedGrandchild() {
        if (mChoiceMode != CHOICE_MODE_MULTIPLE) {
            final int childCount = getPageCount();
            for (int i = 0; i < childCount; ++i) {
                Page layout = (Page) getPageAt(i);
                final int grandChildCount = layout.getPageChildCount();
                for (int j = 0; j < grandChildCount; ++j) {
                    final View v = layout.getChildOnPageAt(j);
                    if (v instanceof Checkable && ((Checkable) v).isChecked()) {
                        return (Checkable) v;
                    }
                }
            }
        }
        return null;
    }

    protected void resetCheckedGrandchildren() {
        // loop through children, and set all of their children to _not_ be checked
        final ArrayList<Checkable> checked = getCheckedGrandchildren();
        for (int i = 0; i < checked.size(); ++i) {
            final Checkable c = checked.get(i);
            c.setChecked(false);
        }
    }

    /**
     * This method is called ONLY to synchronize the number of pages that the paged view has.
     * To actually fill the pages with information, implement syncPageItems() below.  It is
     * guaranteed that syncPageItems() will be called for a particular page before it is shown,
     * and therefore, individual page items do not need to be updated in this method.
     */
    public abstract void syncPages();

    /**
     * This method is called to synchronize the items that are on a particular page.  If views on
     * the page can be reused, then they should be updated within this method.
     */
    public abstract void syncPageItems(int page, boolean immediate);

    protected void invalidatePageData() {
        invalidatePageData(-1, false);
    }

    protected void invalidatePageData(int currentPage) {
        invalidatePageData(currentPage, false);
    }

    protected void invalidatePageData(int currentPage, boolean immediateAndOnly) {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "invalidatePageData: currentPage = " + currentPage 
                    + ",immediateAndOnly = " + immediateAndOnly + ",mIsDataReady = "
                    + mIsDataReady + ",mContentIsRefreshable = " + mContentIsRefreshable
                    + ",mScrollX = " + mScrollX + ",this = " + this);
        }
        
        if (!mIsDataReady) {
            return;
        }

        if (mContentIsRefreshable) {
            // Force all scrolling-related behavior to end
            mScroller.forceFinished(true);
            mNextPage = INVALID_PAGE;

            // Update all the pages
            for (int i = 0; i < mDirtyPageContent.size(); i++) {
				boolean target = mDirtyPageContent.get(i);
				if (target) {
					syncPages();
					break;
				}
			}

            // We must force a measure after we've loaded the pages to update the content width and
            // to determine the full scroll width
            measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));

            // Set a new page as the current page if necessary
            if (currentPage > -1) {
                setCurrentPage(/*Math.min(getPageCount() - 1, */currentPage/*)*/);
            }
            updateDivider(currentPage);
            // Mark each of the pages as dirty
            /*final int count = getChildCount();
            mDirtyPageContent.clear();
            for (int i = 0; i < count; ++i) {
                mDirtyPageContent.add(true);
            }*/

            // Load any pages that are necessary for the current window of views
            loadAssociatedPages(mCurrentPage, immediateAndOnly);
            
            /*
             * The scroller is force finished at the very begin, sometimes the
             * page will stop in the middle, we need to snap it to the right
             * destination to make pages position to the bounds.
             */
            if (LauncherLog.DEBUG) {
                LauncherLog.d(TAG, "[--Case Watcher--]invalidatePageData: currentPage = " + currentPage
                        + ",immediateAndOnly = " + immediateAndOnly + ",mScrollX = " + mScrollX);
            }
            snapToDestination();
            requestLayout();
        }
    }
    /**
     * update divider by prepared params, added by leeyb
     */
    protected void updateDivider(int which) {
        if (mDotDragging) {
            mLauncher.getDragLayer().updateIndicator(which + 1);
        }
	}
    
	private ViewGroup mGridIndicator;
    /*protected ImageView getScrollingIndicator() {
        // We use mHasScrollIndicator to prevent future lookups if there is no sibling indicator
        // found
//        if (mHasScrollIndicator && mScrollIndicator == null) {
//            ViewGroup parent = (ViewGroup) getParent();
//            mScrollIndicator = (ImageView) (parent.findViewById(R.id.paged_view_indicator));
            mDockDivider = (ViewGroup) parent.findViewById(R.id.dock_divider);
            mHasScrollIndicator = mScrollIndicator != null;
            if (mHasScrollIndicator) {
                mScrollIndicator.setVisibility(View.VISIBLE);
            }
        }
        return mScrollIndicator;
    }*/

    protected boolean isScrollingIndicatorEnabled() {
        return !LauncherApplication.isScreenLarge();
    }

    Runnable hideScrollingIndicatorRunnable = new Runnable() {
        @Override
        public void run() {
            hideScrollingIndicator(false);
        }
    };
    protected void flashScrollingIndicator(boolean animated) {
//        removeCallbacks(hideScrollingIndicatorRunnable);
//        showScrollingIndicator(!animated);
//        postDelayed(hideScrollingIndicatorRunnable, sScrollIndicatorFlashDuration);
    }

    protected void showScrollingIndicator(boolean immediately) {
        if (getChildCount() <= 1) return;
        if (!isScrollingIndicatorEnabled()) return;

//        getScrollingIndicator();
        if (mScrollIndicator != null) {
            // Fade the indicator in
            updateScrollingIndicatorPosition();
//            mScrollIndicator.setVisibility(View.VISIBLE);
            cancelScrollingIndicatorAnimations();
            if (immediately) {
                mScrollIndicator.setAlpha(1f);
            } else {
                mScrollIndicatorAnimator = ObjectAnimator.ofFloat(mScrollIndicator, "alpha", 1f);
                mScrollIndicatorAnimator.setDuration(sScrollIndicatorFadeInDuration);
                mScrollIndicatorAnimator.start();
            }
        }
    }

    protected void cancelScrollingIndicatorAnimations() {
        if (mScrollIndicatorAnimator != null) {
            mScrollIndicatorAnimator.cancel();
        }
    }

    protected void hideScrollingIndicator(boolean immediately) {
        if (getChildCount() <= 1) return;
        if (!isScrollingIndicatorEnabled()) return;

//        getScrollingIndicator();
        if (mScrollIndicator != null) {
            // Fade the indicator out
            updateScrollingIndicatorPosition();
            cancelScrollingIndicatorAnimations();
//            if (immediately) {
//                mScrollIndicator.setVisibility(View.INVISIBLE);
//                mScrollIndicator.setAlpha(0f);
//            } else {
//                mScrollIndicatorAnimator = ObjectAnimator.ofFloat(mScrollIndicator, "alpha", 0f);
//                mScrollIndicatorAnimator.setDuration(sScrollIndicatorFadeOutDuration);
//                mScrollIndicatorAnimator.addListener(new AnimatorListenerAdapter() {
//                    private boolean cancelled = false;
//                    @Override
//                    public void onAnimationCancel(android.animation.Animator animation) {
//                        cancelled = true;
//                    }
//                    @Override
//                    public void onAnimationEnd(Animator animation) {
//                        if (!cancelled) {
//                            mScrollIndicator.setVisibility(View.INVISIBLE);
//                        }
//                    }
//                });
//                mScrollIndicatorAnimator.start();
//            }
        }
    }

    /**
     * To be overridden by subclasses to determine whether the scroll indicator should stretch to
     * fill its space on the track or not.
     */
    protected boolean hasElasticScrollIndicator() {
        return true;
    }

    private void updateScrollingIndicator() {
        if (getChildCount() <= 1) return;
        if (!isScrollingIndicatorEnabled()) return;

//        getScrollingIndicator();
        if (mScrollIndicator != null) {
//            updateScrollingIndicatorPosition();
        }
    }

    private void updateScrollingIndicatorPosition() {
        if (!isScrollingIndicatorEnabled()) return;
        if (mScrollIndicator == null) return;
        int numPages = getChildCount();
        int pageWidth = getMeasuredWidth();
        int lastChildIndex = Math.max(0, getChildCount() - 1);
        int maxScrollX = getChildOffset(lastChildIndex) - getRelativeChildOffset(lastChildIndex);
        int indicatorWidth = mScrollIndicator.getMeasuredWidth() -
                mScrollIndicator.getPaddingLeft() - mScrollIndicator.getPaddingRight();
        int indicatorSpace = 0;
        int trackWidth = 0;
//        if(!(this instanceof AppsCustomizePagedView)){
	        trackWidth = mGridIndicator.getChildAt(mGridIndicator.getChildCount() - 1).getLeft() -
	        		mGridIndicator.getChildAt(0).getLeft() + 26;
	        if (hasElasticScrollIndicator()) {
	            if (mScrollIndicator.getMeasuredWidth() != mGridIndicator.getHeight()) {
	                mScrollIndicator.getLayoutParams().width = mGridIndicator.getHeight();
	                mScrollIndicator.getLayoutParams().height = mGridIndicator.getHeight();
	                mScrollIndicator.requestLayout();
	            }
	        }
	        try {
	        	float distance = getScrollX() % pageWidth;
	        	float scale = 0;
				scale = Math.abs(distance - pageWidth / 2) / (pageWidth / 2f) + 0.5f;
				mScrollIndicator.setScaleX(scale == 0 ? 1f : scale);
			} catch (RuntimeException e) {
			}
       /* } else {
        	trackWidth = pageWidth - mScrollIndicatorPaddingLeft - mScrollIndicatorPaddingRight;
        	indicatorSpace = trackWidth / numPages;
        	if (hasElasticScrollIndicator()) {
	            if (mScrollIndicator.getMeasuredWidth() != indicatorSpace) {
	                mScrollIndicator.getLayoutParams().width = indicatorSpace;
	                mScrollIndicator.getLayoutParams().height = 8;
	                mScrollIndicator.requestLayout();
	            }
	        }
        }*/
        float offset = Math.max(0f, Math.min(1f, (float) getScrollX() / maxScrollX));
        int indicatorPos = (int) (offset * (trackWidth - indicatorSpace)) + mScrollIndicatorPaddingLeft;
        mScrollIndicator.setTranslationX(indicatorPos);
        mScrollIndicator.invalidate();
    }

    public void showScrollIndicatorTrack() {
    }

    public void hideScrollIndicatorTrack() {
    }

    /* Accessibility */
    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setScrollable(true);
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setScrollable(true);
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            event.setFromIndex(mCurrentPage);
            event.setToIndex(mCurrentPage);
            event.setItemCount(getChildCount());
        }
    }

    protected String getCurrentPageDescription() {
        int page = (mNextPage != INVALID_PAGE) ? mNextPage : mCurrentPage;
        return String.format(mContext.getString(R.string.default_scroll_format),
                page + 1, getChildCount());
    }

    @Override
    public boolean onHoverEvent(android.view.MotionEvent event) {
        return true;
    }
    
    protected void controlMTKWidget(int whichScreen) {
		//if (canSendMessage && ! mDragController.isDraging() ) {
			View currentHostView = getPageAt(mCurrentPage);
			View currentMtkWidgetView = searchIMTKWidget(currentHostView);
			if (currentMtkWidgetView != null) {
				((IMTKWidget) currentMtkWidgetView).moveOut(mCurrentPage);
				canSendMessage = false;
				if (LauncherLog.DEBUG_SURFACEWIDGET) {
				    LauncherLog.d(Launcher.TAG_SURFACEWIDGET, "moveOut.");
				}
			}
		//}
    }
    
    public View searchIMTKWidget(View hostView, String providerName ) {
		if (hostView instanceof IMTKWidget) {
			return hostView;
		} else if (hostView instanceof ViewGroup) {
			int childCount = ((ViewGroup) hostView).getChildCount();
			for (int i = 0; i < childCount; i++) {
				View mtkWidgetView = searchIMTKWidget(((ViewGroup) hostView).getChildAt(i), providerName);
				if (mtkWidgetView != null) {
					View v = (View) mtkWidgetView.getParent();
					if ( v instanceof LauncherAppWidgetHostView) {
						LauncherAppWidgetHostView parent = (LauncherAppWidgetHostView)v;
						AppWidgetProviderInfo info = (AppWidgetProviderInfo)parent.getAppWidgetInfo();
						if (info.provider.getClassName().equals(providerName)) {
							return mtkWidgetView;
						}
					}						
				}
			}
		}
		return null;
    }
	
	public View searchIMTKWidget(View hostView) {
		if (hostView instanceof IMTKWidget) {
			return hostView;
		} else if (hostView instanceof ViewGroup) {
			int childCount = ((ViewGroup) hostView).getChildCount();
			for (int i = 0; i < childCount; i++) {
				View mtkWidgetView = searchIMTKWidget(((ViewGroup) hostView).getChildAt(i));
				if (mtkWidgetView != null)
					return mtkWidgetView;
			}
		}
		return null;
    }
	
	private static final int[][] sMatrixADW={{1},{2},{1,2},{2,2},{2,1,2},{2,2,2},{2,3,2},{3,2,3},{3,3,3},{3,4,3}};
	/**
	 * Generate a matrix array which contains position coordinates by specified style.  
	 * added by leeyb
	 * @param parent the visual ViewGroup that you want to play a previews display,
	 *  it determines a absolute size of out matrix
	 * @param style indicated style
	 * @param out the matrix container, must be format into 'new int[count][>=2]'
	 * @return scale size should be zoomed
	 */
	float previewMatrix(ViewGroup parent, Style style, int[][] out) {
		final int count = out.length;
		final int scrollX = mScrollX;
		final int scrollY = mScrollY;
		ViewGroup target = parent != null ? parent : this;
		final int height = target.getMeasuredHeight() - target.getPaddingTop() - target.getPaddingBottom();
		final int width = target.getMeasuredWidth() - target.getPaddingLeft() - target.getPaddingRight();
		int gap = getResources().getDimensionPixelSize(R.dimen.page_previews_gap);
		//FIXME: height and width calculation is not beautiful, fix me
		int cellWidth = (width - 4 * gap) / 3;
		int cellHeight = (height - 6 * gap) / 3;
		float scale = (float)cellHeight / (float)height;
		switch (style) {
		case SUMSUNG:
			for (int i = 0; i < count; i++) {
				int offsetX = i % 2 == 0 ? 0 : (cellWidth + gap);
				out[i][0] = scrollX + (width - 2 * cellWidth - gap) / 2 + offsetX;
				out[i][1] = scrollY + gap + (i / 2) * (cellHeight + gap);
			}
			return scale;
		case ADW:
			if (count > sMatrixADW.length) {
				throw new IllegalArgumentException("can not be ADW style when childCount > " + sMatrixADW.length);
			}
			int[] adw = sMatrixADW[count - 1];
            int maxHonNum = 0;
            for (int i = 0; i < adw.length; i++) {
                if (adw[i] >= maxHonNum) {
                    maxHonNum = adw[i];
                }
            }
            int maxVerNum = adw.length;
            cellWidth = (width - (maxHonNum + 1) * gap) / maxHonNum;
            cellHeight = (height - (maxVerNum + 3) * gap) / maxVerNum;
            int dirty = 5 - maxVerNum;
            scale = Math.min((float)cellHeight / (float)height, (float)cellWidth / (float)width);
            cellWidth = (int) (width * scale);
            cellHeight = (int) (height * scale);
			for (int i = 0; i < count; i++) {
				int countY = 0;
				int countX = 0;
				for (int j = 0; j < maxVerNum; j++) {
					countY += adw[j];
					if (i + 1 <= countY) {
						out[i][0] = scrollX + (width - (cellWidth * adw[j] + gap * (adw[j] - 1))) / 2
								 + (i - countX) * (cellWidth + gap);
						out[i][1] = scrollY + (height - (maxVerNum * cellHeight + (maxVerNum - 1) * gap)) / 2
								 + j * (cellHeight + gap * dirty);
						break;
					}
					countX += adw[j];
				}
			}
			return scale;
		case HORIZONTAL:
			for (int i = 0; i < count; i++) {
				out[i][0] = scrollX + gap + i * (cellWidth + gap);
				out[i][1] = scrollY + (height - cellHeight) / 2;
			}
			return scale;
		case VETICAL:
			for (int i = 0; i < count; i++) {
				out[i][0] = scrollX + (width - cellWidth) / 2;
				out[i][1] = scrollY + gap + i * (cellHeight + gap);
			}
			return scale;
		default:
			throw new IllegalArgumentException("incorrect indicated Style");
		}
	}
	public boolean isPreviewsState() {
		return mPreviewsState || mPreviewsSwitching;
	}
	public boolean haveBeenPreviews() {
	    return mPreviewsState;
	}
	
	protected boolean mDotDragging;
	@Override
    public void onDotClick(int page) {
    }

    @Override
    public void onDragging(float progress) {
        if (mScroller != null && !mScroller.isFinished()) {
            mScroller.abortAnimation();
        }
        progress = Math.max(0, Math.min(progress, 1f));
        scrollTo(getCurrentScrollX(progress), mScrollY);
    }

    @Override
    public final boolean onDragStart(float startOffset) {
        boolean accept = onDotDragStart(true);
        if (accept) {
            final float start = Math.max(0f, Math.min(startOffset, 1f));
            if (mScroller != null) {
                mLauncher.getDragLayer().updateIndicator(mScrollIndicator.getCurrentItem() + 1);
                if (!mScroller.isFinished()) {
                    mScroller.abortAnimation();
                }
                int distance = getCurrentScrollX(start) - mScrollX;
                mScroller.startScroll(mScrollX, mScrollY, distance, 0, 700);
                invalidate();
            } else {
                mDotDragging = accept = false;
                scrollTo(getCurrentScrollX(start), mScrollY);
            }
            mLauncher.getDragLayer().showOrHideIndicator(accept);
        }
        return accept;
    }

    protected int getCurrentScrollX(float progress) {
        return (int) (progress * mMaxScrollX);
    }

    protected boolean onDotDragStart(boolean enter) {
        if (mDotDragging == enter) {
            return false;
        }
        mDotDragging = enter;
        if (!enter) {
            snapToDestination();
        }
        return !(mPreviewsSwitching || getPageCount() <= 0);
    }

    @Override
    public final void onDragStop() {
        onDotDragStart(false);
        mLauncher.getDragLayer().showOrHideIndicator(false);
    }
    
    public final void registeSlideDividerforMyself(SlideDivider target) {
        mScrollIndicator = target;
        target.setOnDotDragListener(this);
        target.setOnDotClickListener(this);
    }
}
