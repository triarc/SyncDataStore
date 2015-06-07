package com.triarc.sync;

import java.io.File;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.apache.cordova.PluginResult.Status;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SyncInfo;
import android.content.SyncStatusObserver;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.util.Log;
import android.webkit.ValueCallback;

/**
 * This class echoes a string called from JavaScript.
 */
public class SyncDataStore extends CordovaPlugin {
	private final static String TAG = "SyncDataStorePlugin";
	private BroadcastReceiver changeSetReceivedReceiver;
	private BroadcastReceiver startSyncReceiver;
	private BroadcastReceiver stopSyncReceiver;
	private BroadcastReceiver syncBlockReceiver;
	private BroadcastReceiver syncUnblockReceiver;

	protected void registerReceivers() {
		webView.getContext().registerReceiver(changeSetReceivedReceiver,
				new IntentFilter(SyncAdapter.UPDATES_RECEIVED));
		webView.getContext().registerReceiver(this.startSyncReceiver,
				new IntentFilter(SyncAdapter.SYNC_START));
		webView.getContext().registerReceiver(stopSyncReceiver,
				new IntentFilter(SyncAdapter.SYNC_FINISHED));
		webView.getContext().registerReceiver(syncBlockReceiver,
				new IntentFilter(SyncAdapter.SYNC_BLOCK));
		webView.getContext().registerReceiver(syncUnblockReceiver,
				new IntentFilter(SyncAdapter.SYNC_UNBLOCK));
	}
	@SuppressLint("NewApi")
	private void notifyWebApp(String json, SyncType syncType) {
		
		String method = syncType.getNotificationMethod();
		if (method == null)
			return;
		try {
			CallbackContext callbackContext = _updateListeners.get(syncType.getName());
			if (callbackContext == null)
				return;
			PluginResult result = new PluginResult(Status.OK, json);
			result.setKeepCallback(true);
			callbackContext.sendPluginResult(result);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	@Override
	protected void pluginInitialize() {
		// TODO Auto-generated method stub
		super.pluginInitialize();

		this.changeSetReceivedReceiver = new BroadcastReceiver() {

			@SuppressLint("NewApi")
			@Override
			public void onReceive(Context context, Intent intent) {
				// then notify all
				for (Entry<SyncType, String> entry : SyncAdapter.notificationMap .entrySet()) {
					notifyWebApp(entry.getValue(), entry.getKey());
				}
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
		this.syncBlockReceiver = new BroadcastReceiver() {
			@SuppressLint("NewApi")
			@Override
			public void onReceive(Context context, Intent intent) {
				SyncDataStore.this.webView.evaluateJavascript(
						"DispoClient.NotificationHub.injectSyncState('block', '"
								+ intent.getStringExtra("collection") + "')",
						null);
			}
		};
		this.syncUnblockReceiver = new BroadcastReceiver() {
			@SuppressLint("NewApi")
			@Override
			public void onReceive(Context context, Intent intent) {
				SyncDataStore.this.webView.evaluateJavascript(
						"DispoClient.NotificationHub.injectSyncState('unblock')",
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
		webView.getContext().unregisterReceiver(this.syncUnblockReceiver);
		webView.getContext().unregisterReceiver(this.syncBlockReceiver);
	}

	private void getLatestUpdate(final String collectionName,
			final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				try {
					SyncTypeCollection[] syncCollections = SyncAdapter
							.getSyncCollections(SyncDataStore.this.webView
									.getContext());
					SyncTypeCollection foundCollection = null;
					for (SyncTypeCollection syncTypeCollection : syncCollections) {
						if (syncTypeCollection.getName().equalsIgnoreCase(
								collectionName)) {
							foundCollection = syncTypeCollection;
							break;
						}
					}
					long lastTimestamp = 0;
					if (foundCollection != null) {
						lastTimestamp = PreferenceManager
								.getDefaultSharedPreferences(
										SyncDataStore.this.webView.getContext())
								.getLong(foundCollection.getName(), 0);
					}

					callbackContext.success("" + lastTimestamp);
				} catch (Exception e) {
					callbackContext.error("failed to read last timestamp");
					e.printStackTrace();
				}
			}
		});
	}

	@Override
	public void onResume(boolean multitasking) {
		super.onResume(multitasking);
		registerReceivers();
	}

	private ConcurrentHashMap<String, CallbackContext> _updateListeners = new ConcurrentHashMap<String, CallbackContext>();
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
		} else if (action.equals("getLastUpdateTimestamp")) {
			this.getLatestUpdate(args.getString(0), callbackContext);
			;
		} else if (action.equals("isSyncing")) {
			callbackContext.success(new Boolean(isSyncing()).toString());
		} else if (action.equals("listen")){
			String typeName = args.getString(0);
			PluginResult pluginResult = new PluginResult(Status.NO_RESULT);
			pluginResult.setKeepCallback(true);
			callbackContext.sendPluginResult(pluginResult);
			this._updateListeners.put(typeName, callbackContext);
		}

		return true;
	}

	@SuppressLint("NewApi")
	private boolean isSyncing() {
		ContentResolver contentResolver = this.webView.getContext()
				.getContentResolver();
		for (SyncInfo syncInfo : contentResolver.getCurrentSyncs()) {
			if (syncInfo.authority.equals(SyncUtils.CONTENT_AUTHORITY)) {
				return true;
			}
		}
		return false;
	}

	private void unRegister(CallbackContext callbackContext) {
		try {
			SyncUtils.DeleteAccount(this.webView.getContext());
			for (SyncTypeCollection syncTypeCollection : SyncAdapter
					.getSyncCollections(this.webView.getContext())) {
				PreferenceManager
						.getDefaultSharedPreferences(this.webView.getContext())
						.edit().remove(syncTypeCollection.getName()).commit();
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