package com.chattriggers.ctjs.minecraft.wrappers.entity

import com.chattriggers.ctjs.minecraft.wrappers.CTWrapper
import com.chattriggers.ctjs.minecraft.wrappers.world.block.Block
import com.chattriggers.ctjs.minecraft.wrappers.world.block.BlockPos
import com.chattriggers.ctjs.minecraft.wrappers.world.block.BlockType
import com.chattriggers.ctjs.utils.MCBlockEntity
import net.minecraft.block.entity.BlockEntityType

class BlockEntity(override val mcValue: MCBlockEntity) : CTWrapper<MCBlockEntity> {

    fun getX(): Int = getBlockPos().x

    fun getY(): Int = getBlockPos().y

    fun getZ(): Int = getBlockPos().z

    fun getBlockType(): BlockType = BlockType(BlockEntityType.getId(mcValue.type)!!.toString())

    fun getBlockPos(): BlockPos = BlockPos(mcValue.pos)

    fun getBlock(): Block = Block(getBlockType(), getBlockPos())

    override fun toString(): String {
        val coordStrings = listOf(getX(), getY(), getZ()).map { "%.3f".format(it) }
        return "BlockEntity(type=${getBlockType()}, pos=[${coordStrings.joinToString()}])"
    }
}
