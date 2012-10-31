package com.android.launcher3;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

/**
 * @author yangbin.li
 */
public class AllAppsEater extends DeleteDropTarget {

	private int mHoverColor;
	private boolean mCapSwitch;
	private float mSwingDegree;
	private float mUpandDownNormalize;
	private boolean mDelete;
	
	private Paint mHoverPaint;
	private Resources mRes;
	private Movie mMovie;

    private Drawable mDeleteDrawable;
    private Drawable mCap;
    private static Launcher sLauncher;
    private static float sCapUpMax;
	private static final long ANIMATION_DURATION_INOUT = 150;
	private static final int ANIMATION_DURATION_DELETE = 250;
	private static final long ANIMATION_DELAY_SWING = 100;
	
	public AllAppsEater(Context context, AttributeSet attrs) {
		super(context, attrs);
		mRes = context.getResources();
		sCapUpMax = mRes.getDimension(R.dimen.garbage_cap_up);
		mCap = mRes.getDrawable(R.drawable.hotseat_icon_delete_lid);
		mDeleteDrawable = mRes.getDrawable(R.drawable.hotseat_icon_delete);
		mMovie = new Movie();
		ValueAnimator swing = ValueAnimator.ofFloat(0f, 15f, -15f, 10f, -10f, 5f, -5f, 0f);
        ValueAnimator down = ValueAnimator.ofFloat(1f, 0f);
        ValueAnimator up = ValueAnimator.ofFloat(0f, 1f);
		initAnimators(swing, down, up);
		mMovie.play(up, 0.6f).before(down, 0.5f).withAll(swing);
		mMovie.setMovieListener(new Movie.MovieListenerAdapter(){
			@Override
			public void onMovieBegin() {
				super.onMovieBegin();
				mSwingDegree = 0.0f;
				mUpandDownNormalize = 0.0f;
			}
			@Override
			public void onMovieOver() {
				super.onMovieOver();
				rollBack(R.drawable.hotseat_icon_delete);
			}
		});
	}

	private void initAnimators(ValueAnimator swing, ValueAnimator down, ValueAnimator up) {
		swing.addUpdateListener(new AnimatorUpdateListener() {
			@Override
			public void onAnimationUpdate(ValueAnimator animation) {
				mSwingDegree = (Float) animation.getAnimatedValue();
				invalidate();
			}
		});
		swing.setInterpolator(new AccelerateDecelerateInterpolator());
		swing.setStartDelay(ANIMATION_DELAY_SWING);
		down.addUpdateListener(new AnimatorUpdateListener() {
			@Override
			public void onAnimationUpdate(ValueAnimator animation) {
				mUpandDownNormalize = (Float) animation.getAnimatedValue();
				invalidate();
			}
		});
		down.setInterpolator(new DecelerateInterpolator());
		up.setInterpolator(new AccelerateInterpolator());
		up.setStartDelay(ANIMATION_DELAY_SWING);
		up.addUpdateListener(new AnimatorUpdateListener() {
			@Override
			public void onAnimationUpdate(ValueAnimator animation) {
				mUpandDownNormalize = (Float) animation.getAnimatedValue();
				invalidate();
			}
		});
	}
	
	private void rollBack(int id) {
		Drawable b = mRes.getDrawable(id);
		setCompoundDrawablesWithIntrinsicBounds(null, b, null, null);
		capOpen(false);
		mSwingDegree = 0.0f;
		mUpandDownNormalize = 0.0f;
	}
	
	@Override
	protected void onFinishInflate() {
		mHoverPaint = new Paint();
		mHoverColor = mRes.getColor(R.color.delete_target_hover_tint);
		mHoverPaint.setColorFilter(new PorterDuffColorFilter(mHoverColor,
				PorterDuff.Mode.SRC_ATOP));
		mHoverPaint.setAlpha(125);
		mOriginalTextColor = getTextColors();
	}

	@Override
	public void onDragStart(DragSource source, Object info, int dragAction) {
//		mRotate.set(getCompoundDrawables()[1].getBounds());
		capOpen(false);
		mDelete = false;
		if (mMovie != null && mMovie.isRunning()) {
			mMovie.cut();
		}
		upAndDown(true);
	}
	/**
	 * the garbage image out and in animation implementation
	 * @param upordown true up, otherwise down
	 */
	private void upAndDown(boolean upordown) {
	    if (mDelete) {
            return;
        }
		ObjectAnimator moveDown = ObjectAnimator.ofFloat(this, "translationY",
				getHeight(), 0);
		if (upordown) {
			moveDown.addListener(new AnimatorListenerAdapter() {
				@Override
				public void onAnimationStart(Animator animation) {
						rollBack(R.drawable.hotseat_icon_delete);
						setText("");
				}
			});
		} else {
			moveDown.addListener(new AnimatorListenerAdapter() {
				@Override
				public void onAnimationStart(Animator animation) {
						rollBack(R.drawable.all_apps_button_icon);
						setText(R.string.all_apps_button_label);
				}
			});
		}
		moveDown.setInterpolator(new DecelerateInterpolator());
		moveDown.setDuration(ANIMATION_DURATION_INOUT);
		moveDown.start();
	}

	@Override
	public boolean isDropEnabled() {
		return true;
	}

	@Override
	public void onDragEnd() {
		upAndDown(false);
	}

	@Override
	public void onDragEnter(DragObject dragObject) {
		dragObject.dragView.setPaint(mHoverPaint);
		Drawable b = mRes.getDrawable(R.drawable.hotseat_icon_delete_can);
		setCompoundDrawablesWithIntrinsicBounds(null, b, null, null);
//		setTextColor(mHoverColor);
		capOpen(true);
		mDelete = false;
		if (mMovie.isRunning()) {
			mMovie.cut();
		}
		mMovie.action();
		sLauncher.getWorkspace().onDragGarbage(true);
	}

	@Override
	public void onDragExit(DragObject dragObject) {
		dragObject.dragView.setPaint(null);
		if (mMovie.isRunning()) {
			mMovie.cut();
		}
		capOpen(false);
		if (dragObject.dragComplete) {
			mDelete = true;
			Drawable b = mRes.getDrawable(R.drawable.hotseat_delete_fill);
			setCompoundDrawablesWithIntrinsicBounds(null, b, null, null);
			ValueAnimator delete = ValueAnimator.ofFloat(1f, 0.6f, 1f, 0.6f, 1.0f);
			delete.setDuration(ANIMATION_DURATION_DELETE * 3);
			delete.addUpdateListener(new AnimatorUpdateListener() {
				@Override
				public void onAnimationUpdate(ValueAnimator animation) {
					float scale = (Float) animation.getAnimatedValue();
					setScaleX(scale);
					setScaleY(scale);
				}
			});
			delete.addListener(new AnimatorListenerAdapter() {
				@Override
				public void onAnimationEnd(Animator animation) {
					rollBack(R.drawable.all_apps_button_icon);
					setText(R.string.all_apps_button_label);
				}
			});
			delete.start();
		}
		sLauncher.getWorkspace().onDragGarbage(false);
	}

	@Override
	protected void animateToTrashAndCompleteDrop(final DragObject d) {
		DragLayer dragLayer = sLauncher.getDragLayer();
		Rect from = new Rect();
		Rect to = new Rect();
		dragLayer.getViewRectRelativeToSelf(d.dragView, from);
		dragLayer.getViewRectRelativeToSelf(this, to);

		int width = mDeleteDrawable.getIntrinsicWidth();
		int height = mDeleteDrawable.getIntrinsicHeight();
		to.set(to.left + getPaddingLeft(), to.top + getPaddingTop(), to.left
				+ getPaddingLeft() + width, to.bottom);

		// Center the destination rect about the trash icon
		int xOffset = (int) -(d.dragView.getMeasuredWidth() - width) / 2;
		int yOffset = (int) -(d.dragView.getMeasuredHeight() - height) / 2;
		to.offset(xOffset, yOffset);
		Runnable onAnimationEndRunnable = new Runnable() {
			@Override
			public void run() {
				sLauncher.exitSpringLoadedDragMode();
				completeDrop(d);
			}
		};
		dragLayer
				.animateView(d.dragView, from, to, 0.1f, 0.1f,
						ANIMATION_DURATION_DELETE,
						new DecelerateInterpolator(2),
						new DecelerateInterpolator(1.5f),
						onAnimationEndRunnable, false);
	}
	/**
	 * cap animation implementation
	 */
	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		if (mCapSwitch) {
			int compoundPaddingLeft = getCompoundPaddingLeft();
			int compoundPaddingRight = getCompoundPaddingRight();
			int hspace = mRight - mLeft - compoundPaddingRight - compoundPaddingLeft;
			Drawable current = getCompoundDrawables()[1];
			int drawableWidthTop = current.getBounds().width();
			mCap.setBounds(current.getBounds());
			canvas.save();
            canvas.translate(mScrollX + compoundPaddingLeft + (hspace - drawableWidthTop) / 2,
                    mScrollY + mPaddingTop - mUpandDownNormalize * sCapUpMax);
            canvas.rotate(mSwingDegree, mCap.getBounds().width() * 0.5f, mCap.getBounds().height() * 0.5f);
            mCap.draw(canvas);
            canvas.restore();
		}
	}
	
	private void capOpen(boolean open) {
        mCapSwitch = open;
        invalidate();
    }

	public void setup(Launcher launcher) {
		sLauncher = launcher;
	}
	
	@Override
	public void setCompoundDrawablesWithIntrinsicBounds(Drawable left, Drawable top,
	        Drawable right, Drawable bottom) {
	    int size = mRes.getDimensionPixelSize(R.dimen.app_icon_size);
	    if (left != null) {
	        left.setBounds(0, 0, size, size);
        }
	    if (top != null) {
	        top.setBounds(0, 0, size, size);
        }
	    if (right != null) {
	        right.setBounds(0, 0, size, size);
        }
	    if (bottom != null) {
	        bottom.setBounds(0, 0, size, size);
        }
	    setCompoundDrawables(left, top, right, bottom);
	}
	
	static class CellLayoutEater extends ImageView {
	    private boolean mCapSwitch;
	    private float mSwingDegree;
	    private float mUpandDownNormalize;
	    
	    private Movie mMovie;
	    
	    public CellLayoutEater(Context context) {
	        super(context);
	        mMovie = new Movie();
	        ValueAnimator swing = ValueAnimator.ofFloat(0f, 15f, -15f, 10f, -10f, 5f, -5f, 0f);
	        ValueAnimator down = ValueAnimator.ofFloat(1f, 0f);
	        ValueAnimator up = ValueAnimator.ofFloat(0f, 1f);
	        initAnimators(swing, down, up);
	        mMovie.play(up, 0.6f).before(down, 0.5f).withAll(swing);
	        setImageDrawable(getResources().getDrawable(R.drawable.hotseat_icon_delete));
	    }

	    private void initAnimators(ValueAnimator swing, ValueAnimator down, ValueAnimator up) {
	        swing.addUpdateListener(new AnimatorUpdateListener() {
	            @Override
	            public void onAnimationUpdate(ValueAnimator animation) {
	                mSwingDegree = (Float) animation.getAnimatedValue();
	                invalidate();
	            }
	        });
	        swing.setInterpolator(new AccelerateDecelerateInterpolator());
	        swing.setStartDelay(ANIMATION_DELAY_SWING);
	        down.addUpdateListener(new AnimatorUpdateListener() {
	            @Override
	            public void onAnimationUpdate(ValueAnimator animation) {
	                mUpandDownNormalize = (Float) animation.getAnimatedValue();
	                invalidate();
	            }
	        });
	        up.setStartDelay(ANIMATION_DELAY_SWING);
	        up.addUpdateListener(new AnimatorUpdateListener() {
	            @Override
	            public void onAnimationUpdate(ValueAnimator animation) {
	                mUpandDownNormalize = (Float) animation.getAnimatedValue();
	                invalidate();
	            }
	        });
	    }
	    
	    private void capOpen(boolean open) {
	        mCapSwitch = open;
	        invalidate();
	    }
	    
	    public void onDragEnter() {
	        Drawable red = getResources().getDrawable(R.drawable.hotseat_icon_delete_can);
	        setImageDrawable(red);
	        capOpen(true);
	        if (mMovie.isRunning()) {
                mMovie.cut();
            }
	        mMovie.action();
	        mSwingDegree = mUpandDownNormalize = 0f;
	    }
	    
	    @Override
	    public void invalidate() {
	        if (sLauncher != null) {
	            sLauncher.getHotseat().invalidate(getLeft(), getTop(), getRight(), getBottom());
            }
	    }
	    
	    public void onDragExit() {
	        Drawable delete = getResources().getDrawable(R.drawable.hotseat_icon_delete);
	        setImageDrawable(delete);
	        capOpen(false);
	        if (mMovie.isRunning()) {
                mMovie.cut();
            }
	        mSwingDegree = mUpandDownNormalize = 0f;
	    }
	    
	    public void onCellLayoutEat() {
            ValueAnimator delete = ValueAnimator.ofFloat(1f, 0.6f, 1f, 0.6f, 1.0f);
            delete.setDuration(ANIMATION_DURATION_DELETE * 3);
            delete.addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float scale = (Float) animation.getAnimatedValue();
                    invalidate();
                    setFastScaleX(scale);
                    setFastScaleY(scale);
                    invalidate();
                }
            });
            delete.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    setImageResource(R.drawable.hotseat_icon_delete);
                }
                @Override
                public void onAnimationStart(Animator animation) {
                    setImageResource(R.drawable.hotseat_delete_fill);
                }
            });
            delete.start();
	    }
	    
	    @Override
	    protected void onDraw(Canvas canvas) {
	        super.onDraw(canvas);
	        if (mCapSwitch) {
	            Drawable cap = getResources().getDrawable(R.drawable.hotseat_icon_delete_lid);
	            cap.setBounds(0, 0, getWidth(), getHeight());
	            canvas.save();
	            canvas.translate(mScrollX + mPaddingLeft, mScrollY + mPaddingTop - mUpandDownNormalize * sCapUpMax);
	            canvas.rotate(mSwingDegree, getWidth() * 0.5f, getHeight() * 0.5f);
	            cap.draw(canvas);
	            canvas.restore();
	        }
	    }
	}
}
