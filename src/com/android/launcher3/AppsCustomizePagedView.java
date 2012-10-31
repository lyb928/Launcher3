/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.R.anim;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.MaskFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.TableMaskFilter;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Process;
import android.util.AndroidRuntimeException;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.Toast;

import com.android.launcher3.R;
import com.android.launcher3.DropTarget.DragObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A simple callback interface which also provides the results of the task.
 */
interface AsyncTaskCallback {
    void run(AppsCustomizeAsyncTask task, AsyncTaskPageData data);
}

/**
 * The data needed to perform either of the custom AsyncTasks.
 */
class AsyncTaskPageData {
    enum Type {
        LoadWidgetPreviewData,
        LoadHolographicIconsData
    }

    AsyncTaskPageData(int p, ArrayList<Object> l, ArrayList<Bitmap> si, AsyncTaskCallback bgR,
            AsyncTaskCallback postR) {
        page = p;
        items = l;
        sourceImages = si;
        generatedImages = new ArrayList<Bitmap>();
        maxImageWidth = maxImageHeight = -1;
        doInBackgroundCallback = bgR;
        postExecuteCallback = postR;
    }
    AsyncTaskPageData(int p, ArrayList<Object> l, int cw, int ch, AsyncTaskCallback bgR,
            AsyncTaskCallback postR) {
        page = p;
        items = l;
        generatedImages = new ArrayList<Bitmap>();
        maxImageWidth = cw;
        maxImageHeight = ch;
        doInBackgroundCallback = bgR;
        postExecuteCallback = postR;
    }
    void cleanup(boolean cancelled) {
        // Clean up any references to source/generated bitmaps
        if (sourceImages != null) {
            if (cancelled) {
                for (Bitmap b : sourceImages) {
                    b.recycle();
                }
            }
            sourceImages.clear();
        }
        if (generatedImages != null) {
            if (cancelled) {
                for (Bitmap b : generatedImages) {
                    b.recycle();
                }
            }
            generatedImages.clear();
        }
    }
    int page;
    ArrayList<Object> items;
    ArrayList<Bitmap> sourceImages;
    ArrayList<Bitmap> generatedImages;
    int maxImageWidth;
    int maxImageHeight;
    AsyncTaskCallback doInBackgroundCallback;
    AsyncTaskCallback postExecuteCallback;
}

/**
 * A generic template for an async task used in AppsCustomize.
 */
class AppsCustomizeAsyncTask extends AsyncTask<AsyncTaskPageData, Void, AsyncTaskPageData> {
    AppsCustomizeAsyncTask(int p, AsyncTaskPageData.Type ty) {
        page = p;
        threadPriority = Process.THREAD_PRIORITY_DEFAULT;
        dataType = ty;
    }
    @Override
    protected AsyncTaskPageData doInBackground(AsyncTaskPageData... params) {
        if (params.length != 1) return null;
        // Load each of the widget previews in the background
        params[0].doInBackgroundCallback.run(this, params[0]);
        return params[0];
    }
    @Override
    protected void onPostExecute(AsyncTaskPageData result) {
        // All the widget previews are loaded, so we can just callback to inflate the page
        result.postExecuteCallback.run(this, result);
    }

    void setThreadPriority(int p) {
        threadPriority = p;
    }
    void syncThreadPriority() {
        Process.setThreadPriority(threadPriority);
    }

    // The page that this async task is associated with
    AsyncTaskPageData.Type dataType;
    int page;
    int threadPriority;
}

/**
 * The Apps/Customize page that displays all the applications, widgets, and shortcuts.
 */
public class AppsCustomizePagedView extends PagedViewWithDraggableItems implements
        AllAppsView, View.OnClickListener, View.OnKeyListener, DragSource {
    static final String TAG = "Launcher.AppsCustomizePagedView";

    /**
     * The different content types that this paged view can show.
     */
    public enum ContentType {
        Applications,
        Widgets
    }

    // Refs
    private DragController mDragController;
    private final LayoutInflater mLayoutInflater;
    private final PackageManager mPackageManager;

    // Save and Restore
    private int mSaveInstanceStateItemIndex = -1;

    // Content
    private ArrayList<ApplicationInfo> mApps;
    private ArrayList<Object> mWidgets;

    // Cling
    private boolean mHasShownAllAppsCling;
//    private int mClingFocusedX;
//    private int mClingFocusedY;

    // Caching
    private Canvas mCanvas;
    private Drawable mDefaultWidgetBackground;
    private IconCache mIconCache;
    private int mDragViewMultiplyColor;

    // Dimens
    private int mContentWidth;
    private int mAppIconSize;
    private int mMaxAppCellCountX, mMaxAppCellCountY;
    private int mWidgetCountX, mWidgetCountY;
    private int mWidgetWidthGap, mWidgetHeightGap;
    private final int mWidgetPreviewIconPaddedDimension;
    private final float sWidgetPreviewIconPaddingPercentage = 0.25f;
    private PagedViewCellLayout mWidgetSpacingLayout;
    private int mNumAppsPages;
    private int mNumWidgetPages;

    // Relating to the scroll and overscroll effects
    Workspace.ZInterpolator mZInterpolator = new Workspace.ZInterpolator(0.5f);
    private static float CAMERA_DISTANCE = 5000;
    private static float TRANSITION_SCALE_FACTOR = 0.74f;
    private static float TRANSITION_PIVOT = 0.65f;
    private static float TRANSITION_MAX_ROTATION = 44;
    private static final boolean PERFORM_OVERSCROLL_ROTATION = true;
    private AccelerateInterpolator mAlphaInterpolator = new AccelerateInterpolator(0.9f);
    private DecelerateInterpolator mLeftScreenAlphaInterpolator = new DecelerateInterpolator(4);

    // Previews & outlines
    ArrayList<AppsCustomizeAsyncTask> mRunningTasks;
    private HolographicOutlineHelper mHolographicOutlineHelper;
    private static final int sPageSleepDelay = 200;
    
    private float[] originalTranslationX;
    private float[] targetTranslationX;
    private float[] originalTranslationY;
    private float[] targetTranslationY;
    private float mMaxTranslationY;
    private float mMaxScale;
    private float[] originalScaleX;
    private float[] originalScaleY;
    private float[] targetScale;
    private float[] originalPageBackAlpha;

    public AppsCustomizePagedView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setAlwaysDrawnWithCacheEnabled(true);
        mLayoutInflater = LayoutInflater.from(context);
        mPackageManager = context.getPackageManager();
        mApps = new ArrayList<ApplicationInfo>();
        mWidgets = new ArrayList<Object>();
        mIconCache = ((LauncherApplication) context.getApplicationContext()).getIconCache();
        mHolographicOutlineHelper = new HolographicOutlineHelper();
        mCanvas = new Canvas();
        mRunningTasks = new ArrayList<AppsCustomizeAsyncTask>();

        // Save the default widget preview background
        Resources resources = context.getResources();
        mDefaultWidgetBackground = resources.getDrawable(R.drawable.default_widget_preview_holo);
        mAppIconSize = resources.getDimensionPixelSize(R.dimen.app_icon_size);
        mDragViewMultiplyColor = resources.getColor(R.color.drag_view_multiply_color);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AppsCustomizePagedView, 0, 0);
        mMaxAppCellCountX = a.getInt(R.styleable.AppsCustomizePagedView_maxAppCellCountX, -1);
        mMaxAppCellCountY = a.getInt(R.styleable.AppsCustomizePagedView_maxAppCellCountY, -1);
        mWidgetWidthGap =
            a.getDimensionPixelSize(R.styleable.AppsCustomizePagedView_widgetCellWidthGap, 0);
        mWidgetHeightGap =
            a.getDimensionPixelSize(R.styleable.AppsCustomizePagedView_widgetCellHeightGap, 0);
        mWidgetCountX = a.getInt(R.styleable.AppsCustomizePagedView_widgetCountX, 2);
        mWidgetCountY = a.getInt(R.styleable.AppsCustomizePagedView_widgetCountY, 2);
//        mClingFocusedX = a.getInt(R.styleable.AppsCustomizePagedView_clingFocusedX, 0);
//        mClingFocusedY = a.getInt(R.styleable.AppsCustomizePagedView_clingFocusedY, 0);
        a.recycle();
        mWidgetSpacingLayout = new PagedViewCellLayout(getContext());

        // The padding on the non-matched dimension for the default widget preview icons
        // (top + bottom)
        mWidgetPreviewIconPaddedDimension =
            (int) (mAppIconSize * (1 + (2 * sWidgetPreviewIconPaddingPercentage)));
        mFadeInAdjacentScreens = false;
        setOnHierarchyChangeListener(new OnHierarchyChangeListener() {
			@Override
			public void onChildViewRemoved(View parent, View child) {
				originalTranslationX = new float[getPageCount()];
			    targetTranslationX = new float[getPageCount()];
			    originalTranslationY = new float[getPageCount()];
			    targetTranslationY = new float[getPageCount()];
			    originalScaleX = new float[getPageCount()];
			    originalScaleY = new float[getPageCount()];
			    targetScale = new float[getPageCount()];
			    originalPageBackAlpha = new float[getPageCount()];
			    mMaxTranslationY = mMaxScale = 0.0f;
//			    final int count = getChildCount();
//	            mDirtyPageContent.clear();
//	            for (int i = 0; i < count; ++i) {
//	                mDirtyPageContent.add(true);
//	            }
//			    mDirtyPageContent.remove(true);
			}
			
			@Override
			public void onChildViewAdded(View parent, View child) {
				originalTranslationX = new float[getPageCount()];
			    targetTranslationX = new float[getPageCount()];
			    originalTranslationY = new float[getPageCount()];
			    targetTranslationY = new float[getPageCount()];
			    originalScaleX = new float[getPageCount()];
			    originalScaleY = new float[getPageCount()];
			    targetScale = new float[getPageCount()];
			    originalPageBackAlpha = new float[getPageCount()];
			    mMaxTranslationY = mMaxScale = 0.0f;
//			    mDirtyPageContent.add(true);
//			    final int count = getChildCount();
//	            mDirtyPageContent.clear();
//	            for (int i = 0; i < count; ++i) {
//	                mDirtyPageContent.add(true);
//	            }
			}
		});
    }
    
    /**
     * Override for implement circulate slide, just draw 4 fake pages for visually circulation
     * fake true true fake fake true true ... true fake
     * <--       Apps      --> | <--          Widgets           -->
     * added by leeyb
     */
    @Override
	protected void onDrawBeforeDispatching(Canvas canvas, int center) {
		// FIXME sometimes the fake page sliding do not work, why ?
    	super.onDrawBeforeDispatching(canvas, center);
    	int appWidth = mNumAppsPages * getMeasuredWidth();
    	int widgetWidth = mNumWidgetPages * getMeasuredWidth();
    	View firstAppPage = getPageAt(0);
    	View lastAppPage = getPageAt(mNumAppsPages - 1);
    	View firstWidget = getPageAt(mNumAppsPages);
    	View lastWidget = getPageAt(getChildCount() - 1);
    	canvas.save();
    	canvas.translate(- appWidth, 0f);
    	if (lastAppPage != null) {
    		super.drawChild(canvas, lastAppPage, getDrawingTime());
		}
    	canvas.translate(2 * appWidth, 0f);
    	if (firstAppPage != null) {
    		super.drawChild(canvas, firstAppPage, getDrawingTime());
		}
    	canvas.restore();
    	canvas.save();
    	canvas.translate(- widgetWidth, 0f);
    	if (lastWidget != null) {
    		super.drawChild(canvas, lastWidget, getDrawingTime());
		}
    	canvas.translate(2 * widgetWidth, 0f);
    	if (firstWidget != null) {
    		super.drawChild(canvas, firstWidget, getDrawingTime());
		}
    	canvas.restore();
	}
    
    @Override
    protected void layoutChildren() {
    	final int verticalPadding = mPaddingTop + mPaddingBottom;
    	int childLeft = getRelativeChildOffset(0);
    	for (int i = 0; i < getChildCount(); i++) {
            final View child = getPageAt(i);
            if (child.getVisibility() != View.GONE) {
            	View next = getPageAt(i + 1);
            	int multiple = next == null || child.getClass() == next.getClass() ? 1 : 3;
            	//multiple --> this flag indicate quantity the gap should be
                final int childWidth = getScaledMeasuredWidth(child);
                final int childHeight = child.getMeasuredHeight();
                int childTop = mPaddingTop;
                if (mCenterPagesVertically) {
                    childTop += ((getMeasuredHeight() - verticalPadding) - childHeight) / 2;
                }
                child.layout(childLeft, childTop,
                        childLeft + child.getMeasuredWidth(), childTop + childHeight);
                childLeft += (childWidth + mPageSpacing * 2) * multiple;
            }
        }
	}
    
	@Override
	protected void handleCirculation() {
		final int firstAppPage = 0;
		final int lastAppPage = mNumAppsPages - 1;
		final int firstWidgetPage = mNumAppsPages + 2;
		final int lastWidgetPage = mNumAppsPages + 2 + mNumWidgetPages - 1;
		int destination = 0;
		if (mNextPage == firstAppPage - 1) {
			destination = getChildOffset(lastAppPage) - getRelativeChildOffset(lastAppPage);
    		scrollTo(destination, 0);
    		mNextPage = lastAppPage;
		} else if (mNextPage == lastAppPage + 1) {
			destination = getChildOffset(firstAppPage) - getRelativeChildOffset(firstAppPage);
    		scrollTo(destination, 0);
    		mNextPage = firstAppPage;
		} else if (mNextPage == firstWidgetPage - 1) {
			destination = getChildOffset(lastWidgetPage) - getRelativeChildOffset(lastWidgetPage);
    		scrollTo(destination, 0);
    		mNextPage = lastWidgetPage;
		} else if (mNextPage == lastWidgetPage + 1) {
			destination = getChildOffset(firstWidgetPage) - getRelativeChildOffset(firstWidgetPage);
    		scrollTo(destination, 0);
    		mNextPage = firstWidgetPage;
		}
	}
	
	@Override
    public void scrollBy(int x, int y) {
	    final int width = getMeasuredWidth();
        final boolean app = isAppState();
        final int firstAppPage = 0;
        final int lastAppPage = mNumAppsPages - 1;
        final int firstWidgetPage = mNumAppsPages + 2;
        final int lastWidgetPage = mNumAppsPages + 2 + mNumWidgetPages - 1;
        int start = app ? -width : (mNumAppsPages + 1) * width;
        int end = app ? mNumAppsPages * width : ((getPageCount() + 2) * width);
        if (mScrollX <= start) {
            mNextPage = app ? firstAppPage - 1 : firstWidgetPage - 1;
        } else if (mScrollX >= end) {
            mNextPage = app ? lastAppPage + 1 : lastWidgetPage + 1;
        }
        super.scrollBy(x, y);
    }

	@Override
	protected int getChildWidth(int index) {
		// TODO Auto-generated method stub
		return super.getChildWidth(index > mNumAppsPages ? index - 2 : index);
	}

	@Override
	protected int getPagePositionInArray(int index) {
		// TODO Auto-generated method stub
		int out = 0;
		if (index > mNumAppsPages) {
			out = index - 2;
		} else {
			out = index;
		}
		return out;
	}

	@Override
    protected void init() {
        super.init();
        mCenterPagesVertically = false;

        Context context = getContext();
        Resources r = context.getResources();
        setDragSlopeThreshold(r.getInteger(R.integer.config_appsCustomizeDragSlopeThreshold)/100f);
    }

    @Override
    protected void onUnhandledTap(MotionEvent ev) {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "onUnhandledTap: action = " + ev.getAction() + ",ev = " + ev);
        }
        if (LauncherApplication.isScreenLarge()) {
            // Dismiss AppsCustomize if we tap
            mLauncher.showWorkspace(true);
        }
    }

    /** Returns the item index of the center item on this page so that we can restore to this
     *  item index when we rotate. */
    private int getMiddleComponentIndexOnCurrentPage() {
        int i = -1;
        if (getPageCount() > 0) {
            int currentPage = getCurrentPage();
            if (currentPage < mNumAppsPages) {
                PagedViewCellLayout layout = (PagedViewCellLayout) getPageAtByCurrent(currentPage);
                PagedViewCellLayoutChildren childrenLayout = layout.getChildrenLayout();
                int numItemsPerPage = mCellCountX * mCellCountY;
                int childCount = childrenLayout.getChildCount();
                if (childCount > 0) {
                    i = (currentPage * numItemsPerPage) + (childCount / 2);
                }
            } else {
                int numApps = mApps.size();
                PagedViewGridLayout layout = (PagedViewGridLayout) getPageAtByCurrent(currentPage);
                int numItemsPerPage = mWidgetCountX * mWidgetCountY;
                int childCount = layout.getChildCount();
                if (childCount > 0) {
                    i = numApps +
                        ((currentPage - mNumAppsPages) * numItemsPerPage) + (childCount / 2);
                }
            }
        }
        return i;
    }

    /** Get the index of the item to restore to if we need to restore the current page. */
    int getSaveInstanceStateIndex() {
        if (mSaveInstanceStateItemIndex == -1) {
            mSaveInstanceStateItemIndex = getMiddleComponentIndexOnCurrentPage();
        }
        return mSaveInstanceStateItemIndex;
    }
    
    /**
     * Force set mSaveInstanceStateItemIndex to set the current page item,
     * called when user click the tab to change content.
     */
    void forceSetInstanceStateIndex() {
        mSaveInstanceStateItemIndex = getMiddleComponentIndexOnCurrentPage();
    }

    /** Returns the page in the current orientation which is expected to contain the specified
     *  item index. */
    int getPageForComponent(int index) {
        if (index < 0) return 0;

        if (index < mApps.size()) {
            int numItemsPerPage = mCellCountX * mCellCountY;
            return (index / numItemsPerPage);
        } else {
            int numItemsPerPage = mWidgetCountX * mWidgetCountY;
            return mNumAppsPages + ((index - mApps.size()) / numItemsPerPage);
        }
    }

    /**
     * This differs from isDataReady as this is the test done if isDataReady is not set.
     */
    private boolean testDataReady() {
        // We only do this test once, and we default to the Applications page, so we only really
        // have to wait for there to be apps.
        // TODO: What if one of them is validly empty
        return !mApps.isEmpty() && !mWidgets.isEmpty();
    }

    /** Restores the page for an item at the specified index */
    void restorePageForIndex(int index) {
        if (index < 0) return;
        mSaveInstanceStateItemIndex = index;
    }

    private void updatePageCounts() {
        mNumWidgetPages = (int) Math.ceil(mWidgets.size() /
                (float) (mWidgetCountX * mWidgetCountY));
        mNumAppsPages = (int) Math.ceil((float) mApps.size() / (mCellCountX * mCellCountY));
//        final int count = getChildCount();
        if (mNumWidgetPages + mNumAppsPages > 70 || (mNumWidgetPages + mNumAppsPages) <= 0) {
			return;
		}
        mDirtyPageContent.clear();
        for (int i = 0; i < mNumWidgetPages + mNumAppsPages; i++) {
            mDirtyPageContent.add(true);
        }
    }
    
    protected void onDataReady(int width, int height) {
        // Note that we transpose the counts in portrait so that we get a similar layout
        boolean isLandscape = getResources().getConfiguration().orientation ==
            Configuration.ORIENTATION_LANDSCAPE;
        int maxCellCountX = Integer.MAX_VALUE;
        int maxCellCountY = Integer.MAX_VALUE;
        if (LauncherApplication.isScreenLarge()) {
            maxCellCountX = (isLandscape ? LauncherModel.getCellCountX() :
                LauncherModel.getCellCountY());
            maxCellCountY = (isLandscape ? LauncherModel.getCellCountY() :
                LauncherModel.getCellCountX());
        }
        if (mMaxAppCellCountX > -1) {
            maxCellCountX = Math.min(maxCellCountX, mMaxAppCellCountX);
        }
        if (mMaxAppCellCountY > -1) {
            maxCellCountY = Math.min(maxCellCountY, mMaxAppCellCountY);
        }

        // Now that the data is ready, we can calculate the content width, the number of cells to
        // use for each page
        mWidgetSpacingLayout.setGap(mPageLayoutWidthGap, mPageLayoutHeightGap);
        mWidgetSpacingLayout.setPadding(mPageLayoutPaddingLeft, mPageLayoutPaddingTop,
                mPageLayoutPaddingRight, mPageLayoutPaddingBottom);
        mWidgetSpacingLayout.calculateCellCount(width, height, maxCellCountX, maxCellCountY);
        mCellCountX = mWidgetSpacingLayout.getCellCountX();
        mCellCountY = mWidgetSpacingLayout.getCellCountY();
        updatePageCounts();

        // Force a measure to update recalculate the gaps
        int widthSpec = MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.AT_MOST);
        int heightSpec = MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.AT_MOST);
        mWidgetSpacingLayout.measure(widthSpec, heightSpec);
        mContentWidth = mWidgetSpacingLayout.getContentWidth();

        AppsCustomizeTabHost host = (AppsCustomizeTabHost) getTabHost();
        final boolean hostIsTransitioning = host.isTransitioning();

        // Restore the page
        int page = getPageForComponent(mSaveInstanceStateItemIndex);
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "onDataReady: height = " + height + ",width = " + width
                    + ",isLandscape = " + isLandscape + ",page = " + page
                    + ",hostIsTransitioning = " + hostIsTransitioning + ",mContentWidth = "
                    + mContentWidth + ",this = " + this);
        }
        invalidatePageData(Math.max(0, page), hostIsTransitioning);
        // Show All Apps cling if we are finished transitioning, otherwise, we will try again when
        // the transition completes in AppsCustomizeTabHost (otherwise the wrong offsets will be
        // returned while animating)
        if (!hostIsTransitioning) {
            post(new Runnable() {
                @Override
                public void run() {
                    showAllAppsCling();
                }
            });
        }
    }
    
    /**
     * Override this method with a special logic, that is,
     *  get around 2 fade pages in the middle of ViewGroup
     */
    @Override
	protected void updateDivider(int which) {
        super.updateDivider(which);
    	if (mScrollIndicator != null) {
    		int visualPage = which;
    		if (which > mNumAppsPages) {
    			mScrollIndicator.setTotalPages(mNumWidgetPages);
    			visualPage -= (2 + mNumAppsPages);
    		} else {
    			mScrollIndicator.setTotalPages(mNumAppsPages);
    		}
    		mScrollIndicator.setCurrentDivider(visualPage);
		}
	}

	@Override
	int getPageNearestToCenterOfScreen() {
		int minDistanceFromScreenCenter = Integer.MAX_VALUE;
        int minDistanceFromScreenCenterIndex = -1;
        int screenCenter = mScrollX + (getMeasuredWidth() / 2);
        final int childCount = getPageCount();
        for (int i = 0; i < childCount; ++i) {
            View layout = (View) getPageAt(i);
            int childWidth = getScaledMeasuredWidth(layout);
            int halfChildWidth = (childWidth / 2);
            int visualPage = i >= mNumAppsPages ? i + 2 : i;
            int childCenter = getChildOffset(visualPage) + halfChildWidth;
            int distanceFromScreenCenter = Math.abs(childCenter - screenCenter);
            if (distanceFromScreenCenter < minDistanceFromScreenCenter) {
                minDistanceFromScreenCenter = distanceFromScreenCenter;
                minDistanceFromScreenCenterIndex = visualPage;
            }
        }
        return minDistanceFromScreenCenterIndex;
	}

	void showAllAppsCling() {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "showAllAppsCling: mHasShownAllAppsCling = " + mHasShownAllAppsCling);
        }
        if (!mHasShownAllAppsCling && isDataReady() && testDataReady()) {
            mHasShownAllAppsCling = true;
            /*// Calculate the position for the cling punch through
            int[] offset = new int[2];
            int[] pos = mWidgetSpacingLayout.estimateCellPosition(mClingFocusedX, mClingFocusedY);
            mLauncher.getDragLayer().getLocationInDragLayer(this, offset);
            // PagedViews are centered horizontally but top aligned
            pos[0] += (getMeasuredWidth() - mWidgetSpacingLayout.getMeasuredWidth()) / 2 +
                    offset[0];
            pos[1] += offset[1];*/
            mLauncher.showFirstRunAllAppsCling(null);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (!isDataReady()) {
            if (testDataReady()) {
                setDataIsReady();
                setMeasuredDimension(width, height);
                onDataReady(width, height);
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public void onPackagesUpdated() {
        // TODO: this isn't ideal, but we actually need to delay here. This call is triggered
        // by a broadcast receiver, and in order for it to work correctly, we need to know that
        // the AppWidgetService has already received and processed the same broadcast. Since there
        // is no guarantee about ordering of broadcast receipt, we just delay here. Ideally,
        // we should have a more precise way of ensuring the AppWidgetService is up to date.
        postDelayed(new Runnable() {
           public void run() {
               updatePackages();
           }
        }, 500);
    }

    public void updatePackages() {
        // Get the list of widgets and shortcuts
        boolean wasEmpty = mWidgets.isEmpty();
        mWidgets.clear();
        List<AppWidgetProviderInfo> widgets =
            AppWidgetManager.getInstance(mLauncher).getInstalledProviders();
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "updatePackages: wasEmpty = " + wasEmpty + ",widgets size = "
                    + widgets.size());
        }
        Intent shortcutsIntent = new Intent(Intent.ACTION_CREATE_SHORTCUT);
        List<ResolveInfo> shortcuts = mPackageManager.queryIntentActivities(shortcutsIntent, 0);
        for (AppWidgetProviderInfo widget : widgets) {
            if (widget.minWidth > 0 && widget.minHeight > 0) {
                mWidgets.add(widget);
            } else {
                Log.e(TAG, "Widget " + widget.provider + " has invalid dimensions (" +
                        widget.minWidth + ", " + widget.minHeight + ")");
            }
        }
        mWidgets.addAll(shortcuts);
        Collections.sort(mWidgets,
                new LauncherModel.WidgetAndShortcutNameComparator(mPackageManager));
        updatePageCounts();

        if (wasEmpty) {
            // The next layout pass will trigger data-ready if both widgets and apps are set, so request
            // a layout to do this test and invalidate the page data when ready.
            if (testDataReady()) requestLayout();
        } else {
            cancelAllTasks();
            invalidatePageData();
        }
    }

    @Override
    public void onClick(View v) {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "onClick: v = " + v + ", v.getTag() = " + v.getTag());
        }
        // When we have exited all apps or are in transition, disregard clicks
        if (!mLauncher.isAllAppsCustomizeOpen() ||
                mLauncher.getWorkspace().isSwitchingState()) return;

        if (v instanceof PagedViewIcon) {
            // Animate some feedback to the click
            final ApplicationInfo appInfo = (ApplicationInfo) v.getTag();
            animateClickFeedback(v, new Runnable() {
                @Override
                public void run() {
                    mLauncher.startActivitySafely(appInfo.intent, appInfo);
                }
            });
        } else if (v instanceof PagedViewWidget) {
            // Let the user know that they have to long press to add a widget
            Toast.makeText(getContext(), R.string.long_press_widget_to_add,
                    Toast.LENGTH_SHORT).show();

            // Create a little animation to show that the widget can move
            float offsetY = getResources().getDimensionPixelSize(R.dimen.dragViewOffsetY);
            final ImageView p = (ImageView) v.findViewById(R.id.widget_preview);
            AnimatorSet bounce = new AnimatorSet();
            ValueAnimator tyuAnim = ObjectAnimator.ofFloat(p, "translationY", offsetY);
            tyuAnim.setDuration(125);
            ValueAnimator tydAnim = ObjectAnimator.ofFloat(p, "translationY", 0f);
            tydAnim.setDuration(100);
            bounce.play(tyuAnim).before(tydAnim);
            bounce.setInterpolator(new AccelerateInterpolator());
            bounce.start();
        }
    }

    public boolean onKey(View v, int keyCode, KeyEvent event) {
        return FocusHelper.handleAppsCustomizeKeyEvent(v,  keyCode, event);
    }

    /*
     * PagedViewWithDraggableItems implementation
     */
    @Override
    protected void determineDraggingStart(android.view.MotionEvent ev) {
        // Disable dragging by pulling an app down for now.
    }

    private void beginDraggingApplication(View v) {
        mLauncher.getWorkspace().onDragStartedWithItem(v);
        mLauncher.getWorkspace().beginDragShared(v, this);
    }

    private void beginDraggingWidget(View v) {
        // Get the widget preview as the drag representation
        ImageView image = (ImageView) v.findViewById(R.id.widget_preview);
        PendingAddItemInfo createItemInfo = (PendingAddItemInfo) v.getTag();

        if (LauncherLog.DEBUG_DRAG) {
            LauncherLog.d(TAG, "beginDraggingWidget: createItemInfo = " + createItemInfo + ",v = "
                    + v + ",image = " + image + ",this = " + this);
        }
        // Compose the drag image
        Bitmap preview;
        Bitmap outline;
        if (createItemInfo instanceof PendingAddWidgetInfo) {
            PendingAddWidgetInfo createWidgetInfo = (PendingAddWidgetInfo) createItemInfo;
            int[] spanXY = mLauncher.getSpanForWidget(createWidgetInfo, null);
            createItemInfo.spanX = spanXY[0];
            createItemInfo.spanY = spanXY[1];

            int[] maxSize = mLauncher.getWorkspace().estimateItemSize(spanXY[0], spanXY[1],
                    createWidgetInfo, true);
            preview = getWidgetPreview(createWidgetInfo.componentName, createWidgetInfo.previewImage,
                    createWidgetInfo.icon, spanXY[0], spanXY[1], maxSize[0], maxSize[1]);
        } else {
            // Workaround for the fact that we don't keep the original ResolveInfo associated with
            // the shortcut around.  To get the icon, we just render the preview image (which has
            // the shortcut icon) to a new drag bitmap that clips the non-icon space.
            preview = Bitmap.createBitmap(mWidgetPreviewIconPaddedDimension,
                    mWidgetPreviewIconPaddedDimension, Bitmap.Config.ARGB_8888);
            Drawable d = image.getDrawable();
            mCanvas.setBitmap(preview);
            d.draw(mCanvas);
            mCanvas.setBitmap(null);
            createItemInfo.spanX = createItemInfo.spanY = 1;
        }

        // We use a custom alpha clip table for the default widget previews
        Paint alphaClipPaint = null;
        if (createItemInfo instanceof PendingAddWidgetInfo) {
            if (((PendingAddWidgetInfo) createItemInfo).previewImage != 0) {
                MaskFilter alphaClipTable = TableMaskFilter.CreateClipTable(0, 255);
                alphaClipPaint = new Paint();
                alphaClipPaint.setMaskFilter(alphaClipTable);
            }
        }

        // Save the preview for the outline generation, then dim the preview
        outline = Bitmap.createScaledBitmap(preview, preview.getWidth(), preview.getHeight(),
                false);
        mCanvas.setBitmap(preview);
        mCanvas.drawColor(mDragViewMultiplyColor, PorterDuff.Mode.MULTIPLY);
        mCanvas.setBitmap(null);

        // Start the drag
        alphaClipPaint = null;
        mLauncher.lockScreenOrientationOnLargeUI();
        mLauncher.getWorkspace().onDragStartedWithItem(createItemInfo, outline, alphaClipPaint);
        mDragController.startDrag(image, preview, this, createItemInfo,
                DragController.DRAG_ACTION_COPY, null);
        outline.recycle();
        preview.recycle();
    }
    
    @Override
    protected boolean beginDragging(View v) {
        if (LauncherLog.DEBUG_DRAG) {
            LauncherLog.d(TAG, "beginDragging: v = " + v + ",this = " + this);
        }
        // Dismiss the cling
        mLauncher.dismissAllAppsCling(null);

        if (!super.beginDragging(v)) return false;

        // Go into spring loaded mode (must happen before we startDrag())
        mLauncher.enterSpringLoadedDragMode();

        if (v instanceof PagedViewIcon) {
            beginDraggingApplication(v);
        } else if (v instanceof PagedViewWidget) {
            beginDraggingWidget(v);
        }
        return true;
    }
    
    private void endDragging(View target, boolean success) {
        if (LauncherLog.DEBUG_DRAG) {
            LauncherLog.d(TAG, "endDragging: target = " + target + ",success = " + success);
        }
        mLauncher.getWorkspace().onDragStopped(success);
        if (!success || (target != mLauncher.getWorkspace() &&
                !(target instanceof DeleteDropTarget))) {
            // Exit spring loaded mode if we have not successfully dropped or have not handled the
            // drop in Workspace
            mLauncher.exitSpringLoadedDragMode();
        }
        mLauncher.unlockScreenOrientationOnLargeUI();

    }

    @Override
    public void onDropCompleted(View target, DragObject d, boolean success) {
        if (LauncherLog.DEBUG_DRAG) {
            LauncherLog.d(TAG, "onDropCompleted: target = " + target + ",d = " + d + ",success = " + success);
        }
        endDragging(target, success);

        // Display an error message if the drag failed due to there not being enough space on the
        // target layout we were dropping on.
        if (!success) {
            boolean showOutOfSpaceMessage = false;
            if (target instanceof Workspace) {
                int currentScreen = mLauncher.getCurrentWorkspaceScreen();
                Workspace workspace = (Workspace) target;
                CellLayout layout = (CellLayout) workspace.getChildAt(currentScreen);
                ItemInfo itemInfo = (ItemInfo) d.dragInfo;
                if (layout != null) {
                    layout.calculateSpans(itemInfo);
                    showOutOfSpaceMessage =
                            !layout.findCellForSpan(null, itemInfo.spanX, itemInfo.spanY);
                }
            }
            if (showOutOfSpaceMessage) {
                mLauncher.showOutOfSpaceMessage();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAllTasks();
    }

    public void clearAllWidgetPages() {        
        cancelAllTasks();
        int count = getChildCount();
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "clearAllWidgetPages: count = " + count);
        }
        for (int i = 0; i < count; i++) {
            View v = getPageAt(i);
            if (v instanceof PagedViewGridLayout) {
                ((PagedViewGridLayout) v).removeAllViewsOnPage();
                mDirtyPageContent.set(i, true);
            }
        }
    }

    private void cancelAllTasks() {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "cancelAllTasks: mRunningTasks size = " + mRunningTasks.size());
        }
        // Clean up all the async tasks
        Iterator<AppsCustomizeAsyncTask> iter = mRunningTasks.iterator();
        while (iter.hasNext()) {
            AppsCustomizeAsyncTask task = (AppsCustomizeAsyncTask) iter.next();
            task.cancel(false);
            iter.remove();
        }
    }

    public void setContentType(ContentType type) {
        if (type == ContentType.Widgets) {
            invalidatePageData(mNumAppsPages + 2, true);
        } else if (type == ContentType.Applications) {
            invalidatePageData(0, true);
        }
    }

    protected void snapToPage(int whichPage, int delta, int duration) {
        super.snapToPage(whichPage, delta, duration);
//        updateCurrentTab(whichPage);
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "snapToPage: whichPage = " + whichPage + ",delta = "
                    + delta + ",duration = " + duration + ",this = " + this);
        }
        
        // Update the thread priorities given the direction lookahead
        Iterator<AppsCustomizeAsyncTask> iter = mRunningTasks.iterator();
        while (iter.hasNext()) {
            AppsCustomizeAsyncTask task = (AppsCustomizeAsyncTask) iter.next();
            int pageIndex = task.page + mNumAppsPages;
            if ((mNextPage > mCurrentPage && pageIndex >= mCurrentPage) ||
                (mNextPage < mCurrentPage && pageIndex <= mCurrentPage)) {
                task.setThreadPriority(getThreadPriorityForPage(pageIndex));
            } else {
                task.setThreadPriority(Process.THREAD_PRIORITY_LOWEST);
            }
        }
    }

    private void updateCurrentTab(int currentPage) {
        AppsCustomizeTabHost tabHost = getTabHost();
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "updateCurrentTab: currentPage = " + currentPage
                    + ",mCurrentPage = " + mCurrentPage + ",this = " + this);
        }
        if (tabHost != null) {
            String tag = tabHost.getCurrentTabTag();
            if (tag != null) {
                if ((currentPage >= mNumAppsPages || currentPage == - 1) &&
                        !tag.equals(tabHost.getTabTagForContentType(ContentType.Widgets))) {
                    tabHost.setCurrentTabFromContent(ContentType.Widgets);
                } else if ((currentPage < mNumAppsPages || currentPage == getPageCount()) &&
                        !tag.equals(tabHost.getTabTagForContentType(ContentType.Applications))) {
                    tabHost.setCurrentTabFromContent(ContentType.Applications);
                }
            }
        }
    }

    /*
     * Apps PagedView implementation
     */
    private void setVisibilityOnChildren(ViewGroup layout, int visibility) {
        int childCount = layout.getChildCount();
        for (int i = 0; i < childCount; ++i) {
            layout.getChildAt(i).setVisibility(visibility);
        }
    }
    
    private void setupPage(PagedViewCellLayout layout) {
        layout.setCellCount(mCellCountX, mCellCountY);
        layout.setGap(mPageLayoutWidthGap, mPageLayoutHeightGap);
        layout.setPadding(mPageLayoutPaddingLeft, mPageLayoutPaddingTop,
                mPageLayoutPaddingRight, mPageLayoutPaddingBottom);

        // Note: We force a measure here to get around the fact that when we do layout calculations
        // immediately after syncing, we don't have a proper width.  That said, we already know the
        // expected page width, so we can actually optimize by hiding all the TextView-based
        // children that are expensive to measure, and let that happen naturally later.
        setVisibilityOnChildren(layout, View.GONE);
        int widthSpec = MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.AT_MOST);
        int heightSpec = MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.AT_MOST);
        layout.setMinimumWidth(getPageContentWidth());
        layout.measure(widthSpec, heightSpec);
        setVisibilityOnChildren(layout, View.VISIBLE);
    }

    public void syncAppsPageItems(int page, boolean immediate) {
        // ensure that we have the right number of items on the pages
        int numCells = mCellCountX * mCellCountY;
        int startIndex = page * numCells;
        int endIndex = Math.min(startIndex + numCells, mApps.size());
        PagedViewCellLayout layout = (PagedViewCellLayout) getPageAt(page);
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "syncAppsPageItems: page = " + page + ",immediate = " + immediate
                    + ",numCells = " + numCells + ",startIndex = " + startIndex + ",endIndex = "
                    + endIndex + ",this = " + this);
        }
        layout.removeAllViewsOnPage();
        ArrayList<Object> items = new ArrayList<Object>();
        ArrayList<Bitmap> images = new ArrayList<Bitmap>();
        for (int i = startIndex; i < endIndex; ++i) {
            ApplicationInfo info = mApps.get(i);
            PagedViewIcon icon = (PagedViewIcon) mLayoutInflater.inflate(
                    R.layout.apps_customize_application, layout, false);
            icon.applyFromApplicationInfo(info, true, mHolographicOutlineHelper);
            icon.setOnClickListener(this);
            icon.setOnLongClickListener(this);
            icon.setOnTouchListener(this);
            icon.setOnKeyListener(this);

            int index = i - startIndex;
            int x = index % mCellCountX;
            int y = index / mCellCountX;
            layout.addViewToCellLayout(icon, -1, i, new PagedViewCellLayout.LayoutParams(x,y, 1,1));

            items.add(info);
            images.add(info.iconBitmap);
        }

//        layout.createHardwareLayers();

        /* TEMPORARILY DISABLE HOLOGRAPHIC ICONS
        if (mFadeInAdjacentScreens) {
            prepareGenerateHoloOutlinesTask(page, items, images);
        }
        */
    }

    /**
     * A helper to return the priority for loading of the specified widget page.
     */
    private int getWidgetPageLoadPriority(int page) {
        // If we are snapping to another page, use that index as the target page index
        int toPage = mCurrentPage;
        if (mNextPage > -1) {
            toPage = mNextPage;
        }

        // We use the distance from the target page as an initial guess of priority, but if there
        // are no pages of higher priority than the page specified, then bump up the priority of
        // the specified page.
        Iterator<AppsCustomizeAsyncTask> iter = mRunningTasks.iterator();
        int minPageDiff = Integer.MAX_VALUE;
        while (iter.hasNext()) {
            AppsCustomizeAsyncTask task = (AppsCustomizeAsyncTask) iter.next();
            minPageDiff = Math.abs(task.page + mNumAppsPages - toPage);
        }

        int rawPageDiff = Math.abs(page - toPage);
        return rawPageDiff - Math.min(rawPageDiff, minPageDiff);
    }
    /**
     * Return the appropriate thread priority for loading for a given page (we give the current
     * page much higher priority)
     */
    private int getThreadPriorityForPage(int page) {
        // TODO-APPS_CUSTOMIZE: detect number of cores and set thread priorities accordingly below
        int pageDiff = getWidgetPageLoadPriority(page);
        if (pageDiff <= 0) {
            return Process.THREAD_PRIORITY_LESS_FAVORABLE;
        } else if (pageDiff <= 1) {
            return Process.THREAD_PRIORITY_LOWEST;
        } else {
            return Process.THREAD_PRIORITY_LOWEST;
        }
    }
    
    private int getSleepForPage(int page) {
        int pageDiff = getWidgetPageLoadPriority(page);
        return Math.max(0, pageDiff * sPageSleepDelay);
    }
    
    /**
     * Creates and executes a new AsyncTask to load a page of widget previews.
     */
    private void prepareLoadWidgetPreviewsTask(int page, ArrayList<Object> widgets,
            int cellWidth, int cellHeight, int cellCountX) {
        // Prune all tasks that are no longer needed
        Iterator<AppsCustomizeAsyncTask> iter = mRunningTasks.iterator();
        while (iter.hasNext()) {
            AppsCustomizeAsyncTask task = (AppsCustomizeAsyncTask) iter.next();
            int taskPage = task.page + mNumAppsPages;
            /*if (taskPage < getAssociatedLowerPageBound(mCurrentPage) ||
                    taskPage > getAssociatedUpperPageBound(mCurrentPage)) {
                task.cancel(false);
                iter.remove();
            } else {
                task.setThreadPriority(getThreadPriorityForPage(taskPage));
            }*/
            task.setThreadPriority(getThreadPriorityForPage(taskPage));
        }

        // We introduce a slight delay to order the loading of side pages so that we don't thrash
        final int sleepMs = getSleepForPage(page + mNumAppsPages);
        AsyncTaskPageData pageData = new AsyncTaskPageData(page, widgets, cellWidth, cellHeight,
            new AsyncTaskCallback() {
                @Override
                public void run(AppsCustomizeAsyncTask task, AsyncTaskPageData data) {
                    try {
                        try {
                            Thread.sleep(sleepMs);
                        } catch (Exception e) {}
                        loadWidgetPreviewsInBackground(task, data);
                    } finally {
                        if (task.isCancelled()) {
                            data.cleanup(true);
                        }
                    }
                }
            },
            new AsyncTaskCallback() {
                @Override
                public void run(AppsCustomizeAsyncTask task, AsyncTaskPageData data) {
                    try {
                        mRunningTasks.remove(task);
                        if (task.isCancelled()) return;
                        onSyncWidgetPageItems(data);
                    } finally {
                        data.cleanup(task.isCancelled());
                    }
                }
            });

        // Ensure that the task is appropriately prioritized and runs in parallel
        AppsCustomizeAsyncTask t = new AppsCustomizeAsyncTask(page,
                AsyncTaskPageData.Type.LoadWidgetPreviewData);
        t.setThreadPriority(getThreadPriorityForPage(page + mNumAppsPages));
        t.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, pageData);
        mRunningTasks.add(t);
    }
    
    /**
     * Creates and executes a new AsyncTask to load the outlines for a page of content.
     */
    /*private void prepareGenerateHoloOutlinesTask(int page, ArrayList<Object> items,
            ArrayList<Bitmap> images) {
        // Prune old tasks for this page
        Iterator<AppsCustomizeAsyncTask> iter = mRunningTasks.iterator();
        while (iter.hasNext()) {
            AppsCustomizeAsyncTask task = (AppsCustomizeAsyncTask) iter.next();
            int taskPage = task.page;
            if ((taskPage == page) &&
                    (task.dataType == AsyncTaskPageData.Type.LoadHolographicIconsData)) {
                task.cancel(false);
                iter.remove();
            }
        }

        AsyncTaskPageData pageData = new AsyncTaskPageData(page, items, images,
            new AsyncTaskCallback() {
                @Override
                public void run(AppsCustomizeAsyncTask task, AsyncTaskPageData data) {
                    try {
                        // Ensure that this task starts running at the correct priority
                        task.syncThreadPriority();

                        ArrayList<Bitmap> images = data.generatedImages;
                        ArrayList<Bitmap> srcImages = data.sourceImages;
                        int count = srcImages.size();
                        Canvas c = new Canvas();
                        for (int i = 0; i < count && !task.isCancelled(); ++i) {
                            // Before work on each item, ensure that this task is running at the correct
                            // priority
                            task.syncThreadPriority();

                            Bitmap b = srcImages.get(i);
                            Bitmap outline = Bitmap.createBitmap(b.getWidth(), b.getHeight(),
                                    Bitmap.Config.ARGB_8888);

                            c.setBitmap(outline);
                            c.save();
                            c.drawBitmap(b, 0, 0, null);
                            c.restore();
                            c.setBitmap(null);

                            images.add(outline);
                        }
                    } finally {
                        if (task.isCancelled()) {
                            data.cleanup(true);
                        }
                    }
                }
            },
            new AsyncTaskCallback() {
                @Override
                public void run(AppsCustomizeAsyncTask task, AsyncTaskPageData data) {
                    try {
                        mRunningTasks.remove(task);
                        if (task.isCancelled()) return;
                        onHolographicPageItemsLoaded(data);
                    } finally {
                        data.cleanup(task.isCancelled());
                    }
                }
            });

        // Ensure that the outline task always runs in the background, serially
        AppsCustomizeAsyncTask t =
            new AppsCustomizeAsyncTask(page, AsyncTaskPageData.Type.LoadHolographicIconsData);
        t.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        t.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, pageData);
        mRunningTasks.add(t);
    }*/

    /*
     * Widgets PagedView implementation
     */
    private void setupPage(PagedViewGridLayout layout) {
        layout.setPadding(mPageLayoutPaddingLeft, mPageLayoutPaddingTop,
                mPageLayoutPaddingRight, mPageLayoutPaddingBottom);

        // Note: We force a measure here to get around the fact that when we do layout calculations
        // immediately after syncing, we don't have a proper width.
        int widthSpec = MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.AT_MOST);
        int heightSpec = MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.AT_MOST);
        layout.setMinimumWidth(getPageContentWidth());
        layout.measure(widthSpec, heightSpec);
    }

    private void renderDrawableToBitmap(Drawable d, Bitmap bitmap, int x, int y, int w, int h) {
        renderDrawableToBitmap(d, bitmap, x, y, w, h, 1f, 0xFFFFFFFF);
    }
    
    private void renderDrawableToBitmap(Drawable d, Bitmap bitmap, int x, int y, int w, int h,
            float scale, int multiplyColor) {
        if (bitmap != null) {
            Canvas c = new Canvas(bitmap);
            c.scale(scale, scale);
            Rect oldBounds = d.copyBounds();
            d.setBounds(x, y, x + w, y + h);
            d.draw(c);
            d.setBounds(oldBounds); // Restore the bounds
            if (multiplyColor != 0xFFFFFFFF) {
                c.drawColor(mDragViewMultiplyColor, PorterDuff.Mode.MULTIPLY);
            }
            c.setBitmap(null);
        }
    }
    private Bitmap getShortcutPreview(ResolveInfo info) {
        // Render the background
        int offset = 0;
        int bitmapSize = mAppIconSize;
        Bitmap preview = Bitmap.createBitmap(bitmapSize, bitmapSize, Config.ARGB_8888);

        // Render the icon
        Drawable icon = mIconCache.getFullResIcon(info);
        renderDrawableToBitmap(icon, preview, offset, offset, mAppIconSize, mAppIconSize);
        return preview;
    }
    
    private Bitmap getWidgetPreview(ComponentName provider, int previewImage, int iconId,
            int cellHSpan, int cellVSpan, int maxWidth, int maxHeight) {
        // Load the preview image if possible
        String packageName = provider.getPackageName();
        if (maxWidth < 0) maxWidth = Integer.MAX_VALUE;
        if (maxHeight < 0) maxHeight = Integer.MAX_VALUE;

        Drawable drawable = null;
        if (previewImage != 0) {
            drawable = mPackageManager.getDrawable(packageName, previewImage, null);
            if (drawable == null) {
                Log.w(TAG, "Can't load widget preview drawable 0x" +
                        Integer.toHexString(previewImage) + " for provider: " + provider);
            }
        }

        int bitmapWidth;
        int bitmapHeight;
        boolean widgetPreviewExists = (drawable != null);
        if (widgetPreviewExists) {
            bitmapWidth = drawable.getIntrinsicWidth();
            bitmapHeight = drawable.getIntrinsicHeight();

            // Cap the size so widget previews don't appear larger than the actual widget
            maxWidth = Math.min(maxWidth, mWidgetSpacingLayout.estimateCellWidth(cellHSpan));
            maxHeight = Math.min(maxHeight, mWidgetSpacingLayout.estimateCellHeight(cellVSpan));
        } else {
            // Determine the size of the bitmap for the preview image we will generate
            // TODO: This actually uses the apps customize cell layout params, where as we make want
            // the Workspace params for more accuracy.
            bitmapWidth = mWidgetSpacingLayout.estimateCellWidth(cellHSpan);
            bitmapHeight = mWidgetSpacingLayout.estimateCellHeight(cellVSpan);
            if (cellHSpan == cellVSpan) {
                // For square widgets, we just have a fixed size for 1x1 and larger-than-1x1
                int minOffset = (int) (mAppIconSize * sWidgetPreviewIconPaddingPercentage);
                if (cellHSpan <= 1) {
                    bitmapWidth = bitmapHeight = mAppIconSize + 2 * minOffset;
                } else {
                    bitmapWidth = bitmapHeight = mAppIconSize + 4 * minOffset;
                }
            }
        }

        float scale = 1f;
        if (bitmapWidth > maxWidth) {
            scale = maxWidth / (float) bitmapWidth;
        }
        if (bitmapHeight * scale > maxHeight) {
            scale = maxHeight / (float) bitmapHeight;
        }
        if (scale != 1f) {
            bitmapWidth = (int) (scale * bitmapWidth);
            bitmapHeight = (int) (scale * bitmapHeight);
        }

        Bitmap preview = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Config.ARGB_8888);

        if (widgetPreviewExists) {
            renderDrawableToBitmap(drawable, preview, 0, 0, bitmapWidth, bitmapHeight);
        } else {
            // Generate a preview image if we couldn't load one
            int minOffset = (int) (mAppIconSize * sWidgetPreviewIconPaddingPercentage);
            int smallestSide = Math.min(bitmapWidth, bitmapHeight);
            float iconScale = Math.min((float) smallestSide / (mAppIconSize + 2 * minOffset), 1f);
            if (cellHSpan != 1 || cellVSpan != 1) {
                renderDrawableToBitmap(mDefaultWidgetBackground, preview, 0, 0, bitmapWidth,
                        bitmapHeight);
            }

            // Draw the icon in the top left corner
            try {
                Drawable icon = null;
                int hoffset = (int) (bitmapWidth / 2 - mAppIconSize * iconScale / 2);
                int yoffset = (int) (bitmapHeight / 2 - mAppIconSize * iconScale / 2);
                if (iconId > 0) icon = mIconCache.getFullResIcon(packageName, iconId);
                Resources resources = mLauncher.getResources();
                if (icon == null) icon = resources.getDrawable(R.drawable.ic_launcher_application);

                renderDrawableToBitmap(icon, preview, hoffset, yoffset,
                        (int) (mAppIconSize * iconScale),
                        (int) (mAppIconSize * iconScale));
            } catch (Resources.NotFoundException e) {}
        }
        return preview;
    }

    public void syncWidgetPageItems(final int page, final boolean immediate) {
        int numItemsPerPage = mWidgetCountX * mWidgetCountY;
        // Calculate the dimensions of each cell we are giving to each widget
        final ArrayList<Object> items = new ArrayList<Object>();
        int contentWidth = mWidgetSpacingLayout.getContentWidth();
        final int cellWidth = ((contentWidth - mPageLayoutPaddingLeft - mPageLayoutPaddingRight
                - ((mWidgetCountX - 1) * mWidgetWidthGap)) / mWidgetCountX);
        int contentHeight = mWidgetSpacingLayout.getContentHeight();
        final int cellHeight = ((contentHeight - mPageLayoutPaddingTop - mPageLayoutPaddingBottom
                - ((mWidgetCountY - 1) * mWidgetHeightGap)) / mWidgetCountY);

        // Prepare the set of widgets to load previews for in the background
        int offset = page * numItemsPerPage;
        for (int i = offset; i < Math.min(offset + numItemsPerPage, mWidgets.size()); ++i) {
            items.add(mWidgets.get(i));
        }
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "syncWidgetPageItems: page = " + page + ",immediate = " + immediate
                    + ",numItemsPerPage = " + numItemsPerPage + ",cellWidth = " + cellWidth
                    + ",contentHeight = " + contentHeight + ",cellHeight = " + cellHeight
                    + ",offset = " + offset + ",this = " + this);
        }
        // Prepopulate the pages with the other widget info, and fill in the previews later
        final PagedViewGridLayout layout = (PagedViewGridLayout) getPageAt(page + mNumAppsPages);
        layout.setColumnCount(layout.getCellCountX());
        for (int i = 0; i < items.size(); ++i) {
            Object rawInfo = items.get(i);
            PendingAddItemInfo createItemInfo = null;
            PagedViewWidget widget = (PagedViewWidget) mLayoutInflater.inflate(
                    R.layout.apps_customize_widget, layout, false);
            if (rawInfo instanceof AppWidgetProviderInfo) {
                // Fill in the widget information
                AppWidgetProviderInfo info = (AppWidgetProviderInfo) rawInfo;
                createItemInfo = new PendingAddWidgetInfo(info, null, null);
                int[] cellSpans = mLauncher.getSpanForWidget(info, null);
                widget.applyFromAppWidgetProviderInfo(info, -1, cellSpans,
                        mHolographicOutlineHelper);
                widget.setTag(createItemInfo);
            } else if (rawInfo instanceof ResolveInfo) {
                // Fill in the shortcuts information
                ResolveInfo info = (ResolveInfo) rawInfo;
                createItemInfo = new PendingAddItemInfo();
                createItemInfo.itemType = LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT;
                createItemInfo.componentName = new ComponentName(info.activityInfo.packageName,
                        info.activityInfo.name);
                widget.applyFromResolveInfo(mPackageManager, info, mHolographicOutlineHelper);
                widget.setTag(createItemInfo);
            }
            widget.setOnClickListener(this);
            widget.setOnLongClickListener(this);
            widget.setOnTouchListener(this);
            widget.setOnKeyListener(this);

            // Layout each widget
            int ix = i % mWidgetCountX;
            int iy = i / mWidgetCountX;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(iy, GridLayout.LEFT),
                    GridLayout.spec(ix, GridLayout.TOP));
            lp.width = cellWidth;
            lp.height = cellHeight;
            lp.setGravity(Gravity.TOP | Gravity.LEFT);
            if (ix > 0) lp.leftMargin = mWidgetWidthGap;
            if (iy > 0) lp.topMargin = mWidgetHeightGap;
            layout.addView(widget, lp);
        }

        final Resources res = getContext().getResources();
        final int widgetsPaddingLeft = (int)res.getDimension(R.dimen.app_widget_preview_padding_left);
        final int widgetsPaddingTop = (int)res.getDimension(R.dimen.app_widget_preview_padding_top);
        // wait until a call on onLayout to start loading, because
        // PagedViewWidget.getPreviewSize() will return 0 if it hasn't been laid out
        // TODO: can we do a measure/layout immediately?
        layout.setOnLayoutListener(new Runnable() {
            public void run() {
                // Load the widget previews, minus the widgets padding to avoid image cut issue.
                int maxPreviewWidth = cellWidth - widgetsPaddingLeft;
                int maxPreviewHeight = cellHeight - widgetsPaddingTop;
                if (layout.getChildCount() > 0) {
                    PagedViewWidget w = (PagedViewWidget) layout.getChildAt(0);
                    int[] maxSize = w.getPreviewSize();
                    if (maxSize[0] > 0 && maxSize[1] > 0) {
                        maxPreviewWidth = maxSize[0];
                        maxPreviewHeight = maxSize[1];
                    }               
                }
                if (immediate) {
                    AsyncTaskPageData data = new AsyncTaskPageData(page, items,
                            maxPreviewWidth, maxPreviewHeight, null, null);
                    loadWidgetPreviewsInBackground(null, data);
                    onSyncWidgetPageItems(data);
                } else {
                    prepareLoadWidgetPreviewsTask(page, items,
                            maxPreviewWidth, maxPreviewHeight, mWidgetCountX);
                }
            }
        });
    }
    
    private void loadWidgetPreviewsInBackground(AppsCustomizeAsyncTask task,
            AsyncTaskPageData data) {
        // loadWidgetPreviewsInBackground can be called without a task to load a set of widget
        // previews synchronously
        if (task != null) {
            // Ensure that this task starts running at the correct priority
            task.syncThreadPriority();
        }

        // Load each of the widget/shortcut previews
        ArrayList<Object> items = data.items;
        ArrayList<Bitmap> images = data.generatedImages;
        int count = items.size();
        for (int i = 0; i < count; ++i) {
            if (task != null) {
                // Ensure we haven't been cancelled yet
                if (task.isCancelled()) break;
                // Before work on each item, ensure that this task is running at the correct
                // priority
                task.syncThreadPriority();
            }

            Object rawInfo = items.get(i);
            if (rawInfo instanceof AppWidgetProviderInfo) {
                AppWidgetProviderInfo info = (AppWidgetProviderInfo) rawInfo;
                int[] cellSpans = mLauncher.getSpanForWidget(info, null);
                Bitmap b = getWidgetPreview(info.provider, info.previewImage, info.icon,
                        cellSpans[0], cellSpans[1], data.maxImageWidth, data.maxImageHeight);
                images.add(b);
            } else if (rawInfo instanceof ResolveInfo) {
                // Fill in the shortcuts information
                ResolveInfo info = (ResolveInfo) rawInfo;
                images.add(getShortcutPreview(info));
            }
        }
    }
    
    private void onSyncWidgetPageItems(AsyncTaskPageData data) {
        int page = data.page;
        PagedViewGridLayout layout = (PagedViewGridLayout) getPageAt(page + mNumAppsPages);
        ArrayList<Object> items = data.items;
        int count = items.size();
        for (int i = 0; i < count; ++i) {
            PagedViewWidget widget = (PagedViewWidget) layout.getChildAt(i);
            if (widget != null) {
                Bitmap preview = data.generatedImages.get(i);
                widget.applyPreview(new FastBitmapDrawable(preview), i);
            }
        }

        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "onSyncWidgetPageItems: page = " + page + ",layout = " + 
                    layout + ",count = " + count + ",this = " + this);
        }
//        layout.createHardwareLayer();
        invalidate();

        /* TEMPORARILY DISABLE HOLOGRAPHIC ICONS
        if (mFadeInAdjacentScreens) {
            prepareGenerateHoloOutlinesTask(data.page, data.items, data.generatedImages);
        }
        */

        // Update all thread priorities
        Iterator<AppsCustomizeAsyncTask> iter = mRunningTasks.iterator();
        while (iter.hasNext()) {
            AppsCustomizeAsyncTask task = (AppsCustomizeAsyncTask) iter.next();
            int pageIndex = task.page + mNumAppsPages;
            task.setThreadPriority(getThreadPriorityForPage(pageIndex));
        }
    }
    
    private void onHolographicPageItemsLoaded(AsyncTaskPageData data) {
        // Invalidate early to short-circuit children invalidates
        invalidate();

        int page = data.page;
        ViewGroup layout = (ViewGroup) getPageAt(page);
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "onSyncWidgetPageItems: page = " + page + ",layout = " + layout
                    + ",this = " + this);
        }
        if (layout instanceof PagedViewCellLayout) {
            PagedViewCellLayout cl = (PagedViewCellLayout) layout;
            int count = cl.getPageChildCount();
            if (count != data.generatedImages.size()) return;
            for (int i = 0; i < count; ++i) {
                PagedViewIcon icon = (PagedViewIcon) cl.getChildOnPageAt(i);
                icon.setHolographicOutline(data.generatedImages.get(i));
            }
        } else {
            int count = layout.getChildCount();
            if (count != data.generatedImages.size()) return;
            for (int i = 0; i < count; ++i) {
                View v = layout.getChildAt(i);
                ((PagedViewWidget) v).setHolographicOutline(data.generatedImages.get(i));
            }
        }
    }

    @Override
    public void syncPages() {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "syncPages: mNumWidgetPages = " + mNumWidgetPages + ",mNumAppsPages = "
                    + mNumAppsPages + ",this = " + this);
        }
        removeAllViews();
        cancelAllTasks();

        Context context = getContext();
        for (int j = 0; j < mNumWidgetPages; ++j) {
            PagedViewGridLayout layout = new PagedViewGridLayout(context, mWidgetCountX,
                    mWidgetCountY);
            setupPage(layout);
            addView(layout, new PagedViewGridLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));
        }

        for (int i = 0; i < mNumAppsPages; ++i) {
            PagedViewCellLayout layout = new PagedViewCellLayout(context);
            setupPage(layout);
            addView(layout);
        }
    }

    @Override
    public void syncPageItems(int page, boolean immediate) {
        if (page < mNumAppsPages) {
            syncAppsPageItems(page, immediate);
        } else {
            syncWidgetPageItems(page - mNumAppsPages, immediate);
        }
    }

    // We want our pages to be z-ordered such that the further a page is to the left, the higher
    // it is in the z-order. This is important to insure touch events are handled correctly.
    @Override
    View getPageAt(int index) {
    	return getChildAt(getChildCount() - index - 1);
    }

    View getPageAtByCurrent(int index) {
		return getPageAt(index >= mNumAppsPages ? index - 2 : index);
    }
    
    @Override
    protected int indexToPage(int index) {
        return getChildCount() - index - 1;
    }

    // In apps customize, we have a scrolling effect which emulates pulling cards off of a stack.
    /*@Override
    protected void screenScrolled(int center) {
        super.screenScrolled(center);
        final int visualIndex = (center + getMeasuredWidth()) / getMeasuredWidth() - 1;//make ensure this figure can be nagetive
        int middle = locateOfCenter(center);
        int leftOne = locateOfCenter(center - getMeasuredWidth());
        int rightOne = locateOfCenter(center + getMeasuredWidth());
        CellLayout page = (CellLayout) getPageAt(middle);
        CellLayout prevPage = (CellLayout) getPageAt(leftOne);
        CellLayout nextPage = (CellLayout) getPageAt(rightOne);
        float scrollProgress = getScrollProgress(center, page, visualIndex);
    }*/
    
    protected void overScroll(float amount) {
        acceleratedOverScroll(amount);
    }

    /**
     * Used by the parent to get the content width to set the tab bar to
     * @return
     */
    public int getPageContentWidth() {
        return mContentWidth;
    }

    @Override
    protected void onPageEndMoving() {
        super.onPageEndMoving();
        // We reset the save index when we change pages so that it will be recalculated on next
        // rotation
        mSaveInstanceStateItemIndex = -1;
    }

    /*
     * AllAppsView implementation
     */
    @Override
    public void setup(Launcher launcher, DragController dragController, SlideDivider dockDivider) {
        mLauncher = launcher;
        mDragController = dragController;
        registeSlideDividerforMyself(dockDivider);
    }
    
    @Override
    public void zoom(float zoom, boolean animate) {
        // TODO-APPS_CUSTOMIZE: Call back to mLauncher.zoomed()
    }
    
    @Override
    public boolean isVisible() {
        return (getVisibility() == VISIBLE);
    }
    
    @Override
    public boolean isAnimating() {
        return false;
    }
    
    @Override
    public void setApps(ArrayList<ApplicationInfo> list) {
        mApps = list;
        Collections.sort(mApps, LauncherModel.APP_NAME_COMPARATOR);
        reorderApps();
        updatePageCounts();

        // The next layout pass will trigger data-ready if both widgets and apps are set, so 
        // request a layout to do this test and invalidate the page data when ready.
        if (testDataReady()) requestLayout();
    }
    
    private void addAppsWithoutInvalidate(ArrayList<ApplicationInfo> list) {
        // We add it in place, in alphabetical order
        int count = list.size();
        for (int i = 0; i < count; ++i) {
            ApplicationInfo info = list.get(i);
            int index = Collections.binarySearch(mApps, info, LauncherModel.APP_NAME_COMPARATOR);
            if (index < 0) {
                mApps.add(-(index + 1), info);
            }
        }
    }
    
    @Override
    public void addApps(ArrayList<ApplicationInfo> list) {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "addApps: list = " + list + ",this = " + this);
        }
        addAppsWithoutInvalidate(list);
        reorderApps();
        updatePageCounts();
        invalidatePageData();
    }
    
    private int findAppByComponent(List<ApplicationInfo> list, ApplicationInfo item) {
        ComponentName removeComponent = item.intent.getComponent();
        int length = list.size();
        for (int i = 0; i < length; ++i) {
            ApplicationInfo info = list.get(i);
            if (info.intent.getComponent().equals(removeComponent)) {
                return i;
            }
        }
        return -1;
    }
    
    private void removeAppsWithoutInvalidate(ArrayList<ApplicationInfo> list) {
        // loop through all the apps and remove apps that have the same component
        int length = list.size();
        for (int i = 0; i < length; ++i) {
            ApplicationInfo info = list.get(i);
            int removeIndex = findAppByComponent(mApps, info);
            if (removeIndex > -1) {
                mApps.remove(removeIndex);
            }
        }
    }
    
    @Override
    public void removeApps(ArrayList<ApplicationInfo> list) {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "removeApps: list = " + list + ",this = " + this);
        }
        removeAppsWithoutInvalidate(list);
        reorderApps();
        updatePageCounts();
        invalidatePageData();
    }
    
    @Override
    public void updateApps(ArrayList<ApplicationInfo> list) {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "updateApps: list = " + list + ",this = " + this);
        }
        // We remove and re-add the updated applications list because it's properties may have
        // changed (ie. the title), and this will ensure that the items will be in their proper
        // place in the list.
        removeAppsWithoutInvalidate(list);
        addAppsWithoutInvalidate(list);
        updatePageCounts();

        invalidatePageData();
    }

    @Override
    public void reset() {
        AppsCustomizeTabHost tabHost = getTabHost();
        String tag = tabHost.getCurrentTabTag();
        if (tag != null) {
            if (!tag.equals(tabHost.getTabTagForContentType(ContentType.Applications))) {
                tabHost.setCurrentTabFromContent(ContentType.Applications);
            }
        }
        if (mCurrentPage != 0) {
            invalidatePageData(0);
        }
    }

    AppsCustomizeTabHost mTabHost;
    private ValueAnimator mInertia;
    private AppsCustomizeTabHost getTabHost() {
        if (mTabHost == null) {
            mTabHost = (AppsCustomizeTabHost) mLauncher.findViewById(R.id.apps_customize_pane);
        }
        return mTabHost;
    }

    @Override
    public void dumpState() {
        // TODO: Dump information related to current list of Applications, Widgets, etc.
        ApplicationInfo.dumpApplicationInfoList(TAG, "mApps", mApps);
        dumpAppWidgetProviderInfoList(TAG, "mWidgets", mWidgets);
    }
    private void dumpAppWidgetProviderInfoList(String tag, String label,
            ArrayList<Object> list) {
        Log.d(tag, label + " size=" + list.size());
        for (Object i: list) {
            if (i instanceof AppWidgetProviderInfo) {
                AppWidgetProviderInfo info = (AppWidgetProviderInfo) i;
                Log.d(tag, "   label=\"" + info.label + "\" previewImage=" + info.previewImage
                        + " resizeMode=" + info.resizeMode + " configure=" + info.configure
                        + " initialLayout=" + info.initialLayout
                        + " minWidth=" + info.minWidth + " minHeight=" + info.minHeight);
            } else if (i instanceof ResolveInfo) {
                ResolveInfo info = (ResolveInfo) i;
                Log.d(tag, "   label=\"" + info.loadLabel(mPackageManager) + "\" icon="
                        + info.icon);
            }
        }
    }
    @Override
    public void surrender() {
        // TODO: If we are in the middle of any process (ie. for holographic outlines, etc) we
        // should stop this now.

        // Stop all background tasks
        cancelAllTasks();
    }

    /*
     * We load an extra page on each side to prevent flashes from scrolling and loading of the
     * widget previews in the background with the AsyncTasks.
     */
    final static int sLookBehindPageCount = 2;
    final static int sLookAheadPageCount = 2;
    protected int getAssociatedLowerPageBound(int page) {
        final int count = getChildCount();
        int windowSize = Math.min(count, sLookBehindPageCount + sLookAheadPageCount + 1);
        int windowMinIndex = Math.max(Math.min(page - sLookBehindPageCount, count - windowSize), 0);
        return windowMinIndex;
    }
    protected int getAssociatedUpperPageBound(int page) {
        final int count = getChildCount();
        int windowSize = Math.min(count, sLookBehindPageCount + sLookAheadPageCount + 1);
        int windowMaxIndex = Math.min(Math.max(page + sLookAheadPageCount, windowSize - 1),
                count - 1);
        return windowMaxIndex;
    }

    @Override
    protected String getCurrentPageDescription() {
        int page = (mNextPage != INVALID_PAGE) ? mNextPage : mCurrentPage;
        int stringId = R.string.default_scroll_format;
        int count = 0;
        
        if (page < mNumAppsPages) {
            stringId = R.string.apps_customize_apps_scroll_format;
            count = mNumAppsPages;
        } else {
            page -= mNumAppsPages;
            stringId = R.string.apps_customize_widgets_scroll_format;
            count = mNumWidgetPages;
        }

        return String.format(mContext.getString(stringId), page + 1, count);
    }
    
    public void reorderApps(){
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "reorderApps: mApps = " + mApps + ",this = " + this);
        }
		if (AllAppsList.mTopPackages == null || mApps == null
				|| AllAppsList.mTopPackages.isEmpty() || mApps.isEmpty()) {
			return;
		}

		ArrayList<ApplicationInfo> dataReorder = new ArrayList<ApplicationInfo>(
				AllAppsList.DEFAULT_APPLICATIONS_NUMBER);

		for (AllAppsList.TopPackage tp : AllAppsList.mTopPackages) {
			int loop = 0;
			for (ApplicationInfo ai : mApps) {
				if (ai.componentName.getPackageName().equals(tp.mPackageName)
						&& ai.componentName.getClassName().equals(tp.mClassName)) {
					mApps.remove(ai);
					dataReorder.add(ai);
					break;
				}
				loop++;
			}
		}

		for (AllAppsList.TopPackage tp : AllAppsList.mTopPackages) {
			int newIndex = 0;
			for (ApplicationInfo ai : dataReorder) {
				if (ai.componentName.getPackageName().equals(tp.mPackageName)
						&& ai.componentName.getClassName().equals(tp.mClassName)) {
					newIndex = Math.min(Math.max(tp.mOrder, 0), mApps.size());
					mApps.add(newIndex, ai);
					break;
				}
			}
		}
    }
    
    /**
     * @return true means we are browsing app page, otherwise is widget page
     */
    private boolean isAppState() {
    	return mCurrentPage <= mNumAppsPages;
    }
    
	@Override
	public void showPreviews(final boolean start) {
//		super.showPreviews(start);
		if (mPreviewsState && start) {
			return;
		}
		final boolean appState = isAppState();
		final int total = appState ? mNumAppsPages : mNumWidgetPages;
		final AppsCustomizeTabHost container = getTabHost();
		final View tab = mLauncher.getAppTab();
		int[][] matrix = null;
		float finalScale = 0;
		
		setCurrentPage((mNextPage != INVALID_PAGE) ? mNextPage : mCurrentPage);
		
		if (start) {
			matrix = new int[total][2];
			finalScale = previewMatrix(mLauncher.getDragLayer(), Style.SUMSUNG, matrix);
		}
		final float originalTabHostBackAlpha = container.getBackgroundAlpha();
		final float targetTabHostBackAlpha = start ? 0 : (100f / 255f);
		final float originalTabAlpha = tab.getAlpha();
		final float originalTabTranslationY = tab.getTranslationY();
		final float targetTabTranslationY = start ? -tab.getMeasuredHeight() : 0f;
		final float originalDividerAlpha = mScrollIndicator.getAlpha();
		final float targetTandDAlpha = start ? 0f : 1f;
		final float targetPageBackAlpha = start ? 1f : 0f;
		final Class<? extends ViewGroup> ancestor = appState ? PagedViewCellLayout.class : PagedViewGridLayout.class;
		
		for (int i = 0; i < getPageCount(); i++) {
			View child = getPageAt(i);
			if (child.getClass() != ancestor) {
				if (appState) {
					break;
				} else {
					continue;
				}
			}
			originalTranslationX[i] = child.getTranslationX();
			originalTranslationY[i] = child.getTranslationY();
			originalScaleX[i] = child.getScaleX();
			originalScaleY[i] = child.getScaleY();
			originalPageBackAlpha[i] = child instanceof BackgroundAlphable ?
					((BackgroundAlphable)child).getBackgroundAlpha() : 0f;
			if (start) {
				targetTranslationX[i] = matrix[appState ? i : (i - mNumAppsPages)][0] - child.getX();
				targetTranslationY[i] = matrix[appState ? i : (i - mNumAppsPages)][1] - child.getY() - tab.getMeasuredHeight();
				mMaxTranslationY = Math.max(mMaxTranslationY, targetTranslationY[i]);
			} else {
				targetTranslationX[i] = 0f;
				targetTranslationY[i] = 0f;
			}
			targetScale[i] = start ? finalScale : 1.0f;
			mMaxScale = Math.max(targetScale[i], targetScale[i]);
			child.setPivotX(0f);
			child.setPivotY(0f);
		}
		
		ValueAnimator engine = ValueAnimator.ofFloat(0f, 1f);
		engine.setDuration(getResources().getInteger(R.integer.config_workspacePreviewsTime));
		engine.addUpdateListener(new LauncherAnimatorUpdateListener() {
			@Override
			void onAnimationUpdate(float a, float b) {
				for (int i = 0; i < getPageCount(); i++) {
					View child = getPageAt(i);
					if (child.getClass() != ancestor) {
						if (appState) {
							break;
						} else {
							continue;
						}
					}
					child.setTranslationX(a * originalTranslationX[i] + b * targetTranslationX[i]);
					child.setTranslationY(a * originalTranslationY[i] + b * targetTranslationY[i]);
					child.setScaleX(a * originalScaleX[i] + b * targetScale[i]);
					child.setScaleY(a * originalScaleY[i] + b * targetScale[i]);
					if (child instanceof BackgroundAlphable) {
						BackgroundAlphable v = (BackgroundAlphable) child;
						v.setBackgroundAlpha(a * originalPageBackAlpha[i] + b * targetPageBackAlpha);
					}
				}
				tab.setAlpha(a * originalTabAlpha + b * targetTandDAlpha);
				mScrollIndicator.setAlpha(a * originalDividerAlpha + b * targetTandDAlpha);
				tab.setTranslationY(a * originalTabTranslationY + b * targetTabTranslationY);
				container.setBackgroundAlpha(a * originalTabHostBackAlpha + b * targetTabHostBackAlpha);
			}
		});
		engine.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
			    if (start) {
			        mPreviewsState = true;
                }
				mPreviewsSwitching = false;
			}
			@Override
			public void onAnimationStart(Animator animation) {
			    if (!start) {
			        mPreviewsState = false;
                }
				mPreviewsSwitching = true;
			}
		});
		engine.start();
	}

	@Override
	protected boolean onPreviewTouchEvent(MotionEvent ev) {
		final int x = (int) ev.getX();
		final int y = (int) ev.getY();
		
		acquireVelocityTrackerAndAddMovement(ev);
		
		switch (ev.getAction() & MotionEvent.ACTION_MASK) {
		case MotionEvent.ACTION_DOWN:
			mPreviewClickTargetPage = INVALID_PAGE;
			mLastMotionY = y;
			if (!mScroller.isFinished()) {
				mScroller.abortAnimation();
				break;
			}
			if (mInertia != null) {
                mInertia.cancel();
            }
			for (int i = 0; i < getChildCount(); i++) {
				View v = getPageAt(i);
				v.getHitRect(mTempRect);
				if (mTempRect.contains(x + mScrollX, y + mScrollY)) {
					mPreviewClickTargetPage = i;
					return true;
				}
			}
			break;
		case MotionEvent.ACTION_MOVE:
			if ((isAppState() && mNumAppsPages <= 6) || (!isAppState() && mNumWidgetPages <= 6)) {
				return true;
			}
			final float delta = y - mLastMotionY;
			if (mTouchState == TOUCH_STATE_SCROLLING || Math.abs(delta) > 2) {
				moveVisually(0, delta);
				getTabHost().invalidate();
				mPreviewClickTargetPage = INVALID_PAGE;
				mTouchState = TOUCH_STATE_SCROLLING;
			}
			mLastMotionY = y;
			break;
		case MotionEvent.ACTION_UP:
			mLastMotionY = 0;
			if (mPreviewClickTargetPage == INVALID_PAGE && mVelocityTracker != null
			        && mTouchState == TOUCH_STATE_SCROLLING) {
				mVelocityTracker.computeCurrentVelocity(1000);
				final int speed = (int) mVelocityTracker.getYVelocity();
				if (Math.abs(speed) > 100) {
				    mInertia = ValueAnimator.ofFloat(1f, 0f);
	                mInertia.setDuration((long) (Math.abs(speed) * 0.5f));
	                mInertia.addUpdateListener(new AnimatorUpdateListener() {
	                    @Override
	                    public void onAnimationUpdate(ValueAnimator animation) {
	                        float fraction = (Float) animation.getAnimatedValue();
	                        float offset = (speed < 0 ? -1 : 1) * (float) Math.sqrt(Math.abs(speed * 0.5f)) * fraction;
	                        if (moveVisually(0, offset)) {
	                            getTabHost().invalidate();
                            } else {
                                animation.cancel();
                            }
	                    }
	                });
	                mInertia.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            mTouchState = TOUCH_STATE_REST;
                        }
                    });
	                mInertia.start();
                }
				releaseVelocityTracker();
				return true;
			}
			releaseVelocityTracker();
			for (int i = 0; i < getChildCount(); i++) {
				View v = getPageAt(i);
				v.getHitRect(mTempRect);
				if (mTempRect.contains(x + mScrollX, y + mScrollY) && mPreviewClickTargetPage == i) {
					mNextPage = mPreviewClickTargetPage + (isAppState() ? 0 : 2);
					float offset = mNextPage * getMeasuredWidth() - mCurrentPage * getMeasuredWidth();
					moveVisually(offset, 0);
					showPreviews(false);
					return false;
				}
			}		
		}
		return true;
	}
	
	/**
	 * move child view visually in previews situation,  added by leeyb
	 * @param x how long you want to move horizontally
	 * @param y how long you want to move vetically
	 * @return whether we touch the boundry
	 */
	private boolean moveVisually(float offsetX, float offsetY) {
	    final boolean appState = isAppState();
	    final int diff = Math.abs(getTabHost().getHeight() - ((int) (mMaxTranslationY
                        + getTabHost().getHeight() * mMaxScale + 3 * getResources().getDimension(R.dimen.page_previews_gap))));
	    boolean handle = true;
        final Class<? extends ViewGroup> ancestor = appState ? PagedViewCellLayout.class : PagedViewGridLayout.class;
        for (int i = 0; i < getPageCount(); i++) {
            View v = getPageAt(i);
            if (v.getClass() != ancestor) {
                if (appState) {
                    break;
                } else {
                    continue;
                }
            }
            final int boundryUp = (int) targetTranslationY[i] - diff;
            final int boundryDown = (int) targetTranslationY[i] /*+ diff(int) targetTranslationY[targetTranslationY.length - 1]*/;
            if (offsetY != 0.0f) {
                float targetY = v.getY() + offsetY;
//                if (targetY < -tolerance || ) {
//                    handle = false;
//                } else {
//                    v.setY(targetY);
//                }
                float usedtargetY = Math.max(boundryUp, Math.min(targetY, boundryDown));
                if (targetY != usedtargetY) {
                    handle = false;
                }
                v.setY(usedtargetY);
            }
            if (offsetX != 0.0f) {
                v.setX(v.getX() + offsetX);
            }
        }
        return handle;
    }

    @Override
    protected int getCurrentScrollX(float startOffset) {
        float out;
        if (isAppState()) {
            out = (mNumAppsPages - 1) * getMeasuredWidth() * startOffset;
        } else {
            out = (mNumWidgetPages - 1) * getMeasuredWidth() * startOffset + (mNumAppsPages + 2) * getMeasuredWidth();
        }
        return (int) out;
    }
    
    @Override
    public void onDotClick(int page) {
        super.onDotClick(page);
        if (isAppState()) {
            page = Math.max(0, Math.min(page, mNumAppsPages - 1));
        } else {
            page = Math.max(0, Math.min(page, mNumWidgetPages - 1));
            page += mNumAppsPages + 2;
        }
        if (page != mCurrentPage) {
            snapToPage(page, Math.abs(page - mCurrentPage) * PAGE_SNAP_ANIMATION_DURATION);
        }
    }
    
    @Override
    protected void getVisiblePages(int[] range, int center) {
        range[0] = range[1] = range[2] = INVALID_PAGE;
        final int width = getMeasuredWidth();
        final boolean app = isAppState();
        int start = app ? 0 : (mNumAppsPages + 2) * width;
        int end = app ? mNumAppsPages * width : ((getPageCount() + 2) * width);
        int offset = app ? 0 : 2;
        if (center - width > start) {
            range[0] = (center - width) / width - offset; 
        }
        if (center >= start && center <= end) {
            range[1] = center / width - offset;
        }
        if (center + width < end) {
            range[2] = (center + width) / width - offset; 
        }
    }
}
