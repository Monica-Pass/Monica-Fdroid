package takagi.ru.monica.autofill_ng.fixture;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.widget.TextView;

/** An IME without inline suggestions, for testing Android's dropdown path. */
public class AutofillFixtureInputMethodService extends InputMethodService {
    @Override public View onCreateInputView() {
        TextView view = new TextView(this);
        view.setText("Autofill test keyboard");
        view.setPadding(24, 32, 24, 32);
        return view;
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }
}
