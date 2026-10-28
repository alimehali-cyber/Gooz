package com.zig.gargantua.renderer

/**
 * Temporary uniform spatial sampling modes for the Gargantua ray pass.
 *
 * A block size of N shades one ray pixel for each N by N block in the internal target. The
 * resulting ray texture is later upsampled into the unchanged internal HDR target. This class is
 * deliberately free of rendering or physics code so the startup default remains block size 1.
 */
object GargantuaCoarseSampling {
    const val BASELINE_BLOCK_SIZE = 1
    const val COARSE_2X2_BLOCK_SIZE = 2
    const val COARSE_3X3_BLOCK_SIZE = 3
    const val COARSE_4X4_BLOCK_SIZE = 4
    const val COARSE_6X6_BLOCK_SIZE = 6
    const val COARSE_8X8_BLOCK_SIZE = 8

    private val SUPPORTED_BLOCK_SIZES = intArrayOf(
        BASELINE_BLOCK_SIZE,
        COARSE_2X2_BLOCK_SIZE,
        COARSE_3X3_BLOCK_SIZE,
        COARSE_4X4_BLOCK_SIZE,
        COARSE_6X6_BLOCK_SIZE,
        COARSE_8X8_BLOCK_SIZE
    )

    fun supportedBlockSizes(): List<Int> = SUPPORTED_BLOCK_SIZES.toList()

    fun sanitizeBlockSize(requested: Int): Int =
        SUPPORTED_BLOCK_SIZES.firstOrNull { it == requested } ?: BASELINE_BLOCK_SIZE

    data class Grid(
        val internalWidth: Int,
        val internalHeight: Int,
        val blockSize: Int,
        val rayWidth: Int,
        val rayHeight: Int
    ) {
        val internalPixels: Long get() = internalWidth.toLong() * internalHeight.toLong()
        val shadedBlocks: Long get() = rayWidth.toLong() * rayHeight.toLong()
        val primaryRayReductionVsBaseline: Float
            get() = if (shadedBlocks > 0L) internalPixels.toFloat() / shadedBlocks.toFloat() else 1f
    }

    fun grid(internalWidth: Int, internalHeight: Int, requestedBlockSize: Int): Grid {
        val width = internalWidth.coerceAtLeast(1)
        val height = internalHeight.coerceAtLeast(1)
        val block = sanitizeBlockSize(requestedBlockSize)
        return Grid(
            internalWidth = width,
            internalHeight = height,
            blockSize = block,
            rayWidth = (width + block - 1) / block,
            rayHeight = (height + block - 1) / block
        )
    }
}
