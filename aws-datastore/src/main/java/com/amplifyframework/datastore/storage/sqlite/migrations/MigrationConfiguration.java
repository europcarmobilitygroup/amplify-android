package com.amplifyframework.datastore.storage.sqlite.migrations;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MigrationConfiguration {
    private final List<Migration> migrationList;
    public MigrationConfiguration(List<Migration> migrationList){
        this.migrationList = migrationList;
    }

    public List<Migration> getMigrationList(){
        return new ArrayList<>(migrationList);
    }

    public static final class Builder {
        private final List<Migration> migrationList;

        public Builder() {
            migrationList = new ArrayList<>();
        }

        @NonNull
        public MigrationConfiguration.Builder addMigration(Migration migration) {
            migrationList.add(migration);
            return MigrationConfiguration.Builder.this;
        }

        @NonNull
        public MigrationConfiguration.Builder addMigrations(List<Migration> migrations) {
            migrationList.addAll(migrations);
            return MigrationConfiguration.Builder.this;
        }

        public MigrationConfiguration build(){
            Collections.sort(migrationList, (o1, o2) -> {
                if(o1.fromVersion == o2.fromVersion) return o1.toVersion - o2.toVersion;
                else return o1.fromVersion - o2.fromVersion;
            });
            return new MigrationConfiguration(migrationList);
        }
    }
}
