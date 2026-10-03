package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.Architecture.archAsStringAndroid;
import static net.kdt.pojavlaunch.Architecture.getDeviceArchitecture;

import android.content.Context;
import android.system.Os;

import androidx.appcompat.app.AppCompatActivity;

import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.utils.JREUtils;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Boots Wildermyth's desktop build on the bundled Java 8 with Amethyst's LWJGL and Zink. */
public final class WildermythLauncher {
    public static final String EXTRA = "wildermyth";
    /** Comma-separated DLC app IDs Steam confirmed the account owns (set by WildermythActivity). */
    public static final String PREF_OWNED_DLC = "wm_owned_dlc";
    private static final String MAIN_CLASS = "com.worldwalkergames.legacy.LegacyDesktop";
    // Wildermyth is built against LWJGL 3.3.1; the closest bundled build is 3.3.3.
    private static final String LWJGL = "3.3.3";

    private WildermythLauncher() {}

    /** The game install: assets/, lib/ and wildermyth.jar. */
    public static File gameDir(Context ctx) {
        return new File(ctx.getExternalFilesDir(null), "wildermyth");
    }

    /**
     * The game's manifest Class-Path lists desktop LWJGL, whose jars carry glibc natives LWJGL would extract
     * and fail to load. Moving them aside makes the JVM skip those entries; Amethyst's LWJGL stands in.
     */
    private static void retireDesktopLwjgl(File game) {
        File lib = new File(game, "lib");
        File aside = new File(lib, "desktop-only");
        File[] jars = lib.listFiles((d, n) -> n.startsWith("lwjgl-") && n.endsWith(".jar"));
        if (jars == null || jars.length == 0) return;
        if (!aside.isDirectory() && !aside.mkdirs()) throw new IllegalStateException("cannot create " + aside);
        for (File j : jars) if (!j.renameTo(new File(aside, j.getName()))) throw new IllegalStateException("cannot move " + j);
    }

    public static void launch(AppCompatActivity activity) throws Throwable {
        File game = gameDir(activity);
        File jar = new File(game, "wildermyth.jar");
        if (!jar.isFile()) throw new IllegalStateException("Wildermyth is not installed at " + game);

        retireDesktopLwjgl(game);

        // The runtime unpacks asynchronously at app start; a first launch can get here before it is done.
        for (int i = 0; i < 120 && MultiRTUtils.readInternalRuntimeVersion("Internal") == null; i++) Thread.sleep(500);
        Runtime runtime = MultiRTUtils.forceReread("Internal"); // Java 8: the game needs JDK internals 9+ hides
        // Normally set from the Minecraft version; the EGL bridge parses it and crashes on null.
        ExtraCore.setValue(ExtraConstants.OPEN_GL_VERSION, "3");
        Tools.iLwjglVersion = 331;
        Tools.sLwjglVersion = LWJGL;
        Tools.lwjglNativesDir = String.format("%s/lwjgl-%s-natives/%s", Tools.DIR_DATA, LWJGL, archAsStringAndroid(getDeviceArchitecture()));

        // Android LWJGL first, then our FMOD loader (shadowing the game's), then the game jar, whose
        // manifest Class-Path brings in the rest of lib/.
        StringBuilder cp = new StringBuilder();
        File lwjglDir = new File(Tools.DIR_GAME_HOME, "lwjgl3/" + LWJGL);
        cp.append(new File(lwjglDir, "lwjgl.jar")).append(':');
        cp.append(new File(lwjglDir, "lwjgl-" + LWJGL + "-merged-modules.jar")).append(':');
        File[] modules = lwjglDir.listFiles((d, n) -> n.endsWith(".jar") && !n.equals("lwjgl.jar")
                && !n.contains("merged-modules") && !n.endsWith("lwjglx.jar"));
        if (modules != null) for (File m : modules) cp.append(m).append(':');
        cp.append(new File(Tools.DIR_DATA, "wildermyth/wm-fmodloader.jar")).append(':');
        cp.append(jar);

        List<String> args = new ArrayList<>();
        args.add("-Djava.awt.headless=true");
        // libGDX sees this runtime as Android and System.loadLibrary()s its natives: ship Android builds there.
        String gdxNatives = new File(Tools.DIR_DATA, "wildermyth").getAbsolutePath();
        args.add("-Djava.library.path=" + Tools.lwjglNativesDir + ":" + gdxNatives + ":" + Tools.NATIVE_LIB_DIR);
        args.add("-Dorg.lwjgl.librarypath=" + Tools.lwjglNativesDir);
        // Owned DLC: the game would ask the Steam client, which is not here; the agent answers for it.
        android.content.SharedPreferences prefs = activity.getSharedPreferences("wildermyth", Context.MODE_PRIVATE);
        args.add("-Dwm.ownedDlc=" + prefs.getString(PREF_OWNED_DLC, ""));
        // Dual-screen mode: the agent streams HUD panels (roster, hero sheet, log) to the second display.
        if (prefs.getBoolean(WildermythSecondScreen.PREF, false)) {
            int port = WildermythSecondScreen.start(activity, game);
            if (port > 0) args.add("-Dwm.ds.port=" + port);
        }
        args.add("-javaagent:" + new File(Tools.DIR_DATA, "wildermyth/wm-dlcagent.jar").getAbsolutePath());
        args.add("-cp");
        args.add(cp.toString());
        args.add(MAIN_CLASS);
        // SDL's HIDAPI driver looks up Android classes from the game's (desktop JVM) thread and fails;
        // the handheld's pad is a plain Android input device, so it does not need HIDAPI.
        Os.setenv("SDL_JOYSTICK_HIDAPI", "0", true);
        // SDL needs its Android JNI side before the game touches it. Amethyst normally does this from a
        // hook on SDL_InitSubSystem, but Jamepad goes through sdl2-compat, which calls SDL3 directly.
        // FMOD on Android must be loaded and initialised by the app's own VM before the game uses it.
        System.loadLibrary("fmod");
        System.loadLibrary("fmodstudio");
        org.fmod.FMOD.init(activity);
        CallbackBridge.notifyLauncher(CallbackBridge.NOTIF_TYPE_SDL, CallbackBridge.ACTION_INIT_LAUNCHER_INTEGRATION);
        Tools.SDL.initializeControllerSubsystems();
        JREUtils.launchJavaVM(activity, runtime, game, args, "");
    }
}
