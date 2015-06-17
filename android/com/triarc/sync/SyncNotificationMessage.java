package com.triarc.sync;

public class SyncNotificationMessage {

	
	public SyncNotificationMessage(SyncType syncType, String json) {
		super();
		this.syncType = syncType;
		this.json = json;
	}
	private SyncType syncType;
	public SyncType getSyncType() {
		return syncType;
	}
	public void setSyncType(SyncType syncType) {
		this.syncType = syncType;
	}
	private String json;
	public String getJson() {
		return json;
	}
	public void setJson(String json) {
		this.json = json;
	}
}
