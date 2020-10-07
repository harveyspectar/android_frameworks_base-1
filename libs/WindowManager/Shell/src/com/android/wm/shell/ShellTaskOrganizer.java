/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import android.app.ActivityManager.RunningTaskInfo;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.SurfaceControl;
import android.window.ITaskOrganizerController;
import android.window.TaskOrganizer;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Unified task organizer for all components in the shell.
 * TODO(b/167582004): may consider consolidating this class and TaskOrganizer
 */
public class ShellTaskOrganizer extends TaskOrganizer {

    private static final String TAG = "ShellTaskOrganizer";

    /**
     * Callbacks for when the tasks change in the system.
     */
    public interface TaskListener {
        default void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {}
        default void onTaskInfoChanged(RunningTaskInfo taskInfo) {}
        default void onTaskVanished(RunningTaskInfo taskInfo) {}
        default void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {}
    }

    private final SparseArray<TaskListener> mListenerByWindowingMode = new SparseArray<>();

    // Keeps track of all the tasks reported to this organizer (changes in windowing mode will
    // require us to report to both old and new listeners)
    private final SparseArray<Pair<RunningTaskInfo, SurfaceControl>> mTasks = new SparseArray<>();

    // TODO(shell-transitions): move to a more "global" Shell location as this isn't only for Tasks
    private final Transitions mTransitions;

    public ShellTaskOrganizer(SyncTransactionQueue syncQueue, TransactionPool transactionPool,
            ShellExecutor mainExecutor, ShellExecutor animExecutor) {
        this(null, syncQueue, transactionPool, mainExecutor, animExecutor);
    }

    @VisibleForTesting
    ShellTaskOrganizer(ITaskOrganizerController taskOrganizerController,
            SyncTransactionQueue syncQueue, TransactionPool transactionPool,
            ShellExecutor mainExecutor, ShellExecutor animExecutor) {
        super(taskOrganizerController);
        addListener(new FullscreenTaskListener(syncQueue), WINDOWING_MODE_FULLSCREEN);
        mTransitions = new Transitions(this, transactionPool, mainExecutor, animExecutor);
        if (Transitions.ENABLE_SHELL_TRANSITIONS) registerTransitionPlayer(mTransitions);
    }

    /**
     * Adds a listener for tasks in a specific windowing mode.
     */
    public void addListener(TaskListener listener, int... windowingModes) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Add listener for modes=%s listener=%s",
                Arrays.toString(windowingModes), listener);
        for (int winMode : windowingModes) {
            if (mListenerByWindowingMode.get(winMode) != null) {
                throw new IllegalArgumentException("Listener for winMode=" + winMode
                        + " already exists");
            }
            mListenerByWindowingMode.put(winMode, listener);

            // Notify the listener of all existing tasks in that windowing mode
            for (int i = mTasks.size() - 1; i >= 0; i--) {
                Pair<RunningTaskInfo, SurfaceControl> data = mTasks.valueAt(i);
                int taskWinMode = data.first.configuration.windowConfiguration.getWindowingMode();
                if (taskWinMode == winMode) {
                    listener.onTaskAppeared(data.first, data.second);
                }
            }
        }
    }

    /**
     * Removes a registered listener.
     */
    public void removeListener(TaskListener listener) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Remove listener=%s", listener);
        final int index = mListenerByWindowingMode.indexOfValue(listener);
        if (index == -1) {
            Log.w(TAG, "No registered listener found");
            return;
        }
        mListenerByWindowingMode.removeAt(index);
    }

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Task appeared taskId=%d",
                taskInfo.taskId);
        mTasks.put(taskInfo.taskId, new Pair<>(taskInfo, leash));
        final TaskListener listener = mListenerByWindowingMode.get(getWindowingMode(taskInfo));
        if (listener != null) {
            listener.onTaskAppeared(taskInfo, leash);
        }
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Task info changed taskId=%d",
                taskInfo.taskId);
        final Pair<RunningTaskInfo, SurfaceControl> data = mTasks.get(taskInfo.taskId);
        final int winMode = getWindowingMode(taskInfo);
        final int prevWinMode = getWindowingMode(data.first);
        mTasks.put(taskInfo.taskId, new Pair<>(taskInfo, data.second));
        if (prevWinMode != -1 && prevWinMode != winMode) {
            // TODO: We currently send vanished/appeared as the task moves between win modes, but
            //       we should consider adding a different mode-changed callback
            TaskListener listener = mListenerByWindowingMode.get(prevWinMode);
            if (listener != null) {
                listener.onTaskVanished(taskInfo);
            }
            listener = mListenerByWindowingMode.get(winMode);
            if (listener != null) {
                SurfaceControl leash = data.second;
                listener.onTaskAppeared(taskInfo, leash);
            }
        } else {
            final TaskListener listener = mListenerByWindowingMode.get(winMode);
            if (listener != null) {
                listener.onTaskInfoChanged(taskInfo);
            }
        }
    }

    @Override
    public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Task root back pressed taskId=%d",
                taskInfo.taskId);
        final TaskListener listener = mListenerByWindowingMode.get(getWindowingMode(taskInfo));
        if (listener != null) {
            listener.onBackPressedOnTaskRoot(taskInfo);
        }
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Task vanished taskId=%d",
                taskInfo.taskId);
        final int prevWinMode = getWindowingMode(mTasks.get(taskInfo.taskId).first);
        mTasks.remove(taskInfo.taskId);
        final TaskListener listener = mListenerByWindowingMode.get(prevWinMode);
        if (listener != null) {
            listener.onTaskVanished(taskInfo);
        }
    }

    private int getWindowingMode(RunningTaskInfo taskInfo) {
        return taskInfo.configuration.windowConfiguration.getWindowingMode();
    }
}
