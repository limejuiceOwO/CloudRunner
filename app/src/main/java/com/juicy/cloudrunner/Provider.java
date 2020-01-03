package com.juicy.cloudrunner;

import android.content.*;
import android.database.sqlite.*;
import android.database.*;
import android.net.Uri;

public class Provider extends ContentProvider {

    static final String PROVIDER_NAME = "com.juicy.cloudrunner";
    static final String URL = "content://" + PROVIDER_NAME + "/";
    static final Uri CONTENT_URI = Uri.parse(URL);

    static final int LOCATIONS = 1;
    static final int CONFIGS = 2;
    static final int MARKERS = 3;

    static final UriMatcher uriMatcher;
    static final String LOCATION_TABLE_NAME = "location";
    static final String CONFIG_TABLE_NAME = "config";
    static final String MARKER_TABLE_NAME = "marker";
    static{
        uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        uriMatcher.addURI(PROVIDER_NAME, LOCATION_TABLE_NAME, LOCATIONS);
        uriMatcher.addURI(PROVIDER_NAME, CONFIG_TABLE_NAME, CONFIGS);
        uriMatcher.addURI(PROVIDER_NAME, MARKER_TABLE_NAME, MARKERS);
    }

    /**
     * 数据库特定常量声明
     */
    private SQLiteDatabase db;
    static final String DATABASE_NAME = "Settings";
    static final int DATABASE_VERSION = 1;
    static final String CREATE_DB_TABLE_1 =
            " CREATE TABLE " + LOCATION_TABLE_NAME +
                    " (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    " latitude DOUBLE NOT NULL, " +
                    " longitude DOUBLE NOT NULL);";
    static final String CREATE_DB_TABLE_2 =
            " CREATE TABLE " + CONFIG_TABLE_NAME +
                    " (name TEXT PRIMARY KEY, " +
                    " value TEXT NOT NULL);";
    static final String CREATE_DB_TABLE_3 =
            " CREATE TABLE " + MARKER_TABLE_NAME +
                    " (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    " latitude DOUBLE NOT NULL, " +
                    " longitude DOUBLE NOT NULL);";

    /**
     * 创建和管理提供者内部数据源的帮助类.
     */
    private static class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context){
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db)
        {
            db.execSQL(CREATE_DB_TABLE_1);
            db.execSQL(CREATE_DB_TABLE_2);
            db.execSQL(CREATE_DB_TABLE_3);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " +  LOCATION_TABLE_NAME);
            db.execSQL("DROP TABLE IF EXISTS " +  CONFIG_TABLE_NAME);
            db.execSQL("DROP TABLE IF EXISTS " +  MARKER_TABLE_NAME);
            onCreate(db);
        }
    }

    @Override
    public boolean onCreate() {
        Context context = getContext();
        DatabaseHelper dbHelper = new DatabaseHelper(context);

        /*
         * 如果不存在，则创建一个可写的数据库。
         */
        db = dbHelper.getWritableDatabase();
        return db != null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        int uriType = uriMatcher.match(uri);
        long rowID = 0;
        switch(uriType) {
            case LOCATIONS:
                rowID = db.insert(LOCATION_TABLE_NAME, null, values);
                break;
            case CONFIGS:
                rowID = db.insert(CONFIG_TABLE_NAME, null, values);
                break;
            case MARKERS:
                rowID = db.insert(MARKER_TABLE_NAME, null, values);
                break;
            default:
                throw new IllegalArgumentException("Unknown Uri : " + uri);
        }

        if (rowID > 0)
        {
            Uri _uri = ContentUris.withAppendedId(CONTENT_URI, rowID);
            getContext().getContentResolver().notifyChange(_uri, null);
            return _uri;
        }
        throw new SQLException("Failed to add a record into " + uri);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,String[] selectionArgs, String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        switch (uriMatcher.match(uri)) {
            case LOCATIONS:
                qb.setTables(LOCATION_TABLE_NAME);
                break;
            case CONFIGS:
                qb.setTables(CONFIG_TABLE_NAME);
                break;
            case MARKERS:
                qb.setTables(MARKER_TABLE_NAME);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        Cursor c = qb.query(db, projection, selection, selectionArgs,null, null, sortOrder);

        /*
         * 注册内容URI变化的监听器
         */
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int count;

        switch (uriMatcher.match(uri)){
            case LOCATIONS:
                count = db.delete(LOCATION_TABLE_NAME, selection, selectionArgs);
                break;
            case CONFIGS:
                count = db.delete(CONFIG_TABLE_NAME, selection, selectionArgs);
                break;
            case MARKERS:
                count = db.delete(MARKER_TABLE_NAME, selection, selectionArgs);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if(count > 0) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        int count;

        switch (uriMatcher.match(uri)){
            case LOCATIONS:
                count = db.update(LOCATION_TABLE_NAME, values, selection, selectionArgs);
                break;
            case CONFIGS:
                count = db.update(CONFIG_TABLE_NAME, values, selection, selectionArgs);
                break;
            case MARKERS:
                count = db.update(MARKER_TABLE_NAME, values, selection, selectionArgs);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri );
        }
        if(count > 0) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }
}
