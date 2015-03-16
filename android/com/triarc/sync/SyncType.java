package com.triarc.sync;

public class SyncType {
	

	public SyncField[] getFields() {
		return fields;
	}
	public void setFields(SyncField[] fields) {
		this.fields = fields;
	}
	public String getMapperJavaClass() {
		return mapperJavaClass;
	}
	public void setMapperJavaClass(String mapperJavaClass) {
		this.mapperJavaClass = mapperJavaClass;
	}
	public SyncKind getKind() {
		return kind;
	}
	public void setKind(SyncKind kind) {
		this.kind = kind;
	}
	public String getTimestampColumn() {
		return timestampColumn;
	}
	public void setTimestampColumn(String timestampColumn) {
		this.timestampColumn = timestampColumn;
	}
	private String name;
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	private SyncField[] fields;
	private String mapperJavaClass;
	private SyncKind kind;
	private String timestampColumn;
	private String notificationMethod;
	public String getNotificationMethod() {
		return notificationMethod;
	}
	public void setNotificationMethod(String notificationMethod) {
		this.notificationMethod = notificationMethod;
	}
	

//	public String getControllerName() {
//		return controllerName;
//	}
//
//	public String getLocalTableName() {
//		return localTableName;
//	}
//
////	public Field[] getFields() {
////		return fields;
////	}
//
//	public SyncType(String controllerName, String actionName, String localTableName,String timestampColumn, Class<?> mapperClass, SyncKind kind) {
//		this.controllerName = controllerName;
//		this.actionName = actionName;
//		this.localTableName = localTableName;
//		this.timestampColumn = timestampColumn;
//		//this.columnNames = columnNames;
//		this.mapperJavaClass = mapperClass;
//		this.kind = kind;
//		//this.fields = fields;
//	}
//
//	public Class<?> getMapperJavaClass() {
//		return mapperJavaClass;
//	}
//
//	public SyncKind getKind() {
//		return kind;
//	}
//
//	public String getActionName() {
//		return actionName;
//	}
//
//	public String getTimestampColumn() {
//		return timestampColumn;
//	}	
}
