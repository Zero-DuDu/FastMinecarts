package me.certainly1182.fastminecarts

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Rail
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.vehicle.VehicleExitEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

class FastMinecarts : JavaPlugin(), Listener {
    private val cartStates = ConcurrentHashMap<Minecart, CartState>()

    @Volatile
    private var settings = Settings()

    override fun onEnable() {
        saveDefaultConfig()
        reloadConfig()
        loadSettings()
        Bukkit.getPluginManager().registerEvents(this, this)
    }

    private fun loadSettings() {
        val blockMaxSpeeds = mutableMapOf<Material, Double>()
        val blockConfig = config.getConfigurationSection("blocks")
        if (blockConfig != null) {
            for (key in blockConfig.getKeys(false)) {
                val material = Material.matchMaterial(key)
                if (material == null || !material.isBlock) {
                    logger.warning("忽略无效方块配置 blocks.$key：不是有效方块材料")
                    continue
                }

                val speed = blockConfig.getDouble(key)
                if (!speed.isFinite() || speed <= 0.0) {
                    logger.warning("忽略无效速度配置 blocks.$key：速度必须是大于 0 的有限数值")
                    continue
                }

                blockMaxSpeeds[material] = speed
            }
        }

        settings = Settings(
            blockMaxSpeeds = blockMaxSpeeds.toMap(),
            graceTicks = readGraceTicks(),
            slopeAssistMode = readSlopeAssistMode(),
            slopeBoostPerTick = readSlopeBoost(),
            slopeMinSpeedRatio = readSlopeMinRatio(),
        )
    }

    private fun readGraceTicks(): Int {
        val ticks = config.getInt("grace-ticks", DEFAULT_GRACE_TICKS)
        if (ticks < 0) {
            logger.warning("配置 grace-ticks 不能为负数，已使用默认值 $DEFAULT_GRACE_TICKS")
            return DEFAULT_GRACE_TICKS
        }
        return ticks
    }

    private fun readSlopeAssistMode(): SlopeAssistMode {
        val raw = config.getString("slope-assist.mode", SlopeAssistMode.BOOST.configName)!!
        val mode = SlopeAssistMode.fromConfig(raw)
        if (mode == null) {
            val valid = SlopeAssistMode.entries.joinToString(", ") { it.configName }
            logger.warning("配置 slope-assist.mode 的值 '$raw' 无效，可选值为 $valid，已使用默认值 ${SlopeAssistMode.BOOST.configName}")
            return SlopeAssistMode.BOOST
        }
        return mode
    }

    private fun readSlopeBoost(): Double {
        val boost = config.getDouble("slope-assist.boost-per-tick", DEFAULT_SLOPE_BOOST)
        if (!boost.isFinite() || boost <= 0.0) {
            logger.warning("配置 slope-assist.boost-per-tick 必须是大于 0 的有限数值，已使用默认值 $DEFAULT_SLOPE_BOOST")
            return DEFAULT_SLOPE_BOOST
        }
        return boost
    }

    private fun readSlopeMinRatio(): Double {
        val ratio = config.getDouble("slope-assist.min-speed-ratio", DEFAULT_SLOPE_MIN_RATIO)
        if (!ratio.isFinite() || ratio < 0.0 || ratio > 1.0) {
            logger.warning("配置 slope-assist.min-speed-ratio 必须在 0.0 到 1.0 之间，已使用默认值 $DEFAULT_SLOPE_MIN_RATIO")
            return DEFAULT_SLOPE_MIN_RATIO
        }
        return ratio
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (args.size != 1 || !args[0].equals("reload", ignoreCase = true)) return false
        if (!sender.hasPermission(RELOAD_PERMISSION)) {
            sender.sendMessage("§c你没有权限执行此命令。")
            return true
        }

        reloadConfig()
        loadSettings()
        cartStates.values.forEach(::cancelSlopeAssist)
        cartStates.clear()
        sender.sendMessage("§aFastMinecarts 配置已重载，共载入 ${settings.blockMaxSpeeds.size} 个方块速度。")
        return true
    }

    @EventHandler
    fun onVehicleMove(event: VehicleMoveEvent) {
        val minecart = event.vehicle as? Minecart ?: return
        if (minecart.passengers.none { it is Player }) return

        val currentSettings = settings
        val railBlock = findRailBlock(event.to.block)
        val configuredSpeed = railBlock?.let { currentSettings.blockMaxSpeeds[it.getRelative(BlockFace.DOWN).type] }
        val state = cartStates.computeIfAbsent(minecart) { CartState() }
        val currentTick = Bukkit.getCurrentTick()

        if (configuredSpeed != null) {
            state.lastConfiguredSpeed = configuredSpeed
            state.lastMatchTick = currentTick
        }

        val targetSpeed = resolveTargetSpeed(state, configuredSpeed, currentTick, currentSettings.graceTicks)
        minecart.maxSpeed = targetSpeed
        state.targetSpeed = targetSpeed

        // 记录朝向：坡上停住后没有位移可参考，只能靠停住前的行进方向判断该往哪边推。
        val dx = event.to.x - event.from.x
        val dz = event.to.z - event.from.z
        if (dx * dx + dz * dz > MIN_HEADING_SQ) {
            state.headingX = dx
            state.headingZ = dz
        }

        if (currentSettings.slopeAssistMode != SlopeAssistMode.OFF && targetSpeed > VANILLA_MAX_SPEED) {
            scheduleSlopeAssist(minecart, state)
        } else {
            cancelSlopeAssist(state)
        }
    }

    /**
     * 为矿车挂载补速任务。
     *
     * 补速必须由调度任务驱动而非移动事件：矿车在坡上失速停住后不再触发
     * VehicleMoveEvent，而那恰恰是最需要补速的时刻。
     *
     * 使用实体调度器而非全局调度器，这样在 Folia 上任务会跟随矿车所在区域线程，
     * 不需要为两种服务端写不同实现。
     */
    private fun scheduleSlopeAssist(minecart: Minecart, state: CartState) {
        synchronized(state) {
            if (state.assistTask != null) return
            val task = minecart.scheduler.runAtFixedRate(
                this,
                { task ->
                    if (cartStates[minecart] !== state || !minecart.isValid || minecart.passengers.none { it is Player }) {
                        cartStates.remove(minecart, state)
                        clearSlopeAssistTask(state, task)
                        if (minecart.isValid) minecart.maxSpeed = VANILLA_MAX_SPEED
                        task.cancel()
                        return@runAtFixedRate
                    }

                    val currentSettings = settings
                    if (currentSettings.slopeAssistMode == SlopeAssistMode.OFF || state.targetSpeed <= VANILLA_MAX_SPEED) {
                        clearSlopeAssistTask(state, task)
                        task.cancel()
                        return@runAtFixedRate
                    }
                    tickSlopeAssist(minecart, state, currentSettings)
                },
                {
                    synchronized(state) { state.assistTask = null }
                    cartStates.remove(minecart, state)
                },
                1L,
                1L,
            )
            if (task == null) {
                cartStates.remove(minecart, state)
            } else {
                state.assistTask = task
            }
        }
    }

    private fun cancelSlopeAssist(state: CartState) {
        val task = synchronized(state) {
            val currentTask = state.assistTask
            state.assistTask = null
            currentTask
        }
        task?.cancel()
    }

    private fun clearSlopeAssistTask(state: CartState, task: ScheduledTask) {
        synchronized(state) {
            if (state.assistTask === task) state.assistTask = null
        }
    }

    /** 检查矿车是否正在上坡且动力不足，按配置模式补充推进力。 */
    private fun tickSlopeAssist(minecart: Minecart, state: CartState, currentSettings: Settings) {
        val railBlock = findRailBlock(minecart.location.block) ?: return
        val slopeFace = ascendingFace(railBlock) ?: return
        assistOnSlope(minecart, state, slopeFace, currentSettings)
    }

    /**
     * 沿上坡方向补充推进力。
     *
     * 关键点：必须沿坡道方向"加"推力，不能缩放现有速度向量。矿车失速后速度
     * 方向已经朝下，缩放只会让它滑得更快；速度接近零时缩放倍数还会趋于无穷。
     */
    private fun assistOnSlope(
        minecart: Minecart,
        state: CartState,
        slopeFace: BlockFace,
        currentSettings: Settings,
    ) {
        val velocity = minecart.velocity
        val targetSpeed = state.targetSpeed

        // 上坡方向的单位向量：水平沿坡道朝上，竖直分量使其呈 45 度爬升。
        val uphill = Vector(slopeFace.modX.toDouble(), 0.0, slopeFace.modZ.toDouble())
        if (uphill.lengthSquared() < SPEED_EPSILON) return
        uphill.normalize()

        // 用行进朝向消除坡道形状的方向歧义：ASCENDING_EAST 既可能是往东爬升，
        // 也可能是从东侧下坡，取决于矿车往哪边走。点积为负说明推力方向反了。
        val heading = Vector(state.headingX, 0.0, state.headingZ)
        if (heading.lengthSquared() > MIN_HEADING_SQ && uphill.dot(heading) < 0.0) return

        uphill.setY(SLOPE_CLIMB_Y)
        uphill.normalize()

        // 当前沿上坡方向的速度分量。为负表示正在往坡下滑。
        val alongSlope = velocity.dot(uphill)
        val desired = when (currentSettings.slopeAssistMode) {
            SlopeAssistMode.CONSTANT -> targetSpeed
            SlopeAssistMode.BOOST -> targetSpeed * currentSettings.slopeMinSpeedRatio
            SlopeAssistMode.OFF -> return
        }
        if (alongSlope >= desired) return

        val boost = when (currentSettings.slopeAssistMode) {
            // 恒速模式一步补齐差额，逐 Tick 维持在目标速度。
            SlopeAssistMode.CONSTANT -> desired - alongSlope
            // 补速模式每 Tick 只补一点，避免突兀的弹射感。
            SlopeAssistMode.BOOST -> currentSettings.slopeBoostPerTick.coerceAtMost(desired - alongSlope)
            SlopeAssistMode.OFF -> return
        }

        minecart.velocity = velocity.add(uphill.multiply(boost))
    }

    /**
     * 返回上坡轨道的爬升水平朝向，非斜坡轨道返回 null。
     *
     * ASCENDING_EAST 表示轨道向东侧升高，因此爬升方向就是东。
     */
    private fun ascendingFace(railBlock: Block): BlockFace? {
        val rail = railBlock.blockData as? Rail ?: return null
        return when (rail.shape) {
            Rail.Shape.ASCENDING_NORTH -> BlockFace.NORTH
            Rail.Shape.ASCENDING_SOUTH -> BlockFace.SOUTH
            Rail.Shape.ASCENDING_EAST -> BlockFace.EAST
            Rail.Shape.ASCENDING_WEST -> BlockFace.WEST
            else -> null
        }
    }

    /**
     * 决定当前应当趋近的速度上限。
     *
     * 命中配置方块时直接用配置值；未命中时进入宽限期（coyote time），
     * 在 [graceTicks] 内继续维持上一次命中的速度，避免斜坡垫坡方块、
     * 路口、单格缺料导致目标速度反复抖动。宽限期结束才回落到原版速度。
     */
    private fun resolveTargetSpeed(
        state: CartState,
        configuredSpeed: Double?,
        currentTick: Int,
        graceTicks: Int,
    ): Double {
        if (configuredSpeed != null) return configuredSpeed

        val lastMatchTick = state.lastMatchTick ?: return VANILLA_MAX_SPEED
        val elapsed = currentTick - lastMatchTick
        return if (elapsed in 0..graceTicks) state.lastConfiguredSpeed else VANILLA_MAX_SPEED
    }

    private fun findRailBlock(destinationBlock: Block): Block? {
        if (destinationBlock.type in RAIL_TYPES) return destinationBlock

        val blockBelow = destinationBlock.getRelative(BlockFace.DOWN)
        return blockBelow.takeIf { it.type in RAIL_TYPES }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVehicleExit(event: VehicleExitEvent) {
        val minecart = event.vehicle as? Minecart ?: return
        if (event.exited !is Player) return
        if (minecart.passengers.any { it is Player && it !== event.exited }) return

        val state = cartStates.remove(minecart) ?: return
        cancelSlopeAssist(state)
        minecart.maxSpeed = VANILLA_MAX_SPEED
    }

    override fun onDisable() {
        val states = cartStates.entries.toList()
        states.forEach { (_, state) -> cancelSlopeAssist(state) }
        cartStates.clear()
        for ((minecart) in states) {
            if (Bukkit.isOwnedByCurrentRegion(minecart)) minecart.maxSpeed = VANILLA_MAX_SPEED
        }
    }

    /** 上坡辅助模式。 */
    private enum class SlopeAssistMode(val configName: String) {
        /** 不介入，爬坡完全交给原版物理。 */
        OFF("off"),

        /** 逐 Tick 补一点推进力，速度不低于目标速度的一定比例。手感接近原版。 */
        BOOST("boost"),

        /** 坡上直接维持目标速度。手感像高速列车。 */
        CONSTANT("constant"),
        ;

        companion object {
            fun fromConfig(raw: String): SlopeAssistMode? =
                entries.firstOrNull { it.configName.equals(raw.trim(), ignoreCase = true) }
        }
    }

    private data class Settings(
        val blockMaxSpeeds: Map<Material, Double> = emptyMap(),
        val graceTicks: Int = DEFAULT_GRACE_TICKS,
        val slopeAssistMode: SlopeAssistMode = SlopeAssistMode.BOOST,
        val slopeBoostPerTick: Double = DEFAULT_SLOPE_BOOST,
        val slopeMinSpeedRatio: Double = DEFAULT_SLOPE_MIN_RATIO,
    )

    private class CartState {
        /** 上一次命中配置方块时的目标速度，用于宽限期内维持原速度上限。 */
        var lastConfiguredSpeed: Double = VANILLA_MAX_SPEED

        /** 上一次命中配置方块的服务器 tick，null 表示从未命中。 */
        var lastMatchTick: Int? = null

        /** 当前生效的目标速度，供补速任务读取。 */
        @Volatile
        var targetSpeed: Double = VANILLA_MAX_SPEED

        /** 最近一次有效位移的水平朝向，用于判断坡道该往哪边推。 */
        var headingX: Double = 0.0
        var headingZ: Double = 0.0

        /** 当前补速任务；null 表示后续进入高速路段时可重新创建。 */
        var assistTask: ScheduledTask? = null
    }

    private companion object {
        const val VANILLA_MAX_SPEED = 0.4
        const val DEFAULT_GRACE_TICKS = 40
        const val DEFAULT_SLOPE_BOOST = 0.04
        const val DEFAULT_SLOPE_MIN_RATIO = 0.85
        const val SPEED_EPSILON = 1.0E-9

        /** 斜坡轨道呈 45 度，上坡方向的竖直分量与水平分量等大。 */
        const val SLOPE_CLIMB_Y = 1.0

        /** 判定位移是否足以代表行进朝向的平方阈值。 */
        const val MIN_HEADING_SQ = 1.0E-8
        const val RELOAD_PERMISSION = "fastminecarts.reload"
        val RAIL_TYPES: Set<Material> = EnumSet.of(
            Material.RAIL,
            Material.POWERED_RAIL,
            Material.DETECTOR_RAIL,
            Material.ACTIVATOR_RAIL,
        )
    }
}
