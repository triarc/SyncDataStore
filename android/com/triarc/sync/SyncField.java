package com.triarc.sync;

public class SyncField {

	public String getName() {
		return name;
	}

	private String name;
	private FieldType fieldType;

	public FieldType getFieldType() {
		return fieldType;
	}

	public void setFieldType(FieldType fieldType) {
		this.fieldType = fieldType;
	}

	public void setName(String name) {
		this.name = name;
	}
}
