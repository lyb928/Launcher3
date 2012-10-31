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

import java.util.ArrayList;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AndroidRuntimeException;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.android.launcher3.AllAppsEater.CellLayoutEater;
import com.android.launcher3.DragController.DragListener;

public class Hotseat extends FrameLayout implements BackgroundAlphable {
    private static final String TAG = "Hotseat";
    private static final int sAllAppsButtonRank = 4; // In the middle of the dock, replaced by right-most position

    private Launcher mLauncher;
    private DragController mController;
    private CellLayout mContent;
    private AllAppsEater mGarbage;
    private Drawable mBackground;
    private CellLayoutEater mCellLayoutEater;

    private int mCellCountX;
    private int mCellCountY;
    private float mBackgroundAlpha;
    private boolean mIsLandscape;
	private Rect mBackgroundRect;
	private float mBackgroundTranslationY;

    public Hotseat(Context context) {
        this(context, null);
    }

    public Hotseat(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Hotseat(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.Hotseat, defStyle, 0);
        mCellCountX = a.getInt(R.styleable.Hotseat_cellCountX, -1);
        mCellCountY = a.getInt(R.styleable.Hotseat_cellCountY, -1);
        mIsLandscape = context.getResources().getConfiguration().orientation ==
            Configuration.ORIENTATION_LANDSCAPE;
        mBackground = getResources().getDrawable(R.drawable.bottom_bar);
        mBackgroundRect = new Rect();
        setWillNotDraw(false);
        mCellLayoutEater = new CellLayoutEater(context);
    }

    public void setBackgroundAlpha(float backgroundAlpha) {
		this.mBackgroundAlpha = backgroundAlpha;
		invalidate();
	}

	@Override
	public void setBackgroundDrawable(Drawable d) {
//		super.setBackgroundDrawable(d);
		mBackground = d;
		invalidate();
	}

	@Override
	public void setBackgroundResource(int resid) {
//		super.setBackgroundResource(resid);
		mBackground = getResources().getDrawable(resid);
		invalidate();
	}
	
	public void setBackgroundTranslationY(float translationY) {
		mBackgroundRect.set(mScrollX, (int)(mScrollY + translationY),
				mScrollX + getWidth(), (int)(mScrollY + getHeight() + translationY));
		mBackgroundTranslationY = translationY;
		invalidate();
	}

	public float getBackgroundAlpha() {
		return mBackgroundAlpha;
	}

	public float getBackgroundTranslationY() {
		return mBackgroundTranslationY;
	}

	public void setup(Launcher launcher, DragController dragController) {
        mLauncher = launcher;
        setOnKeyListener(new HotseatIconKeyEventListener());
        mController = dragController;
        checkAndInsert(mGarbage);
    }

    CellLayout getLayout() {
        return mContent;
    }

    /* Get the orientation invariant order of the item in the hotseat for persistence. */
    int getOrderInHotseat(int x, int y) {
        return mIsLandscape ? (mContent.getCountY() - y - 1) : x;
    }
    /* Get the orientation specific coordinates given an invariant order in the hotseat. */
    int getCellXFromOrder(int rank) {
        return mIsLandscape ? 0 : rank;
    }
    int getCellYFromOrder(int rank) {
        return mIsLandscape ? (mContent.getCountY() - (rank + 1)) : 0;
    }
    public static boolean isAllAppsButtonRank(int rank) {
        return rank == sAllAppsButtonRank;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (mCellCountX < 0) mCellCountX = LauncherModel.getCellCountX();
        if (mCellCountY < 0) mCellCountY = LauncherModel.getCellCountY();
        mContent = (CellLayout) findViewById(R.id.layout);
        mContent.setGridSize(mCellCountX, mCellCountY);

        resetLayout();
    }

    void resetLayout() {
        mContent.removeAllViewsInLayout();

        // Add the Apps button
        Context context = getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        AllAppsEater allAppsButton = (AllAppsEater)
                inflater.inflate(R.layout.allapps_garbage, mContent, false);
        allAppsButton.setup(mLauncher);
        allAppsButton.setCompoundDrawablesWithIntrinsicBounds(null,
                context.getResources().getDrawable(R.drawable.all_apps_button_icon), null, null);
        allAppsButton.setContentDescription(context.getString(R.string.all_apps_button_label));
        allAppsButton.setText(R.string.all_apps_button_label);//added by
        allAppsButton.setCompoundDrawablePadding(0);////////leeyb
        allAppsButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (mLauncher != null &&
                    (event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) {
                    mLauncher.onTouchDownAllAppsButton(v);
                }
                return false;
            }
        });

        allAppsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (LauncherLog.DEBUG) {
                    LauncherLog.d(TAG, "Click on all apps view on hotseat: mLauncher = " + mLauncher);
                }
                if (mLauncher != null) {
                    mLauncher.onClickAllAppsButton(v);
                }
            }
        });
        
        // Note: We do this to ensure that the hotseat is always laid out in the orientation of
        // the hotseat in order regardless of which orientation they were added
        int x = getCellXFromOrder(sAllAppsButtonRank);
        int y = getCellYFromOrder(sAllAppsButtonRank);
        mContent.addViewToCellLayout(allAppsButton, -1, 0, new CellLayout.LayoutParams(x,y,1,1),
                true);
        checkAndInsert(allAppsButton);
    }
    /**
     * Replace droptarget or draglistener in list by newest instance, if not found, insert it
     * added by leeyb
     * @param allAppsButton
     */
    private void checkAndInsert(AllAppsEater allAppsButton) {
		// TODO Auto-generated method stub
    	if (allAppsButton == null) throw new AndroidRuntimeException("inserted targets cannot be null");
    	allAppsButton.setLauncher(mLauncher);
    	if (mController != null) {
        	ArrayList<DropTarget> targets = mController.getDropTargets();
        	ArrayList<DragListener> listeners = mController.getListeners();
        	boolean insertTarget = false;
        	boolean insertListener = false;
        	for (DropTarget dropTarget : targets) {
				if (dropTarget instanceof AllAppsEater) {
					int index = targets.indexOf(dropTarget);
					targets.set(index, allAppsButton);
					insertTarget = true;
				}
			}
        	for (DragListener listener : listeners) {
				if (listener instanceof AllAppsEater) {
					int index = listeners.indexOf(listener);
					listeners.set(index, allAppsButton);
					insertListener = true;
				}
			}
        	if (!insertTarget) {
				mController.addDropTarget(allAppsButton);
			}
        	if (!insertListener) {
				mController.addDragListener(allAppsButton);
			}
        } else {
        	mGarbage = allAppsButton;//wait here for setup
        }
	}
    
	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		mBackgroundRect.set(mScrollX, (int)(mScrollY + mBackgroundTranslationY)
				, mScrollX + w, (int)(mScrollY + h + mBackgroundTranslationY));
	}

	/**
	 * Support background animation, added by leeyb
	 */
	@Override
	protected void dispatchDraw(Canvas canvas) {
		if (mBackgroundAlpha > 0) {
			mBackground.setBounds(mBackgroundRect);
			mBackground.setAlpha((int) (mBackgroundAlpha * 255));
			mBackground.draw(canvas);
		}
		if (mLauncher.getWorkspace().haveBeenPreviews()) {
//			Paint textPaint = new Paint();
//			textPaint.setDither(true);
//			textPaint.setColor(Color.WHITE);
//			textPaint.setShadowLayer(4.0f, 0.0f, 2.0f, 0xFF000000);
//			String hint = getResources().getString(R.string.workspace_previews_hint);
//			textPaint.setTextSize(23f);
//			float length = textPaint.measureText(hint);
//			canvas.drawText(hint, (getMeasuredWidth() - length) * 0.5f,
//					(getMeasuredHeight() - textPaint.getTextSize()) * 0.7f, textPaint);
		    int lookupH = mCellLayoutEater.getMeasuredHeight();
		    int lookupW = mCellLayoutEater.getMeasuredWidth();
		    if (lookupH <= 0 || lookupW <= 0) {
		        Drawable been = mGarbage.getCompoundDrawables()[1];
                int widthMeasureSpec = MeasureSpec.makeMeasureSpec(been.getIntrinsicWidth()
                        , MeasureSpec.EXACTLY);
                int heightMeasureSpec = MeasureSpec.makeMeasureSpec(been.getIntrinsicHeight()
                        , MeasureSpec.EXACTLY);
                mCellLayoutEater.measure(widthMeasureSpec, heightMeasureSpec);
                mCellLayoutEater.layout((getMeasuredWidth() - mCellLayoutEater.getMeasuredWidth()) / 2,
                        (int)((getMeasuredHeight() + mBackgroundTranslationY - mCellLayoutEater.getMeasuredHeight()) / 2),
                        (getMeasuredWidth() - mCellLayoutEater.getMeasuredWidth()) / 2 + mCellLayoutEater.getMeasuredWidth(),
                        (int)((getMeasuredHeight() + mBackgroundTranslationY - mCellLayoutEater.getMeasuredHeight()) / 2 + mCellLayoutEater.getMeasuredHeight()));
            }
		    canvas.save();
		    canvas.translate(mCellLayoutEater.getLeft(), mCellLayoutEater.getTop());
		    mCellLayoutEater.draw(canvas);
		    canvas.restore();
		} else {
			super.dispatchDraw(canvas);
		}
	}

	/**
	 * intercept all touch when previews of workspace happens
	 */
	@Override
	public boolean onInterceptTouchEvent(MotionEvent event) {
		return mLauncher.getWorkspace().isPreviewsState();
	}

	/**
	 * Override alpha and scale's getter and setter to seperates background's alpha from itself
	 */
    @Override
    public void setAlpha(float alpha) {
        mContent.setAlpha(alpha);
    }
    @Override
    public float getAlpha() {
        return mContent.getAlpha();
    }
    @Override
    public void setScaleX(float scale) {
        mContent.setScaleX(scale);
    }
    @Override
    public void setScaleY(float scale) {
        mContent.setScaleY(scale);
    }
    @Override
    public float getScaleX() {
        return mContent.getScaleX();
    }
    @Override
    public float getScaleY() {
        return mContent.getScaleY();
    }
    /**
     * Let its children(CellLayout) handle alpha's transformation
     */
    @Override
    protected boolean onSetAlpha(int alpha) {
        return true;
    }

    @Override
    public void getHitRect(Rect outRect) {
        super.getHitRect(outRect);
        outRect.offset(0, (int) mBackgroundTranslationY);
    }
    
    CellLayoutEater getCellLayoutEater() {
        return mCellLayoutEater;
    }
}
