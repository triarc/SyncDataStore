/*
 * Copyright 2013 The Android Open Source Project
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

package com.triarc.sync;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.TimeZone;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.triarc.sync.accounts.GenericAccountService;

/**
 * Define a sync adapter for the app.
 *
 * <p>
 * This class is instantiated in {@link SyncService}, which also binds
 * SyncAdapter to the system. SyncAdapter should only be initialized in
 * SyncService, never anywhere else.
 *
 * <p>
 * The system calls onPerformSync() via an RPC call through the IBinder object
 * supplied by SyncService.
 */
class SyncAdapter extends AbstractThreadedSyncAdapter {
	private static final String FIELD_PREFIX = "_";

	public static final String TAG = "SyncAdapter";

	public static final String UPDATES_RECEIVED = "SyncDataReceived";

	public static final String PAYLOAD_NAME = "ChangeSet";

	public static final String SYNC_START = "SyncStart";
	public static final String SYNC_FINISHED = "SyncFinished";

	public static final String SYNC_FIELDS = "SyncFields";
	public static final int UNCHANGED = 0;
	public static final int UPDATED = 1;
	public static final int DELETED = 2;
	public static final int ADDED = 3;
	
	private AccountManager mAccountManager;

	private SyncResult syncResult;

	public static SyncTypeCollection[] getSyncCollections(Context context) {
		String serializedFields = PreferenceManager
				.getDefaultSharedPreferences(context).getString(SYNC_FIELDS,
						null);
		SyncTypeCollection[] syncTypes = createGson().fromJson(
				serializedFields, SyncTypeCollection[].class);
		if (syncTypes == null)
			syncTypes = new SyncTypeCollection[] {};
		return syncTypes;
	}

	/**
	 * Constructor. Obtains handle to content resolver for later use.
	 */
	public SyncAdapter(Context context, boolean autoInitialize) {
		super(context, autoInitialize);

		this.mAccountManager = (AccountManager) context
				.getSystemService(Context.ACCOUNT_SERVICE);
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	/**
	 * Called by the Android system in response to a request to run the sync
	 * adapter. The work required to read data from the network, parse it, and
	 * store it in the content provider is done here. Extending
	 * AbstractThreadedSyncAdapter ensures that all methods within SyncAdapter
	 * run on a background thread. For this reason, blocking I/O and other
	 * long-running tasks can be run <em>in situ</em>, and you don't have to set
	 * up a separate thread for them. .
	 *
	 * <p>
	 * This is where we actually perform any work required to perform a sync.
	 * {@link AbstractThreadedSyncAdapter} guarantees that this will be called
	 * on a non-UI thread, so it is safe to peform blocking I/O here.
	 *
	 * <p>
	 * The syncResult argument allows you to pass information back to the method
	 * that triggered the sync.
	 */
	@Override
	public void onPerformSync(Account account, Bundle extras, String authority,
			ContentProviderClient provider, SyncResult syncResult) {
		Date start = new Date();
		this.syncResult = syncResult;
		this.notifySyncStart();
		for (SyncTypeCollection type : getSyncCollections(this.getContext())) {
			syncTypeCollection(type);
		}
		this.notifySyncFinished();
		long duration = (new java.util.Date()).getTime() - start.getTime();
		Log.d(TAG, "Sync finished in " + duration + "ms");
	}

	private void sendLogs() {
		final LogCollector mLogCollector = new LogCollector(
				SyncAdapter.this.getContext());
		new AsyncTask<Void, Void, Boolean>() {
			@Override
			protected Boolean doInBackground(Void... params) {
				mLogCollector.collect();
				String path = SyncUtils.GetWebApiPath(SyncAdapter.this
						.getContext());
				if (path == null)
					return true;
				mLogCollector.sendLog(path);
				return true;
			}

			@Override
			protected void onPreExecute() {
				// showDialog(DIALOG_PROGRESS_COLLECTING_LOG);
			}

			@Override
			protected void onPostExecute(Boolean result) {

			}

		}.execute();
	}

	private void syncTypeCollection(SyncTypeCollection typeCollection) {
		Log.i(TAG, "Beginning network synchronization");
		try {
			String password = mAccountManager.getPassword(GenericAccountService
					.GetAccount());
			if (password == null) {
				Log.d(TAG, "Person not logged in, can't sync yet");
				return;
			}

			HttpParams my_httpParams = new BasicHttpParams();
			HttpConnectionParams.setConnectionTimeout(my_httpParams, 120000);
			HttpConnectionParams.setSoTimeout(my_httpParams, 120000);
			DefaultHttpClient httpclient = new DefaultHttpClient(my_httpParams);

			String path = SyncUtils.GetWebApiPath(this.getContext());
			if (path == null) {
				Log.d(TAG, "web api path not set yet, ignore sync");
				return;
			}

			String localTableName = typeCollection.getName();
			Log.i(TAG, "Streaming data for type: " + localTableName);

			String actionPath = path + "/" + typeCollection.getController()
					+ "/" + typeCollection.getAction();

			HttpRequestBase request;

			HttpPost httppost = new HttpPost(actionPath);
			request = httppost;
			JSONObject entity = this.getVersionSets(typeCollection);
			httppost.setEntity(new StringEntity(entity.toString(), HTTP.UTF_8));

			request.setHeader("Content-Type", "application/json; charset=utf-8");

			request.addHeader("Cookie", password);

			InputStream inputStream = null;
			String result = null;
			try {
				HttpResponse response = httpclient.execute(request);
				int statusCode = response.getStatusLine().getStatusCode();
				if (statusCode >= 200 && statusCode < 300) {
					HttpEntity httpEntity = response.getEntity();

					long lastUpdate = 0;
					Header header = response
							.getFirstHeader("X-Application-Timestamp");
					if (header != null) {
						String value = header.getValue();
						lastUpdate = Long.parseLong(value);
					}
					inputStream = httpEntity.getContent();
					// json is UTF-8 by default
					BufferedReader reader = new BufferedReader(
							new InputStreamReader(inputStream, "UTF-8"), 8);
					String json = getStringForReader(reader);
					JSONObject syncChangeSets = new JSONObject(json)
							.getJSONObject("changeSetPerType");
					HashMap<SyncType, JSONObject> hashMap = new HashMap<SyncType, JSONObject>();

					// first save all
					for (SyncType syncType : typeCollection.getTypes()) {
						syncResult.madeSomeProgress();
						if (syncChangeSets.has(syncType.getName())) {
							JSONObject changeSetObject = syncChangeSets
									.getJSONObject(syncType.getName());
							updateLocalTypeData(changeSetObject, syncType,
									syncResult);

							hashMap.put(syncType, changeSetObject);

						}
					}
					// store collection update timestamp
					PreferenceManager
							.getDefaultSharedPreferences(this.getContext())
							.edit()
							.putLong(typeCollection.getName(), lastUpdate)
							.commit();
					// then notify all
					for (Entry<SyncType, JSONObject> entry : hashMap.entrySet()) {
						notifyWebApp(entry.getValue().toString(),
								entry.getKey());
					}
				} else {
					if (statusCode == 401) {
						Log.w(TAG,
								"Not authenticated, remove this password and remove account");
						// SyncUtils.DeleteAccount(this.getContext());
					} else {
						ByteArrayOutputStream outstream = new ByteArrayOutputStream();
						response.getEntity().writeTo(outstream);
						String responseBody = outstream.toString();
						Log.e(TAG, "Sync failed:" + responseBody);
					}
					syncResult.partialSyncUnavailable = true;
				}
			} catch (SocketTimeoutException e) {
				e.printStackTrace();
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				sendLogs();
			} finally {
				try {
					if (inputStream != null)
						inputStream.close();
				} catch (Exception squish) {
					squish.printStackTrace();
					sendLogs();
				}
			}
		} catch (Exception e) {
			Log.e(TAG, "Error reading from network: " + e.toString());
			syncResult.stats.numIoExceptions++;
			sendLogs();
			return;
		}
		Log.i(TAG, "Network synchronization complete");
	}

	private void notifySyncStart() {
		Intent intent = new Intent(SYNC_START);
		this.getContext().sendBroadcast(intent);
	}

	private void notifySyncFinished() {
		Intent intent = new Intent(SYNC_FINISHED);
		this.getContext().sendBroadcast(intent);
	}

	private void notifyWebApp(String json, SyncType syncType) {
		String method = syncType.getNotificationMethod();
		if (method == null)
			return;
		try {
			Intent intent = new Intent(UPDATES_RECEIVED);
			intent.putExtra(PAYLOAD_NAME, json);
			intent.putExtra(PAYLOAD_INJECT_METHOD,
					syncType.getNotificationMethod());
			this.getContext().sendBroadcast(intent);
		} catch (Exception e) {
			syncResult.stats.numIoExceptions++;
			e.printStackTrace();
			sendLogs();
		}
	}

	private String getStringForReader(BufferedReader reader) throws IOException {
		StringBuilder sb = new StringBuilder();
		String line = reader.readLine();
		while (line != null) {
			sb.append(line);
			line = reader.readLine();
		}
		return sb.toString();
	}

	private JSONObject getVersionSets(SyncTypeCollection collection)
			throws JSONException {
		JSONObject containerObject = new JSONObject();

		JSONObject changeSets = new JSONObject();

		for (SyncType syncType : collection.getTypes()) {
			try {
				changeSets.put(syncType.getName(), getVersions(syncType));
			} catch (Exception e) {
				syncResult.stats.numParseExceptions++;
				e.printStackTrace();
				sendLogs();
			}
		}
		containerObject.put("syncSetPerType", changeSets);
		return containerObject;
	}

	private JSONObject getVersions(SyncType type) {
		JSONObject changeSet = new JSONObject();
		JSONArray entityVersions = new JSONArray();
		SQLiteDatabase openDatabase = null;
		Cursor query = null;
		try {
			changeSet.put("entityVersions", entityVersions);
			openDatabase = openDatabase(type.getName());

			query = openDatabase
					.query(type.getName(), new String[] { "_id", "_timestamp",
							"__state" }, null, null, null, null, "__internalTimestamp ASC");
			while (query.moveToNext()) {
				syncResult.stats.numEntries++;
				JSONObject jsonObject = new JSONObject();

				int id = query.getInt(0);
				jsonObject.put("id", id);
				jsonObject.put("timestamp", query.getLong(1));
				int state = query.getInt(2);
				if (state == ADDED || state == UPDATED) {
					appendEntity(type, openDatabase, jsonObject, id);
				}
				jsonObject.put("state", state);
				entityVersions.put(jsonObject);
			}
		} catch (Exception e) {
			syncResult.stats.numIoExceptions++;
			syncResult.stats.numSkippedEntries++;
			syncResult.databaseError = true;
			e.printStackTrace();
			sendLogs();
		} finally {
			if (query != null)
				query.close();
			if (openDatabase != null && openDatabase.isOpen())
				openDatabase.close();
		}
		return changeSet;
	}

	private void appendEntity(SyncType type, SQLiteDatabase openDatabase,
			JSONObject jsonObject, int id) {
		Cursor fullEntityCursor = null;
		try {
			fullEntityCursor = openDatabase.query(type.getName(),
					null, "_id=" + id, null, null, null, null);

			if (fullEntityCursor.moveToNext()) {
				try {
					jsonObject.put("entity",
							readEntity(type, fullEntityCursor));
				} catch (Exception e) {
					syncResult.stats.numIoExceptions++;
					syncResult.databaseError = true;
					e.printStackTrace();
					sendLogs();
				}
			}
		} finally {
			if (fullEntityCursor != null)
				fullEntityCursor.close();
		}
	}

	private JSONObject readEntity(SyncType type, Cursor query)
			throws JSONException {
		JSONObject jsonObject = new JSONObject();
		for (SyncField syncField : type.getFields()) {
			jsonObject.put(
					syncField.getName(),
					this.getModelValueFor(query,
							FIELD_PREFIX + syncField.getName(),
							syncField.getFieldType()));
		}
		return jsonObject;
	}

	@SuppressLint("UseValueOf")
	private Object getModelValueFor(Cursor query, String fieldName,
			FieldType fieldType) throws JSONException {

		int columnIndex = query.getColumnIndex(fieldName);
		if (query.isNull(columnIndex)) {
			return null;
		}
		switch (fieldType) {
		case Boolean:
			return new Boolean(query.getInt(columnIndex) != 0);
		case Date:
			return format.format(new Date(query.getLong(columnIndex)));
		case Numeric:
			return new Long(query.getLong(columnIndex));
		case Text:
			return query.getString(columnIndex);
		case JsonObject:
			String json = query.getString(columnIndex);
			return new JSONObject(json);
		case JsonArray:
			json = query.getString(columnIndex);
			return new JSONArray(json);
		default:
			return null;
		}
	}

	private SQLiteDatabase openDatabase(String tableName) throws Exception {
		try {
			File dbfile = this.getContext().getDatabasePath(tableName);

			if (!dbfile.exists()) {
				dbfile.getParentFile().mkdirs();
			}

			Log.v("info", "Open sqlite db: " + dbfile.getAbsolutePath());

			return SQLiteDatabase.openOrCreateDatabase(dbfile, null);

		} catch (SQLiteException e) {
			e.printStackTrace();
			sendLogs();
			throw e;
		}
	}

	private void debugRow(Cursor cursor) {
		for (int index = 0; index < cursor.getColumnCount(); index++) {

			Log.d(TAG,
					cursor.getColumnName(index) + " => "
							+ cursor.getString(index));
		}
	}

	static String join(Collection<?> s, String delimiter) {
		StringBuilder builder = new StringBuilder();
		Iterator<?> iter = s.iterator();
		while (iter.hasNext()) {
			builder.append(iter.next());
			if (!iter.hasNext()) {
				break;
			}
			builder.append(delimiter);
		}
		return builder.toString();
	}

	public void updateLocalTypeData(JSONObject changeSet, SyncType type,
			final SyncResult syncResult) throws Exception {
		SQLiteDatabase db = openDatabase(type.getName());
		try {
			if (changeSet.has("added")) {
				JSONArray added = changeSet.getJSONArray("added");
				for (int index = 0; index < added.length(); index++) {
					JSONObject entity = added.getJSONObject(index);
					this.addOrUpdate(db, entity, type);
				}
			}
			if (changeSet.has("updated")) {
				JSONArray updated = changeSet.getJSONArray("updated");
				for (int index = 0; index < updated.length(); index++) {
					JSONObject entity = updated.getJSONObject(index);
					this.addOrUpdate(db, entity, type);
				}
			}
			if (changeSet.has("deleted")) {
				JSONArray deleted = changeSet.getJSONArray("deleted");
				for (int index = 0; index < deleted.length(); index++) {
					int id = deleted.getInt(index);
					this.deleteRow(db, id, type);
				}
			}

		} finally {
			if (db.isOpen()) {
				db.close();
			}

		}
	}

	private static final SimpleDateFormat format = new SimpleDateFormat(
			"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

	public static final String PAYLOAD_INJECT_METHOD = "ChangesetType";

	private static Gson createGson() {
		Gson gson = new GsonBuilder().setDateFormat(
				"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").create();
		return gson;
	}

	private void deleteRow(SQLiteDatabase db, Integer id, SyncType type) {
		db.delete(type.getName(), "_id=" + id.toString(), null);
	}

	private void addOrUpdate(SQLiteDatabase db, JSONObject entity, SyncType type)
			throws JSONException, ParseException {
		ContentValues contentValues = new ContentValues();
		for (SyncField syncField : type.getFields()) {
			this.addContentValueFor(contentValues, entity, syncField);
		}
		contentValues.put("__state", UNCHANGED);

		String localTableName = type.getName();
		db.replaceOrThrow(localTableName, null, contentValues);
	}

	private void addContentValueFor(ContentValues values, JSONObject entity,
			SyncField syncField) throws JSONException, ParseException {
		String name = syncField.getName();
		String dbFieldName = FIELD_PREFIX + name;
		if (!entity.has(name) || entity.isNull(name)) {
			values.putNull(dbFieldName);
			return;
		}
		switch (syncField.getFieldType()) {
		case Boolean:
			values.put(dbFieldName, entity.getBoolean(name));
			break;
		case Date:
			Date date = format.parse(entity.getString(name));
			values.put(dbFieldName, date.getTime());
			break;
		case Numeric:
			values.put(dbFieldName, entity.getLong(name));
			break;
		case JsonArray:
			JSONArray jsonArray = entity.getJSONArray(name);
			values.put(dbFieldName, jsonArray.toString());
			break;
		case JsonObject:
			JSONObject object = entity.getJSONObject(name);
			values.put(dbFieldName, object.toString());
			break;
		case Text:
			values.put(dbFieldName, entity.getString(name));
			break;
		default:
		}
	}
}
