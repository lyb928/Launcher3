/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.MotionEvent;

/**
 * Specific {@link AppWidgetHost} that creates our {@link LauncherAppWidgetHostView}
 * which correctly captures all long-press events. This ensures that users can
 * always pick up and move widgets.
 */
public class LauncherAppWidgetHost extends AppWidgetHost {
    public LauncherAppWidgetHost(Context context, int hostId) {
        super(context, hostId);
    }

    @Override
    protected AppWidgetHostView onCreateView(Context context, int appWidgetId,
            AppWidgetProviderInfo appWidget) {
        if ("com.android.widget.weather".equals(appWidget.provider.getPackageName())) {
            return new LauncherAppWidgetHostViewSpecial(context);
        }
        return new LauncherAppWidgetHostView(context);
    }

    @Override
    public void stopListening() {
        super.stopListening();
        if(LauncherLog.DEBUG) LauncherLog.d("LauncherAppWidgetHost", "(LauncherAppWidgetHost)stopListening");
        clearViews();
    }
    
    private class LauncherAppWidgetHostViewSpecial extends LauncherAppWidgetHostView {
        private GestureDetector mGestureDetector;
        
        public LauncherAppWidgetHostViewSpecial(Context context) {
            super(context);
            mGestureDetector = new GestureDetector(context, new SimpleOnGestureListener(){
                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                        float velocityY) {
                    if (velocityY > 400) {
                        getContext().sendBroadcast(new Intent("com.android.launcher3.timer.animate_next"));
                    } else if (velocityY < 400) {
                        getContext().sendBroadcast(new Intent("com.android.launcher3.timer.animate_previous"));
                    }
                    return true;
                }
            });
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            mGestureDetector.onTouchEvent(event);
            return super.onTouchEvent(event);
        }
        
    }
}
