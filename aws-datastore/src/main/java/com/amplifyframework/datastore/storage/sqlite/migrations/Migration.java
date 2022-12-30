package com.amplifyframework.datastore.storage.sqlite.migrations;

import android.database.sqlite.SQLiteDatabase;

abstract public class Migration {
    public final int fromVersion;
    public final int toVersion;

    public Migration(int fromVersion, int toVersion){
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
    }

    public abstract void apply(SQLiteDatabase database);
}
