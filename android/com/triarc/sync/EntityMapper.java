package com.triarc.sync;

import android.database.sqlite.SQLiteDatabase;

public abstract class EntityMapper {
	public abstract Object getDbEntity(SQLiteDatabase db);
}
