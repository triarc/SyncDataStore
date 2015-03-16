package com.triarc.sync;

import java.io.File;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SyncStatusObserver;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * This class echoes a string called from JavaScript.
 */
public class SyncDataStore extends CordovaPlugin {
	private final static String TAG = "SyncDataStorePlugin";
	private BroadcastReceiver changeSetReceivedReceiver;
	private BroadcastReceiver startSyncReceiver;
	private BroadcastReceiver stopSyncReceiver;

	protected void registerReceivers() {
		webView.getContext().registerReceiver(changeSetReceivedReceiver,
				new IntentFilter(SyncAdapter.UPDATES_RECEIVED));
		webView.getContext().registerReceiver(this.startSyncReceiver,
				new IntentFilter(SyncAdapter.SYNC_START));
		webView.getContext().registerReceiver(stopSyncReceiver,
				new IntentFilter(SyncAdapter.SYNC_FINISHED));
	}

	@Override
	protected void pluginInitialize() {
		// TODO Auto-generated method stub
		super.pluginInitialize();
		this.changeSetReceivedReceiver = new BroadcastReceiver() {

			@SuppressLint("NewApi")
			@Override
			public void onReceive(Context context, Intent intent) {
				String stringExtra = intent
						.getStringExtra(SyncAdapter.PAYLOAD_NAME);
				String methodName = intent
						.getStringExtra(SyncAdapter.PAYLOAD_INJECT_METHOD);
				SyncDataStore.this.webView.evaluateJavascript(methodName + "('"
						+ stringExtra + "')", null);
			}
		};

		this.startSyncReceiver = new BroadcastReceiver() {
			@SuppressLint("NewApi")
			@Override
			public void onReceive(Context context, Intent intent) {
				SyncDataStore.this.webView
						.evaluateJavascript(
								"DispoClient.NotificationHub.injectSyncState('started')",
								null);
			}
		};
		this.stopSyncReceiver = new BroadcastReceiver() {
			@SuppressLint("NewApi")
			@Override
			public void onReceive(Context context, Intent intent) {
				SyncDataStore.this.webView
						.evaluateJavascript(
								"DispoClient.NotificationHub.injectSyncState('finished')",
								null);
			}
		};
		registerReceivers();
	}

	@Override
	public void onPause(boolean multitasking) {
		// TODO Auto-generated method stub
		super.onPause(multitasking);
		webView.getContext().unregisterReceiver(this.stopSyncReceiver);
		webView.getContext().unregisterReceiver(this.startSyncReceiver);
		webView.getContext().unregisterReceiver(this.changeSetReceivedReceiver);
	}

	@Override
	public void onResume(boolean multitasking) {
		super.onResume(multitasking);
		registerReceivers();
	}

	@Override
	public boolean execute(String action, JSONArray args,
			CallbackContext callbackContext) throws JSONException {
		if (action.equals("initialize")) {
			this.initialize(args.getString(0), args.getString(1),
					callbackContext);
		} else if (action.equals("register")) {
			if (args.length() == 1) {
				SyncUtils.CreateSyncAccount(this.webView.getContext(),
						args.getString(0));

				callbackContext.success();
				return true;
			} else {
				callbackContext.error("Wrong amount of arguments");
				return false;
			}

		} else if (action.equals("unRegister")) {
			this.unRegister(callbackContext);
		} else if (action.equals("requestSync")) {
			SyncUtils.TriggerRefresh();
			callbackContext.success();
			return true;
		}

		return true;
	}

	private void unRegister(CallbackContext callbackContext) {
		try {
			SyncUtils.DeleteAccount(this.webView.getContext());
			for (SyncTypeCollection syncTypeCollection : SyncAdapter
					.getSyncCollections(this.webView.getContext())) {
				for (SyncType syncType : syncTypeCollection.getTypes()) {
					try {
						File dbfile = this.webView.getContext()
								.getDatabasePath(syncType.getName());
						if (dbfile.exists())
							dbfile.delete();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}

			callbackContext.success();
		} catch (Exception e) {
			callbackContext.error(e.getMessage());
		}
	}

	private void initialize(final String webApiPath,
			final String serializedFields, final CallbackContext callbackContext) {
		final CordovaWebView webView = this.webView;
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				SyncUtils.SetWebApiPath(webView.getContext(), webApiPath);
				PreferenceManager
						.getDefaultSharedPreferences(webView.getContext())
						.edit()
						.putString(SyncAdapter.SYNC_FIELDS, serializedFields)
						.commit();
				callbackContext.success(); // Thread-safe.
			}
		});

	}
}