package takagi.ru.monica.autofill_ng.fixture;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.view.inputmethod.InputConnection;

/** An IME without inline suggestions, for testing Android's dropdown path. */
public class AutofillFixtureInputMethodService extends InputMethodService {
    @Override public View onCreateInputView() {
        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.VERTICAL);
        addKey(keys, "fixture-ime-prefix", connection -> connection.setComposingText("employee-", 1));
        addKey(keys, "fixture-ime-suffix", connection -> {
            connection.setComposingText(AutofillFormFixtureActivity.USERNAME, 1);
            connection.finishComposingText();
        });
        addKey(keys, "fixture-ime-password", connection -> connection.commitText(AutofillFormFixtureActivity.PASSWORD, 1));
        return keys;
    }

    private interface InputAction { void run(InputConnection connection); }

    private void addKey(LinearLayout keys, String label, InputAction action) {
        Button key = new Button(this);
        key.setText(label);
        key.setContentDescription(label);
        key.setOnClickListener(view -> {
            InputConnection connection = getCurrentInputConnection();
            if (connection != null) action.run(connection);
        });
        keys.addView(key, new LinearLayout.LayoutParams(-1, -2));
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }
}
