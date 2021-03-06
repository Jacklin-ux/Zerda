/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.activity;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.BottomSheetDialog;
import android.support.design.widget.Snackbar;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebStorage;

import org.mozilla.focus.R;
import org.mozilla.focus.fragment.BrowserFragment;
import org.mozilla.focus.fragment.FirstrunFragment;
import org.mozilla.focus.fragment.ListPanelDialog;
import org.mozilla.focus.fragment.ScreenCaptureDialogFragment;
import org.mozilla.focus.home.HomeFragment;
import org.mozilla.focus.locale.LocaleAwareAppCompatActivity;
import org.mozilla.focus.telemetry.TelemetryWrapper;
import org.mozilla.focus.urlinput.UrlInputFragment;
import org.mozilla.focus.utils.Constants;
import org.mozilla.focus.utils.FileUtils;
import org.mozilla.focus.utils.FormatUtils;
import org.mozilla.focus.utils.NoRemovableStorageException;
import org.mozilla.focus.utils.SafeIntent;
import org.mozilla.focus.utils.Settings;
import org.mozilla.focus.utils.StorageUtils;
import org.mozilla.focus.web.BrowsingSession;
import org.mozilla.focus.web.IWebView;
import org.mozilla.focus.web.WebViewProvider;
import org.mozilla.focus.widget.FragmentListener;

import java.io.File;
import java.lang.ref.WeakReference;

import static org.mozilla.focus.screenshot.ScreenshotViewerActivity.REQ_CODE_NOTIFY_SCREENSHOT_DELETE;

public class MainActivity extends LocaleAwareAppCompatActivity implements FragmentListener,SharedPreferences.OnSharedPreferenceChangeListener{

    public static final String ACTION_OPEN = "open";

    public static final String EXTRA_TEXT_SELECTION = "text_selection";
    private boolean mTurboModePref = true;
    private boolean mBlockImgPref = true;
    private static int REQUEST_CODE_STORAGE_PERMISSION = 101;
    private static final Handler HANDLER = new Handler();

    private String pendingUrl;

    private BottomSheetDialog menu;
    private View nextButton;
    private View refreshButton;
    private View shareButton;
    private View captureButton;

    private MainMediator mediator;
    private boolean safeForFragmentTransactions = false;
    private DialogFragment mDialogFragment;

    private BroadcastReceiver uiMessageReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTurboModePref = Settings.getInstance(this).shouldUseTurboMode();
        mBlockImgPref = Settings.getInstance(this).shouldBlockImages();

        asyncInitialize();

        setContentView(R.layout.activity_main);
        initViews();
        initBroadcastReceivers();

        mediator = new MainMediator(this);

        SafeIntent intent = new SafeIntent(getIntent());

        if (savedInstanceState == null) {
            if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                final String url = intent.getDataString();

                BrowsingSession.getInstance().loadCustomTabConfig(this, intent);

                if (Settings.getInstance(this).shouldShowFirstrun()) {
                    pendingUrl = url;
                    this.mediator.showFirstRun();
                } else {
                    this.mediator.showBrowserScreen(url, true);
                }
            } else {
                if (Settings.getInstance(this).shouldShowFirstrun()) {
                    this.mediator.showFirstRun();
                } else {
                    this.mediator.showHomeScreen();
                }
            }
        }
        WebViewProvider.preload(this);
    }

    private void initBroadcastReceivers() {
        uiMessageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final CharSequence msg = intent.getCharSequenceExtra(Constants.EXTRA_MESSAGE);
                showMessage(msg);
            }
        };
    }

    @Override
    public void applyLocale() {
        // We don't care here: all our fragments update themselves as appropriate
    }

    @Override
    protected void onStart() {
        // TODO: handle fragment creation
        //HomeFragment homeFragment = (HomeFragment) getSupportFragmentManager().findFragmentByTag(HomeFragment.FRAGMENT_TAG);
        //if (homeFragment != null) {
        //    getTopSitesPresenter().setView(homeFragment);
        //}
        //UrlInputFragment urlInputFragment = (UrlInputFragment) getSupportFragmentManager().findFragmentByTag(UrlInputFragment.FRAGMENT_TAG);
        //if (urlInputFragment != null) {
        //    getUrlInputPresenter().setView(urlInputFragment);
        //}
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();

        TelemetryWrapper.startSession();
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(this);

        final IntentFilter uiActionFilter = new IntentFilter(Constants.ACTION_NOTIFY_UI);
        uiActionFilter.addCategory(Constants.CATEGORY_FILE_OPERATION);
        registerReceiver(uiMessageReceiver, uiActionFilter);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        safeForFragmentTransactions = true;
    }

    @Override
    protected void onPause() {
        super.onPause();

        unregisterReceiver(uiMessageReceiver);

        safeForFragmentTransactions = false;
        TelemetryWrapper.stopSession();

        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();

        TelemetryWrapper.stopMainActivity();
    }

    @Override
    protected void onNewIntent(Intent unsafeIntent) {
        final SafeIntent intent = new SafeIntent(unsafeIntent);
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            // We can't update our fragment right now because we need to wait until the activity is
            // resumed. So just remember this URL and load it in onResumeFragments().
            pendingUrl = intent.getDataString();
        }

        if (ACTION_OPEN.equals(intent.getAction())) {
            TelemetryWrapper.openNotificationActionEvent();
        }

        // We do not care about the previous intent anymore. But let's remember this one.
        setIntent(unsafeIntent);
        BrowsingSession.getInstance().loadCustomTabConfig(this, intent);
    }

    @Override
    protected void onResumeFragments() {
        super.onResumeFragments();

        if (pendingUrl != null && !Settings.getInstance(this).shouldShowFirstrun()) {
            // We have received an URL in onNewIntent(). Let's load it now.
            // Unless we're trying to show the firstrun screen, in which case we leave it pending until
            // firstrun is dismissed.
            this.mediator.showBrowserScreen(pendingUrl, true);
            pendingUrl = null;
        }
    }

    private void initViews() {
        int visibility = getWindow().getDecorView().getSystemUiVisibility();
        // do not overwrite existing value
        visibility |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        getWindow().getDecorView().setSystemUiVisibility(visibility);

        setUpMenu();
    }

    private void setUpMenu() {
        final View sheet = getLayoutInflater().inflate(R.layout.bottom_sheet_main_menu, null);
        menu = new BottomSheetDialog(this);
        menu.setContentView(sheet);
        nextButton = menu.findViewById(R.id.action_next);
        refreshButton = menu.findViewById(R.id.action_refresh);
        shareButton = menu.findViewById(R.id.action_share);
        captureButton = menu.findViewById(R.id.capture_page);
        menu.findViewById(R.id.menu_turbomode).setSelected(mTurboModePref);
        menu.findViewById(R.id.menu_blockimg).setSelected(mBlockImgPref);
    }

    private void showMenu() {
        final BrowserFragment browserFragment = getVisibleBrowserFragment();
        final boolean hasLoadedPage = browserFragment != null && !browserFragment.isLoading();
        final boolean canGoForward = browserFragment != null && browserFragment.canGoForward();
        setEnable(nextButton, canGoForward);
        setEnable(refreshButton, hasLoadedPage);
        setEnable(shareButton, hasLoadedPage);
        setEnable(captureButton, hasLoadedPage);
        menu.show();
    }

    private BrowserFragment getVisibleBrowserFragment() {
        final BrowserFragment browserFragment = getBrowserFragment();
        if (browserFragment == null || !browserFragment.isVisible()) {
            return null;
        } else {
            return browserFragment;
        }
    }

    private void showListPanel(int type) {
        DialogFragment dialogFragment = ListPanelDialog.newInstance(type);
        dialogFragment.setCancelable(true);
        dialogFragment.show(getSupportFragmentManager(), "");
        mDialogFragment = dialogFragment;
    }

    public void onMenuItemClicked(View v) {
        final View container = findViewById(R.id.container);
        final int stringResource;
        if(!v.isEnabled()) {
            return;
        }
        menu.cancel();
        switch (v.getId()) {
            case R.id.menu_blockimg:
                mBlockImgPref = !mBlockImgPref;
                v.setSelected(mBlockImgPref);
                String blockImagePrefKey = this.getResources().getString(R.string.pref_key_performance_block_images);
                PreferenceManager.getDefaultSharedPreferences(this)
                        .edit()
                        .putBoolean(blockImagePrefKey, mBlockImgPref)
                        .apply();
                stringResource = mBlockImgPref ? R.string.message_enable_block_image : R.string.message_disable_block_image;
                Snackbar.make(container, stringResource, Snackbar.LENGTH_SHORT).show();
                break;
            case R.id.menu_turbomode:
                mTurboModePref = !mTurboModePref;
                v.setSelected(mTurboModePref);
                String turboModePrefKey = this.getResources().getString(R.string.pref_key_turbo_mode);
                PreferenceManager.getDefaultSharedPreferences(this)
                        .edit()
                        .putBoolean(turboModePrefKey, mTurboModePref)
                        .apply();
                stringResource = mTurboModePref ? R.string.message_enable_turbo_mode : R.string.message_disable_turbo_mode;
                Snackbar.make(container, stringResource, Snackbar.LENGTH_SHORT).show();
                break;
            case R.id.menu_delete:
                onDeleteClicked();
                break;
            case R.id.menu_download:
                onDownloadClicked();
                break;
            case R.id.menu_history:
                onHistoryClicked();
                break;
            case R.id.menu_screenshots:
                onScreenshotsClicked();
                break;
            case R.id.menu_preferences:
                onPreferenceClicked();
                break;
            case R.id.action_next:
            case R.id.action_refresh:
            case R.id.action_share:
            case R.id.capture_page:
                onMenuBrowsingItemClicked(v);
                break;
            default:
                throw new RuntimeException("Unknown id in menu, onMenuItemClicked() is only for" +
                        " known ids");
        }
    }

    private void setEnable(View v, boolean enable) {
        v.setEnabled(enable);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for(int i=0 ; i < vg.getChildCount() ; i++) {
                setEnable(((ViewGroup) v).getChildAt(i), enable);
            }
        }
    }

    public void onMenuBrowsingItemClicked(View v) {
        final BrowserFragment browserFragment = getVisibleBrowserFragment();
        if (browserFragment == null) {
            return;
        }
        switch (v.getId()) {
            case R.id.action_next:
                onNextClicked(browserFragment);
                break;
            case R.id.action_refresh:
                onRefreshClicked(browserFragment);
                break;
            case R.id.action_share:
                onShraeClicked(browserFragment);
                break;
            case R.id.capture_page:
                onCapturePageClicked(browserFragment);
                break;
            default:
                throw new RuntimeException("Unknown id in menu, onMenuBrowsingItemClicked() is" +
                        " only for known ids");
        }
    }

    private void onPreferenceClicked() {
        openPreferences();
    }

    private void onDownloadClicked() {
        showListPanel(ListPanelDialog.TYPE_DOWNLOADS);
    }

    private void onHistoryClicked() {
        showListPanel(ListPanelDialog.TYPE_HISTORY);
    }

    private void onScreenshotsClicked() {
        showListPanel(ListPanelDialog.TYPE_SCREENSHOTS);
    }

    private void onDeleteClicked() {
        WebStorage.getInstance().deleteAllData();
        
        final long diff = FileUtils.deleteWebViewCacheDirectory(this);
        final int stringId = (diff < 0) ? R.string.message_clear_cache_fail : R.string.message_cleared_cached;
        final String msg = getString(stringId, FormatUtils.getReadableStringFromFileSize(diff));
        showMessage(msg);
    }

    private BrowserFragment getBrowserFragment() {
        return (BrowserFragment) getSupportFragmentManager().findFragmentByTag(BrowserFragment.FRAGMENT_TAG);
    }

    private void onBackClicked(final BrowserFragment browserFragment) {
        browserFragment.goBack();
    }

    private void onNextClicked(final BrowserFragment browserFragment) {
        browserFragment.goForward();
    }

    private void onRefreshClicked(final BrowserFragment browserFragment) {
        browserFragment.reload();
    }

    private void onCapturePageClicked(final BrowserFragment browserFragment) {
        if (PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            // We do have the permission to write to the external storage.
            showLoadingAndCapture(browserFragment);
        } else {
            // We do not have the permission to write to the external storage. Request the permission and start the
            // capture from onRequestPermissionsResult().
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CODE_STORAGE_PERMISSION);
            }
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // Only refresh when disabling turbo mode
        if (this.getResources().getString(R.string.pref_key_turbo_mode).equals(key) && !Settings.getInstance(this).shouldUseTurboMode()){
            if (getVisibleBrowserFragment() != null){
                getVisibleBrowserFragment().reload();
            }
        }
        // For turbo mode, a automatic refresh is done when we disable block image.
    }

    private static final class CaptureRunnable implements Runnable {
        private final WeakReference<BrowserFragment> browserFragmentWeakReference;
        private final WeakReference<ScreenCaptureDialogFragment> screenCaptureDialogFragmentWeakReference;
        private final WeakReference<View> containerWeakReference;

        public CaptureRunnable(BrowserFragment browserFragment, ScreenCaptureDialogFragment screenCaptureDialogFragment, View container) {
            browserFragmentWeakReference = new WeakReference<>(browserFragment);
            screenCaptureDialogFragmentWeakReference = new WeakReference<>(screenCaptureDialogFragment);
            containerWeakReference = new WeakReference<>(container);
        }

        @Override
        public void run() {
            BrowserFragment browserFragment = browserFragmentWeakReference.get();
            ScreenCaptureDialogFragment screenCaptureDialogFragment = screenCaptureDialogFragmentWeakReference.get();
            View view = containerWeakReference.get();
            int captureResultResource = R.string.screenshot_failed;
            if (browserFragment != null && browserFragment.capturePage()) {
                captureResultResource = R.string.screenshot_saved;
            }
            if (screenCaptureDialogFragment != null) {
                screenCaptureDialogFragment.dismiss();
            }
            if (view != null) {
                Snackbar.make(view, captureResultResource, Snackbar.LENGTH_SHORT).show();
            }
        }
    }

    private void showLoadingAndCapture(final BrowserFragment browserFragment) {
        if (!safeForFragmentTransactions) {
            return;
        }
        final ScreenCaptureDialogFragment capturingFragment = ScreenCaptureDialogFragment.newInstance();
        capturingFragment.show(getSupportFragmentManager(), "capturingFragment");
        final int WAIT_INTERVAL = 50;
        // Post delay to wait for Dialog to show
        HANDLER.postDelayed(new CaptureRunnable(browserFragment, capturingFragment, findViewById(R.id.container)), WAIT_INTERVAL);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                final BrowserFragment browserFragment = getBrowserFragment();
                if (browserFragment == null || !browserFragment.isVisible()) {
                    return;
                }
                showLoadingAndCapture(browserFragment);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if(resultCode == RESULT_OK && requestCode == REQ_CODE_NOTIFY_SCREENSHOT_DELETE && mDialogFragment != null) {
            Snackbar.make(mDialogFragment.getView(), R.string.screenshot_image_viewer_msg_image_deleted, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void onShraeClicked(final BrowserFragment browserFragment) {
        final Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, browserFragment.getUrl());
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_dialog_title)));

        TelemetryWrapper.shareEvent();
    }

    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
        if (name.equals(IWebView.class.getName())) {
            View v = WebViewProvider.create(this, attrs);
            return v;
        }

        return super.onCreateView(name, context, attrs);
    }

    @Override
    public void onBackPressed() {
        if (this.mediator.handleBackKey()) {
            return;
        }
        super.onBackPressed();
    }

    public void firstrunFinished() {
        if (pendingUrl != null) {
            // We have received an URL in onNewIntent(). Let's load it now.
            this.mediator.showBrowserScreen(pendingUrl, true);
            pendingUrl = null;
        } else {
            this.mediator.showHomeScreen();
        }
    }

    @Override
    public void onNotified(@NonNull Fragment from, @NonNull TYPE type, @Nullable Object payload) {
        switch (type) {
            case OPEN_URL:
                if ((payload != null) && (payload instanceof String)) {
                    this.mediator.showBrowserScreen(payload.toString(), false);
                }
                break;
            case OPEN_PREFERENCE:
                openPreferences();
                break;
            case SHOW_HOME:
                this.mediator.showHomeScreen();
                break;
            case SHOW_MENU:
                this.showMenu();
                break;
            case SHOW_URL_INPUT:
                final String url = (payload != null) ? payload.toString() : null;
                this.mediator.showUrlInput(url);
                break;
            case DISMISS_URL_INPUT:
                this.mediator.dismissUrlInput();
                break;
            case FRAGMENT_STARTED:
                if ((payload != null) && (payload instanceof String)) {
                    this.mediator.onFragmentStarted(((String) payload).toLowerCase());
                }
                break;
            case FRAGMENT_STOPPED:
                if ((payload != null) && (payload instanceof String)) {
                    this.mediator.onFragmentStopped(((String) payload).toLowerCase());
                }
                break;
        }
    }

    public FirstrunFragment createFirstRunFragment() {
        return FirstrunFragment.create();
    }

    public BrowserFragment createBrowserFragment(@Nullable String url) {
        BrowserFragment fragment = BrowserFragment.create(url);
        return fragment;
    }

    public UrlInputFragment createUrlInputFragment(@Nullable String url) {
        final UrlInputFragment fragment = UrlInputFragment.create(url);
        return fragment;
    }

    public HomeFragment createHomeFragment() {
        final HomeFragment fragment = HomeFragment.create();
        return fragment;
    }

    public void sendBrowsingTelemetry() {
        final SafeIntent intent = new SafeIntent(getIntent());
        if (intent.getBooleanExtra(EXTRA_TEXT_SELECTION, false)) {
            TelemetryWrapper.textSelectionIntentEvent();
        } else if (BrowsingSession.getInstance().isCustomTab()) {
            TelemetryWrapper.customTabsIntentEvent(BrowsingSession.getInstance().getCustomTabConfig().getOptionsList());
        } else {
            TelemetryWrapper.browseIntentEvent();
        }
    }

    private void showMessage(@NonNull CharSequence msg) {
        if (TextUtils.isEmpty(msg)) {
            return;
        }
        final View container = findViewById(R.id.container);
        Snackbar.make(container, msg, Snackbar.LENGTH_SHORT).show();
    }

    private void asyncInitialize() {
        (new Thread(new Runnable() {
            @Override
            public void run() {
                asyncCheckStorage();
            }
        })).start();
    }

    /**
     * To check existence of removable storage, and write result to preference
     */
    private void asyncCheckStorage() {
        boolean exist;
        try {
            final File dir = StorageUtils.getTargetDirOnRemovableStorageForDownloads(this, "*/*");
            exist = (dir != null);
        } catch (NoRemovableStorageException e) {
            exist = false;
        }

        Settings.getInstance(this).setRemovableStorageStateOnCreate(exist);
    }
}
