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
// ^(?!(eglCodecCommon))
package com.triarc.sync;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HTTP;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pgsqlite.SQLiteAccess;

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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonStreamParser;
import com.google.gson.stream.JsonReader;
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
@SuppressLint("NewApi")
public class SyncAdapter extends AbstractThreadedSyncAdapter {
	public static final String LOCK_SOURCE_NAME = "SyncDataStore";

	private static final String FIELD_PREFIX = "_";

	public static final String TAG = "SyncAdapter";

	public static final String UPDATES_RECEIVED = "SyncDataReceived";

	public static final String PAYLOAD_NAME = "ChangeSet";

	public static final String SYNC_START = "SyncStart";
	public static final String SYNC_FINISHED = "SyncFinished";
	public static final String SYNC_BLOCK = "SyncBlock";
	public static final String SYNC_UNBLOCK = "SyncUnblock";

	public static final String SYNC_FIELDS = "SyncFields";
	public static final int UNCHANGED = 0;
	public static final int UPDATED = 1;
	public static final int DELETED = 2;
	public static final int ADDED = 3;

	static final String SYNC_TYPE = "SyncType";

	public static final String SYNC_ERROR = "SyncError";

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
		try {

			this.syncResult = syncResult;

			for (SyncTypeCollection type : getSyncCollections(this.getContext())) {

				this.notifySyncStart(type);
				try {
					syncTypeCollection(type);
				} catch (Exception e) {
					this.notifySyncError(e, type);
				}
				this.notifySyncFinished(type);
			}

		} catch (Exception e) {
			e.printStackTrace();
		} finally {

			long duration = (new java.util.Date()).getTime() - start.getTime();
			Log.d(TAG, "Sync finished in " + duration + "ms");
		}

	}

	private void notifySyncError(Exception e, SyncTypeCollection syncType) {
		Intent intent = new Intent(SYNC_ERROR);
		intent.putExtra(SYNC_ERROR, e.toString());
		intent.putExtra(SYNC_TYPE, syncType.getName());
		this.getContext().sendBroadcast(intent);
	}

	private void sendLogs() {
		final LogCollector mLogCollector = new LogCollector(
				SyncAdapter.this.getContext());
		new AsyncTask<Void, Void, Boolean>() {
			@Override
			protected Boolean doInBackground(Void... params) {
				try {
					mLogCollector.collect();
					String path = SyncUtils.GetWebApiPath(SyncAdapter.this
							.getContext());
					if (path == null)
						return true;
					mLogCollector.sendLog(path);
				} catch (Exception e) {
					e.printStackTrace();
				}
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
		Log.i(TAG, "Beginning network synchronization for collection: "
				+ typeCollection);
		DefaultHttpClient httpclient = createHttpClient();
		HttpRequestBase request;
		MutableBoolean hasChanges;
		try {
			String password = mAccountManager.getPassword(GenericAccountService
					.GetAccount());
			if (password == null) {
				Log.d(TAG, "Person not logged in, can't sync yet");
				return;
			}
			String path = SyncUtils.GetWebApiPath(this.getContext());
			if (path == null) {
				Log.d(TAG, "web api path not set yet, ignore sync");
				return;
			}

			
			hasChanges = new MutableBoolean();

			request = createRequest(typeCollection, password, path, hasChanges);
		} catch (Exception e) {
			Log.e(TAG, "Error creating request: " + e.toString());
			syncResult.stats.numIoExceptions++;
			sendLogs();
			notifySyncError(e, typeCollection);
			return;
		}
		InputStream inputStream = null;
		String result = null;
		try {
			HttpResponse response = httpclient.execute(request);
			int statusCode = response.getStatusLine().getStatusCode();
			if (statusCode >= 200 && statusCode < 300) {
				HttpEntity httpEntity = response.getEntity();
				inputStream = httpEntity.getContent();

				readResponse(typeCollection, inputStream, response);
			} else {
				if (statusCode == 401) {
					Log.w(TAG,
							"Not authenticated, remove this password and remove account");
					sendLogs();
					// SyncUtils.DeleteAccount(this.getContext());
				} else {
					logResponse(response);
				}
				syncResult.partialSyncUnavailable = true;
			}
		} catch (SocketTimeoutException e) {
			e.printStackTrace();
			sendLogs();
			// swallow
		} catch (Exception e) {
			e.printStackTrace();
			sendLogs();
			notifySyncError(e, typeCollection);
		} finally {
			try {
				if (hasChanges.getValue()) {
					this.unlockUi(typeCollection);
				}
				if (inputStream != null)
					inputStream.close();
			} catch (Exception squish) {
				squish.printStackTrace();
				sendLogs();
			}
		}

		Log.i(TAG, "Network synchronization complete for collection: "
				+ typeCollection.getName());
	}

	private void notifyWebApp() {
		try {
			Intent intent = new Intent(UPDATES_RECEIVED);
			this.getContext().sendBroadcast(intent);
		} catch (Exception e) {
			syncResult.stats.numIoExceptions++;
			e.printStackTrace();
			sendLogs();
		}
	}

	private void logResponse(HttpResponse response) throws IOException {
		ByteArrayOutputStream outstream = new ByteArrayOutputStream();
		response.getEntity().writeTo(outstream);
		String responseBody = outstream.toString();
		Log.e(TAG, "Sync failed:" + responseBody);
	}

	public static ConcurrentLinkedQueue<SyncNotificationMessage> notificationQueue = new ConcurrentLinkedQueue<SyncNotificationMessage>();

	@SuppressLint("NewApi")
	private void readResponse(SyncTypeCollection typeCollection,
			InputStream inputStream, HttpResponse response)
			throws UnsupportedEncodingException, IOException, JSONException,
			Exception {
		long lastUpdate = 0;
		Header header = response.getFirstHeader("X-Application-Timestamp");
		if (header != null) {
			String value = header.getValue();
			lastUpdate = Long.parseLong(value);
		}

		// json is UTF-8 by default
		BufferedReader reader = new BufferedReader(new InputStreamReader(
				inputStream, "UTF-8"), 8);
		JsonStreamParser parser = new JsonStreamParser(reader);
		JsonReader jsonReader = new JsonReader(reader);
		jsonReader.beginObject();
		jsonReader.nextName();
		jsonReader.beginObject();
		while (jsonReader.hasNext()) {

			String syncName = jsonReader.nextName();
			SyncType syncType = this.GetSyncType(typeCollection, syncName);

			JsonElement fromJson = new Gson().fromJson(jsonReader,
					JsonElement.class);
			updateLocalTypeData(fromJson.getAsJsonObject(), syncType,
					typeCollection, syncResult);
			notificationQueue.add(new SyncNotificationMessage(syncType, fromJson.toString()));
			// String path = jsonReader.getPath();
			// String nextName = jsonReader.nextName();
			// jsonReader.endObject();
		}
		jsonReader.endObject();

		// String json = getStringForReader(reader);
		//
		//
		// JSONObject syncChangeSets = new JSONObject(json)
		// .getJSONObject("changeSetPerType");
		//
		// // first save all
		// for (SyncType syncType : typeCollection.getTypes()) {
		// syncResult.madeSomeProgress();
		// String name = syncType.getName();
		//
		// if (syncChangeSets.has(name)) {
		// JSONObject changeSetObject = syncChangeSets.getJSONObject(name);
		// updateLocalTypeData(changeSetObject, syncType, typeCollection,
		// syncResult);
		// notificationMap.put(syncType, changeSetObject);
		// } else {
		// Log.w(TAG, "Server does not support syncing of " + name);
		// sendLogs();
		// }
		// }

		// store collection update timestamp
		PreferenceManager.getDefaultSharedPreferences(this.getContext()).edit()
				.putLong(typeCollection.getName(), lastUpdate).commit();
		// then notify all
		notifyWebApp();

	}

	private SyncType GetSyncType(SyncTypeCollection typeCollection, String name) {
		for (SyncType syncType : typeCollection.getTypes()) {
			if (syncType.getName().equals(name))
				return syncType;
		}
		return null;
	}

	private HttpRequestBase createRequest(SyncTypeCollection typeCollection,
			String password, String path, MutableBoolean hasChanges)
			throws Exception, IOException, UnsupportedEncodingException {
		String localTableName = typeCollection.getName();
		Log.i(TAG, "Streaming data for type: " + localTableName);

		String actionPath = path + "/" + typeCollection.getController() + "/"
				+ typeCollection.getAction();

		HttpRequestBase request;

		HttpPost httppost = new HttpPost(actionPath);
		request = httppost;

		JSONObject entity;

		try {
			entity = this.getVersionSets(typeCollection, hasChanges);
		} finally {
			if (hasChanges.getValue()) {
				// keep the ui locked until the change is confirmed from the
				// server
				hasChanges.setValue(true);
				this.lockUi(typeCollection);
			}
		}

		httppost.setEntity(new StringEntity(entity.toString(), HTTP.UTF_8));

		request.setHeader("Content-Type", "application/json; charset=utf-8");

		request.addHeader("Cookie", password);
		return request;
	}

	private void lockUi(SyncTypeCollection collection) {
		Intent intent = new Intent(SYNC_BLOCK);
		intent.putExtra("collection", collection.getName());
		this.getContext().sendBroadcast(intent);
	}

	private void unlockUi(SyncTypeCollection collection) {
		Intent intent = new Intent(SYNC_UNBLOCK);
		intent.putExtra("collection", collection.getName());
		this.getContext().sendBroadcast(intent);
	}

	private DefaultHttpClient createHttpClient() {
		HttpParams my_httpParams = new BasicHttpParams();
		HttpConnectionParams.setConnectionTimeout(my_httpParams, 120000);
		HttpConnectionParams.setSoTimeout(my_httpParams, 120000);
		DefaultHttpClient httpclient = new DefaultHttpClient(my_httpParams);
		return httpclient;
	}

	private void notifySyncStart(SyncTypeCollection type) {
		Intent intent = new Intent(SYNC_START);
		intent.putExtra(SYNC_TYPE, type.getName());
		this.getContext().sendBroadcast(intent);
	}

	private void notifySyncFinished(SyncTypeCollection type) {
		Intent intent = new Intent(SYNC_FINISHED);
		intent.putExtra(SYNC_TYPE, type.getName());
		this.getContext().sendBroadcast(intent);
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

	private JSONObject getVersionSets(SyncTypeCollection collection,
			MutableBoolean hasUpdates) throws Exception {
		long startGetVersions = System.currentTimeMillis();
		JSONObject containerObject = new JSONObject();
		JSONObject changeSets = new JSONObject();

		for (SyncType syncType : collection.getTypes()) {
			syncResult.madeSomeProgress();
			try {
				changeSets.put(syncType.getName(),
						getVersions(syncType, collection, hasUpdates));
			} catch (Exception e) {
				syncResult.stats.numParseExceptions++;
				e.printStackTrace();
				sendLogs();
			}
		}
		containerObject.put("syncSetPerType", changeSets);
		long durationInMs = System.currentTimeMillis() - startGetVersions;
		Log.d(TAG, "get local entity versions in " + durationInMs + "ms");
		return containerObject;
	}

	@SuppressLint("NewApi")
	private JSONObject getVersions(SyncType type,
			SyncTypeCollection collection, MutableBoolean hasUpdates) {
		JSONObject changeSet = new JSONObject();
		JSONArray entityVersions = new JSONArray();
		SQLiteDatabase openDatabase = null;
		Cursor query = null;
		try {
			changeSet.put("entityVersions", entityVersions);
			openDatabase = openDatabase(collection);

			query = openDatabase.query(type.getName(), new String[] { "_id",
					"_timestamp", "__internalTimestamp", "__state" }, null,
					null, null, null, "__internalTimestamp ASC");
			while (query.moveToNext()) {
				syncResult.stats.numEntries++;
				JSONObject jsonObject = new JSONObject();

				int fieldType = query.getType(0);
				String id = query.getString(0);
				String queryId = this.getQueryId(fieldType, id);

				jsonObject.put("id", id);
				jsonObject.put("timestamp", query.getLong(1));
				jsonObject.put("clientTimestamp", query.getLong(2));
				int state = query.getInt(3);
				if (state != UNCHANGED) {
					hasUpdates.setValue(true);
				}
				if (state == ADDED || state == UPDATED) {
					appendEntity(type, openDatabase, jsonObject, queryId);
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
				closeDb(collection.getName());
		}
		return changeSet;
	}

	private String getQueryId(int fieldType, String id) {
		if (Cursor.FIELD_TYPE_INTEGER == fieldType)
			return id;
		if (Cursor.FIELD_TYPE_STRING == fieldType)
			return "'" + id + "'";
		// TODO Auto-generated method stub
		return null;
	}

	private void closeDb(String name) {
		SQLiteAccess.getInstance(this.getContext()).releaseDb(name);
	}

	private void appendEntity(SyncType type, SQLiteDatabase openDatabase,
			JSONObject jsonObject, String id) {
		Cursor fullEntityCursor = null;
		try {
			fullEntityCursor = openDatabase.query(type.getName(), null, "_id="
					+ id, null, null, null, null);

			if (fullEntityCursor.moveToNext()) {
				try {
					jsonObject
							.put("entity", readEntity(type, fullEntityCursor));
				} catch (Exception e) {
					syncResult.stats.numIoExceptions++;
					syncResult.databaseError = true;
					e.printStackTrace();
					sendLogs();
				}
			} else {
				Log.w(TAG, "should not happen, check appendEntity");
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

	private SQLiteDatabase openDatabase(SyncTypeCollection collection)
			throws Exception {
		try {
			SQLiteDatabase db = SQLiteAccess.getInstance(this.getContext())
					.requestDb(collection.getName());
			return db;
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

	private boolean hasArrayValues(String fieldName, JsonObject changeSet)
			throws JSONException {
		if (changeSet.has(fieldName)) {
			return changeSet.get(fieldName).getAsJsonArray().size() > 0;
		}
		return false;
	}

	private boolean hasTypeChanges(JsonObject changeSet) throws JSONException {
		boolean hasAdded = hasArrayValues("added", changeSet);
		boolean hasUpdated = hasArrayValues("updated", changeSet);
		boolean hasDeleted = hasArrayValues("deleted", changeSet);
		return hasAdded || hasUpdated || hasDeleted;
	}

	public void updateLocalTypeData(JsonObject fromJson, SyncType type,
			SyncTypeCollection collection, final SyncResult syncResult)
			throws Exception {
		if (!hasTypeChanges(fromJson)) {
			Log.d(TAG, "No updates for type:" + type.getName());
			return;
		}

		SQLiteDatabase db = openDatabase(collection);
		try {
			if (hasArrayValues("added", fromJson)) {
				JsonArray added = fromJson.get("added").getAsJsonArray();
				for (int index = 0; index < added.size(); index++) {
					JsonObject entity = added.get(index).getAsJsonObject();
					this.addOrUpdate(db, entity, type);
				}
			}
			if (hasArrayValues("updated", fromJson)) {
				JsonArray updated = fromJson.get("updated").getAsJsonArray();
				for (int index = 0; index < updated.size(); index++) {
					JsonObject entity = updated.get(index).getAsJsonObject();
					this.addOrUpdate(db, entity, type);
				}
			}
			if (hasArrayValues("deleted", fromJson)) {
				JsonArray deleted = fromJson.get("deleted").getAsJsonArray();
				for (int index = 0; index < deleted.size(); index++) {
					String id = deleted.get(index).getAsString();
					this.deleteRow(db, id, type);
				}
			}
		} finally {
			if (db.isOpen()) {
				closeDb(collection.getName());
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

	private void deleteRow(SQLiteDatabase db, String id, SyncType type)
			throws IOException {
		String queryId = id;
		try {
			Integer.parseInt(id);
		} catch (Exception e) {
			queryId = "'" + id + "'";
		}

		db.delete(type.getName(), "_id=" + queryId, null);
	}

	private void addOrUpdate(SQLiteDatabase db, JsonObject entity, SyncType type)
			throws JSONException, ParseException, IOException {
		ContentValues contentValues = new ContentValues();
		for (SyncField syncField : type.getFields()) {
			this.addContentValueFor(contentValues, entity, syncField);
		}
		contentValues.put("__state", UNCHANGED);

		contentValues.put("__internalTimestamp", entity.get("timestamp")
				.getAsLong());
		String localTableName = type.getName();
		db.replaceOrThrow(localTableName, null, contentValues);
	}

	private void addContentValueFor(ContentValues values, JsonObject entity,
			SyncField syncField) throws JSONException, ParseException {
		String name = syncField.getName();
		String dbFieldName = FIELD_PREFIX + name;
		if (!entity.has(name) || entity.get(name).isJsonNull()) {
			values.putNull(dbFieldName);
			return;
		}
		switch (syncField.getFieldType()) {
		case Boolean:
			values.put(dbFieldName, entity.get(name).getAsBoolean());
			break;
		case Date:
			Date date = format.parse(entity.get(name).getAsString());
			values.put(dbFieldName, date.getTime());
			break;
		case Numeric:
			values.put(dbFieldName, entity.get(name).getAsLong());
			break;
		case JsonArray:
			JsonArray jsonArray = entity.get(name).getAsJsonArray();
			values.put(dbFieldName, jsonArray.toString());
			break;
		case JsonObject:
			JsonObject object = entity.get(name).getAsJsonObject();
			values.put(dbFieldName, object.toString());
			break;
		case Text:
			values.put(dbFieldName, entity.get(name).getAsString());
			break;
		default:
		}
	}
}
