package dev.l5z12.etmc.client;

import dev.l5z12.etmc.client.screen.ConnectUrlScreen;
import dev.l5z12.etmc.client.screen.EtmcConnectingScreen;
import dev.l5z12.etmc.client.screen.EtmcNoticeScreen;
import dev.l5z12.etmc.client.screen.EtmcScreen;
import dev.l5z12.etmc.client.screen.HostScreen;
import dev.l5z12.etmc.client.screen.JoinScreen;
import dev.l5z12.etmc.client.screen.LinkGeneratorScreen;
import dev.l5z12.etmc.client.screen.SettingsScreen;
import dev.l5z12.etmc.client.screen.StatusScreen;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;*/
//?}
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CI's launch test, built into the mod and inert unless the JVM is started with
 * {@code -Detmc.launchProbe=<result file>} (the buildscripts add it to {@code runClient} when Gradle
 * gets {@code -Petmc.launchProbe=true}).
 *
 * <p>Compiling every node proves nothing about what happens at runtime: a mixin whose target or
 * injection point doesn't exist under this version's names, a screen whose {@code init} or render hook
 * throws, a native library that doesn't load on this Java. Each of those only shows up in a running
 * game. So once the game is up this:
 * <ol>
 *   <li>checks the EasyTier library loaded (JNA on Java 17, FFM on 21+);</li>
 *   <li>force-loads every class an etmc mixin targets, so each mixin is applied now (with
 *       {@code defaultRequire = 1}, an injection that finds nothing is an error);</li>
 *   <li>opens the Multiplayer screen and checks the etmc button was added to it;</li>
 *   <li>opens every etmc screen in turn and waits until it has rendered a few frames;</li>
 * </ol>
 * then writes {@code OK} (or what failed) to the result file and exits the game. A crash on the way
 * leaves no result file, which the build treats as a failure too.
 */
public final class LaunchProbe {

    /** The result file; never set under a Fabric client gametest (Loom copies the client run's
     *  properties into that run, and the probe quitting the game would end the gametest). */
    private static final String OUT = System.getProperty("fabric.client.gametest") != null
            ? null : System.getProperty("etmc.launchProbe");
    /** The whole probe, game start included, must finish within this. */
    private static final long TOTAL_TIMEOUT_MS = 300_000;
    /** One step (the first waits out the resource-loading overlay). */
    private static final long STEP_TIMEOUT_MS = 120_000;
    private static final int FRAMES_PER_SCREEN = 3;

    private static volatile boolean multiplayerButtonAdded;
    private static volatile Screen watched;
    private static volatile int framesOfWatched;

    private static final long started = System.currentTimeMillis();
    private static boolean finished;
    private static int step = -1;
    private static long stepStarted;
    private static int stepTicks;
    private static int restarts;
    private static final int MAX_RESTARTS = 3;
    private static Screen multiplayer;
    private static final List<String> passed = new ArrayList<>();

    private LaunchProbe() {}

    public static boolean enabled() {
        return OUT != null && !OUT.isEmpty();
    }

    /** From {@code MultiplayerScreenMixin}: the etmc button really was added. */
    public static void multiplayerButtonAdded() {
        multiplayerButtonAdded = true;
    }

    /** From every etmc screen's render hook. */
    public static void rendered(Screen screen) {
        if (screen == watched) framesOfWatched++;
    }

    /** Called every client tick (from {@link EtmcManager#tick()}); client thread. */
    static void tick() {
        if (!enabled() || finished) return;
        try {
            advance();
        } catch (Throwable t) {
            fail(t);
            return;
        }
        if (finished) return;
        long now = System.currentTimeMillis();
        if (now - started > TOTAL_TIMEOUT_MS || (step >= 0 && now - stepStarted > STEP_TIMEOUT_MS)) {
            fail("timed out in step " + step + " (" + stepName() + "); screen: " + screenName(McScreens.current()));
        }
    }

    // ------------------------------------------------------------------ steps

    private static final String[] SCREENS = {
            "EtmcScreen", "HostScreen", "JoinScreen", "SettingsScreen", "StatusScreen",
            "LinkGeneratorScreen", "ConnectUrlScreen", "EtmcNoticeScreen", "EtmcConnectingScreen",
    };

    private static String stepName() {
        if (step == 0) return "native library";
        if (step == 1) return "mixins";
        if (step == 2) return "Multiplayer screen";
        int i = step - 3;
        return i >= 0 && i < SCREENS.length ? SCREENS[i] : "done";
    }

    private static Supplier<Screen> screen(int i) {
        Screen p = multiplayer;
        switch (SCREENS[i]) {
            case "EtmcScreen": return () -> new EtmcScreen(p);
            case "HostScreen": return () -> new HostScreen(p);
            case "JoinScreen": return () -> new JoinScreen(p);
            case "SettingsScreen": return () -> new SettingsScreen(p);
            case "StatusScreen": return () -> new StatusScreen(p);
            case "LinkGeneratorScreen": return () -> new LinkGeneratorScreen(p);
            case "ConnectUrlScreen": return () -> new ConnectUrlScreen(p);
            case "EtmcNoticeScreen": return () -> new EtmcNoticeScreen(p, "Launch probe",
                    "A long notice, so the wrapping is exercised too: etmc couldn't reach the host over "
                            + "the relay. Check that the relay address is right and that it is running, "
                            + "then try again.\n\nSecond paragraph.");
            default: return () -> new EtmcConnectingScreen(p, "probe", () -> {}, () -> {},
                    () -> "Waiting for a direct connection to the host");
        }
    }

    private static void next() {
        step++;
        stepStarted = System.currentTimeMillis();
        stepTicks = 0;
    }

    /**
     * Our screen is no longer the current one. The game itself does that once, when it finishes
     * loading and puts up its first real screen (title, accessibility onboarding...), and when that
     * happens differs by version and by machine. So start the screens over a few times; a screen
     * that keeps going away is closing itself.
     */
    private static void replaced(String what) {
        String now = screenName(McScreens.current());
        if (++restarts > MAX_RESTARTS) {
            fail(what + " keeps closing; screen now: " + now);
            return;
        }
        EtmcManager.LOGGER.info("[etmc] launch probe: {} was replaced by {}; starting the screens over", what, now);
        while (passed.size() > 2) passed.remove(passed.size() - 1); // keep "native library", "mixins"
        step = 1;
        next();
    }

    private static void advance() throws Exception {
        if (step < 0) {
            // Wait for the game to put up its first screen (title / onboarding), i.e. finish starting.
            if (McScreens.current() == null) return;
            EtmcManager.LOGGER.info("[etmc] launch probe: game is up, probing");
            next();
        }
        stepTicks++;
        switch (step) {
            case 0:
                if (!EtmcManager.get().isReady()) {
                    fail("EasyTier library didn't load: " + EtmcManager.get().nativeError());
                    return;
                }
                pass("native library");
                next();
                return;
            case 1:
                pass("mixins (" + String.join(", ", forceLoadMixinTargets()) + ")");
                next();
                return;
            case 2:
                if (stepTicks == 1) {
                    //? if yarn {
                    multiplayer = new MultiplayerScreen(McScreens.current());
                    //?} else {
                    /*multiplayer = new JoinMultiplayerScreen(McScreens.current());*/
                    //?}
                    McScreens.goTo(multiplayer);
                    return;
                }
                if (McScreens.current() != multiplayer) {
                    replaced("the Multiplayer screen");
                    return;
                }
                if (stepTicks < 10) return;
                if (!multiplayerButtonAdded) {
                    fail("the Multiplayer screen opened without the etmc button");
                    return;
                }
                pass("Multiplayer screen + etmc button");
                next();
                return;
            default:
                int i = step - 3;
                if (i >= SCREENS.length) {
                    finish();
                    return;
                }
                if (stepTicks == 1) {
                    Screen s = screen(i).get();
                    framesOfWatched = 0;
                    watched = s;
                    McScreens.goTo(s);
                    return;
                }
                if (McScreens.current() != watched) {
                    replaced(SCREENS[i]);
                    return;
                }
                if (framesOfWatched < FRAMES_PER_SCREEN) return;
                pass(SCREENS[i]);
                next();
        }
    }

    /**
     * Loads every class an etmc mixin targets (read from the mixin classes' {@code @Mixin} annotations
     * as bytes, since a mixin class itself can't be loaded), which applies the mixins to them now.
     */
    private static List<String> forceLoadMixinTargets() throws Exception {
        ClassLoader loader = LaunchProbe.class.getClassLoader();
        String json;
        try (InputStream in = loader.getResourceAsStream("etmc.mixins.json")) {
            if (in == null) throw new IllegalStateException("etmc.mixins.json is not on the classpath");
            json = new String(readAll(in), StandardCharsets.UTF_8);
        }
        Matcher pkg = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!pkg.find()) throw new IllegalStateException("etmc.mixins.json has no package");
        List<String> mixins = new ArrayList<>();
        Matcher arr = Pattern.compile("\"(?:client|mixins|server)\"\\s*:\\s*\\[([^\\]]*)]").matcher(json);
        while (arr.find()) {
            Matcher name = Pattern.compile("\"([^\"]+)\"").matcher(arr.group(1));
            while (name.find()) mixins.add(pkg.group(1) + "." + name.group(1));
        }
        if (mixins.isEmpty()) throw new IllegalStateException("etmc.mixins.json lists no mixins");

        List<String> loaded = new ArrayList<>();
        for (String mixin : mixins) {
            List<String> targets = mixinTargets(loader, mixin);
            if (targets.isEmpty()) throw new IllegalStateException(mixin + " names no target");
            for (String target : targets) {
                try {
                    Class.forName(target, false, loader);
                } catch (Throwable t) {
                    throw new IllegalStateException("applying " + mixin + " to " + target + " failed", t);
                }
            }
            loaded.add(mixin.substring(mixin.lastIndexOf('.') + 1));
        }
        return loaded;
    }

    private static List<String> mixinTargets(ClassLoader loader, String mixin) throws Exception {
        byte[] bytes;
        try (InputStream in = loader.getResourceAsStream(mixin.replace('.', '/') + ".class")) {
            if (in == null) throw new IllegalStateException("mixin class " + mixin + " is not on the classpath");
            bytes = readAll(in);
        }
        List<String> targets = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(desc)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String n, Object value) {
                                if (value instanceof Type) targets.add(((Type) value).getClassName());
                                else if (value instanceof String) targets.add(((String) value).replace('/', '.'));
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return targets;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ result

    private static void pass(String what) {
        passed.add(what);
        EtmcManager.LOGGER.info("[etmc] launch probe: ok - {}", what);
    }

    private static void finish() {
        report(true, "OK\n" + String.join("\n", passed) + "\n");
    }

    private static void fail(String why) {
        report(false, "FAIL: " + why + "\npassed before that:\n  " + String.join("\n  ", passed) + "\n");
    }

    private static void fail(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        fail("step " + step + " (" + stepName() + ") threw:\n" + sw);
    }

    /** Writes the result and stops the game without waiting on a normal shutdown (which can hang headless). */
    private static void report(boolean ok, String text) {
        finished = true;
        if (ok) EtmcManager.LOGGER.info("[etmc] launch probe: {}", text.trim());
        else EtmcManager.LOGGER.error("[etmc] launch probe: {}", text.trim());
        try {
            Path out = Paths.get(OUT);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.write(out, text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            EtmcManager.LOGGER.error("[etmc] launch probe: couldn't write {}", OUT, e);
        }
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(ok ? 0 : 1);
    }

    private static String screenName(Screen s) {
        return s == null ? "none" : s.getClass().getName();
    }
}
