package com.triarc.sync;

import com.google.gson.annotations.SerializedName;

public enum SyncKind {
	@SerializedName("0")
	ReadOnly,
	@SerializedName("1")
	ReadUpdate
}
