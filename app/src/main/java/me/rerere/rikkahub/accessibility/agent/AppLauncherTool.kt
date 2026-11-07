// Adapted from ExTV/rikkahub-agent, a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc.
// SPDX-License-Identifier: AGPL-3.0-only
// Source and adaptation notes: docs/phone-control-sources.md
package me.rerere.rikkahub.accessibility.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.accessibility.PhoneAccessibilityService

private suspend fun waitForForegroundPackage(
    expectedPkg: String,
    timeoutMs: Long = 2500,
    stepMs: Long = 150,
): String? {
    val deadline = System.currentTimeMillis() + timeoutMs
    var current: String? = null
    while (System.currentTimeMillis() < deadline) {
        val svc = PhoneAccessibilityService.instance ?: return null
        current = svc.rootInActiveWindow?.packageName?.toString()
        if (current == expectedPkg) return current
        delay(stepMs)
    }
    return current
}

/**
 * Launches an installed app by package name. Useful when the LLM needs to bring a specific
 * app to the foreground before performing screen automation gestures (taps/swipes), or when
 * the user explicitly asks to "open Termux" / "open Settings" / etc.
 *
 * The companion list_installed_apps tool exposes available package names so the model does
 * not have to guess.
 */
fun launchAppTool(
    context: Context,
): Tool = Tool(
    name = "launch_app",
    description = """
        Open an installed app on the device by its package name (e.g. com.termux, com.android.settings).
        Returns {success: true} if the launch intent was dispatched. If you do not know the package name,
        first call list_installed_apps to discover available packages. The app is brought to the
        foreground; if the screen automation tools are enabled, they can then drive its UI
        (tap, swipe, read_window_tree).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Application package id, e.g. com.termux")
                })
            },
            required = listOf("package_name")
        )
    },
    execute = { input ->
        val pkg = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
        if (pkg.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "package_name is required") }.toString()
                )
            )
        }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "no_launch_intent")
                        put("package", pkg)
                        put("recovery", "Package may not be installed or has no launcher activity. Call list_installed_apps to verify.")
                    }.toString()
                )
            )
        }
        // Wake the screen if it's off; without this the activity launches behind the lock
        // screen and any subsequent read_window_tree call will see no_active_window. We do
        // not bypass a real PIN/biometric keyguard - that requires the user.

        val keyLocked = (context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
        val keySecure = (context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardSecure

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result = try {
            context.startActivity(intent)
            // Confirm the launched app actually took focus. If the user is physically
            // in another app (e.g. RikkaPlus's own chat), the launch can be silently
            // ignored and subsequent screen-automation calls will loop on
            // wrong_foreground_app. Only meaningful when AccessibilityService is bound;
            // when unbound we cannot verify and report confirmed_foreground:false.
            val accessibilityRunning = PhoneAccessibilityService.instance != null
            val finalForeground: String? = if (accessibilityRunning && !keyLocked) {
                waitForForegroundPackage(pkg)
            } else null
            val confirmed = finalForeground == pkg
            if (accessibilityRunning && !keyLocked && !confirmed) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "launch_did_not_focus")
                            put("requested", pkg)
                            put("current_foreground", finalForeground.orEmpty())
                            put(
                                "recovery",
                                "The launch intent was dispatched but the OS did not move ${pkg} to the foreground within 2.5s. The user is likely actively viewing another app (often RikkaPlus itself) — If the screen automation tools are enabled: do NOT pass package_name to read_window_tree on this turn. Either ask the user to switch to ${pkg}, or call read_window_tree with no package_name guard so you can see whatever IS currently on screen; otherwise ask the user to switch to the app manually."
                            )

                        }.toString()
                    )
                )
            } else {


                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("package", pkg)
                            put("confirmed_foreground", confirmed)
                            if (!accessibilityRunning) {
                                put("note", "AccessibilityService not bound — could not verify foreground. Treat success as best-effort.")
                            }

                            if (keyLocked) {
                                put("keyguard_locked", true)
                                put("keyguard_secure", keySecure)
                                if (keySecure) {
                                    put("warn", "Screen is woken but PIN/biometric keyguard is up. The user must unlock for the launched app to be visible and drivable.")
                                }
                            }
                            PhoneAccessibilityService.instance?.let { svc ->
                                put("after", screenStateJson(svc, screenChanged = null))
                            }
                        }.toString()
                    )
                )
            }
        } catch (t: Throwable) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "launch_failed")
                        put("reason", t.message ?: t::class.java.simpleName)
                    }.toString()
                )
            )
        }

        result
    }
)

fun listInstalledAppsTool(context: Context): Tool = Tool(
    name = "list_installed_apps",
    description = "List installed apps as {label, package, version, has_launcher}. Default: launcher-visible only. With `filter` or `include_no_launcher=true`, also returns service-only addons (Termux:API, Termux:Boot, etc.) that have no app-drawer entry. Optional `include_permissions` adds each app's requested permissions.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("filter", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional case-insensitive substring to match against label or package. Setting this auto-includes addon packages (no launcher activity).")
                })
                put("user_only", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true (default), only apps installed by the user; false includes system apps")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Cap on the number of returned apps (default 200)")
                })
                put("include_no_launcher", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, also returns packages that have no launcher activity (service-only addons). Auto-true when filter is non-empty.")
                })
                put("include_permissions", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, each row also lists the permissions the app requests. Output grows a lot; combine with `filter` or a small `limit`.")
                })
            }
        )
    },
    execute = { input ->
        val filter = input.jsonObject["filter"]?.jsonPrimitive?.contentOrNull
            ?.lowercase()?.takeIf { it.isNotBlank() }
        val userOnly = input.jsonObject["user_only"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: true
        val limit = input.jsonObject["limit"]?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull()?.coerceIn(1, 1000) ?: 200
        val includeNoLauncher = (input.jsonObject["include_no_launcher"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false) || filter != null
        val includePermissions = input.jsonObject["include_permissions"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false

        val pm = context.packageManager

        // Set of packages that DO have a launcher activity. Used to set has_launcher on each
        // row regardless of whether we walked them via the launcher query or the full
        // package list.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val launcherResolved = pm.queryIntentActivities(launcherIntent, 0)
        val launcherPkgs = launcherResolved.mapNotNull { it.activityInfo?.packageName }.toHashSet()

        data class Row(
            val label: String,
            val pkg: String,
            val hasLauncher: Boolean,
            val version: String,
            val permissions: List<String>?,
        )

        fun versionOf(pkg: String): String =
            runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: ""

        fun permissionsOf(pkg: String): List<String>? =
            if (!includePermissions) null else runCatching {
                pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
            }.getOrNull() ?: emptyList()

        val rows = mutableListOf<Row>()
        val seen = mutableSetOf<String>()

        // 1) Walk launcher apps first so we get clean labels via ResolveInfo.loadLabel.
        for (info in launcherResolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg in seen) continue
            seen.add(pkg)
            if (userOnly) {
                try {
                    val flags = pm.getApplicationInfo(pkg, 0).flags
                    val isSystem = (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val isUpdated = (flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (isSystem && !isUpdated) continue
                } catch (_: PackageManager.NameNotFoundException) {
                    continue
                }
            }
            val label = info.loadLabel(pm).toString()
            if (filter != null && !label.lowercase().contains(filter) && !pkg.lowercase().contains(filter)) {
                continue
            }
            rows.add(Row(label, pkg, hasLauncher = true, version = versionOf(pkg), permissions = permissionsOf(pkg)))
            if (rows.size >= limit) break
        }

        // 2) If asked, also walk the full installed-package list and pick up addons without
        //    a launcher activity. Termux:API (com.termux.api), Termux:Boot, dialer addons,
        //    etc. only show up here.
        if (rows.size < limit && includeNoLauncher) {
            @Suppress("DEPRECATION")
            val all = try { pm.getInstalledPackages(0) } catch (_: Throwable) { emptyList() }
            for (pkgInfo in all) {
                val pkg = pkgInfo.packageName
                if (pkg in seen) continue
                seen.add(pkg)
                val appInfo = pkgInfo.applicationInfo ?: continue
                if (userOnly) {
                    val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val isUpdated = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (isSystem && !isUpdated) continue
                }
                val label = appInfo.loadLabel(pm).toString()
                if (filter != null && !label.lowercase().contains(filter) && !pkg.lowercase().contains(filter)) {
                    continue
                }
                rows.add(
                    Row(
                        label,
                        pkg,
                        hasLauncher = pkg in launcherPkgs,
                        version = versionOf(pkg),
                        permissions = permissionsOf(pkg),
                    )
                )
                if (rows.size >= limit) break
            }
        }

        rows.sortWith(compareBy({ it.label.lowercase() }, { it.pkg }))

        val payload = buildJsonObject {
            put("count", rows.size)
            put("apps", buildJsonArray {
                rows.forEach { row ->
                    addJsonObject {
                        put("label", row.label)
                        put("package", row.pkg)
                        put("version", row.version)
                        put("has_launcher", row.hasLauncher)
                        if (includePermissions) {
                            put("permissions", buildJsonArray {
                                row.permissions?.forEach { add(it) }
                            })
                        }
                    }
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
