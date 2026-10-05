package takagi.ru.monica.autofill_ng.defaultmanager;
import android.os.Bundle;

// Current Android user only. No shell command, setting name or target package input.
interface IMonicaDefaultManager {
    void destroy() = 16777114;
    Bundle read() = 1;
    Bundle apply(in Bundle expected, boolean includeAutofill, boolean keepOthers) = 2;
    Bundle restore(in Bundle expected, in Bundle previous) = 3;
}
