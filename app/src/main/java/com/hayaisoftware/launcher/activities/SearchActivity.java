/*
 * Copyright (c) 2015-2017 Hayai Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hayaisoftware.launcher.activities;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.hayaisoftware.launcher.LaunchableActivity;
import com.hayaisoftware.launcher.LaunchableActivityPrefs;
import com.hayaisoftware.launcher.LaunchableAdapter;
import com.hayaisoftware.launcher.LoadLaunchableActivityTask;
import com.hayaisoftware.launcher.PackageChangedReceiver;
import com.hayaisoftware.launcher.R;
import com.hayaisoftware.launcher.ShortcutNotificationManager;
import com.hayaisoftware.launcher.fragments.SettingsFragment;
import com.hayaisoftware.launcher.threading.SimpleTaskConsumerManager;
import com.hayaisoftware.launcher.util.ContentShare;

import java.util.Collection;

import static com.hayaisoftware.launcher.util.ContentShare.getLaunchableResolveInfos;

public class SearchActivity extends Activity
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String SEARCH_EDIT_TEXT_KEY = "SearchEditText";
    private LaunchableAdapter<LaunchableActivity> mAdapter;
    private SharedPreferences mSharedPreferences;
    private EditText mSearchEditText;
    private BroadcastReceiver mPackageChangedReceiver;
    private InputMethodManager mInputMethodManager;
    private View mOverflowButtonTopleft;

    /**
     * Retrieves the visibility status of the navigation bar.
     *
     * @param resources The resources for the device.
     * @return {@code True} if the navigation bar is enabled, {@code false} otherwise.
     */
    private static boolean hasNavBar(final Resources resources) {
        final boolean hasNavBar;
        final int id = resources.getIdentifier("config_showNavigationBar", "bool", "android");

        if (id > 0) {
            hasNavBar = resources.getBoolean(id);
        } else {
            hasNavBar = false;
        }

        return hasNavBar;
    }

    /**
     * Retrieves the navigation bar height.
     *
     * @param resources The resources for the device.
     * @return The height of the navigation bar.
     */
    private static int getNavigationBarHeight(final Resources resources) {
        final int navBarHeight;

        if (hasNavBar(resources)) {
            final Configuration configuration = resources.getConfiguration();

            //Only phone between 0-599 has navigationbar can move
            final boolean isSmartphone = configuration.smallestScreenWidthDp < 600;
            final boolean isPortrait =
                    configuration.orientation == Configuration.ORIENTATION_PORTRAIT;

            if (isSmartphone && !isPortrait) {
                navBarHeight = 0;
            } else if (isPortrait) {
                navBarHeight = getDimensionSize(resources, "navigation_bar_height");
            } else {
                navBarHeight = getDimensionSize(resources, "navigation_bar_height_landscape");
            }
        } else {
            navBarHeight = 0;
        }


        return navBarHeight;
    }

    /**
     * Get the navigation bar width.
     *
     * @param resources The resources for the device.
     * @return The width of the navigation bar.
     */
    private static int getNavigationBarWidth(final Resources resources) {
        final int navBarWidth;

        if (hasNavBar(resources)) {
            final Configuration configuration = resources.getConfiguration();

            //Only phone between 0-599 has navigationbar can move
            final boolean isSmartphone = configuration.smallestScreenWidthDp < 600;

            if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && isSmartphone) {
                navBarWidth = getDimensionSize(resources, "navigation_bar_width");
            } else {
                navBarWidth = 0;
            }
        } else {
            navBarWidth = 0;
        }


        return navBarWidth;
    }

    /**
     * This method returns the size of the dimen
     *
     * @param resources The resources for the containing the named identifier.
     * @param name      The name of the resource to get the id for.
     * @return The dimension size, {@code 0} if the name for the identifier doesn't exist.
     */
    private static int getDimensionSize(final Resources resources, final String name) {
        final int resourceId = resources.getIdentifier(name, "dimen", "android");
        final int dimensionSize;

        if (resourceId > 0) {
            dimensionSize = resources.getDimensionPixelSize(resourceId);
        } else {
            dimensionSize = 0;
        }

        return dimensionSize;
    }

    /**
     * Retain the state of the adapter on configuration change.
     *
     * @return The attached {@link LaunchableAdapter}.
     */
    @Override
    public Object onRetainNonConfigurationInstance() {
        return mAdapter.export();
    }

    @Override
    public void onMultiWindowModeChanged(final boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);

        setupPadding(!isInMultiWindowMode);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_search);

        //fields:
        mSearchEditText = (EditText) findViewById(R.id.user_search_input);
        mOverflowButtonTopleft = findViewById(R.id.overflow_button_topleft);
        mInputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        mAdapter = loadLaunchableAdapter();

        final boolean noMultiWindow = Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
                !isInMultiWindowMode();
        final boolean transparentPossible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;

        setupPadding(transparentPossible && noMultiWindow);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        mPackageChangedReceiver = new PackageChangedReceiver();
        registerReceiver(mPackageChangedReceiver, filter);

        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        setupPreferences();
        mSharedPreferences.registerOnSharedPreferenceChangeListener(this);

        //loadShareableApps();
        setupViews();
    }

    /**
     * This method dynamically sets the padding for the outer boundaries of the masterLayout and
     * appContainer.
     *
     * @param isNavBarTranslucent Set this to {@code true} if android.R.windowTranslucentNavigation
     *                            is expected to be {@code true}, {@code false} otherwise.
     */
    private void setupPadding(final boolean isNavBarTranslucent) {
        final Resources resources = getResources();
        final View masterLayout = findViewById(R.id.masterLayout);
        final View appContainer = findViewById(R.id.appsContainer);
        final int appTop = resources.getDimensionPixelSize(R.dimen.activity_vertical_margin);

        if (isNavBarTranslucent) {
            masterLayout.setFitsSystemWindows(false);
            final int navBarWidth = getNavigationBarWidth(resources);
            final int searchUpperPadding = getDimensionSize(resources, "status_bar_height");
            final int navBarHeight = getNavigationBarHeight(resources);

            // If the navigation bar is on the side, don't put apps under it.
            masterLayout.setPadding(0, searchUpperPadding, navBarWidth, 0);

            // If the navigation bar is at the bottom, stop the icons above it.
            appContainer.setPadding(0, appTop, 0, navBarHeight);
        } else {
            masterLayout.setFitsSystemWindows(true);
            appContainer.setPadding(0, appTop, 0, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        final Editable searchText = mSearchEditText.getText();

        if (mSharedPreferences.getBoolean(SettingsFragment.KEY_PREF_AUTO_KEYBOARD, false) ||
                searchText.length() > 0) {
            // This is a special case to show SearchEditText should have focus.
            if (searchText.length() == 1 && searchText.charAt(0) == '\0') {
                mSearchEditText.setText(null);
            }

            mSearchEditText.requestFocus();
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            mInputMethodManager.showSoftInput(mSearchEditText, 0);
        } else {
            hideKeyboard();
        }

        if (mSharedPreferences.getBoolean(SettingsFragment.KEY_PREF_ALLOW_ROTATION, false)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);
        }
    }

    private EditText setupSearchEditText() {
        final EditText searchEditText = findViewById(R.id.user_search_input);

        searchEditText.addTextChangedListener(new TextWatcher() {

            final View mClearButton = findViewById(R.id.clear_button);

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                final int seqLength = s.length();

                if (seqLength != 1 || s.charAt(0) != '\0') {
                    mAdapter.getFilter().filter(s);
                }

                // Avoid performing visible app update
                mClearButton.setVisibility(seqLength > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                //do nothing
            }

            @Override
            public void afterTextChanged(final Editable s) {
                //do nothing
            }
        });
        searchEditText.setImeActionLabel(getString(R.string.launch), EditorInfo.IME_ACTION_GO);
        searchEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {

                if (actionId == EditorInfo.IME_ACTION_GO) {
                    Log.d("KEYBOARD", "ACTION_GO");
                    return openFirstActivity();
                }
                return false;
            }
        });
        searchEditText.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if ((event.getAction() == KeyEvent.ACTION_DOWN) &&
                        (keyCode == KeyEvent.KEYCODE_ENTER)) {
                    Log.d("KEYBOARD", "ENTER_KEY");
                    return openFirstActivity();
                }
                return false;
            }
        });

        return searchEditText;
    }

    private void setupViews() {
        final GridView appContainer = findViewById(R.id.appsContainer);
        mSearchEditText = setupSearchEditText();

        registerForContextMenu(appContainer);

        appContainer.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState != SCROLL_STATE_IDLE) {
                    hideKeyboard();
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem,
                                 int visibleItemCount, int totalItemCount) {

            }
        });
        appContainer.setAdapter(mAdapter);

        appContainer.setOnItemClickListener(new OnItemClickListener() {

            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                launchActivity(mAdapter.getItem(position));
            }

        });
    }

    private boolean openFirstActivity() {
        if (!mAdapter.isEmpty()) {
            launchActivity(mAdapter.getItem(0));
            return true;
        }
        return false;
    }

    private void setupPreferences() {
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        if (mSharedPreferences.getBoolean(SettingsFragment.KEY_PREF_NOTIFICATION, false)) {
            final ShortcutNotificationManager shortcutNotificationManager = new ShortcutNotificationManager();
            final String strPriority =
                    mSharedPreferences.getString(SettingsFragment.KEY_PREF_NOTIFICATION_PRIORITY,
                            "low");
            final int priority = ShortcutNotificationManager.getPriorityFromString(strPriority);
            shortcutNotificationManager.showNotification(this, priority);
        }

        if (mSharedPreferences.getBoolean("pref_disable_icons", false)) {
            mAdapter.setIconsDisabled();
        } else {
            mAdapter.setIconsEnabled();
        }

        setPreferredOrder();
    }

    private void setPreferredOrder() {
        final String order = mSharedPreferences.getString("pref_app_preferred_order", "recent");

        if ("recent".equals(order)) {
            mAdapter.enableOrderByRecent();
        } else {
            mAdapter.disableOrderByRecent();
        }

        if ("usage".equals(order)) {
            mAdapter.enableOrderByUsage();
        } else {
            mAdapter.disableOrderByUsage();
        }
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        final String searchEdit = mSearchEditText.getText().toString();

        if (!searchEdit.isEmpty()) {
            outState.putCharSequence(SEARCH_EDIT_TEXT_KEY, searchEdit);
        } else if (mSearchEditText.hasFocus()) {
            // This is a special case to show that the box had focus.
            outState.putCharSequence(SEARCH_EDIT_TEXT_KEY, '\0' + "");
        }

        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        final CharSequence searchEditText =
                savedInstanceState.getCharSequence(SEARCH_EDIT_TEXT_KEY);

        if (searchEditText != null) {
            mSearchEditText.setText(searchEditText);
            mSearchEditText.setSelection(searchEditText.length());
        }
    }

    private boolean isCurrentLauncher() {
        final PackageManager pm = getPackageManager();
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        final ResolveInfo resolveInfo =
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        return resolveInfo != null &&
                getPackageName().equals(resolveInfo.activityInfo.packageName);

    }

    private LaunchableAdapter<LaunchableActivity> loadLaunchableAdapter() {
        final LaunchableAdapter<LaunchableActivity> adapter;
        final Object object = getLastNonConfigurationInstance();

        if (object == null) {
            adapter = loadLaunchableApps();
        } else {
            adapter = new LaunchableAdapter<>(object, this, R.layout.app_grid_item);
        }

        return adapter;
    }

    private LaunchableAdapter<LaunchableActivity> loadLaunchableApps() {
        final PackageManager pm = getPackageManager();
        final Collection<ResolveInfo> infoList = getLaunchableResolveInfos(pm);
        final int infoListSize = infoList.size();
        final LaunchableAdapter<LaunchableActivity> adapter
                = new LaunchableAdapter<>(this, R.layout.app_grid_item, infoListSize);
        final int cores = Runtime.getRuntime().availableProcessors();
        final String thisCanonicalName = getClass().getCanonicalName();

        if (cores <= 1) {
            for (final ResolveInfo info : infoList) {
                // Don't include activities from this package.
                if (!thisCanonicalName.startsWith(info.activityInfo.packageName)) {
                final LaunchableActivity launchableActivity = new LaunchableActivity(
                        info.activityInfo, info.activityInfo.loadLabel(pm).toString(), false);

                    adapter.add(launchableActivity);
                }
            }
        } else {
            final SimpleTaskConsumerManager simpleTaskConsumerManager =
                    new SimpleTaskConsumerManager(cores, infoListSize);

            final LoadLaunchableActivityTask.SharedData sharedAppLoadData =
                    new LoadLaunchableActivityTask.SharedData(pm, adapter);

            for (final ResolveInfo info : infoList) {
                // Don't include activities from this package.
                if (!thisCanonicalName.startsWith(info.activityInfo.packageName)) {
                    final LoadLaunchableActivityTask loadLaunchableActivityTask =
                            new LoadLaunchableActivityTask(info, sharedAppLoadData);
                    simpleTaskConsumerManager.addTask(loadLaunchableActivityTask);
                }
            }

            //Log.d("MultithreadStartup","waiting for completion of all tasks");
            simpleTaskConsumerManager.destroyAllConsumers(true, true);
            //Log.d("MultithreadStartup", "all tasks ok");
        }

        adapter.sortApps();
        adapter.notifyDataSetChanged();

        return adapter;
    }

    private void hideKeyboard() {
        final View focus = getCurrentFocus();

        if (focus != null) {
            mInputMethodManager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
        findViewById(R.id.appsContainer).requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
    }

    private void handlePackageChanged() {
        final SharedPreferences.Editor editor = mSharedPreferences.edit();
        final String[] packageChangedNames = mSharedPreferences.getString("package_changed_name", "")
                .split(" ");
        final Intent intent = new Intent(Intent.ACTION_MAIN);

        editor.remove("package_changed_name");
        editor.apply();

        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        for (String packageName : packageChangedNames) {
            packageName = packageName.trim();

            if (!packageName.isEmpty()) {
                intent.setPackage(packageName);
                final Collection<ResolveInfo> infoList =
                        getPackageManager().queryIntentActivities(intent, 0);

                if (infoList.isEmpty()) {
                    Log.d("SearchActivity",
                            "No activities in list. Uninstall detected: " + packageName);
                    mAdapter.remove(packageName);
                } else {
                    Log.d("SearchActivity", "Activities in list. Install/update detected!");
                    final PackageManager pm = getPackageManager();

                    for (final ResolveInfo info : infoList) {
                        if (mAdapter.getPosition(info.activityInfo.packageName) == -1) {
                            final LaunchableActivity launchableActivity = new LaunchableActivity(
                                    info.activityInfo, info.activityInfo.loadLabel(pm).toString(),
                                    false);
                            mAdapter.add(launchableActivity);
                        }
                    }
                }
            }
        }

        mAdapter.sortApps();
    }

    @Override
    public void onBackPressed() {
        if (isCurrentLauncher()) {
            hideKeyboard();
        } else {
            moveTaskToBack(false);
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mPackageChangedReceiver);
        if (!isChangingConfigurations()) {
            Log.d("HayaiLauncher", "Hayai is ded");
        }
        mAdapter.onDestroy();
        super.onDestroy();
    }

    public void showPopup(View v) {
        final PopupMenu popup = new PopupMenu(this, v);
        popup.setOnMenuItemClickListener(new PopupEventListener());
        final MenuInflater inflater = popup.getMenuInflater();
        inflater.inflate(R.menu.search_activity_menu, popup.getMenu());
        popup.show();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        //does this need to run in uiThread?
        if (key.equals("package_changed_name") && !sharedPreferences.getString(key, "").isEmpty()) {
            handlePackageChanged();
        } else if (key.equals("pref_app_preferred_order")) {
            setPreferredOrder();
            mAdapter.sortApps();
        } else if (key.equals("pref_disable_icons")) {
            recreate();
        }
    }

    /**
     * This method is called when the user is already in this activity and presses the {@code home}
     * button. Use this opportunity to return this activity back to a default state.
     *
     * @param intent The incoming {@link Intent} sent by this activity
     */
    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);

        // If search has been typed, and home is hit, clear it.
        if (mSearchEditText.length() > 0) {
            mSearchEditText.setText(null);
        }

        closeContextMenu();

        // If the y coordinate is not at 0, let's reset it.
        final GridView view = (GridView) findViewById(R.id.appsContainer);
        final int[] loc = { 0, 0 };
        view.getLocationInWindow(loc);
        if (loc[1] != 0) {
            view.smoothScrollToPosition(0);
        }
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showPopup(mOverflowButtonTopleft);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == TRIM_MEMORY_COMPLETE)
            mAdapter.clearCaches();

    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
                                    ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.app, menu);

        if (menuInfo instanceof AdapterContextMenuInfo) {
            final AdapterContextMenuInfo adapterMenuInfo = (AdapterContextMenuInfo) menuInfo;
            final LaunchableActivity activity = (LaunchableActivity) adapterMenuInfo.targetView
                    .findViewById(R.id.appIcon).getTag();
            final MenuItem item = menu.findItem(R.id.appmenu_pin_to_top);

            menu.setHeaderTitle(activity.getActivityLabel());

            if (activity.getPriority() == 0) {
                item.setTitle(R.string.appmenu_pin_to_top);
            } else {
                item.setTitle(R.string.appmenu_remove_pin);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.action_settings:
                final Intent intentSettings = new Intent(this, SettingsActivity.class);
                startActivity(intentSettings);
                return true;
            case R.id.action_refresh_app_list:
                recreate();
                return true;
            case R.id.action_system_settings:
                final Intent intentSystemSettings = new Intent(Settings.ACTION_SETTINGS);
                intentSystemSettings.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intentSystemSettings);
                return true;
            case R.id.action_manage_apps:
                final Intent intentManageApps = new Intent(Settings.ACTION_APPLICATION_SETTINGS);
                intentManageApps.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intentManageApps);
                return true;
            case R.id.action_set_wallpaper:
                final Intent intentWallpaperPicker = new Intent(Intent.ACTION_SET_WALLPAPER);
                startActivity(intentWallpaperPicker);
                return true;
            case R.id.action_about:
                final Intent intentAbout = new Intent(this, AboutActivity.class);
                startActivity(intentAbout);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }


    @Override
    public boolean onContextItemSelected(MenuItem item) {
        final AdapterContextMenuInfo info = (AdapterContextMenuInfo) item
                .getMenuInfo();
        final View itemView = info.targetView;
        final LaunchableActivity launchableActivity =
                (LaunchableActivity) itemView.findViewById(R.id.appIcon).getTag();
        switch (item.getItemId()) {
            case R.id.appmenu_launch:
                launchActivity(launchableActivity);
                return true;
            case R.id.appmenu_info:
                final Intent intent = new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:"
                        + launchableActivity.getComponent().getPackageName()));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            case R.id.appmenu_onplaystore:
                final Intent intentPlayStore = new Intent(Intent.ACTION_VIEW);
                intentPlayStore.setData(Uri.parse("market://details?id=" +
                        launchableActivity.getComponent().getPackageName()));
                startActivity(intentPlayStore);
                return true;
            case R.id.appmenu_pin_to_top:
                final LaunchableActivityPrefs prefs = new LaunchableActivityPrefs(this);
                launchableActivity.setPriority(launchableActivity.getPriority() == 0 ? 1 : 0);
                prefs.writePreference(launchableActivity);
                mAdapter.sortApps();
                return true;
            default:
                return false;
        }

    }

    public void onClickSettingsButton(View view) {
        showPopup(mOverflowButtonTopleft);


    }

    public void launchActivity(final LaunchableActivity launchableActivity) {
        final LaunchableActivityPrefs prefs = new LaunchableActivityPrefs(this);

        hideKeyboard();
        try {
            startActivity(launchableActivity.getLaunchIntent(mSearchEditText.getText().toString()));
            mSearchEditText.setText(null);
            launchableActivity.setLaunchTime();
            launchableActivity.addUsage();
            prefs.writePreference(launchableActivity);
            mAdapter.sortApps();
        } catch (ActivityNotFoundException e) {
            //this should only happen when the launcher still hasn't updated the file list after
            //an activity removal.
            Toast.makeText(this, getString(R.string.activity_not_found),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public void onClickClearButton(View view) {
        mSearchEditText.setText("");
    }

    class PopupEventListener implements PopupMenu.OnMenuItemClickListener {
        @Override
        public boolean onMenuItemClick(MenuItem item) {
            return onOptionsItemSelected(item);
        }
    }
}
