package com.juicy.cloudrunner;

import android.content.ContentResolver;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

public class SettingsActivity extends AppCompatActivity {
    final static String[] alterable = new String[] {"speed_min","speed_max","cycle_min","cycle_max","drop_loc_data","loop","step_auto_stop"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new SettingsFragment())
                .commit();
        ActionBar actionBar = getSupportActionBar();
        if(actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            PreferenceManager preferenceManager = getPreferenceManager();
            preferenceManager.setPreferenceDataStore(new DataStore(getContext().getContentResolver()));
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            setInputType("speed_min", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            setInputType("speed_max", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            setSimpleSummaryProvider("target");
            setSimpleSummaryProvider("speed_cent");
            setSimpleSummaryProvider("speed_delta");
            setSimpleSummaryProvider("cycle_min");
            setSimpleSummaryProvider("cycle_max");
        }

        private void setInputType(String key,final int flag) {
            EditTextPreference pref = findPreference(key);
            if (pref != null) {
                pref.setOnBindEditTextListener(
                        new EditTextPreference.OnBindEditTextListener() {
                            @Override
                            public void onBindEditText(@NonNull EditText editText) {
                                editText.setInputType(flag);
                            }
                        });
            }
        }

        public class DataStore extends PreferenceDataStore {
            ContentResolver resolver;

            DataStore(ContentResolver resolver) {
                super();
                if(resolver == null) {
                    throw new NullPointerException();
                }
                this.resolver = resolver;
            }

            @Override
            public void putBoolean(String key,boolean val) {
                PrefAlter.putBoolean(resolver, key, val);
                setLastUpdate(key);
            }

            @Override
            public boolean getBoolean(String key,boolean def) {
                return PrefAlter.getBoolean(resolver, key, def);
            }

            @Override
            public void putString(String key, @Nullable String val) {
                PrefAlter.putString(resolver, key, val); //TODO:min/max check
                setLastUpdate(key);
            }

            @Override
            @Nullable
            public String getString(String key, @Nullable String def) {
                return PrefAlter.getString(resolver, key, def);
            }

            private void setLastUpdate(String key) {
                for(String k : alterable) {
                    if(key.equals(k)) {
                        PrefAlter.putString(resolver,"last_update", "" + System.nanoTime());
                        break;
                    }
                }
            }
        }

        private void setSimpleSummaryProvider(String key) {
            EditTextPreference pref = findPreference(key);
            if (pref != null) {
                pref.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        if(item.getItemId() == android.R.id.home)
        {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}