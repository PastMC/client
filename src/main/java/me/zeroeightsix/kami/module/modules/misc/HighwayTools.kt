package me.zeroeightsix.kami.module.modules.misc

import baritone.api.BaritoneAPI
import me.zeroeightsix.kami.event.events.RenderWorldEvent
import me.zeroeightsix.kami.event.events.SafeTickEvent
import me.zeroeightsix.kami.module.Module
import me.zeroeightsix.kami.module.modules.combat.Surround
import me.zeroeightsix.kami.module.modules.player.NoBreakAnimation
import me.zeroeightsix.kami.process.HighwayToolsProcess
import me.zeroeightsix.kami.setting.Settings
import me.zeroeightsix.kami.util.BaritoneUtils
import me.zeroeightsix.kami.util.BlockUtils
import me.zeroeightsix.kami.util.InventoryUtils
import me.zeroeightsix.kami.util.color.ColorHolder
import me.zeroeightsix.kami.util.combat.SurroundUtils
import me.zeroeightsix.kami.util.event.listener
import me.zeroeightsix.kami.util.graphics.ESPRenderer
import me.zeroeightsix.kami.util.graphics.GeometryMasks
import me.zeroeightsix.kami.util.math.CoordinateConverter.asString
import me.zeroeightsix.kami.util.math.MathUtils.Cardinal
import me.zeroeightsix.kami.util.math.MathUtils.getPlayerCardinal
import me.zeroeightsix.kami.util.math.RotationUtils
import me.zeroeightsix.kami.util.math.VectorUtils
import me.zeroeightsix.kami.util.math.VectorUtils.getDistance
import me.zeroeightsix.kami.util.math.VectorUtils.toBlockPos
import me.zeroeightsix.kami.util.math.VectorUtils.toVec3d
import me.zeroeightsix.kami.util.text.MessageSendHelper.sendChatMessage
import net.minecraft.block.Block
import net.minecraft.block.Block.getIdFromBlock
import net.minecraft.block.BlockLiquid
import net.minecraft.client.audio.PositionedSoundRecord
import net.minecraft.init.Blocks
import net.minecraft.init.SoundEvents
import net.minecraft.network.play.client.*
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.RayTraceResult
import net.minecraft.util.math.Vec3d
import java.util.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * @author Avanatiker
 * @since 20/08/2020
 */

@Module.Info(
        name = "HighwayTools",
        description = "Be the grief a step a head.",
        category = Module.Category.MISC,
        modulePriority = 10
)
object HighwayTools : Module() {

    private val mode = register(Settings.e<Mode>("Mode", Mode.HIGHWAY))
    private val page = register(Settings.e<Page>("Page", Page.BUILD))

    // build settings
    val clearSpace = register(Settings.booleanBuilder("ClearSpace").withValue(true).withVisibility { page.value == Page.BUILD })
    var clearHeight = register(Settings.integerBuilder("ClearHeight").withMinimum(1).withValue(4).withMaximum(6).withVisibility { page.value == Page.BUILD && clearSpace.value })
    private var buildWidth = register(Settings.integerBuilder("BuildWidth").withMinimum(1).withValue(5).withMaximum(9).withVisibility { page.value == Page.BUILD })
    private val railing = register(Settings.booleanBuilder("Railing").withValue(true).withVisibility { page.value == Page.BUILD })
    private var railingHeight = register(Settings.integerBuilder("RailingHeight").withMinimum(0).withValue(1).withMaximum(clearHeight.value).withVisibility { railing.value && page.value == Page.BUILD })
    private val cornerBlock = register(Settings.booleanBuilder("CornerBlock").withValue(false).withVisibility { page.value == Page.BUILD })

    // behavior settings
    val baritoneMode = register(Settings.booleanBuilder("AutoMode").withValue(true).withVisibility { page.value == Page.BEHAVIOR })
    private val blocksPerTick = register(Settings.integerBuilder("BlocksPerTick").withMinimum(1).withValue(1).withMaximum(10).withVisibility { page.value == Page.BEHAVIOR })
    private val tickDelayPlace = register(Settings.integerBuilder("TickDelayPlace").withMinimum(0).withValue(3).withMaximum(16).withVisibility { page.value == Page.BEHAVIOR })
    private val tickDelayBreak = register(Settings.integerBuilder("TickDelayBreak").withMinimum(0).withValue(0).withMaximum(16).withVisibility { page.value == Page.BEHAVIOR })
    private val interacting = register(Settings.enumBuilder(InteractMode::class.java).withName("InteractMode").withValue(InteractMode.SPOOF).withVisibility { page.value == Page.BEHAVIOR })
    private val autoCenter = register(Settings.enumBuilder(AutoCenterMode::class.java).withName("AutoCenter").withValue(AutoCenterMode.MOTION).withVisibility { page.value == Page.BEHAVIOR })
    private val stuckDelay = register(Settings.integerBuilder("TickDelayStuck").withMinimum(1).withValue(200).withMaximum(500).withVisibility { page.value == Page.BEHAVIOR })
    val maxReach = register(Settings.floatBuilder("MaxReach").withMinimum(2.0F).withValue(5.4F).withVisibility { page.value == Page.BEHAVIOR })

    // config
    private val info = register(Settings.booleanBuilder("ShowInfo").withValue(true).withVisibility { page.value == Page.CONFIG })
    private val printDebug = register(Settings.booleanBuilder("ShowQueue").withValue(false).withVisibility { page.value == Page.CONFIG })
    private val debugMessages = register(Settings.enumBuilder(DebugMessages::class.java).withName("Debug").withValue(DebugMessages.IMPORTANT).withVisibility { page.value == Page.CONFIG })
    private val goalRender = register(Settings.booleanBuilder("GoalRender").withValue(false).withVisibility { page.value == Page.CONFIG })
    private val filled = register(Settings.booleanBuilder("Filled").withValue(true).withVisibility { page.value == Page.CONFIG })
    private val outline = register(Settings.booleanBuilder("Outline").withValue(true).withVisibility { page.value == Page.CONFIG })
    private val aFilled = register(Settings.integerBuilder("FilledAlpha").withMinimum(0).withValue(26).withMaximum(255).withVisibility { filled.value && page.value == Page.CONFIG })
    private val aOutline = register(Settings.integerBuilder("OutlineAlpha").withMinimum(0).withValue(91).withMaximum(255).withVisibility { outline.value && page.value == Page.CONFIG })

    // internal settings
    val ignoreBlocks = mutableListOf(Blocks.STANDING_SIGN, Blocks.WALL_SIGN, Blocks.STANDING_BANNER, Blocks.WALL_BANNER, Blocks.BEDROCK, Blocks.PORTAL)
    var material: Block = Blocks.OBSIDIAN
    var fillerMat: Block = Blocks.NETHERRACK
    private var playerHotbarSlot = -1
    private var lastHotbarSlot = -1
    private lateinit var buildDirectionSaved: Cardinal
    private var baritoneSettingAllowPlace = false
    private var baritoneSettingRenderGoal = false

    // runtime vars
    val blockQueue: PriorityQueue<BlockTask> = PriorityQueue(compareBy { it.taskState.ordinal })
    private val doneQueue: Queue<BlockTask> = LinkedList()
    private var blockOffsets = mutableListOf<Pair<BlockPos, Block>>()
    private var waitTicks = 0
    private var blocksPlaced = 0
    var pathing = false
    private var stuckBuilding = 0
    private var stuckMining = 0
    private var currentBlockPos = BlockPos(0, 0, 0)
    private lateinit var startingBlockPos: BlockPos
    private var lastViewVec: RayTraceResult? = null
    private var ticks = true

    // stats
    private var totalBlocksPlaced = 0
    private var totalBlocksDestroyed = 0

    init {
        listener<SafeTickEvent> {
            if (ticks) {
                ticks = !ticks
            } else {
                ticks = !ticks
                if (mc.playerController == null) return@listener
                BaritoneAPI.getProvider().primaryBaritone.pathingControlManager.registerProcess(HighwayToolsProcess)

                if (baritoneMode.value) {
                    pathing = BaritoneAPI.getProvider().primaryBaritone.pathingBehavior.isPathing

                    if (relativeDirection(currentBlockPos, 1, 0) == mc.player.positionVector.toBlockPos()) {
                        if (!isDone()) {
                            centerPlayer()
                            if (!doTask()) {
                                if (!pathing && !BaritoneUtils.paused && !AutoObsidian.isActive()) {
                                    stuckBuilding += 1
                                    shuffleTasks()
                                    if (debugMessages.value == DebugMessages.ALL) sendChatMessage("$chatName Shuffled tasks (${stuckBuilding}x)")
                                    if (stuckBuilding > blockOffsets.size) {
                                        refreshData()
                                        if (debugMessages.value == DebugMessages.IMPORTANT) sendChatMessage("$chatName You got stuck, retry")
                                    }
                                } else {
                                    refreshData()
                                }
                            } else {
                                stuckBuilding = 0
                            }
                        } else {
                            refreshData()
                            if (checkTasks() && !pathing) {
                                currentBlockPos = getNextBlock()
                                doneQueue.clear()
                                updateTasks()
                            }
                        }
                    }
                } else {
                    if (currentBlockPos == mc.player.positionVector.toBlockPos()) {
                        if (!doTask()) {
                            shuffleTasks()
                        }
                    } else {
                        currentBlockPos = mc.player.positionVector.toBlockPos()
                        if (abs((buildDirectionSaved.ordinal - getPlayerCardinal(mc.player).ordinal) % 8) == 4) buildDirectionSaved = getPlayerCardinal(mc.player)
                        refreshData()
                    }
                }
            }
        }

        listener<RenderWorldEvent> {
            if (mc.player == null) return@listener
            val renderer = ESPRenderer()
            renderer.aFilled = if (filled.value) aFilled.value else 0
            renderer.aOutline = if (outline.value) aOutline.value else 0
            updateRenderer(renderer)
            renderer.render(true)
        }
    }

    fun isDone(): Boolean {
        return blockQueue.size == 0
    }

    override fun onEnable() {
        if (mc.player == null) {
            disable()
            return
        }

        startingBlockPos = mc.player.positionVector.toBlockPos()
        currentBlockPos = startingBlockPos
        playerHotbarSlot = mc.player.inventory.currentItem
        lastHotbarSlot = -1
        buildDirectionSaved = getPlayerCardinal(mc.player)

        if (baritoneMode.value) {
            baritoneSettingAllowPlace = BaritoneAPI.getSettings().allowPlace.value
            BaritoneAPI.getSettings().allowPlace.value = false
            if (!goalRender.value) {
                baritoneSettingRenderGoal = BaritoneAPI.getSettings().renderGoal.value
                BaritoneAPI.getSettings().renderGoal.value = false
            }
        }

        playerHotbarSlot = mc.player.inventory.currentItem

        refreshData()
        printEnable()
    }

    override fun onDisable() {
        if (mc.player == null) return

        // load initial player hand
        if (lastHotbarSlot != playerHotbarSlot && playerHotbarSlot != -1) {
            mc.player.inventory.currentItem = playerHotbarSlot
        }
        playerHotbarSlot = -1
        lastHotbarSlot = -1


        if (baritoneMode.value) {
            BaritoneAPI.getSettings().allowPlace.value = baritoneSettingAllowPlace
            if (!goalRender.value) BaritoneAPI.getSettings().renderGoal.value = baritoneSettingRenderGoal
            val baritoneProcess = BaritoneAPI.getProvider().primaryBaritone.pathingControlManager.mostRecentInControl()
            if (baritoneProcess.isPresent && baritoneProcess.get() == HighwayToolsProcess) baritoneProcess.get().onLostControl()
        }
        printDisable()
        totalBlocksPlaced = 0
        totalBlocksDestroyed = 0
    }

    private fun addTask(blockPos: BlockPos, taskState: TaskState, material: Block) {
        blockQueue.add(BlockTask(blockPos, taskState, material))
    }

    private fun addTask(blockPos: BlockPos, material: Block) {
        doneQueue.add(BlockTask(blockPos, TaskState.DONE, material))
    }

    private fun updateTask(blockTask: BlockTask, taskState: TaskState) {
        blockQueue.poll()
        blockTask.taskState = taskState
        if (taskState == TaskState.DONE) {
            doneQueue.add(blockTask)
        } else {
            blockQueue.add(blockTask)
        }
    }

    private fun updateTask(blockTask: BlockTask, material: Block) {
        blockQueue.poll()
        blockTask.block = material
        doneQueue.add(blockTask)
    }

    private fun doTask(): Boolean {
        if (!isDone() && !pathing && !BaritoneUtils.paused && !AutoObsidian.isActive()) {
            if (waitTicks == 0) {
                val blockTask = blockQueue.peek()

                if (printDebug.value) printDebug()
                when (blockTask.taskState) {
                    TaskState.DONE -> doDONE(blockTask)
                    TaskState.BREAKING -> doBREAKING(blockTask)
                    TaskState.PLACED -> doPLACED(blockTask)
                    TaskState.BREAK -> if(!doBREAK(blockTask)) return false
                    TaskState.PLACE, TaskState.LIQUID_SOURCE, TaskState.LIQUID_FLOW -> if(!doPLACE(blockTask)) return false
                }
            } else {
                waitTicks--
            }
            return true
        } else {
            return false
        }
    }

    private fun doDONE(blockTask: BlockTask) {
        blockQueue.poll()
        doneQueue.add(blockTask)
        doTask()
    }

    private fun doBREAKING(blockTask: BlockTask) {
        if (stuckMining > stuckDelay.value) {

            // ToDo: Make this more reliable for high TPS

            stuckMining = 0
            updateTask(blockTask, TaskState.BREAK)
            refreshData()
            shuffleTasks()
            if (debugMessages.value == DebugMessages.IMPORTANT) sendChatMessage("Shuffled because of mining issue.")
        }

        when (mc.world.getBlockState(blockTask.blockPos).block) {
            Blocks.AIR -> {
                stuckMining = 0
                totalBlocksDestroyed++
                waitTicks = tickDelayBreak.value
                if (blockTask.block == material || blockTask.block == fillerMat) {
                    updateTask(blockTask, TaskState.PLACE)
                } else {
                    updateTask(blockTask, TaskState.DONE)
                    doTask()
                }
            }
            Blocks.LAVA -> {
                updateTask(blockTask, TaskState.LIQUID_SOURCE)
                updateTask(blockTask, fillerMat)
            }
            Blocks.FLOWING_LAVA -> {
                updateTask(blockTask, TaskState.LIQUID_FLOW)
                updateTask(blockTask, fillerMat)
            }
            else -> {
                mineBlock(blockTask.blockPos, false)
                stuckMining++
            }
        }
    }

    private fun doPLACED(blockTask: BlockTask) {
        val block = mc.world.getBlockState(blockTask.blockPos).block

        when {
            blockTask.block == block && block != Blocks.AIR -> updateTask(blockTask, TaskState.DONE)
            blockTask.block == Blocks.AIR && block != Blocks.AIR -> updateTask(blockTask, TaskState.BREAK)
            else -> updateTask(blockTask, TaskState.PLACE)
        }
        doTask()
    }

    private fun doBREAK(blockTask: BlockTask): Boolean {
        val block = mc.world.getBlockState(blockTask.blockPos).block

        // ignore blocks
        for (b in ignoreBlocks) {
            if (block == b) {
                updateTask(blockTask, TaskState.DONE)
                doTask()
            }
        }

        // last check before breaking
        when (block) {
            Blocks.AIR -> {
                updateTask(blockTask, TaskState.DONE)
                doTask()
            }
            Blocks.LAVA -> {
                updateTask(blockTask, TaskState.LIQUID_SOURCE)
                updateTask(blockTask, fillerMat)
            }
            Blocks.FLOWING_LAVA -> {
                updateTask(blockTask, TaskState.LIQUID_FLOW)
                updateTask(blockTask, fillerMat)
            }
            else -> {
                // liquid search around the breaking block
                if (liquidHandler(blockTask.blockPos)) return false

                // ToDo: inventory management function

                if (!mineBlock(blockTask.blockPos, true)) {
                    shuffleTasks()
                } else {
                    updateTask(blockTask, TaskState.BREAKING)
                }
            }
        }
        return true
    }

    private fun doPLACE(blockTask: BlockTask): Boolean {
        val block = mc.world.getBlockState(blockTask.blockPos).block

        if (blockTask.block == Blocks.AIR && block !is BlockLiquid) {
            blockQueue.poll()
            return true
        }

        when {
            block == material && block == blockTask.block -> updateTask(blockTask, TaskState.PLACED)
            block == fillerMat && block == blockTask.block -> updateTask(blockTask, TaskState.PLACED)
            else -> {

                // ToDo: inventory management function

                if (!BlockUtils.isPlaceable(blockTask.blockPos)) {
                    if (debugMessages.value != DebugMessages.OFF) sendChatMessage("Error: " + blockTask.blockPos + " is not a valid position to place a block, removing task.")
                    blockQueue.remove(blockTask)
                    return false
                }

                if (!placeBlock(blockTask.blockPos, blockTask.block)) return false
                if (blockTask.taskState != TaskState.PLACE && isInsideBuild(blockTask.blockPos)) updateTask(blockTask, Blocks.AIR)
                updateTask(blockTask, TaskState.PLACED)
                if (blocksPerTick.value > blocksPlaced + 1) {
                    blocksPlaced++
                    doTask()
                } else {
                    blocksPlaced = 0
                }

                waitTicks = tickDelayPlace.value
                totalBlocksPlaced++
            }
        }
        return true
    }


    private fun checkTasks(): Boolean {
        loop@ for (blockTask in doneQueue) {
            val block = mc.world.getBlockState(blockTask.blockPos).block
            for (b in ignoreBlocks) {
                if (b == block) continue@loop
            }
            when {
                blockTask.block == material && block == Blocks.AIR -> return false
                blockTask.block == Blocks.AIR && block != Blocks.AIR -> return false
            }
        }
        return true
    }

    private fun updateTasks() {
        updateBlockArray()
        for ((blockPos, blockType) in blockOffsets) {
            when (val block = mc.world.getBlockState(blockPos).block) {
                Blocks.LAVA, Blocks.WATER -> addTask(blockPos, TaskState.LIQUID_SOURCE, fillerMat)
                Blocks.FLOWING_LAVA, Blocks.FLOWING_WATER -> addTask(blockPos, TaskState.LIQUID_FLOW, fillerMat)
                else -> {
                    when (blockType) {
                        Blocks.AIR -> {
                            when {
                                block in ignoreBlocks -> addTask(blockPos, Blocks.AIR)
                                block == Blocks.AIR -> addTask(blockPos, Blocks.AIR)
                                block != Blocks.AIR -> addTask(blockPos, TaskState.BREAK, Blocks.AIR)
                            }
                        }
                        material -> {
                            when {
                                block == material -> addTask(blockPos, material)
                                block != Blocks.AIR && block != material -> addTask(blockPos, TaskState.BREAK, material)
                                block == Blocks.AIR -> addTask(blockPos, TaskState.PLACE, material)
                            }
                        }
                        fillerMat -> {
                            val blockUp = mc.world.getBlockState(blockPos.up()).block
                            when {
                                getPlaceableSide(blockPos.up()) == null && blockUp != material -> addTask(blockPos, TaskState.PLACE, fillerMat)
                                getPlaceableSide(blockPos.up()) != null -> addTask(blockPos, fillerMat)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun shuffleTasks() {
        var tmpQueue: Queue<BlockTask> = LinkedList<BlockTask>(blockQueue)
        tmpQueue = LinkedList<BlockTask>(tmpQueue.shuffled())
        blockQueue.clear()
        blockQueue.addAll(tmpQueue)
    }

    private fun liquidHandler(blockPos: BlockPos): Boolean {
        var foundLiquid = false
        for (side in EnumFacing.values()) {
            val neighbour = blockPos.offset(side)
            val neighbourBlock = mc.world.getBlockState(neighbour).block

            if (neighbourBlock == Blocks.LAVA || neighbourBlock == Blocks.FLOWING_LAVA) {
                if (sqrt(mc.player.getDistanceSqToCenter(neighbour)) > maxReach.value) continue
                foundLiquid = true
                val found = mutableListOf<Triple<BlockTask, TaskState, Block>>()
                for (bt in blockQueue) {
                    if (bt.blockPos == neighbour) {
                        when (neighbourBlock) {
                            Blocks.LAVA, Blocks.WATER -> found.add(Triple(bt, TaskState.LIQUID_SOURCE, fillerMat))
                            Blocks.FLOWING_LAVA, Blocks.FLOWING_WATER -> found.add(Triple(bt, TaskState.LIQUID_FLOW, fillerMat))
                        }
                    }
                }
                if (found.isEmpty()) {
                    when (neighbourBlock) {
                        Blocks.LAVA, Blocks.WATER -> {
                            addTask(neighbour, TaskState.LIQUID_SOURCE, fillerMat)
                        }
                        Blocks.FLOWING_LAVA, Blocks.FLOWING_WATER -> {
                            addTask(neighbour, TaskState.LIQUID_FLOW, fillerMat)
                        }
                    }
                } else {
                    for (x in found) {
                        updateTask(x.first, x.second)
                        updateTask(x.first, x.third)
                    }
                }
            }
        }
        return foundLiquid
    }

    private fun mineBlock(pos: BlockPos, pre: Boolean): Boolean {
        var rayTrace: RayTraceResult?
        if (pre) {
            if (InventoryUtils.getSlotsHotbar(278) == null && InventoryUtils.getSlotsNoHotbar(278) != null) {
                InventoryUtils.moveToHotbar(278, 130)
                return true
            } else if (InventoryUtils.getSlots(0, 35, 278) == null) {
                sendChatMessage("$chatName No Pickaxe was found in inventory")
                mc.getSoundHandler().playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
                disable()
                return false
            }
            InventoryUtils.swapSlotToItem(278)

            rayTrace = mc.world.rayTraceBlocks(mc.player.getPositionEyes(1f), Vec3d(pos).add(0.5, 0.5, 0.5))
            if (rayTrace == null) {
                refreshData()
                return false
            }
            if (rayTrace.blockPos != pos) {
                var found = false
                for (side in EnumFacing.values()) {
                    if (mc.world.getBlockState(pos.offset(side)).block == Blocks.AIR) {
                        rayTrace = mc.world.rayTraceBlocks(mc.player.getPositionEyes(1f), Vec3d(pos).add(0.5, 0.5, 0.5).add(Vec3d(side.directionVec).scale(0.499)))?: continue
                        if (rayTrace.blockPos == pos) {
                            found = true
                            break
                        }
                    }
                }
                if (!found) {
                    refreshData()
                    shuffleTasks()
                    return false
                }
            }
            lastViewVec = rayTrace
        } else {
            rayTrace = lastViewVec
        }

        val facing = rayTrace?.sideHit ?: return false
        val rotation = RotationUtils.getRotationTo(Vec3d(pos).add(0.5, 0.5, 0.5).add(Vec3d(facing.directionVec).scale(0.499)), true)

        when (interacting.value) {
            InteractMode.SPOOF -> {
                val rotationPacket = CPacketPlayer.PositionRotation(mc.player.posX, mc.player.posY, mc.player.posZ, rotation.x.toFloat(), rotation.y.toFloat(), mc.player.onGround)
                mc.connection!!.sendPacket(rotationPacket)
            }
            InteractMode.VIEWLOCK -> {
                mc.player.rotationYaw = rotation.x.toFloat()
                mc.player.rotationPitch = rotation.y.toFloat()
            }
        }

        Thread {
            Thread.sleep(25L)
            if (pre) {
                mc.connection!!.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.START_DESTROY_BLOCK, pos, facing))
            } else {
                mc.connection!!.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, facing))
            }
            mc.player.swingArm(EnumHand.MAIN_HAND)
        }.start()
        return true
    }

    // Only temporary till we found solution to avoid untraceable blocks
    private fun placeBlockWall(pos: BlockPos, mat: Block): Boolean {
        if (InventoryUtils.getSlotsHotbar(getIdFromBlock(mat)) == null && InventoryUtils.getSlotsNoHotbar(getIdFromBlock(mat)) != null) {
            for (x in InventoryUtils.getSlotsNoHotbar(getIdFromBlock(mat))!!) {
                InventoryUtils.quickMoveSlot(x)
            }
        } else if (InventoryUtils.getSlots(0, 35, getIdFromBlock(mat)) == null) {
            sendChatMessage("$chatName No ${mat.localizedName} was found in inventory")
            mc.getSoundHandler().playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
            disable()
        }
        InventoryUtils.swapSlotToItem(getIdFromBlock(mat))

        val side = getPlaceableSide(pos) ?: return false
        val neighbour = pos.offset(side)
        val hitVec = Vec3d(neighbour).add(0.5, 0.5, 0.5).add(Vec3d(side.opposite.directionVec).scale(0.5))

        val rotation = RotationUtils.getRotationTo(hitVec, true)
        when (interacting.value) {
            InteractMode.SPOOF -> {
                val rotationPacket = CPacketPlayer.PositionRotation(mc.player.posX, mc.player.posY, mc.player.posZ, rotation.x.toFloat(), rotation.y.toFloat(), mc.player.onGround)
                mc.connection!!.sendPacket(rotationPacket)
            }
            InteractMode.VIEWLOCK -> {
                mc.player.rotationYaw = rotation.x.toFloat()
                mc.player.rotationPitch = rotation.y.toFloat()
            }
        }

        Thread{
            Thread.sleep(25L)
            val placePacket = CPacketPlayerTryUseItemOnBlock(neighbour, side.opposite, EnumHand.MAIN_HAND, hitVec.x.toFloat(), hitVec.y.toFloat(), hitVec.z.toFloat())
            mc.connection!!.sendPacket(placePacket)
            mc.player.swingArm(EnumHand.MAIN_HAND)
            if (NoBreakAnimation.isEnabled) NoBreakAnimation.resetMining()
        }.start()
        return true
    }

    private fun placeBlock(pos: BlockPos, mat: Block): Boolean {
        if (InventoryUtils.getSlotsHotbar(getIdFromBlock(mat)) == null && InventoryUtils.getSlotsNoHotbar(getIdFromBlock(mat)) != null) {
            // InventoryUtils.moveToHotbar(getIdFromBlock(mat), 130, (tickDelay.value * 16).toLong())
            for (x in InventoryUtils.getSlotsNoHotbar(getIdFromBlock(mat))!!) {
                InventoryUtils.quickMoveSlot(x)
            }
            // InventoryUtils.quickMoveSlot(1, (tickDelay.value * 16).toLong())
        } else if (InventoryUtils.getSlots(0, 35, getIdFromBlock(mat)) == null) {
            sendChatMessage("$chatName No ${mat.localizedName} was found in inventory")
            mc.getSoundHandler().playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
            disable()
        }
        InventoryUtils.swapSlotToItem(getIdFromBlock(mat))

        val rayTraces = mutableListOf<RayTraceResult>()
        for (side in EnumFacing.values()) {
            val offPos = pos.offset(side)
            if (mc.world.getBlockState(offPos).material.isReplaceable) continue
            if (mc.player.getPositionEyes(1f).distanceTo(Vec3d(offPos).add(BlockUtils.getHitVecOffset(side))) > maxReach.value) continue
            val rotationVector = Vec3d(offPos).add(0.5, 0.5, 0.5).add(Vec3d(side.opposite.directionVec).scale(0.499))
            val rt = mc.world.rayTraceBlocks(mc.player.getPositionEyes(1f), rotationVector)?: continue
            if (rt.typeOfHit != RayTraceResult.Type.BLOCK) continue
            if (rt.blockPos == offPos && offPos.offset(rt.sideHit) == pos) {
                rayTraces.add(rt)
            }
        }
        if (rayTraces.size == 0) {
            if(debugMessages.value == DebugMessages.ALL) sendChatMessage("Trying to place through wall $pos")
            return placeBlockWall(pos, mat)
        }

        var rayTrace: RayTraceResult? = null
        var shortestRT = 99.0
        for (rt in rayTraces) {
            if (mc.player.getPositionEyes(1f).distanceTo(Vec3d(rt.blockPos).add(BlockUtils.getHitVecOffset(rt.sideHit))) < shortestRT) {
                shortestRT = mc.player.getPositionEyes(1f).distanceTo(Vec3d(rt.blockPos).add(BlockUtils.getHitVecOffset(rt.sideHit)))
                rayTrace = rt
            }
        }
        if (rayTrace == null) {
            sendChatMessage("Can't find any vector?")
            return false
        }

        val hitVecOffset = rayTrace.hitVec
        val rotation = RotationUtils.getRotationTo(hitVecOffset, true)
        when (interacting.value) {
            InteractMode.SPOOF -> {
                val rotationPacket = CPacketPlayer.PositionRotation(mc.player.posX, mc.player.posY, mc.player.posZ, rotation.x.toFloat(), rotation.y.toFloat(), mc.player.onGround)
                mc.connection!!.sendPacket(rotationPacket)
            }
            InteractMode.VIEWLOCK -> {
                mc.player.rotationYaw = rotation.x.toFloat()
                mc.player.rotationPitch = rotation.y.toFloat()
            }
        }

        Thread{
            Thread.sleep(25L)
            val placePacket = CPacketPlayerTryUseItemOnBlock(rayTrace.blockPos, rayTrace.sideHit, EnumHand.MAIN_HAND, hitVecOffset.x.toFloat(), hitVecOffset.y.toFloat(), hitVecOffset.z.toFloat())
            mc.connection!!.sendPacket(placePacket)
            mc.player.swingArm(EnumHand.MAIN_HAND)
            if (NoBreakAnimation.isEnabled) NoBreakAnimation.resetMining()
        }.start()
        return true
    }

    private fun getPlaceableSide(pos: BlockPos): EnumFacing? {
        for (side in EnumFacing.values()) {
            val neighbour = pos.offset(side)
            if (!mc.world.getBlockState(neighbour).block.canCollideCheck(mc.world.getBlockState(neighbour), false)) continue
            val blockState = mc.world.getBlockState(neighbour)
            if (!blockState.material.isReplaceable) return side
        }
        return null
    }

    private fun isInsideBuild(blockPos: BlockPos): Boolean {
        for (bt in blockQueue) {
            if (bt.blockPos == blockPos) return true
        }
        return false
    }

    private fun centerPlayer(): Boolean {
        return if (autoCenter.value == Surround.AutoCenterMode.OFF) {
            true
        } else {
            SurroundUtils.centerPlayer(autoCenter.value == Surround.AutoCenterMode.TP)
        }
    }

    private fun updateRenderer(renderer: ESPRenderer): ESPRenderer {
        val side = GeometryMasks.Quad.ALL
        for (blockTask in blockQueue) {
            if (blockTask.taskState != TaskState.DONE) renderer.add(blockTask.blockPos, blockTask.taskState.color, side)
        }
        for (blockTask in doneQueue) {
            if (blockTask.block != Blocks.AIR) renderer.add(blockTask.blockPos, blockTask.taskState.color, side)
        }
        return renderer
    }

    private fun printDebug() {
        var message = "\n\n"
        message += "-------------------- QUEUE -------------------"
        for (blockTask in blockQueue) message += "\n" + blockTask.block.localizedName + "@(" + blockTask.blockPos.asString() + ") Priority: " + blockTask.taskState.ordinal + " State: " + blockTask.taskState.toString()
        message += "\n-------------------- DONE --------------------"
        for (blockTask in doneQueue) message += "\n" + blockTask.block.localizedName + "@(" + blockTask.blockPos.asString() + ") Priority: " + blockTask.taskState.ordinal + " State: " + blockTask.taskState.toString()
        sendChatMessage(message)
    }

    fun printSettings() {
        var message = "$chatName Settings" +
                "\n    §9> §rMaterial: §7${material.localizedName}" +
                "\n    §9> §rBaritone: §7${baritoneMode.value}" +
                "\n    §9> §rIgnored Blocks:"
        for (b in ignoreBlocks) message += "\n        §9> §7${b!!.registryName}"
        sendChatMessage(message)
    }

    private fun printEnable() {
        var message = ""
        if (info.value) {
            message += "$chatName Module started." +
                    "\n    §9> §7Direction: §a${buildDirectionSaved.cardinalName}§r"

            message += if (buildDirectionSaved.isDiagonal) {
                "\n    §9> §7Coordinates: §a${startingBlockPos.x} ${startingBlockPos.z}§r"
            } else {
                if (buildDirectionSaved == Cardinal.NEG_Z || buildDirectionSaved == Cardinal.POS_Z) {
                    "\n    §9> §7Coordinate: §a${startingBlockPos.x}§r"
                } else {
                    "\n    §9> §7Coordinate: §a${startingBlockPos.z}§r"
                }
            }
        } else {
            message += "$chatName Module started."
        }
        sendChatMessage(message)
    }

    private fun printDisable() {
        var message = ""
        if (info.value) {
            message += "$chatName Module stopped." +
                    "\n    §9> §7Placed blocks: §a$totalBlocksPlaced§r" +
                    "\n    §9> §7Destroyed blocks: §a$totalBlocksDestroyed§r"
            if (baritoneMode.value) message += "\n    §9> §7Distance: §a${getDistance(startingBlockPos.toVec3d(), currentBlockPos.toVec3d()).toInt()}§r"
        } else {
            message += "$chatName Module stopped."
        }
        sendChatMessage(message)
    }

    fun getNextBlock(): BlockPos {
        return when (buildDirectionSaved) {
            Cardinal.NEG_Z -> currentBlockPos.north()
            Cardinal.POS_X_NEG_Z -> currentBlockPos.north().east()
            Cardinal.POS_X -> currentBlockPos.east()
            Cardinal.POS_X_POS_Z -> currentBlockPos.south().east()
            Cardinal.POS_Z -> currentBlockPos.south()
            Cardinal.NEG_X_POS_Z -> currentBlockPos.south().west()
            Cardinal.NEG_X -> currentBlockPos.west()
            else -> currentBlockPos.north().west()
        }
    }

    private fun relativeDirection(curs: BlockPos, steps: Int, turn: Int): BlockPos {
        var c = curs
        var d = (buildDirectionSaved.ordinal + turn).rem(8)
        if (d < 0) d += 8
        when (d) {
            0 -> c = c.north(steps)
            1 -> c = c.north(steps).east(steps)
            2 -> c = c.east(steps)
            3 -> c = c.south(steps).east(steps)
            4 -> c = c.south(steps)
            5 -> c = c.south(steps).west(steps)
            6 -> c = c.west(steps)
            7 -> c = c.north(steps).west(steps)
        }
        return c
    }

    private fun addOffset(cursor: BlockPos, height: Int, width: Int, mat: Block, turn: Boolean) {
        var turnValue = 1
        if (turn) turnValue = -1
        if (mat != fillerMat) {
            if (height > 1) {
                blockOffsets.add(Pair(relativeDirection(relativeDirection(cursor, 1, 3 * turnValue), width - 1, 2 * turnValue), Blocks.AIR))
            } else {
                blockOffsets.add(Pair(relativeDirection(relativeDirection(cursor, 1, 3 * turnValue), width - 1, 2 * turnValue), mat))
            }
        } else {
            blockOffsets.add(Pair(relativeDirection(relativeDirection(cursor, 1, 3 * turnValue), width - 1, 2 * turnValue), material))
        }
    }

    private fun genOffset(cursor: BlockPos, height: Int, width: Int, mat: Block, isOdd: Boolean) {
        blockOffsets.add(Pair(relativeDirection(cursor, width, -2), mat))
        if (buildDirectionSaved.isDiagonal) {
            addOffset(cursor, height, width, mat, true)
        }
        when {
            isOdd -> {
                blockOffsets.add(Pair(relativeDirection(cursor, width, 2), mat))
                if (buildDirectionSaved.isDiagonal) {
                    addOffset(cursor, height, width, mat, false)
                }
            }
            else -> {
                val evenCursor = relativeDirection(cursor, 1, 2)
                if (buildDirectionSaved.isDiagonal) {
                    blockOffsets.add(Pair(relativeDirection(evenCursor, width, 2), mat))
                    addOffset(cursor, height, width, mat, false)
                    addOffset(evenCursor, height, width, mat, false)
                } else {
                    blockOffsets.add(Pair(relativeDirection(evenCursor, width, 2), mat))
                }
            }
        }
    }

    private fun refreshData() {
        doneQueue.clear()
        blockQueue.clear()
        updateTasks()
        shuffleTasks()
    }

    private fun updateBlockArray() {
        blockOffsets.clear()
        var cursor = currentBlockPos.down()

        when (mode.value) {
            Mode.HIGHWAY -> {
                if (baritoneMode.value) {
                    cursor = relativeDirection(cursor, 1, 0)
                    blockOffsets.add(Pair(cursor, material))
                }
                cursor = relativeDirection(cursor, 1, 0)
                blockOffsets.add(Pair(cursor, material))
                var buildIterationsWidth = buildWidth.value / 2
                var evenCursor = relativeDirection(cursor, 1, 2)
                var isOdd = false
                if (buildWidth.value % 2 == 1) {
                    isOdd = true
                    buildIterationsWidth++
                } else {
                    blockOffsets.add(Pair(evenCursor, material))
                }
                for (i in 1 until clearHeight.value + 1) {
                    for (j in 1 until buildIterationsWidth) {
                        if (i == 1) {
                            if (j == buildIterationsWidth - 1 && !cornerBlock.value) {
                                genOffset(cursor, i, j, fillerMat, isOdd)
                            } else {
                                genOffset(cursor, i, j, material, isOdd)
                            }
                        } else {
                            if (i <= railingHeight.value + 1 && j == buildIterationsWidth - 1) {
                                genOffset(cursor, i, j, material, isOdd)
                            } else {
                                if (clearSpace.value) {
                                    genOffset(cursor, i, j, Blocks.AIR, isOdd)
                                }
                            }
                        }
                    }
                    cursor = cursor.up()
                    evenCursor = evenCursor.up()
                    if (clearSpace.value && i < clearHeight.value) {
                        blockOffsets.add(Pair(cursor, Blocks.AIR))
                        if (!isOdd) blockOffsets.add(Pair(evenCursor, Blocks.AIR))
                    }
                }
            }
            Mode.FLAT -> {
                for (bp in VectorUtils.getBlockPositionsInArea(cursor.north(buildWidth.value).west(buildWidth.value), cursor.south(buildWidth.value).east(buildWidth.value))) {
                    blockOffsets.add(Pair(bp, material))
                }
            }
            null -> {
                sendChatMessage("Module logic is a lie.")
                disable()
            }
        }
    }

    private enum class DebugMessages {
        OFF,
        IMPORTANT,
        ALL
    }

    private enum class Mode {
        HIGHWAY,
        FLAT
    }

    private enum class Page {
        BUILD,
        BEHAVIOR,
        CONFIG
    }

    private enum class InteractMode {
        OFF,
        SPOOF,
        VIEWLOCK
    }

    enum class AutoCenterMode {
        OFF,
        TP,
        MOTION
    }

    data class BlockTask(val blockPos: BlockPos, var taskState: TaskState, var block: Block)

    enum class TaskState(val color: ColorHolder) {
        DONE(ColorHolder(50, 50, 50)),
        BREAKING(ColorHolder(240, 222, 60)),
        PLACED(ColorHolder(53, 222, 66)),
        LIQUID_SOURCE(ColorHolder(120, 41, 240)),
        LIQUID_FLOW(ColorHolder(120, 41, 240)),
        BREAK(ColorHolder(222, 0, 0)),
        PLACE(ColorHolder(35, 188, 254))
    }
}
