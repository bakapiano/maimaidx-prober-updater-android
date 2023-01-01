package com.bakapiano.maimai.updater.ui;

import static com.bakapiano.maimai.updater.ui.DataContext.HookHost;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SwitchCompat;
import android.support.v7.widget.Toolbar;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Calendar;
import java.util.Random;

import com.bakapiano.maimai.updater.R;
import com.bakapiano.maimai.updater.crawler.Callback;
import com.bakapiano.maimai.updater.crawler.CrawlerCaller;
import com.bakapiano.maimai.updater.server.HttpServerService;
import com.bakapiano.maimai.updater.vpn.core.Constant;
import com.bakapiano.maimai.updater.vpn.core.LocalVpnService;
import com.bakapiano.maimai.updater.vpn.core.ProxyConfig;

public class MainActivity extends AppCompatActivity implements
        OnCheckedChangeListener,
        LocalVpnService.onStatusChangedListener {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int START_VPN_SERVICE_REQUEST_CODE = 1985;
    private static String GL_HISTORY_LOGS;
    private SwitchCompat switchProxy;
    private TextView textViewLog;
    private ScrollView scrollViewLog;
    private Calendar mCalendar;

    private SharedPreferences mContextSp;

    private void updateTilte() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            if (LocalVpnService.IsRunning) {
                actionBar.setTitle(getString(R.string.connected));
            } else {
                actionBar.setTitle(getString(R.string.disconnected));
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        scrollViewLog = (ScrollView) findViewById(R.id.scrollViewLog);
        textViewLog = (TextView) findViewById(R.id.textViewLog);

        assert textViewLog != null;
        textViewLog.setText(GL_HISTORY_LOGS);
        textViewLog.setMovementMethod(ScrollingMovementMethod.getInstance());
        scrollViewLog.fullScroll(ScrollView.FOCUS_DOWN);

        mCalendar = Calendar.getInstance();
        LocalVpnService.addOnStatusChangedListener(this);

        mContextSp = this.getSharedPreferences(
                "com.bakapiano.maimai.updater.data",
                Context.MODE_PRIVATE);

        CrawlerCaller.listener = this;

        loadContextData();
    }

    private void inputAddress() {
//        AlertDialog.Builder builder = new AlertDialog.Builder(this);
//        builder.setTitle("Http com.bakapiano.maimai.com.bakapiano.maimai.proxy server");
//        final EditText input = new EditText(this);
//        input.setText(ProxyConfig.getHttpProxyServer(this));
//        builder.setView(input);
//        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
//            @Override
//            public void onClick(DialogInterface dialog, int which) {
//                String text = input.getText().toString();
//                ProxyConfig.Instance.setProxy(text);
//                ProxyConfig.setHttpProxyServer(MainActivity.this, text);
//            }
//        });
//        builder.setCancelable(false);
//        builder.show();
        String text = "http://user:pass@127.0.0.1:8848";
        ProxyConfig.Instance.setProxy(text);
        ProxyConfig.setHttpProxyServer(MainActivity.this, text);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTilte();
    }

    String getVersionName() {
        PackageManager packageManager = getPackageManager();
        if (packageManager == null) {
            Log.e(TAG, "null package manager is impossible");
            return null;
        }

        try {
            return packageManager.getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "package not found is impossible", e);
            return null;
        }
    }

    boolean isValidUrl(String url) {
        try {
            if (url == null || url.isEmpty())
                return false;

            if (url.startsWith("/")) {//file path
                File file = new File(url);
                if (!file.exists()) {
                    onLogReceived(String.format("File(%s) not exists.", url));
                    return false;
                }
                if (!file.canRead()) {
                    onLogReceived(String.format("File(%s) can't read.", url));
                    return false;
                }
            } else { //url
                Uri uri = Uri.parse(url);
                if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
                    return false;
                if (uri.getHost() == null)
                    return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressLint("DefaultLocale")
    @Override
    public void onLogReceived(String logString) {
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        logString = String.format("[%1$02d:%2$02d:%3$02d] %4$s\n",
                mCalendar.get(Calendar.HOUR_OF_DAY),
                mCalendar.get(Calendar.MINUTE),
                mCalendar.get(Calendar.SECOND),
                logString);

        Log.d(Constant.TAG, logString);

//        if (textViewLog.getLineCount() > 200) {
//            textViewLog.setText("");
//        }

        textViewLog.append(logString);
        scrollViewLog.fullScroll(ScrollView.FOCUS_DOWN);
        GL_HISTORY_LOGS = textViewLog.getText() == null ? "" : textViewLog.getText().toString();
    }

    @Override
    public void onStatusChanged(String status, Boolean isRunning) {
        switchProxy.setEnabled(true);
        switchProxy.setChecked(isRunning);
        onLogReceived(status);
        updateTilte();
        Toast.makeText(this, status, Toast.LENGTH_SHORT).show();
    }

    private Object switchLock = new Object();

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (!switchProxy.isEnabled()) return;
        Context context = this;
        if (LocalVpnService.IsRunning != isChecked) {
            switchProxy.setEnabled(false);
            if (isChecked) {
                checkProberAccount(result -> {
                    this.runOnUiThread(() -> {
                        if ((Boolean) result) {
                            // Start vpn service
                            Intent intent = LocalVpnService.prepare(context);
                            if (intent == null) {
                                startVPNService();
                                // Jump to wechat app
                                getWechatApi();
                            } else {
                                startActivityForResult(intent, START_VPN_SERVICE_REQUEST_CODE);
                            }
                            // Start http service
                            startHttpService();

                            String link = "http://" + getRandomString(10) + ".redirect." + HookHost;
                            if (DataContext.CompatibleMode) {
                                link = "https://maimai.bakapiano.com/shortcut?username="
                                        + DataContext.Username + "&password=" + DataContext.Password;
                            }

                            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            ClipData clip = ClipData.newPlainText("link", link);
                            clipboard.setPrimaryClip(clip);
                        } else {
                            switchProxy.setChecked(false);
                            switchProxy.setEnabled(true);
                        }
                    });
                });
            } else {
                LocalVpnService.IsRunning = false;
                stopHttpService();
            }
        }
    }

    private void startHttpService() {
        startService(new Intent(this, HttpServerService.class));
    }

    private void stopHttpService() {
        stopService(new Intent(this, HttpServerService.class));
    }

    private void startVPNService() {
        textViewLog.setText("");
        GL_HISTORY_LOGS = null;
        onLogReceived("starting...");
        startService(new Intent(this, LocalVpnService.class));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == START_VPN_SERVICE_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startVPNService();
                // Jump to wechat app
                getWechatApi();
            } else {
                switchProxy.setChecked(false);
                switchProxy.setEnabled(true);
                onLogReceived("canceled.");
            }
            return;
        }

        super.onActivityResult(requestCode, resultCode, intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_activity_actions, menu);

        MenuItem menuItem = menu.findItem(R.id.menu_item_switch);
        if (menuItem == null) {
            return false;
        }

        switchProxy = (SwitchCompat) menuItem.getActionView();
        if (switchProxy == null) {
            return false;
        }

        switchProxy.setChecked(LocalVpnService.IsRunning);
        switchProxy.setOnCheckedChangeListener(this);

        if (!switchProxy.isChecked()) {
            inputAddress();
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_item_about:
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.app_name) + " " + getVersionName())
                        .setMessage(R.string.about_info)
                        .setPositiveButton(R.string.btn_ok, null)
                        .show();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onDestroy() {
        LocalVpnService.removeOnStatusChangedListener(this);
        super.onDestroy();
    }

    public void saveText(View view) {
        Context context = this;
        checkProberAccount(result -> {
            if ((Boolean) result) {
                saveContextData();
                this.runOnUiThread(() -> {
                    new AlertDialog.Builder(context)
                            .setTitle(getString(R.string.app_name) + " " + getVersionName())
                            .setMessage("查分器账户保存成功")
                            .setPositiveButton(R.string.btn_ok, null)
                            .show();
                });
            }
        });
    }

    private void showInvalidAccountDialog() {
        Log.d(TAG, "testtest");
        this.runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.app_name) + " " + getVersionName())
                    .setMessage("查分账户信息无效")
                    .setPositiveButton(R.string.btn_ok, null)
                    .show();
        });
    }

    private void checkProberAccount(Callback callback) {
        DataContext.Username = ((TextView) findViewById(R.id.username)).getText().toString();
        DataContext.Password = ((TextView) findViewById(R.id.password)).getText().toString();
        if (DataContext.Username == null || DataContext.Password == null) {
            showInvalidAccountDialog();
            callback.onResponse(false);
            return;
        }
        CrawlerCaller.verifyAccount(DataContext.Username, DataContext.Password, result -> {
            if (!(Boolean) result) showInvalidAccountDialog();
            callback.onResponse(result);
        });
    }


    private void loadContextData() {
        String username = mContextSp.getString("username", null);
        String password = mContextSp.getString("password", null);
        ((TextView) findViewById(R.id.username)).setText(username);
        ((TextView) findViewById(R.id.password)).setText(password);
        DataContext.Username = username;
        DataContext.Password = password;
    }

    private void saveContextData() {
        SharedPreferences.Editor editor = mContextSp.edit();
        editor.putString("username", DataContext.Username);
        editor.putString("password", DataContext.Password);
        editor.apply();
    }

    private void getWechatApi() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            ComponentName cmp = new ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI");
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setComponent(cmp);
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    public static String getRandomString(int length){
        String str="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random random = new Random();
        StringBuffer sb = new StringBuffer();
        for(int i=0;i<length;i++){
            int number=random.nextInt(62);
            sb.append(str.charAt(number));
        }
        return sb.toString();
    }
}
