package com.juicy.cloudrunner;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.Nullable;

class PrefAlter {
    static void putString(ContentResolver resolver,String key, @Nullable String val) {
        //Log.e("FA","putString " + key + " " + val);
        ContentValues values = new ContentValues();
        values.put("name",key);
        values.put("value",val);
        Cursor cursor = resolver.query(Uri.withAppendedPath(Provider.CONTENT_URI,"config"),new String[] {"value"},"name = ?",new String[] {key},null );
        if(cursor != null) {
            if (cursor.moveToNext()) {
                resolver.update(Uri.withAppendedPath(Provider.CONTENT_URI, "config"), values, "name = ?", new String[]{key});
            } else {
                resolver.insert(Uri.withAppendedPath(Provider.CONTENT_URI, "config"), values);
            }
            cursor.close();
        }
    }
    static String getString(ContentResolver resolver,String key, @Nullable String def) {
        //Log.e("FA", "getString " + key + " " + def);
        Cursor cursor = resolver.query(Uri.withAppendedPath(Provider.CONTENT_URI, "config"), new String[]{"value"}, "name = ?", new String[]{key}, null);
        String ans = def;
        if(cursor != null) {
            if (cursor.moveToNext()) {
                ans = cursor.getString(cursor.getColumnIndex("value"));
            }
            cursor.close();
        }
        return ans;
    }
    static boolean getBoolean(ContentResolver resolver,String key,boolean def) {
        String str = getString(resolver,key, "");
        if(str.equals("true")) {
            return true;
        } else if(str.equals("false")) {
            return false;
        } else {
            return def;
        }
    }
    static void putBoolean(ContentResolver resolver,String key,boolean val) {
        putString(resolver,key, val ? "true" : "false");
    }
}
