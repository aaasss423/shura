package app.shura.translator;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import app.shura.translator.ui.ChapterFragment;
import app.shura.translator.ui.SettingsFragment;
import app.shura.translator.ui.StatusFragment;
import app.shura.translator.ui.TranslateFragment;

/**
 * Four screens behind a bottom bar: translate a line, translate a chapter, check the
 * server, change the settings.
 *
 * Fragments are kept alive in a tag map rather than recreated on every tab switch, so
 * switching tabs never discards half-typed chapter text or a finished translation.
 */
public class MainActivity extends AppCompatActivity {

    private static final String KEY_FRAGMENT = "currentFragment";

    private Fragment translate;
    private Fragment chapter;
    private Fragment status;
    private Fragment settings;
    private String currentTag = "translate";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        translate = getSupportFragmentManager().findFragmentByTag("translate");
        chapter = getSupportFragmentManager().findFragmentByTag("chapter");
        status = getSupportFragmentManager().findFragmentByTag("status");
        settings = getSupportFragmentManager().findFragmentByTag("settings");

        if (savedInstanceState != null) {
            currentTag = savedInstanceState.getString(KEY_FRAGMENT, "translate");
        }
        show(currentTag);

        BottomNavigationView nav = findViewById(R.id.nav);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_chapter) {
                show("chapter");
            } else if (id == R.id.nav_status) {
                show("status");
            } else if (id == R.id.nav_settings) {
                show("settings");
            } else {
                show("translate");
            }
            return true;
        });
        if (savedInstanceState == null) {
            nav.setSelectedItemId(R.id.nav_translate);
        }
    }

    /** Swaps the visible fragment, creating it on first use. */
    private void show(String tag) {
        currentTag = tag;
        Fragment target;
        switch (tag) {
            case "chapter":
                target = chapter != null ? chapter : new ChapterFragment();
                chapter = target;
                break;
            case "status":
                target = status != null ? status : new StatusFragment();
                status = target;
                break;
            case "settings":
                target = settings != null ? settings : new SettingsFragment();
                settings = target;
                break;
            default:
                target = translate != null ? translate : new TranslateFragment();
                translate = target;
                break;
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content, target, tag)
                .commit();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_FRAGMENT, currentTag);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, 1, Menu.NONE, R.string.action_copy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1 && translate instanceof TranslateFragment) {
            ((TranslateFragment) translate).copyOutput();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
