package com.chattriggers.ctjs.utils.console

import com.chattriggers.ctjs.engine.langs.Lang
import com.chattriggers.ctjs.engine.langs.js.JSLoader
import com.chattriggers.ctjs.utils.Config
import com.chattriggers.ctjs.utils.Initializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW

object ConsoleManager : Initializer {
    private val consoles = mutableMapOf<Lang?, Console>()
    private val keybindings = mutableMapOf<Lang?, KeyBinding>()

    init {
        consoles[null] = ElementaConsole(null)
        consoles[Lang.JS] = ElementaConsole(JSLoader)
    }

    override fun init() {
        keybindings[null] = KeyBindingHelper.registerKeyBinding(KeyBinding(
            "chattriggers.key.binding.console.general",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "chattriggers.key.category.console",
        ))

        keybindings[Lang.JS] = KeyBindingHelper.registerKeyBinding(KeyBinding(
            "chattriggers.key.binding.console.js",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_GRAVE_ACCENT,
            "chattriggers.key.category.console",
        ))

        ClientTickEvents.END_CLIENT_TICK.register {
            for ((lang, binding) in keybindings) {
                if (binding.wasPressed())
                    consoles[lang]!!.show()
            }
        }
    }

    fun onConsoleSettingsChanged() {
        consoles.values.forEach { it.onConsoleSettingsChanged() }
    }

    fun installConsole(lang: Lang?, console: Console) {
        consoles[lang] = console
    }

    fun getConsole(lang: Lang? = null) = consoles[lang]!!

    fun clearConsoles() {
        if (Config.clearConsoleOnLoad)
            consoles.values.forEach(Console::clear)
    }
}
