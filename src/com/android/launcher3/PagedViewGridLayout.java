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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;

/**
 * The grid based layout used strictly for the widget/wallpaper tab of the AppsCustomize pane
 */
public class PagedViewGridLayout extends GridLayout implements Page, BackgroundAlphable {
    static final String TAG = "PagedViewGridLayout";

    private int mCellCountX;
    private int mCellCountY;
    private float mBackgroundAlpha;
    private Drawable mBackground;
    private Runnable mOnLayoutListener;

    public PagedViewGridLayout(Context context, int cellCountX, int cellCountY) {
        super(context, null, 0);
        mCellCountX = cellCountX;
        mCellCountY = cellCountY;
        setWillNotDraw(false);
//        setDrawingCacheEnabled(true);
//        setDrawingCacheQuality(DRAWING_CACHE_QUALITY_LOW);
        setAlwaysDrawnWithCacheEnabled(false);
//        setChildrenDrawnWithCacheEnabled(false);
    }

    int getCellCountX() {
        return mCellCountX;
    }

    int getCellCountY() {
        return mCellCountY;
    }

    /**
     * Clears all the key listeners for the individual widgets.
     */
    public void resetChildrenOnKeyListeners() {
        int childCount = getChildCount();
        for (int j = 0; j < childCount; ++j) {
            getChildAt(j).setOnKeyListener(null);
        }
    }

    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // PagedView currently has issues with different-sized pages since it calculates the
        // offset of each page to scroll to before it updates the actual size of each page
        // (which can change depending on the content if the contents aren't a fixed size).
        // We work around this by having a minimum size on each widget page).
        int widthSpecSize = Math.min(getSuggestedMinimumWidth(),
                MeasureSpec.getSize(widthMeasureSpec));
        int widthSpecMode = MeasureSpec.EXACTLY;
        super.onMeasure(MeasureSpec.makeMeasureSpec(widthSpecSize, widthSpecMode),
                heightMeasureSpec);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mOnLayoutListener = null;
    }

    public void setOnLayoutListener(Runnable r) {
        mOnLayoutListener = r;
    }

    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mOnLayoutListener != null) {
            mOnLayoutListener.run();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean result = super.onTouchEvent(event);
        int count = getPageChildCount();
        if (count > 0) {
            // We only intercept the touch if we are tapping in empty space after the final row
            View child = getChildOnPageAt(count - 1);
            int bottom = child.getBottom();
            result = result || (event.getY() < bottom);
        }
        return result;
    }

    void destroyHardwareLayer() {
        if (LauncherLog.DEBUG_DRAW || LauncherLog.DEBUG_TEMP) {
            LauncherLog.d(TAG, "destroyHardwareLayer: this = " + this);
        }
        Thread.dumpStack();
        setLayerType(LAYER_TYPE_NONE, null);
    }

    void createHardwareLayer() {
        if (LauncherLog.DEBUG_DRAW || LauncherLog.DEBUG_TEMP) {
            LauncherLog.d(TAG, "cretaeHardwareLayer: this = " + this);
        }
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override
    public void removeAllViewsOnPage() {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "removeAllViewsOnPage: this = " + this);
        }
        removeAllViews();
        destroyHardwareLayer();
    }

    @Override
    public void removeViewOnPageAt(int index) {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "removeViewOnPageAt: index = " + index);
        }
        removeViewAt(index);
    }

    @Override
    public int getPageChildCount() {
        return getChildCount();
    }

    @Override
    public View getChildOnPageAt(int i) {
        return getChildAt(i);
    }

    @Override
    public int indexOfChildOnPage(View v) {
        return indexOfChild(v);
    }

    @Override
	public void draw(Canvas canvas) {
    	if (mBackgroundAlpha > 0.0f) {
    		if (mBackground == null) {
				mBackground = getResources().getDrawable(R.drawable.homescreen_quick_view_bg);
			}
    		mBackground.setBounds(mScrollX, mScrollY,
    				getMeasuredWidth() + mScrollX, getMeasuredHeight() + mScrollY);
			mBackground.setAlpha((int) (mBackgroundAlpha * 255));
			canvas.save();
			canvas.scale(1.0f, 1.20f, mScrollX + getMeasuredWidth() * 0.5f, mScrollY + getMeasuredHeight() * 0.5f);
			mBackground.draw(canvas);
			canvas.restore();
		}
		super.draw(canvas);
	}
    
    @Override
    public void setBackgroundAlpha(float alpha) {
    	mBackgroundAlpha = alpha;
    	invalidate();
	}
    
    @Override
    public float getBackgroundAlpha() {
    	return mBackgroundAlpha;
	}
    
    @Override
    protected void setChildrenDrawingCacheEnabled(boolean enabled) {
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View view = getChildAt(i);
            view.setDrawingCacheEnabled(enabled);
            // Update the drawing caches
            if (!view.isHardwareAccelerated()) {
                view.buildDrawingCache(true);
            }
        }
    }

	public static class LayoutParams extends FrameLayout.LayoutParams {
        public LayoutParams(int width, int height) {
            super(width, height);
        }
    }
}
