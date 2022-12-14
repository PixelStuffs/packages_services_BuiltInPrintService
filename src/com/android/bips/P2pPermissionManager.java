/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.bips;

import static android.Manifest.permission.NEARBY_WIFI_DEVICES;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.android.bips.ui.AddPrintersActivity;
import com.android.bips.ui.AddPrintersFragment;

/**
 * Manage Wi-Fi Direct permission requirements and state.
 */
public class P2pPermissionManager {
    private static final String TAG = P2pPermissionManager.class.getCanonicalName();
    private static final boolean DEBUG = false;

    private static final String PACKAGE_TAG = "package";
    private static final String CHANNEL_ID_CONNECTIONS = "connections";
    public static final int REQUEST_P2P_PERMISSION_CODE = 1000;
    public static final int REQUEST_P2P_PERMISSION_ENABLE = 1001;

    private static final String STATE_KEY = "state";
    private static final String PERMISSION_RATIONALE_KEY = "permissionRationale";

    private static final P2pPermissionRequest sFinishedRequest = () -> { };

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final NotificationManager mNotificationManager;

    public P2pPermissionManager(Context context) {
        mContext = context;
        mPrefs = mContext.getSharedPreferences(TAG, 0);
        mNotificationManager = mContext.getSystemService(NotificationManager.class);
    }

    /**
     * Reset any temporary modes.
     */
    public void reset() {
        if (getState() == State.TEMPORARILY_DISABLED) {
            setState(State.DENIED);
        }
    }

    /**
     * Update the current P2P permissions request state.
     */
    public void setState(State state) {
        if (DEBUG) Log.d(TAG, "State from " + mPrefs.getString(STATE_KEY, "?") + " to " + state);
        mPrefs.edit().putString(STATE_KEY, state.name()).apply();
    }

    /**
     * Return true if P2P features are enabled.
     */
    public boolean isP2pEnabled() {
        return getState() == State.ALLOWED;
    }

    /**
     * The user has made a permissions-related choice.
     */
    public void applyPermissionChange(boolean permanent) {
        closeNotification();
        if (hasP2pPermission()) {
            setState(State.ALLOWED);
        } else if (getState() != State.DISABLED) {
            if (permanent) {
                setState(State.DISABLED);
            } else {
                // Inform the user and don't try again for the rest of this session.
                setState(State.TEMPORARILY_DISABLED);
                Toast.makeText(mContext, R.string.wifi_direct_permission_rationale,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Return true if the user has granted P2P-related permission.
     */
    private boolean hasP2pPermission() {
        return mContext.checkSelfPermission(NEARBY_WIFI_DEVICES)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Request P2P permission from the user, until the user makes a selection or the returned
     * {@link P2pPermissionRequest} is closed.
     *
     * Note: if requested on behalf of an Activity, the Activity MUST call
     * {@link P2pPermissionManager#applyPermissionChange(boolean)} whenever
     * {@link Activity#onRequestPermissionsResult(int, String[], int[])} is called with code
     * {@link P2pPermissionManager#REQUEST_P2P_PERMISSION_CODE}.
     */
    public P2pPermissionRequest request(boolean explain, P2pPermissionListener listener) {
        // Check current permission level
        State state = getState();

        if (DEBUG) Log.d(TAG, "request() state=" + state);

        if (state.isTerminal()) {
            listener.onP2pPermissionComplete(state == State.ALLOWED);
            // Nothing to close because no listener registered.
            return sFinishedRequest;
        }

        SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
                listenForPreferenceChanges(listener);

        if (mContext instanceof Activity) {
            Activity activity = (Activity) mContext;
            if (!hasP2pPermission()) {
                boolean rationale = activity.shouldShowRequestPermissionRationale(
                        NEARBY_WIFI_DEVICES);
                if (explain && (rationale || mPrefs.getBoolean(PERMISSION_RATIONALE_KEY, false))) {
                    explain(activity, rationale);
                } else {
                    request(activity);
                }
                if (rationale) {
                    // Saving the permission to prefs to handle "Don't ask again" scenario
                    mPrefs.edit().putBoolean(PERMISSION_RATIONALE_KEY, true).apply();
                }
            }
        } else {
            showNotification();
        }

        return () -> {
            // Allow the caller to close this request if it no longer cares about the result
            closeNotification();
            mPrefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        };
    }

    /**
     * Use the activity to request permissions if possible.
     */
    private void request(Activity activity) {
        activity.requestPermissions(new String[]{NEARBY_WIFI_DEVICES},
                REQUEST_P2P_PERMISSION_CODE);
    }

    private void redirectToSettings(Activity activity) {
        Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts(PACKAGE_TAG, activity.getPackageName(), null));
        activity.startActivityForResult(settingsIntent, REQUEST_P2P_PERMISSION_ENABLE);
    }

    private void explain(Activity activity, boolean rationale) {
        // User denied, but asked us to use P2P, so explain and redirect to settings
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setMessage(mContext.getString(R.string.wifi_direct_permission_rationale))
                .setPositiveButton(R.string.fix, (dialog, which) -> {
                    if (rationale) {
                        request(activity);
                    } else {
                        redirectToSettings(activity);
                    }
                })
                .show();
    }

    private SharedPreferences.OnSharedPreferenceChangeListener listenForPreferenceChanges(
            P2pPermissionListener listener) {
        SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
                new SharedPreferences.OnSharedPreferenceChangeListener() {
                    @Override
                    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                                          String key) {
                        if (PERMISSION_RATIONALE_KEY.equals(key)) {
                            return;
                        }
                        State state = getState();
                        if (state.isTerminal() || state == State.DENIED) {
                            listener.onP2pPermissionComplete(state == State.ALLOWED);
                            mPrefs.unregisterOnSharedPreferenceChangeListener(this);
                        }
                    }
                };
        mPrefs.registerOnSharedPreferenceChangeListener(preferenceListener);
        return preferenceListener;
    }

    /**
     * Deliver a notification to the user.
     */
    private void showNotification() {
        // Because we are not in an activity create a notification to do the work
        mNotificationManager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID_CONNECTIONS, mContext.getString(R.string.connections),
                NotificationManager.IMPORTANCE_HIGH));

        Intent proceedIntent = new Intent(mContext, AddPrintersActivity.class);
        proceedIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        proceedIntent.putExtra(AddPrintersFragment.EXTRA_FIX_P2P_PERMISSION, true);
        PendingIntent proceedPendingIntent = PendingIntent.getActivity(mContext, 0,
                proceedIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action fixAction = new Notification.Action.Builder(
                Icon.createWithResource(mContext, R.drawable.ic_printservice),
                mContext.getString(R.string.fix), proceedPendingIntent).build();

        Intent cancelIntent = new Intent(mContext, BuiltInPrintService.class)
                .setAction(BuiltInPrintService.ACTION_P2P_PERMISSION_CANCEL);
        PendingIntent cancelPendingIndent = PendingIntent.getService(mContext,
                BuiltInPrintService.P2P_PERMISSION_REQUEST_ID, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent disableIntent = new Intent(mContext, BuiltInPrintService.class)
                .setAction(BuiltInPrintService.ACTION_P2P_DISABLE);
        PendingIntent disablePendingIndent = PendingIntent.getService(mContext,
                BuiltInPrintService.P2P_PERMISSION_REQUEST_ID, disableIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action disableAction = new Notification.Action.Builder(
                Icon.createWithResource(mContext, R.drawable.ic_printservice),
                mContext.getString(R.string.disable_wifi_direct), disablePendingIndent).build();

        Notification notification = new Notification.Builder(mContext, CHANNEL_ID_CONNECTIONS)
                .setSmallIcon(R.drawable.ic_printservice)
                .setContentText(mContext.getString(R.string.wifi_direct_problem))
                .setStyle(new Notification.BigTextStyle().bigText(
                        mContext.getString(R.string.wifi_direct_problem)))
                .setAutoCancel(true)
                .setContentIntent(proceedPendingIntent)
                .setDeleteIntent(cancelPendingIndent)
                .addAction(fixAction)
                .addAction(disableAction)
                .build();

        mNotificationManager.notify(BuiltInPrintService.P2P_PERMISSION_REQUEST_ID, notification);
    }

    /**
     * Return the current {@link State}.
     */
    public State getState() {
        // Look up stored state
        String stateString = mPrefs.getString(STATE_KEY, State.DENIED.name());
        State state = State.valueOf(stateString);

        if (state == State.DISABLED) {
            // If disabled do no further checking
            return state;
        }

        boolean allowed = hasP2pPermission();
        if (allowed && state != State.ALLOWED) {
            // Upgrade state if now allowed
            state = State.ALLOWED;
            setState(state);
        } else if (!allowed && state == State.ALLOWED) {
            state = State.DENIED;
            setState(state);
        }
        return state;
    }

    /**
     * Close any outstanding notification.
     */
    void closeNotification() {
        mNotificationManager.cancel(BuiltInPrintService.P2P_PERMISSION_REQUEST_ID);
    }

    /**
     * The current P2P permission request state.
     */
    public enum State {
        // The user has not granted permissions.
        DENIED,
        // The user did not grant permissions this time but try again next time.
        TEMPORARILY_DISABLED,
        // The user explicitly disabled or chose not to enable P2P.
        DISABLED,
        // Permissions are granted.
        ALLOWED;

        /** Return true if the user {@link State} is at a final permissions state. */
        public boolean isTerminal() {
            return this != DENIED;
        }
    }

    /**
     * Listener for determining when a P2P permission request is complete.
     */
    public interface P2pPermissionListener {
        /**
         * Invoked when it is known that the user has allowed or denied the permission request.
         */
        void onP2pPermissionComplete(boolean allowed);
    }

    /**
     * A closeable request for grant of P2P permissions.
     */
    public interface P2pPermissionRequest extends AutoCloseable {
        @Override
        void close();
    }
}
