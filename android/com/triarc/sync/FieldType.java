package com.triarc.sync;

import com.google.gson.annotations.SerializedName;

public enum FieldType {
	@SerializedName("0")
	Text,
	@SerializedName("1")
	Numeric,
	@SerializedName("2")
	Date,
	@SerializedName("3")
	Boolean,
	@SerializedName("4")
	JsonObject,
	@SerializedName("5")
	JsonArray
}
