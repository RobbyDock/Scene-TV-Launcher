package com.robbydock.scene

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/** HDMI inputs on this TV. Device-wide. Empty on boxes without inputs. */
object Inputs {
    class Input(val id: String, val system: String, val name: String, val hidden: Boolean)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)

    /** Shows the Inputs row on Home. */
    fun showOnHome(ctx: Context) = prefs(ctx).getBoolean("inputsOn", true)
    fun setShowOnHome(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("inputsOn", on).apply()

    private fun hiddenIds(ctx: Context) = prefs(ctx).getStringSet("inputsHidden", emptySet()).orEmpty()

    fun list(ctx: Context): List<Input> {
        val tim = ctx.getSystemService(TvInputManager::class.java) ?: return emptyList()
        val hidden = hiddenIds(ctx)
        return try {
            tim.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI && !it.isHidden(ctx) }.map { info ->
                val system = info.loadLabel(ctx).toString()
                val custom = info.loadCustomLabel(ctx)?.toString()?.takeIf { it.isNotBlank() }
                Input(info.id, system, prefs(ctx).getString("inputName:${info.id}", null) ?: custom ?: system, info.id in hidden)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setHidden(ctx: Context, id: String, hide: Boolean) {
        val s = hiddenIds(ctx).toMutableSet()
        if (hide) s.add(id) else s.remove(id)
        prefs(ctx).edit().putStringSet("inputsHidden", s).apply()
    }

    fun rename(ctx: Context, id: String, name: String?) {
        val e = prefs(ctx).edit()
        if (name.isNullOrBlank()) e.remove("inputName:$id") else e.putString("inputName:$id", name)
        e.apply()
    }

    fun switchTo(act: HomeActivity, i: Input) {
        act.start(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(i.id)), "Can't switch to ${i.name}")
    }

    /** Options for an input tile. */
    fun menu(act: HomeActivity, i: Input) {
        val a = listOf(
            Sheet.Action("Switch to ${i.system}", R.drawable.ic_input) { switchTo(act, i) },
            Sheet.Action("Rename", R.drawable.ic_edit, "Shown on Home") {
                act.push(NameEntry(act, "Rename ${i.system}", i.name, hint = "Input name", icon = R.drawable.ic_input, maxLen = Config.MAX_ROW_NAME) { v ->
                    rename(act, i.id, Guard.cleanText(v, Config.MAX_ROW_NAME)); act.home.rebuild()
                })
            },
            Sheet.Action("Hide", R.drawable.ic_visibility_off, "Turn it back on in Settings, Inputs") { setHidden(act, i.id, true); act.home.rebuild() },
        )
        act.push(Sheet(act, i.name, i.system, null, a))
    }
}
