package androidx.test;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

/**
 * In-app stand-in for androidx.test.InstrumentationRegistry, kept at the upstream class name so the vendored
 * CTS sources compile unchanged. There is no instrumentation in a normal app process, so the app registers a
 * plain {@link Instrumentation} whose target context is the application context and whose arguments carry
 * what cts-tradefed would have passed on the command line (see dev.halcamera.ctsvendor.VendoredCts).
 */
public final class InstrumentationRegistry {
    private static volatile Instrumentation sInstrumentation;
    private static volatile Bundle sArguments = new Bundle();

    private InstrumentationRegistry() {}

    public static void registerInstance(Instrumentation instrumentation, Bundle arguments) {
        sInstrumentation = instrumentation;
        sArguments = arguments == null ? new Bundle() : new Bundle(arguments);
    }

    public static Instrumentation getInstrumentation() {
        Instrumentation instrumentation = sInstrumentation;
        if (instrumentation == null) {
            throw new IllegalStateException("VendoredCts.install() has not been called");
        }
        return instrumentation;
    }

    public static Bundle getArguments() {
        return new Bundle(sArguments);
    }

    public static Context getTargetContext() {
        return getInstrumentation().getTargetContext();
    }

    public static Context getContext() {
        return getInstrumentation().getContext();
    }
}
