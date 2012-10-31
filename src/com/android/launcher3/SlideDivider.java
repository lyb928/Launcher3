package com.android.launcher3;


import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.AnimatorSet.Builder;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
/**
 * 
 * @author yangbin.li
 *
 */
public class SlideDivider extends ViewGroup implements BackgroundAlphable {
	
	private static int sDotWidth;
	private static int sDotHeight;
	private static int sDotGap;
	private static final float ANIMATE_ALPHA_BEFORE = 0.5f;
	private static final float ANIMATE_SCALE_BEFORE = 1.0f;
	private static final float ANIMATE_ALPHA_END = 1.0f;
	private static final float ANIMATE_SCALE_END = 1.5f;
	private static final long ANIMATION_DURATION = 200;
	private static final int INVALID_PAGE = -1;
	private static final long DELAY_AMOUNT = 50;
	
	private View mLastOut;
	private CheckForLongPress mPendingCheckForLongPress;
	private DelayWatcher mDelayWatcher;
    private onDotClickListener mDotListener;
    private onDotDragListener mDragListener;
    private ViewConfiguration mConfiguration;
    private Drawable mTrack;
    private Drawable mThumb;
    private boolean mDragHasPerformed;
    private int mTargetPage;
    private int mLastMotionX;
    private int mCurrentItem;
    private float mBackgroundAlpha;
    private boolean mDragHasAccepted = true;
    private float mProgress;

	public SlideDivider(Context context, AttributeSet attrs) {
		super(context, attrs);
		mConfiguration = ViewConfiguration.get(context);
		Resources res = getResources();
		if (sDotWidth == 0 || sDotHeight == 0) {
		    sDotWidth = (int) (res.getDrawable(R.drawable.pageindicator).getIntrinsicWidth() * 0.75f);
	        sDotHeight = (int) (res.getDrawable(R.drawable.pageindicator).getIntrinsicHeight() * 0.75f);
	        sDotGap = res.getDimensionPixelSize(R.dimen.slideDivider_dot_gap);
        }
		mTrack = res.getDrawable(R.drawable.pageindicator_fastscroll_bar);
		mThumb = res.getDrawable(R.drawable.pageindicator_fastscroll_handle);
		setWillNotDraw(false);
	}
	
	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int heightMode = MeasureSpec.getMode(heightMeasureSpec);
		int widthMode = MeasureSpec.getMode(widthMeasureSpec);
		if(heightMode == MeasureSpec.UNSPECIFIED || widthMode == MeasureSpec.UNSPECIFIED){
			throw new IllegalArgumentException("a SlideDivider can not have UNSPECIFIED mode");
		}
		heightMeasureSpec = MeasureSpec.makeMeasureSpec((int) (sDotWidth * 1.5f), heightMode);
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		int count = getChildCount();
		if(count <= 0) return;
		int totalWidth = 0;
		final int top = (b - t - sDotHeight) / 2;
		for (int i = 0; i < count; i++) {
			View v = getChildAt(i);
			ViewGroup.LayoutParams params = v.getLayoutParams();
			if(!(params instanceof LayoutParams)){
				throw new IllegalArgumentException("a child of SlideDivider can only be SlideDivider.LayoutParms");
			}
			SlideDivider.LayoutParams myParams = (LayoutParams) params;
			totalWidth += myParams.width;
			if(myParams.position != 0){
				totalWidth += myParams.leftMargin;
			}
		}
		final int w = getMeasuredWidth();
		final int sideMargin = (w - totalWidth) / 2;
		int childLeft = sideMargin;
		for (int i = 0; i < count; i++) {
			View v = getChildAt(i);
			LayoutParams params = (LayoutParams) v.getLayoutParams();
			v.layout(childLeft, top, childLeft + params.width, top + params.height);
			childLeft += params.leftMargin + sDotWidth;
		}
		mTrack.setBounds(getVisuallyLeft(), top, getVisuallyRight(), top + sDotHeight);
	}
	
	public void add() {
		ImageView child = new ImageView(mContext);
		child.setAlpha(ANIMATE_ALPHA_BEFORE);
		child.setScaleX(ANIMATE_SCALE_BEFORE);
		child.setScaleType(ScaleType.FIT_XY);
		LayoutParams params = new LayoutParams(getChildCount(), false);
		child.setImageResource(R.drawable.pageindicator);
		addViewInLayout(child, getChildCount(), params, false);
	}
	
	public void remove() {
		if(getChildCount() <= 0){
			throw new RuntimeException("no divider to be removed");
		}
		removeView(getChildAt(getChildCount() - 1));
	}

	/**
	 * A SlideDivider can registe an Object as a dot click listener, which listen to whether we click the dot divider
	 * @param listener
	 */
	public void setOnDotClickListener(onDotClickListener listener) {
	    this.mDotListener = listener;
    }
	
	/**
	 * A SlideDivider can registe an Object as a drag listener, which listen to when we start drag operation
     * so we notified the detail infomation of dragging process
	 * @param listener
	 */
	public void setOnDotDragListener(onDotDragListener listener) {
	    this.mDragListener = listener;
	}
	
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mDragHasPerformed) {
            return true;
        }
        mLastMotionX = (int) ev.getX();
        final int left = getVisuallyLeft() - sDotGap;
        final int right = getVisuallyRight() + sDotGap;
        final int x = (int) ev.getX();
        boolean intercepted = false;
        if (mDotListener != null || mDragListener != null) {
            intercepted = true;
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                mDragHasAccepted = true;
                intercepted = left <= x && x <= right;
            }
        }
        return intercepted;
    }
    
    int getVisuallyLeft() {
        if (getChildCount() <= 0) {
            return 0;
        }
        return getChildAt(0).getLeft();
    }
    
    int getVisuallyRight() {
        if (getChildCount() <= 0) {
            return getMeasuredWidth();
        }
        return getChildAt(getChildCount() - 1).getRight();
    }
    
    private void determineDraggingStart(int x) {
        final int xDiff = Math.abs(x - mLastMotionX);
        if (xDiff >= mConfiguration.getScaledTouchSlop()) {
            if (mTargetPage != INVALID_PAGE) {
                mTargetPage = INVALID_PAGE;
            }
            if (mDragListener != null) {
                if (mDragListener.onDragStart(mProgress)) {
                    mDragHasAccepted = true;
                    onDragStartLocal(true);
                } else {
                    mDragHasAccepted = false;
                }
            } else {
                mDragHasAccepted = false;
            }
            if (mPendingCheckForLongPress != null) {
                removeCallbacks(mPendingCheckForLongPress);
            }
        }
    }

    private void onDragStartLocal(final boolean start) {
        mDragHasPerformed = start;
        ValueAnimator fade = new ValueAnimator().setDuration(ANIMATION_DURATION);
        if (start) {
            fade.setFloatValues(0f, 1f);
        } else {
            fade.setFloatValues(1f, 0f);
        }
        fade.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float percent = (Float) animation.getAnimatedValue();
                setBackgroundAlpha(percent);
            }
        });
        fade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                for (int i = 0; i < getChildCount(); i++) {
                    if (start) {
                        getChildAt(i).setVisibility(View.INVISIBLE);
                    } else {
                        getChildAt(i).setVisibility(View.VISIBLE);
                    }
                }
            }
        });
        fade.start();
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();
        mTargetPage = INVALID_PAGE;
        if (mPendingCheckForLongPress != null) {
            removeCallbacks(mPendingCheckForLongPress);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int count = getChildCount();
        if (count <= 0) {
            return false;
        }
        final int left = getVisuallyLeft();
        final int right = getVisuallyRight();
        final int visuallyWidth = right - left;
        final int x = (int) event.getX();
        mProgress = (float)(x - left) / visuallyWidth;
        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                if (!(left - sDotGap <= x && x <= right + sDotGap)) {
                    return false;
                }
                mDragHasAccepted = mDragListener != null;
                mLastMotionX = x;
                postCheckForLongClick();
                mTargetPage = Math.round(mProgress * (count - 1));
                invalidate();
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (mDragHasPerformed && mDragListener != null) {
                    mDragListener.onDragging(mProgress);
                } else if (mDragHasAccepted) {
                    determineDraggingStart(x);
                }
                offsetThumbRect();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDragHasPerformed && mDragListener != null) {
                    mDragListener.onDragStop();
                    onDragStartLocal(false);
                } else if (mTargetPage != INVALID_PAGE && mDotListener != null && !mDragHasPerformed) {
                    mDotListener.onDotClick(mTargetPage);
                    mTargetPage = INVALID_PAGE;
                }
                if (mPendingCheckForLongPress != null) {
                    removeCallbacks(mPendingCheckForLongPress);
                }
                break;
        }
        return true;
    }
    
    private void postCheckForLongClick() {
        mDragHasPerformed = false;

        if (mPendingCheckForLongPress == null) {
            mPendingCheckForLongPress = new CheckForLongPress();
        }
        mPendingCheckForLongPress.rememberWindowAttachCount();
        postDelayed(mPendingCheckForLongPress, ViewConfiguration.getLongPressTimeout());
    }
    
    public void setCurrentDivider(int pageNum){
		View out = null;
		if (pageNum == -1) {
			out = getChildAt(getChildCount() - 1);
			pageNum = getChildCount() - 1;
		} else if (pageNum == getChildCount()) {
			out = getChildAt(0);
			pageNum = 0;
		} else {
			out = getChildAt(pageNum);
		}
		LayoutParams params = out != null ? (LayoutParams) out.getLayoutParams() : null;
		if (params == null || params.occupy || pageNum < -1 || pageNum > getChildCount()) {
			return;//ignore
		} else if (mDragHasPerformed) {
            transform(out, false);
        } else {
            transform(out, true);
        }
		mCurrentItem = pageNum;
	}
    
    public int getCurrentItem() {
        return mCurrentItem;
    }

    private void transform(View out, boolean animate) {
        LayoutParams params = (LayoutParams) out.getLayoutParams();
        if (animate) {
            ValueAnimator alphaOut = ObjectAnimator.ofFloat(out, "alpha", ANIMATE_ALPHA_END);
            ValueAnimator lengthOut = ObjectAnimator.ofFloat(out, "scaleX", ANIMATE_SCALE_END);
            final AnimatorSet set = new AnimatorSet();
            set.setDuration(ANIMATION_DURATION);
            Builder builder = set.play(alphaOut);
            builder.with(lengthOut);
            params.occupy = true;
            if (mLastOut == null) {
                set.start();
                mLastOut = out;
                return;
            }
            ValueAnimator alphaIn = ObjectAnimator.ofFloat(mLastOut, "alpha", ANIMATE_ALPHA_BEFORE);
            ValueAnimator lengthIn = ObjectAnimator.ofFloat(mLastOut, "scaleX", ANIMATE_SCALE_BEFORE);
            builder.with(alphaIn);
            builder.with(lengthIn);
            if (mDelayWatcher == null) {
                mDelayWatcher = new DelayWatcher();
            }
            set.addListener(mDelayWatcher);
            set.setStartDelay(mDelayWatcher.getDelay());
            set.start();
            LayoutParams in = (LayoutParams) mLastOut.getLayoutParams();
            in.occupy = false;
            mLastOut = out;
        } else {
            if (mLastOut != null) {
                mLastOut.setScaleX(ANIMATE_SCALE_BEFORE);
                mLastOut.setAlpha(ANIMATE_ALPHA_BEFORE);
                LayoutParams in = (LayoutParams) mLastOut.getLayoutParams();
                in.occupy = false;
            }
            out.setScaleX(ANIMATE_SCALE_END);
            out.setAlpha(ANIMATE_ALPHA_END);
            params.occupy = true;
            mLastOut = out; 
        }
    }
    
//    private void offsetThumbRect(int page) {
//        int leftOffset = getVisuallyLeft() + page * (sDotWidth + sDotGap);
//        Rect trackRect = mTrack.getBounds();
//        mThumb.setBounds(leftOffset, trackRect.top, leftOffset + (sDotWidth + sDotGap), trackRect.bottom);
//    }
    
    private void offsetThumbRect() {
        if (!mDragHasPerformed) {
            return;
        }
        float progress = mProgress;
        progress = Math.max(0f, Math.min(progress, 1f));
        Rect trackBound = mTrack.getBounds();
        int trackWidth = trackBound.right - trackBound.left - (sDotWidth + sDotGap);
        int leftOffset = (int) (trackWidth * progress) + trackBound.left;
        mThumb.setBounds(leftOffset, trackBound.top, leftOffset + (sDotWidth + sDotGap), trackBound.bottom);
        invalidate();
    }
	
	/**
	 * set the total number of this divider
	 * @param totalNum indicated pagecount
	 */
	public void setTotalPages(int totalNum) {
		final int childCount = getChildCount();
		if (childCount == totalNum || totalNum < 0) {
			return;
		}
		int delta = Math.abs(totalNum - childCount);
		for (int i = 0; i < delta; i++) {
			if (childCount < totalNum) {
				add();
			} else {
				remove();
			}
		}
		requestLayout();
		invalidate();
	}
	
	@Override
	public ViewGroup.LayoutParams generateLayoutParams (
			AttributeSet attrs) {
		return new LayoutParams(mContext, attrs);
	}
	
	@Override
	protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
		return new LayoutParams(p);
	}
	
	@Override
	protected boolean checkLayoutParams(android.view.ViewGroup.LayoutParams p) {
		return super.checkLayoutParams(p) && p instanceof LayoutParams;
	}
	
	private class LayoutParams extends MarginLayoutParams {
		
		public int position;
		public boolean occupy;

		private void init() {
			this.leftMargin = sDotGap;
			width = sDotWidth;
			height = sDotHeight;
		}
		public LayoutParams(ViewGroup.LayoutParams params) {
			super(params);
			init();
		}
		public LayoutParams(Context c, AttributeSet attrs) {
			super(c, attrs);
			init();
		}
		public LayoutParams(int position, boolean occupy) {
			super(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			this.position = position;
			this.occupy = occupy;
			init();
		}
	}
	
	/**
	 * Callback that notify client when dot was tapped
	 * @author yangbin.li
	 */
	interface onDotClickListener {
	    /**
	     * this hook will be invoked if a "dot" is clicked
	     * @param page which dot was clicked, it determined by how much child items the SlideDivider has
	     */
	    void onDotClick(int page);
	}
	
	/**
	 * Callback that notify client the situation of drag operation
	 * @author yangbin.li
	 */
	interface onDotDragListener {
	    /**
	     * This hook will notify client dragging process level has changed
	     * @param progress The current progress level. This should be in the range 0 and 1, but also may be cross the border
	     */
	    void onDragging(float progress);
	    /**
         * This hook will be invoked if the drag operation has been prepared to start and ask client for acception
         * @param startOffset drag progress before it really start
         * @return true if you accept this requestion, otherwise negative
         */
	    boolean onDragStart(float startOffset);
	    /**
         * This hook will notify client dragging has been stopped
         */
	    void onDragStop();
	}
	
	private class CheckForLongPress implements Runnable {
        private int mOriginalWindowAttachCount;

        public void run() {
            if ((mParent != null) && hasWindowFocus()
                    && mOriginalWindowAttachCount == getWindowAttachCount()
                    && !mDragHasPerformed && mDragListener != null) {
                if (mDragListener.onDragStart(mProgress)) {
                    onDragStartLocal(true);
                } else {
                    mDragHasAccepted = false;
                }
            }
        }

        public void rememberWindowAttachCount() {
            mOriginalWindowAttachCount = getWindowAttachCount();
        }
    }
	
	private class DelayWatcher extends AnimatorListenerAdapter {
        private int mAnimatingCount;
        @Override
        public void onAnimationEnd(Animator animation) {
            mAnimatingCount--;
        }

        @Override
        public void onAnimationStart(Animator animation) {
            mAnimatingCount++;
        }
        
        public long getDelay() {
            return DELAY_AMOUNT * mAnimatingCount;
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (mBackgroundAlpha > 0.0f) {
            mTrack.setAlpha((int) (mBackgroundAlpha * 255));
            mThumb.setAlpha((int) (mBackgroundAlpha * 255));
            mTrack.draw(canvas);
            mThumb.draw(canvas);
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
}
