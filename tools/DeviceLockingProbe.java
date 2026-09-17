import android.os.IBinder;
import android.app.TaskStackListener;
import java.lang.reflect.Method;
import java.util.List;

public final class DeviceLockingProbe {
    public static void main(String[] args) throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, "activity_task");
        Object service = Class.forName("android.app.IActivityTaskManager$Stub")
            .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class<?> query = Class.forName("dev.pranav.applock.shizuku.TaskQueryCompat");
        Object singleton = query.getField("INSTANCE").get(null);
        List<?> tasks = (List<?>) query.getMethod("query", Object.class).invoke(singleton, service);
        if (tasks.isEmpty()) throw new AssertionError("Expected foreground task");
        System.out.println("PASS: production TaskQueryCompat returned " + tasks.size() + " tasks on API " + android.os.Build.VERSION.SDK_INT);
        TaskStackListener listener = new TaskStackListener() {};
        Method register = null, unregister = null;
        for (Method method : service.getClass().getMethods()) {
            if (method.getName().equals("registerTaskStackListener")) register = method;
            if (method.getName().equals("unregisterTaskStackListener")) unregister = method;
        }
        register.setAccessible(true);
        unregister.setAccessible(true);
        register.invoke(service, listener);
        unregister.invoke(service, listener);
        System.out.println("PASS: task-stack listener registered and unregistered as shell");
        verifyLegacyCredentials(args[1]);
        verifyResources(args[0]);
        System.exit(0);
    }

    private static void verifyLegacyCredentials(String preferencesPath) throws Exception {
        // PreferencesRepository now reads BOOT_COUNT through the application resolver.
        // Keep only preferences synthetic; delegate framework services to a real shell context.
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        android.content.Context systemContext = (android.content.Context)
            activityThread.getMethod("getSystemContext").invoke(thread);
        java.io.File directory = new java.io.File(preferencesPath);
        if (!directory.mkdir()) throw new IllegalStateException("Use a fresh probe preferences directory");
        java.util.Map<String, android.content.SharedPreferences> preferences = new java.util.HashMap<>();
        android.content.Context context = new android.content.ContextWrapper(systemContext) {
            @Override public android.content.Context getApplicationContext() { return this; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return preferences.computeIfAbsent(name, key -> {
                    try {
                        java.lang.reflect.Constructor<?> constructor = Class.forName("android.app.SharedPreferencesImpl")
                            .getDeclaredConstructor(java.io.File.class, int.class);
                        constructor.setAccessible(true);
                        return (android.content.SharedPreferences) constructor.newInstance(new java.io.File(directory, key + ".xml"), mode);
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
        };
        try {
            Class<?> repositoryClass = Class.forName("dev.pranav.applock.data.repository.PreferencesRepository");
            Object repository = repositoryClass.getConstructor(android.content.Context.class).newInstance(context);
            android.content.SharedPreferences prefs = context.getSharedPreferences("app_lock_prefs", 0);
            prefs.edit().putString("pattern", "0,1,4,7").commit();
            Method validatePattern = repositoryClass.getMethod("validatePattern", String.class);
            check(Boolean.FALSE.equals(validatePattern.invoke(repository, "0,1,2,3")), "wrong legacy pattern rejected");
            check("0,1,4,7".equals(prefs.getString("pattern", null)), "failed attempt did not migrate pattern");
            check(Boolean.TRUE.equals(validatePattern.invoke(repository, "0,1,4,7")), "legacy pattern accepted");
            check(!"0,1,4,7".equals(prefs.getString("pattern", null)), "legacy pattern migrated");
            check(Boolean.TRUE.equals(validatePattern.invoke(repository, "0,1,4,7")), "migrated pattern accepted");
            prefs.edit().putString("password", "YWJj:ZGVm").commit();
            check(Boolean.TRUE.equals(repositoryClass.getMethod("validatePassword", String.class)
                .invoke(repository, "YWJj:ZGVm")), "legacy colon password accepted and migrated");
            System.out.println("PASS: production credential migration with Android SharedPreferences");
        } finally {
            for (android.content.SharedPreferences prefs : preferences.values()) prefs.edit().clear().commit();
            for (java.io.File file : directory.listFiles()) file.delete();
            directory.delete();
        }
    }

    private static void verifyResources(String apkPath) throws Exception {
        android.content.res.AssetManager assets = android.content.res.AssetManager.class.getConstructor().newInstance();
        assets.getClass().getMethod("addAssetPath", String.class).invoke(assets, apkPath);
        android.content.res.Resources system = android.content.res.Resources.getSystem();
        android.content.res.Resources resources = new android.content.res.Resources(assets, system.getDisplayMetrics(), system.getConfiguration());
        int title = resources.getIdentifier("shizuku_protecting", "string", "dev.pranav.applock");
        check(title != 0 && "Protecting your apps with Shizuku".equals(resources.getString(title)), "APK resources loaded");
        int themeId = resources.getIdentifier("Theme.AppLock", "style", "dev.pranav.applock");
        check(themeId != 0, "APK theme found");
        android.content.res.Resources.Theme theme = resources.newTheme();
        theme.applyStyle(themeId, true);
        android.content.res.TypedArray values = theme.obtainStyledAttributes(new int[] { android.R.attr.windowIsTranslucent });
        check(!values.getBoolean(0, false), "authentication theme is opaque");
        values.recycle();
        assets.close();
        System.out.println("PASS: APK resources and opaque theme parsed by Android");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
