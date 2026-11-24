package io.github.p5rrecomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.system.Os;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.text.DateFormat;
import java.util.Date;
import java.util.Map;

/**
 * The home screen in front of the game (a NativeActivity, libp5r.so): checks for the player's data in Internal
 * storage/Persona 5 Royal (asking for all-files access), shows the options and writes them to that folder's
 * config.txt, whose NX_* lines the runtime turns into its switches:
 *   game text   the language of the game's texts and subtitles (language=auto, default: the device's, English when
 *               the game lacks it; or en-US, es, fr, de, it, ja, ko, zh-Hans, zh-Hant). The voices are the game's own
 *               option.
 *   resolution  720p (NX_HANDHELD=1, default) or 1080p (NX_HANDHELD=0)
 *   interface   at 720p (NX_HUD_1080=0, default: faster) or, in 720p, drawn at 1080p (NX_HUD_1080=1: sharper text)
 *   graphics    low (default), medium or high: textures one mip smaller (NX_LOD_BIAS=1), half a mip, or the game's
 *               own. (A lower render resolution needs the engine's target sizes: games/p5r/PROGRESO.md.)
 *   frame rate  30 (NX_FPS_CAP=30, default) or 60 (NX_FPS_CAP=60: games/p5r/game.cpp patches the engine's interval)
 *   pipeline compilation  smart asynchronous (Android default; NX_ASYNC_PIPELINES=2) or synchronous (0)
 *   FPS counter off (default) or on (NX_SHOW_FPS)
 *   shadows     off (NX_SHADOWS=0, default: the shadow maps are not drawn, ~9% of the GPU on the 3D title) or on
 *   on-screen controls on (default) or off (NX_TOUCH_SHOW), their size (NX_TOUCH_SIZE) and opacity (NX_TOUCH_OPACITY),
 *               and where they are (NX_TOUCH_LAYOUT, written by ControlsEditor)
 * and imports or exports the saves (user/save, SaveFiles.java): a ZIP or a folder in, a ZIP in Download out.
 * The game: the player picks their own NSP or XCI (Persona 5 Royal 1.0.0) and prod.keys here; ImportActivity checks
 * them and translates the game's shaders once, and every start reads the game from that file as it is (the runtime's
 * game_image.cpp, through NX_GAME_FD / NX_GAME_NAME / NX_KEYS). Nothing of the game is copied but its shaders. A data
 * folder made on a PC (exefs/ or analysis/, extract/romfs, spirv/) still works when no file was picked.
 * The icon: a home screen shortcut with a picture of the player's own (Android lets an app pin shortcuts, not change
 * its own icon).
 * Other lines of config.txt are kept as they were. The launcher itself speaks the device's language (Lang.java:
 * Spanish, English, French, German, Italian, Portuguese, Japanese, Korean, Chinese; English otherwise).
 */
public class Launcher extends Activity {
    static final String DATA = "Persona 5 Royal";

    static final class Option {
        final String key, es, en;
        final String[] values, labelsEs, labelsEn;
        int pick;

        Option(String key, String es, String en, String[] values, String[] labelsEs, String[] labelsEn, int pick) {
            this.key = key;
            this.es = es;
            this.en = en;
            this.values = values;
            this.labelsEs = labelsEs;
            this.labelsEn = labelsEn;
            this.pick = pick;
        }
    }

    final List<Option> options = new ArrayList<>();
    final Map<String, String> config = new LinkedHashMap<>();
    TextView status;
    TextView historyStatus;
    Button play;
    Button profileButton;
    TextView deviceRecommendationName;
    Button deviceRecommendationButton;
    ScrollView launcherScroll;
    final View[] navTargets = new View[6];
    LinearLayout launcherRoot;
    P5Views.Backdrop backdrop;
    SoundPool uiSoundPool;
    int tabSoundId;
    boolean tabSoundReady;
    final Button[] sideNavButtons = new Button[7];
    TextView pageTitle;
    int activeSection;
    LinearLayout subTabBar;
    final Map<View, Integer> subSections = new LinkedHashMap<>();
    int activeSubSection;
    final List<View> focusTargets = new ArrayList<>();
    int focusIndex;
    boolean stickUp, stickDown, stickLeft, stickRight;
    boolean triggerLeft, triggerRight;
    PowerManager thermalPowerManager;
    PowerManager.OnThermalStatusChangedListener thermalListener;
    int lastThermalStatus = -1;
    boolean manualEdit;
    String manualEditKey;
    static final int PICK_ZIP = 1, PICK_FOLDER = 2, PICK_GAME = 3, PICK_KEYS = 4, PICK_ICON = 5, PREPARE = 6, PICK_CPK = 7, PICK_DRIVER = 8, PICK_MOD = 9;

    String t(String spanish, String english) { return Lang.t(spanish, english); }

    String label(Option o) { return t(o.labelsEs[o.pick], o.labelsEn[o.pick]); }

    Option findOption(String key) {
        for (Option o : options) {
            if (key.equals(o.key)) return o;
        }
        return null;
    }

    static File dataDir() { return new File(Environment.getExternalStorageDirectory(), DATA); }

    static final class Api30Helper {
        static boolean isExternalStorageManager() {
            return Environment.isExternalStorageManager();
        }
    }

    boolean hasAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return Api30Helper.isExternalStorageManager();
    }

    // ---- the game file, read in place at every start ----

    SharedPreferences prefs() { return getSharedPreferences("game", MODE_PRIVATE); }

    String gameUri() { return prefs().getString("uri", null); }

    String gameName() { return prefs().getString("name", ""); }

    static File keysFile(Context c, String name) { return new File(new File(c.getFilesDir(), "keys"), name); }

    boolean hasKeys() { return keysFile(this, "prod.keys").length() > 0; }

    boolean gameReadable() {
        try (ParcelFileDescriptor f = getContentResolver().openFileDescriptor(Uri.parse(gameUri()), "r")) {
            return f != null;
        } catch (Exception e) {
            return false;
        }
    }

    // The shaders were translated from this game file (ImportActivity writes spirv/import.txt last).
    boolean prepared() {
        File note = new File(dataDir(), "spirv/import.txt");
        if (!note.canRead()) return false;
        try (BufferedReader r = new BufferedReader(new FileReader(note))) {
            return gameName().equals(r.readLine());
        } catch (Exception e) {
            return false;
        }
    }

    String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last.substring(last.lastIndexOf('/') + 1);
    }

    void pick(int request, String type, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, request);
    }

    void onGamePicked(Uri uri) {
        String name = displayName(uri);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".nsp") && !lower.endsWith(".xci")) {
            tell(t("Archivo no válido", "Not a game file"),
                    t("Elige el archivo .nsp o .xci del juego base (no el de la actualización).",
                            "Choose the base game's .nsp or .xci file (not the update's)."));
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {  // a provider without persistable grants: works while the app runs
        }
        prefs().edit().putString("uri", uri.toString()).putString("name", name).apply();
    }

    // prod.keys (and title.keys, when the dump needs it): copied into the app's own storage, never shared or logged.
    void onKeysPicked(Intent data) {
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null)
            for (int k = 0; k < clip.getItemCount(); k++) uris.add(clip.getItemAt(k).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        boolean prod = false;
        for (Uri uri : uris) {
            String name = displayName(uri).toLowerCase(Locale.ROOT);
            String target = name.contains("title") ? "title.keys" : "prod.keys";
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[1 << 20];
                int n = 0, r;
                while (in != null && n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                String text = new String(buf, 0, n, "UTF-8");
                if (target.equals("prod.keys") && !text.contains("header_key") && !text.contains("master_key_00")) continue;
                File f = keysFile(this, target);
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    out.write(buf, 0, n);
                }
                prod |= target.equals("prod.keys");
            } catch (Exception ignored) {
            }
        }
        if (!prod && !hasKeys())
            tell(t("Claves no válidas", "Not a keys file"),
                    t("Ese archivo no tiene las claves (header_key). Elige el prod.keys de tu consola.",
                            "That file has no keys (header_key). Choose your console's prod.keys."));
    }

    void prepare() {
        Intent i = new Intent(this, ImportActivity.class);
        i.putExtra(ImportActivity.EXTRA_URI, gameUri());
        i.putExtra(ImportActivity.EXTRA_NAME, gameName());
        startActivityForResult(i, PREPARE);
    }

    // The runtime reads the game from this descriptor (same process: the NativeActivity runs in the launcher's).
    boolean openGameForRuntime() {
        try {
            ParcelFileDescriptor f = getContentResolver().openFileDescriptor(Uri.parse(gameUri()), "r");
            if (f == null) return false;
            Os.setenv("NX_GAME_FD", String.valueOf(f.detachFd()), true);
            Os.setenv("NX_GAME_NAME", gameName(), true);
            Os.setenv("NX_KEYS", keysFile(this, "prod.keys").getPath(), true);
            File tk = keysFile(this, "title.keys");
            if (tk.exists()) Os.setenv("NX_TITLE_KEYS", tk.getPath(), true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // A home screen shortcut with the player's picture (cropped to a square; an adaptive icon, so the home screen gives
    // it its own shape).
    void onIconPicked(Uri uri) {
        try {
            Bitmap src;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                src = BitmapFactory.decodeStream(in);
            }
            if (src == null) throw new IllegalArgumentException("not a picture");
            int side = Math.min(src.getWidth(), src.getHeight());
            Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
            Bitmap icon = Bitmap.createScaledBitmap(square, 432, 432, true);
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                tell(t("Icono", "Icon"), t("Tu pantalla de inicio no admite accesos directos.",
                        "Your home screen does not support shortcuts."));
                return;
            }
            Intent launch = new Intent(this, Launcher.class).setAction(Intent.ACTION_MAIN);
            ShortcutInfo info = new ShortcutInfo.Builder(this, "picture_" + System.currentTimeMillis())
                    .setShortLabel("P5R").setLongLabel("Persona 5 Royal").setIcon(Icon.createWithAdaptiveBitmap(icon))
                    .setIntent(launch).build();
            sm.requestPinShortcut(info, null);
            tell(t("Icono", "Icon"), t("Acepta el aviso para poner el acceso directo con tu imagen en la pantalla de inicio. "
                            + "El icono del cajón de apps no cambia: Android no deja que una app cambie el suyo.",
                    "Accept the prompt to put the shortcut with your picture on the home screen. The icon in the app "
                            + "drawer stays: Android does not let an app change its own."));
        } catch (Exception e) {
            tell(t("Icono", "Icon"), t("No se pudo usar esa imagen.", "That picture could not be used."));
        }
    }

    // The recompiled build reads analysis/main.bin, the native one (the game's own code) exefs/main.
    boolean hasData() {
        return new File(dataDir(), "exefs/main").canRead() || new File(dataDir(), "analysis/main.bin").canRead();
    }

    // The build ID the port is made for (Persona 5 Royal 1.0.0, the base game): the start of exefs/main's module ID
    // (NSO header +0x40). The native build refuses another (runtime main.cpp); say so here instead of closing.
    static final String[] KNOWN_BUILDS = {
        "708BCACA873EA32B",  // US
        "B78A03E489E5BD92",  // EU
        "6E1F4CAE92A28C91",  // JP
        "2F2253ACD6BE5D87"   // Asia
    };

    // Null when exefs/main is missing or a recognized base game version, else its build ID.
    String wrongBuild() {
        File main = new File(dataDir(), "exefs/main");
        if (!main.canRead()) return null;
        byte[] id = new byte[8];
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(main, "r")) {
            f.seek(0x40);
            f.readFully(id);
        } catch (java.io.IOException e) {
            return "?";
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : id) hex.append(String.format("%02X", b & 0xFF));
        String have = hex.toString();
        for (String k : KNOWN_BUILDS) {
            if (have.equals(k)) return null;
        }
        return have;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AudioAttributes audio = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        uiSoundPool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(audio).build();
        uiSoundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override public void onLoadComplete(SoundPool pool, int sampleId, int status) {
                if (sampleId == tabSoundId && status == 0) tabSoundReady = true;
            }
        });
        int tabSoundRes = getResources().getIdentifier("tab_select", "raw", getPackageName());
        if (tabSoundRes != 0) tabSoundId = uiSoundPool.load(this, tabSoundRes, 1);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // The game's texts and subtitles (runtime language.h): its own language names, as a console lists them.
        options.add(new Option("language", "Idioma de textos y subtítulos", "Text and subtitle language",
                new String[] {"auto", "en-US", "es", "fr", "de", "it", "ja", "ko", "zh-Hans", "zh-Hant"},
                new String[] {"Automático (idioma del dispositivo)", "English", "Español", "Français", "Deutsch",
                        "Italiano", "日本語", "한국어", "简体中文", "繁體中文"},
                new String[] {"Automatic (device language)", "English", "Español", "Français", "Deutsch", "Italiano",
                        "日本語", "한국어", "简体中文", "繁體中文"}, 0));
        options.add(new Option("RESOLUTION", "Resolución", "Resolution",
                new String[] {"360p", "480p", "720p", "1080p"},
                new String[] {"360p", "480p", "720p", "1080p"},
                new String[] {"360p", "480p", "720p", "1080p"}, 2));
        options.add(new Option("NX_STRETCH", "Pantalla completa (estirar)", "Fullscreen (stretch)",
                new String[] {"0", "1"},
                new String[] {"Desactivada", "Activada"},
                new String[] {"Off", "On"}, 0));
        options.add(new Option("NX_FSR", "Superresolución (FSR 1.0)", "Super resolution (FSR 1.0)",
                new String[] {"0", "1"},
                new String[] {"Desactivada", "Activada"},
                new String[] {"Off", "On"}, 0));
        options.add(new Option("NX_HUD_1080", "Interfaz", "Interface", new String[] {"0", "1"},
                new String[] {"720p (más rápida)", "1080p (textos nítidos)"},
                new String[] {"720p (faster)", "1080p (sharp text)"}, 0));
        options.add(new Option("GRAPHICS", "Gráficos", "Graphics", new String[] {"low", "medium", "high"},
                new String[] {"Bajos", "Medios", "Altos"}, new String[] {"Low", "Medium", "High"}, 0));
        options.add(new Option("NX_FPS_CAP", "Fotogramas por segundo", "Frame rate", new String[] {"30", "60"},
                new String[] {"30 fps", "60 fps (experimental)"}, new String[] {"30 fps", "60 fps (experimental)"}, 0));
        options.add(new Option("THERMAL_PACING", "Control térmico", "Thermal pacing", new String[] {"0", "1"},
                new String[] {"Desactivado", "Activado (solo con calor)"}, new String[] {"Off", "On (only when hot)"}, 1));
        options.add(new Option("NX_ASYNC_PIPELINES", "Compilación asíncrona de pipelines", "Async pipeline compilation",
                new String[] {"2", "0"},
                new String[] {"Inteligente (puede faltar algo un instante)", "Desactivada (puede dar tirones con efectos nuevos)"},
                new String[] {"Smart async (brief pop-in possible)", "Off (may hitch on new effects)"}, 0));
        options.add(new Option("NX_TEX_AUDIT", "Reparación de texturas de escena", "Scene texture refresh",
                new String[] {"0", "2"},
                new String[] {"Desactivada", "Activada (puede usar más CPU)"},
                new String[] {"Off", "On (may use more CPU)"}, 0));
        options.add(new Option("NX_SHADOWS", "Sombras", "Shadows", new String[] {"0", "1"},
                new String[] {"Desactivadas", "Activadas"}, new String[] {"Off", "On"}, 0));
        options.add(new Option("NX_MALI_LOW_SHADOWS", "Calidad de sombras", "Shadow quality",
                new String[] {"1", "0"},
                new String[] {"Baja (0.5x - mejor rendimiento)", "Original (1.0x)"},
                new String[] {"Low (0.5x - best performance)", "Original (1.0x)"}, 0));
        options.add(new Option("NX_SHOW_FPS", "Contador de FPS", "FPS counter", new String[] {"0", "1"},
                new String[] {"Desactivado", "Activado"}, new String[] {"Off", "On"}, 0));
        options.add(new Option("NX_TOUCH_SHOW", "Controles táctiles", "On-screen controls", new String[] {"1", "0"},
                new String[] {"Activados", "Desactivados"}, new String[] {"On", "Off"}, 0));
        options.add(new Option("NX_TOUCH_HIDE", "Ocultar controles", "Hide controls", new String[] {"0", "1"},
                new String[] {"No (visibles)", "Sí (invisibles)"}, new String[] {"No (visible)", "Yes (invisible)"}, 0));
        options.add(new Option("NX_TOUCH_SIZE", "Tamaño de los botones", "Button size",
                new String[] {"80", "100", "125"}, new String[] {"Pequeño", "Normal", "Grande"},
                new String[] {"Small", "Normal", "Large"}, 1));
        options.add(new Option("NX_TOUCH_OPACITY", "Opacidad de los botones", "Button opacity",
                new String[] {"50", "85", "100"}, new String[] {"Baja", "Normal", "Alta"},
                new String[] {"Low", "Normal", "High"}, 1));
        options.add(new Option("NX_NO_CROWD", "Gente / Multitudes de NPCs", "Crowd NPCs",
                new String[] {"0", "1"},
                new String[] {"Activada (normal)", "Desactivada (más FPS)"},
                new String[] {"Enabled (normal)", "Disabled (more FPS)"}, 0));
        options.add(new Option("NX_DISABLE_BLOOM", "Post-procesado (bloom/brillo)", "Post-processing (bloom)",
                new String[] {"0", "1"},
                new String[] {"Activado (normal)", "Desactivado (más FPS)"},
                new String[] {"Enabled (normal)", "Disabled (more FPS)"}, 0));
        options.add(new Option("NX_GAMEPAD_LAYOUT", "Botones del mando físico", "Physical gamepad buttons",
                new String[] {"0", "1"},
                new String[] {"Nintendo (A dcha, B abajo)", "Xbox / PC (A abajo, B dcha)"},
                new String[] {"Nintendo (A right, B down)", "Xbox / PC (A down, B right)"}, 0));
        options.add(new Option("NX_STICK_SENSITIVITY", "Sensibilidad palanca táctil", "Touch stick sensitivity",
                new String[] {"50", "75", "100", "125", "150", "200"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%"}, 2));
        options.add(new Option("NX_CAMERA_SENSITIVITY", "Sensibilidad cámara táctil", "Touch camera sensitivity",
                new String[] {"50", "75", "100", "125", "150", "200", "250"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%", "250%"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%", "250%"}, 2));
        options.add(new Option("NX_TOUCH_FIXED_STICKS", "Posición de palancas", "Stick position",
                new String[] {"0", "1"}, new String[] {"Flotante", "Fija"},
                new String[] {"Floating", "Fixed"}, 0));
        if (!PerformanceProfiles.isProfile(prefs().getString("graphics_profile", PerformanceProfiles.MANUAL)))
            prefs().edit().putString("graphics_profile", PerformanceProfiles.MANUAL).apply();
        setupThermalPacing();
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        finishTrackedSession();
        refresh();
        refreshHistory();
        maybeShowDeviceRecommendation();
    }

    @Override protected void onDestroy() {
        if (uiSoundPool != null) { uiSoundPool.release(); uiSoundPool = null; }
        if (thermalPowerManager != null && thermalListener != null) {
            thermalPowerManager.removeThermalStatusListener(thermalListener);
            thermalListener = null;
        }
        super.onDestroy();
    }

    void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        launcherRoot = root;
        root.setBackgroundColor(Color.TRANSPARENT);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("DISPLAY");
        pageTitle = title;
        title.setTextColor(Color.rgb(230, 0, 18));
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTag("shared");
        root.addView(title);
        subTabBar = new LinearLayout(this);
        subTabBar.setOrientation(LinearLayout.HORIZONTAL);
        subTabBar.setGravity(Gravity.CENTER_VERTICAL);
        subTabBar.setTag("shared");
        root.addView(subTabBar);

        addProfileRow(root);
        root.getChildAt(root.getChildCount() - 1).setTag("shared");
        addDeviceRecommendationRow(root);
        root.getChildAt(root.getChildCount() - 1).setTag("shared");

        status = new TextView(this);
        status.setTextColor(Color.rgb(220, 220, 220));
        status.setTextSize(14);
        status.setPadding(0, dp(6), 0, dp(14));
        root.addView(status);
        status.setTag("shared");
        historyStatus = new TextView(this);
        historyStatus.setTextColor(0xFFB8A6AD);
        historyStatus.setTextSize(13);
        historyStatus.setPadding(0, 0, 0, dp(10));
        historyStatus.setTag("shared");
        root.addView(historyStatus);

        addRow(root, t("Archivo del juego (.nsp / .xci)", "Game file (.nsp / .xci)"), t("Elegir", "Choose"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pick(PICK_GAME, "*/*", false);
                    }
                });
        root.getChildAt(root.getChildCount() - 1).setTag(3);
        navTargets[3] = root.getChildAt(root.getChildCount() - 1);
        addRow(root, t("Claves (prod.keys)", "Keys (prod.keys)"), t("Elegir", "Choose"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(PICK_KEYS, "*/*", true);
            }
        });
        root.getChildAt(root.getChildCount() - 1).setTag(3);
        addRow(root, t("Icono", "Icon"), t("Elegir imagen", "Choose a picture"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(PICK_ICON, "image/*", false);
            }
        });
        root.getChildAt(root.getChildCount() - 1).setTag(4);
        addHapticsRow(root);
        addAppearanceRows(root);

        for (final Option o : options) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(7), dp(8), dp(7));
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(0xD91E1118);
            panel.setStroke(dp(1), 0x66F21B35);
            row.setBackground(panel);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(5);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(t(o.es, o.en));
            name.setTextColor(Color.WHITE);
            name.setTextSize(20);
            name.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            info.addView(name);
            TextView description = new TextView(this);
            description.setText(optionDescription(o.key));
            description.setTextColor(0xFFe4d6d8);
            description.setTextSize(14);
            info.addView(description);
            row.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            final Button value = new Button(this);
            value.setAllCaps(false);
            value.setMinWidth(dp(180));
            row.addView(value);
            value.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if ("THERMAL_PACING".equals(o.key)) {
                        o.pick = (o.pick + 1) % o.values.length;
                        prefs().edit().putBoolean("thermal_pacing", o.pick == 1).apply();
                        syncThermalPacing();
                        value.setText(label(o));
                        return;
                    }
                    if (profiled() && PerformanceProfiles.controls(o.key)) return;
                    o.pick = (o.pick + 1) % o.values.length;
                    value.setText(label(o));
                    if (prefs().getBoolean(DeviceRecommendations.PREF_ENABLED, false)
                            && ("RESOLUTION".equals(o.key) || "NX_FPS_CAP".equals(o.key)))
                        disableRecommendationForManualEdit(o.key, o.values[o.pick]);
                    manualEdit = PerformanceProfiles.MANUAL.equals(profile()) && PerformanceProfiles.controls(o.key);
                    manualEditKey = manualEdit ? o.key : null;
                    save();
                    manualEdit = false;
                    manualEditKey = null;
                    updateButtons(getWindow().getDecorView());
                }
            });
            value.setTag(o);
            row.setTag(optionSection(o.key));
            subSections.put(row, optionSubSection(o.key));
            root.addView(row, rowParams);
            if ("RESOLUTION".equals(o.key)) navTargets[0] = row;
            if ("GRAPHICS".equals(o.key)) navTargets[1] = row;
        }

        LinearLayout edit = new LinearLayout(this);
        edit.setOrientation(LinearLayout.HORIZONTAL);
        edit.setGravity(Gravity.CENTER_VERTICAL);
        edit.setPadding(0, dp(4), 0, dp(4));
        TextView editName = new TextView(this);
        editName.setText(t("Posición de los controles", "Controls position"));
        editName.setTextColor(Color.WHITE);
        editName.setTextSize(17);
        edit.addView(editName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button editButton = new Button(this);
        editButton.setAllCaps(false);
        editButton.setMinWidth(dp(180));
        editButton.setText(t("Mover", "Move"));
        edit.addView(editButton);
        editButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasAccess()) {
                    askAccess();
                    return;
                }
                save();
                startActivity(new Intent(Launcher.this, ControlsEditor.class));
            }
        });
        root.addView(edit);
        edit.setTag(2);
        subSections.put(edit, 0);
        navTargets[2] = edit;

        // Saves: in from a ZIP or a folder (a Switch backup or another player's), out to a ZIP in Download.
        LinearLayout saves = new LinearLayout(this);
        saves.setOrientation(LinearLayout.HORIZONTAL);
        saves.setGravity(Gravity.CENTER_VERTICAL);
        saves.setPadding(0, dp(4), 0, dp(4));
        TextView savesName = new TextView(this);
        savesName.setText(t("Partidas guardadas", "Saves"));
        savesName.setTextColor(Color.WHITE);
        savesName.setTextSize(17);
        saves.addView(savesName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button importButton = new Button(this);
        importButton.setAllCaps(false);
        importButton.setMinWidth(dp(88));
        importButton.setText(t("Importar", "Import"));
        saves.addView(importButton);
        Button exportButton = new Button(this);
        exportButton.setAllCaps(false);
        exportButton.setMinWidth(dp(88));
        exportButton.setText(t("Exportar", "Export"));
        saves.addView(exportButton);
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasAccess()) {
                    askAccess();
                    return;
                }
                askImport();
            }
        });
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasAccess()) {
                    askAccess();
                    return;
                }
                exportSaves();
            }
        });
        root.addView(saves);
        saves.setTag(3);

        LinearLayout modsRow = new LinearLayout(this);
        modsRow.setOrientation(LinearLayout.HORIZONTAL);
        modsRow.setGravity(Gravity.CENTER_VERTICAL);
        modsRow.setPadding(0, dp(4), 0, dp(4));
        TextView modsName = new TextView(this);
        modsName.setText(t("Mods", "Mods"));
        modsName.setTextColor(Color.WHITE); modsName.setTextSize(17);
        modsRow.addView(modsName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button installModButton = new Button(this); installModButton.setAllCaps(false);
        installModButton.setText(t("Instalar", "Install"));
        modsRow.addView(installModButton);
        Button modsButton = new Button(this); modsButton.setAllCaps(false);
        modsButton.setText(t("Administrar", "Manage"));
        modsRow.addView(modsButton);
        installModButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pick(PICK_MOD, "application/zip", false); }});
        modsButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showModManager(); }});
        root.addView(modsRow); modsRow.setTag(3); subSections.put(modsRow, 0);

        // Translation CPK import
        LinearLayout transRow = new LinearLayout(this);
        transRow.setOrientation(LinearLayout.HORIZONTAL);
        transRow.setGravity(Gravity.CENTER_VERTICAL);
        transRow.setPadding(0, dp(4), 0, dp(4));
        TextView transName = new TextView(this);
        transName.setText(t("Traducción / Paquete (.cpk)", "Translation / CPK mod"));
        transName.setTextColor(Color.WHITE);
        transName.setTextSize(17);
        transRow.addView(transName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button transButton = new Button(this);
        transButton.setAllCaps(false);
        transButton.setMinWidth(dp(180));
        transButton.setText(t("Importar CPK", "Import CPK"));
        transRow.addView(transButton);
        transButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasAccess()) {
                    askAccess();
                    return;
                }
                pick(PICK_CPK, "*/*", false);
            }
        });
        root.addView(transRow);
        transRow.setTag(3);

        // Custom GPU driver (e.g. Mesa Turnip for Adreno): imported into the app's private files, opened by the runtime
        LinearLayout drvRow = new LinearLayout(this);
        drvRow.setOrientation(LinearLayout.HORIZONTAL);
        drvRow.setGravity(Gravity.CENTER_VERTICAL);
        drvRow.setPadding(0, dp(4), 0, dp(4));
        driverName = new TextView(this);
        driverName.setTextColor(Color.WHITE);
        driverName.setTextSize(17);
        drvRow.addView(driverName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button drvImport = new Button(this);
        drvImport.setAllCaps(false);
        drvImport.setMinWidth(dp(88));
        drvImport.setText(t("Importar", "Import"));
        drvRow.addView(drvImport);
        Button drvRemove = new Button(this);
        drvRemove.setAllCaps(false);
        drvRemove.setMinWidth(dp(88));
        drvRemove.setText(t("Quitar", "Remove"));
        drvRow.addView(drvRemove);
        drvImport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(PICK_DRIVER, "*/*", false);
            }
        });
        drvRemove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                removeDriver();
            }
        });
        root.addView(drvRow);
        drvRow.setTag(3);
        showDriver();

        // Debug logs export
        LinearLayout logsRow = new LinearLayout(this);
        logsRow.setOrientation(LinearLayout.HORIZONTAL);
        logsRow.setGravity(Gravity.CENTER_VERTICAL);
        logsRow.setPadding(0, dp(4), 0, dp(4));
        TextView logsName = new TextView(this);
        logsName.setText(t("Registros de depuración", "Debug logs"));
        logsName.setTextColor(Color.WHITE);
        logsName.setTextSize(17);
        logsRow.addView(logsName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button logsButton = new Button(this);
        logsButton.setAllCaps(false);
        logsButton.setMinWidth(dp(180));
        logsButton.setText(t("Exportar registros", "Export logs"));
        logsRow.addView(logsButton);
        logsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLogs();
            }
        });
        root.addView(logsRow);
        logsRow.setTag(3);
        subSections.put(logsRow, 2);
        navTargets[4] = logsRow;

        play = new P5Views.SlashButton(this);
        play.setTextSize(20);
        play.setAllCaps(false);
        play.setBackgroundColor(Color.rgb(230, 0, 18));
        play.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        lp.topMargin = dp(18);
        root.addView(play, lp);
        play.setTag("shared");
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasAccess()) {
                    askAccess();
                    return;
                }
                if (gameUri() != null) {  // the game file the player picked
                    if (!gameReadable()) pick(PICK_GAME, "*/*", false);
                    else if (!hasKeys()) pick(PICK_KEYS, "*/*", true);
                    else if (!prepared()) prepare();
                    else if (openGameForRuntime()) {
                        save();
                        launchTrackedGame();
                    } else {
                        tell(t("No se pudo abrir el juego", "The game could not be opened"), gameName());
                    }
                    return;
                }
                if (!hasData()) {
                    pick(PICK_GAME, "*/*", false);
                    return;
                }
                if (wrongBuild() != null) {
                    refresh();
                    return;
                }
                save();
                launchTrackedGame();
            }
        });

        TextView hintBar = new TextView(this);
        hintBar.setText("◀ ▶  ADJUST      LB / RB  TABS      B  HOME      START  PLAY");
        hintBar.setTextColor(0xFFffe500);
        hintBar.setTextSize(13);
        hintBar.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        hintBar.setGravity(Gravity.CENTER_VERTICAL);
        hintBar.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable hintBg = new GradientDrawable();
        hintBg.setColor(0xDD080305);
        hintBg.setStroke(dp(1), 0x66ff2a3a);
        hintBar.setBackground(hintBg);
        hintBar.setTag("shared");
        root.addView(hintBar, new LinearLayout.LayoutParams(-1, dp(44)));

        ScrollView scroll = new ScrollView(this);
        launcherScroll = scroll;
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.addView(root);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.addView(buildSidebar(), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.31f));
        shell.addView(scroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.69f));
        FrameLayout screen = new FrameLayout(this);
        backdrop = new P5Views.Backdrop(this, prefs().getInt("launcher_wallpaper", 0),
                prefs().getBoolean("launcher_motion", false));
        screen.addView(backdrop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        screen.addView(shell, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(screen);
        showLauncherSection(0);
        rebuildFocus(0);
        animateLauncherEntrance(root);
    }

    LinearLayout buildSidebar() {
        LinearLayout side = new P5Views.Sidebar(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(18), dp(20), dp(12), dp(14));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setRotation(-2f);
        for (int i = 0; i < "NIGHTFALL".length(); i++) {
            TextView tile = new TextView(this);
            tile.setText(String.valueOf("NIGHTFALL".charAt(i)));
            tile.setGravity(Gravity.CENTER);
            tile.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            tile.setTextSize(23);
            tile.setTextColor(i % 2 == 0 ? Color.WHITE : Color.BLACK);
            GradientDrawable tileBg = new GradientDrawable();
            tileBg.setColor(i % 2 == 0 ? Color.BLACK : Color.WHITE);
            tileBg.setStroke(dp(1), i % 2 == 0 ? Color.WHITE : Color.TRANSPARENT);
            tile.setBackground(tileBg);
            tile.setShadowLayer(dp(3), dp(2), dp(2), Color.rgb(232,16,31));
            LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(dp(27), dp(36));
            tileLp.rightMargin = dp(2); brand.addView(tile, tileLp);
        }
        side.addView(brand);
        TextView tag = new TextView(this); tag.setText("LAUNCHER");
        tag.setTextColor(Color.WHITE); tag.setTextSize(12); tag.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        tag.setBackgroundColor(Color.rgb(232,16,31)); tag.setPadding(dp(8),dp(2),dp(8),dp(2));
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(-2,-2); tagLp.bottomMargin=dp(18); side.addView(tag,tagLp);
        String[] labels = {"PLAY", "DISPLAY", "PERFORMANCE", "CONTROLS", "FILES", "LOOK", "LOGS"};
        int[] sections = {-1, 0, 1, 2, 3, 4, 5};
        for (int i = 0; i < labels.length; i++) {
            final int section = sections[i];
            Button item = new Button(this); item.setAllCaps(true); item.setText(labels[i]);
            sideNavButtons[i] = item;
            item.setTextColor(Color.rgb(185,169,171)); item.setTextSize(12);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            item.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
            item.setBackgroundColor(0xFF141012);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46)); lp.bottomMargin = dp(5);
            side.addView(item, lp);
            item.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                haptic(v); if (section >= 0) { playTabSound(); showLauncherSection(section); } else if (play != null) play.performClick();
            }});
        }
        TextView version = new TextView(this); version.setText("v0.5.14");
        version.setTextColor(0xFFB8A6AD); version.setTextSize(10); version.setPadding(0,dp(8),0,0);
        side.addView(version);
        return side;
    }

    void launchTrackedGame() {
        prefs().edit().putLong("history_pending_start", System.currentTimeMillis())
                .putString("history_pending_profile", profile()).apply();
        startActivity(new Intent(this, P5RGameActivity.class));
    }

    void finishTrackedSession() {
        long start = prefs().getLong("history_pending_start", 0);
        if (start <= 0) return;
        long elapsed = Math.max(0, System.currentTimeMillis() - start);
        if (elapsed < 6L * 60 * 60 * 1000) {
            prefs().edit().putLong("history_last_start", start)
                    .putLong("history_last_duration", elapsed)
                    .putString("history_last_profile", prefs().getString("history_pending_profile", "Manual"))
                    .putInt("history_sessions", prefs().getInt("history_sessions", 0) + 1).apply();
        }
        prefs().edit().remove("history_pending_start").remove("history_pending_profile").apply();
    }

    void refreshHistory() {
        if (historyStatus == null) return;
        long start = prefs().getLong("history_last_start", 0);
        if (start == 0) {
            historyStatus.setText(t("Historial: todavía no hay sesiones", "Play history: no sessions yet"));
            return;
        }
        long mins = prefs().getLong("history_last_duration", 0) / 60000;
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                .format(new Date(start));
        String summary = t("Última sesión: " + mins + " min • " + when + " • " + prefs().getString("history_last_profile", "Manual"),
                "Last session: " + mins + " min • " + when + " • " + prefs().getString("history_last_profile", "Manual"));
        historyStatus.setText(summary);
    }

    void addLauncherTabs(LinearLayout root) {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        String[] labels = {"DISPLAY", "PERFORMANCE", "CONTROLS", "FILES", "LOOK", "LOGS"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button tab = new Button(this);
            tab.setAllCaps(true);
            tab.setText(labels[i]);
            tab.setTextSize(10);
            tab.setTextColor(Color.WHITE);
            tab.setBackgroundColor(index == 0 ? Color.rgb(190, 0, 25) : Color.rgb(48, 20, 29));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            lp.setMargins(dp(2), dp(3), dp(2), dp(8));
            tabs.addView(tab, lp);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    haptic(v);
                    playTabSound();
                    showLauncherSection(index);
                }
            });
        }
        root.addView(tabs);
        tabs.setTag("shared");
    }

    void addHapticsRow(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(7), dp(8), dp(7));
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(0xD91E1118);
        panel.setStroke(dp(1), 0x66F21B35);
        row.setBackground(panel);
        TextView name = new TextView(this);
        name.setText(t("Vibración del launcher", "Launcher haptics"));
        name.setTextColor(Color.WHITE);
        name.setTextSize(17);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button toggle = new Button(this);
        toggle.setAllCaps(false);
        row.addView(toggle);
        final Runnable update = new Runnable() {
            @Override public void run() {
                toggle.setText(prefs().getBoolean("launcher_haptics", false)
                        ? t("Activada", "On") : t("Desactivada", "Off"));
            }
        };
        update.run();
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs().edit().putBoolean("launcher_haptics", !prefs().getBoolean("launcher_haptics", false)).apply();
                update.run();
                haptic(v);
            }
        });
        row.setTag(4);
        root.addView(row);
        subSections.put(row, 1);
    }

    void addAppearanceRows(LinearLayout root) {
        LinearLayout wallpaperRow = new LinearLayout(this);
        wallpaperRow.setOrientation(LinearLayout.HORIZONTAL);
        wallpaperRow.setGravity(Gravity.CENTER_VERTICAL);
        wallpaperRow.setPadding(dp(10), dp(7), dp(8), dp(7));
        TextView name = new TextView(this);
        name.setText(t("Fondo", "Wallpaper")); name.setTextColor(Color.WHITE); name.setTextSize(17);
        wallpaperRow.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button choose = new Button(this); choose.setAllCaps(false); wallpaperRow.addView(choose);
        final String[] names = {"Calling Card", "Velvet", "Crimson"};
        Runnable updateWallpaper = new Runnable() { @Override public void run() {
            int i = prefs().getInt("launcher_wallpaper", 0); choose.setText(names[Math.max(0, Math.min(2, i))]);
        }};
        updateWallpaper.run();
        choose.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            int next = (prefs().getInt("launcher_wallpaper", 0) + 1) % names.length;
            prefs().edit().putInt("launcher_wallpaper", next).apply(); updateWallpaper.run(); haptic(v);
            if (backdrop != null) backdrop.setAppearance(next, prefs().getBoolean("launcher_motion", false));
        }});
        wallpaperRow.setTag(4); root.addView(wallpaperRow);

        LinearLayout motionRow = new LinearLayout(this);
        motionRow.setOrientation(LinearLayout.HORIZONTAL); motionRow.setGravity(Gravity.CENTER_VERTICAL);
        motionRow.setPadding(dp(10), dp(7), dp(8), dp(7));
        TextView motionName = new TextView(this); motionName.setText(t("Fondo animado", "Animated wallpaper"));
        motionName.setTextColor(Color.WHITE); motionName.setTextSize(17);
        motionRow.addView(motionName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button motion = new Button(this); motion.setAllCaps(false); motionRow.addView(motion);
        Runnable updateMotion = new Runnable() { @Override public void run() {
            motion.setText(prefs().getBoolean("launcher_motion", false) ? t("Activado", "On") : t("Estático", "Static"));
        }};
        updateMotion.run();
        motion.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            boolean next = !prefs().getBoolean("launcher_motion", false);
            prefs().edit().putBoolean("launcher_motion", next).apply(); updateMotion.run(); haptic(v);
            if (backdrop != null) backdrop.setAppearance(prefs().getInt("launcher_wallpaper", 0), next);
        }});
        motionRow.setTag(4); root.addView(motionRow);

        LinearLayout sfxRow = new LinearLayout(this);
        sfxRow.setOrientation(LinearLayout.HORIZONTAL); sfxRow.setGravity(Gravity.CENTER_VERTICAL);
        sfxRow.setPadding(dp(10), dp(7), dp(8), dp(7));
        TextView sfxName = new TextView(this); sfxName.setText(t("Sonido al cambiar pestañas", "Tab switch sound"));
        sfxName.setTextColor(Color.WHITE); sfxName.setTextSize(17);
        sfxRow.addView(sfxName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button sfx = new Button(this); sfx.setAllCaps(false); sfxRow.addView(sfx);
        Runnable updateSfx = new Runnable() { @Override public void run() {
            sfx.setText(prefs().getBoolean("launcher_sfx", true) ? t("Activado", "On") : t("Desactivado", "Off"));
        }};
        updateSfx.run();
        sfx.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            boolean next = !prefs().getBoolean("launcher_sfx", true);
            prefs().edit().putBoolean("launcher_sfx", next).apply(); updateSfx.run(); haptic(v);
            if (next) playTabSound();
        }});
        sfxRow.setTag(4); root.addView(sfxRow); subSections.put(sfxRow, 1);
    }

    void haptic(View view) {
        if (prefs().getBoolean("launcher_haptics", false))
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
    }

    void playTabSound() {
        if (prefs().getBoolean("launcher_sfx", true) && uiSoundPool != null && tabSoundReady)
            uiSoundPool.play(tabSoundId, 0.7f, 0.7f, 1, 0, 1f);
    }

    void showLauncherSection(int selected) {
        if (launcherRoot == null) return;
        activeSection = Math.max(0, Math.min(5, selected));
        activeSubSection = 0;
        if (pageTitle != null) {
            String[] pageNames = {"DISPLAY", "PERFORMANCE", "CONTROLS", "FILES", "APPEARANCE", "LOGS"};
            pageTitle.setText(pageNames[activeSection]);
        }
        for (int i = 1; i < sideNavButtons.length; i++) {
            Button item = sideNavButtons[i];
            if (item == null) continue;
            boolean selectedItem = i == activeSection + 1;
            item.setBackgroundColor(selectedItem ? Color.WHITE : 0xFF141012);
            item.setTextColor(selectedItem ? Color.BLACK : Color.rgb(185,169,171));
            item.setTranslationX(selectedItem ? dp(12) : 0);
            item.setElevation(selectedItem ? dp(5) : 0);
        }
        renderSubTabs();
        for (int i = 0; i < launcherRoot.getChildCount(); i++) {
            View child = launcherRoot.getChildAt(i);
            Object tag = child.getTag();
            boolean visible = tag == null || "shared".equals(tag);
            if (tag instanceof Integer && (Integer) tag == selected)
                visible = subSections.getOrDefault(child, 0) == activeSubSection;
            child.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        applySubSection();
        rebuildFocus(0);
    }

    int optionSubSection(String key) {
        if ("NX_STRETCH".equals(key)) return 2;
        if ("NX_FSR".equals(key)) return 1;
        if ("NX_SHOW_FPS".equals(key)) return 2;
        if ("GRAPHICS".equals(key) || "NX_TEX_AUDIT".equals(key) || key.contains("CROWD") || key.contains("BLOOM")) return 1;
        if ("NX_GAMEPAD_LAYOUT".equals(key) || key.contains("SENSITIVITY")) return 1;
        return 0;
    }

    int optionSection(String key) {
        if ("RESOLUTION".equals(key) || "NX_HUD_1080".equals(key) || "NX_FSR".equals(key)
                || "NX_STRETCH".equals(key) || "language".equals(key)) return 0;
        if (key.startsWith("NX_TOUCH") || "NX_GAMEPAD_LAYOUT".equals(key) || key.contains("SENSITIVITY")) return 2;
        return 1;
    }

    void renderSubTabs() {
        if (subTabBar == null) return;
        subTabBar.removeAllViews();
        String[][] pages = {{"SCALING", "ENHANCE", "FRAMING"}, {"ENGINE", "QUALITY", "LOGS"},
                {"TOUCH", "GAMEPAD"}, {"DATA", "APPEARANCE", "LOGS"}, {"WALLPAPER", "FEEDBACK"}, {"EXPORT"}};
        String[] tabs = pages[activeSection];
        for (int i = 0; i < tabs.length; i++) {
            final int which = i;
            Button tab = new Button(this);
            tab.setAllCaps(true); tab.setText(tabs[i]); tab.setTextSize(11);
            tab.setTextColor(i == activeSubSection ? Color.BLACK : Color.WHITE);
            tab.setBackgroundColor(i == activeSubSection ? Color.WHITE : 0xFF1b1214);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(dp(3), dp(6), dp(3), dp(8)); subTabBar.addView(tab, lp);
            tab.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                haptic(v); playTabSound(); activeSubSection = which; renderSubTabs();
                applySubSection(); rebuildFocus(0);
            }});
        }
    }

    void applySubSection() {
        if (launcherRoot == null) return;
        for (int j=0;j<launcherRoot.getChildCount();j++) {
            View child=launcherRoot.getChildAt(j); Object tag=child.getTag();
            if (tag instanceof Integer && (Integer)tag==activeSection)
                child.setVisibility(subSections.getOrDefault(child,0)==activeSubSection?View.VISIBLE:View.GONE);
        }
    }

    void collectFocusTargets(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof Button && child.isEnabled()) focusTargets.add(child);
            if (child instanceof ViewGroup) collectFocusTargets((ViewGroup) child);
        }
    }

    void rebuildFocus(int index) {
        focusTargets.clear();
        if (launcherRoot != null) collectFocusTargets(launcherRoot);
        focusIndex = focusTargets.isEmpty() ? 0 : Math.max(0, Math.min(index, focusTargets.size() - 1));
        updateFocusVisual();
    }

    void updateFocusVisual() {
        for (int i = 0; i < focusTargets.size(); i++) {
            View view = focusTargets.get(i);
            boolean focused = i == focusIndex;
            view.setScaleX(focused ? 1.04f : 1f);
            view.setScaleY(focused ? 1.04f : 1f);
            view.setElevation(focused ? dp(8) : 0);
            if (Build.VERSION.SDK_INT >= 23) {
                if (focused) {
                    GradientDrawable ring = new GradientDrawable();
                    ring.setColor(Color.TRANSPARENT);
                    ring.setStroke(dp(3), 0xFFffe500);
                    view.setForeground(ring);
                } else view.setForeground(null);
            }
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) {
            if (!focusTargets.isEmpty()) {
                focusIndex = Math.max(0, Math.min(focusTargets.size()-1, focusIndex + (key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)));
                updateFocusVisual(); focusTargets.get(focusIndex).requestFocus(); return true;
            }
        } else if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (!focusTargets.isEmpty()) { adjustFocusedOption(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1); return true; }
        } else if (key == KeyEvent.KEYCODE_BUTTON_L1 || key == KeyEvent.KEYCODE_BUTTON_R1) {
            int count = subTabCount(activeSection);
            activeSubSection = Math.max(0, Math.min(count - 1, activeSubSection + (key == KeyEvent.KEYCODE_BUTTON_L1 ? -1 : 1)));
            playTabSound(); renderSubTabs(); applySubSection(); return true;
        } else if (key == KeyEvent.KEYCODE_BUTTON_L2 || key == KeyEvent.KEYCODE_BUTTON_R2) {
            playTabSound(); showLauncherSection(Math.max(0, Math.min(5, activeSection + (key == KeyEvent.KEYCODE_BUTTON_L2 ? -1 : 1)))); return true;
        } else if (key == KeyEvent.KEYCODE_BUTTON_START) {
            if (play != null) play.performClick(); return true;
        } else if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_ESCAPE) {
            showLauncherSection(0); return true;
        } else if (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_BUTTON_A) {
            if (!focusTargets.isEmpty()) { focusTargets.get(focusIndex).performClick(); return true; }
        }
        return super.dispatchKeyEvent(event);
    }

    int subTabCount(int page) {
        if (page == 0 || page == 1 || page == 3) return 3;
        if (page == 2 || page == 4) return 2;
        return 1;
    }

    void adjustFocusedOption(int dir) {
        View focused = focusTargets.get(focusIndex);
        Object tag = focused.getTag();
        if (!(tag instanceof Option)) return;
        Option o = (Option) tag;
        if (profiled() && PerformanceProfiles.controls(o.key)) return;
        int next = Math.max(0, Math.min(o.values.length - 1, o.pick + dir));
        if (next == o.pick) return;
        o.pick = next;
        if ("THERMAL_PACING".equals(o.key)) {
            prefs().edit().putBoolean("thermal_pacing", o.pick == 1).apply();
            syncThermalPacing();
            updateButtons(getWindow().getDecorView());
            return;
        }
        manualEdit = PerformanceProfiles.MANUAL.equals(profile()) && PerformanceProfiles.controls(o.key);
        manualEditKey = manualEdit ? o.key : null;
        save(); manualEdit = false; manualEditKey = null;
        updateButtons(getWindow().getDecorView());
    }

    @Override public boolean onGenericMotionEvent(android.view.MotionEvent event) {
        if ((event.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) != android.view.InputDevice.SOURCE_JOYSTICK
                || event.getAction() != android.view.MotionEvent.ACTION_MOVE) return super.onGenericMotionEvent(event);
        float x = Math.abs(event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)) > .2f
                ? event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X) : event.getAxisValue(android.view.MotionEvent.AXIS_X);
        float y = Math.abs(event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)) > .2f
                ? event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y) : event.getAxisValue(android.view.MotionEvent.AXIS_Y);
        boolean up = y < -.65f, down = y > .65f, left = x < -.65f, right = x > .65f;
        if (up && !stickUp) moveFocus(-1); if (down && !stickDown) moveFocus(1);
        if (left && !stickLeft) adjustFocusedOption(-1); if (right && !stickRight) adjustFocusedOption(1);
        stickUp = up; stickDown = down; stickLeft = left; stickRight = right;
        float leftTrigger = Math.max(event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(android.view.MotionEvent.AXIS_BRAKE));
        float rightTrigger = Math.max(event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(android.view.MotionEvent.AXIS_GAS));
        boolean leftPressed = leftTrigger > .65f, rightPressed = rightTrigger > .65f;
        if (leftPressed && !triggerLeft) { playTabSound(); showLauncherSection(activeSection - 1); }
        if (rightPressed && !triggerRight) { playTabSound(); showLauncherSection(activeSection + 1); }
        triggerLeft = leftPressed; triggerRight = rightPressed;
        return true;
    }

    void moveFocus(int dir) {
        if (focusTargets.isEmpty()) return;
        focusIndex = Math.max(0, Math.min(focusTargets.size() - 1, focusIndex + dir));
        updateFocusVisual(); focusTargets.get(focusIndex).requestFocus();
    }

    void setupThermalPacing() {
        Option option = findOption("THERMAL_PACING");
        boolean enabled = prefs().getBoolean("thermal_pacing", true);
        if (option != null) option.pick = enabled ? 1 : 0;
        if (Build.VERSION.SDK_INT < 29) return;
        thermalPowerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (thermalPowerManager == null) return;
        thermalListener = new PowerManager.OnThermalStatusChangedListener() {
            @Override public void onThermalStatusChanged(int status) {
                lastThermalStatus = status;
                syncThermalPacing();
            }
        };
        thermalPowerManager.addThermalStatusListener(thermalListener);
        lastThermalStatus = thermalPowerManager.getCurrentThermalStatus();
        syncThermalPacing();
    }

    void syncThermalPacing() {
        File capFile = new File(dataDir(), "thermal_fps");
        if (!prefs().getBoolean("thermal_pacing", true) || lastThermalStatus < PowerManager.THERMAL_STATUS_SEVERE) {
            capFile.delete();
            return;
        }
        if (!hasAccess()) return;
        int cap = lastThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL ? 10 : 15;
        File tempFile = new File(dataDir(), "thermal_fps.tmp");
        try {
            dataDir().mkdirs();
            try (FileWriter writer = new FileWriter(tempFile)) { writer.write(Integer.toString(cap)); }
            if (!tempFile.renameTo(capFile)) {
                try (FileWriter writer = new FileWriter(capFile)) { writer.write(Integer.toString(cap)); }
                tempFile.delete();
            }
        } catch (Exception ignored) {
            tempFile.delete();
        }
    }

    // Short, staggered reveal; it runs only in the launcher, never in the game renderer.
    void animateLauncherEntrance(LinearLayout root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(dp(12));
            child.animate().alpha(1f).translationY(0f).setDuration(220)
                    .setStartDelay(Math.min(i * 28L, 392L)).start();
        }
    }

    void refresh() {
        load();
        if (profileButton != null) profileButton.setText(profileLabel());
        updateDeviceRecommendationRow();
        Option thermalOption = findOption("THERMAL_PACING");
        if (thermalOption != null) thermalOption.pick = prefs().getBoolean("thermal_pacing", true) ? 1 : 0;
        for (Option o : options) {
            if ("THERMAL_PACING".equals(o.key)) continue;
            String v = config.get(o.key);
            if ("RESOLUTION".equals(o.key) && v == null) {
                String hh = config.get("NX_HANDHELD");
                if ("0".equals(hh)) v = "1080p";
                else {
                    String r = config.get("resolution");
                    if (r != null && r.contains("360")) v = "360p";
                    else if (r != null && r.contains("480")) v = "480p";
                    else v = "720p";
                }
            }
            for (int k = 0; v != null && k < o.values.length; k++)
                if (o.values[k].equals(v)) o.pick = k;
        }
        updateButtons(getWindow().getDecorView());
        if (!hasAccess()) {
            status.setText(t("Hace falta el permiso de acceso a todos los archivos para leer los datos del juego.",
                    "All-files access is needed to read the game data."));
            play.setText(t("Dar permiso", "Grant access"));
        } else if (gameUri() != null && !gameReadable()) {
            status.setText(Lang.f("Ya no se puede leer el archivo del juego (%s): vuelve a elegirlo.",
                    "The game file can no longer be read (%s): choose it again.", gameName()));
            play.setText(t("Elegir el juego", "Choose the game"));
        } else if (gameUri() != null && !hasKeys()) {
            status.setText(t("Falta tu archivo prod.keys.", "Your prod.keys file is missing."));
            play.setText(t("Elegir prod.keys", "Choose prod.keys"));
        } else if (gameUri() != null && !prepared()) {
            status.setText(Lang.f("Juego: %s. Falta prepararlo: solo una vez, unos minutos.",
                    "Game: %s. It needs preparing: once, a few minutes.", gameName()));
            play.setText(t("Preparar el juego", "Prepare the game"));
        } else if (gameUri() != null) {
            status.setText(Lang.f("Juego listo: %s", "Game ready: %s", gameName()));
            play.setText(t("Jugar", "Play"));
        } else if (!hasData()) {
            status.setText(t("Elige el archivo de tu juego (.nsp o .xci de Persona 5 Royal 1.0.0) y tu prod.keys.",
                    "Choose your game file (a Persona 5 Royal 1.0.0 .nsp or .xci) and your prod.keys."));
            play.setText(t("Elegir el juego", "Choose the game"));
        } else if (wrongBuild() != null) {
            status.setText(Lang.f("Almacenamiento interno/%s/exefs/main no es Persona 5 Royal 1.0.0 (build %s). Copia el "
                            + "exefs del juego base, sin la actualización.",
                    "Internal storage/%s/exefs/main is not Persona 5 Royal 1.0.0 (build %s). Copy the exefs of the base "
                            + "game, without the update.", DATA, wrongBuild()));
            play.setText(t("Volver a buscar", "Look again"));
        } else {
            status.setText(Lang.f("Datos listos: %s", "Data ready: %s", dataDir().getPath()));
            play.setText(t("Jugar", "Play"));
        }
    }

    void updateButtons(View v) {
        if (v instanceof Button && v.getTag() instanceof Option) {
            Option o = (Option) v.getTag();
            Button b = (Button) v;
            b.setText(label(o));
            b.setEnabled(!(profiled() && PerformanceProfiles.controls(o.key))
                    && !("NX_MALI_LOW_SHADOWS".equals(o.key) && !"1".equals(valueOf("NX_SHADOWS"))));
            if ("NX_MALI_LOW_SHADOWS".equals(o.key)) {
                Option shadows = findOption("NX_SHADOWS");
                boolean enabled = shadows != null && "1".equals(shadows.values[shadows.pick]);
                b.setEnabled(enabled);
                b.setAlpha(enabled ? 1.0f : 0.4f);
                if (b.getParent() instanceof View) {
                    ((View) b.getParent()).setAlpha(enabled ? 1.0f : 0.4f);
                }
            }
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int k = 0; k < g.getChildCount(); k++) updateButtons(g.getChildAt(k));
        }
    }

    void tell(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
    }

    void askImport() {
        new AlertDialog.Builder(this)
                .setTitle(t("Importar partidas", "Import saves"))
                .setMessage(t("Elige un archivo ZIP o una carpeta con partidas de Persona 5 Royal (las carpetas DATA01, "
                                + "DATA02... y SYSTEM, como las exporta JKSV o Checkpoint, o el ZIP que exporta este botón). "
                                + "Antes se guarda una copia de tus partidas actuales.",
                        "Pick a ZIP file or a folder with Persona 5 Royal saves (the DATA01, DATA02... and SYSTEM folders, "
                                + "as JKSV or Checkpoint export them, or the ZIP the Export button makes). Your current saves "
                                + "are copied aside first."))
                .setPositiveButton(t("Archivo ZIP", "ZIP file"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip",
                                "application/x-zip-compressed", "application/octet-stream"});
                        startActivityForResult(i, PICK_ZIP);
                    }
                })
                .setNeutralButton(t("Carpeta", "Folder"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
                    }
                })
                .setNegativeButton(t("Cancelar", "Cancel"), null)
                .show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PREPARE) {
            refresh();
            return;
        }
        if (result != RESULT_OK || data == null) return;
        if (request == PICK_KEYS) {
            onKeysPicked(data);
            refresh();
            return;
        }
        if (data.getData() == null) return;
        if (request == PICK_GAME) {
            onGamePicked(data.getData());
            refresh();
            return;
        }
        if (request == PICK_ICON) {
            onIconPicked(data.getData());
            return;
        }
        if (request == PICK_DRIVER) {
            importDriver(data.getData());
            return;
        }
        if (request == PICK_CPK) {
            importCpk(data.getData());
            return;
        }
        if (request == PICK_MOD) {
            importMod(data.getData());
            return;
        }
        try {
            SaveFiles.Result r = request == PICK_ZIP ? SaveFiles.importZip(getContentResolver(), data.getData())
                    : request == PICK_FOLDER ? SaveFiles.importTree(getContentResolver(), data.getData()) : null;
            if (r == null) {
                tell(t("No hay partidas", "No saves found"),
                        t("Ahí no encontré partidas de Persona 5 Royal: busca las carpetas DATA01, DATA02... o SYSTEM.",
                                "No Persona 5 Royal saves there: look for the DATA01, DATA02... or SYSTEM folders."));
                return;
            }
            String slots = r.slots.isEmpty() ? t("solo ajustes del sistema", "system settings only")
                    : Lang.f("ranuras %s", "slots %s", android.text.TextUtils.join(", ", r.slots));
            String kept = r.backup == null ? "" : Lang.f("\n\nTus partidas anteriores quedaron en user/%s.",
                    "\n\nYour previous saves are in user/%s.", r.backup);
            tell(t("Partidas importadas", "Saves imported"),
                    Lang.f("%d archivos (%s). Ábrelas desde Cargar partida.", "%d files (%s). Open them from Load Game.",
                            r.files, slots) + kept);
        } catch (Exception e) {
            tell(t("No se pudo importar", "Import failed"), String.valueOf(e.getMessage()));
        }
    }

    void exportSaves() {
        try {
            java.io.File out = SaveFiles.export();
            // Listed at once by the file pickers and the Files app (written straight to disk, it would wait for a scan).
            if (out != null) android.media.MediaScannerConnection.scanFile(this, new String[] {out.getPath()}, null, null);
            if (out == null) tell(t("No hay partidas", "No saves"), t("Todavía no hay partidas guardadas.", "There are no saves yet."));
            else tell(t("Partidas exportadas", "Saves exported"),
                    Lang.f("Guardadas en Descargas/%s. Se importan tal cual con el botón Importar.",
                            "Saved to Download/%s. The Import button takes it back as is.", out.getName()));
        } catch (Exception e) {
            tell(t("No se pudo exportar", "Export failed"), String.valueOf(e.getMessage()));
        }
    }

    void askAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(new String[] {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 100);
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // config.txt: KEY=value lines, in order.
    static Map<String, String> readConfig(File f) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!f.canRead()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (eq > 0) out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static boolean writeConfig(File f, Map<String, String> config) {
        try (FileWriter w = new FileWriter(f)) {
            for (Map.Entry<String, String> e : config.entrySet()) w.write(e.getKey() + "=" + e.getValue() + "\n");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void load() {
        config.clear();
        config.putAll(readConfig(new File(dataDir(), "config.txt")));
    }

    void save() {
        if (!hasAccess()) return;
        dataDir().mkdirs();
        if (!dataDir().isDirectory()) return;
        load();
        config.remove("THERMAL_PACING");
        if (manualEdit && prefs().getString("manual_graphics_snapshot", null) == null)
            prefs().edit().putString("manual_graphics_snapshot", PerformanceProfiles.encode(
                    PerformanceProfiles.snapshot(config))).apply();
        for (Option o : options) if (!"THERMAL_PACING".equals(o.key)) config.put(o.key, o.values[o.pick]);
        final String res = config.get("RESOLUTION");
        if ("360p".equals(res)) {
            config.put("NX_HANDHELD", "1");
            config.put("resolution", "360p");
            config.put("NX_RENDER_RES", "360p");
            config.put("NX_RES", "360p");
        } else if ("480p".equals(res)) {
            config.put("NX_HANDHELD", "1");
            config.put("resolution", "480p");
            config.put("NX_RENDER_RES", "480p");
            config.put("NX_RES", "480p");
        } else if ("1080p".equals(res)) {
            config.put("NX_HANDHELD", "0");
            config.put("resolution", "1080p");
            config.put("NX_RENDER_RES", "1080p");
            config.put("NX_RES", "1080p");
        } else {
            config.put("NX_HANDHELD", "1");
            config.put("resolution", "720p");
            config.put("NX_RENDER_RES", "720p");
            config.put("NX_RES", "720p");
        }
        for (String k : new String[] {"RENDER_SCALE", "NX_ANISO_MAX", "NX_LOD_BIAS"}) config.remove(k);
        final String preset = config.get("GRAPHICS");
        if ("low".equals(preset)) config.put("NX_LOD_BIAS", "1");
        else if ("medium".equals(preset)) config.put("NX_LOD_BIAS", "0.5");
        String mode = profile();
        if (PerformanceProfiles.MANUAL.equals(mode)) {
            String saved = prefs().getString("manual_graphics_snapshot", null);
            if (manualEdit) {
                Map<String, String> snapshot = PerformanceProfiles.decode(saved);
                if (snapshot.isEmpty()) snapshot = PerformanceProfiles.snapshot(config);
                if (manualEditKey != null)
                    PerformanceProfiles.updateManualValue(snapshot, manualEditKey, valueOf(manualEditKey));
                prefs().edit().putString("manual_graphics_snapshot", PerformanceProfiles.encode(snapshot)).apply();
                PerformanceProfiles.restore(config, snapshot);
            } else if (saved != null) {
                PerformanceProfiles.restore(config, PerformanceProfiles.decode(saved));
            }
        } else {
            config.putAll(PerformanceProfiles.values(mode));
            // Keep the established resolution aliases in sync with this profile.
            String profileResolution = config.get("RESOLUTION");
            String handheld = "1080p".equals(profileResolution) ? "0" : "1";
            config.put("NX_HANDHELD", handheld);
            config.put("resolution", profileResolution);
            config.put("NX_RENDER_RES", profileResolution);
            config.put("NX_RES", profileResolution);
            if ("low".equals(config.get("GRAPHICS"))) config.put("NX_LOD_BIAS", "1");
            else if ("medium".equals(config.get("GRAPHICS"))) config.put("NX_LOD_BIAS", "0.5");
            else config.remove("NX_LOD_BIAS");
        }
        if (prefs().getBoolean(DeviceRecommendations.PREF_ENABLED, false)) {
            DeviceRecommendations.apply(config, !activeDriver().isEmpty());
            syncOptionsFromConfig();
        }
        writeConfig(new File(dataDir(), "config.txt"), config);
    }

    String profile() { return prefs().getString("graphics_profile", PerformanceProfiles.MANUAL); }

    boolean profiled() { return !PerformanceProfiles.MANUAL.equals(profile()); }

    String valueOf(String key) {
        Option option = findOption(key);
        return option == null ? null : option.values[option.pick];
    }

    String profileLabel() {
        String mode = profile();
        return t("Perfil gráfico: " + mode, "Graphics profile: " + mode);
    }

    void addProfileRow(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView name = new TextView(this);
        name.setText(t("Perfil gráfico", "Graphics profile"));
        name.setTextColor(Color.WHITE);
        name.setTextSize(17);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        profileButton = new Button(this);
        profileButton.setAllCaps(false);
        profileButton.setMinWidth(dp(180));
        profileButton.setText(profileLabel());
        row.addView(profileButton);
        profileButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseNextProfile();
            }
        });
        root.addView(row);
    }

    void addDeviceRecommendationRow(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        deviceRecommendationName = new TextView(this);
        deviceRecommendationName.setTextColor(Color.WHITE);
        deviceRecommendationName.setTextSize(15);
        row.addView(deviceRecommendationName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        deviceRecommendationButton = new Button(this);
        deviceRecommendationButton.setAllCaps(false);
        deviceRecommendationButton.setMinWidth(dp(180));
        row.addView(deviceRecommendationButton);
        deviceRecommendationButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDeviceRecommendationDialog(false); }
        });
        root.addView(row);
        updateDeviceRecommendationRow();
    }

    void updateDeviceRecommendationRow() {
        if (deviceRecommendationName == null || deviceRecommendationButton == null) return;
        deviceRecommendationName.setText(Lang.f("Dispositivo: %s", "Device: %s", DeviceRecommendations.displayName()));
        boolean enabled = prefs().getBoolean(DeviceRecommendations.PREF_ENABLED, false);
        deviceRecommendationButton.setText(enabled ? t("Ajuste activo", "Recommendation on")
                : t("Ajuste recomendado", "Recommended setup"));
    }

    void maybeShowDeviceRecommendation() {
        String identity = DeviceRecommendations.identity();
        if (identity.equals(prefs().getString(DeviceRecommendations.PREF_SEEN_ID, "")) || isFinishing()) return;
        showDeviceRecommendationDialog(true);
    }

    void showDeviceRecommendationDialog(boolean firstPrompt) {
        boolean enabled = prefs().getBoolean(DeviceRecommendations.PREF_ENABLED, false);
        String message = DeviceRecommendations.recommendation(!activeDriver().isEmpty());
        if (enabled) message += "\n\n" + t("La recomendación está activa. Puedes volver a los ajustes previos desde aquí.",
                "The recommendation is active. You can restore your previous settings here.");
        else message += "\n\n" + t("Es opcional y no instala controladores. Puedes cambiarla después.",
                "This is optional and does not install a driver. You can change it later.");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(Lang.f("Ajustes para %s", "Settings for %s", DeviceRecommendations.displayName()))
                .setMessage(message)
                .setPositiveButton(enabled ? t("Aplicar otra vez", "Apply again") : t("Usar recomendados", "Use recommended"),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) { enableDeviceRecommendation(); }
                        })
                .create();
        if (enabled) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, t("Desactivar", "Turn off"),
                    new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) { disableDeviceRecommendation(); }
                    });
            dialog.setButton(AlertDialog.BUTTON_NEGATIVE, t("Cerrar", "Close"),
                    new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) { markDeviceRecommendationSeen(); }
                    });
        } else {
            dialog.setButton(AlertDialog.BUTTON_NEGATIVE,
                    firstPrompt ? t("Mantener ajustes", "Keep current settings") : t("Cerrar", "Close"),
                    new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) { markDeviceRecommendationSeen(); }
                    });
        }
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface d) { markDeviceRecommendationSeen(); }
        });
        dialog.show();
    }

    void markDeviceRecommendationSeen() {
        prefs().edit().putString(DeviceRecommendations.PREF_SEEN_ID, DeviceRecommendations.identity()).apply();
    }

    void enableDeviceRecommendation() {
        if (!hasAccess()) {
            Toast.makeText(this, t("Permite acceso a la carpeta del juego para guardar los ajustes.",
                    "Allow access to the game folder to save these settings."), Toast.LENGTH_LONG).show();
            askAccess();
            return;
        }
        markDeviceRecommendationSeen();
        load();
        if (!prefs().getBoolean(DeviceRecommendations.PREF_ENABLED, false)) {
            SharedPreferences.Editor editor = prefs().edit()
                    .putBoolean(DeviceRecommendations.PREF_ENABLED, true)
                    .putBoolean(DeviceRecommendations.PREF_PREVIOUS_DRIVER_PRESENT, config.containsKey("NX_SYSTEM_DRIVER"))
                    .putBoolean(DeviceRecommendations.PREF_PREVIOUS_RESOLUTION_PRESENT, config.containsKey("RESOLUTION"))
                    .putBoolean(DeviceRecommendations.PREF_PREVIOUS_FPS_PRESENT, config.containsKey("NX_FPS_CAP"));
            if (config.containsKey("NX_SYSTEM_DRIVER"))
                editor.putString(DeviceRecommendations.PREF_PREVIOUS_DRIVER_VALUE, config.get("NX_SYSTEM_DRIVER"));
            if (config.containsKey("RESOLUTION"))
                editor.putString(DeviceRecommendations.PREF_PREVIOUS_RESOLUTION_VALUE, config.get("RESOLUTION"));
            if (config.containsKey("NX_FPS_CAP"))
                editor.putString(DeviceRecommendations.PREF_PREVIOUS_FPS_VALUE, config.get("NX_FPS_CAP"));
            editor.apply();
        }
        save();
        updateDeviceRecommendationRow();
        refresh();
        Toast.makeText(this, t("Ajustes guardados para el próximo inicio del juego.",
                "Recommended settings saved for the next game launch."), Toast.LENGTH_LONG).show();
    }

    void disableDeviceRecommendation() {
        if (!hasAccess()) { askAccess(); return; }
        load();
        restoreDeviceRecommendation(config, null, null);
        prefs().edit().putBoolean(DeviceRecommendations.PREF_ENABLED, false).apply();
        writeConfig(new File(dataDir(), "config.txt"), config);
        save();
        updateDeviceRecommendationRow();
        refresh();
    }

    void restoreDeviceRecommendation(Map<String, String> target, String preserveKey, String preserveValue) {
        restoreRecommendationEntry(target, "RESOLUTION", DeviceRecommendations.PREF_PREVIOUS_RESOLUTION_PRESENT,
                DeviceRecommendations.PREF_PREVIOUS_RESOLUTION_VALUE);
        restoreRecommendationEntry(target, "NX_FPS_CAP", DeviceRecommendations.PREF_PREVIOUS_FPS_PRESENT,
                DeviceRecommendations.PREF_PREVIOUS_FPS_VALUE);
        restoreRecommendationEntry(target, "NX_SYSTEM_DRIVER", DeviceRecommendations.PREF_PREVIOUS_DRIVER_PRESENT,
                DeviceRecommendations.PREF_PREVIOUS_DRIVER_VALUE);
        if (preserveKey != null) target.put(preserveKey, preserveValue);
    }

    void restoreRecommendationEntry(Map<String, String> target, String configKey, String presentPref, String valuePref) {
        if (prefs().getBoolean(presentPref, false)) target.put(configKey, prefs().getString(valuePref, ""));
        else target.remove(configKey);
    }

    void disableRecommendationForManualEdit(String key, String value) {
        if (!hasAccess()) return;
        load();
        restoreDeviceRecommendation(config, key, value);
        prefs().edit().putBoolean(DeviceRecommendations.PREF_ENABLED, false).apply();
        writeConfig(new File(dataDir(), "config.txt"), config);
        updateDeviceRecommendationRow();
    }

    void chooseNextProfile() {
        String old = profile();
        int next = 0;
        for (int i = 0; i < PerformanceProfiles.MODES.length; i++)
            if (PerformanceProfiles.MODES[i].equals(old)) next = (i + 1) % PerformanceProfiles.MODES.length;
        String mode = PerformanceProfiles.MODES[next];
        if (PerformanceProfiles.MANUAL.equals(old) && !PerformanceProfiles.MANUAL.equals(mode)) {
            load();
            prefs().edit().putString("manual_graphics_snapshot", PerformanceProfiles.encode(
                    PerformanceProfiles.snapshot(config))).apply();
        }
        prefs().edit().putString("graphics_profile", mode).apply();
        if (PerformanceProfiles.MANUAL.equals(mode)) {
            String saved = prefs().getString("manual_graphics_snapshot", null);
            if (saved != null) {
                load();
                PerformanceProfiles.restore(config, PerformanceProfiles.decode(saved));
                writeConfig(new File(dataDir(), "config.txt"), config);
            }
        }
        syncOptionsFromConfig();
        save();
        refresh();
    }

    void syncOptionsFromConfig() {
        for (Option option : options) {
            String value = config.get(option.key);
            if ("RESOLUTION".equals(option.key) && value == null) value = config.get("resolution");
            for (int i = 0; value != null && i < option.values.length; i++)
                if (option.values[i].equals(value)) option.pick = i;
        }
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ---- custom GPU driver ------------------------------------------------------------------------------------
    // <files>/gpu_driver/<name>.so and active.txt (its name): the runtime opens it with libadrenotools instead of the
    // system Vulkan driver (platform_android.cpp). A .so, or a .zip holding one (the AdrenoTools driver packages:
    // the .so and a meta.json whose "libraryName" is the file to load).
    TextView driverName;

    File driverDir() {
        File d = new File(getFilesDir(), "gpu_driver");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    String activeDriver() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new FileReader(new File(driverDir(), "active.txt")))) {
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }

    void showDriver() {
        if (driverName == null) return;
        String a = activeDriver();
        driverName.setText(a.isEmpty() ? t("Driver de GPU: del sistema", "GPU driver: system")
                : Lang.f("Driver de GPU: %s", "GPU driver: %s", a));
    }

    void removeDriver() {
        File[] files = driverDir().listFiles();
        if (files != null) for (File f : files) f.delete();
        showDriver();
        Toast.makeText(this, t("Driver del sistema activado", "System driver restored"), Toast.LENGTH_LONG).show();
    }

    static void copy(InputStream in, File dest) throws java.io.IOException {
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    void importDriver(Uri uri) {
        try {
            String picked = displayName(uri);
            File dir = driverDir();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            String libName = null;
            if (picked.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                String soEntry = null, wanted = null;
                try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(getContentResolver().openInputStream(uri))) {
                    java.util.zip.ZipEntry e;
                    while ((e = zin.getNextEntry()) != null) {
                        String base = new File(e.getName()).getName();
                        if (e.isDirectory()) continue;
                        if (base.equals("meta.json")) {
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"libraryName\"\\s*:\\s*\"([^\"]+)\"")
                                    .matcher(bo.toString("UTF-8"));
                            if (m.find()) wanted = m.group(1);
                        } else if (base.endsWith(".so")) {
                            copy(zin, new File(dir, base));
                            if (soEntry == null) soEntry = base;
                        }
                    }
                }
                libName = wanted != null && new File(dir, wanted).exists() ? wanted : soEntry;
            } else {
                libName = picked.endsWith(".so") ? picked : picked + ".so";
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("Cannot open selected file");
                    copy(in, new File(dir, libName));
                }
            }
            if (libName == null) throw new java.io.IOException(t("No hay ningun .so dentro", "No .so file inside"));
            try (FileWriter w = new FileWriter(new File(dir, "active.txt"))) {
                w.write(libName + "\n");
            }
            showDriver();
            tell(t("Driver importado", "Driver imported"),
                    Lang.f("%s\n\nCierra el juego por completo y abrelo otra vez. Para volver al driver del sistema pulsa Quitar.",
                            "%s\n\nClose the game completely and start it again. To go back to the system driver press Remove.",
                            libName));
        } catch (Exception e) {
            tell(t("No se pudo importar el driver", "Driver import failed"), String.valueOf(e.getMessage()));
        }
    }

    void importCpk(Uri uri) {
        try {
            File cpkDir = new File(dataDir(), "mods" + File.separator + "translation" + File.separator + "romfs" + File.separator + "CPK");
            if (!cpkDir.exists()) cpkDir.mkdirs();
            File dest = new File(cpkDir, "PATCH1.CPK");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(dest)) {
                if (in == null) throw new java.io.IOException("Cannot open selected file");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Toast.makeText(this, t("Traducción instalada en mods/translation/ (PATCH1.CPK)",
                    "Translation installed to mods/translation/ (as PATCH1.CPK)"), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, t("Error al importar CPK: " + e.getMessage(),
                    "Error importing CPK: " + e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    void showModManager() {
        if (!hasAccess()) { askAccess(); return; }
        final File mods = new File(dataDir(), "mods");
        if (!mods.isDirectory()) mods.mkdirs();
        File[] children = mods.listFiles();
        final List<File> entries = new ArrayList<>();
        if (children != null) {
            for (File child : children) {
                String name = child.getName();
                if (child.isDirectory() && !name.startsWith(".") &&
                        new File(child, "romfs").isDirectory()) entries.add(child);
            }
        }
        java.util.Collections.sort(entries, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });
        if (entries.isEmpty()) {
            tell(t("Administrador de mods", "Mod manager"),
                    Lang.f("No hay mods instalados. Coloca cada mod en %s\\mods\\<Nombre>\\romfs.",
                            "No mods found. Put each mod in %s\\mods\\<Name>\\romfs.", dataDir().getPath()));
            return;
        }
        final String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            String name = entries.get(i).getName();
            boolean enabled = !name.startsWith("_");
            labels[i] = (enabled ? "✓  " : "○  ") + (enabled ? name : name.substring(1)) +
                    t(enabled ? "  (activado)" : "  (desactivado)", enabled ? "  (enabled)" : "  (disabled)");
        }
        new AlertDialog.Builder(this).setTitle(t("Mods · toca para activar/desactivar", "Mods · tap to enable/disable"))
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        File old = entries.get(which);
                        String name = old.getName();
                        String nextName = name.startsWith("_") ? name.substring(1) : "_" + name;
                        File next = new File(mods, nextName);
                        if (next.exists() || !old.renameTo(next)) {
                            tell(t("No se pudo cambiar el mod", "Could not change mod"), name);
                            return;
                        }
                        Toast.makeText(Launcher.this,
                                t("Cambio aplicado. Cierra y vuelve a abrir el juego.", "Change saved. Restart the game to apply it."),
                                Toast.LENGTH_LONG).show();
                        showModManager();
                    }
                }).setNegativeButton(t("Cerrar", "Close"), null).show();
    }

    void importMod(Uri uri) {
        File temp = new File(getCacheDir(), "mod_import_" + System.currentTimeMillis());
        try {
            if (!temp.mkdirs()) throw new java.io.IOException("Cannot create temporary folder");
            int count = 0;
            long total = 0;
            try (InputStream raw = getContentResolver().openInputStream(uri);
                 java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(raw)) {
                if (raw == null) throw new java.io.IOException("Cannot open archive");
                java.util.zip.ZipEntry entry;
                byte[] buffer = new byte[65536];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++count > 100000) throw new java.io.IOException("Archive contains too many files");
                    String relative = entry.getName().replace('\\', '/');
                    File destination = new File(temp, relative);
                    String rootPath = temp.getCanonicalPath() + File.separator;
                    if (!destination.getCanonicalPath().startsWith(rootPath))
                        throw new java.io.IOException("Unsafe path in archive");
                    if (entry.isDirectory()) destination.mkdirs();
                    else {
                        File parent = destination.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (OutputStream out = new FileOutputStream(destination)) {
                            int n;
                            while ((n = zip.read(buffer)) > 0) {
                                total += n;
                                if (total > 8L * 1024 * 1024 * 1024) throw new java.io.IOException("Archive exceeds 8 GB extracted size limit");
                                out.write(buffer, 0, n);
                            }
                        }
                    }
                    zip.closeEntry();
                }
            }
            File romfs = findRomfs(temp, 0);
            if (romfs == null) throw new java.io.IOException("No romfs folder found. Use a LayeredFS mod ZIP containing romfs/.");
            String base = displayName(uri).replaceFirst("(?i)\\.zip$", "").replaceAll("[^A-Za-z0-9._ -]", "_").trim();
            if (base.isEmpty() || base.startsWith("_") || base.startsWith(".")) base = "ImportedMod";
            File mods = new File(dataDir(), "mods"); mods.mkdirs();
            String name = base;
            for (int suffix = 2; new File(mods, name).exists(); suffix++) name = base + " (" + suffix + ")";
            File target = new File(new File(mods, name), "romfs");
            if (!copyFolder(romfs, target)) throw new java.io.IOException("Could not copy mod files");
            Toast.makeText(this, Lang.f("Mod instalado: %s. Reinicia el juego para aplicarlo.",
                    "Mod installed: %s. Restart the game to apply it.", name), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            tell(t("No se pudo instalar el mod", "Could not install mod"), String.valueOf(e.getMessage()));
        } finally {
            deleteFolder(temp);
        }
    }

    File findRomfs(File dir, int depth) {
        if (depth > 10) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) if (file.isDirectory() && "romfs".equalsIgnoreCase(file.getName())) return file;
        for (File file : files) if (file.isDirectory()) {
            File found = findRomfs(file, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    boolean copyFolder(File source, File target) throws java.io.IOException {
        if (!target.mkdirs() && !target.isDirectory()) return false;
        File[] files = source.listFiles();
        if (files == null) return false;
        for (File file : files) {
            File to = new File(target, file.getName());
            if (file.isDirectory()) { if (!copyFolder(file, to)) return false; }
            else try (InputStream in = new java.io.FileInputStream(file)) { copy(in, to); }
        }
        return true;
    }

    void deleteFolder(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) deleteFolder(file);
        dir.delete();
    }

    void exportLogs() {
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dl.exists()) dl.mkdirs();
            File out = new File(dl, "p5r-log.txt");
            StringBuilder sb = new StringBuilder();
            sb.append("=== Persona 5 Royal Recomp Log Export ===\n");
            sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("Date: ").append(new java.util.Date().toString()).append("\n\n");

            try {
                Process p = Runtime.getRuntime().exec(new String[] {"logcat", "-d", "-t", "500"});
                try (BufferedReader br = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                }
            } catch (Exception e) {
                sb.append("logcat capture error: ").append(e.getMessage()).append("\n");
            }

            File nxLog = new File(dataDir(), "nx.log");
            if (nxLog.exists()) {
                sb.append("\n=== nx.log ===\n");
                try (BufferedReader br = new BufferedReader(new FileReader(nxLog))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                }
            }

            File nxLogPrev = new File(dataDir(), "nx.log.prev");
            if (nxLogPrev.exists()) {
                sb.append("\n=== nx.log.prev ===\n");
                try (BufferedReader br = new BufferedReader(new FileReader(nxLogPrev))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                }
            }

            try (FileWriter fw = new FileWriter(out)) {
                fw.write(sb.toString());
            }

            Toast.makeText(this, t("Registros exportados a Download/p5r-log.txt",
                    "Logs exported to Download/p5r-log.txt"), Toast.LENGTH_SHORT).show();

            Uri logUri = Uri.parse("content://" + getPackageName() + ".logprovider" + out.getAbsolutePath());
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, logUri);
            share.putExtra(Intent.EXTRA_SUBJECT, "Persona 5 Royal Debug Log");
            share.setClipData(android.content.ClipData.newRawUri("Log", logUri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, t("Compartir archivo de registros", "Share log file")));
        } catch (Exception e) {
            Toast.makeText(this, t("Error al exportar registros: " + e.getMessage(),
                    "Error exporting logs: " + e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    void addRow(LinearLayout root, String label, String button, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(Color.WHITE);
        name.setTextSize(17);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setMinWidth(dp(180));
        b.setText(button);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { haptic(v); click.onClick(v); }
        });
        row.addView(b);
        root.addView(row);
    }

    String optionDescription(String key) {
        if ("NX_TOUCH_FIXED_STICKS".equals(key))
            return t("Fija las dos palancas táctiles en su sitio; ambas siguen respondiendo al tacto.",
                    "Keep both touch sticks anchored in place while they remain fully usable.");
        if (key.startsWith("NX_TOUCH")) return t("Ajusta los controles en pantalla", "Adjust the on-screen controls");
        if ("RESOLUTION".equals(key)) return t("Resolución de renderizado del juego", "Game render resolution");
        if ("NX_HUD_1080".equals(key)) return t("Nitidez de menús y textos", "Menu and text sharpness");
        if ("NX_FSR".equals(key)) return t("Escalado para mejorar la nitidez", "Upscaling for a sharper image");
        if ("NX_FPS_CAP".equals(key)) return t("Objetivo de fotogramas", "Frame-rate target");
        if ("THERMAL_PACING".equals(key)) return t("Reduce el límite al calentarse; conserva los gráficos", "Lowers the frame cap when hot; keeps graphics unchanged");
        if ("NX_ASYNC_PIPELINES".equals(key)) return t("Puede reducir tirones con efectos nuevos", "Can reduce hitches with new effects");
        if ("NX_TEX_AUDIT".equals(key)) return t("Vuelve a cargar texturas de escena que cambian; puede corregir parpadeos o ausencias", "Refreshes changed scene textures; may fix flicker or missing textures");
        if ("NX_SHADOWS".equals(key)) return t("Desactivarlas puede mejorar el rendimiento", "Turning shadows off may improve performance");
        if ("NX_SHOW_FPS".equals(key)) return t("Muestra los FPS durante el juego", "Show FPS while playing");
        if ("GRAPHICS".equals(key)) return t("Calidad de texturas del juego", "Game texture quality");
        if ("NX_NO_CROWD".equals(key)) return t("Reduce personajes de fondo", "Reduce background crowds");
        if ("NX_DISABLE_BLOOM".equals(key)) return t("Efectos de brillo", "Glow post-processing effect");
        return t("Ajuste del juego", "Game setting");
    }
}
