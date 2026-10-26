package moe.chenxy.fiilpods.pods

/** FIIL GAIA 命令号（与 com.fiil.sdk.gaia.father.Gaia 的常量一一对应，已逐条核对行号）。 */
object FiilCommands {

    // ---- 查询类（耳机 → App）----
    const val GET_VERSION = 769            // 0x0301
    const val GET_ADDRESS = 770            // 0x0302
    const val GET_BATTERY_LEVEL = 771      // 0x0303
    const val GET_SEQCODE = 772            // 0x0304
    const val GET_USETIME = 773            // 0x0305
    const val GET_BATTERY_CHARGE = 774     // 0x0306
    const val GET_EARTYPE = 777            // 0x0309
    const val GET_ANC = 641                // 0x0281
    const val GET_MAF = 650                // 0x028A
    const val GET_EQ = 643                 // 0x0283
    const val GET_3D = 644                 // 0x0284
    const val GET_WEAR = 391               // 0x0187
    const val GET_WEARING_STATE = 1912     // 0x0778
    const val GET_WEARING_SWITCH = 1917    // 0x077D
    const val GET_LOW_LATENCY = 1825       // 0x0721
    const val GET_MULTIPOINT = 386         // 0x0182
    const val GET_MULTIPOINT_CONN = 1953   // 0x07A1
    const val GET_STATUS = 16394           // 0x400A
    const val GET_CONNECTION_TYPE = 1955   // 0x07A3
    const val GET_CODING_TYPE = 1954       // 0x07A2
    const val GET_BATTERY_OF_BOX = 1915    // 0x077B
    const val GET_TWS_VERSION = 1819       // 0x071B

    // ---- 设置类（App → 耳机）----
    const val SET_ANC = 513                // 0x0201
    const val SET_EQ = 515                 // 0x0203
    const val SET_3D = 516                 // 0x0204
    const val SET_MAF = 522                // 0x020A
    const val SET_WEAR = 263               // 0x0107
    const val SET_MULTIPOINT = 258         // 0x0102
    const val SET_VOLUME = 262             // 0x0106
    const val UP_VOLUME = 517              // 0x0205
    const val DOWN_VOLUME = 518            // 0x0206
    const val SET_SEARCH = 265             // 0x0109 寻找耳机
    const val SET_TIME = 4104              // 0x1008
    const val SET_LANGUAGE = 260           // 0x0104
    const val SET_BRIGHT = 261             // 0x0105
    const val SET_SHUTDOWNTIME = 259       // 0x0103
    const val SET_RESET = 521              // 0x0209
    const val RESTORE_FACTORY = 16398      // 0x400E

    // ---- 播放控制（部分老机型）----
    const val PALY = 8193                  // 0x2001
    const val PAUSE = 8194                 // 0x2002
    const val NEXT = 8195                  // 0x2003
    const val PREVIOUS = 8196              // 0x2004

    // ---- 新协议（0x07xx）----
    const val GET_BATTERY_LEVEL_V2 = 1800  // 0x0708
    const val GET_EQ_V2 = 1803             // 0x070B
    const val SET_EQ_V2 = 1804             // 0x070C
    const val SET_MAF_V2 = 1824            // 0x0720
    const val GET_MAF_V2 = 1823            // 0x071F
    const val SET_LOW_LATENCY = 1826       // 0x0722
    const val SET_MULTIPOINT_CONN = 1952   // 0x07A0
}
