/*
    Copyright (C) 2015, 2016 sandstranger
    Copyright (C) 2018, 2019 Ilya Zhuravlev

    This file is part of OpenMW-Android.

    OpenMW-Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    OpenMW-Android is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with OpenMW-Android.  If not, see <https://www.gnu.org/licenses/>.
*/

package ui.activity

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.ProgressDialog
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.system.ErrnoException
import android.system.Os
import android.util.DisplayMetrics
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import com.bugsnag.android.Bugsnag

import com.libopenmw.openmw.BuildConfig
import com.libopenmw.openmw.R
import constants.Constants
import file.GameInstaller

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader

import file.utils.CopyFilesFromAssets
import mods.ModType
import mods.ModsCollection
import mods.ModsDatabaseOpenHelper
import ui.fragments.FragmentSettings
import permission.PermissionHelper
import utils.MyApp
import utils.Utils.hideAndroidControls
import java.util.*

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MyApp.app.defaultScaling = determineScaling()

        PermissionHelper.getWriteExternalStoragePermission(this@MainActivity)
        setContentView(R.layout.main)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        fragmentManager.beginTransaction()
            .replace(R.id.content_frame, FragmentSettings()).commit()

        setSupportActionBar(findViewById(R.id.main_toolbar))

        val fab = findViewById<FloatingActionButton>(R.id.fab)
        fab.setOnClickListener { checkStartGame() }

        if (prefs.getString("bugsnag_consent", "")!! == "") {
            askBugsnagConsent()
        }
    }

    /**
     * Set new user consent and maybe restart the app
     * @param consent New value of bugsnag consent
     */
    @SuppressLint("ApplySharedPref")
    private fun setBugsnagConsent(consent: String) {
        val currentConsent = prefs.getString("bugsnag_consent", "")!!
        if (currentConsent == consent)
            return

        // We only need to force a restart if the user revokes their consent
        // If user grants consent, crashes won't be reported for 1 game session, but that's alright
        val needRestart = currentConsent == "true" && consent == "false"

        with (prefs.edit()) {
            putString("bugsnag_consent", consent)
            commit()
        }

        if (needRestart) {
            AlertDialog.Builder(this)
                .setOnDismissListener { System.exit(0) }
                .setTitle(R.string.bugsnag_consent_restart_title)
                .setMessage(R.string.bugsnag_consent_restart_message)
                .setPositiveButton(android.R.string.ok) { _, _ -> System.exit(0) }
                .show()
        }
    }

    /**
     * Opens the url in a web browser and gracefully handles the failure
     * @param url Url to open
     */
    fun openUrl(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(browserIntent)
        } catch (e: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setTitle(R.string.no_browser_title)
                .setMessage(getString(R.string.no_browser_message, url))
                .setPositiveButton(android.R.string.ok) { _, _ -> }
                .show()
        }
    }

    /**
     * Asks the user if they want to automatically report crashes
     */
    private fun askBugsnagConsent() {
        // Do nothing for builds without api-key
        if (!MyApp.haveBugsnagApiKey)
            return

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.bugsnag_consent_title)
            .setMessage(R.string.bugsnag_consent_message)
            .setNeutralButton(R.string.bugsnag_policy) { _, _ -> /* set up below */ }
            .setNegativeButton(R.string.bugsnag_no) { _, _ -> setBugsnagConsent("false") }
            .setPositiveButton(R.string.bugsnag_yes) { _, _ -> setBugsnagConsent("true") }
            .create()

        dialog.show()

        // don't close the dialog when the privacy-policy button is clicked
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            openUrl("https://omw.xyz.is/privacy-policy.html")
        }
    }

    /**
     * Checks that the game is properly installed and if so, starts the game
     * - the game files must be selected
     * - there must be at least 1 activated mod (user can ignore this warning)
     */
    private fun checkStartGame() {
        // First, check that there are game files present
        val inst = GameInstaller(prefs.getString("game_files", "")!!)
        if (!inst.check()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.no_data_files_title)
                .setMessage(R.string.no_data_files_message)
                .setNeutralButton(R.string.dialog_howto) { _, _ ->
                    openUrl("https://omw.xyz.is/game.html")
                }
                .setPositiveButton(android.R.string.ok) { _: DialogInterface, _: Int -> }
                .show()
            return
        }

        // Second, check if user has at least one mod enabled
        val plugins = ModsCollection(ModType.Plugin, inst.findDataFiles(),
            ModsDatabaseOpenHelper.getInstance(this))
        if (plugins.mods.count { it.enabled } == 0) {
            // No mods enabled, show a warning
            AlertDialog.Builder(this)
                .setTitle(R.string.no_content_files_title)
                .setMessage(R.string.no_content_files_message)
                .setNeutralButton(R.string.dialog_howto) { _, _ ->
                    openUrl("https://omw.xyz.is/mods.html")
                }
                .setNegativeButton(R.string.no_content_files_dismiss) { _, _ -> startGame() }
                .setPositiveButton(R.string.configure_mods) { _, _ ->
                    this.startActivity(Intent(this, ModsActivity::class.java))
                }
                .show()

            return
        }

        // If everything's alright, start the game
        startGame()
    }

    private fun deleteRecursive(fileOrDirectory: File) {
        if (fileOrDirectory.isDirectory)
            for (child in fileOrDirectory.listFiles())
                deleteRecursive(child)

        fileOrDirectory.delete()
    }

    private fun logConfig() {

    }

    private fun runGame() {
        logConfig()
        val intent = Intent(this@MainActivity,
            GameActivity::class.java)
        finish()

        this@MainActivity.startActivityForResult(intent, 1)
    }


    /**
     * Set up fixed screen resolution
     * This doesn't do anything unless the user chose to override screen resolution
     */
    private fun obtainFixedScreenResolution() {
        // Split resolution e.g 640x480 to width/height
        val customResolution = prefs.getString("pref_customResolution", "")
        val sep = customResolution!!.indexOf("x")
        if (sep > 0) {
            try {
                val x = Integer.parseInt(customResolution.substring(0, sep))
                val y = Integer.parseInt(customResolution.substring(sep + 1))

                resolutionX = x
                resolutionY = y
            } catch (e: NumberFormatException) {
                // user entered resolution wrong, just ignore it
            }
        }
    }

    /**
     * Generates openmw.cfg using values from openmw.base.cfg combined with mod manager settings
     */
    private fun generateOpenmwCfg() {
        // contents of openmw.base.cfg
        val base: String
        // contents of openmw.fallback.cfg
        val fallback: String

        // try to read the files
        try {
            base = File(Constants.OPENMW_BASE_CFG).readText()
            // TODO: support user custom options
            fallback = File(Constants.OPENMW_FALLBACK_CFG).readText()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read openmw.base.cfg or openmw.fallback.cfg", e)
            return
        }

        val dataFiles = GameInstaller.getDataFiles(this)
        val db = ModsDatabaseOpenHelper.getInstance(this)
        val resources = ModsCollection(ModType.Resource, dataFiles, db)
        val plugins = ModsCollection(ModType.Plugin, dataFiles, db)
        val groundcovers = ModsCollection(ModType.Groundcover, dataFiles, db)

        try {
            // generate final output.cfg
            var output = base + "\n" + fallback + "\n"

            // output resources
            resources.mods
                .filter { it.enabled }
                .forEach { output += "fallback-archive=${it.filename}\n" }

            // output plugins
            plugins.mods
                .filter { it.enabled }
                .forEach { output += "content=${it.filename}\n" }

            // output groundcover
            groundcovers.mods
                .filter { it.enabled }
                .forEach { output += "groundcover=${it.filename}\n" }

            // write everything to openmw.cfg
            File(Constants.OPENMW_CFG).writeText(output)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to generate openmw.cfg.", e)
        }
    }

    /**
     * Determines required screen scaling based on resolution and physical size of the device
     */
    private fun determineScaling(): Float {
        // The idea is to stretch an old-school 1024x768 monitor to the device screen
        // Assume that 1x scaling corresponds to resolution of 1024x768
        // Assume that the longest side of the device corresponds to the 1024 side
        // Therefore scaling is calculated as longest size of the device divided by 1024
        // Note that it doesn't take into account DPI at all. Which is fine for now, but in future
        // we might want to add some bonus scaling to e.g. phone devices so that it's easier
        // to click things.

        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        return maxOf(dm.heightPixels, dm.widthPixels) / 1024.0f
    }

    /**
     * Removes old and creates new files located in private application directories
     * (i.e. under getFilesDir(), or /data/data/.../files)
     */
    private fun reinstallStaticFiles() {
        // we store global "config" and "resources" under private files

        // wipe old version first
        removeStaticFiles()

        // copy in the new version
        val assetCopier = CopyFilesFromAssets(this)
        assetCopier.copy("libopenmw/resources", Constants.RESOURCES)
        assetCopier.copy("libopenmw/openmw", Constants.GLOBAL_CONFIG)

        // set up user config (if not present)
        File(Constants.USER_CONFIG).mkdirs()
        if (!File(Constants.USER_OPENMW_CFG).exists())
            File(Constants.USER_OPENMW_CFG).writeText("# This is the user openmw.cfg. Feel free to modify it as you wish.\n")

        // set version stamp
        File(Constants.VERSION_STAMP).writeText(BuildConfig.VERSION_CODE.toString())
    }

    /**
     * Removes global static files, these include resources and config
     */
    private fun removeStaticFiles() {
        // remove version stamp so that reinstallStaticFiles is called during game launch
        File(Constants.VERSION_STAMP).delete()

        deleteRecursive(File(Constants.GLOBAL_CONFIG))
        deleteRecursive(File(Constants.RESOURCES))
    }

    /**
     * Resets user config to default values by removing it
     */
    private fun removeUserConfig() {
        deleteRecursive(File(Constants.USER_CONFIG))
    }

    private fun configureDefaultsBin(args: Map<String, String>) {
        val defaults = File(Constants.DEFAULTS_BIN).readText()
        val decoded = String(Base64.getDecoder().decode(defaults))
        val lines = decoded.lines().map {
            for ((k, v) in args) {
                if (it.startsWith("$k ="))
                    return@map "$k = $v"
            }
            it
        }
        val data = lines.joinToString("\n")
        val encoded = Base64.getEncoder().encodeToString(data.toByteArray())
        File(Constants.DEFAULTS_BIN).writeText(encoded)
    }

    private fun startGame() {
//***********************************************************************************************************************************************************

        var scaling = 0f

        try {
            scaling = prefs.getString("pref_uiScaling", "")!!.toFloat()
        } catch (e: NumberFormatException) {
            with(prefs.edit()) {
                putString("pref_uiScaling", "")
                apply()
            }
        }
        // set up gamma, if invalid, use the default (1.0)
        var gamma = 1.0f
        try {
            gamma = prefs.getString("pref_gamma", "")!!.toFloat()
        } catch (e: NumberFormatException) {
            // Reset the invalid setting
            with(prefs.edit()) {
                putString("pref_gamma", "")
                apply()
            }
        }

        try {
            Os.setenv("OPENMW_GAMMA", "%.2f".format(Locale.ROOT, gamma), true)
        } catch (e: ErrnoException) {
            // can't really do much if that fails...
        }

        // If scaling didn't get set, determine it automatically
        if (scaling == 0f) {
            scaling = MyApp.app.defaultScaling
        }

        val dialog = ProgressDialog.show(
            this, "", "Preparing for launch...", true)

        val activity = this

        // hide the controls so that ScreenResolutionHelper can get the right resolution
        hideAndroidControls(this)

        val th = Thread {
            try {
                // Only reinstall static files if they are of a mismatched version
                try {
                    val stamp = File(Constants.VERSION_STAMP).readText().trim()
                    if (stamp.toInt() != BuildConfig.VERSION_CODE) {
                        reinstallStaticFiles()
                    }
                } catch (e: Exception) {
                    reinstallStaticFiles()
                }

                val inst = GameInstaller(prefs.getString("game_files", "")!!)

                // Regenerate the fallback file in case user edits their Morrowind.ini
                inst.convertIni(prefs.getString("pref_encoding", GameInstaller.DEFAULT_CHARSET_PREF)!!)

                generateOpenmwCfg()

                // openmw.cfg: data, resources
                file.Writer.write(Constants.OPENMW_CFG, "resources", Constants.RESOURCES)
                file.Writer.write(Constants.OPENMW_CFG, "data", "\"" + inst.findDataFiles() + "\"")

                file.Writer.write(Constants.OPENMW_CFG, "encoding", prefs!!.getString("pref_encoding", GameInstaller.DEFAULT_CHARSET_PREF)!!)

                var settingsFile = File(Constants.USER_CONFIG + "/settings.cfg")
                var settingsFolder = File(Constants.USER_CONFIG)
                settingsFolder.mkdirs()

                val settingsFileCreated :Boolean = settingsFile.createNewFile()
                if(settingsFileCreated)
                    settingsFile.writeText(File(filesDir, "config/settings.cfg").readText())

                val enabler = prefs.getBoolean("pref_global_functions", false)
                if (enabler) {

                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "scaling factor", "%.2f".format(Locale.ROOT, scaling))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "viewing distance", prefs.getString("pref_viewing_distance", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "framerate limit", prefs.getString("pref_framerate_limit", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "antialiasing", prefs.getString("pref_antialiasing", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "async num threads", prefs.getString("pref_async", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "show owned", prefs.getString("pref_show_owned", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "strength influences hand to hand", prefs.getString("pref_strength_influences_hand_to_hand", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "projectiles enchant multiplier", prefs.getString("pref_projectiles_enchant_multiplier", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "rtt size", prefs.getString("pref_rtt_size", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "reflection detail", prefs.getString("pref_reflection_detail", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "lod factor", prefs.getString("pref_lod_factor", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "difficulty", prefs.getString("pref_difficulty", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "target framerate", prefs.getString("pref_target_framerate", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "small feature culling pixel size", prefs.getString("pref_small_feature_culling_pixel_size", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "max quicksaves", prefs.getString("pref_max_quicksaves", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "preload num threads", prefs.getString("pref_preload_num_threads", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "density", prefs.getString("pref_density", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "min chunk size", prefs.getString("pref_min_chunk_size", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "stomp mode", prefs.getString("pref_stomp_mode", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "stomp intensity", prefs.getString("pref_stomp_intensity", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "object paging min size", prefs.getString("pref_object_paging_min_size", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "lighting method", prefs.getString("pref_lighting_method", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "max lights", prefs.getString("pref_max_lights", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "minimum interior brightness", prefs.getString("pref_minimum_interior_brightness", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "preload distance", prefs.getString("pref_preload_distance", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "object paging merge factor", prefs.getString("pref_object_paging_merge_factor", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "rendering distance", prefs.getString("pref_rendering_distance", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "vertex lod mod", prefs.getString("pref_vertex_lod_mod", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "composite map level", prefs.getString("pref_composite_map_level", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "color topic specific", prefs.getString("pref_color_topic_specific", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "color topic exhausted", prefs.getString("pref_color_topic_exhausted", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "async nav mesh updater threads", prefs.getString("pref_async_nav_mesh_updater_threads", "true")!!)
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "font size", prefs.getString("pref_font_size", "true")!!)

                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "vsync", (if (prefs.getBoolean("pref_vsync", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "preload enabled", (if (prefs.getBoolean("pref_preloading", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "distant terrain", (if (prefs.getBoolean("pref_distant", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "force shaders", (if (prefs.getBoolean("pref_shaders", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "force per pixel lighting", (if (prefs.getBoolean("pref_pix_light", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "clamp lighting", (if (prefs.getBoolean("pref_clamp_lighting", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "radial fog", (if (prefs.getBoolean("pref_radfog", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "enable gyroscope", (if (prefs.getBoolean("pref_gyroscope", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "stretch menu background", (if (prefs.getBoolean("pref_stretch_menu_background", false)) "true" else "false"))     
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "color topic enable", (if (prefs.getBoolean("pref_color_topic_enable", false)) "true" else "false"))     
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "show projectile damage", (if (prefs.getBoolean("pref_show_projectile_damage", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "show melee info", (if (prefs.getBoolean("pref_show_melee_info", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "show enchant chance", (if (prefs.getBoolean("pref_show_enchant_chance", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "best attack", (if (prefs.getBoolean("pref_best_attack", false)) "true" else "false"))     
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "classic reflected absorb spells behavior", (if (prefs.getBoolean("pref_classic_reflected_absorb_spells_behavior", false)) "true" else "false"))   
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "show effect duration", (if (prefs.getBoolean("pref_show_effect_duration", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "prevent merchant equipping", (if (prefs.getBoolean("pref_prevent_merchant_equipping", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "enchanted weapons are magical", (if (prefs.getBoolean("pref_enchanted_weapons_are_magical", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "followers attack on sight", (if (prefs.getBoolean("pref_followers_attack_on_sight", false)) "true" else "false"))     
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "can loot during death animation", (if (prefs.getBoolean("pref_can_loot_during_death_animation", false)) "true" else "false")) 
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "rebalance soul gem values", (if (prefs.getBoolean("pref_rebalance_soul_gem_values", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "use additional anim sources", (if (prefs.getBoolean("pref_use_additional_anim_sources", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "barter disposition change is permanent", (if (prefs.getBoolean("pref_barter_disposition_change_is_permanent", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "weapon sheathing", (if (prefs.getBoolean("pref_weapon_sheathing", false)) "true" else "false"))     
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "shield sheathing", (if (prefs.getBoolean("pref_shield_sheathing", false)) "true" else "false")) 
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "only appropriate ammunition bypasses resistance", (if (prefs.getBoolean("pref_only_appropriate_ammunition_bypasses_resistance", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "use magic item animations", (if (prefs.getBoolean("pref_use_magic_item_animations", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "normalise race speed", (if (prefs.getBoolean("pref_normalise_race_speed", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "uncapped damage fatigue", (if (prefs.getBoolean("pref_uncapped_damage_fatigue", false)) "true" else "false"))     
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "turn to movement direction", (if (prefs.getBoolean("pref_turn_to_movement_direction", false)) "true" else "false")) 
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "smooth movement", (if (prefs.getBoolean("pref_smooth_movement", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "NPCs avoid collisions", (if (prefs.getBoolean("pref_NPCs_avoid_collisions", false)) "true" else "false"))     
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "NPCs give way", (if (prefs.getBoolean("pref_NPCs_give_way", false)) "true" else "false")) 
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "swim upward correction", (if (prefs.getBoolean("pref_swim_upward_correction", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "trainers training skills based on base skill", (if (prefs.getBoolean("pref_trainers_training_skills_based_on_base_skill", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "always allow stealing from knocked out actors", (if (prefs.getBoolean("pref_always_allow_stealing_from_knocked_out_actors", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "graphic herbalism", (if (prefs.getBoolean("pref_graphic_herbalism", false)) "true" else "false"))     
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "allow actors to follow over water surface", (if (prefs.getBoolean("pref_allow_actors_to_follow_over_water_surface", false)) "true" else "false")) 
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "shader", (if (prefs.getBoolean("pref_shader_water", false)) "true" else "false"))     
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "refraction", (if (prefs.getBoolean("pref_refraction", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "head bobbing", (if (prefs.getBoolean("pref_head_bobbing", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "view over shoulder", (if (prefs.getBoolean("pref_view_over_shoulder", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "auto switch shoulder", (if (prefs.getBoolean("pref_auto_switch_shoulder", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "preview if stand still", (if (prefs.getBoolean("pref_preview_if_stand_still", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "deferred preview rotation", (if (prefs.getBoolean("pref_deferred_preview_rotation", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "subtitles", (if (prefs.getBoolean("pref_subtitles", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "toggle sneak", (if (prefs.getBoolean("pref_toggle_sneak", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "small feature culling", (if (prefs.getBoolean("pref_small_feature_culling", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "auto use object normal maps", (if (prefs.getBoolean("pref_auto_use_pbr", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "auto use object specular maps", (if (prefs.getBoolean("pref_auto_use_pbr", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "auto use terrain normal maps", (if (prefs.getBoolean("pref_auto_use_pbr", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "auto use terrain specular maps", (if (prefs.getBoolean("pref_auto_use_pbr", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "apply lighting to environment maps", (if (prefs.getBoolean("pref_apply_lighting_to_environment_maps", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "autosave", (if (prefs.getBoolean("pref_autosave", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "timeplayed", (if (prefs.getBoolean("pref_timeplayed", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "enabled", (if (prefs.getBoolean("pref_groundcover_enable", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "preload exterior grid", (if (prefs.getBoolean("pref_preload_exterior_grid", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "preload fast travel", (if (prefs.getBoolean("pref_preload_fast_travel", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "preload doors", (if (prefs.getBoolean("pref_preload_doors", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "preload instances", (if (prefs.getBoolean("pref_preload_instances", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "object paging", (if (prefs.getBoolean("pref_object_paging", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "always run", (if (prefs.getBoolean("pref_always_run", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "load unsupported nif files", (if (prefs.getBoolean("pref_load_unsupported_nif_files", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "occlusion culling", (if (prefs.getBoolean("pref_occlusion_culling", false)) "true" else "false"))
                file.Writer.write(Constants.USER_CONFIG + "/settings.cfg", "reflection occlusion culling", (if (prefs.getBoolean("pref_reflection_occlusion_culling", false)) "true" else "false"))
                }


                configureDefaultsBin(mapOf(

                        "camera sensitivity" to "0.4"
                ))

                runOnUiThread {
                    obtainFixedScreenResolution()
                    dialog.hide()
                    runGame()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write config files.", e)
            }
        }
        th.start()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_settings, menu)
        if (!MyApp.haveBugsnagApiKey)
            menu.findItem(R.id.action_bugsnag_consent).setVisible(false)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reset_config -> {
                removeUserConfig()
                removeStaticFiles()
                Toast.makeText(this, getString(R.string.config_was_reset), Toast.LENGTH_SHORT).show()
                true
            }

            R.id.action_about -> {
                val text = assets.open("libopenmw/3rdparty-licenses.txt")
                    .bufferedReader()
                    .use { it.readText() }

                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.about_title))
                    .setMessage(text)
                    .show()

                true
            }

            R.id.action_bugsnag_consent -> {
                askBugsnagConsent()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val TAG = "OpenMW-Launcher"

        var resolutionX = 0
        var resolutionY = 0
    }
}
