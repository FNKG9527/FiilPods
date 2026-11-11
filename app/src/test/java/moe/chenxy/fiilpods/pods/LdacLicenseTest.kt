package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LDAC 授权码（license）的构造、解析与组帧测试。
 *
 * 这些用例保护的是 v3.4.3 新增的写授权码链路。它比普通开关危险：
 * **固件会校验内容，写错会清掉耳机已有的解锁状态且不可恢复**（真机实测）。
 * 所以「首字节是耳别、长度 17、内容 16 字节、组帧字节与官方一致」这几条
 * 必须逐字节钉死，不能靠函数名自我保证。
 */
class LdacLicenseTest {

    /** 官方单耳授权码形态：32 位小写 hex。 */
    private val sampleHex = "4250323632303512003b51450620bb78"

    /** 真机读到的 Key Max UUID（左右相同，16 字节）。由 [sampleHex] 解出，避免手写字节表出错。 */
    private val sampleUuid: ByteArray =
        sampleHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ---------------------------------------------------------------- build

    /**
     * license 必须是 17 字节，首字节是耳别，后 16 字节是授权内容。
     *
     * 依据：`F046UnlockLDACActivity.java:106-117`（`bArr[0] = 0` + arraycopy 到 1）、
     * `UnlockLDACActivity.java:213-218`（左 0 / 右 1）。
     */
    @Test
    fun buildPutsEarByteFirstAndContentAfter() {
        val left = FiilMate.License.build(sampleUuid, FiilMate.License.EAR_LEFT)
        val right = FiilMate.License.build(sampleUuid, FiilMate.License.EAR_RIGHT)

        assertEquals("license 长度必须是 17", 17, left.size)
        assertEquals("左耳首字节必须是 0x00", 0, left[0].toInt())
        assertEquals("右耳首字节必须是 0x01", 1, right[0].toInt())

        // 后 16 字节原样拷贝，一个 bit 都不能改
        assertArrayEquals(sampleUuid, left.copyOfRange(1, 17))
        assertArrayEquals(sampleUuid, right.copyOfRange(1, 17))

        // 左右只差首字节
        assertArrayEquals(left.copyOfRange(1, 17), right.copyOfRange(1, 17))
    }

    /** 默认耳别是左耳（F046 只有这一种写法）。 */
    @Test
    fun buildDefaultsToLeftEar() {
        val default = FiilMate.License.build(sampleUuid)
        assertArrayEquals(FiilMate.License.build(sampleUuid, FiilMate.License.EAR_LEFT), default)
    }

    /** 内容长度不是 16 必须拒绝，不能静默截断/补零 —— 写错会清掉授权。 */
    @Test
    fun buildRejectsWrongContentLength() {
        assertThrows(IllegalArgumentException::class.java) {
            FiilMate.License.build(ByteArray(15))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FiilMate.License.build(ByteArray(17))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FiilMate.License.build(ByteArray(0))
        }
    }

    /** 官方「取消激活」原语：17 个 0（`F046HomeFragment.java:94-95`）。 */
    @Test
    fun zeroLeftIsSeventeenZeroBytes() {
        val zero = FiilMate.License.zeroLeft()
        assertEquals(17, zero.size)
        assertTrue("全零 license 必须全为 0", zero.all { it.toInt() == 0 })
    }

    /** 右耳的取消形态：首字节 01，其余全零（`CcPro2NewEleFragment.java:645-647`）。 */
    @Test
    fun zeroRightHasEarByteOne() {
        val zero = FiilMate.License.zeroRight()
        assertEquals(17, zero.size)
        assertEquals(1, zero[0].toInt())
        assertTrue("右耳取消形态只有首字节非零", zero.copyOfRange(1, 17).all { it.toInt() == 0 })
    }

    // ---------------------------------------------------------------- parse

    /** 单段 32 hex → 一个 16 字节块（F046 的用法）。 */
    @Test
    fun parseSingleSegment() {
        val blocks = FiilMate.License.parseActivateCode(sampleHex)
        assertEquals(1, blocks.size)
        assertArrayEquals(sampleUuid, blocks[0])
    }

    /** 双段逗号分隔 → 两个 16 字节块（F048 的用法）。 */
    @Test
    fun parseTwoSegments() {
        val second = "00".repeat(16)
        val blocks = FiilMate.License.parseActivateCode("$sampleHex,$second")
        assertEquals(2, blocks.size)
        assertArrayEquals(sampleUuid, blocks[0])
        assertArrayEquals(ByteArray(16), blocks[1])
    }

    /** 大写 hex、空白、用空格代替逗号都要能吃下（用户从各处复制粘贴）。 */
    @Test
    fun parseIsLenientAboutCaseAndWhitespace() {
        val upper = sampleHex.uppercase()
        assertArrayEquals(sampleUuid, FiilMate.License.parseActivateCode(upper)[0])

        // 前后空白
        assertArrayEquals(sampleUuid, FiilMate.License.parseActivateCode("  $sampleHex  ")[0])

        // 空格分隔两段
        val blocks = FiilMate.License.parseActivateCode("$sampleHex ${"00".repeat(16)}")
        assertEquals(2, blocks.size)

        // 逗号 + 空格
        val blocks2 = FiilMate.License.parseActivateCode("$sampleHex, ${"00".repeat(16)}")
        assertEquals(2, blocks2.size)
    }

    /** 段数不对必须报错，不能「有多少算多少」—— 少写一段在 F048 上会被当成右耳。 */
    @Test
    fun parseRejectsWrongSegmentCount() {
        assertThrows(IllegalArgumentException::class.java) {
            FiilMate.License.parseActivateCode("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FiilMate.License.parseActivateCode("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FiilMate.License.parseActivateCode("$sampleHex,$sampleHex,$sampleHex")
        }
    }

    /** 长度不是 32 位必须报错（少一位是最常见的粘贴事故）。 */
    @Test
    fun parseRejectsWrongLength() {
        assertThrows(IllegalArgumentException::class.java) {
            FiilMate.License.parseActivateCode(sampleHex.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FiilMate.License.parseActivateCode("${sampleHex}00")
        }
    }

    /** 非 hex 字符必须报错，不能当成 0 悄悄写进去。 */
    @Test
    fun parseRejectsNonHexCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            FiilMate.License.parseActivateCode("zz" + sampleHex.drop(2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FiilMate.License.parseActivateCode("g" + sampleHex.drop(1))
        }
    }

    /** 解析 → 组装必须能回到原始字节序列（解析与组装的连接点）。 */
    @Test
    fun parseThenBuildRoundTrips() {
        val block = FiilMate.License.parseActivateCode(sampleHex).single()
        val license = FiilMate.License.build(block, FiilMate.License.EAR_LEFT)
        assertEquals(17, license.size)
        assertEquals(sampleHex, FiilMate.License.contentToHex(license.copyOfRange(1, 17)))
    }

    /** contentToHex 必须是小写无分隔（与官方 `formatHexString` 的形态一致）。 */
    @Test
    fun contentToHexIsLowercaseNoSeparator() {
        val hex = FiilMate.License.contentToHex(sampleUuid)
        assertEquals(32, hex.length)
        assertEquals(sampleHex, hex)
        assertFalse("不应含分隔符", hex.contains("-") || hex.contains(" "))
        assertEquals("必须是小写", hex, hex.lowercase())
    }

    // ---------------------------------------------------------------- frame

    /**
     * 组帧字节必须与官方 `writeMate` / 真机实测完全一致。
     *
     * 真机实测（fiil-ldac-unlock v1.1.0 证据日志）发出的写授权帧为
     * `01 01 01 00 13 0C 11 <17 字节>`：帧长字段 0x13 = 17 + 2、cmdId 0x0C = 12、
     * payload 长度 0x11 = 17。
     */
    @Test
    fun licenseFrameMatchesRealDeviceBytes() {
        val license = FiilMate.License.build(sampleUuid, FiilMate.License.EAR_LEFT)
        val frame = FiilMate.buildFrame(
            FiilMate.Cid.SET_PARAM,
            FiilMate.Param.LDAC_ACTIVATE,
            license,
        )

        // 头 7 字节
        assertEquals(0x00, frame[0].toInt())
        assertEquals("cid 必须是 SET_PARAM(1)", 1, frame[1].toInt())
        assertEquals(0x01, frame[2].toInt())
        assertEquals(0x00, frame[3].toInt())
        assertEquals("帧长字段 = payload + 2", 0x13, frame[4].toInt())
        assertEquals("cmdId 必须是 LDAC_ACTIVATE(12)", 12, frame[5].toInt())
        assertEquals("payload 长度必须是 17", 0x11, frame[6].toInt())

        // 总长 = 7 + 17
        assertEquals(24, frame.size)
        assertArrayEquals(license, frame.copyOfRange(7, 24))
    }

    /** 常量本身不能被随手改动 —— 它们直接决定写进耳机的哪条命令。 */
    @Test
    fun licenseConstantsArePinned() {
        assertEquals("license 长度", 17, FiilMate.License.LEN)
        assertEquals("左耳前缀", 0, FiilMate.License.EAR_LEFT.toInt())
        assertEquals("右耳前缀", 1, FiilMate.License.EAR_RIGHT.toInt())
        assertEquals("写授权命令号", 12, FiilMate.Param.LDAC_ACTIVATE)
        assertEquals("SET_PARAM 分类号", 1, FiilMate.Cid.SET_PARAM)

        // 两个有 setActivationLdac 的机型族
        assertEquals(46, FiilMate.License.EAR_TYPE_F046)
        assertEquals(48, FiilMate.License.EAR_TYPE_F048)
    }

    // ------------------------------------------------------------ 机型门控

    /**
     * 能写授权码的机型必须**窄于**能开 LDAC 的机型。
     *
     * 只有 F046(`c.java`) 与 F048(`e.java`) 的协议栈里有 `setActivationLdac`，
     * b/d/f/g/h 五个类没有该方法 —— 对它们发 cmdId 12 是发给不存在的命令。
     */
    @Test
    fun licenseCapabilityIsNarrowerThanLdacSupport() {
        assertTrue(FiilModels.supportsLdacLicense(46))
        assertTrue(FiilModels.supportsLdacLicense(48))

        // 能开 LDAC 但不能写授权码的机型：必须为 false
        for (t in listOf(39, 49, 51, 53)) {
            assertTrue("earType=$t 应该能开 LDAC", FiilModels.supportsLdac(t))
            assertFalse("earType=$t 协议栈里没有 setActivationLdac", FiilModels.supportsLdacLicense(t))
        }

        // 完全不支持的机型
        for (t in listOf(45, 47, 1, 28, 42, -1, 0, 9999)) {
            assertFalse("earType=$t 不应支持写授权码", FiilModels.supportsLdacLicense(t))
        }

        // 反向包含关系：能写授权码的一定能开 LDAC
        for (t in listOf(46, 48)) {
            assertTrue("earType=$t 能写授权码就必须能开 LDAC", FiilModels.supportsLdac(t))
        }
    }
}
