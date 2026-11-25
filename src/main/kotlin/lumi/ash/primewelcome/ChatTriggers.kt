package lumi.ash.primewelcome

import com.google.gson.GsonBuilder
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

enum class MatchMode { CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, REGEX }

data class Trigger(
    var name: String = "",
    var mode: MatchMode = MatchMode.CONTAINS,
    var match: String = "",
    var response: String = "",
    var minDelayTicks: Int = 0,
    var maxDelayTicks: Int = 0,
    var cooldownMs: Long = 3000L,
    var enabled: Boolean = true,
    var ignoreCase: Boolean = true
)

data class PrimeWelcomeConfig(
    var enabled: Boolean = true,
    var triggers: MutableList<Trigger> = mutableListOf()
)

object ChatTriggers {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = FabricLoader.getInstance().configDir.resolve("primewelcome.json")
    private val lastFired = HashMap<String, Long>()
    private val pending = CopyOnWriteArrayList<Pending>()

    private class Pending(var ticks: Int, val text: String)

    @Volatile
    var config = PrimeWelcomeConfig()
        private set

    private val helpLines = listOf(
        "/primewelcome help - show this list",
        "/primewelcome add <name> <mode> <trigger> => <response> - create a trigger",
        "    modes: contains, equals, starts_with, ends_with, regex",
        "    example: /primewelcome add greet contains hello there => Hey!",
        "/primewelcome remove <name> - delete a trigger",
        "/primewelcome toggle <name> - enable or disable a trigger",
        "/primewelcome delay <name> <ticks> - fixed delay before responding",
        "/primewelcome delay <name> <min> <max> - random delay range in ticks",
        "/primewelcome cooldown <name> <ms> - minimum time between firings",
        "/primewelcome case <name> <true|false> - ignore case when matching",
        "/primewelcome enabled <true|false> - master on/off switch",
        "/primewelcome test <name> [message] - simulate a trigger (message required for regex)",
        "/primewelcome list - show all triggers",
        "/primewelcome reload - reload the config file",
        "Placeholders: {message} for the full message, {0} {1} ... for regex groups",
        "Responses starting with / are sent as commands"
    )

    fun init() {
        load()

        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay) handle(message.string)
        }

        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            handle(message.string)
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (pending.isEmpty()) return@register
            val due = ArrayList<Pending>()
            for (p in pending) {
                p.ticks--
                if (p.ticks <= 0) due.add(p)
            }
            pending.removeAll(due.toSet())
            due.forEach { send(it.text) }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val names = SuggestionProvider<FabricClientCommandSource> { _, b ->
                SharedSuggestionProvider.suggest(config.triggers.map { it.name }, b)
            }
            val modes = SuggestionProvider<FabricClientCommandSource> { _, b ->
                SharedSuggestionProvider.suggest(MatchMode.entries.map { it.name.lowercase() }, b)
            }

            dispatcher.register(
                ClientCommands.literal("primewelcome")
                    .executes { help(it) }
                    .then(ClientCommands.literal("help").executes { help(it) })
                    .then(
                        ClientCommands.literal("add")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.word())
                                    .then(
                                        ClientCommands.argument("mode", StringArgumentType.word())
                                            .suggests(modes)
                                            .then(
                                                ClientCommands.argument("rest", StringArgumentType.greedyString())
                                                    .executes { add(it) }
                                            )
                                    )
                            )
                    )
                    .then(
                        ClientCommands.literal("remove")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.word())
                                    .suggests(names)
                                    .executes { remove(it) }
                            )
                    )
                    .then(
                        ClientCommands.literal("toggle")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.word())
                                    .suggests(names)
                                    .executes { toggle(it) }
                            )
                    )
                    .then(
                        ClientCommands.literal("delay")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.word())
                                    .suggests(names)
                                    .then(
                                        ClientCommands.argument("min", IntegerArgumentType.integer(0, 12000))
                                            .executes { delayFixed(it) }
                                            .then(
                                                ClientCommands.argument("max", IntegerArgumentType.integer(0, 12000))
                                                    .executes { delayRange(it) }
                                            )
                                    )
                            )
                    )
                    .then(
                        ClientCommands.literal("cooldown")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.word())
                                    .suggests(names)
                                    .then(
                                        ClientCommands.argument("ms", IntegerArgumentType.integer(0, 3600000))
                                            .executes { cooldown(it) }
                                    )
                            )
                    )
                    .then(
                        ClientCommands.literal("case")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.word())
                                    .suggests(names)
                                    .then(
                                        ClientCommands.argument("ignore", BoolArgumentType.bool())
                                            .executes { caseSensitivity(it) }
                                    )
                            )
                    )
                    .then(
                        ClientCommands.literal("enabled")
                            .then(
                                ClientCommands.argument("value", BoolArgumentType.bool())
                                    .executes { globalEnabled(it) }
                            )
                    )
                    .then(
                        ClientCommands.literal("test")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.word())
                                    .suggests(names)
                                    .executes { test(it, null) }
                                    .then(
                                        ClientCommands.argument("message", StringArgumentType.greedyString())
                                            .executes { test(it, StringArgumentType.getString(it, "message")) }
                                    )
                            )
                    )
                    .then(ClientCommands.literal("list").executes { list(it) })
                    .then(ClientCommands.literal("reload").executes { reload(it) })
            )
        }
    }

    fun load() {
        config = try {
            if (Files.exists(path)) {
                Files.newBufferedReader(path).use { gson.fromJson(it, PrimeWelcomeConfig::class.java) }
                    ?: PrimeWelcomeConfig()
            } else {
                PrimeWelcomeConfig().also { save(it) }
            }
        } catch (e: Exception) {
            PrimeWelcomeConfig()
        }
    }

    fun save(cfg: PrimeWelcomeConfig = config) {
        try {
            Files.createDirectories(path.parent)
            Files.newBufferedWriter(path).use { gson.toJson(cfg, it) }
        } catch (_: Exception) {
        }
    }

    private fun handle(raw: String) {
        val cfg = config
        if (!cfg.enabled) return
        val now = System.currentTimeMillis()

        for (t in cfg.triggers) {
            if (!t.enabled) continue
            val groups = matches(t, raw) ?: continue

            val last = lastFired[t.name] ?: 0L
            if (now - last < t.cooldownMs) continue
            lastFired[t.name] = now

            fire(t, raw, groups)
        }
    }

    private fun fire(t: Trigger, raw: String, groups: List<String>) {
        val text = fill(t.response, raw, groups)
        val low = minOf(t.minDelayTicks, t.maxDelayTicks)
        val high = maxOf(t.minDelayTicks, t.maxDelayTicks)
        val delay = if (high <= 0) 0 else Random.nextInt(low, high + 1)
        if (delay > 0) pending.add(Pending(delay, text)) else send(text)
    }

    private fun matches(t: Trigger, raw: String): List<String>? {
        return try {
            when (t.mode) {
                MatchMode.CONTAINS -> if (raw.contains(t.match, t.ignoreCase)) listOf(raw) else null
                MatchMode.EQUALS -> if (raw.equals(t.match, t.ignoreCase)) listOf(raw) else null
                MatchMode.STARTS_WITH -> if (raw.startsWith(t.match, t.ignoreCase)) listOf(raw) else null
                MatchMode.ENDS_WITH -> if (raw.endsWith(t.match, t.ignoreCase)) listOf(raw) else null
                MatchMode.REGEX -> {
                    val options = if (t.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                    Regex(t.match, options).find(raw)?.groupValues
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fill(template: String, raw: String, groups: List<String>): String {
        var out = template.replace("{message}", raw)
        groups.forEachIndexed { i, g -> out = out.replace("{$i}", g) }
        return out
    }

    private fun send(text: String) {
        val mc = Minecraft.getInstance()
        mc.execute {
            val connection = mc.player?.connection ?: return@execute
            if (text.startsWith("/")) connection.sendCommand(text.substring(1)) else connection.sendChat(text)
        }
    }

    private fun reply(ctx: CommandContext<FabricClientCommandSource>, msg: String) {
        ctx.source.sendFeedback(Component.literal(msg))
    }

    private fun find(name: String): Trigger? = config.triggers.firstOrNull { it.name.equals(name, true) }

    private fun help(ctx: CommandContext<FabricClientCommandSource>): Int {
        helpLines.forEach { reply(ctx, it) }
        return helpLines.size
    }

    private fun add(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val modeText = StringArgumentType.getString(ctx, "mode")
        val rest = StringArgumentType.getString(ctx, "rest")

        val mode = MatchMode.entries.firstOrNull { it.name.equals(modeText, true) }
        if (mode == null) {
            reply(ctx, "Invalid mode. Use: " + MatchMode.entries.joinToString { it.name.lowercase() })
            return 0
        }

        val split = rest.indexOf(" => ")
        if (split < 0) {
            reply(ctx, "Separate the trigger and response with ' => '")
            return 0
        }
        val trigger = rest.substring(0, split).trim()
        val response = rest.substring(split + 4).trim()
        if (trigger.isEmpty() || response.isEmpty()) {
            reply(ctx, "Both the trigger and the response are required")
            return 0
        }

        if (find(name) != null) {
            reply(ctx, "A trigger named $name already exists")
            return 0
        }
        if (mode == MatchMode.REGEX) {
            try {
                Regex(trigger)
            } catch (e: Exception) {
                reply(ctx, "Invalid regex")
                return 0
            }
        }

        config.triggers.add(Trigger(name = name, mode = mode, match = trigger, response = response))
        save()
        reply(ctx, "Added trigger $name")
        return 1
    }

    private fun remove(ctx: CommandContext<FabricClientCommandSource>): Int {
        val t = find(StringArgumentType.getString(ctx, "name"))
        if (t == null) {
            reply(ctx, "Trigger not found")
            return 0
        }
        config.triggers.remove(t)
        lastFired.remove(t.name)
        save()
        reply(ctx, "Removed trigger ${t.name}")
        return 1
    }

    private fun toggle(ctx: CommandContext<FabricClientCommandSource>): Int {
        val t = find(StringArgumentType.getString(ctx, "name"))
        if (t == null) {
            reply(ctx, "Trigger not found")
            return 0
        }
        t.enabled = !t.enabled
        save()
        reply(ctx, "Trigger ${t.name} is now " + if (t.enabled) "enabled" else "disabled")
        return 1
    }

    private fun delayFixed(ctx: CommandContext<FabricClientCommandSource>): Int {
        val t = find(StringArgumentType.getString(ctx, "name"))
        if (t == null) {
            reply(ctx, "Trigger not found")
            return 0
        }
        val value = IntegerArgumentType.getInteger(ctx, "min")
        t.minDelayTicks = value
        t.maxDelayTicks = value
        save()
        reply(ctx, "Delay for ${t.name} set to $value ticks")
        return 1
    }

    private fun delayRange(ctx: CommandContext<FabricClientCommandSource>): Int {
        val t = find(StringArgumentType.getString(ctx, "name"))
        if (t == null) {
            reply(ctx, "Trigger not found")
            return 0
        }
        val min = IntegerArgumentType.getInteger(ctx, "min")
        val max = IntegerArgumentType.getInteger(ctx, "max")
        if (min > max) {
            reply(ctx, "Min delay can't be larger than max delay")
            return 0
        }
        t.minDelayTicks = min
        t.maxDelayTicks = max
        save()
        reply(ctx, "Delay for ${t.name} set to $min-$max ticks")
        return 1
    }

    private fun cooldown(ctx: CommandContext<FabricClientCommandSource>): Int {
        val t = find(StringArgumentType.getString(ctx, "name"))
        if (t == null) {
            reply(ctx, "Trigger not found")
            return 0
        }
        t.cooldownMs = IntegerArgumentType.getInteger(ctx, "ms").toLong()
        save()
        reply(ctx, "Cooldown for ${t.name} set to ${t.cooldownMs} ms")
        return 1
    }

    private fun caseSensitivity(ctx: CommandContext<FabricClientCommandSource>): Int {
        val t = find(StringArgumentType.getString(ctx, "name"))
        if (t == null) {
            reply(ctx, "Trigger not found")
            return 0
        }
        t.ignoreCase = BoolArgumentType.getBool(ctx, "ignore")
        save()
        reply(ctx, "Ignore case for ${t.name} set to ${t.ignoreCase}")
        return 1
    }

    private fun globalEnabled(ctx: CommandContext<FabricClientCommandSource>): Int {
        config.enabled = BoolArgumentType.getBool(ctx, "value")
        save()
        reply(ctx, "PrimeWelcome " + if (config.enabled) "enabled" else "disabled")
        return 1
    }

    private fun test(ctx: CommandContext<FabricClientCommandSource>, custom: String?): Int {
        val t = find(StringArgumentType.getString(ctx, "name"))
        if (t == null) {
            reply(ctx, "Trigger not found")
            return 0
        }

        val sample = custom ?: if (t.mode == MatchMode.REGEX) null else t.match
        if (sample == null) {
            reply(ctx, "Regex triggers need a sample message: /primewelcome test ${t.name} <message>")
            return 0
        }

        val groups = matches(t, sample)
        if (groups == null) {
            reply(ctx, "That message doesn't match trigger ${t.name}")
            return 0
        }

        reply(ctx, "[Test] $sample")
        fire(t, sample, groups)
        return 1
    }

    private fun list(ctx: CommandContext<FabricClientCommandSource>): Int {
        if (config.triggers.isEmpty()) {
            reply(ctx, "No triggers configured")
            return 1
        }
        config.triggers.forEach {
            val state = if (it.enabled) "on" else "off"
            val delay = if (it.minDelayTicks == it.maxDelayTicks) "${it.minDelayTicks}t" else "${it.minDelayTicks}-${it.maxDelayTicks}t"
            reply(ctx, "[$state] ${it.name} (${it.mode.name.lowercase()}) ${it.match} => ${it.response} | delay $delay, cooldown ${it.cooldownMs}ms")
        }
        return config.triggers.size
    }

    private fun reload(ctx: CommandContext<FabricClientCommandSource>): Int {
        load()
        reply(ctx, "Config reloaded")
        return 1
    }
}