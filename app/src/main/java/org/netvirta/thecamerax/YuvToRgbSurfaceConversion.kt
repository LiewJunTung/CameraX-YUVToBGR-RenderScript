package org.netvirta.thecamerax

import android.graphics.ImageFormat
import android.renderscript.*
import android.util.Size
import android.view.Surface
import org.netvirta.thecamerax.rs.ScriptC_rotator
import java.nio.ByteBuffer

class YuvToRgbSurfaceConversion(rs: RenderScript, private val imageSize: Size) :
    Allocation.OnBufferAvailableListener {
    private var mInputAllocation: Allocation? = null
    private var mOutputAllocation: Allocation? = null
    private var mRotatedAllocation: Allocation? = null

    private val yuvToRGB: ScriptIntrinsicYuvToRGB
    private val TAG = YuvToRgbSurfaceConversion::class.java.simpleName
    private val rotator: ScriptC_rotator = ScriptC_rotator(rs)

    val inputSurface: Surface
        get() = mInputAllocation!!.surface

    init {

        createAllocations(rs)

        mInputAllocation!!.setOnBufferAvailableListener(this)

        yuvToRGB = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
    }

    private fun createAllocations(rs: RenderScript) {

        val width = imageSize.width
        val height = imageSize.height

        val yuvTypeBuilder = Type.Builder(rs, Element.U8_4(rs))
        yuvTypeBuilder.setX(width)
        yuvTypeBuilder.setY(height)

        yuvTypeBuilder.setYuvFormat(ImageFormat.YUV_420_888)

        mInputAllocation = Allocation.createTyped(
            rs, yuvTypeBuilder.create(),
            Allocation.USAGE_IO_INPUT or Allocation.USAGE_SCRIPT
        )

        val rgbType = Type.createXY(rs, Element.RGBA_8888(rs), width, height)

        mOutputAllocation = Allocation.createTyped(rs, rgbType, Allocation.USAGE_SCRIPT)

        val rotatedType = Type.createXY(rs, Element.RGBA_8888(rs), height, width)
        mRotatedAllocation = Allocation.createTyped(
            rs, rotatedType,
            Allocation.USAGE_IO_OUTPUT or Allocation.USAGE_SCRIPT
        )

    }

    fun setOutputSurface(output: Surface) {
        mRotatedAllocation!!.surface = output
    }

    override fun onBufferAvailable(a: Allocation) {
        // Get the new frame into the input allocation
        a.ioReceive()
        println("Wm ${imageSize.width} Hm ${imageSize.height}")
        yuvToRGB.setInput(a)
        yuvToRGB.forEach(mOutputAllocation)
        rotator._inImage = mOutputAllocation
        rotator._inWidth = imageSize.width
        rotator._inHeight = imageSize.height
        rotator.forEach_rotate_270_clockwise(mRotatedAllocation, mRotatedAllocation)

        mRotatedAllocation!!.ioSend()
    }
}

class YuvToRgbBufferConversion(val rs: RenderScript) {

    private var rotateAllocation: Allocation? = null
    private var mOutputAllocation: Allocation? = null
    private var mInputAllocation: Allocation? = null

    val rsYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
    val rotator: ScriptC_rotator = ScriptC_rotator(rs)

    var yArray: ByteArray? = null
    var uArray: ByteArray? = null
    var vArray: ByteArray? = null

    private fun ByteBuffer.initByteArray(): ByteArray {
        return ByteArray(capacity())
    }


    fun yuvToRgb(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rotation: Int
    ): ByteBuffer {
        val currentTimestamp = System.currentTimeMillis()
        println("Width $width Height $height")
        if (mInputAllocation == null) {
            val totalSize = yBuffer.capacity() + uBuffer.capacity() + vBuffer.capacity()
            val yuvType = Type.Builder(rs, Element.U8(rs)).apply {
                setX(totalSize)
            }
            mInputAllocation = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT)
        }
        if (mOutputAllocation == null) {
            val rgbType = Type.createXY(rs, Element.RGBA_8888(rs), width, height)
            mOutputAllocation = Allocation.createTyped(rs, rgbType, Allocation.USAGE_SCRIPT)
        }

        if (rotateAllocation == null) {
            val rotatorType =
                if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                    Type.createXY(rs, Element.RGBA_8888(rs), height, width)
                } else {
                    Type.createXY(rs, Element.RGBA_8888(rs), width, height)
                }
            rotateAllocation = Allocation.createTyped(rs, rotatorType, Allocation.USAGE_SCRIPT)
        } else {
            rotateAllocation!!.byteBuffer.clear()
//            rotateAllocation!!.byteBuffer.position(0)
        }

        if (yArray == null) {
            yArray = yBuffer.initByteArray()
        }
        if (uArray == null) {
            uArray = uBuffer.initByteArray()
        }
        if (vArray == null) {
            vArray = vBuffer.initByteArray()
        }
        val yuvArray = ByteArray(yBuffer.capacity() + uBuffer.capacity() + vBuffer.capacity())

        yBuffer.get(yArray)
        uBuffer.get(uArray)
        vBuffer.get(vArray)
        System.arraycopy(yArray!!, 0, yuvArray, 0, yArray!!.size)
        System.arraycopy(uArray!!, 0, yuvArray, yArray!!.size, uArray!!.size)
        System.arraycopy(vArray!!, 0, yuvArray, yArray!!.size + uArray!!.size, vArray!!.size)

        println("TOTAL SIZE " + (yBuffer.capacity() + uBuffer.capacity() + vBuffer.capacity()))
        println("YUV SIZE" + yuvArray.size)
        mInputAllocation!!.copyFrom(yuvArray)

//        mInputAllocation.copyFrom(yuvBuffer.array())
        rsYuvToRgb.setInput(mInputAllocation)
        rsYuvToRgb.forEach(mOutputAllocation)
        return if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            rotator._inWidth = width
            rotator._inHeight = height
            rotator._inImage = mOutputAllocation

            rotator.forEach_rotate_90_counterclockwise(rotateAllocation, rotateAllocation)
            val lastAnalyzedTimestamp = System.currentTimeMillis()
            val perf = lastAnalyzedTimestamp - currentTimestamp
            println("PERFORMANCE : $perf ms")
            rotateAllocation!!.byteBuffer
        } else {
            mOutputAllocation!!.byteBuffer
        }

    }
}
