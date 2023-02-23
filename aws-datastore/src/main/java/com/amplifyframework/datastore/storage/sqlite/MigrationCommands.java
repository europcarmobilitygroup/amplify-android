package com.amplifyframework.datastore.storage.sqlite;

import android.database.sqlite.SQLiteDatabase;

import com.amplifyframework.core.Amplify;
import com.amplifyframework.datastore.storage.sqlite.migrations.Migration;
import com.amplifyframework.logging.Logger;

import java.util.List;

public class MigrationCommands {
    private static final Logger LOGGER = Amplify.Logging.forNamespace("amplify:aws-datastore");
    private final List<Migration> migrationList;

    MigrationCommands(List<Migration> migrationList){
        this.migrationList = migrationList;
    }
    public void apply(SQLiteDatabase database, int oldVersion, int newVersion){
        LOGGER.debug("applying database migration old version " + oldVersion + " new version " + newVersion);
        database.beginTransaction();
        for(Migration migration : migrationList){
            try{
                if(migration.fromVersion >= oldVersion && migration.toVersion <= newVersion){
                    migration.apply(database);
                }
            }catch (Exception ex){
                LOGGER.error("exception during migration", ex);
            }
        }
        database.setTransactionSuccessful();
        database.endTransaction();
    }
}
