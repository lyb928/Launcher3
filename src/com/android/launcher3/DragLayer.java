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
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * A ViewGroup that coordinates dragging across its descendants
 */
public class DragLayer extends FrameLayout {
	static final String TAG = "Launcher.DragLayer";
    private DragController mDragController;
    private int[] mTmpXY = new int[2];

    private int mXDown, mYDown;
    private Launcher mLauncher;

    // Variables relating to resizing widgets
    private final ArrayList<AppWidgetResizeFrame> mResizeFrames =
            new ArrayList<AppWidgetResizeFrame>();
    private AppWidgetResizeFrame mCurrentResizeFrame;

    // Variables relating to animation of views after drop
    private TimeInterpolator mCubicEaseOutInterpolator = new DecelerateInterpolator(/*1.5f*/);
//    private View mDropView = null;
//
//    private int[] mDropViewPos = new int[2];
//    private float mDropViewScale;
//    private float mDropViewAlpha;
    /**
     * a DragLayer has not only one dragging item, but many of them
     */
    private ArrayList<DragHolder> mDragHolders = new ArrayList<DragLayer.DragHolder>();
    private boolean mHoverPointClosesFolder = false;
    private Rect mHitRect = new Rect();
    private int mWorkspaceIndex = -1;
    private int mQsbIndex = -1;
    private Drawable mPagePanelBackground;
    
    /**
     * Unit controlled by DragLayer, it will be drawn one by one in dispatchDraw
     * @author yangbin.li
     *
     */
    static class DragHolder {
    	View mDropView = null;
    	int[] mDropViewPos = new int[2];
    	float mDropViewScale;
    	float mDropViewAlpha;
		@Override
		public boolean equals(Object o) {
			if (o instanceof DragView) {
				return mDropView == o;
			}
			return super.equals(o);
		}
    }

    /**
     * Used to create a new DragLayer from XML.
     *
     * @param context The application's context.
     * @param attrs The attributes set containing the Workspace's customization values.
     */
    public DragLayer(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Disable multitouch across the workspace/all apps/customize tray
        setMotionEventSplittingEnabled(false);
        setChildrenDrawingOrderEnabled(true);
        mPagePanelBackground = getResources().getDrawable(R.drawable.pageindicator_fastscroll_panel);
    }
    
    public void setup(Launcher launcher, DragController controller) {
        mLauncher = launcher;
        mDragController = controller;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {        
        boolean handled = mDragController.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
        if (LauncherLog.DEBUG_KEY) {
            LauncherLog.d(TAG, "dispatchKeyEvent: keycode = " + event.getKeyCode() + ",action = "
                    + event.getAction() + ",handled = " + handled);
        }
        return handled;
    }

    private boolean isEventOverFolderTextRegion(Folder folder, MotionEvent ev) {
        getDescendantRectRelativeToSelf(folder.getEditTextRegion(), mHitRect);
        if (mHitRect.contains((int) ev.getX(), (int) ev.getY())) {
            return true;
        }
        return false;
    }

    private boolean isEventOverFolder(Folder folder, MotionEvent ev) {
        getDescendantRectRelativeToSelf(folder, mHitRect);
        if (mHitRect.contains((int) ev.getX(), (int) ev.getY())) {
            return true;
        }
        return false;
    }

    private boolean handleTouchDown(MotionEvent ev, boolean intercept) {
        Rect hitRect = new Rect();
        int x = (int) ev.getX();
        int y = (int) ev.getY();
        if (LauncherLog.DEBUG_MOTION) {
            LauncherLog.d(TAG, "handleTouchDown: x = " + x + ",y = " + y + ",intercept = " + intercept
                    + ",mXDown = " + mXDown + ",mYDown = " + mYDown);
        }
        
        for (AppWidgetResizeFrame child: mResizeFrames) {
            child.getHitRect(hitRect);
            if (hitRect.contains(x, y)) {
                if (child.beginResizeIfPointInRegion(x - child.getLeft(), y - child.getTop())) {
                    mCurrentResizeFrame = child;
                    mXDown = x;
                    mYDown = y;
                    requestDisallowInterceptTouchEvent(true);
                    return true;
                }
            }
        }

        Folder currentFolder = mLauncher.getWorkspace().getOpenFolder();
        if (currentFolder != null && !mLauncher.isFolderClingVisible() && intercept) {
            if (currentFolder.isEditingName()) {
                if (!isEventOverFolderTextRegion(currentFolder, ev)) {
                    currentFolder.dismissEditingName();
                    return true;
                }
            }

            getDescendantRectRelativeToSelf(currentFolder, hitRect);
            if (!isEventOverFolder(currentFolder, ev)) {
                mLauncher.closeFolder();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (LauncherLog.DEBUG_MOTION) {
            LauncherLog.d(TAG, "onInterceptTouchEvent: action = " + ev.getAction() + ",x = " + ev.getX()
                    + ",y = " + ev.getY());
        }
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            if (handleTouchDown(ev, true)) {
                return true;
            }
        }
        clearAllResizeFrames();
        boolean handle = mDragController.onInterceptTouchEvent(ev);
        Log.v("TouchDetector", "onInterceptTouchEvent DragLayer event:" + ev.getAction() + "\thandle:" + handle);
        return handle;
    }

    @Override
    public boolean onInterceptHoverEvent(MotionEvent ev) {
        Folder currentFolder = mLauncher.getWorkspace().getOpenFolder();
        if (currentFolder == null) {
            return false;
        } else {
            if (AccessibilityManager.getInstance(mContext).isTouchExplorationEnabled()) {
                final int action = ev.getAction();
                boolean isOverFolder;
                switch (action) {
                    case MotionEvent.ACTION_HOVER_ENTER:
                        isOverFolder = isEventOverFolder(currentFolder, ev);
                        if (!isOverFolder) {
                            sendTapOutsideFolderAccessibilityEvent(currentFolder.isEditingName());
                            mHoverPointClosesFolder = true;
                            return true;
                        } else if (isOverFolder) {
                            mHoverPointClosesFolder = false;
                        } else {
                            return true;
                        }
                    case MotionEvent.ACTION_HOVER_MOVE:
                        isOverFolder = isEventOverFolder(currentFolder, ev);
                        if (!isOverFolder && !mHoverPointClosesFolder) {
                            sendTapOutsideFolderAccessibilityEvent(currentFolder.isEditingName());
                            mHoverPointClosesFolder = true;
                            return true;
                        } else if (isOverFolder) {
                            mHoverPointClosesFolder = false;
                        } else {
                            return true;
                        }
                }
            }
        }
        return false;
    }

    private void sendTapOutsideFolderAccessibilityEvent(boolean isEditingName) {
        if (AccessibilityManager.getInstance(mContext).isEnabled()) {
            int stringId = isEditingName ? R.string.folder_tap_to_rename : R.string.folder_tap_to_close;
            AccessibilityEvent event = AccessibilityEvent.obtain(
                    AccessibilityEvent.TYPE_VIEW_FOCUSED);
            onInitializeAccessibilityEvent(event);
            event.getText().add(mContext.getString(stringId));
            AccessibilityManager.getInstance(mContext).sendAccessibilityEvent(event);
        }
    }

    @Override
    public boolean onHoverEvent(MotionEvent ev) {
        // If we've received this, we've already done the necessary handling
        // in onInterceptHoverEvent. Return true to consume the event.
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean handled = false;
        int action = ev.getAction();

        int x = (int) ev.getX();
        int y = (int) ev.getY();

        if (LauncherLog.DEBUG_MOTION) {
            LauncherLog.d(TAG, "onTouchEvent: action = " + action + ",x = " + x + ",y = " + y
                    + ",mXDown = " + mXDown + ",mYDown = " + mYDown + ",mCurrentResizeFrame = "
                    + mCurrentResizeFrame);
        }
        
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                if (handleTouchDown(ev, false)) {
                    if (LauncherLog.DEBUG_MOTION) {
                        LauncherLog.d(TAG, "onTouchEvent: handleTouchDown return true.");
                    }
                    return true;
                }
            }
        }

        if (mCurrentResizeFrame != null) {
            handled = true;
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    mCurrentResizeFrame.visualizeResizeForDelta(x - mXDown, y - mYDown);
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    mCurrentResizeFrame.commitResizeForDelta(x - mXDown, y - mYDown);
                    mCurrentResizeFrame = null;
            }
        }
        if (handled || ev.getPointerCount() > 1/*drag operation can not be interrupt by multi-touch*/) return true;
        return mDragController.onTouchEvent(ev);
    }

    /**
     * Determine the rect of the descendant in this DragLayer's coordinates
     *
     * @param descendant The descendant whose coordinates we want to find.
     * @param r The rect into which to place the results.
     * @return The factor by which this descendant is scaled relative to this DragLayer.
     */
    public float getDescendantRectRelativeToSelf(View descendant, Rect r) {
        mTmpXY[0] = 0;
        mTmpXY[1] = 0;
        float scale = getDescendantCoordRelativeToSelf(descendant, mTmpXY, true);
        r.set(mTmpXY[0], mTmpXY[1],
                mTmpXY[0] + descendant.getWidth(), mTmpXY[1] + descendant.getHeight());
        return scale;
    }

    public void getLocationInDragLayer(View child, int[] loc) {
        loc[0] = 0;
        loc[1] = 0;
        getDescendantCoordRelativeToSelf(child, loc, true);
    }

    /**
     * Given a coordinate relative to the descendant, find the coordinate in this DragLayer's
     * coordinates.
     *
     * @param descendant The descendant to which the passed coordinate is relative.
     * @param coord The coordinate that we want mapped.
     * @param matrix calculation will concat matrix of descendent itself if true
     * @return The factor by which this descendant is scaled relative to this DragLayer.
     */
    public float getDescendantCoordRelativeToSelf(View descendant, int[] coord, boolean matrix) {
        float scale = 1.0f;
        float[] pt = {coord[0], coord[1]};
        if (matrix) {
        	descendant.getMatrix().mapPoints(pt);
		}
        scale *= descendant.getScaleX();
        pt[0] += descendant.getLeft();
        pt[1] += descendant.getTop();
        ViewParent viewParent = descendant.getParent();
        while (viewParent instanceof View && viewParent != this) {
            final View view = (View)viewParent;
            if (matrix) {
            	descendant.getMatrix().mapPoints(pt);
    		}
            scale *= view.getScaleX();
            pt[0] += view.getLeft() - view.getScrollX();
            pt[1] += view.getTop() - view.getScrollY();
            viewParent = view.getParent();
        }
        coord[0] = (int) Math.round(pt[0]);
        coord[1] = (int) Math.round(pt[1]);
        return scale;
    }

    public void getViewRectRelativeToSelf(View v, Rect r) {
        int[] loc = new int[2];
        getLocationInWindow(loc);
        int x = loc[0];
        int y = loc[1];

        v.getLocationInWindow(loc);
        int vX = loc[0];
        int vY = loc[1];

        int left = vX - x;
        int top = vY - y;
        r.set(left, top, left + v.getMeasuredWidth(), top + v.getMeasuredHeight());
    }

    @Override
    public boolean dispatchUnhandledMove(View focused, int direction) {
        if (LauncherLog.DEBUG_KEY) {
            LauncherLog.d(TAG, "dispatchUnhandledMove: focused = " + focused + ",direction = " + direction);
        }
        return mDragController.dispatchUnhandledMove(focused, direction);
    }

    public static class LayoutParams extends FrameLayout.LayoutParams {
        public int x, y;
        public boolean customPosition = false;

        /**
         * {@inheritDoc}
         */
        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public void setWidth(int width) {
            this.width = width;
        }

        public int getWidth() {
            return width;
        }

        public void setHeight(int height) {
            this.height = height;
        }

        public int getHeight() {
            return height;
        }

        public void setX(int x) {
            this.x = x;
        }

        public int getX() {
            return x;
        }

        public void setY(int y) {
            this.y = y;
        }

        public int getY() {
            return y;
        }
    }
    
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            final FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) child.getLayoutParams();
            if (flp instanceof LayoutParams) {
                final LayoutParams lp = (LayoutParams) flp;
                if (lp.customPosition) {
                    child.layout(lp.x, lp.y, lp.x + lp.width, lp.y + lp.height);
                }
            }
        }
    }

    public void clearAllResizeFrames() {
        if (LauncherLog.DEBUG_DRAG) {
            LauncherLog.d(TAG, "clearAllResizeFrames: mResizeFrames size = " + mResizeFrames.size());
        }
        if (mResizeFrames.size() > 0) {
            for (AppWidgetResizeFrame frame: mResizeFrames) {
                removeView(frame);
            }
            mResizeFrames.clear();
        }
    }

    public boolean hasResizeFrames() {
        return mResizeFrames.size() > 0;
    }

    public boolean isWidgetBeingResized() {
        return mCurrentResizeFrame != null;
    }

    public void addResizeFrame(ItemInfo itemInfo, LauncherAppWidgetHostView widget,
            CellLayout cellLayout) {
        AppWidgetResizeFrame resizeFrame = new AppWidgetResizeFrame(getContext(),
                itemInfo, widget, cellLayout, this);

        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "addResizeFrame: itemInfo = " + itemInfo + ",widget = " + widget
                    + ",resizeFrame = " + resizeFrame);
        }
        
        LayoutParams lp = new LayoutParams(-1, -1);
        lp.customPosition = true;

        addView(resizeFrame, lp);
        mResizeFrames.add(resizeFrame);

        resizeFrame.snapToWidget(false);
    }

    public void animateViewIntoPosition(DragView dragView, final View child) {
        animateViewIntoPosition(dragView, child, null);
    }

    public void animateViewIntoPosition(DragView dragView, final int[] pos, float scale,
            Runnable onFinishRunnable) {
        Rect r = new Rect();
        getViewRectRelativeToSelf(dragView, r);
        final int fromX = r.left;
        final int fromY = r.top;

        animateViewIntoPosition(dragView, fromX, fromY, pos[0], pos[1], scale,
                onFinishRunnable, true, -1);
    }

    public void animateViewIntoPosition(DragView dragView, final View child,
            final Runnable onFinishAnimationRunnable) {
        animateViewIntoPosition(dragView, child, -1, onFinishAnimationRunnable);
    }

    public void animateViewIntoPosition(DragView dragView, final View child, int duration,
            final Runnable onFinishAnimationRunnable) {
        ((CellLayoutChildren) child.getParent()).measureChild(child);
        CellLayout.LayoutParams lp =  (CellLayout.LayoutParams) child.getLayoutParams();

        Rect r = new Rect();
        getViewRectRelativeToSelf(dragView, r);

        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "animateViewIntoPosition: dragView = " + dragView + ",r = " + r
                    + ",lp.x = " + lp.x + ",lp.y = " + lp.y);
        }
        
        int coord[] = new int[2];
        coord[0] = lp.x;
        coord[1] = lp.y;
        // Since the child hasn't necessarily been laid out, we force the lp to be updated with
        // the correct coordinates (above) and use these to determine the final location
        float scale = getDescendantCoordRelativeToSelf((View) child.getParent(), coord, duration < 0);
        int toX = coord[0];
        int toY = coord[1];
        scale = 1.0f;
        if (child instanceof TextView) {
            TextView tv = (TextView) child;
            Drawable d = tv.getCompoundDrawables()[1];

            // Center in the y coordinate about the target's drawable
            toY += Math.round(scale * tv.getPaddingTop());
            toY -= (dragView.getHeight() - (int) Math.round(scale * d.getIntrinsicHeight())) / 2;
            // Center in the x coordinate about the target's drawable
            toX -= (dragView.getWidth() - Math.round(scale * child.getMeasuredWidth())) / 2;
        } else if (child instanceof FolderIcon) {
            // Account for holographic blur padding on the drag view
            toY -= HolographicOutlineHelper.MAX_OUTER_BLUR_RADIUS / 2;
            // Center in the x coordinate about the target's drawable
            toX -= (dragView.getWidth() - Math.round(scale * child.getMeasuredWidth())) / 2;
        } else {
            toY -= (Math.round(scale * (dragView.getHeight() - child.getMeasuredHeight()))) / 2;
            toX -= (Math.round(scale * (dragView.getWidth()
                    - child.getMeasuredWidth()))) / 2;
        }

        final int fromX = r.left;
        final int fromY = r.top;
        child.setVisibility(INVISIBLE);
        child.setAlpha(0);
        Runnable onCompleteRunnable = new Runnable() {
            public void run() {
                child.setVisibility(VISIBLE);
                ObjectAnimator oa = ObjectAnimator.ofFloat(child, "alpha", 0f, 1f);
                oa.setDuration(60);
                oa.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        if (onFinishAnimationRunnable != null) {
                            onFinishAnimationRunnable.run();
                        }
                    }
                });
                oa.start();
            }
        };
        animateViewIntoPosition(dragView, fromX, fromY, toX, toY, scale,
                onCompleteRunnable, true, duration);
    }

    private void animateViewIntoPosition(final View view, final int fromX, final int fromY,
            final int toX, final int toY, float finalScale, Runnable onCompleteRunnable,
            boolean fadeOut, int duration) {
        Rect from = new Rect(fromX, fromY, fromX +
                view.getMeasuredWidth(), fromY + view.getMeasuredHeight());
        Rect to = new Rect(toX, toY, toX + view.getMeasuredWidth(), toY + view.getMeasuredHeight());
        animateView(view, from, to, 1f, finalScale, duration, null, null, onCompleteRunnable, fadeOut);
    }
    
    /**
     * added by leeyb
     * @param dragView
     * @param from
     * @param to
     * @param finalScale
     * @param onCompleteRunnable
     * @param fadeOut false means this dragView wouldn't vanished automatically, 
     * you must remove it by your followed code
     */
    public void animateDragViewIntoPosition(final View dragView, final int[] from, final int[] to,
	    float finalScale, Runnable onCompleteRunnable, boolean fadeOut) {
    	
    	Rect fromR = new Rect(from[0], from[1], from[0] +
    			dragView.getMeasuredWidth(), from[1] + dragView.getMeasuredHeight());
        Rect toR = new Rect(to[0], to[1], to[0] + dragView.getMeasuredWidth(), to[1] + dragView.getMeasuredHeight());
        animateView(dragView, fromR, toR, fadeOut ? 0.0f : 1.0f, finalScale, -1, null, null, onCompleteRunnable, fadeOut);
	}

    /**
     * This method animates a view at the end of a drag and drop animation.
     *
     * modified by leeyb, duration < 0 && !fadeOut is a key that keep this view persistent visible
     * as long as you don't remove it from mDragHolders
     *
     * @param view The view to be animated. This view is drawn directly into DragLayer, and so
     *        doesn't need to be a child of DragLayer.
     * @param from The initial location of the view. Only the left and top parameters are used.
     * @param to The final location of the view. Only the left and top parameters are used. This
     *        location doesn't account for scaling, and so should be centered about the desired
     *        final location (including scaling).
     * @param finalAlpha The final alpha of the view, in case we want it to fade as it animates.
     * @param finalScale The final scale of the view. The view is scaled about its center.
     * @param duration The duration of the animation.
     * @param motionInterpolator The interpolator to use for the location of the view.
     * @param alphaInterpolator The interpolator to use for the alpha of the view.
     * @param onCompleteRunnable Optional runnable to run on animation completion.
     * @param fadeOut Whether or not to fade out the view once the animation completes. If true,
     *        the runnable will execute after the view is faded out.
     */
    public void animateView(final View view, final Rect from, final Rect to, final float finalAlpha,
            final float finalScale, int duration, final Interpolator motionInterpolator,
            final Interpolator alphaInterpolator, final Runnable onCompleteRunnable,
            final boolean fadeOut) {
        // Calculate the duration of the animation based on the object's distance
//        final float dist = (float) Math.sqrt(Math.pow(to.left - from.left, 2) +
//                Math.pow(to.top - from.top, 2));
        final Resources res = getResources();
//        final float maxDist = (float) res.getInteger(R.integer.config_dropAnimMaxDist);
        final boolean dragMode = duration < 0;

        // If duration < 0, this is a cue to compute the duration based on the distance
//        if (dragMode) {
//        	duration = (int) (dist / maxDist * res.getInteger(R.integer.config_workspaceSpringLoadTime));
//        } else {
        	duration = res.getInteger(R.integer.config_workspaceSpringLoadTime);
//        }


//        if (fadeOutAnim != null) {
//            fadeOutAnim.cancel();
//        }
        final DragHolder holder;
        if (mDragHolders.contains(view)) {
			holder = mDragHolders.get(mDragHolders.indexOf(view));
		} else {
			holder = new DragHolder();
			holder.mDropView = view;
			mDragHolders.add(holder);
		}
//        mDropView = view;
        final float initialAlpha = dragMode && !fadeOut ? 0.0f : view.getAlpha();
        final float initialScale = view.getScaleX();
        ValueAnimator dropAnim = new ValueAnimator();
        if (alphaInterpolator == null || motionInterpolator == null) {
            dropAnim.setInterpolator(mCubicEaseOutInterpolator);
        }

        dropAnim.setDuration(duration);
        dropAnim.setFloatValues(0.0f, 1.0f);
//        dropAnim.removeAllUpdateListeners();
        dropAnim.addUpdateListener(new AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                final float percent = (Float) animation.getAnimatedValue();
                // Invalidate the old position
                int width = view.getMeasuredWidth();
                int height = view.getMeasuredHeight();
                invalidate(holder.mDropViewPos[0], holder.mDropViewPos[1],
                		holder.mDropViewPos[0] + width, holder.mDropViewPos[1] + height);

                float alphaPercent = alphaInterpolator == null ? percent :
                        alphaInterpolator.getInterpolation(percent);
                float motionPercent = motionInterpolator == null ? percent :
                        motionInterpolator.getInterpolation(percent);
                holder.mDropViewPos[0] = from.left + (int) Math.round((to.left - from.left) * motionPercent);
                holder.mDropViewPos[1] = from.top + (int) Math.round((to.top - from.top) * motionPercent);
                holder.mDropViewScale = percent * finalScale + (1 - percent) * initialScale;
                holder.mDropViewAlpha = alphaPercent * finalAlpha + (1 - alphaPercent) * initialAlpha;
                invalidate(holder.mDropViewPos[0], holder.mDropViewPos[1],
                		holder.mDropViewPos[0] + width, holder.mDropViewPos[1] + height);
            }
        });
        dropAnim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                if (onCompleteRunnable != null) {
                    onCompleteRunnable.run();
                }
                if (fadeOut) {
                    fadeOutDragView(holder);
                } else if (!dragMode) {
					mDragHolders.remove(holder);
				}
            }
        });
//        dropAnim.setStartDelay(res.getInteger(R.integer.config_appsCustomizeWorkspaceAnimationStagger));
        dropAnim.start();
    }

    private void fadeOutDragView(final DragHolder holder) {
    	if (holder.mDropViewAlpha == 0) {
    		mDragHolders.remove(holder);
    		invalidate();
			return;
		}
    	ValueAnimator fadeOutAnim = new ValueAnimator();
        fadeOutAnim.setDuration(250);
        fadeOutAnim.setFloatValues(holder.mDropView.getAlpha(), 0f);
//        fadeOutAnim.removeAllUpdateListeners();
        fadeOutAnim.addUpdateListener(new AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                final float percent = (Float) animation.getAnimatedValue();
                holder.mDropViewAlpha = percent;
                int width = holder.mDropView.getMeasuredWidth();
                int height = holder.mDropView.getMeasuredHeight();
                invalidate(holder.mDropViewPos[0], holder.mDropViewPos[1],
                		holder.mDropViewPos[0] + width, holder.mDropViewPos[1] + height);
            }
        });
        fadeOutAnim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
            	mDragHolders.remove(holder);
            	invalidate();
            }
        });
        fadeOutAnim.start();
    }
    
    /**
     * Clear all dragViews that DragLayer have.  added by leeyb
     */
    void clearDragViews() {
        if (mDragHolders != null) {
            mDragHolders.clear();
            invalidate();
        }
	}

    @Override
    protected void onViewAdded(View child) {
        super.onViewAdded(child);
        updateChildIndices();
    }

    @Override
    protected void onViewRemoved(View child) {
        super.onViewRemoved(child);
        updateChildIndices();
    }

    private void updateChildIndices() {
        if (mLauncher != null) {
            mWorkspaceIndex = indexOfChild(mLauncher.getWorkspace());
            mQsbIndex = indexOfChild(mLauncher.getSearchBar());
        }
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int i) {
        // We don't want to prioritize the workspace drawing on top of the other children in
        // landscape for the overscroll event.
        if (LauncherApplication.isScreenLandscape(getContext())) {
            return super.getChildDrawingOrder(childCount, i);
        }

        if (mWorkspaceIndex == -1 || mQsbIndex == -1 || 
                mLauncher.getWorkspace().isDrawingBackgroundGradient()) {
            return i;
        }

        // This ensures that the workspace is drawn above the hotseat and qsb,
        // except when the workspace is drawing a background gradient, in which
        // case we want the workspace to stay behind these elements.
        if (i == mQsbIndex) {   
            return mWorkspaceIndex;
        } else if (i == mWorkspaceIndex) {
            return mQsbIndex;
        } else {
        	return i;
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mDragHolders != null && mDragHolders.size() > 0) {
            // We are animating an item that was just dropped on the home screen.
            // Render its View in the current animation position.
        	for (int i = 0; i < mDragHolders.size(); i++) {
				DragHolder holder = mDragHolders.get(i);
				canvas.save(Canvas.MATRIX_SAVE_FLAG);
	            final int xPos = holder.mDropViewPos[0] - holder.mDropView.getScrollX();
	            final int yPos = holder.mDropViewPos[1] - holder.mDropView.getScrollY();
	            int width = holder.mDropView.getMeasuredWidth();
	            int height = holder.mDropView.getMeasuredHeight();
	            canvas.translate(xPos, yPos);
	            canvas.translate((1 - holder.mDropViewScale) * width / 2, (1 - holder.mDropViewScale) * height / 2);
	            canvas.scale(holder.mDropViewScale, holder.mDropViewScale);
	            holder.mDropView.setAlpha(holder.mDropViewAlpha);
	            holder.mDropView.draw(canvas);
	            canvas.restore();
			}
        }
    }
	/**
	 * Get around the mScrollX and mScrollY calculation when rect offsets if args scroll is false,
	 * mScrollX of cell in CellLayout in official code was weird -->524249?
	 * added by leeyb
	 * @param descendant
	 * @param rect
	 * @param scroll
	 */
	public void offsetDescendantRectToMyCoords2(View descendant, Rect rect, boolean scroll) {
		if (descendant == this) {
            return;
        }
		RectF rf = new RectF(rect);
        ViewParent theParent = descendant.getParent();
        while ((theParent != null)
                && (theParent instanceof View)
                && (theParent != this)) {
        	//fix a bug in framework here, rect.offset(view.mLeft....), the args view cannot be the original descendant
        	descendant = (View) theParent;
        	theParent = descendant.getParent();
        	if (theParent == this) break;
        	descendant.getMatrix().mapRect(rf);
        	rf.offset(descendant.getLeft() - (scroll ? descendant.getScrollX() : 0),
        			descendant.getTop() - (scroll ? descendant.getScrollY() : 0));
        }
        if (theParent == this) {
        	descendant.getMatrix().mapRect(rf);
        	rf.offset(descendant.getLeft() - (scroll ? descendant.getScrollX() : 0),
        			descendant.getTop() - (scroll ? descendant.getScrollY() : 0));
        } else {
            throw new IllegalArgumentException("parameter must be a descendant of this view");
        }
        rect.set((int)rf.left, (int)rf.top, (int)rf.right, (int)rf.bottom);
	}
	
	private DragHolder mPageIndicator;
	
	void showOrHideIndicator(boolean show) {
	    ValueAnimator fade = new ValueAnimator().setDuration(200);
	    fade.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float percent = (Float) animation.getAnimatedValue();
                if (mPageIndicator != null) {
                    int left = mPageIndicator.mDropViewPos[0];
                    int top = mPageIndicator.mDropViewPos[1];
                    View panel = mPageIndicator.mDropView;
                    mPageIndicator.mDropViewAlpha = percent;
                    invalidate(left, top, left + panel.getMeasuredWidth(), top + panel.getMeasuredHeight());
                }
            }
        });
	    if (show) {
	        Drawable bg = mPagePanelBackground;
	        int left = (int) ((getMeasuredWidth() - bg.getIntrinsicWidth() * 1.75f) * 0.5f);
            float heightOffset = mLauncher.isAllAppsCustomizeOpen() ? 0.75f : 0.6f;
            int top = (int) (getMeasuredHeight() * heightOffset);
	        if (mPageIndicator == null || mPageIndicator.mDropView == null) {
	            mPageIndicator = new DragHolder();
	            PanelIndicator pi = new PanelIndicator(mLauncher);
	            
	            int heightMeasureSpec = MeasureSpec.makeMeasureSpec((int) (bg.getIntrinsicHeight() * 1.75f), MeasureSpec.EXACTLY);
	            int widthMeasureSpec = MeasureSpec.makeMeasureSpec((int) (bg.getIntrinsicWidth() * 1.75f), MeasureSpec.EXACTLY);
	            pi.measure(widthMeasureSpec, heightMeasureSpec);
	            
	            mPageIndicator.mDropView = pi;
	            mPageIndicator.mDropViewScale = 1.0f;
	        }
	        mPageIndicator.mDropViewPos[0] = left;
            mPageIndicator.mDropViewPos[1] = top;
            fade.setFloatValues(0f, 1f);
            fade.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mDragHolders.add(mPageIndicator);
                }
            });
            fade.start();
        } else if (mPageIndicator != null && !show) {
            fade.setFloatValues(1f, 0f);
            fade.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mDragHolders.remove(mPageIndicator);
                }
            });
            fade.start();
        }
	}
	
	void updateIndicator(int num) {
	    if (mPageIndicator != null) {
            PanelIndicator pi = (PanelIndicator) mPageIndicator.mDropView;
            String target = String.valueOf(num);
            if (pi.mDisplay == null || !pi.mDisplay.equals(target)) {
                pi.mDisplay = target;
                int left = mPageIndicator.mDropViewPos[0];
                int top = mPageIndicator.mDropViewPos[1];
                View panel = mPageIndicator.mDropView;
                invalidate(left, top, left + panel.getMeasuredWidth(), top + panel.getMeasuredHeight());
            }
        }
	}
	
	private class PanelIndicator extends View {
	    String mDisplay;
	    private Paint mPaint;
	    
        public PanelIndicator(Context context) {
            super(context);
            mPaint = new Paint();
            mPaint.setDither(true);
            mPaint.setColor(Color.WHITE);
            mPaint.setShadowLayer(4.0f, 0.0f, 2.0f, 0xFF000000);
            mPaint.setTextSize(getResources().getDimension(R.dimen.page_panel_textsize));
        }
        
        @Override
        protected void onDraw(Canvas canvas) {
            if (mPagePanelBackground != null) {
                mPagePanelBackground.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
                mPagePanelBackground.setAlpha((int) (getAlpha() * 255));
                mPagePanelBackground.draw(canvas);
            }
            if (mDisplay != null && mPaint != null) {
                mPaint.setAlpha((int) (getAlpha() * 255));
                float leftOffset = mPaint.measureText(mDisplay);
                canvas.drawText(mDisplay, (getMeasuredWidth() - leftOffset) * 0.5f, getMeasuredHeight() * 0.71f, mPaint);
            }
        }

        @Override
        protected boolean onSetAlpha(int alpha) {
            return true;
        }
	}
}
