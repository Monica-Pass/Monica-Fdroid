package com.monica.mdbx3.smoke;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public final class MainActivity extends Activity {
    static {
        System.loadLibrary("mdbx_ffi");
        System.loadLibrary("mdbx3_smoke");
    }

    private static native int contractVersion();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int contract = contractVersion();
        if (contract != 30) {
            throw new IllegalStateException("unexpected UniFFI contract: " + contract);
        }
        Log.i("mdbx3_smoke", "android-loader-smoke-ok contract=" + contract);
        TextView result = new TextView(this);
        result.setText("MDBX3 loader OK; UniFFI contract " + contract);
        setContentView(result);
    }
}
