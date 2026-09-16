package androidx.test.rule;

import android.app.Activity;

import dev.halcamera.ctsvendor.VendoredCts;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * In-app stand-in for androidx.test.rule.ActivityTestRule. The real rule launches the activity through the
 * instrumentation before each test; here the app has already started the host activity that the test needs
 * (a subclass of the vendored Camera2SurfaceViewCtsActivity) and registered it with {@link VendoredCts}, so
 * {@link #getActivity()} simply returns it and the rule itself is a no-op.
 */
public class ActivityTestRule<T extends Activity> implements TestRule {
    private final Class<T> mActivityClass;

    public ActivityTestRule(Class<T> activityClass) {
        mActivityClass = activityClass;
    }

    public T getActivity() {
        Activity activity = VendoredCts.currentActivity();
        if (activity == null || !mActivityClass.isInstance(activity)) {
            throw new IllegalStateException("no " + mActivityClass.getSimpleName() + " is hosting the test");
        }
        return mActivityClass.cast(activity);
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return base;
    }
}
